package cz.starenka.jiffy

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.NotDirectoryException

class MainActivity : Activity() {
    private lateinit var ip: TextView
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var storage: Button
    private lateinit var fp: TextView
    private lateinit var keyInput: EditText
    private lateinit var addBtn: Button
    private lateinit var keyList: LinearLayout
    private lateinit var shareList: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(24)) }
        val scroll = ScrollView(this).apply { addView(col, MATCH_PARENT, WRAP_CONTENT) }
        // Pad the root (not the ScrollView) so the ScrollView itself shrinks when the keyboard opens
        // and scrolls the focused field into view. Needed because Android 15+ is edge-to-edge.
        val root = FrameLayout(this).apply {
            addView(scroll, MATCH_PARENT, MATCH_PARENT)
            setOnApplyWindowInsetsListener { v, w ->
                val i = w.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime() or android.view.WindowInsets.Type.displayCutout())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                android.view.WindowInsets.CONSUMED
            }
        }
        setContentView(root)

        ip = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER }
        col.addView(ip, MATCH_PARENT, WRAP_CONTENT)

        toggle = Button(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f); setOnClickListener { onToggle() } }
        col.addView(toggle, MATCH_PARENT, dp(110)).also { (toggle.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(16), 0, dp(8)) }

        status = TextView(this).apply { gravity = Gravity.CENTER }
        col.addView(status, MATCH_PARENT, WRAP_CONTENT)

        storage = Button(this).apply {
            text = "Grant All files access (required)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
        }
        col.addView(storage, MATCH_PARENT, WRAP_CONTENT)

        col.addView(header("Shared directories"))
        shareList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(shareList, MATCH_PARENT, WRAP_CONTENT)
        renderShares()
        col.addView(Button(this).apply { text = "Add directory"; setOnClickListener { addShareDialog() } }, MATCH_PARENT, WRAP_CONTENT)
        col.addView(note("Tap a directory path to change it. Added directories can be removed (files are never deleted). Changes apply to new connections."))

        col.addView(header("Authorized keys"))
        keyInput = EditText(this).apply {
            hint = "GitHub handle, or paste public key(s)"
            typeface = Typeface.MONOSPACE; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            minLines = 1; maxLines = 6; isSingleLine = false
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        col.addView(keyInput, MATCH_PARENT, WRAP_CONTENT)
        addBtn = Button(this).apply { text = "Add"; setOnClickListener { addKeys() } }
        col.addView(addBtn, MATCH_PARENT, WRAP_CONTENT)
        keyList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(keyList, MATCH_PARENT, WRAP_CONTENT)
        renderKeys()

        fp = note("")
        col.addView(header("Host key"))
        col.addView(fp)
        col.addView(note("build ${BuildConfig.GIT_REV} · ${BuildConfig.BUILD_TIME}").apply { setPadding(0, dp(12), 0, 0); alpha = 0.6f })

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun header(t: String) = TextView(this).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(20), 0, dp(4))
    }
    private fun note(t: String) = TextView(this).apply { text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); typeface = Typeface.MONOSPACE }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun renderShares() {
        shareList.removeAllViews()
        for (s in Config.shares(this)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(CheckBox(this).apply {
                text = "/${s.name}"; isChecked = Config.isEnabled(this@MainActivity, s)
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                setOnCheckedChangeListener { _, v -> Config.setEnabled(this@MainActivity, s, v) }
            }, dp(120), WRAP_CONTENT)
            row.addView(Button(this).apply {
                text = Config.path(this@MainActivity, s); isAllCaps = false
                typeface = Typeface.MONOSPACE; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setOnClickListener { editShare(s, this) }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (s.custom) row.addView(Button(this).apply {
                text = "×"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); minWidth = 0; minimumWidth = dp(48)
                setOnClickListener { confirmRemove(s) }
            })
            shareList.addView(row, MATCH_PARENT, WRAP_CONTENT)
        }
    }

    private fun confirmRemove(share: Config.Share) {
        AlertDialog.Builder(this).setTitle("Stop sharing /${share.name}?")
            .setMessage("Only the share is removed. No files are deleted. Existing connections keep working until they end.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ -> Config.removeShare(this, share); renderShares() }.show()
    }

    private fun addShareDialog() {
        fun field(hint: String, text: String = "") = EditText(this).apply {
            setSingleLine(); this.hint = hint; setText(text)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val name = field("Name, e.g. Podcasts")
        val path = field("Absolute directory path", "/storage/emulated/0/")
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(name); addView(path) }
        val dialog = AlertDialog.Builder(this).setTitle("Add a shared directory").setView(form)
            .setNegativeButton("Cancel", null).setPositiveButton("Add", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val resolved = try {
                Config.addShare(this, name.text.toString().trim(), path.text.toString())
            } catch (e: IllegalArgumentException) {
                name.error = e.message; return@setOnClickListener
            } catch (e: InvalidPathException) {
                path.error = e.reason; return@setOnClickListener
            } catch (e: NotDirectoryException) {
                path.error = "Not a directory"; return@setOnClickListener
            } catch (e: IOException) {
                path.error = "Cannot open directory: ${e.message}"; return@setOnClickListener
            } catch (e: SecurityException) {
                path.error = "Permission denied"; return@setOnClickListener
            }
            if (resolved != path.text.toString()) {
                path.setText(resolved)
                path.error = "Resolved directory shown. Tap Add to confirm."
                return@setOnClickListener
            }
            renderShares()
            dialog.dismiss()
        }
    }

    private fun editShare(share: Config.Share, button: Button) {
        val draft = EditText(this).apply {
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(Config.path(this@MainActivity, share))
        }
        val dialog = AlertDialog.Builder(this).setTitle("Directory for /${share.name}").setView(draft)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val path = try {
                Config.confirmPath(this, share, draft.text.toString())
            } catch (e: InvalidPathException) {
                draft.error = e.reason; return@setOnClickListener
            } catch (e: NotDirectoryException) {
                draft.error = "Not a directory"; return@setOnClickListener
            } catch (e: IOException) {
                draft.error = "Cannot open directory: ${e.message}"; return@setOnClickListener
            } catch (e: SecurityException) {
                draft.error = "Permission denied"; return@setOnClickListener
            }
            if (path != draft.text.toString()) {
                draft.setText(path)
                draft.error = "Resolved directory shown. Tap Save to confirm."
                return@setOnClickListener
            }
            button.text = path
            dialog.dismiss()
        }
    }

    private fun renderKeys() {
        keyList.removeAllViews()
        val entries = Config.keyEntries(this)
        if (entries.isEmpty()) keyList.addView(note("No keys yet: nobody can log in. Add a GitHub handle or a public key."))
        for (e in entries) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            if (e.github != null) row.addView(ImageView(this).apply { setImageResource(R.drawable.ic_github) }, dp(24), dp(24))
            row.addView(TextView(this).apply {
                text = if (e.github != null) "${e.github}  (${e.keys.size} key${if (e.keys.size == 1) "" else "s"})" else e.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); typeface = Typeface.MONOSPACE; setPadding(dp(8), 0, dp(8), 0)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "Remove"; setOnClickListener { Config.removeEntry(this@MainActivity, e); renderKeys() }
            })
            keyList.addView(row, MATCH_PARENT, WRAP_CONTENT)
        }
    }

    private fun addKeys() {
        val text = keyInput.text.toString().trim()
        if (text.isEmpty()) return
        if (GitHubKeys.looksLikeKey(text)) {
            val lines = text.lines().map { it.trim() }.filter { GitHubKeys.looksLikeKey(it) }
            for (l in lines) Config.addEntry(this, Config.KeyEntry(null, GitHubKeys.label(l), listOf(l)))
            keyInput.setText(""); renderKeys(); return
        }
        val handle = GitHubKeys.normalizeHandle(text)
        if (!GitHubKeys.isValidHandle(handle)) { toast("Not a public key or a valid GitHub handle"); return }
        addBtn.isEnabled = false; addBtn.text = "Fetching…"
        Thread {
            val result = runCatching { GitHubKeys.fetch(handle) }
            runOnUiThread {
                addBtn.isEnabled = true; addBtn.text = "Add"
                result.onSuccess {
                    Config.addEntry(this, Config.KeyEntry(handle, handle, it))
                    keyInput.setText(""); renderKeys()
                    if (it.isEmpty()) toast("No public keys on GitHub. No keys are authorized from '$handle'.")
                }.onFailure { toast("Import failed: ${it.message ?: it}. Previously saved keys are unchanged.") }
            }
        }.start()
    }

    private fun onToggle() {
        if (ServerState.running) {
            stopService(Intent(this, SftpService::class.java))
        } else {
            if (!Environment.isExternalStorageManager()) { storage.performClick(); return }
            val endpoint = WifiLan.endpoint ?: run { toast("No Wi-Fi connection"); return }
            ServerState.error = null
            SftpService.start(this, endpoint)
        }
    }

    private fun refresh() {
        val bound = ServerState.boundHost
        val host = bound ?: WifiLan.endpoint?.host
        ip.text = host?.let { "$it:${Config.PORT}" } ?: "No Wi-Fi connection"
        toggle.isEnabled = host != null
        toggle.text = if (bound != null) "Stop sharing" + (rate?.let { "\n$it" } ?: "") else "Start sharing"
        status.text = ServerState.error?.let { "Error: $it" }
            ?: if (bound != null) "Sharing. e.g. sshfs $bound:/Music /mnt/phone -p ${Config.PORT}" else "Not sharing"
        storage.visibility = if (Environment.isExternalStorageManager()) android.view.View.GONE else android.view.View.VISIBLE
        fp.text = ServerState.fingerprint ?: "(generated on first start)"
    }

    // Transfer rate: sampled once a second from this app's UID byte counters (only while the UI is visible).
    private var rate: String? = null
    private var lastTx = 0L; private var lastRx = 0L; private var lastAt = 0L
    private val ticker = object : Runnable {
        override fun run() {
            val uid = android.os.Process.myUid()
            val tx = android.net.TrafficStats.getUidTxBytes(uid); val rx = android.net.TrafficStats.getUidRxBytes(uid)
            val now = android.os.SystemClock.elapsedRealtime()
            if (lastAt != 0L && now > lastAt) {
                val dt = (now - lastAt) / 1000.0
                rate = "\u2191 ${fmt((tx - lastTx) / dt)}   \u2193 ${fmt((rx - lastRx) / dt)}"
            }
            lastTx = tx; lastRx = rx; lastAt = now
            refresh()
            toggle.postDelayed(this, 1000)
        }
    }
    private fun fmt(bps: Double) = when {
        bps >= 1024 * 1024 -> "%.1f MB/s".format(bps / 1024 / 1024)
        bps >= 1024 -> "%.0f KB/s".format(bps / 1024)
        else -> "0 KB/s"
    }

    override fun onResume() {
        super.onResume()
        ServerState.listener = { runOnUiThread { refresh() } }
        lastAt = 0L; rate = null
        refresh()
        toggle.postDelayed(ticker, 1000)
    }

    override fun onPause() { ServerState.listener = null; toggle.removeCallbacks(ticker); super.onPause() }
}
