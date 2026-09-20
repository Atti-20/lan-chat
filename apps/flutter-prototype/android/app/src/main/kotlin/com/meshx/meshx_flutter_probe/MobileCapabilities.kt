package com.meshx.meshx_flutter_probe

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.database.MatrixCursor
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.Executors

/** Read-only, opaque handles for user-selected copies; no arbitrary path command. */
class MeshXShareProvider : ContentProvider() {
    override fun onCreate() = true
    private fun file(uri: Uri): File {
        val segments = uri.pathSegments
        if (segments.size != 2 || !segments[0].matches(Regex("^[0-9a-f-]{36}$"))) throw FileNotFoundException()
        val base = File(context!!.cacheDir, "meshx-selected/${segments[0]}").canonicalFile
        val file = File(base, segments[1]).canonicalFile
        if (file.parentFile != base || file.name == "mime" || !file.isFile) throw FileNotFoundException()
        return file
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read-only capability")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri): String = File(file(uri).parentFile, "mime").readText()
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = file(uri)
        val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { if (it == OpenableColumns.SIZE) file.length() else file.name }) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}

internal class MobileCapabilities(private val activity: Activity, messenger: BinaryMessenger,
    private val networkChanged: () -> Unit) {
    private val channel = MethodChannel(messenger, "com.meshx.mobile/capabilities")
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val manager = activity.getSystemService(NotificationManager::class.java)
    private val connectivity = activity.getSystemService(ConnectivityManager::class.java)
    private val prefs = activity.getSharedPreferences("meshx-capabilities", Context.MODE_PRIVATE)
    private val folder = File(activity.cacheDir, "meshx-selected")
    private var sink: EventChannel.EventSink? = null
    private var locationResult: MethodChannel.Result? = null
    private var locationListener: LocationListener? = null
    private var locationTimeout: Runnable? = null
    private var notificationPermission: MethodChannel.Result? = null
    private var picker: MethodChannel.Result? = null
    private var pickerCode = 7400
    private var pickerMax = 25L * 1024 * 1024
    private var pendingTap: Map<String, String>? = null
    private var owner: String? = null
    @Volatile private var epoch = 0
    @Volatile private var disposed = false
    private var networkKey: String? = null
    private val callback = object: ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed(network.toString())
        override fun onLost(network: Network) = changed("lost:${network}")
        override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) = changed(network.toString() + link.linkAddresses.toString())
    }
    private fun changed(key: String) { handler.post {
        if (disposed || key == networkKey) return@post
        val previous = networkKey; networkKey = key
        if (previous != null) { networkChanged(); sink?.success(mapOf("type" to "networkChanged")) }
    } }
    private fun reply(result: MethodChannel.Result, status: String, reason: String = "") = result.success(mapOf("status" to status, "reason" to reason))
    init {
        folder.mkdirs()
        // Temporary selected copies are not a permanent file library.
        folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.deleteRecursively() }
        manager.createNotificationChannel(NotificationChannel("meshx_messages", "消息提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "仅提醒有新消息，不展示消息正文"; lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        EventChannel(messenger, "com.meshx.mobile/capabilities/events").setStreamHandler(object: EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                sink = events; pendingTap?.let { sink?.success(it) }; pendingTap = null
            }
            override fun onCancel(arguments: Any?) { sink = null }
        })
        try { connectivity.registerDefaultNetworkCallback(callback) }
        catch (_: RuntimeException) { networkKey = "unavailable" }
        channel.setMethodCallHandler { call, result ->
            if (disposed) { reply(result, "CANCELLED", "disposed"); return@setMethodCallHandler }
            val args = call.arguments as? Map<*, *> ?: emptyMap<Any, Any>()
            try {
                when (call.method) {
                    "pushState" -> result.success(mapOf("status" to "SUCCESS", "owner" to activity.getSharedPreferences("meshx-push", Context.MODE_PRIVATE).getString("owner", null)))
                    "registerPush" -> MeshXPush.register(activity, args, result)
                    "clearPush" -> { MeshXPush.clear(activity); reply(result, "SUCCESS") }

                    "currentLocation" -> startLocation(result)
                    "runtimeInfo" -> {
                        val packageInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
                        @Suppress("DEPRECATION")
                        val buildNumber = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode.toString() else packageInfo.versionCode.toString()
                        result.success(mapOf(
                            "status" to "SUCCESS",
                            "reason" to "",
                            "appVersion" to (packageInfo.versionName ?: "0"),
                            "buildNumber" to buildNumber,
                            "osName" to "Android",
                            "osVersion" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
                        ))
                    }
                    "notificationPermission" -> {
                        val status = permissionStatus()
                        if (args["request"] == true && Build.VERSION.SDK_INT >= 33 && status in listOf("PERMISSION_REQUIRED", "PERMISSION_DENIED")) {
                            if (notificationPermission != null) reply(result, "TEMPORARILY_UNAVAILABLE", "permissionInProgress")
                            else {
                                notificationPermission = result; prefs.edit().putBoolean("notificationAsked", true).apply()
                                activity.requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7302)
                            }
                        } else reply(result, status)
                    }
                    "notificationOwner" -> {
                        MeshXPush.ownerChanged(activity, args["owner"] as? String)
                        owner = args["owner"] as? String; manager.cancelAll(); pendingTap = null; reply(result, "SUCCESS") }
                    "notificationShow" -> {
                        val target = args["owner"] as? String
                        val conversation = args["conversationId"] as? String
                        val id = args["id"] as? String
                        when {
                            target == null || target != owner -> reply(result, "CANCELLED", "sessionChanged")
                            conversation.isNullOrBlank() || id.isNullOrBlank() || id.length > 256 -> reply(result, "FAILED", "invalidNotification")
                            permissionStatus() != "AVAILABLE" -> reply(result, permissionStatus())
                            else -> {
                                val intent = Intent(activity, MainActivity::class.java).setAction("meshx.notification.$id")
                                    .putExtra("meshx_owner", target).putExtra("meshx_conversation", conversation)
                                    .putExtra("meshx_message", args["messageId"] as? String)
                                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                (args["broadcastId"] as? Number)?.toLong()?.takeIf { it > 0 }?.let {
                                    intent.putExtra("meshx_broadcast", it.toString())
                                }
                                val pending = PendingIntent.getActivity(activity, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                                val notification = Notification.Builder(activity, "meshx_messages")
                                    .setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle("MeshX")
                                    .setContentText("你有新消息，打开 MeshX 查看").setVisibility(Notification.VISIBILITY_PRIVATE)
                                    .setContentIntent(pending).setAutoCancel(true).build()
                                manager.notify(id, 0, notification); reply(result, "SUCCESS")
                            }
                        }
                    }
                    "notificationCancel" -> { manager.cancel(args["id"] as? String, 0); reply(result, "SUCCESS") }
                    "notificationCancelAll" -> { if (args["owner"] != null && args["owner"] != owner) reply(result, "CANCELLED", "sessionChanged") else { manager.cancelAll(); reply(result, "SUCCESS") } }
                    "pickFile", "pickPhoto" -> {
                        if (picker != null) reply(result, "TEMPORARILY_UNAVAILABLE", "pickerBusy")
                        else {
                            pickerMax = ((args["maxBytes"] as? Number)?.toLong() ?: pickerMax).coerceIn(1, 25L * 1024 * 1024)
                            pickerCode = 7400 + ((pickerCode - 7400 + 1) % 10000)
                            picker = result
                            try {
                                val photos = call.method == "pickPhoto" || args["photos"] == true
                                val document = Intent(Intent.ACTION_OPEN_DOCUMENT)
                                    .setType(if (photos) "image/*" else "*/*")
                                    .addCategory(Intent.CATEGORY_OPENABLE)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                val photo = Intent("android.provider.action.PICK_IMAGES").setType("image/*")
                                // Documents provide a fallback without Google Play services.
                                val intent = if (photos && (Build.VERSION.SDK_INT >= 33 || photo.resolveActivity(activity.packageManager) != null)) photo else document
                                try {
                                    activity.startActivityForResult(intent, pickerCode)
                                } catch (missing: android.content.ActivityNotFoundException) {
                                    if (intent === document) throw missing
                                    activity.startActivityForResult(document, pickerCode)
                                }
                            } catch (_: android.content.ActivityNotFoundException) { picker = null; reply(result, "UNSUPPORTED") }
                        }
                    }
                    "readFile" -> {
                        val file = selected(args["handle"])
                        val limit = ((args["maxBytes"] as? Number)?.toLong() ?: 0L).coerceAtMost(25L * 1024 * 1024)
                        when {
                            file == null || !file.canRead() -> reply(result, "FAILED", "fileMissing")
                            limit <= 0 || file.length() > limit -> reply(result, "FAILED", "fileTooLarge")
                            else -> {
                                val expected = epoch
                                worker.execute {
                                    try {
                                        val bytes = file.readBytes()
                                        handler.post {
                                            if (disposed || expected != epoch) reply(result, "CANCELLED", "sessionChanged")
                                            else if (bytes.size.toLong() != file.length() || bytes.size.toLong() > limit) reply(result, "FAILED", "fileChanged")
                                            else result.success(mapOf("status" to "SUCCESS", "bytes" to bytes))
                                        }
                                    } catch (_: IOException) {
                                        handler.post { reply(result, "FAILED", "fileUnavailable") }
                                    }
                                }
                            }
                        }
                    }
                    "readFileChunk" -> {
                        val file = selected(args["handle"])
                        val offset = (args["offset"] as? Number)?.toLong() ?: -1L
                        val length = (args["length"] as? Number)?.toInt() ?: 0
                        when {
                            file == null || !file.canRead() -> reply(result, "FAILED", "fileMissing")
                            offset < 0 || offset > file.length() || length <= 0 || length > 1024 * 1024 -> reply(result, "FAILED", "invalidFileRange")
                            else -> {
                                val expected = epoch
                                worker.execute {
                                    try {
                                        val count = minOf(length.toLong(), file.length() - offset).toInt()
                                        val bytes = ByteArray(count)
                                        RandomAccessFile(file, "r").use { input -> input.seek(offset); input.readFully(bytes) }
                                        handler.post {
                                            if (disposed || expected != epoch) reply(result, "CANCELLED", "sessionChanged")
                                            else result.success(mapOf("status" to "SUCCESS", "bytes" to bytes))
                                        }
                                    } catch (_: IOException) {
                                        handler.post { reply(result, "FAILED", "fileUnavailable") }
                                    }
                                }
                            }
                        }
                    }
                    "cacheFile" -> {
                        val bytes = args["bytes"] as? ByteArray
                        val rawName = args["name"] as? String ?: "MeshX-file"
                        val mime = (args["mime"] as? String)?.takeIf { it.matches(Regex("^[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+$")) }
                            ?: "application/octet-stream"
                        if (bytes == null || bytes.isEmpty() || bytes.size > 25 * 1024 * 1024) reply(result, "FAILED", "fileTooLarge")
                        else {
                            val expected = epoch
                            worker.execute {
                                val id = UUID.randomUUID().toString()
                                val dir = File(folder, id)
                                val name = rawName.replace(Regex("[\\\\/\\p{Cntrl}]"), "_").take(120).trim('.').ifEmpty { "MeshX-file" }
                                    .let { if (it == "mime") "MeshX-file" else it }
                                try {
                                    check(dir.mkdirs())
                                    File(dir, name).outputStream().use { output -> output.write(bytes); output.fd.sync() }
                                    File(dir, "mime").writeText(mime)
                                    handler.post {
                                        if (disposed || expected != epoch) { dir.deleteRecursively(); reply(result, "CANCELLED", "sessionChanged") }
                                        else result.success(mapOf("status" to "SUCCESS", "handle" to id, "name" to name, "mime" to mime, "size" to bytes.size))
                                    }
                                } catch (_: IOException) {
                                    dir.deleteRecursively(); handler.post { reply(result, "FAILED", "fileUnavailable") }
                                }
                            }
                        }
                    }
                    "releaseFile" -> { val file = selected(args["handle"]); if (file == null) reply(result, "FAILED", "fileMissing")
                        else { activity.revokeUriPermission(shareUri(file), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            reply(result, if (file.parentFile!!.deleteRecursively()) "SUCCESS" else "FAILED") } }
                    "releaseAllFiles" -> {
                        epoch++; if (picker != null) activity.finishActivity(pickerCode)
                        picker?.let { reply(it, "CANCELLED", "sessionChanged") }; picker = null
                        folder.listFiles()?.forEach { dir -> dir.listFiles()?.filter { it.name != "mime" }?.forEach { activity.revokeUriPermission(shareUri(it), Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
                        reply(result, if (folder.deleteRecursively()) "SUCCESS" else "FAILED")
                    }
                    "shareFile" -> {
                        val file = selected(args["handle"])
                        if (file == null || !file.canRead()) reply(result, "FAILED", "fileMissing")
                        else {
                            val uri = shareUri(file)
                            val send = Intent(Intent.ACTION_SEND).setType(File(file.parentFile, "mime").readText())
                                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            send.clipData = ClipData.newUri(activity.contentResolver, file.name, uri)
                            try { activity.startActivity(Intent.createChooser(send, "分享文件")); reply(result, "SUCCESS", "shareSheetPresented") }
                            catch (_: android.content.ActivityNotFoundException) { reply(result, "UNSUPPORTED") }
                        }
                    }
                    "openSettings" -> { activity.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))); reply(result, "SUCCESS") }
                    else -> result.notImplemented()
                }
            } catch (_: SecurityException) { reply(result, "PERMISSION_DENIED") }
            catch (_: IOException) { reply(result, "FAILED", "fileUnavailable") }
            catch (_: RuntimeException) { reply(result, "FAILED", "platformOperationFailed") }
        }
    }
    private fun permissionStatus(): String {
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            if (!prefs.getBoolean("notificationAsked", false)) return "PERMISSION_REQUIRED"
            return if (activity.shouldShowRequestPermissionRationale("android.permission.POST_NOTIFICATIONS")) "PERMISSION_DENIED" else "PERMISSION_PERMANENTLY_DENIED"
        }
        return if (manager.areNotificationsEnabled() && manager.getNotificationChannel("meshx_messages")?.importance != NotificationManager.IMPORTANCE_NONE) "AVAILABLE" else "PERMISSION_DENIED"
    }
    private fun finishLocation(status: String, location: Location? = null) {
        val pending = locationResult ?: return
        locationResult = null
        locationTimeout?.let { handler.removeCallbacks(it) }; locationTimeout = null
        locationListener?.let { activity.getSystemService(LocationManager::class.java).removeUpdates(it) }; locationListener = null
        if (location == null) reply(pending, status) else pending.success(mapOf("status" to "SUCCESS", "latitude" to location.latitude, "longitude" to location.longitude, "accuracyMeters" to location.accuracy.toDouble(), "timestamp" to location.time))
    }
    private fun startLocation(result: MethodChannel.Result) {
        if (locationResult != null) { reply(result, "TEMPORARILY_UNAVAILABLE"); return }
        locationResult = result
        val timeout = Runnable { finishLocation("TIMEOUT") }
        locationTimeout = timeout; handler.postDelayed(timeout, 30000)
        if (activity.checkSelfPermission("android.permission.ACCESS_COARSE_LOCATION") != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"), 7303)
        } else acquireLocation()
    }
    @Suppress("DEPRECATION", "MissingPermission")
    private fun acquireLocation() {
        val manager = activity.getSystemService(LocationManager::class.java)
        val fine = activity.checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") == PackageManager.PERMISSION_GRANTED
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter { manager.isProviderEnabled(it) && (it != LocationManager.GPS_PROVIDER || fine) }
        if (providers.isEmpty()) { finishLocation("TEMPORARILY_UNAVAILABLE"); return }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (location.hasAccuracy() && System.currentTimeMillis() - location.time in 0..60000) finishLocation("SUCCESS", location)
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        locationListener = listener
        try { providers.forEach { manager.requestSingleUpdate(it, listener, Looper.getMainLooper()) } }
        catch (_: SecurityException) { finishLocation("PERMISSION_DENIED") }
        catch (_: Exception) { finishLocation("FAILED") }
    }
    fun permissionResult(code: Int, grants: IntArray) {
        if (code == 7303) {
            if (locationResult == null) return
            if (activity.checkSelfPermission("android.permission.ACCESS_COARSE_LOCATION") == PackageManager.PERMISSION_GRANTED) acquireLocation()
            else finishLocation("PERMISSION_DENIED")
            return
        }

        if (code != 7302) return
        notificationPermission?.let { reply(it, if (grants.isEmpty()) "CANCELLED" else permissionStatus()) }; notificationPermission = null
    }
    fun handleIntent(intent: Intent?) {
        val target = intent?.getStringExtra("meshx_owner") ?: return
        val conversation = intent.getStringExtra("meshx_conversation") ?: return
        val event = mutableMapOf("type" to "notificationTap", "owner" to target, "conversationId" to conversation)
        intent.getStringExtra("meshx_message")?.let { event["messageId"] = it }
        intent.getStringExtra("meshx_broadcast")?.let { event["broadcastId"] = it }
        if (sink != null) sink?.success(event) else pendingTap = event
        intent.removeExtra("meshx_owner"); intent.removeExtra("meshx_conversation"); intent.removeExtra("meshx_broadcast"); intent.removeExtra("meshx_message")
    }
    private fun selected(value: Any?): File? {
        val handle = value as? String ?: return null
        if (!handle.matches(Regex("^[0-9a-f-]{36}$"))) return null
        return File(folder, handle).listFiles()?.singleOrNull { it.isFile && it.name != "mime" }
    }
    private fun shareUri(file: File): Uri = Uri.Builder().scheme("content").authority(activity.packageName + ".selected")
        .appendPath(file.parentFile!!.name).appendPath(file.name).build()
    fun activityResult(code: Int, status: Int, data: Intent?) {
        if (code != pickerCode) return
        val result = picker ?: return
        if (status != Activity.RESULT_OK) { picker = null; reply(result, "CANCELLED"); return }
        val uri = data?.data
        if (uri == null || uri.scheme != "content") { picker = null; reply(result, "FAILED", "invalidSelection"); return }
        val expected = epoch; val limit = pickerMax
        worker.execute {
            val id = UUID.randomUUID().toString(); val dir = File(folder, id)
            var response: Map<String, Any> = mapOf("status" to "FAILED", "reason" to "fileUnreadable")
            try {
                var name = "selected-file"; var knownSize = -1L
                activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME); val s = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (n >= 0) name = cursor.getString(n) ?: name
                        if (s >= 0 && !cursor.isNull(s)) knownSize = cursor.getLong(s)
                    }
                }
                if (knownSize > limit) throw TooLarge()
                name = name.replace(Regex("[\\\\/\\p{Cntrl}]"), "_").take(120).trim('.').ifEmpty { "selected-file" }
                if (name == "mime") name = "selected-mime"
                var mime = activity.contentResolver.getType(uri)?.takeIf { it.matches(Regex("^[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+$")) } ?: "application/octet-stream"
                check(dir.mkdirs())
                var size = 0L; val deadline = System.nanoTime() + 30_000_000_000L
                activity.contentResolver.openInputStream(uri)?.use { input ->
                    File(dir, name).outputStream().use { output ->
                        val buffer = ByteArray(32768)
                        while (true) {
                            if (disposed || expected != epoch) throw InterruptedException()
                            if (System.nanoTime() > deadline) throw java.util.concurrent.TimeoutException()
                            val count = input.read(buffer); if (count < 0) break
                            size += count; if (size > limit) throw TooLarge()
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                } ?: throw FileNotFoundException()
                File(dir, "mime").writeText(mime)
                prepareImage(File(dir, name), limit)?.let { prepared ->
                    name = prepared.name
                    mime = if (prepared.extension == "png") "image/png" else "image/jpeg"
                    size = prepared.length()
                    File(dir, "mime").writeText(mime)
                }
                if (disposed || expected != epoch) throw InterruptedException()
                if (System.nanoTime() > deadline) throw java.util.concurrent.TimeoutException()
                response = mapOf("status" to "SUCCESS", "handle" to id, "name" to name, "mime" to mime, "size" to size)
            } catch (_: TooLarge) { response = mapOf("status" to "FAILED", "reason" to "fileTooLarge") }
            catch (_: SecurityException) { response = mapOf("status" to "PERMISSION_DENIED", "reason" to "fileAccessDenied") }
            catch (_: FileNotFoundException) { response = mapOf("status" to "FAILED", "reason" to "fileMissing") }
            catch (_: java.util.concurrent.TimeoutException) { response = mapOf("status" to "TIMEOUT") }
            catch (_: InterruptedException) { response = mapOf("status" to "CANCELLED") }
            catch (_: Exception) { response = mapOf("status" to "FAILED", "reason" to "fileUnreadable") }
            handler.post {
                if (disposed || expected != epoch || picker !== result) { dir.deleteRecursively(); return@post }
                picker = null
                if (response["status"] != "SUCCESS") dir.deleteRecursively()
                result.success(response)
            }
        }
    }
    private fun prepareImage(file: File, limit: Long): File? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val mime = bounds.outMimeType ?: return null
        if (mime !in setOf("image/jpeg", "image/png", "image/heic", "image/heif")) return null
        val width = bounds.outWidth; val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        val heif = mime == "image/heic" || mime == "image/heif"
        if (!heif && width.toLong() * height <= 40_000_000L) return null
        val decoded: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val ratio = minOf(1.0, 4096.0 / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(maxOf(1, (info.size.width * ratio).toInt()), maxOf(1, (info.size.height * ratio).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            var sample = 1
            while (maxOf(width, height) / sample > 4096) sample *= 2
            val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw IOException("Invalid image")
            val transform = Matrix()
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> transform.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> transform.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> transform.setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { transform.setRotate(90f); transform.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> transform.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { transform.setRotate(270f); transform.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> transform.setRotate(270f)
            }
            val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
            if (oriented !== bitmap) bitmap.recycle()
            oriented
        }
        val png = mime == "image/png"
        val target = File(file.parentFile, file.nameWithoutExtension.take(100) + "-meshx." + if (png) "png" else "jpg")
        try {
            target.outputStream().use { out ->
                if (!decoded.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, out)) throw IOException("Image encoding failed")
            }
        } finally { decoded.recycle() }
        if (target.length() <= 0 || target.length() > limit) throw TooLarge()
        if (!file.delete()) throw IOException("Image staging cleanup failed")
        return target
    }
    private class TooLarge: IOException()
    fun dispose() {
        finishLocation("CANCELLED")
        disposed = true; epoch++
        picker?.let { reply(it, "CANCELLED", "disposed") }; picker = null
        notificationPermission?.let { reply(it, "CANCELLED", "disposed") }; notificationPermission = null
        try { connectivity.unregisterNetworkCallback(callback) } catch (_: IllegalArgumentException) { /* Registration was unavailable. */ }
        worker.shutdownNow(); sink = null; channel.setMethodCallHandler(null)
    }
}
