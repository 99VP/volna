package com.volna.player.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.volna.player.BuildConfig
import com.volna.player.R
import com.volna.player.data.SettingsStore
import com.volna.player.net.BypassState
import com.volna.player.net.ProtocolTest
import com.volna.player.net.ProxyRuntime
import kotlinx.coroutines.launch

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val state by ProxyRuntime.state.collectAsState()
    val detail by ProxyRuntime.detail.collectAsState()
    val server by SettingsStore.watchServer.collectAsState()
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<ProtocolTest>>(emptyList()) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Назад")
                }
                Text("Диагностика", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(16.dp))
            Text("Версия ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Сборка ${BuildConfig.BUILD_ID}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Обход: " + when (state) {
                    BypassState.READY -> "работает"
                    BypassState.STARTING -> "запуск"
                    BypassState.VPN_ACTIVE -> "пауза, включён VPN"
                    BypassState.FAILED -> "не удалось"
                    BypassState.DISABLED -> "выключен"
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Сервер комнат: " + server.ifBlank { "не задан" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    scope.launch {
                        testing = true
                        results = ProxyRuntime.testProtocols()
                        testing = false
                    }
                },
                enabled = !testing,
            ) {
                Text(if (testing) "Проверяю…" else "Проверить протоколы")
            }
            if (testing) {
                Spacer(Modifier.height(12.dp))
                CircularProgressIndicator()
            }
            results.forEach { r ->
                Spacer(Modifier.height(10.dp))
                Text(
                    "${r.name}: " + if (r.ok) "работает" else "нет ответа",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Text(
                    "${r.elapsedMs} мс",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Если disorder работает, а split/fake нет — для этой сети это нормально.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
