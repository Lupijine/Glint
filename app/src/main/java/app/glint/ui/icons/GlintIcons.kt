package app.glint.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Glint's 24dp icons share an open outline, soft corners, and the same optical weight. */
object GlintIcons {
    val Download = outline("Download") {
        moveTo(12f, 3.5f)
        lineTo(12f, 14f)
        moveTo(8f, 10f)
        lineTo(12f, 14f)
        lineTo(16f, 10f)
        moveTo(4.5f, 14f)
        lineTo(4.5f, 18f)
        curveTo(4.5f, 19.4f, 5.6f, 20.5f, 7f, 20.5f)
        lineTo(17f, 20.5f)
        curveTo(18.4f, 20.5f, 19.5f, 19.4f, 19.5f, 18f)
        lineTo(19.5f, 14f)
    }

    val Library = outline("Library") {
        moveTo(15f, 3.5f)
        lineTo(5.5f, 3.5f)
        curveTo(4.4f, 3.5f, 3.5f, 4.4f, 3.5f, 5.5f)
        lineTo(3.5f, 16f)
        roundedRect(7.5f, 7f, 20.5f, 20.5f, 2f)
        moveTo(12.5f, 11f)
        lineTo(16.5f, 13.8f)
        lineTo(12.5f, 16.5f)
        close()
    }

    val Commands = outline("Commands") {
        roundedRect(3f, 5f, 21f, 19f, 3f)
        moveTo(7f, 9f)
        lineTo(10f, 12f)
        lineTo(7f, 15f)
        moveTo(13.5f, 15f)
        lineTo(17f, 15f)
    }

    val Settings = outline("Settings") {
        moveTo(3.5f, 6f)
        lineTo(6.5f, 6f)
        moveTo(11.5f, 6f)
        lineTo(20.5f, 6f)
        circle(9f, 6f, 2.5f)
        moveTo(3.5f, 12f)
        lineTo(12.5f, 12f)
        moveTo(17.5f, 12f)
        lineTo(20.5f, 12f)
        circle(15f, 12f, 2.5f)
        moveTo(3.5f, 18f)
        lineTo(6.5f, 18f)
        moveTo(11.5f, 18f)
        lineTo(20.5f, 18f)
        circle(9f, 18f, 2.5f)
    }

    val Plus = outline("Plus") {
        moveTo(12f, 5f)
        lineTo(12f, 19f)
        moveTo(5f, 12f)
        lineTo(19f, 12f)
    }

    val Paste = outline("Paste") {
        moveTo(8f, 5f)
        lineTo(6.5f, 5f)
        curveTo(5.1f, 5f, 4f, 6.1f, 4f, 7.5f)
        lineTo(4f, 18.5f)
        curveTo(4f, 19.9f, 5.1f, 21f, 6.5f, 21f)
        lineTo(17.5f, 21f)
        curveTo(18.9f, 21f, 20f, 19.9f, 20f, 18.5f)
        lineTo(20f, 7.5f)
        curveTo(20f, 6.1f, 18.9f, 5f, 17.5f, 5f)
        lineTo(16f, 5f)
        roundedRect(8f, 3f, 16f, 7f, 1.5f)
        moveTo(8.5f, 12f)
        lineTo(15.5f, 12f)
        moveTo(8.5f, 16f)
        lineTo(13f, 16f)
    }

    val Link = outline("Link") {
        moveTo(9f, 15f)
        lineTo(15f, 9f)
        moveTo(8.5f, 11.5f)
        lineTo(6f, 14f)
        curveTo(4.3f, 15.7f, 4.3f, 18.3f, 6f, 20f)
        curveTo(7.7f, 21.7f, 10.3f, 21.7f, 12f, 20f)
        lineTo(14.5f, 17.5f)
        moveTo(9.5f, 6.5f)
        lineTo(12f, 4f)
        curveTo(13.7f, 2.3f, 16.3f, 2.3f, 18f, 4f)
        curveTo(19.7f, 5.7f, 19.7f, 8.3f, 18f, 10f)
        lineTo(15.5f, 12.5f)
    }

    val Close = outline("Close") {
        moveTo(6.5f, 6.5f)
        lineTo(17.5f, 17.5f)
        moveTo(17.5f, 6.5f)
        lineTo(6.5f, 17.5f)
    }

    val Video = outline("Video") {
        roundedRect(3f, 6.5f, 16f, 17.5f, 2.5f)
        moveTo(16f, 10f)
        lineTo(21f, 7.5f)
        lineTo(21f, 16.5f)
        lineTo(16f, 14f)
    }

