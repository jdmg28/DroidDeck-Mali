package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R

/**
 * The Epic tab: the same shape as Steam's home, without Steam anywhere behind it. The mark on a
 * quiet purple-lit wall, the one thing to do here - play the launcher - and its settings cog. The
 * launcher itself is an AppImage staged into the runtime; until one is, the button picks it. What
 * the launcher's last session died of is said here, so a failure is read off the page, not out of
 * a log zip.
 */
@Composable
internal fun EpicHome(s: FrontEndState, a: FrontEndActions, modifier: Modifier) {
    val narrow = LocalNarrowPane.current
    val colors = MaterialTheme.colorScheme
    Box(modifier = modifier.fillMaxSize().background(
        Brush.verticalGradient(0f to colors.background, 0.62f to colors.background, 1f to colors.surface),
    )) {
        // The mark, up where the mockup puts it, with the section's rocket beside the wall glow.
        Column(Modifier.align(Alignment.Center).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Wordmark(if (narrow) 34.dp else 46.dp)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.RocketLaunch, null, tint = LocalPalette.current.signal, modifier = Modifier.padding(top = 2.dp))
                Text(stringResource(R.string.epic_subtitle), fontSize = 14.sp, color = colors.onSurfaceVariant)
            }
            if (s.importingEpic) {
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.mode_added_importing), fontSize = 13.sp, color = colors.onSurfaceVariant)
            }
        }
        Column(
            Modifier.align(Alignment.BottomStart)
                .padding(start = if (narrow) 20.dp else 44.dp, bottom = if (narrow) 18.dp else 34.dp, end = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(
                    stringResource(R.string.games_play_epic), enabled = !s.busy && !s.importingEpic,
                    main = true, large = true, icon = Icons.Filled.PlayArrow,
                    modifier = Modifier.testTag("play-epic"), onClick = a.onPlayEpic,
                )
                Cog(size = 54.dp, onClick = a.onEpicSettings)
            }
            Spacer(Modifier.height(10.dp))
            // Staged or not: what the button does the moment it is pressed.
            Note(stringResource(if (s.epicStaged) R.string.epic_staged_ready else R.string.epic_install_hint))
            // The launcher is x86_64: without FEX (set up the first time Steam runs) it cannot
            // boot, whatever the button says. Said here, before a session is spent finding out.
            if (s.epicFexReady == false) {
                Spacer(Modifier.height(8.dp))
                Note(stringResource(R.string.app_fex_not_ready, "x86_64"))
            }
            // A launcher that died on its last boot says why, in its own words, from its own log.
            s.epicError?.let { error ->
                Spacer(Modifier.height(8.dp))
                Note(stringResource(R.string.epic_last_error, error))
            }
            // The session that is out there right now, as on the rail, but in the page's own words.
            if (s.running != null) {
                Spacer(Modifier.height(8.dp))
                ResumeCard(name = s.running, onResume = a.onResume)
            }
        }
    }
}

/** Reanudar: the running session, one card, back into it (the rail's resume, page-sized). */
@Composable
private fun ResumeCard(name: String, onResume: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, pal.line2, RoundedCornerShape(12.dp))
            .clickable(onClickLabel = stringResource(R.string.resume_session)) { onResume() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Icon(Icons.Filled.PlayArrow, null, tint = pal.signal)
        Column {
            Text(stringResource(R.string.resume_session), fontSize = 12.sp, color = colors.onSurfaceVariant)
            Text(name, fontSize = 14.sp, maxLines = 1)
        }
    }
}
