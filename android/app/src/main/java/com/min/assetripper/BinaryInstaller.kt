package com.min.assetripper

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Unpacks the bundled server (assets/ar.zip) into filesDir/ar/<BUILD_TAG>/.
 * assets/ar.info carries the tag the server was built for; it must equal this app's BUILD_TAG, which
 * guarantees the app and the server binary always come from the same build.
 */
object BinaryInstaller {
    const val EXE = "AssetRipper.GUI.Free"
    private const val OK_MARKER = ".ok"

    private class Counting(inner: InputStream) : FilterInputStream(inner) {
        var count = 0L

        override fun read(): Int {
            val r = super.read()
            if (r >= 0) count++
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = super.read(b, off, len)
            if (r > 0) count += r
            return r
        }
    }

    fun root(ctx: Context) = File(ctx.filesDir, "ar")

    fun dir(ctx: Context) = File(root(ctx), BuildConfig.BUILD_TAG)

    fun exe(ctx: Context) = File(dir(ctx), EXE)

    fun bundledTag(ctx: Context): String? = try {
        ctx.assets.open("ar.info").bufferedReader().use { r ->
            r.readLines().map { it.trim() }.firstOrNull { it.startsWith("tag=") }?.removePrefix("tag=")
        }
    } catch (e: IOException) {
        null
    }

    private fun isInstalled(ctx: Context) = File(dir(ctx), OK_MARKER).exists() && exe(ctx).exists()

    fun install(ctx: Context, progress: (Int) -> Unit) {
        if (bundledTag(ctx) != BuildConfig.BUILD_TAG) {
            throw IllegalStateException(ctx.getString(R.string.err_binary_mismatch))
        }
        if (isInstalled(ctx)) return

        val root = root(ctx)
        root.mkdirs()
        val tmp = File(root, BuildConfig.BUILD_TAG + ".part")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val tmpPath = tmp.canonicalPath + File.separator

        val total = ctx.assets.openFd("ar.zip").use { it.length }.coerceAtLeast(1L)
        val counting = Counting(ctx.assets.open("ar.zip"))
        ZipInputStream(BufferedInputStream(counting, 1 shl 16)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val out = File(tmp, entry.name)
                if (!out.canonicalPath.startsWith(tmpPath)) throw IOException("Bad entry: " + entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zin.copyTo(it, 1 shl 16) }
                    out.setReadable(true, false)
                    if (!out.name.endsWith(".dll")) out.setExecutable(true, false)
                }
                progress((counting.count * 100 / total).toInt().coerceIn(0, 100))
                entry = zin.nextEntry
            }
        }

        val exe = File(tmp, EXE)
        if (!exe.exists()) throw IOException("Missing $EXE in the bundled archive")
        exe.setExecutable(true, false)
        File(tmp, OK_MARKER).writeText(BuildConfig.BUILD_TAG)

        val target = dir(ctx)
        target.deleteRecursively()
        if (!tmp.renameTo(target)) throw IOException("Cannot move files into place")

        // Drop servers that belong to older builds.
        root.listFiles()?.forEach { if (it.name != BuildConfig.BUILD_TAG) it.deleteRecursively() }
    }
}
