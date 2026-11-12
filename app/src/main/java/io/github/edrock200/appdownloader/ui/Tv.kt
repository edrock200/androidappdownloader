package io.github.edrock200.appdownloader.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Shapes matching the Material components the highlight is drawn around. */
object FocusShapes {
    val Button: Shape = CircleShape
    val Icon: Shape = CircleShape
    val Row: Shape = RoundedCornerShape(8.dp)
    val Fab: Shape = RoundedCornerShape(16.dp)
}

/**
 * Draws a thick outline (and optionally enlarges the element) while it has focus,
 * so the selected control is obvious from across the room when using a TV remote.
 * Place it before the component's own clickable modifier.
 */
fun Modifier.focusHighlight(shape: Shape = FocusShapes.Button, scaleUp: Boolean = true): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused && scaleUp) 1.08f else 1f, label = "focusScale")
    val ring = MaterialTheme.colorScheme.onSurface
    this
        .onFocusChanged { focused = it.isFocused }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .then(if (focused) Modifier.border(3.dp, ring, shape) else Modifier)
}

object Device {
    fun isTv(context: Context): Boolean {
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            isFireTv(context)
    }

    fun isFireTv(context: Context): Boolean =
        context.packageManager.hasSystemFeature("amazon.hardware.fire_tv")

    /** Where the user finds the "install unknown apps" switch on this kind of device. */
    fun installPermissionHelp(context: Context): String = when {
        isFireTv(context) && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O ->
            "On Fire TV: Settings › My Fire TV › Developer options › turn on \"Apps from Unknown Sources\"."
        isFireTv(context) ->
            "On Fire TV: Settings › My Fire TV › Developer options › Install unknown apps › App Downloader."
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O ->
            "Open Settings › Security (or Security & restrictions) and turn on \"Unknown sources\"."
        isTv(context) ->
            "On Android TV / Google TV: Settings › Apps › Security & restrictions › Unknown sources › " +
                "App Downloader. (Older models: Settings › Device Preferences › Security & restrictions.)"
        else ->
            "Turn on \"Allow from this source\" for App Downloader on the next screen."
    }
}

@Composable
fun rememberIsTv(): Boolean {
    val context = LocalContext.current
    return remember { Device.isTv(context) }
}
