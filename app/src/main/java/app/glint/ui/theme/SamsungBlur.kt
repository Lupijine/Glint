package app.glint.ui.theme

import android.os.Build
import android.util.Log
import android.view.View
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * Samsung's own window blur, for Galaxy phones where Android's cross-window blur is switched
 * off (`wm disable-blur` reports "Blur supported on device: false"). One UI blurs its own
 * panels through `View.semSetBlurInfo`, which frosts whatever lies behind the view, including
 * other apps. It is not a public API, so every member is found by reflection and any failure
 * leaves the caller's opaque fallback in place. The member names and the `hidden_` variants
 * (which newer One UI releases expose) follow FrostKeys, a GPLv3 keyboard that uses the same path.
 */
internal object SamsungBlur {
    private const val Tag = "GlintBlur"

    private class Members(
        val builderForMode: Constructor<*>?,
        val builder: Constructor<*>?,
        val setBlurMode: Method?,
        val setRadius: Method?,
        val setBackgroundColor: Method?,
        val setCornerRadius: Method?,
        val build: Method,
        val setBlurInfo: Method,
        val windowMode: Int,
    )

    private val members: Members? by lazy { resolve() }

    /** True on Samsung phones whose framework exposes the blur, before trying it. */
    val available: Boolean
        get() = members != null

    /**
     * Frosts what is behind [view] with [radius], tinted with [color] (ARGB), within rounded
     * corners of [cornerRadius] pixels. False when the phone refused it.
     */
    fun apply(view: View, radius: Int, color: Int, cornerRadius: Float): Boolean {
        val m = members ?: return false
        return try {
            val builder =
                m.builderForMode?.newInstance(m.windowMode)
                    ?: m.builder!!.newInstance().also { m.setBlurMode?.invoke(it, m.windowMode) }
            m.setRadius?.invoke(builder, radius)
            m.setBackgroundColor?.invoke(builder, color)
            m.setCornerRadius?.invoke(builder, cornerRadius)
            m.setBlurInfo.invoke(view, m.build.invoke(builder))
            Log.i(Tag, "Samsung blur applied: radius=$radius on ${view.javaClass.simpleName} ${view.width}x${view.height}")
            true
        } catch (error: Throwable) {
            Log.w(Tag, "Samsung blur was refused", error)
            false
        }
    }

    fun clear(view: View) {
        val m = members ?: return
        try {
            m.setBlurInfo.invoke(view, null)
        } catch (error: Throwable) {
            Log.w(Tag, "Samsung blur could not be cleared", error)
        }
    }

    private fun resolve(): Members? {
        if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return try {
            val info = Class.forName("android.view.SemBlurInfo")
            val builderClass = Class.forName("android.view.SemBlurInfo\$Builder")
            val int = Int::class.javaPrimitiveType!!
            val float = Float::class.javaPrimitiveType!!
            val windowMode =
                (info.fields + info.declaredFields)
                    .firstOrNull { it.name == "BLUR_MODE_WINDOW" }
                    ?.apply { isAccessible = true }
                    ?.getInt(null) ?: 0
            val build = builderClass.method(listOf("hidden_build", "build"))
            val setBlurInfo = View::class.java.method(listOf("hidden_semSetBlurInfo", "semSetBlurInfo"), info)
            val builderForMode = builderClass.constructorOrNull(int)
            val builder = builderClass.constructorOrNull()
            if (build == null || setBlurInfo == null || (builderForMode == null && builder == null)) {
                Log.i(Tag, "Samsung blur unavailable: build=$build setBlurInfo=$setBlurInfo")
                return null
            }
            Members(
                builderForMode = builderForMode,
                builder = builder,
                setBlurMode = builderClass.method(listOf("hidden_setBlurMode", "setBlurMode"), int),
                setRadius = builderClass.method(listOf("hidden_setRadius", "setRadius"), int),
                setBackgroundColor = builderClass.method(listOf("hidden_setBackgroundColor", "setBackgroundColor"), int),
                setCornerRadius =
                    builderClass.method(listOf("hidden_setBackgroundCornerRadius", "setBackgroundCornerRadius"), float),
                build = build,
                setBlurInfo = setBlurInfo,
                windowMode = windowMode,
            )
        } catch (error: Throwable) {
            Log.i(Tag, "Samsung blur unavailable", error)
            null
        }
    }

    private fun Class<*>.method(names: List<String>, vararg parameters: Class<*>): Method? {
        for (name in names) {
            val found =
                runCatching { getDeclaredMethod(name, *parameters) }
                    .recoverCatching { getMethod(name, *parameters) }
                    .getOrNull()
            if (found != null) return found.apply { isAccessible = true }
        }
        return null
    }

    private fun Class<*>.constructorOrNull(vararg parameters: Class<*>): Constructor<*>? =
        runCatching { getDeclaredConstructor(*parameters).apply { isAccessible = true } }.getOrNull()
}
