package app.glint.ui.theme

import android.graphics.Bitmap
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.graphics.HardwareRendererCompat
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import app.glint.ui.EmptyCard
import app.glint.ui.GlassSegmented
import app.glint.ui.LargeTitle
import app.glint.ui.icons.GlintIcons
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** These tests require a hardware-rendered Android window; JVM stubs cannot validate AGSL. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class GlassRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun shaderCompilesAndCreatesTheGpuLens() {
        val lens = GlassRefraction.create()
        assertNotNull("The AGSL shader must compile, rather than silently use a blur fallback", lens)
        assertNotNull(
            "The compiled shader must produce a panel render effect",
            lens!!.effect(252f, 132f, 28f, density = 1f, overscan = 24f, frostRadius = 0f),
        )
    }

    @Test
    fun lensDisplacesBackgroundEdgesAndUpdatesWithDrawOnlySourceChanges() {
        val phase = mutableFloatStateOf(8f)
        val showLens = mutableStateOf(false)
        val lensCompositions = AtomicInteger()

        compose.setContent {
            MaterialTheme(colorScheme = AppThemes.Chrome.colors) {
                val scene = rememberGlassSceneState()
                Box(Modifier.size(300.dp, 360.dp).testTag("glass-render-scene")) {
                    GlassScene(scene, Modifier.matchParentSize()) {
                        // phase is read only in drawing. Updating it must not recompose the lens.
                        Box(
                            Modifier.fillMaxSize().drawBehind {
                                val stripe = 40.dp.toPx()
                                val offset = phase.floatValue.dp.toPx()
                                val palette = arrayOf(
                                    Color(0xFFE7474F),
                                    Color(0xFF366FE7),
                                    Color(0xFF38C871),
                                )
                                val first = floor(offset / stripe).toInt()
                                for (index in first..first + (size.width / stripe).toInt() + 1) {
                                    drawRect(
                                        palette[Math.floorMod(index, palette.size)],
                                        topLeft = Offset(index * stripe - offset, 0f),
                                        size = Size(stripe, size.height),
                                    )
                                }
                            },
                        )
                    }
                    if (showLens.value) {
                        CompositionLocalProvider(LocalLiveGlassBackdrop provides scene) {
                            SideEffect { lensCompositions.incrementAndGet() }
                            Box(
                                Modifier.offset(24.dp, 100.dp)
                                    .size(252.dp, 132.dp)
                                    .liquidGlass(RoundedCornerShape(28.dp)),
                            )
                        }
                    }
                }
            }
        }

        val plain = capture("01-source")
        compose.runOnIdle { showLens.value = true }
        val refracted = capture("02-lens")
        val scale = plain.width / 300f
        val x = (27f * scale).roundToInt()
        val y = (166f * scale).roundToInt()
        val sourcePixel = plain.toPixelMap()[x, y]
        val lensPixel = refracted.toPixelMap()[x, y]

        // The unfiltered stripe switches from red to blue at x=32dp. The lens's curved
        // left edge at x=27dp samples beyond that boundary. Neutral frost/tint alone
        // cannot reverse this red/blue ordering: it requires sampling another position.
        assertTrue("Reference stripe should be red at the probe", sourcePixel.red - sourcePixel.blue > 0.25f)
        assertTrue(
            "The glass edge must bend the blue stripe into the red probe position; " +
                "got red=${lensPixel.red}, blue=${lensPixel.blue}",
            lensPixel.blue - lensPixel.red > 0.05f,
        )

        val compositionsBefore = lensCompositions.get()
        compose.runOnIdle { phase.floatValue = 28f }
        compose.waitForIdle()
        val updated = capture("03-live-update")
        val difference = interiorDifference(refracted, updated)
        assertTrue(
            "Changing a draw-only source must refresh pixels inside the lens; difference=$difference",
            difference > 0.06f,
        )
        assertEquals(
            "Backdrop refresh should not depend on recomposing the glass",
            compositionsBefore,
            lensCompositions.get(),
        )
    }

    @Test
    fun captureGoldMaterialHarnessWithRealSharedWidgets() {
        val captureView = AtomicReference<View>()
        compose.setContent {
            CompositionLocalProvider(LocalAppTheme provides AppThemes.Gold) {
                MaterialTheme(colorScheme = AppThemes.Gold.colors) {
                    val view = LocalView.current
                    SideEffect { captureView.set(view.rootView) }
                    GlassBackdrop(Modifier.fillMaxSize().testTag("glass-render-scene")) {
                        val scene = rememberGlassSceneState()
                        GlassScene(scene, Modifier.fillMaxSize()) {
                            Column {
                                LargeTitle("Downloads")
                                Spacer(Modifier.height(16.dp))
                                GlassSegmented(
                                    options = listOf("All", "Downloading", "Completed"),
                                    selected = "All",
                                    label = { it },
                                    onSelect = {},
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                )
                                Spacer(Modifier.height(20.dp))
                                EmptyCard(
                                    icon = GlintIcons.Download,
                                    title = "No downloads yet",
                                    body = "Copy a link and tap +, or share to Glint from any app.",
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                )
                            }
                        }
                        CompositionLocalProvider(LocalLiveGlassBackdrop provides scene) {
                            Row(
                                Modifier.align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 18.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // The real glass modifier and icons, in a small material
                                // harness. This intentionally does not launch GlintApp.
                                Row(
                                    Modifier.weight(1f).height(66.dp).liquidGlass(CircleShape).padding(5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val tabs = listOf(
                                        "Downloads" to GlintIcons.Download,
                                        "Library" to GlintIcons.Library,
                                        "Commands" to GlintIcons.Commands,
                                        "Settings" to GlintIcons.Settings,
                                    )
                                    tabs.forEach { (label, icon) ->
                                        Column(
                                            Modifier.weight(1f).fillMaxHeight(),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center,
                                        ) {
                                            Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
                                            Spacer(Modifier.height(3.dp))
                                            Text(label, color = Color.White, fontSize = 10.sp)
                                        }
                                    }
                                }
                                Box(
                                    Modifier.size(66.dp).liquidGlass(
                                        CircleShape,
                                        tint = AppThemes.Gold.accent.copy(alpha = 0.14f),
                                    ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(GlintIcons.Plus, null, tint = Color.White, modifier = Modifier.size(28.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // This PNG is evidence for visual review of the real Android-rendered material,
        // not a golden assertion or a screenshot of the complete application lifecycle.
        compose.waitForIdle()
        capture("04-gold-material-harness", warmupView = checkNotNull(captureView.get()))
    }

    @Test
    fun unrelatedWindowDrawsReuseTheSceneButSourceChangesRefreshIt() {
        val color = mutableStateOf(Color(0xFFE7474F))
        var sceneState: GlassBackdropState? = null
        var hostView: View? = null
        compose.setContent {
            MaterialTheme(colorScheme = AppThemes.Chrome.colors) {
                val scene = rememberGlassSceneState()
                sceneState = scene
                hostView = LocalView.current
                Box(Modifier.size(240.dp).testTag("scene-draw-boundary")) {
                    GlassScene(scene, Modifier.matchParentSize()) {
                        Box(Modifier.fillMaxSize().drawBehind { drawRect(color.value) })
                    }
                    CompositionLocalProvider(LocalLiveGlassBackdrop provides scene) {
                        Box(
                            Modifier.offset(30.dp, 70.dp)
                                .size(180.dp, 100.dp)
                                .liquidGlass(RoundedCornerShape(28.dp)),
                        )
                    }
                }
            }
        }

        fun frame() = captureAfterHardwareWarmup(checkNotNull(hostView).rootView, "scene-draw-boundary")
        compose.waitForIdle()
        val before = frame()
        val firstRevision = compose.runOnIdle { checkNotNull(sceneState).revision }
        assertTrue("The source must have recorded its initial content", firstRevision > 0)

        repeat(4) {
            // This causes an actual window repaint, independently of Compose state.
            // Consumers must not induce source rerecording just because their common
            // root is painted or because captureToImage waits for a committed frame.
            compose.runOnIdle { checkNotNull(hostView).invalidate() }
            compose.waitForIdle()
            frame()
            assertEquals(
                "An unchanged scene must reuse its display list after a root View redraw",
                firstRevision,
                compose.runOnIdle { checkNotNull(sceneState).revision },
            )
        }

        compose.runOnIdle { color.value = Color(0xFF366FE7) }
        compose.waitForIdle()
        val after = frame()
        assertTrue(
            "A draw-only child change must still invalidate the scene layer",
            compose.runOnIdle { checkNotNull(sceneState).revision } > firstRevision,
        )
        val x = before.width / 2
        val y = before.height / 2
        val oldPixel = before.toPixelMap()[x, y]
        val newPixel = after.toPixelMap()[x, y]
        assertTrue(
            "The glass must display the changed source despite the display-list boundary",
            abs(oldPixel.red - newPixel.red) + abs(oldPixel.blue - newPixel.blue) > 0.3f,
        )
    }

    @Test
    fun scrollingACachedCardKeepsItsSampleAlignedWithTheFixedWallpaper() {
        val wallpaper = AppTheme(
            id = "scroll-regression",
            name = AppThemes.Chrome.name,
            accent = AppThemes.Chrome.accent,
            secondary = AppThemes.Chrome.secondary,
            background = AppThemes.Chrome.background,
            glass = AppThemes.Chrome.glass,
            scrim = 0f,
            artwork = {
                val band = 40.dp.toPx()
                val colors = arrayOf(Color(0xFFE7474F), Color(0xFF366FE7), Color(0xFF38C871))
                for (index in 0..(size.height / band).toInt()) {
                    drawRect(
                        colors[index % colors.size],
                        topLeft = Offset(0f, index * band),
                        size = Size(size.width, band),
                    )
                }
            },
        )
        lateinit var list: LazyListState
        lateinit var scope: CoroutineScope
        compose.setContent {
            CompositionLocalProvider(LocalAppTheme provides wallpaper) {
                MaterialTheme(colorScheme = wallpaper.colors) {
                    GlassBackdrop(Modifier.size(300.dp, 360.dp).testTag("glass-render-scene")) {
                        list = rememberLazyListState()
                        scope = rememberCoroutineScope()
                        LazyColumn(Modifier.fillMaxSize(), state = list) {
                            item(key = "spacer") { Spacer(Modifier.height(80.dp)) }
                            item(key = "cached-card") {
                                // A tall settings-style card is moved by LazyColumn's
                                // placement; its shader/source inputs remain unchanged.
                                Box(
                                    Modifier.fillMaxWidth()
                                        .padding(horizontal = 24.dp)
                                        .height(600.dp)
                                        .glassSurface(RoundedCornerShape(28.dp)),
                                )
                            }
                        }
                    }
                }
            }
        }

        val before = capture("05-scroll-before")
        compose.runOnIdle { scope.launch { list.scrollToItem(1) } }
        compose.waitForIdle()
        assertEquals("The real LazyColumn must have moved the card by 80dp", 1, list.firstVisibleItemIndex)
        val after = capture("06-scroll-after")
        val oldPixels = before.toPixelMap()
        val newPixels = after.toPixelMap()
        val scale = before.width / 300f

        // These are fixed viewport positions, not positions local to the moving card.
        // Both fall in green wallpaper bands before/after correct world-space sampling.
        // A sample carried along with the card instead sees blue at 220dp and runs
        // beyond the original wallpaper texture into a black rectangle at 340dp.
        for (worldY in listOf(220f, 340f)) {
            val x = (150f * scale).roundToInt()
            val y = (worldY * scale).roundToInt()
            val a = oldPixels[x, y]
            val b = newPixels[x, y]
            assertTrue(
                "Reference wallpaper should be green at y=$worldY: $a",
                a.green - a.blue > 0.15f && a.green - a.red > 0.15f,
            )
            assertTrue(
                "Scrolling must keep wallpaper aligned and avoid a black sample cutoff " +
                    "at y=$worldY: before=$a, after=$b",
                b.green - b.blue > 0.15f && b.green - b.red > 0.15f,
            )
            assertTrue(
                "A static wallpaper's world-space hue must not travel with the card at y=$worldY",
                abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue) < 0.12f,
            )
        }
    }

    private fun capture(name: String, warmupView: View? = null): ImageBitmap {
        compose.waitForIdle()
        val image = captureAfterHardwareWarmup(warmupView ?: resumedWindowDecor())
        val fullResolution = image.asAndroidBitmap()
        // Keep assertions at native resolution; only the logged diagnostic PNG is
        // reduced so diagnostic screenshots stay small enough for the CI job log.
        val preview = if (fullResolution.width > 600) {
            Bitmap.createScaledBitmap(
                fullResolution,
                600,
                (fullResolution.height * (600f / fullResolution.width)).roundToInt().coerceAtLeast(1),
                true,
            )
        } else fullResolution
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/GlintGlassTest/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        // AGP uninstalls the test target after connectedAndroidTest. Public MediaStore
        // images survive uninstall, unlike filesDir, and inserting our own images on
        // API 33 needs no storage or media-reading permission.
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        try {
            checkNotNull(resolver.openOutputStream(uri, "w")).use {
                check(preview.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) == 1)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        } finally {
            if (preview !== fullResolution) preview.recycle()
        }
        return image
    }

    /**
     * The pinned Compose screenshot implementation allows only 2s for a
     * FrameCommitCallback, which can expire on Android 16/SwiftShader even after
     * earlier captures succeed. Warm every capture through a committed frame on
     * the real hardware path; all pixels still come from captureToImage/PixelCopy.
     */
    private fun captureAfterHardwareWarmup(view: View, tag: String = "glass-render-scene"): ImageBitmap {
        val wasDrawingEnabled = HardwareRendererCompat.isDrawingEnabled()
        try {
            if (!wasDrawingEnabled) HardwareRendererCompat.setDrawingEnabled(true)
            awaitCommittedHardwareFrame(view)
            return try {
                compose.onNodeWithTag(tag).captureToImage()
            } catch (error: ComposeTimeoutException) {
                val uncommittedFrame = error.stackTrace.any {
                    it.className == "androidx.compose.ui.test.android.WindowCapture_androidKt" &&
                        it.methodName == "waitUntil"
                }
                // Retry only this known capture-synchronization timeout, exactly once.
                // Optical assertions, PixelCopy failures and other timeouts stay failures.
                if (!uncommittedFrame) throw error
                awaitCommittedHardwareFrame(view)
                compose.onNodeWithTag(tag).captureToImage()
            }
        } finally {
            if (!wasDrawingEnabled) HardwareRendererCompat.setDrawingEnabled(false)
        }
    }

    private fun resumedWindowDecor(): View {
        val view = AtomicReference<View>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).single()
            view.set(activity.window.decorView)
        }
        return checkNotNull(view.get())
    }

    private fun awaitCommittedHardwareFrame(view: View) {
        val committed = CountDownLatch(1)
        val callback = Runnable { committed.countDown() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            check(view.isAttachedToWindow && view.isHardwareAccelerated) {
                "Capture must use an attached, hardware-rendered Android window"
            }
            view.viewTreeObserver.registerFrameCommitCallback(callback)
            view.invalidate()
        }
        try {
            check(committed.await(10, TimeUnit.SECONDS)) {
                "No real Android hardware frame committed during the 10s diagnostic warmup"
            }
        } finally {
            instrumentation.runOnMainSync {
                if (view.viewTreeObserver.isAlive) {
                    view.viewTreeObserver.unregisterFrameCommitCallback(callback)
                }
            }
        }
    }

    private fun interiorDifference(before: ImageBitmap, after: ImageBitmap): Float {
        val a = before.toPixelMap()
        val b = after.toPixelMap()
        val scale = before.width / 300f
        var sum = 0f
        var count = 0
        // Sample well inside the lens: comparing outside pixels could incorrectly pass
        // a stale-cache implementation just because the uncovered source changed.
        for (y in 120..212 step 8) {
            for (x in 48..252 step 8) {
                val px = (x * scale).roundToInt()
                val py = (y * scale).roundToInt()
                val ca = a[px, py]
                val cb = b[px, py]
                sum += abs(ca.red - cb.red) + abs(ca.green - cb.green) + abs(ca.blue - cb.blue)
                count += 3
            }
        }
        return sum / count
    }
}
