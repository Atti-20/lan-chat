package com.meshx.meshx_flutter_probe

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.flutter.plugin.common.MethodChannel
import java.util.UUID

class MeshXApplication : Application() {
    override fun onCreate() { super.onCreate(); MeshXPush.initialize(this) }
}
internal object MeshXPush {
    private fun prefs(context: Context) = context.getSharedPreferences("meshx-push", Context.MODE_PRIVATE)
    fun initialize(context: Context): FirebaseApp? {
        FirebaseApp.getApps(context).firstOrNull()?.let { return it }
        val p = prefs(context)
        val appId = p.getString("applicationId", null) ?: return null
        return try {
            FirebaseApp.initializeApp(context, FirebaseOptions.Builder().setApplicationId(appId).setApiKey(p.getString("apiKey", "")!!).setProjectId(p.getString("projectId", "")!!).setGcmSenderId(p.getString("senderId", "")!!).build())
        } catch (_: Exception) { null }
    }
    fun register(context: Context, args: Map<*, *>, result: MethodChannel.Result) {
        val owner = args["owner"] as? String
        val config = args["firebase"] as? Map<*, *>
        if (owner.isNullOrBlank() || config == null) { result.success(mapOf("status" to "FAILED", "reason" to "pushConfigMissing")); return }
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) { result.success(mapOf("status" to "PERMISSION_DENIED")); return }
        val p = prefs(context)
        val project = config["projectId"] as? String ?: ""
        val existing = FirebaseApp.getApps(context).firstOrNull()
        if (existing != null && existing.options.projectId != project) { clear(context); existing.delete() }
        if (listOf("applicationId", "apiKey", "projectId", "senderId").any { (config[it] as? String).isNullOrBlank() }) { result.success(mapOf("status" to "FAILED", "reason" to "pushConfigMissing")); return }
        val scope = if (p.getString("owner", null) == owner) p.getString("scope", null) ?: UUID.randomUUID().toString() else UUID.randomUUID().toString()
        val edit = p.edit().putString("owner", owner).putString("scope", scope)
        listOf("applicationId", "apiKey", "projectId", "senderId").forEach { edit.putString(it, config[it] as String) }
        if (!edit.commit() || initialize(context) == null) { result.success(mapOf("status" to "FAILED", "reason" to "pushInitializationFailed")); return }
        FirebaseMessaging.getInstance().isAutoInitEnabled = false
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (p.getString("owner", null) != owner || p.getString("scope", null) != scope) { result.success(mapOf("status" to "CANCELLED")); return@addOnCompleteListener }
            if (!task.isSuccessful) { result.success(mapOf("status" to "FAILED", "reason" to "pushRegistrationFailed")); return@addOnCompleteListener }
            p.edit().putString("token", task.result).apply()
            result.success(mapOf("status" to "SUCCESS", "platform" to "FCM", "endpoint" to task.result, "scope" to scope))
        }
    }
    fun clear(context: Context) {
        prefs(context).edit().remove("owner").remove("scope").remove("token").commit()
        if (FirebaseApp.getApps(context).isNotEmpty()) {
            FirebaseMessaging.getInstance().isAutoInitEnabled = false
            FirebaseMessaging.getInstance().deleteToken()
        }
        context.getSystemService(NotificationManager::class.java).cancel("meshx-push", 7310)
    }
    fun ownerChanged(context: Context, owner: String?) {
        val current = prefs(context).getString("owner", null)
        if (current != null && current != owner) clear(context)
    }
}
class MeshXMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        getSharedPreferences("meshx-push", Context.MODE_PRIVATE).edit().putString("token", token).apply()
    }
    override fun onMessageReceived(message: RemoteMessage) {
        val p = getSharedPreferences("meshx-push", Context.MODE_PRIVATE)
        val scope = p.getString("scope", null) ?: return
        val owner = p.getString("owner", null) ?: return
        if (message.data["meshxScope"] != scope) return
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("meshx_messages", "消息提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("meshx_owner", owner).putExtra("meshx_conversation", "push-inbox")
        val pending = PendingIntent.getActivity(this, 7310, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "meshx_messages").setSmallIcon(applicationInfo.icon).setContentTitle("MeshX").setContentText("你有新的协作消息").setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(pending).build()
        manager.notify("meshx-push", 7310, notification)
    }
}
