package app.glint.ui.theme

import android.graphics.drawable.GradientDrawable
import android.graphics.PixelFormat
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.graphics.HardwareRendererCompat
import app.glint.R
import app.glint.ShareActivity
import app.glint.ui.NewDownloadSheet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native-window integration, independent of downloader/engine initialization. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class ShareWindowTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun wrappingWindowUsesRoundedBackingAndKeepsResizeEnabledWhileTyping() {
        val windowRef = AtomicReference<Window>()
        val blurred = AtomicBoolean(false)
        val input = mutableStateOf("")
        compose.setContent {
            MaterialTheme(colorScheme = AppThemes.Chrome.colors) {
                Box(Modifier.fillMaxSize().drawBehind {
                    val stripe = 24.dp.toPx()
                    for (index in 0..(size.width / stripe).toInt()) {
                        drawRect(
                            if (index % 2 == 0) Color.White else Color.Blue,
                            Offset(index * stripe, 0f), Size(stripe, size.height),
                        )
                    }
                })
                ShareGlassDialog(onDismissRequest = {}, windowBlurEnabledOverride = true) {
                    val window = (LocalView.current.parent as DialogWindowProvider).window
                    val nativeBlur = LocalWindowBlurred.current
                    SideEffect { windowRef.set(window); blurred.set(nativeBlur) }
                    Column(Modifier.fillMaxWidth().height(200.dp).padding(24.dp)) {
                        Text("Native share window")
                        BasicTextField(
                            value = input.value,
                            onValueChange = { input.value = it },
                            modifier = Modifier.fillMaxWidth().testTag("share-window-input"),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val window = requireNotNull(windowRef.get())
            val attrs = window.attributes
            val density = window.context.resources.displayMetrics.density
            assertTrue("Window blur should be requested when the controlled capability is enabled", blurred.get())
            assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, attrs.height)
            assertEquals(PixelFormat.TRANSLUCENT, attrs.format)
            assertEquals(Gravity.BOTTOM, attrs.gravity and Gravity.VERTICAL_GRAVITY_MASK)
            assertTrue("The native blur surface must wrap the short sheet", window.decorView.height <
                window.context.resources.displayMetrics.heightPixels * 0.6f)
            assertTrue(attrs.width in 1..(560f * density).roundToInt())
            assertTrue(attrs.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
            assertEquals("Blur-behind must not frost the whole screen", 0,
                attrs.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                attrs.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
            val backing = window.decorView.background as GradientDrawable
            assertEquals(34f * density, backing.cornerRadius, 0.5f)
            assertTrue("Native frosting must retain a translucent backing", backing.alpha in 1..254)
        }
        compose.onNodeWithTag("share-window-input").performClick().performTextInput("example.test")
        compose.runOnIdle {
            assertEquals("example.test", input.value)
            assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                requireNotNull(windowRef.get()).attributes.softInputMode and
                    WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
        }
    }

    @Test
    fun capabilityAndEffectsChangesProduceOpaqueFallbackAndIsolateWindowSources() {
        val available = mutableStateOf(true)
        val effects = mutableStateOf(true)
        val windowRef = AtomicReference<Window>()
        val blurred = AtomicBoolean(false)
        val inheritedBackdrop = AtomicBoolean(true)
        compose.setContent {
            val source = rememberGlassSceneState()
            CompositionLocalProvider(
                LocalGlassEffectsEnabled provides effects.value,
                LocalGlassBackdrop provides source,
                LocalLiveGlassBackdrop provides source,
            ) {
                MaterialTheme(colorScheme = AppThemes.Gold.colors) {
                    ShareGlassDialog(onDismissRequest = {}, windowBlurEnabledOverride = available.value) {
                        val window = (LocalView.current.parent as DialogWindowProvider).window
                        val nativeBlur = LocalWindowBlurred.current
                        val inherited = LocalGlassBackdrop.current != null || LocalLiveGlassBackdrop.current != null
                        SideEffect {
                            windowRef.set(window)
                            blurred.set(nativeBlur)
                            inheritedBackdrop.set(inherited)
                        }
                        Box(Modifier.fillMaxWidth().height(180.dp))
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(blurred.get())
            assertFalse("A separate window must not inherit page graphics layers", inheritedBackdrop.get())
            available.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(blurred.get())
            val window = requireNotNull(windowRef.get())
            assertEquals(255, window.decorView.background.alpha)
            assertEquals(PixelFormat.TRANSLUCENT, window.attributes.format)
            assertEquals(0.56f, window.attributes.dimAmount, 0.001f)
            available.value = true
            effects.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse("Effects-disabled mode must override a blur-capable compositor", blurred.get())
            assertEquals(255, requireNotNull(windowRef.get()).decorView.background.alpha)
        }
    }

    @Test
    fun tappingOutsideDismissesTheBoundedDialog() = withNativeWindowDrawing {
        captureNativeFailures("physical outside touch") {
            val open = mutableStateOf(true)
            val windowRef = AtomicReference<Window>()
            val ownerRef = AtomicReference<View>()
            val dismissals = AtomicInteger()
            fun dismiss() {
                if (open.value) {
                    dismissals.incrementAndGet()
                    open.value = false
                }
            }
            compose.setContent {
                val owner = LocalView.current
                SideEffect { ownerRef.set(owner) }
                MaterialTheme(colorScheme = AppThemes.Chrome.colors) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF101820))) {
                        if (open.value) {
                            ShareGlassDialog(onDismissRequest = ::dismiss, windowBlurEnabledOverride = false) {
                                val window = (LocalView.current.parent as DialogWindowProvider).window
                                SideEffect { windowRef.set(window) }
                                // Reserve actual app space above the real scrollable form.
                                Box(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                                    NewDownloadSheet(
                                        initialUrl = "",
                                        onClose = ::dismiss,
                                        onDownload = {},
                                        windowManagedInsets = true,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            awaitCommittedWindowFrame(requireNotNull(ownerRef.get()), "outside-touch owner Activity")
            val window = waitForReadyDialog(windowRef, phase = "outside touch")
            awaitCommittedWindowFrame(window.decorView, "outside-touch native dialog")
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            val position = IntArray(2)
            var x = 0f
            var y = 0f
            instrumentation.runOnMainSync {
                val decor = window.decorView
                decor.getLocationOnScreen(position)
                x = position[0] + decor.width * 0.5f
                y = position[1] - 24f * decor.resources.displayMetrics.density
                val ownerPosition = IntArray(2)
                requireNotNull(ownerRef.get()).getLocationOnScreen(ownerPosition)
                assertTrue("Outside touch must fall in the app owner, above the sheet", y > ownerPosition[1])
            }
            // Pointer hit testing does not require the dialog to own keyboard focus.
            val downTime = SystemClock.uptimeMillis()
            for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
                try {
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    assertTrue("The physical outside touch must be delivered",
                        instrumentation.uiAutomation.injectInputEvent(event, true))
                } finally {
                    event.recycle()
                }
            }
            awaitPhase("physical outside touch must invoke the real dismiss callback") { dismissals.get() == 1 }
            awaitPhase("the outside-dismissed window must detach") {
                var detached = false
                instrumentation.runOnMainSync { detached = !window.decorView.isAttachedToWindow }
                detached
            }
        }
    }

    @Test
    fun systemBackClosesRealShareActivityAndReturnsToCaller() = withNativeWindowDrawing {
        val callerRef = AtomicReference<Activity>()
        val ownerRef = AtomicReference<View>()
        compose.setContent {
            val owner = LocalView.current
            val caller = requireNotNull(owner.context.findActivity())
            SideEffect { ownerRef.set(owner); callerRef.set(caller) }
            Box(Modifier.fillMaxSize().background(Color(0xFF101820)))
        }
        compose.waitForIdle()
        awaitCommittedWindowFrame(requireNotNull(ownerRef.get()), "real share caller Activity")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instrumentation.targetContext, ShareActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "") // Leave native probing/engine initialization idle.
        }
        ActivityScenario.launch<ShareActivity>(intent).use { scenario ->
            // Collect WMS/input state before closing the live production fixture on failure.
            captureNativeFailures("real ShareActivity system Back") {
                var decor: View? = null
                try {
                    awaitPhase("the real ShareActivity must be resumed") { scenario.state == Lifecycle.State.RESUMED }
                    val shareDecor = waitForActualShareDecor().also { decor = it }
                    awaitCommittedWindowFrame(shareDecor, "real ShareActivity native dialog")
                    instrumentation.waitForIdleSync()
                    awaitKeyboardFocus(shareDecor, "system Back")
                    // Observe an actual IME transition rather than blindly sending two Backs.
                    if (isImeVisible(shareDecor)) {
                        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                        awaitPhase("the first Back must hide the visible IME") { !isImeVisible(shareDecor) }
                        assertEquals("IME Back must leave ShareActivity open", Lifecycle.State.RESUMED, scenario.state)
                        awaitKeyboardFocus(shareDecor, "Back after IME dismissal")
                    }
                    // Synchronous native key injection waits for actual dispatch, unlike
                    // accepting an asynchronous accessibility-action PendingIntent.
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    awaitPhase("system Back must finish the real ShareActivity") { scenario.state == Lifecycle.State.DESTROYED }
                    awaitPhase("system Back must return to the resumed caller Activity") {
                        var resumed = false
                        instrumentation.runOnMainSync {
                            resumed = (callerRef.get() as LifecycleOwner).lifecycle.currentState == Lifecycle.State.RESUMED
                        }
                        resumed
                    }
                } catch (failure: Throwable) {
                    var native = "decor=null"
                    instrumentation.runOnMainSync {
                        decor?.let { view ->
                            val attrs = view.layoutParams as? WindowManager.LayoutParams
                            native = "attached=${view.isAttachedToWindow}, shown=${view.isShown}, " +
                                "focus=${view.hasWindowFocus()}, size=${view.width}x${view.height}, " +
                                "flags=${attrs?.flags}, type=${attrs?.type}, layout=${attrs?.width}x${attrs?.height}"
                        }
                    }
                    throw AssertionError("Real ShareActivity state=${scenario.state}, $native", failure)
                }
            }
        }
    }

    @Test
    fun actualDownloadSheetWrapsNativeWindowAndCloseStillWorks() {
        val open = mutableStateOf(true)
        val closed = AtomicBoolean(false)
        val windowRef = AtomicReference<Window>()
        compose.setContent {
            MaterialTheme(colorScheme = AppThemes.Chrome.colors) {
                if (open.value) {
                    ShareGlassDialog(
                        onDismissRequest = { open.value = false },
                        windowBlurEnabledOverride = false,
                    ) {
                        val window = (LocalView.current.parent as DialogWindowProvider).window
                        SideEffect { windowRef.set(window) }
                        // Empty input leaves probing idle; the test Application
                        // deliberately does not initialize the download engine.
                        NewDownloadSheet(
                            initialUrl = "",
                            onClose = { closed.set(true); open.value = false },
                            onDownload = {},
                            windowManagedInsets = true,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val window = requireNotNull(windowRef.get())
            assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, window.attributes.height)
            assertTrue("The real sheet must fit the native window's available height", window.decorView.height in
                1..window.context.resources.displayMetrics.heightPixels)
        }
        val close = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.close)
        compose.onNodeWithContentDescription(close).performClick()
        compose.waitUntil(5_000) { closed.get() }
    }

    /** A drawn touch target need not be the keyboard-focused window. */
    private fun waitForReadyDialog(reference: AtomicReference<Window>, phase: String): Window {
        val ready = AtomicReference<Window>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            awaitPhase("native dialog must be attached, shown and measured before $phase") {
                instrumentation.runOnMainSync {
                    reference.get()?.let { candidate ->
                        val decor = candidate.decorView
                        if (decor.isAttachedToWindow && decor.isShown && decor.width > 0 && decor.height > 0) {
                            ready.set(candidate)
                        }
                    }
                }
                ready.get() != null
            }
        } catch (error: AssertionError) {
            throw AssertionError("$phase: ${windowDiagnostic(reference, null)}", error)
        }
        return requireNotNull(ready.get())
    }

    private fun waitForActualShareDecor(): View {
        val ready = AtomicReference<View>()
        awaitPhase("the real ShareActivity must show its bounded native dialog") {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val candidates = WindowInspector.getGlobalWindowViews().filter { view ->
                    val attrs = view.layoutParams as? WindowManager.LayoutParams
                    view.isAttachedToWindow && view.isShown && view.width > 0 && view.height > 0 &&
                        attrs != null && attrs.height == WindowManager.LayoutParams.WRAP_CONTENT &&
                        (attrs.gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM
                }
                if (candidates.size == 1) ready.set(candidates.single())
            }
            ready.get() != null
        }
        return requireNotNull(ready.get())
    }

    private fun awaitKeyboardFocus(decor: View, phase: String) {
        awaitPhase("the real share dialog must own keyboard focus before $phase") {
            var focused = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                focused = decor.isAttachedToWindow && decor.hasWindowFocus()
            }
            focused
        }
    }

    private fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    private fun captureNativeFailures(phase: String, block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            for (command in listOf("dumpsys window", "dumpsys input")) {
                try {
                    val dump = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
                        .bufferedReader().use { it.readText() }
                    dump.chunked(3000).forEachIndexed { index, chunk ->
                        Log.e("TestRunner", "$phase: $command [$index]\n$chunk")
                    }
                } catch (dumpFailure: Exception) {
                    Log.e("TestRunner", "$phase: could not collect $command", dumpFailure)
                }
            }
            throw failure
        }
    }

    /** Production drawing is enabled; some testing-focused emulator images disable it. */
    private fun withNativeWindowDrawing(block: () -> Unit) {
        val previous = HardwareRendererCompat.isDrawingEnabled()
        Log.i("TestRunner", "Native share input fixture: initial hardware drawing enabled=$previous, ${deviceInputState()}")
        try {
            if (!previous) HardwareRendererCompat.setDrawingEnabled(true)
            block()
        } finally {
            if (!previous) HardwareRendererCompat.setDrawingEnabled(false)
        }
    }

    private fun awaitCommittedWindowFrame(view: View, phase: String) {
        val committed = CountDownLatch(1)
        val callback = Runnable { committed.countDown() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            check(view.isAttachedToWindow && view.isHardwareAccelerated) {
                "$phase must have an attached hardware-rendered surface"
            }
            view.viewTreeObserver.registerFrameCommitCallback(callback)
            view.invalidate()
        }
        try {
            assertTrue("$phase did not commit a hardware frame before native input", committed.await(10, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync {
                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.unregisterFrameCommitCallback(callback)
            }
        }
    }

    private fun windowDiagnostic(reference: AtomicReference<Window>, previous: Window?): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var native = "window=null"
        instrumentation.runOnMainSync {
            reference.get()?.let { window ->
                val decor = window.decorView
                val attrs = window.attributes
                native = "newWindow=${window !== previous}, attached=${decor.isAttachedToWindow}, " +
                    "shown=${decor.isShown}, visibility=${decor.windowVisibility}, focus=${decor.hasWindowFocus()}, " +
                    "size=${decor.width}x${decor.height}, flags=0x${Integer.toHexString(attrs.flags)}, " +
                    "type=${attrs.type}, format=${attrs.format}, alpha=${attrs.alpha}, " +
                    "layout=${attrs.width}x${attrs.height}, hardware=${decor.isHardwareAccelerated}"
            }
        }
        val active = try {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            try {
                "package=${root?.packageName}, class=${root?.className}, windowId=${root?.windowId}"
            } finally {
                @Suppress("DEPRECATION")
                root?.recycle()
            }
        } catch (error: RuntimeException) { "unavailable=${error.javaClass.simpleName}" }
        return "$native, drawingEnabled=${HardwareRendererCompat.isDrawingEnabled()}, ${deviceInputState()}, active=[$active]"
    }

    private fun deviceInputState(): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return "interactive=${power.isInteractive}, keyguardLocked=${keyguard.isKeyguardLocked}"
    }

    private fun isImeVisible(decor: View): Boolean {
        var visible = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            visible = decor.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        }
        return visible
    }

    private fun awaitPhase(description: String, timeout: Long = 5_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeout) { condition() }
        } catch (error: ComposeTimeoutException) {
            throw AssertionError("Native share window: $description (timeout ${timeout}ms)", error)
        }
    }
}
