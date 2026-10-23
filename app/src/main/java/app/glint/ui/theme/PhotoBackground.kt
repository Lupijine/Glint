package app.glint.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import app.glint.R
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * A picture from the gallery as the background. It is copied into the app, sized for the
 * screen, and the theme's colours are taken from it, so the glass refracts the person's own
 * picture with an accent that suits it.
 */
object PhotoBackground {
    /** The theme id saved in settings while the picture is the background. */
    const val ThemeId = "photo"

    private const val Tag = "GlintPhoto"
    private const val FileName = "background.jpg"
    /** Enough for a sharp phone screen; larger pictures only cost memory. */
    private const val MaxPixels = 3_000_000

    private val mutableTheme = MutableStateFlow<AppTheme?>(null)

    /** The picture's theme, or null until a picture is chosen and loaded. */
    val theme: StateFlow<AppTheme?> = mutableTheme.asStateFlow()

    /** Plain black with silver glass, shown for the moment before the picture has loaded. */
    val loading =
        AppTheme(
            id = ThemeId,
            name = R.string.theme_photo,
            accent = Color(0xFFDDE3EE),
            secondary = Color(0xFFAEB6C4),
            background = Color(0xFF000000),
            glass = Color(0xFF111214),
            scrim = 0f,
            artwork = {},
        )

    /** Loads the saved picture in the background, so it is ready when the app is drawn. */
    fun preload(context: Context) {
        val file = File(context.filesDir, FileName)
        if (!file.isFile) return
        thread(name = "photo-background") {
            val bitmap = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
            if (bitmap != null) mutableTheme.value = themeFor(bitmap)
        }
    }

    /** Copies [uri] into the app as the background. False when it is not a readable picture. */
    suspend fun set(context: Context, uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            val metrics = context.resources.displayMetrics
            val longest = max(metrics.widthPixels, metrics.heightPixels).coerceIn(720, 2560)
            val bitmap = decode(context, uri, longest) ?: return@withContext false
            val file = File(context.filesDir, FileName)
            val temporary = File(context.filesDir, "$FileName.tmp")
            val saved = runCatching {
                    temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    if (!temporary.renameTo(file)) {
                        temporary.copyTo(file, overwrite = true)
                        temporary.delete()
                    }
                    true
                }
                .onFailure { Log.w(Tag, "The picture could not be saved", it) }
                .getOrDefault(false)
            if (!saved) return@withContext false
            mutableTheme.value = themeFor(bitmap)
            true
        }

    /** Decodes at most screen size; newer Android also turns camera photos the right way up. */
    private fun decode(context: Context, uri: Uri, longest: Int): Bitmap? =
        runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        val width = info.size.width
                        val height = info.size.height
                        val scale = targetScale(width, height, longest)
                        decoder.setTargetSize(
                            (width * scale).roundToInt().coerceAtLeast(1),
                            (height * scale).roundToInt().coerceAtLeast(1),
                        )
                        // Software pixels: the colours are read and the file is re-encoded.
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    var sample = 1
                    while (targetScale(bounds.outWidth / sample, bounds.outHeight / sample, longest) < 0.5f) sample *= 2
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                }
            }
            .onFailure { Log.w(Tag, "The picture could not be read", it) }
            .getOrNull()

    private fun targetScale(width: Int, height: Int, longest: Int): Float {
        if (width <= 0 || height <= 0) return 1f
        val bySide = longest.toFloat() / max(width, height)
        val byPixels = sqrt(MaxPixels.toFloat() / (width.toLong() * height))
        return minOf(1f, bySide, byPixels)
    }

    /** A dark glass theme coloured by the picture: its most vivid colour lightened as the accent. */
    private fun themeFor(bitmap: Bitmap): AppTheme {
        val small = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        var red = 0L
        var green = 0L
        var blue = 0L
        var vivid: Int? = null
        var vividScore = 0f
        val hsv = FloatArray(3)
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val pixel = small.getPixel(x, y)
            red += android.graphics.Color.red(pixel)
            green += android.graphics.Color.green(pixel)
            blue += android.graphics.Color.blue(pixel)
            android.graphics.Color.colorToHSV(pixel, hsv)
            val score = hsv[1] * hsv[2]
            if (hsv[2] > 0.3f && score > vividScore) {
                vividScore = score
                vivid = pixel
            }
        }
        val count = (small.width * small.height).coerceAtLeast(1)
        if (small !== bitmap) small.recycle()
        val average = Color((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
        // A nearly grey picture keeps a neutral, silvery accent.
        val base = vivid?.takeIf { vividScore > 0.15f }?.let { Color(it) } ?: Color(0xFFDDE3EE)
        val accent = lerp(base, Color.White, 0.45f)
        val image = bitmap.asImageBitmap()
        return AppTheme(
            id = ThemeId,
            name = R.string.theme_photo,
            accent = accent,
            secondary = lerp(lerp(average, Color.White, 0.5f), accent, 0.3f),
            background = lerp(average, Color.Black, 0.9f),
            glass = lerp(average, Color.Black, 0.82f),
            scrim = 0.5f,
            artwork = { drawPhoto(image) },
        )
    }
}
