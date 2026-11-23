package cz.starenka.jiffy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubKeysTest {
    @Test fun emptyRefreshRevokesOnlyTheMatchingHandle() {
        val old = Config.KeyEntry("Alice", "Alice", listOf("old key"))
        val pasted = Config.KeyEntry(null, "local", listOf("local key"))
        val refreshed = Config.KeyEntry("alice", "alice", GitHubKeys.parseKeyList(sequenceOf("", " ")))
        val saved = Config.replaceEntry(listOf(old, pasted), refreshed)
        assertEquals(listOf("local key"), saved.flatMap { it.keys })
        assertEquals(listOf(pasted, refreshed), saved)
    }

    @Test fun unexpectedResponseIsNotAnEmptyKeyList() {
        assertThrows(IllegalStateException::class.java) { GitHubKeys.parseKeyList(sequenceOf("Service unavailable")) }
    }
}
