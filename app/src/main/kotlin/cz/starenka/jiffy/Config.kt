package cz.starenka.jiffy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.Paths

object Config {
    const val PORT = 2222

    class Share(val name: String, val defaultPath: String, val defaultEnabled: Boolean)

    /** Add more shares here; virtual name -> default real directory. */
    val SHARES = listOf(
        Share("Music", "/storage/emulated/0/Music", true),
        Share("Photos", "/storage/emulated/0/DCIM", true),
        Share("Documents", "/storage/emulated/0/Documents", true),
        Share("Downloads", "/storage/emulated/0/Download", true),
    )

    private fun prefs(c: Context) = c.getSharedPreferences("config", Context.MODE_PRIVATE)

    fun isEnabled(c: Context, s: Share) = prefs(c).getBoolean("share.${s.name}.enabled", s.defaultEnabled)
    fun setEnabled(c: Context, s: Share, v: Boolean) = prefs(c).edit().putBoolean("share.${s.name}.enabled", v).apply()
    fun path(c: Context, s: Share): String = prefs(c).getString("share.${s.name}.path", null)?.takeIf { it.isNotBlank() } ?: s.defaultPath
    fun confirmPath(c: Context, s: Share, draft: String) = confirmDirectory(draft) {
        prefs(c).edit().putString("share.${s.name}.path", it).apply()
    }

    /** Return the resolved directory for display; publish it only when the owner submits that exact path. */
    internal fun confirmDirectory(draft: String, publish: (String) -> Unit): String {
        val path = Paths.get(draft)
        if (!path.isAbsolute) throw InvalidPathException(draft, "Enter an absolute folder path")
        val real = path.toRealPath()
        if (!Files.isDirectory(real)) throw NotDirectoryException(draft)
        val resolved = real.toString()
        if (draft == resolved) publish(resolved)
        return resolved
    }

    /** An authorized-key source: either a GitHub handle (many keys) or one pasted key. */
    class KeyEntry(val github: String?, val label: String, val keys: List<String>)

    fun keyEntries(c: Context): List<KeyEntry> {
        val arr = try { JSONArray(prefs(c).getString("key_entries", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            KeyEntry(
                o.optString("github").takeIf { g -> g.isNotEmpty() },
                o.getString("label"),
                o.getJSONArray("keys").let { k -> (0 until k.length()).map { i -> k.getString(i) } },
            )
        }
    }

    private fun saveEntries(c: Context, list: List<KeyEntry>) {
        val arr = JSONArray()
        for (e in list) arr.put(JSONObject().put("github", e.github ?: "").put("label", e.label).put("keys", JSONArray(e.keys)))
        prefs(c).edit().putString("key_entries", arr.toString()).apply()
    }

    /** Adds or (for the same GitHub handle / same key) replaces an entry. */
    fun addEntry(c: Context, e: KeyEntry) = saveEntries(c, replaceEntry(keyEntries(c), e))

    internal fun replaceEntry(entries: List<KeyEntry>, e: KeyEntry): List<KeyEntry> =
        entries.filterNot {
            if (e.github != null) it.github.equals(e.github, true) else it.github == null && it.keys == e.keys
        } + e

    fun removeEntry(c: Context, e: KeyEntry) =
        saveEntries(c, keyEntries(c).filterNot { it.github == e.github && it.label == e.label && it.keys == e.keys })

    fun authorizedKeyLines(c: Context): List<String> = keyEntries(c).flatMap { it.keys }

    /** Enabled shares as virtual name -> real path, snapshotted per SSH session. */
    fun enabledShares(c: Context): Map<String, Path> =
        SHARES.filter { isEnabled(c, it) }.associate { it.name to Paths.get(path(c, it)) }
}
