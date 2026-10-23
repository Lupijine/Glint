package app.glint.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Dialogs share the glass finish, with a readable fill in their separate window. */
@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    // The page's cached artwork belongs to another window. Never sample it here or
    // inherit the share window's transparent fill: a dialog needs its own backing.
    CompositionLocalProvider(
        LocalGlassBackdrop provides null,
        LocalWindowBlurred provides false,
    ) {
        val shape = RoundedCornerShape(30.dp)
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier.glassSurface(shape),
            dismissButton = dismissButton,
            title = title,
            text = text,
            shape = shape,
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
        )
    }
}
