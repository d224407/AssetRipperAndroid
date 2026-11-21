package com.min.assetripper

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.min.assetripper.ui.Ui
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_PICK = 1001
        private const val REQ_STORAGE = 1002
        private const val DL_JS = "if(!window.__ardl){window.__ardl=1;document.addEventListener('click'," +
            "function(e){var a=e.target.closest&&e.target.closest('a[download]');" +
            "if(a)ARDL.name(a.getAttribute('download')||'')},true)}"
    }

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var home: ScrollView
    private lateinit var webBox: LinearLayout
    private lateinit var web: WebView
    private lateinit var webProgress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var startBtn: TextView
    private lateinit var openBtn: TextView
    private lateinit var storageText: TextView
    private lateinit var grantBtn: TextView
    private lateinit var updateText: TextView
    private lateinit var updateBtn: TextView
    private lateinit var checkBtn: TextView

    private val io = Executors.newCachedThreadPool()
    private var release: Updater.Release? = null
    private var updating = false
    private var checked = false
    @Volatile private var downloadName = ""
    private var lastPhase = ServerState.phase
    private var pickCallback: ((Int, Intent?) -> Unit)? = null
    private val stateListener = Runnable { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ui = Ui(this)

        val root = FrameLayout(this)
        root.setBackgroundColor(ui.c(R.color.md_surface))
        home = buildHome()
        webBox = buildWeb()
        webBox.visibility = View.GONE
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(home, FrameLayout.LayoutParams(match, match))
        root.addView(webBox, FrameLayout.LayoutParams(match, match))
        setContentView(root)

        ServerState.addListener(stateListener)
        refresh()

        if (prefs.autoUpdate && System.currentTimeMillis() - prefs.lastCheck > 6L * 3600_000L) {
            checkUpdates(silent = true)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        ServerState.removeListener(stateListener)
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webBox.visibility == View.VISIBLE) {
            if (web.canGoBack()) web.goBack() else showHome()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK) {
            val cb = pickCallback
            pickCallback = null
            cb?.invoke(resultCode, data)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun buildHome(): ScrollView {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(ui.dp(16), ui.dp(20), ui.dp(16), ui.dp(32))

        col.addView(ui.label(getString(R.string.app_name), 30f, R.color.md_on_surface, bold = true))
        col.addView(
            ui.label(
                getString(R.string.version_line, BuildConfig.VERSION_NAME),
                14f, R.color.md_on_surface_variant
            ).apply { setPadding(0, ui.dp(4), 0, ui.dp(8)) }
        )

        val server = ui.card()
        server.addView(ui.listTitle(getString(R.string.card_server)))
        statusText = ui.label("", 18f, R.color.md_on_surface, bold = true)
        server.addView(statusText)
        startBtn = ui.button("", true) { onStartStop() }
        server.addView(startBtn, ui.lp(top = 14))
        openBtn = ui.button(getString(R.string.btn_open), false) { showWeb() }
        server.addView(openBtn, ui.lp(top = 8))
        col.addView(server, ui.lp(top = 12))

        val storage = ui.card()
        storage.addView(ui.listTitle(getString(R.string.card_storage)))
        storageText = ui.label("", 15f, R.color.md_on_surface_variant)
        storage.addView(storageText)
        grantBtn = ui.button(getString(R.string.btn_grant), false) { grantStorage() }
        storage.addView(grantBtn, ui.lp(top = 12))
        col.addView(storage, ui.lp(top = 12))

        val update = ui.card()
        update.addView(ui.listTitle(getString(R.string.card_update)))
        updateText = ui.label("", 15f, R.color.md_on_surface_variant)
        update.addView(updateText)
        checkBtn = ui.button(getString(R.string.btn_check), false) { checkUpdates(silent = false) }
        update.addView(checkBtn, ui.lp(top = 12))
        updateBtn = ui.button(getString(R.string.btn_update), true) { doUpdate() }
        updateBtn.visibility = View.GONE
        update.addView(updateBtn, ui.lp(top = 8))
        col.addView(update, ui.lp(top = 12))

        val tools = ui.card()
        tools.addView(ui.listTitle(getString(R.string.card_tools)))
        tools.addView(ui.button(getString(R.string.btn_logs), false) { showLogs() })
        tools.addView(ui.button(getString(R.string.btn_settings), false) { showSettings() }, ui.lp(top = 8))
        col.addView(tools, ui.lp(top = 12))

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.addView(col)
        return scroll
    }

    private fun refresh() {
        val s = ServerState.phase
        val idle = s == ServerState.Phase.STOPPED || s == ServerState.Phase.ERROR

        statusText.text = when (s) {
            ServerState.Phase.STOPPED -> getString(R.string.status_stopped)
            ServerState.Phase.INSTALLING -> getString(R.string.status_installing, ServerState.progress)
            ServerState.Phase.STARTING -> getString(R.string.status_starting)
            ServerState.Phase.RUNNING -> getString(R.string.status_running, ServerState.port)
            ServerState.Phase.ERROR -> getString(R.string.status_error, ServerState.error)
        }
        startBtn.text = getString(if (idle) R.string.btn_start else R.string.btn_stop)
        openBtn.visibility = if (s == ServerState.Phase.RUNNING) View.VISIBLE else View.GONE

        val granted = hasStorage()
        storageText.text = getString(if (granted) R.string.storage_ok else R.string.storage_missing)
        grantBtn.visibility = if (granted) View.GONE else View.VISIBLE

        refreshUpdate()

        if (lastPhase != ServerState.Phase.RUNNING && s == ServerState.Phase.RUNNING && prefs.autoOpen) showWeb()
        if (s != ServerState.Phase.RUNNING && webBox.visibility == View.VISIBLE) showHome()
        lastPhase = s
    }

    private fun onStartStop() {
        val s = ServerState.phase
        if (s == ServerState.Phase.STOPPED || s == ServerState.Phase.ERROR) ServerService.start(this)
        else ServerService.stop(this)
    }

    private fun showHome() {
        webBox.visibility = View.GONE
        home.visibility = View.VISIBLE
    }

    private fun showWeb() {
        home.visibility = View.GONE
        webBox.visibility = View.VISIBLE
        val base = "http://127.0.0.1:${ServerState.port}/"
        if (web.url?.startsWith(base) != true) web.loadUrl(base)
    }

    private fun buildWeb(): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(ui.c(R.color.md_surface_container))
        bar.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4))
        bar.addView(ui.iconButton(R.drawable.ic_back) { showHome() })
        val title = ui.label(getString(R.string.app_name), 18f, R.color.md_on_surface, bold = true)
        title.setPadding(ui.dp(8), 0, 0, 0)
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(ui.iconButton(R.drawable.ic_refresh) { web.reload() })

        webProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        webProgress.max = 100
        webProgress.visibility = View.GONE

        web = WebView(this)
        web.setBackgroundColor(ui.c(R.color.md_surface))
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun name(n: String) {
                downloadName = n
            }
        }, "ARDL")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(DL_JS, null)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                if (u.host == "127.0.0.1" && (u.path ?: "").startsWith("/Dialogs/")) {
                    val body = pick(u.lastPathSegment ?: "")
                    return WebResourceResponse(
                        "application/json", "utf-8", 200, "OK",
                        mapOf("Cache-Control" to "no-store"),
                        ByteArrayInputStream(body.toByteArray())
                    )
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host
                if (host == "127.0.0.1" || host == "localhost") return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (e: Exception) {
                }
                return true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    val msg = TextUtils.htmlEncode(getString(R.string.web_unreachable))
                    view.loadDataWithBaseURL(
                        null,
                        "<html><body style=\"font-family:sans-serif;padding:24px\">$msg</body></html>",
                        "text/html", "utf-8", null
                    )
                }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                webProgress.progress = newProgress
                webProgress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView, url: String, message: String, defaultValue: String?, result: JsPromptResult
            ): Boolean {
                val input = EditText(this@MainActivity)
                input.setText(defaultValue ?: "")
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm(input.text.toString()) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }
        }
        web.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            io.execute { saveDownload(url, contentDisposition, mimeType) }
        }

        box.addView(bar)
        box.addView(webProgress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(3)))
        box.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return box
    }

    /** Runs on a WebView thread: shows a picker, blocks, returns the server's JSON answer. */
    private fun pick(kind: String): String {
        if (!kind.startsWith("Open")) return "\"\""
        val multiple = kind.endsWith("s")
        val folder = kind.startsWith("OpenFolder")
        val latch = CountDownLatch(1)
        var result = if (multiple) "[]" else "\"\""

        runOnUiThread {
            val intent = if (folder) {
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            } else {
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            }
            pickCallback = { code, data ->
                if (code == RESULT_OK && data != null) {
                    val paths = mutableListOf<String>()
                    val clip = data.clipData
                    if (clip != null) {
                        for (i in 0 until clip.itemCount) toPath(clip.getItemAt(i).uri, folder)?.let { paths.add(it) }
                    } else {
                        data.data?.let { u -> toPath(u, folder)?.let { paths.add(it) } }
                    }
                    result = if (multiple) JSONArray(paths).toString() else JSONObject.quote(paths.firstOrNull() ?: "")
                }
                latch.countDown()
            }
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQ_PICK)
            } catch (e: Exception) {
                pickCallback = null
                latch.countDown()
            }
        }
        latch.await(10, TimeUnit.MINUTES)
        return result
    }

    private fun toPath(uri: Uri, tree: Boolean): String? {
        PathResolver.resolve(uri, tree)?.let { return it }
        if (!tree) PathResolver.copyToImports(this, uri)?.let { return it }
        runOnUiThread { Toast.makeText(this, R.string.picker_failed, Toast.LENGTH_LONG).show() }
        return null
    }

    private fun saveDownload(url: String, contentDisposition: String?, mimeType: String?) {
        try {
            val given = downloadName
            downloadName = ""
            val name = (if (given.isNotBlank()) given.replace('/', '_') else URLUtil.guessFileName(url, contentDisposition, mimeType))
            val base = if (hasStorage()) Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            else (getExternalFilesDir(null) ?: filesDir)
            val dir = File(base, "AssetRipper").apply { mkdirs() }
            val dot = name.lastIndexOf('.').let { if (it > 0) it else name.length }
            var out = File(dir, name)
            var n = 1
            while (out.exists()) out = File(dir, name.substring(0, dot) + " (" + n++ + ")" + name.substring(dot))
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.inputStream.use { input -> out.outputStream().use { input.copyTo(it, 1 shl 16) } }
            } finally {
                c.disconnect()
            }
            runOnUiThread { Toast.makeText(this, getString(R.string.download_saved, out.path), Toast.LENGTH_LONG).show() }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, getString(R.string.download_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun hasStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    private fun grantStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQ_STORAGE
            )
        }
    }

    private fun refreshUpdate() {
        if (updating) return
        val r = release
        updateText.text = if (r != null) getString(R.string.update_available, r.tag)
        else if (checked) getString(R.string.update_uptodate)
        else ""
        updateBtn.visibility = if (r != null) View.VISIBLE else View.GONE
    }

    private fun checkUpdates(silent: Boolean) {
        if (updating) return
        if (!silent) updateText.text = getString(R.string.update_checking)
        io.execute {
            try {
                val latest = Updater.fetchLatest()
                prefs.lastCheck = System.currentTimeMillis()
                runOnUiThread {
                    release = latest?.takeIf { Updater.isNewer(it) }
                    checked = true
                    refreshUpdate()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!silent) updateText.text = getString(R.string.update_failed, e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    private fun doUpdate() {
        val r = release ?: return
        if (updating) return
        if (!Updater.canInstall(this)) {
            Updater.openInstallSettings(this)
            return
        }
        updating = true
        updateBtn.isEnabled = false
        checkBtn.isEnabled = false
        io.execute {
            try {
                val apk = Updater.download(this, r) { p ->
                    runOnUiThread { updateText.text = getString(R.string.update_downloading, p) }
                }
                runOnUiThread { updateText.text = getString(R.string.update_verifying) }
                Updater.verify(this, r, apk)
                runOnUiThread { ServerService.stop(this) }
                Thread.sleep(800)
                Updater.install(this, apk)
                runOnUiThread { endUpdate() }
            } catch (e: Exception) {
                ServerState.log("Update failed: " + (e.message ?: e.javaClass.simpleName))
                runOnUiThread {
                    endUpdate()
                    updateText.text = getString(R.string.update_failed, e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    private fun endUpdate() {
        updating = false
        updateBtn.isEnabled = true
        checkBtn.isEnabled = true
    }

    private fun showLogs() {
        val tv = TextView(this)
        tv.text = ServerState.logText().ifEmpty { getString(R.string.log_empty) }
        tv.typeface = Typeface.MONOSPACE
        tv.textSize = 11f
        tv.setTextIsSelectable(true)
        tv.setTextColor(ui.c(R.color.md_on_surface))
        tv.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8))
        val scroll = ScrollView(this)
        scroll.addView(tv)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

        AlertDialog.Builder(this)
            .setTitle(R.string.btn_logs)
            .setView(scroll)
            .setPositiveButton(R.string.btn_close, null)
            .setNeutralButton(R.string.btn_copy) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("log", ServerState.logText()))
            }
            .setNegativeButton(R.string.btn_clear) { _, _ -> ServerState.clearLog() }
            .show()
    }

    private fun showSettings() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)

        val port = ui.numberField(prefs.port.toString())
        val heap = ui.numberField(prefs.heapMb.toString())
        val autoOpen = ui.switchRow(getString(R.string.set_auto_open), prefs.autoOpen)
        val wake = ui.switchRow(getString(R.string.set_wake), prefs.wakeLock)
        val autoUpdate = ui.switchRow(getString(R.string.set_auto_update), prefs.autoUpdate)

        col.addView(ui.label(getString(R.string.set_port), 14f, R.color.md_on_surface_variant))
        col.addView(port)
        col.addView(ui.label(getString(R.string.set_heap), 14f, R.color.md_on_surface_variant), ui.lp(top = 12))
        col.addView(heap)
        col.addView(autoOpen, ui.lp(top = 8))
        col.addView(wake)
        col.addView(autoUpdate)
        col.addView(ui.button(getString(R.string.set_language), false) { chooseLanguage() }, ui.lp(top = 12))

        val scroll = ScrollView(this)
        scroll.addView(col)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(scroll)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                prefs.port = (port.text.toString().toIntOrNull() ?: 0).coerceIn(0, 65535)
                prefs.heapMb = (heap.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
                prefs.autoOpen = autoOpen.isChecked
                prefs.wakeLock = wake.isChecked
                prefs.autoUpdate = autoUpdate.isChecked
            }
            .setNegativeButton(R.string.btn_close, null)
            .show()
    }

    private fun chooseLanguage() {
        val tags = arrayOf("", "en", "vi")
        val names = arrayOf(getString(R.string.lang_system), getString(R.string.lang_en), getString(R.string.lang_vi))
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        var checked = tags.indexOfFirst { it.isNotEmpty() && current.startsWith(it) }
        if (checked < 0) checked = 0
        AlertDialog.Builder(this)
            .setTitle(R.string.set_language)
            .setSingleChoiceItems(names, checked) { dialog, which ->
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags[which]))
                dialog.dismiss()
            }
            .show()
    }
}
