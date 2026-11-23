package cz.starenka.jiffy

import org.junit.Assert.*
import org.junit.Test

class CustomSharesTest {
    private val taken = listOf("Photos", "Music", "Documents", "Downloads")

    @Test fun builtInSharesListPhotosBeforeMusic() {
        assertEquals(listOf("Photos", "Music", "Documents", "Downloads"), Config.SHARES.map { it.name })
        assertTrue(Config.SHARES.none { it.custom })
    }

    @Test fun acceptsOrdinaryAndUnicodeNames() {
        for (n in listOf("Podcasts", "My Books", "Hudba_2024", "Žluťoučký kůň", "a.b", "x")) assertNull(n, Config.checkShareName(n, taken))
    }

    @Test fun rejectsNamesThatCouldEscapeOrConfuseThePath() {
        for (n in listOf("", " ", " x", "x ", ".", "..", ".hidden", "a/b", "/a", "a\\b", "a\nb", "a\u0000b", "x".repeat(65)))
            assertNotNull("'$n' should be rejected", Config.checkShareName(n, taken))
    }

    @Test fun rejectsDuplicatesCaseInsensitively() {
        assertNotNull(Config.checkShareName("music", taken))
        assertNotNull(Config.checkShareName("PHOTOS", taken))
        assertNull(Config.checkShareName("Podcasts", taken + "Books"))
        assertNotNull(Config.checkShareName("books", taken + "Books"))
    }

    @Test fun customSharesRoundTrip() {
        val list = listOf("Podcasts" to "/storage/emulated/0/Podcasts", "Žluťoučký" to "/sdcard/x y")
        assertEquals(list, Config.decodeCustom(Config.encodeCustom(list), taken))
    }

    @Test fun decodeDropsInvalidDuplicateAndReservedEntries() {
        val json = """[
            {"name":"Good","path":"/a"},
            {"name":"good","path":"/b"},
            {"name":"Music","path":"/c"},
            {"name":"..","path":"/d"},
            {"name":"a/b","path":"/e"},
            {"name":"NoPath","path":""},
            {"name":"Also good","path":"/f"},
            "junk", 7
        ]"""
        assertEquals(listOf("Good" to "/a", "Also good" to "/f"), Config.decodeCustom(json, taken))
    }

    @Test fun decodeToleratesGarbage() {
        for (j in listOf("", "not json", "{}", "null", "[[]]")) assertEquals(j, emptyList<Pair<String, String>>(), Config.decodeCustom(j, taken))
    }
}
