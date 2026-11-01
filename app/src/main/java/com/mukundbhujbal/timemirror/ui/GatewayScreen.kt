package com.mukundbhujbal.timemirror.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.mukundbhujbal.timemirror.data.AppPreferences
import com.mukundbhujbal.timemirror.phoenix.HealthState
import com.mukundbhujbal.timemirror.phoenix.MonitoringStatus
import com.mukundbhujbal.timemirror.phoenix.MonitoringStatusStore
import com.mukundbhujbal.timemirror.service.TimerOverlayService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Bounded pre-flight wait while the Gateway screen is visible. */
private const val PREFLIGHT_MAX_WAIT_MS = 2000L
private const val PREFLIGHT_POLL_MS = 200L

private data class GatewayApp(
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
    val launchable: Boolean
)

/**
 * Gateway: a small launcher for the user's monitored apps.
 *
 * It performs at most one bounded pre-flight service/recovery check per visit and always
 * launches the target app (fail-open). It never starts a timer, detector, service or polling
 * loop of its own; the existing TimerOverlayService remains the only publisher and the existing
 * TimerEngine remains the sole timer authority.
 */
@Composable
fun GatewayScreen(
    appPreferences: AppPreferences,
    onBackClick: () -> Unit,
    gatewayTarget: String? = null,
    gatewayRequestId: Long = 0L,
    onGatewayRequestHandled: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val monitoredPackages by appPreferences.monitoredPackagesFlow.collectAsState()
    val status by MonitoringStatusStore.status.collectAsState()

    // One recovery attempt per Gateway visit; never a retry loop.
    var recoveryAttempted by remember { mutableStateOf(false) }
    var launching by remember { mutableStateOf(false) }
    // Latest target that arrived while a launch/pre-flight was already running.
    var pendingTarget by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val apps = remember(monitoredPackages) { loadGatewayApps(context, monitoredPackages) }

    // One-shot widget request; the id survives recreation so the target launches exactly once.
    var lastHandledRequestId by rememberSaveable { mutableStateOf(0L) }

    fun launchPackage(packageName: String) {
        if (launching) {
            // Never run two launches at once; remember only the newest requested target.
            pendingTarget = packageName
            return
        }
        launching = true
        message = null
        scope.launch {
            runBoundedPreflight(
                context = context,
                alreadyAttempted = recoveryAttempted,
                onAttempt = { recoveryAttempted = true }
            )
            launchApp(context, packageName) { message = it }
            // Read-and-clear the pending target and release the flag without suspending, so no
            // other request can interleave; then chain into the same path. One launch at a time.
            val next = pendingTarget
            pendingTarget = null
            launching = false
            if (next != null) {
                launchPackage(next)
            }
        }
    }

    LaunchedEffect(gatewayRequestId, gatewayTarget) {
        val target = gatewayTarget ?: return@LaunchedEffect
        if (gatewayRequestId <= 0L || gatewayRequestId == lastHandledRequestId) return@LaunchedEffect
        lastHandledRequestId = gatewayRequestId
        onGatewayRequestHandled()
        launchPackage(target)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp, top = 44.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Open an app",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            text = "Launch a monitored app. The screen-time counter keeps running in the background.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = statusLabel(status),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(4.dp))

        if (apps.isEmpty()) {
            Text(
                text = "No monitored apps yet. Select the apps you want to monitor from the Dashboard first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            apps.forEach { app ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !launching && app.launchable) {
                                launchPackage(app.packageName)
                            }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        app.icon?.let { bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = app.label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (app.launchable) app.packageName else "Not installed or not launchable",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        message?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * One bounded pre-flight pass. Never blocks the launch: the caller always launches afterwards.
 *
 * - Already alive/operational: nothing to do.
 * - Starting: wait up to [PREFLIGHT_MAX_WAIT_MS] for the existing start to settle.
 * - Stopped/unknown: reuse [TimerOverlayService.startService] at most once per visit, then wait
 *   up to [PREFLIGHT_MAX_WAIT_MS].
 * - Degraded/suspended: a restart cannot improve these states, so launch immediately.
 */
private suspend fun runBoundedPreflight(
    context: Context,
    alreadyAttempted: Boolean,
    onAttempt: () -> Unit
) {
    val health = MonitoringStatusStore.status.value.health
    if (isReady(health)) return
    when (health) {
        HealthState.STARTING -> waitBounded()
        HealthState.STOPPED, HealthState.UNKNOWN -> {
            if (!alreadyAttempted) {
                onAttempt()
                try {
                    TimerOverlayService.startService(context)
                } catch (e: Exception) {
                    // Fail-open: the app still launches.
                }
                waitBounded()
            }
        }
        else -> Unit
    }
}

/** Waits at most [PREFLIGHT_MAX_WAIT_MS] for the shared status to report a usable tracker. */
private suspend fun waitBounded() {
    withTimeoutOrNull(PREFLIGHT_MAX_WAIT_MS) {
        while (!isReady(MonitoringStatusStore.status.value.health)) {
            delay(PREFLIGHT_POLL_MS)
        }
    }
}

private fun isReady(health: HealthState): Boolean =
    health == HealthState.OPERATIONAL || health == HealthState.SERVICE_ALIVE

private fun launchApp(context: Context, packageName: String, onError: (String) -> Unit) {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
    if (launchIntent == null) {
        onError("This app is not available.")
        return
    }
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(launchIntent)
    } catch (e: Exception) {
        onError("Could not open this app.")
    }
}

private fun statusLabel(status: MonitoringStatus): String = when (status.health) {
    HealthState.OPERATIONAL -> "Tracker: running"
    HealthState.SERVICE_ALIVE -> "Tracker: alive — detection not confirmed"
    HealthState.STARTING -> "Tracker: starting…"
    HealthState.DEGRADED -> "Tracker: degraded"
    HealthState.SUSPENDED -> "Tracker: suspended"
    HealthState.STOPPED -> "Tracker: stopped"
    HealthState.UNKNOWN -> "Tracker: checking…"
}

private fun loadGatewayApps(context: Context, packages: Set<String>): List<GatewayApp> {
    val packageManager = context.packageManager
    return packages.sortedBy { it }.map { packageName ->
        val launchable = packageManager.getLaunchIntentForPackage(packageName) != null
        val label = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
        val icon: Bitmap? = try {
            packageManager.getApplicationIcon(packageName).toBitmap(96, 96)
        } catch (e: Exception) {
            null
        }
        GatewayApp(
            packageName = packageName,
            label = label,
            icon = icon,
            launchable = launchable
        )
    }
}
