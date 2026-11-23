package cz.starenka.jiffy

import java.net.HttpURLConnection
import java.net.URL

object GitHubKeys {
    private val HANDLE = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$")
    private val KEY_PREFIXES = listOf("ssh-", "ecdsa-", "sk-")

    fun looksLikeKey(s: String) = KEY_PREFIXES.any { s.trimStart().startsWith(it) }
    fun normalizeHandle(s: String) = s.trim().removePrefix("@")
    fun isValidHandle(s: String) = HANDLE.matches(s)

    /** Blocking; call off the main thread. Throws with a user-readable message. */
    fun fetch(handle: String): List<String> {
        val c = URL("https://github.com/$handle.keys").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 10_000; c.instanceFollowRedirects = false
        try {
            when (val code = c.responseCode) {
                200 -> {}
                404 -> throw IllegalStateException("GitHub user '$handle' not found")
                else -> throw IllegalStateException("GitHub returned HTTP $code")
            }
            return c.inputStream.bufferedReader().useLines(::parseKeyList)
        } finally { c.disconnect() }
    }

    internal fun parseKeyList(lines: Sequence<String>): List<String> =
        lines.map { it.trim() }.filter { it.isNotEmpty() }.onEach {
            check(looksLikeKey(it)) { "Unexpected response in GitHub key list" }
        }.toList()

    /** "ssh-ed25519 AAAA… comment" -> "ssh-ed25519 …WyvZ comment" style short label. */
    fun label(key: String): String {
        val p = key.trim().split(Regex("\\s+"))
        val tail = p.getOrNull(1)?.takeLast(8) ?: ""
        return (p[0] + " …" + tail + (p.getOrNull(2)?.let { " $it" } ?: ""))
    }
}
