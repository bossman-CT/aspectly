package io.github.bossmanct.aspectly

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.github.bossmanct.aspectly.boot.Prerequisite
import io.github.bossmanct.aspectly.boot.SelfRepair
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.core.graphics.drawable.toBitmap
import io.github.bossmanct.aspectly.adb.AdbConnection
import io.github.bossmanct.aspectly.adb.AdbSession
import io.github.bossmanct.aspectly.adb.AdbShell
import io.github.bossmanct.aspectly.adb.AspectlyCommands
import io.github.bossmanct.aspectly.adb.BarSide
import io.github.bossmanct.aspectly.apps.AppInventory
import io.github.bossmanct.aspectly.apps.InstalledApp
import io.github.bossmanct.aspectly.apps.Support
import io.github.bossmanct.aspectly.config.AspectlyConfig
import io.github.bossmanct.aspectly.config.Restore
import io.github.bossmanct.aspectly.ui.theme.AspectlyTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AspectlyTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
                    AspectlyScreen(Modifier.padding(inner))
                }
            }
        }
    }
}

@Composable
private fun AspectlyScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config = remember { AspectlyConfig(context) }

    var status by remember { mutableStateOf("Not connected") }
    var pairPort by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var barSide by remember { mutableStateOf(config.barSide) }
    var pairingExpanded by remember { mutableStateOf(!config.hasPaired) }
    var selfRepair by remember { mutableStateOf(SelfRepair.isGranted(context)) }
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    val forced = remember { config.forcedApps.toMutableStateList() }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        apps = AppInventory.installedApps(context)

        // Notifications exist solely to name the toggle that is off when settings stop
        // applying. Without this the app fails silently and the user finds out when a
        // video starts cropping again.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val blocked = Prerequisite.check(context)
        if (blocked != Prerequisite.NONE) {
            status = "${blocked.title}. ${blocked.detail}"
        }
    }

    // Configured apps float to the top so the ones you actually manage stay reachable.
    val configured = apps
        .filter { it.controllable && it.packageName in forced }
        .sortedBy { it.label.lowercase() }
    val available = apps
        .filter { it.controllable && it.packageName !in forced }
        .sortedBy { it.label.lowercase() }
    val locked = apps.filterNot { it.controllable }.sortedBy { it.label.lowercase() }

    val toggle: (InstalledApp, Boolean) -> Unit = { app, on ->
        scope.launch {
            config.setForced(app.packageName, on)
            if (on) forced.add(app.packageName) else forced.remove(app.packageName)
            status = if (on) "Applying to ${app.label}…" else "Clearing ${app.label}…"
            val commands = if (on) {
                AspectlyCommands.applyAspectRatio(app.packageName)
            } else {
                AspectlyCommands.clearAspectRatio(app.packageName)
            }
            status = AdbSession.exclusive { AdbShell.runAll(context, commands) }.fold(
                {
                    if (on) {
                        // Applying the override is not the same as it having an effect.
                        // Apps that already lay out correctly at 10:16 — Instagram is
                        // one — accept every command and look identical afterwards.
                        "${app.label}: 16:9 applied. Open it to check — apps that " +
                            "already fit this screen won't look any different."
                    } else {
                        "${app.label} reset to default"
                    }
                },
                { "Failed: ${it.message}" },
            )
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "Aspectly",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        item {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    status,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }

        item {
            Button(
                onClick = {
                    scope.launch {
                        val blocked = Prerequisite.check(context)
                        if (blocked != Prerequisite.NONE) {
                            status = "${blocked.title}. ${blocked.detail}"
                            return@launch
                        }
                        status = "Connecting and applying…"
                        // Connect means "make reality match my config", not just "open
                        // a socket". Overrides only take effect when an app launches,
                        // so Restore force-stops the configured apps — without that,
                        // an already-running app keeps its old window and the user sees
                        // nothing change.
                        status = Restore.run(context).fold(
                            { "$it, ${config.barSide.description}" },
                            { "Connect failed: ${it.message}" },
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Connect and apply") }
        }

        item {
            PairingSection(
                port = pairPort,
                code = code,
                expanded = pairingExpanded,
                onExpandedChange = { pairingExpanded = it },
                onPort = { pairPort = it },
                onCode = { code = it },
                onPair = { p, c ->
                    scope.launch {
                        status = "Pairing…"
                        status = AdbConnection.pair(context, p, c).fold(
                            {
                                config.hasPaired = true
                                pairingExpanded = false
                                it
                            },
                            { "Pair failed: ${it.message}" },
                        )
                    }
                },
            )
        }

        item {
            SelfRepairRow(
                granted = selfRepair,
                onGrant = {
                    scope.launch {
                        status = "Granting…"
                        status = AdbSession.exclusive {
                            AdbShell.run(
                                context,
                                AspectlyCommands.grantSelfSecureSettings(context.packageName),
                            )
                        }.fold(
                            {
                                selfRepair = SelfRepair.isGranted(context)
                                if (selfRepair) {
                                    "Aspectly can now restore itself after a restart"
                                } else {
                                    "Grant did not take effect — reopen Aspectly and check"
                                }
                            },
                            { "Grant failed: ${it.message}" },
                        )
                    }
                },
                onRevoke = {
                    scope.launch {
                        status = "Revoking…"
                        AdbSession.exclusive {
                            AdbShell.run(
                                context,
                                AspectlyCommands.revokeSelfSecureSettings(context.packageName),
                            )
                        }
                        selfRepair = SelfRepair.isGranted(context)
                        status = "Revoked. You'll need to turn debugging on yourself " +
                            "after a restart."
                    }
                },
            )
        }

        item {
            Text("Black bar position", style = MaterialTheme.typography.titleSmall)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BarSide.entries.forEach { side ->
                    FilterChip(
                        selected = barSide == side,
                        onClick = {
                            barSide = side
                            config.barSide = side
                            scope.launch {
                                AdbSession.exclusive {
                                    AdbShell.run(context, AspectlyCommands.setBarPosition(side))
                                }
                                status = "Now showing ${side.description}"
                            }
                        },
                        label = { Text(side.name.lowercase().replaceFirstChar(Char::titlecase)) },
                    )
                }
            }
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp)) }

        item {
            Text(
                if (apps.isEmpty()) "Loading apps…" else "Apps",
                style = MaterialTheme.typography.titleSmall,
            )
        }

        items(configured, key = { it.packageName }) { app ->
            AppRow(app, app.packageName in forced) { on -> toggle(app, on) }
        }

        if (configured.isNotEmpty() && available.isNotEmpty()) {
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
        }

        items(available, key = { it.packageName }) { app ->
            AppRow(app, app.packageName in forced) { on -> toggle(app, on) }
        }

        if (locked.isNotEmpty()) {
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                Text(
                    "Not supported (${locked.size})",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "These target Android ${AppInventory.LOCKED_FROM_TARGET_SDK}. " +
                        "Android no longer lets any app change their shape, including " +
                        "this one. Nothing here can fix that.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            items(locked, key = { it.packageName }) { app ->
                AppRow(app = app, enabled = false, onToggle = {})
            }
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp)) }

        item {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        status = "Resetting everything…"
                        status = Restore.clearAll(context).fold({ it }, { "Reset failed: ${it.message}" })
                        forced.clear()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Reset everything to default") }
        }

        // Recovery only — needed if the device ever revokes Aspectly's key.
        if (!pairingExpanded) {
            item {
                TextButton(
                    onClick = { pairingExpanded = true },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                ) {
                    Text(
                        "Pair again",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PairingSection(
    port: String,
    code: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onPort: (String) -> Unit,
    onCode: (String) -> Unit,
    onPair: (Int, String) -> Unit,
) {
    if (!expanded) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "First time only — open Wireless debugging in split screen and pair.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = port,
            onValueChange = { onPort(it.filter(Char::isDigit).take(5)) },
            label = { Text("Pairing port") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = code,
            onValueChange = { onCode(it.filter(Char::isDigit).take(6)) },
            label = { Text("Pairing code") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { port.toIntOrNull()?.let { onPair(it, code) } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Pair") }
    }
}

/**
 * Opt-in, and deliberately explained rather than presented as a bare switch. It is the
 * only setting that changes what Aspectly itself can do.
 */
@Composable
private fun SelfRepairRow(
    granted: Boolean,
    onGrant: () -> Unit,
    onRevoke: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Restore after a restart",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = granted,
                onCheckedChange = { if (it) onGrant() else onRevoke() },
            )
        }
        Text(
            if (granted) {
                "Aspectly will turn wireless debugging back on after you restart, and " +
                    "put your settings back on its own."
            } else {
                "Samsung turns off USB debugging when your phone restarts, and wireless " +
                    "debugging depends on it — so your settings stop applying until you " +
                    "turn them back on. Switch this on and Aspectly will do it for you. " +
                    "It needs permission to change developer settings, which it grants " +
                    "itself over the connection it already has. You can switch this off " +
                    "at any time."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppRow(app: InstalledApp, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        app.icon?.let { drawable ->
            val bitmap = remember(app.packageName) {
                drawable.toBitmap(96, 96).asImageBitmap()
            }
            Image(bitmap, contentDescription = null, modifier = Modifier.size(36.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                supportText(app.support),
                style = MaterialTheme.typography.bodySmall,
                color = if (app.controllable) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            enabled = app.controllable,
        )
    }
}

private fun supportText(support: Support): String = when (support) {
    Support.Supported -> "Can be set to 16:9"
    Support.Self -> "This app"
    is Support.TargetsTooHigh ->
        "Not supported — targets Android ${support.targetSdk}, which no app can reshape"
}