    val Audio = outline("Audio") {
        moveTo(9f, 17f)
        lineTo(9f, 5f)
        lineTo(20f, 3f)
        lineTo(20f, 15f)
        moveTo(9f, 9f)
        lineTo(20f, 7f)
        circle(6f, 17.5f, 3f)
        circle(17f, 15.5f, 3f)
    }

    val Retry = outline("Retry") {
        moveTo(19.3f, 8.5f)
        curveTo(18f, 5.8f, 15.3f, 4f, 12f, 4f)
        curveTo(7.6f, 4f, 4f, 7.6f, 4f, 12f)
        curveTo(4f, 16.4f, 7.6f, 20f, 12f, 20f)
        curveTo(15.8f, 20f, 19f, 17.3f, 19.8f, 13.8f)
        moveTo(19.5f, 3.5f)
        lineTo(19.5f, 8.5f)
        lineTo(14.5f, 8.5f)
    }

    val Open = outline("Open") {
        moveTo(8f, 5f)
        lineTo(19f, 12f)
        lineTo(8f, 19f)
        close()
    }

    val Cancel = Close

    val Search = outline("Search") {
        circle(10.5f, 10.5f, 6.5f)
        moveTo(15.3f, 15.3f)
        lineTo(20.5f, 20.5f)
    }

    val More = outline("More") {
        circle(5.5f, 12f, 0.9f)
        circle(12f, 12f, 0.9f)
        circle(18.5f, 12f, 0.9f)
    }

    val Share = outline("Share") {
        moveTo(12f, 15f)
        lineTo(12f, 3.5f)
        moveTo(8f, 7.5f)
        lineTo(12f, 3.5f)
        lineTo(16f, 7.5f)
        moveTo(8.5f, 10.5f)
        lineTo(7f, 10.5f)
        curveTo(5.6f, 10.5f, 4.5f, 11.6f, 4.5f, 13f)
        lineTo(4.5f, 18f)
        curveTo(4.5f, 19.4f, 5.6f, 20.5f, 7f, 20.5f)
        lineTo(17f, 20.5f)
        curveTo(18.4f, 20.5f, 19.5f, 19.4f, 19.5f, 18f)
        lineTo(19.5f, 13f)
        curveTo(19.5f, 11.6f, 18.4f, 10.5f, 17f, 10.5f)
        lineTo(15.5f, 10.5f)
    }

    val Trash = outline("Trash") {
        moveTo(4f, 6.5f)
        lineTo(20f, 6.5f)
        moveTo(9.5f, 6.5f)
        lineTo(9.5f, 4.5f)
        curveTo(9.5f, 3.9f, 9.9f, 3.5f, 10.5f, 3.5f)
        lineTo(13.5f, 3.5f)
        curveTo(14.1f, 3.5f, 14.5f, 3.9f, 14.5f, 4.5f)
        lineTo(14.5f, 6.5f)
        moveTo(6f, 6.5f)
        lineTo(7f, 18.6f)
        curveTo(7.1f, 19.7f, 8f, 20.5f, 9.1f, 20.5f)
        lineTo(14.9f, 20.5f)
        curveTo(16f, 20.5f, 16.9f, 19.7f, 17f, 18.6f)
        lineTo(18f, 6.5f)
        moveTo(10f, 10.5f)
        lineTo(10.3f, 16.5f)
        moveTo(14f, 10.5f)
        lineTo(13.7f, 16.5f)
    }

    val Check = outline("Check") {
        moveTo(5f, 12.5f)
        lineTo(9.5f, 17f)
        lineTo(19f, 7.5f)
    }

    val Folder = outline("Folder") {
        moveTo(3.5f, 7f)
        curveTo(3.5f, 5.6f, 4.6f, 4.5f, 6f, 4.5f)
        lineTo(9.3f, 4.5f)
        lineTo(11.3f, 6.8f)
        lineTo(18f, 6.8f)
        curveTo(19.4f, 6.8f, 20.5f, 7.9f, 20.5f, 9.3f)
        lineTo(20.5f, 17f)
        curveTo(20.5f, 18.4f, 19.4f, 19.5f, 18f, 19.5f)
        lineTo(6f, 19.5f)
        curveTo(4.6f, 19.5f, 3.5f, 18.4f, 3.5f, 17f)
        close()
    }

