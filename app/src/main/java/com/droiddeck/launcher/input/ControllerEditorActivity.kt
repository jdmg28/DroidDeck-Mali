package com.droiddeck.launcher.input

import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import com.droiddeck.launcher.ui.DroidDeckTheme
import com.droiddeck.launcher.ui.onSignal
import com.droiddeck.launcher.ui.LocalPalette

/**
 * The layout editor, Winlator-style: the pill names the profile being drawn, and the bar acts on
 * whatever the finger picked - add a control, remove it, its settings (shape, size). Every drag
 * saves the moment the finger lifts, so adding another control never reshuffles the placed ones.
 */
class ControllerEditorActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        com.droiddeck.launcher.core.AppLanguage.applyTo(this, newBase)
    }

    private lateinit var controls: OnScreenControls

    /** The group the finger last picked in the view; the bar's Quitar/Ajustes act on it. */
    private var selected by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        immersive()
        val layoutId = ControllerPrefs.activeLayoutId(this)
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF0B0F14.toInt())
        controls = OnScreenControls(this, null, editing = true, onSelection = { selected = it })
        root.addView(controls, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val bar = ComposeView(this).apply {
            setContent {
                DroidDeckTheme {
                    EditorBar(layoutId)
                }
            }
        }
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
    }

    @Composable
    private fun EditorBar(layoutId: String) {
        val activity = this
        val custom = layoutId != ControllerPrefs.LAYOUT_DEFAULT
        val name = if (custom) ControllerPrefs.layoutById(activity, layoutId)?.name ?: "" else stringResource(R.string.ctrl_profile_default)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(50))
                .padding(start = 14.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
        ) {
            Text(name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 4.dp))
            if (custom) EditorButton(stringResource(R.string.touchkeys_add), false) {
                ControllerCustoms.showAdd(activity) { controls.rebuild() }
            }
            if (custom) EditorButton(stringResource(R.string.touchkeys_remove), false, enabled = selected != null) {
                val group = selected ?: return@EditorButton
                ControllerPrefs.removeControl(activity, layoutId, group)
                selected = null
                controls.rebuild()
            }
            if (custom) EditorButton(stringResource(R.string.ctrl_settings), false, enabled = selected != null) {
                val group = selected ?: return@EditorButton
                ControllerCustoms.showElementSettings(activity, layoutId, group) { controls.rebuild() }
            }
            EditorButton(stringResource(R.string.ctrl_reset), false) { controls.resetLayout() }
            EditorButton(stringResource(R.string.common_cancel), false) { finish() }
            EditorButton(stringResource(R.string.ctrl_editor_save), true) {
                controls.saveLayout()
                finish()
            }
        }
    }

    @Composable
    private fun EditorButton(label: String, primary: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
        val pal = LocalPalette.current
        Text(
            label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = if (!enabled) Color.White.copy(alpha = 0.3f)
            else if (primary) pal.onSignal else MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.clip(RoundedCornerShape(50))
                .background(if (primary) pal.signal else Color.White.copy(alpha = if (enabled) 0.06f else 0.03f))
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    private fun immersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }
}
