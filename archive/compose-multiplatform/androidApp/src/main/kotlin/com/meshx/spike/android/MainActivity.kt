package com.meshx.spike.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.meshx.spike.MeshXApp
import com.meshx.spike.PlatformServices
import com.meshx.spike.platformHttpClient
import io.ktor.client.engine.okhttp.OkHttp

class MainActivity : ComponentActivity() {
    private lateinit var filePreviewer: AndroidFilePreviewer
    private lateinit var localNetworkAccess: AndroidLocalNetworkAccess

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filePreviewer = AndroidFilePreviewer(this)
        localNetworkAccess = AndroidLocalNetworkAccess(this)
        val services = PlatformServices(
            platformName = "Android · Compose Multiplatform",
            deviceType = "android",
            deviceName = "Android ${android.os.Build.MODEL}",
            localNetworkAccess = localNetworkAccess,
            discovery = AndroidControlDiscovery(applicationContext) {
                localNetworkAccess.state.value.granted
            },
            filePreviewer = filePreviewer,
            httpClient = platformHttpClient(OkHttp.create()),
            deviceIdentityStore = AndroidDeviceIdentityStore(applicationContext),
        )
        setContent {
            MeshXApp(services)
        }
    }
}
