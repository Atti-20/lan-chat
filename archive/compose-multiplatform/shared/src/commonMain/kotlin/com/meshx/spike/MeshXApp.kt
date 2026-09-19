package com.meshx.spike

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

private val MeshXBlue = Color(0xFF315BFF)
private val MeshXBackground = Color(0xFFF4F6FB)

@Composable
fun MeshXApp(services: PlatformServices) {
    val scope = rememberCoroutineScope()
    val state = remember(services) { MeshXAppState(scope, services) }
    DisposableEffect(state) {
        state.start()
        onDispose(state::close)
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = MeshXBlue,
            background = MeshXBackground,
            surface = Color.White,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(20.dp)) {
                if (maxWidth >= 780.dp) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        DiscoveryPanel(state, Modifier.weight(0.9f).fillMaxSize())
                        LoginPanel(state, services.platformName, Modifier.weight(1.1f).fillMaxSize())
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        DiscoveryPanel(state, Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 360.dp))
                        LoginPanel(state, services.platformName, Modifier.fillMaxWidth().heightIn(min = 610.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryPanel(state: MeshXAppState, modifier: Modifier = Modifier) {
    val discovery by state.discovery.collectAsState()
    val access by state.localNetworkAccess.collectAsState()
    Card(modifier, shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(Color.White)) {
        Column(Modifier.fillMaxSize().padding(22.dp)) {
            Text("附近的 MeshX Control", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                if (!access.granted) {
                    access.message ?: "需要局域网权限才能自动发现 Control"
                } else when (discovery.phase) {
                    DiscoveryPhase.IDLE -> "尚未开始扫描"
                    DiscoveryPhase.STARTING -> "正在启动系统局域网发现…"
                    DiscoveryPhase.RUNNING -> "系统 mDNS 扫描中 · ${discovery.controls.size} 个可用节点"
                    DiscoveryPhase.FAILED -> discovery.message ?: "局域网发现失败"
                    DiscoveryPhase.STOPPED -> "扫描已停止"
                },
                color = if (!access.granted || discovery.phase == DiscoveryPhase.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    Color(0xFF687087)
                },
            )
            Spacer(Modifier.height(16.dp))
            if (!access.granted) {
                LocalNetworkAccessCard(access, state::requestLocalNetworkAccess)
            } else if (discovery.phase == DiscoveryPhase.STARTING) {
                CircularProgressIndicator(Modifier.width(28.dp), strokeWidth = 3.dp)
            } else if (discovery.controls.isEmpty()) {
                EmptyDiscoveryHint()
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(discovery.controls, key = { "${it.controlId}@${it.origin}" }) { control ->
                        ControlCard(control) { state.selectControl(control) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalNetworkAccessCard(access: LocalNetworkAccessState, onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Color(0xFFFFF5E8), RoundedCornerShape(16.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "MeshX 只会用此权限发现和连接同一局域网中的 Control 与 Peer，不会扫描公网。",
            color = Color(0xFF73510D),
        )
        Button(
            onClick = onRequest,
            enabled = access.phase != LocalNetworkAccessPhase.REQUESTING,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (access.phase == LocalNetworkAccessPhase.REQUESTING) {
                CircularProgressIndicator(Modifier.width(20.dp), strokeWidth = 2.dp, color = Color.White)
            } else {
                Text(if (access.canRequest) "允许局域网发现" else "打开系统设置")
            }
        }
    }
}

@Composable
private fun EmptyDiscoveryHint() {
    Box(
        Modifier.fillMaxWidth().background(Color(0xFFF7F8FC), RoundedCornerShape(16.dp)).padding(18.dp),
    ) {
        Text("没有发现 Control。可确认设备处于同一局域网，或在右侧手动输入地址。", color = Color(0xFF687087))
    }
}

@Composable
private fun ControlCard(control: DiscoveredControl, onSelect: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(Color(0xFFF7F8FC), RoundedCornerShape(16.dp))
            .clickable(onClick = onSelect)
            .padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(control.name, fontWeight = FontWeight.SemiBold)
            Text(if (control.secure) "TLS" else "LAN", color = MeshXBlue, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(4.dp))
        Text(control.organizationName, color = Color(0xFF687087), style = MaterialTheme.typography.bodySmall)
        Text(control.origin, color = Color(0xFF687087), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LoginPanel(state: MeshXAppState, platformName: String, modifier: Modifier = Modifier) {
    val login by state.login.collectAsState()
    Card(modifier, shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(Color.White)) {
        Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.Center) {
            Text("同一套 UI，原生能力落地", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("当前平台：$platformName", color = Color(0xFF687087))
            Spacer(Modifier.height(22.dp))
            OutlinedTextField(
                value = login.origin,
                onValueChange = state::updateOrigin,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Control 地址") },
                placeholder = { Text("192.168.1.20:8080") },
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = login.username,
                onValueChange = state::updateUsername,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("用户名") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = login.password,
                onValueChange = state::updatePassword,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { state.login() }),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = state::login,
                enabled = !login.isLoggingIn,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (login.isLoggingIn) CircularProgressIndicator(Modifier.width(22.dp), strokeWidth = 2.dp, color = Color.White)
                else Text("验证 Control 登录")
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = state::previewFile,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("选择文件并调用系统预览")
            }
            login.message?.let { message ->
                Spacer(Modifier.height(14.dp))
                Text(message, color = if (login.isError) MaterialTheme.colorScheme.error else Color(0xFF16825D))
            }
            login.session?.let { session ->
                Spacer(Modifier.height(8.dp))
                Text("已验证：${session.nickname} · userId=${session.userId}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
