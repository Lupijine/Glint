package app.glint.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandsTest {
    @Test
    fun `arguments split on spaces and keep quoted text together`() {
        assertEquals(
            listOf("-f", "bv*[height<=720]+ba", "-o", "%(title)s [%(id)s].%(ext)s", "--embed-subs"),
            CommandLine.split("""-f "bv*[height<=720]+ba"   -o '%(title)s [%(id)s].%(ext)s'  --embed-subs"""),
        )
    }

    @Test
    fun `curly quotes from phone keyboards work like straight ones`() {
        assertEquals(listOf("-o", "my file.%(ext)s"), CommandLine.split("-o “my file.%(ext)s”"))
    }

    @Test
    fun `backslashes escape outside quotes and only special characters inside double quotes`() {
        assertEquals(listOf("a b", "c"), CommandLine.split("""a\ b c"""))
        assertEquals(listOf("""say "hi""""), CommandLine.split(""""say \"hi\"""""))
        assertEquals(listOf("""C:\path"""), CommandLine.split(""""C:\path""""))
        assertEquals(listOf("""keep\"""), CommandLine.split("""'keep\'"""))
    }

    @Test
    fun `empty quotes make an empty argument and lines are just spaces`() {
        assertEquals(listOf("--proxy", ""), CommandLine.split("--proxy \"\""))
        assertEquals(listOf("-x", "--audio-format", "mp3"), CommandLine.split("-x\n--audio-format\tmp3\n"))
        assertEquals(emptyList<String>(), CommandLine.split("   "))
    }

    @Test
    fun `audio commands are recognized`() {
        assertTrue(CommandLine.extractsAudio("-x --audio-format mp3"))
        assertTrue(CommandLine.extractsAudio("--extract-audio"))
        assertFalse(CommandLine.extractsAudio("-o \"-x\".%(ext)s"))
    }

    @Test
    fun `starter templates parse cleanly`() {
        CommandTemplates.Starters.forEach { template ->
            val args = CommandLine.split(template.args)
            assertTrue(template.name, args.isNotEmpty())
            assertTrue(template.name, args.none { it.contains('"') })
        }
    }

    @Test
    fun `templates survive saving and loading`() {
        val templates = CommandTemplates.Starters + CommandTemplate("x", "Mine", "-f \"b\" --no-mtime")

        assertEquals(templates, CommandTemplates.decode(CommandTemplates.encode(templates)))
        assertEquals(emptyList<CommandTemplate>(), CommandTemplates.decode("not json"))
        assertEquals(1, CommandTemplates.decode("""[{"id":"a","name":"A","args":"-x"},{"name":"no id"},7]""").size)
    }
}

class NetscapeCookiesTest {
    private val file =
        """
        # Netscape HTTP Cookie File
        # This is a comment

        .youtube.com	TRUE	/	TRUE	1999999999	SID	abc
        #HttpOnly_.youtube.com	TRUE	/	TRUE	1999999999	HSID	def
        www.instagram.com	FALSE	/	TRUE	1999999999	sessionid	ghi
        broken line without tabs
        """
            .trimIndent()

    @Test
    fun `cookie lines are found and junk is skipped`() {
        assertEquals(3, NetscapeCookies.lines(file).size)
        assertTrue(NetscapeCookies.looksValid(file))
        assertFalse(NetscapeCookies.looksValid("<html>not cookies</html>"))
        assertEquals(listOf("instagram.com", "youtube.com"), NetscapeCookies.sites(file))
    }

    @Test
    fun `browser cookies become lines for the site and its subdomains`() {
        val lines = NetscapeCookies.fromBrowser("www.tiktok.com", "a=1; b=x=y ; ;c", nowSeconds = 1000)

        assertEquals(2, lines.size)
        assertEquals(listOf(".tiktok.com", "TRUE", "/", "TRUE", (1000 + 31536000).toString(), "a", "1"), lines[0].split('\t'))
        assertEquals("x=y", lines[1].split('\t')[6])
    }

    @Test
    fun `cookies from a mobile site are saved for the whole site, where yt-dlp looks`() {
        val line = NetscapeCookies.fromBrowser("m.youtube.com", "SID=1", nowSeconds = 0).single()
        assertEquals(".youtube.com", line.substringBefore('\t'))
        assertEquals("youtube.com", NetscapeCookies.siteOf("M.YouTube.com"))
        assertEquals("facebook.com", NetscapeCookies.siteOf("m.facebook.com"))
        assertEquals("facebook.com", NetscapeCookies.siteOf("mbasic.facebook.com"))
        assertEquals("instagram.com", NetscapeCookies.siteOf("www.instagram.com"))
        // Real subdomains and two-part endings stay as they are.
        assertEquals("bbc.co.uk", NetscapeCookies.siteOf("www.bbc.co.uk"))
        assertEquals("music.youtube.com", NetscapeCookies.siteOf("music.youtube.com"))
        assertEquals("m.com", NetscapeCookies.siteOf("m.com"))
    }

    @Test
    fun `signing in again replaces cookies saved for the mobile subdomain`() {
        val old = NetscapeCookies.merge("", listOf(".m.youtube.com\tTRUE\t/\tTRUE\t0\tOLD\t1"))
        val merged = NetscapeCookies.merge(old, NetscapeCookies.fromBrowser("m.youtube.com", "NEW=1", nowSeconds = 0))
        val lines = NetscapeCookies.lines(merged)
        assertEquals(1, lines.size)
        assertTrue(lines.single().startsWith(".youtube.com\t") && lines.single().contains("NEW"))
    }

    @Test
    fun `saving a site replaces only that site's cookies`() {
        val fresh = NetscapeCookies.fromBrowser("youtube.com", "NEW=1", nowSeconds = 0)

        val merged = NetscapeCookies.merge(file, fresh)

        assertTrue(merged.startsWith(NetscapeCookies.Header))
        val lines = NetscapeCookies.lines(merged)
        assertEquals(2, lines.size)
        assertTrue(lines.any { it.contains("sessionid") })
        assertTrue(lines.any { it.contains("NEW") })
        assertFalse(lines.any { it.contains("SID\tabc") || it.contains("HSID") })
    }
}
