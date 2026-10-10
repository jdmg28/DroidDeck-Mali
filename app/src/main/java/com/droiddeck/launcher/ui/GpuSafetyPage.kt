/* SPDX-License-Identifier: GPL-3.0-only */
package com.droiddeck.launcher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.gpu.guard.BootReason
import com.droiddeck.launcher.gpu.guard.GpuGuard
import com.droiddeck.launcher.gpu.guard.GpuGuardLog
import com.droiddeck.launcher.gpu.guard.GpuSafetyPrefs
import com.droiddeck.launcher.gpu.guard.SafeProfile

/**
 * The GPU safety page.
 *
 * It says what it knows, in the order it matters: whether the phone's last boot was a crash, what
 * the guard did about it, and what it will allow. Then the switches, each one with what it costs -
 * a frame cap is a slower session, the software UI is an ugly one, and refusing to start at all is
 * the price of not repeating a reboot.
 */
@Composable
internal fun GpuSafetyPanel(host: MenuHost, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Every switch re-reads the preferences and recomputes the guard's level when it changes.
    var tick by remember { mutableStateOf(0) }
    val p = GpuSafetyPrefs
    val enabled = remember(tick) { p.guardEnabled(context) }
    val profileId = remember(tick) { p.chosenProfile(context) }
    val lock = remember(tick) { p.lockAfterFatalBoot(context) }
    val probe = remember(tick) { p.preflightProbe(context) }
    val driverTrace = remember(tick) { p.driverTrace(context) }
    val drmFacade = remember(tick) { p.drmFacade(context) }
    val probeRequired = remember(tick) { p.probeRequired(context) }
    val watchdog = remember(tick) { p.liveWatchdog(context) }
    val stall = remember(tick) { p.stallBudgetMs(context) }
    val thermal = remember(tick) { p.thermalCeilingC(context) }
    val softwareUi = remember(tick) { p.softwareUi(context) }
    val crashes = remember(tick) { p.fatalBootCount(context) }
    val clean = remember(tick) { p.cleanSessionsSinceFatal(context) }
    val stops = remember(tick) { p.guardStops(context) }
    val locked = remember(tick) { p.lockEngaged(context) }
    val reason = remember(tick) { p.lastFatalBootReason(context) }
    val log = remember(tick) { GpuGuardLog.read(context, 40) }
    val boot = remember { BootReason.read(context.applicationContext) }

    val apply: () -> Unit = { tick++; GpuGuard.refresh(context) }

    Column(modifier = modifier.fillMaxWidth()) {
        SettingsGroup(stringResource(R.string.gpu_safety_status)) {
            // Where it came from, and what it means when it came from nowhere: an unreadable boot
            // reason leaves the latch resting on the guard's own session record, and the person
            // looking at this needs to know that is the case.
            StatusRow(stringResource(R.string.gpu_safety_boot_reason),
                if (boot.raw.isEmpty()) stringResource(R.string.gpu_safety_boot_unknown)
                else "${boot.raw} · ${kindText(boot.kind)}")
            if (boot.kind == com.droiddeck.launcher.gpu.guard.BootReason.Kind.UNKNOWN) {
                Note(stringResource(R.string.gpu_safety_boot_blind, boot.source))
            }
            StatusRow(stringResource(R.string.gpu_safety_crashes), crashes.toString())
            if (crashes > 0 && reason.isNotEmpty()) StatusRow(stringResource(R.string.gpu_safety_last_crash), reason)
            StatusRow(stringResource(R.string.gpu_safety_lock), stringResource(if (locked) R.string.gpu_safety_lock_on else R.string.gpu_safety_lock_off))
            StatusRow(stringResource(R.string.gpu_safety_clean_runs), "$clean / ${GpuSafetyPrefs.CLEAN_RUNS_TO_RELAX}")
            StatusRow(stringResource(R.string.gpu_safety_guard_stops), stops.toString())
            val lastProbe = remember(tick) { GpuGuard.lastProbe }
            StatusRow(stringResource(R.string.gpu_safety_last_probe),
                if (lastProbe == null) stringResource(R.string.gpu_safety_none_yet)
                // A skipped probe is allowed through on purpose, so saying "✓" next to it would
                // be a lie that costs a phone: it is marked as the absence of a test instead.
                else when {
                    lastProbe.skipped -> "? " + stringResource(R.string.gpu_safety_probe_skipped) +
                        " - " + lastProbe.summary
                    lastProbe.ok -> "✓ " + lastProbe.summary
                    else -> "✗ " + lastProbe.summary
                })
        }

        if (!DeviceSupport.mali()) {
            Note(stringResource(R.string.gpu_safety_not_mali))
        }

        SettingsGroup(stringResource(R.string.gpu_safety_guard)) {
            ToggleRow(host, "gpu-guard", stringResource(R.string.gpu_safety_guard_enable),
                stringResource(R.string.gpu_safety_guard_enable_hint), enabled) { on ->
                p.setGuardEnabled(context, on); apply()
            }
            ChoiceRow(
                host, "gpu-profile", stringResource(R.string.gpu_safety_profile),
                stringResource(R.string.gpu_safety_profile_hint),
                listOf(
                    GpuSafetyPrefs.PROFILE_OFF to stringResource(R.string.gpu_safety_profile_off),
                    GpuSafetyPrefs.PROFILE_SAFE to stringResource(R.string.gpu_safety_profile_safe),
                    GpuSafetyPrefs.PROFILE_STRICT to stringResource(R.string.gpu_safety_profile_strict),
                    GpuSafetyPrefs.PROFILE_SOFTWARE to stringResource(R.string.gpu_safety_profile_software),
                ), profileId, enabled = enabled, onPick = { id -> p.setChosenProfile(context, id); apply() },
            )
            if (profileId == GpuSafetyPrefs.PROFILE_SOFTWARE) {
                // Said here because it is the one setting on this page that changes what the guest can
                // see at all, and because "software mode" would otherwise be assumed to mean "slow".
                Note(stringResource(R.string.gpu_safety_software_note))
            }
            ToggleRow(host, "gpu-lock", stringResource(R.string.gpu_safety_lock_after_crash),
                stringResource(R.string.gpu_safety_lock_after_crash_hint), lock, enabled = enabled) { on ->
                p.setLockAfterFatalBoot(context, on); apply()
            }
        }

        SettingsGroup(stringResource(R.string.gpu_safety_before)) {
            ToggleRow(host, "gpu-probe", stringResource(R.string.gpu_safety_probe),
                stringResource(R.string.gpu_safety_probe_hint), probe, enabled = enabled) { on ->
                p.setPreflightProbe(context, on); apply()
            }
            ToggleRow(host, "gpu-probe-required", stringResource(R.string.gpu_safety_probe_required),
                stringResource(R.string.gpu_safety_probe_required_hint), probeRequired,
                enabled = enabled && probe) { on ->
                p.setProbeRequired(context, on); apply()
            }
        }

        SettingsGroup(stringResource(R.string.gpu_safety_during)) {
            ToggleRow(host, "gpu-watchdog", stringResource(R.string.gpu_safety_watchdog),
                stringResource(R.string.gpu_safety_watchdog_hint), watchdog, enabled = enabled) { on ->
                p.setLiveWatchdog(context, on); apply()
            }
            ToggleRow(host, "gpu-driver-trace", stringResource(R.string.gpu_safety_driver_trace),
                stringResource(R.string.gpu_safety_driver_trace_hint), driverTrace,
                enabled = enabled) { on ->
                p.setDriverTrace(context, on); apply()
            }
            ToggleRow(host, "gpu-drm-facade", stringResource(R.string.gpu_safety_drm_facade),
                stringResource(R.string.gpu_safety_drm_facade_hint), drmFacade,
                enabled = enabled) { on ->
                p.setDrmFacade(context, on); apply()
            }
            ChoiceRow(
                host, "gpu-stall", stringResource(R.string.gpu_safety_stall),
                stringResource(R.string.gpu_safety_stall_hint),
                listOf(2000, 3000, 5000, 10000).map { ms -> ms to String.format(java.util.Locale.US, "%.0f s", ms / 1000f) },
                stall, enabled = enabled && watchdog,
            ) { ms -> p.setStallBudgetMs(context, ms); apply() }
            ChoiceRow(
                host, "gpu-thermal", stringResource(R.string.gpu_safety_thermal),
                stringResource(R.string.gpu_safety_thermal_hint),
                listOf(0, 42, 44, 46).map { c -> c to (if (c == 0) stringResource(R.string.gpu_safety_thermal_off) else "$c °C") },
                thermal, enabled = enabled && watchdog,
            ) { c -> p.setThermalCeilingC(context, c); apply() }
            ToggleRow(host, "gpu-software", stringResource(R.string.gpu_safety_software_ui),
                stringResource(R.string.gpu_safety_software_ui_hint), softwareUi, enabled = enabled) { on ->
                p.setSoftwareUi(context, on); apply()
            }
        }

        SettingsGroup(stringResource(R.string.gpu_safety_record)) {
            ActionRow(stringResource(R.string.gpu_safety_copy), stringResource(R.string.gpu_safety_copy_hint),
                stringResource(R.string.gpu_safety_copy_button), onClick = { copyReport(context) })
            ActionRow(stringResource(R.string.gpu_safety_forget), stringResource(R.string.gpu_safety_forget_hint),
                stringResource(R.string.gpu_safety_forget_button), onClick = { GpuGuard.forget(context); apply() })
            if (log.isNotEmpty()) {
                Text(log, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(label, fontSize = 14.sp, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground,
            maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}

@Composable
private fun kindText(kind: BootReason.Kind): String = stringResource(
    when (kind) {
        BootReason.Kind.KERNEL_PANIC -> R.string.gpu_safety_boot_panic
        BootReason.Kind.WATCHDOG -> R.string.gpu_safety_boot_watchdog
        BootReason.Kind.THERMAL -> R.string.gpu_safety_boot_thermal
        BootReason.Kind.NORMAL -> R.string.gpu_safety_boot_normal
        else -> R.string.gpu_safety_boot_unknown
    },
)

private fun copyReport(context: Context) {
    val text = GpuGuard.report(context) + "\n\n" + GpuGuardLog.read(context, 200)
    try {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.gpu_safety_copy_label), text))
        android.widget.Toast.makeText(context, R.string.gpu_safety_copied, android.widget.Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, R.string.gpu_safety_copy_failed, android.widget.Toast.LENGTH_SHORT).show()
    }
}

/**
 * The lock: the phone's last boot was recorded as a crash and the guard is refusing to start a
 * session until someone says they know.
 *
 * This is the one place the app says no and means it. It is a dialog and not a toast because a
 * decision is being asked for, and it carries the settings because the honest answer to "why
 * can't I play" is a page the user can change, not a sentence they have to accept.
 */
@Composable
fun GpuSafetyLockDialog(onRearm: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    val crashes = GpuSafetyPrefs.fatalBootCount(context)
    val reason = GpuSafetyPrefs.lastFatalBootReason(context)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gpu_guard_lock_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.gpu_guard_lock_text, crashes, reason.ifEmpty { "—" }), fontSize = 14.sp)
                if (showSettings) {
                    val host = rememberMenuHost()
                    androidx.compose.foundation.layout.Spacer(
                        Modifier.fillMaxWidth().height(10.dp),
                    )
                    GpuSafetyPanel(host)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRearm) { Text(stringResource(R.string.gpu_guard_rearm)) }
        },
        dismissButton = {
            TextButton(onClick = { showSettings = !showSettings }) {
                Text(stringResource(if (showSettings) R.string.gpu_guard_hide_settings else R.string.gpu_guard_show_settings))
            }
        },
    )
}

