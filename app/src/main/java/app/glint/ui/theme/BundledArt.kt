package app.glint.ui.theme

import android.content.Context
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Pictures that ship with Glint as theme backgrounds. Each is decoded the first time its theme
 * is drawn and then kept: at full size for the screen, and as a small copy for the previews in
 * Settings, so showing every preview does not hold every full picture in memory.
 */
internal object BundledArt {
    private const val Tag = "GlintArt"

    @Volatile private var resources: Resources? = null
    private val sizes = HashMap<Int, Int>()
    private val images = HashMap<Long, ImageBitmap>()

    fun initialize(context: Context) {
        if (resources == null) resources = context.applicationContext.resources
    }

    /** [res] decoded no smaller than [width] pixels where it can be, or null before [initialize]. */
    fun image(@DrawableRes res: Int, width: Float): ImageBitmap? {
        val resources = resources ?: return null
        return synchronized(this) {
            val fullWidth =
                sizes.getOrPut(res) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeResource(resources, res, bounds)
                    bounds.outWidth
                }
            var sample = 1
            while (fullWidth / (sample * 2) >= width && sample < 8) sample *= 2
            val key = (res.toLong() shl 4) or sample.toLong()
            images[key]
                ?: runCatching {
                        BitmapFactory.decodeResource(
                                resources,
                                res,
                                BitmapFactory.Options().apply {
                                    inSampleSize = sample
                                    inScaled = false
                                })
                            ?.asImageBitmap()
                    }
                    .onFailure { Log.w(Tag, "Theme picture $res could not be decoded", it) }
                    .getOrNull()
                    ?.also { images[key] = it }
        }
    }
}
