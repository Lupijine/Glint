package app.glint.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemesTest {
    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    @Test
    fun `theme ids are unique and the original theme keeps its id`() {
        val ids = AppThemes.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals("chrome", AppThemes.Default.id)
        assertTrue(AppThemes.Default in AppThemes.all)
    }

    @Test
    fun `unknown or missing ids show the default theme`() {
        assertSame(AppThemes.Default, AppThemes.find(null))
        assertSame(AppThemes.Default, AppThemes.find("removed-theme"))
        AppThemes.all.forEach { assertSame(it, AppThemes.find(it.id)) }
    }

    @Test
    fun `text on accent buttons is readable in every theme`() {
        AppThemes.all.forEach { theme ->
            val ratio = contrast(theme.accent, theme.colors.onPrimary)
            assertTrue("${theme.id}: accent contrast $ratio", ratio >= 4.5f)
        }
    }

    @Test
    fun `every theme is dark, so white text stays readable on its glass`() {
        AppThemes.all.forEach { theme ->
            assertTrue("${theme.id}: background", contrast(Color.White, theme.background) >= 12f)
            assertTrue("${theme.id}: glass", contrast(Color.White, theme.glass) >= 12f)
            assertTrue("${theme.id}: scrim ${theme.scrim}", theme.scrim in 0f..1f)
        }
    }

    @Test
    fun `the seeded sequence repeats, so artworks look the same every time`() {
        val a = Seeded(7)
        val b = Seeded(7)
        val first = List(50) { a.next() }
        assertEquals(first, List(50) { b.next() })
        assertTrue(first.all { it >= 0f && it < 1f })
        assertTrue(first.toSet().size > 45)
    }
}