    val Chevron = outline("Chevron", autoMirror = true) {
        moveTo(9.5f, 5.5f)
        lineTo(16f, 12f)
        lineTo(9.5f, 18.5f)
    }

    val ChevronDown = outline("ChevronDown") {
        moveTo(5.5f, 9.5f)
        lineTo(12f, 16f)
        lineTo(18.5f, 9.5f)
    }

    val Playlist = outline("Playlist") {
        moveTo(3.5f, 6f)
        lineTo(16.5f, 6f)
        moveTo(3.5f, 11f)
        lineTo(16.5f, 11f)
        moveTo(3.5f, 16f)
        lineTo(10.5f, 16f)
        moveTo(14.5f, 14f)
        lineTo(20.5f, 17.5f)
        lineTo(14.5f, 21f)
        close()
    }

    val Copy = outline("Copy") {
        roundedRect(8.5f, 8.5f, 20.5f, 20.5f, 2.5f)
        moveTo(15.5f, 8.5f)
        lineTo(15.5f, 6f)
        curveTo(15.5f, 4.6f, 14.4f, 3.5f, 13f, 3.5f)
        lineTo(6f, 3.5f)
        curveTo(4.6f, 3.5f, 3.5f, 4.6f, 3.5f, 6f)
        lineTo(3.5f, 13f)
        curveTo(3.5f, 14.4f, 4.6f, 15.5f, 6f, 15.5f)
        lineTo(8.5f, 15.5f)
    }

    val Pause = outline("Pause") {
        moveTo(8.5f, 5.5f)
        lineTo(8.5f, 18.5f)
        moveTo(15.5f, 5.5f)
        lineTo(15.5f, 18.5f)
    }

    val Scissors = outline("Scissors") {
        circle(6.5f, 7f, 2.8f)
        circle(6.5f, 17f, 2.8f)
        moveTo(8.8f, 8.6f)
        lineTo(20f, 17.5f)
        moveTo(8.8f, 15.4f)
        lineTo(20f, 6.5f)
    }

    /** Following a channel: a feed's dot and waves. */
    val Follow = outline("Follow") {
        circle(6f, 18f, 1.2f)
        moveTo(4.5f, 11.5f)
        curveTo(8.6f, 11.5f, 12.5f, 15.4f, 12.5f, 19.5f)
        moveTo(4.5f, 4.5f)
        curveTo(12.8f, 4.5f, 19.5f, 11.2f, 19.5f, 19.5f)
    }

    val Globe = outline("Globe") {
        circle(12f, 12f, 8.5f)
        moveTo(3.5f, 12f)
        lineTo(20.5f, 12f)
        moveTo(12f, 3.5f)
        curveTo(9.5f, 6f, 8.5f, 9f, 8.5f, 12f)
        curveTo(8.5f, 15f, 9.5f, 18f, 12f, 20.5f)
        moveTo(12f, 3.5f)
        curveTo(14.5f, 6f, 15.5f, 9f, 15.5f, 12f)
        curveTo(15.5f, 15f, 14.5f, 18f, 12f, 20.5f)
    }
}

private fun outline(
    name: String,
    autoMirror: Boolean = false,
    draw: PathBuilder.() -> Unit,
): ImageVector =
    ImageVector.Builder(
            name = "Glint.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = autoMirror,
        )
        .apply {
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = draw,
            )
        }
        .build()

private fun PathBuilder.roundedRect(left: Float, top: Float, right: Float, bottom: Float, r: Float) {
    moveTo(left + r, top)
    lineTo(right - r, top)
    curveTo(right - r * 0.45f, top, right, top + r * 0.45f, right, top + r)
    lineTo(right, bottom - r)
    curveTo(right, bottom - r * 0.45f, right - r * 0.45f, bottom, right - r, bottom)
    lineTo(left + r, bottom)
    curveTo(left + r * 0.45f, bottom, left, bottom - r * 0.45f, left, bottom - r)
    lineTo(left, top + r)
    curveTo(left, top + r * 0.45f, left + r * 0.45f, top, left + r, top)
    close()
}

private fun PathBuilder.circle(x: Float, y: Float, r: Float) {
    val c = r * 0.5523f
    moveTo(x + r, y)
    curveTo(x + r, y + c, x + c, y + r, x, y + r)
    curveTo(x - c, y + r, x - r, y + c, x - r, y)
    curveTo(x - r, y - c, x - c, y - r, x, y - r)
    curveTo(x + c, y - r, x + r, y - c, x + r, y)
    close()
}
