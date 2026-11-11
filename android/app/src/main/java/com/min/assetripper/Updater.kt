package com.min.assetripper

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Self-update from GitHub releases.
 *
 * Every release is produced by one CI run that builds the server and the APK together and tags them
 * "v<AssetRipper version>-android.<run number>". The APK bundles that exact server, so installing the
 * APK updates app and binary as one unit. Before installing, the downloaded file is checked against
 * the published SHA-256, against this app's package name, against the release's build number, and
 * against the tag stored in its own assets/ar.info.
 */
object Updater {

    data class Release(
        val tag: String,
        val build: Int,
        val apkName: String,
        val apkUrl: String,
        val apkSize: Long,
        val sha256Url: String?,
        val sha256Inline: String?
    )

    const val INSTALL_ACTION = "com.min.assetripper.INSTALL_RESULT"

    private fun open(url: String, accept: String = "*/*"): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "AssetRipperAndroid/" + BuildConfig.VERSION_NAME)
        c.setRequestProperty("Accept", accept)
        return c
    }

    private fun getText(url: String, accept: String = "*/*"): String {
        val c = open(url, accept)
        try {
            if (c.responseCode != 200) throw IOException("HTTP " + c.responseCode + " for " + url)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Returns the latest published release, or null if it has no matching APK. */
    fun fetchLatest(): Release? {
        val json = JSONObject(
            getText("https://api.github.com/repos/" + BuildConfig.GITHUB_REPO + "/releases/latest", "application/vnd.github+json")
        )
        val tag = json.getString("tag_name")
        val build = Regex("""android\.(\d+)$""").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: return null

        val assets = json.getJSONArray("assets")
        var apk: JSONObject? = null
        var sha: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.getString("name")
            if (!name.contains(tag)) continue
            if (name.endsWith(".apk")) apk = a
            if (name.endsWith(".apk.sha256")) sha = a
        }
        if (apk == null) return null

        val digest = apk.optString("digest", "")
        return Release(
            tag = tag,
            build = build,
            apkName = apk.getString("name"),
            apkUrl = apk.getString("browser_download_url"),
            apkSize = apk.optLong("size", 0L),
            sha256Url = sha?.getString("browser_download_url"),
            sha256Inline = if (digest.startsWith("sha256:")) digest.removePrefix("sha256:") else null
        )
    }

    fun isNewer(r: Release): Boolean = r.build > BuildConfig.VERSION_CODE

    fun download(ctx: Context, r: Release, progress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates")
        dir.deleteRecursively()
        dir.mkdirs()
        val out = File(dir, r.apkName)
        val c = open(r.apkUrl)
        try {
            if (c.responseCode != 200) throw IOException("HTTP " + c.responseCode)
            val total = if (c.contentLengthLong > 0) c.contentLengthLong else r.apkSize
            var done = 0L
            c.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) progress((done * 100 / total).toInt().coerceIn(0, 100))
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        return out
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Throws IllegalStateException if the downloaded APK is not the build the release describes. */
    fun verify(ctx: Context, r: Release, apk: File) {
        val expected = (r.sha256Url?.let { getText(it).trim().split(Regex("\\s+")).firstOrNull() } ?: r.sha256Inline)
            ?.lowercase()
            ?: throw IllegalStateException("The release has no SHA-256 checksum")
        if (sha256(apk) != expected) throw IllegalStateException("Checksum mismatch")

        val info = ctx.packageManager.getPackageArchiveInfo(apk.path, 0)
            ?: throw IllegalStateException("The downloaded file is not a valid APK")
        if (info.packageName != ctx.packageName) throw IllegalStateException("Package name mismatch: " + info.packageName)
        if (PackageInfoCompat.getLongVersionCode(info) != r.build.toLong()) {
            throw IllegalStateException("Version code does not match the release")
        }

        // The APK must carry the server built for the same tag as the release.
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry("assets/ar.info") ?: throw IllegalStateException("The APK has no bundled server")
            val tag = zip.getInputStream(entry).bufferedReader().use { rd ->
                rd.readLines().map { it.trim() }.firstOrNull { it.startsWith("tag=") }?.removePrefix("tag=")
            }
            if (tag != r.tag) throw IllegalStateException("Bundled server is for " + tag + ", expected " + r.tag)
        }
    }

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallSettings(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        installer.openSession(id).use { session ->
            session.openWrite("update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 1 shl 16) }
                session.fsync(out)
            }
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, InstallReceiver::class.java).setAction(INSTALL_ACTION), flags
            )
            session.commit(pending.intentSender)
        }
    }
}