/** The profile a launch will actually run under, for a card that has to say so. */
internal fun profileLabel(context: Context): String = context.getString(
    when (GpuGuard.profile) {
        SafeProfile.OFF -> R.string.gpu_safety_profile_off
        SafeProfile.SAFE -> R.string.gpu_safety_profile_safe
        SafeProfile.STRICT -> R.string.gpu_safety_profile_strict
        // The fourth row has to be nameable here too, and it is the one whose name is most likely to be
        // misread: the card says what a launch will run under, and "software" is a different axis from
        // the other three - not more limits on the same driver, no driver in the guest at all.
        SafeProfile.SOFTWARE -> R.string.gpu_safety_profile_software
    },
)

/**
 * The guard's notice over a running session: amber while it is only a warning, red once the
 * session has been stopped. It has to be impossible to miss and impossible to confuse with the
 * HUD - this is the app saying the GPU is not answering, which on this device is the sentence
 * that used to be followed by a reboot.
 */
@Composable
fun GpuGuardAlert() {
    val alert = GpuGuard.alert ?: return
    val stopped = GpuGuard.sessionStopped
    val pal = LocalPalette.current
    val tone = if (stopped) pal.error else AttentionAmber
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter,
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 20.dp)
                .clip(Shape12)
                .background(tone.copy(alpha = 0.92f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            androidx.compose.foundation.layout.Column {
                Text(
                    stringResource(if (stopped) R.string.gpu_guard_session_stopped else R.string.gpu_guard_alert_title),
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.Black,
                )
                Text(alert, fontSize = 13.sp, color = androidx.compose.ui.graphics.Color.Black)
            }
        }
    }
}
