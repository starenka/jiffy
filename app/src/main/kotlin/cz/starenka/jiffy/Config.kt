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

    /** [custom] shares are added by the owner in the app and can be removed; built-in ones cannot. */
    class Share(val name: String, val defaultPath: String, val defaultEnabled: Boolean, val custom: Boolean = false)

    /** Built-in shares, in display order; virtual name -> default real directory. */
    val SHARES = listOf(
        Share("Photos", "/storage/emulated/0/DCIM", true),
        Share("Music", "/storage/emulated/0/Music", true),
        Share("Documents", "/storage/emulated/0/Documents", true),
        Share("Downloads", "/storage/emulated/0/Download", true),
    )

    /** Why [name] can't be used as a share name (it becomes a top-level path segment), or null if it can. */
    internal fun checkShareName(name: String, taken: Collection<String>): String? = when {
        name.isEmpty() -> "Enter a name"
        name != name.trim() -> "No leading or trailing spaces"
        name.length > 64 -> "Name is too long (max 64)"
        name.startsWith(".") -> "Name can't start with a dot"
        name.any { it == '/' || it == '\\' || it.isISOControl() } -> "Name can't contain / \\ or control characters"
        taken.any { it.equals(name, ignoreCase = true) } -> "A share with this name already exists"
        else -> null
    }

    internal fun encodeCustom(shares: List<Pair<String, String>>): String {
        val arr = JSONArray()
        for ((name, path) in shares) arr.put(JSONObject().put("name", name).put("path", path))
        return arr.toString()
    }

    /** Parses stored custom shares, dropping anything malformed, invalid, or clashing with [reserved] names or each other. */
    internal fun decodeCustom(json: String, reserved: Collection<String>): List<Pair<String, String>> {
        val arr = try { JSONArray(json) } catch (e: Exception) { return emptyList() }
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name"); val path = o.optString("path")
            if (path.isBlank() || checkShareName(name, reserved + out.map { it.first }) != null) continue
            out += name to path
        }
        return out
    }

    private fun customRaw(c: Context) = decodeCustom(prefs(c).getString("custom_shares", "[]") ?: "[]", SHARES.map { it.name })
    private fun saveCustom(c: Context, list: List<Pair<String, String>>) =
        prefs(c).edit().putString("custom_shares", encodeCustom(list)).apply()

    /** Built-in shares followed by the owner's custom shares, in the order added. */
    fun shares(c: Context): List<Share> = SHARES + customRaw(c).map { (name, path) -> Share(name, path, true, custom = true) }

    /** Validates the name, then (like [confirmPath]) saves only a confirmed resolved directory. Returns the resolved directory. */
    fun addShare(c: Context, name: String, draft: String): String {
        checkShareName(name, shares(c).map { it.name })?.let { throw IllegalArgumentException(it) }
        return confirmDirectory(draft) { saveCustom(c, customRaw(c) + (name to it)) }
    }

    /** Removes a custom share's configuration. The directory and its files are untouched. */
    fun removeShare(c: Context, s: Share) {
        if (!s.custom) return
        saveCustom(c, customRaw(c).filterNot { it.first == s.name })
        prefs(c).edit().remove("share.${s.name}.enabled").remove("share.${s.name}.path").apply()
    }

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
        if (!path.isAbsolute) throw InvalidPathException(draft, "Enter an absolute directory path")
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
        shares(c).filter { isEnabled(c, it) }.associate { it.name to Paths.get(path(c, it)) }
}
