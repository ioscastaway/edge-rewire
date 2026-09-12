package com.ioscastaway.edgerewire.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as SysSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ioscastaway.edgerewire.platform.EdgeRewireService
import com.ioscastaway.edgerewire.platform.Prefs
import com.ioscastaway.edgerewire.platform.Settings

/** Debug console: is the service bound, what does it see, and a few knobs. Not the product. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        setContent {
            MaterialTheme {
                Screen(
                    prefs = prefs,
                    openA11ySettings = { startActivity(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    openBrowser = {
                        packageManager.getLaunchIntentForPackage(Settings.SAMSUNG_INTERNET)?.let(::startActivity)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen(prefs: Prefs, openA11ySettings: () -> Unit, openBrowser: () -> Unit) {
    val service by EdgeRewireService.instance.collectAsStateWithLifecycle()
    val state by EdgeRewireService.state.collectAsStateWithLifecycle()
    val log by EdgeRewireService.log.collectAsStateWithLifecycle()
    val settings by prefs.settings.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("Edge Rewire") }) }) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (service != null) "Service: bound" else "Service: OFF (enable it in Accessibility settings)",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text("Foreground: ${state.foreground ?: "-"} (display ${state.displayId})")
                    Text("Strips: ${if (state.attached) "attached" else "not attached"}")
                    if (state.lastAction.isNotEmpty()) Text("Last: ${state.lastAction}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = openA11ySettings) { Text("Accessibility settings") }
                        OutlinedButton(onClick = openBrowser) { Text("Open Samsung Internet") }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Toggle("Enabled", settings.enabled) { v -> prefs.update { it.copy(enabled = v) } }
                    Toggle("Unrestricted exclusion (immersive request)", settings.unrestrictedExclusion) { v ->
                        prefs.update { it.copy(unrestrictedExclusion = v) }
                    }
                    Toggle("Tint the strips", settings.debugTint) { v -> prefs.update { it.copy(debugTint = v) } }
                    Toggle("Fall back to system Back when no button found", settings.fallbackToSystemBack) { v ->
                        prefs.update { it.copy(fallbackToSystemBack = v) }
                    }
                    var targetsText by remember(settings.targets) { mutableStateOf(settings.targets.joinToString(", ")) }
                    OutlinedTextField(
                        value = targetsText,
                        onValueChange = { targetsText = it },
                        label = { Text("Target packages (comma-separated)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = {
                        val set = targetsText.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                        if (set.isNotEmpty()) prefs.update { it.copy(targets = set) }
                    }) { Text("Apply targets") }
                    Text("Edge width: ${settings.edgeWidthDp}dp")
                    Slider(
                        value = settings.edgeWidthDp.toFloat(),
                        onValueChange = { v -> prefs.update { it.copy(edgeWidthDp = v.toInt()) } },
                        valueRange = 8f..48f,
                        steps = 9,
                    )
                    Text("Commit distance: ${settings.commitDistanceDp}dp")
                    Slider(
                        value = settings.commitDistanceDp.toFloat(),
                        onValueChange = { v -> prefs.update { it.copy(commitDistanceDp = v.toInt()) } },
                        valueRange = 24f..160f,
                        steps = 16,
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Log", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(onClick = { EdgeRewireService.clearLog() }) { Text("Clear") }
                    }
                    val lines = remember(log) { log.asReversed() }
                    lines.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
