package com.meshx.meshx_flutter_probe

import android.content.Intent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {
    private var discovery: AndroidDiscoveryAdapter? = null
    private var capabilities: MobileCapabilities? = null
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val messenger = flutterEngine.dartExecutor.binaryMessenger
        MobileStorage(applicationContext, messenger)
        discovery = AndroidDiscoveryAdapter(this, messenger)
        capabilities = MobileCapabilities(this, messenger) { discovery?.stop(reason = "networkChanged") }
        capabilities?.handleIntent(intent)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grants)
        if (discovery?.permissionResult(requestCode, grants) != true) capabilities?.permissionResult(requestCode, grants)
    }
    @Deprecated("The platform picker uses the framework activity result API without a plugin dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        capabilities?.activityResult(requestCode, resultCode, data)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); capabilities?.handleIntent(intent) }
    override fun onStop() { discovery?.stop(); super.onStop() }
    override fun onDestroy() { discovery?.dispose(); capabilities?.dispose(); super.onDestroy() }
}
