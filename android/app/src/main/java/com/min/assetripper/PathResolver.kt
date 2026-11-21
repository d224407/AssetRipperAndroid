package com.min.assetripper

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File

/** Maps Storage Access Framework URIs to file paths for the server. */
object PathResolver {

    fun resolve(uri: Uri, tree: Boolean): String? {
        try {
            val docId = if (tree) DocumentsContract.getTreeDocumentId(uri) else DocumentsContract.getDocumentId(uri)
            when (uri.authority) {
                "com.android.externalstorage.documents" -> {
                    val i = docId.indexOf(':')
                    if (i < 0) return null
                    val volume = docId.substring(0, i)
                    val rel = docId.substring(i + 1)
                    val base = if (volume == "primary") Environment.getExternalStorageDirectory().path
                    else "/storage/$volume"
                    return if (rel.isEmpty()) base else "$base/$rel"
                }
                "com.android.providers.downloads.documents" -> {
                    if (docId.startsWith("raw:")) return docId.removePrefix("raw:")
                }
            }
        } catch (e: IllegalArgumentException) {
        }
        return null
    }

    /** Copies a file with no real path into filesDir/imports. */
    fun copyToImports(ctx: Context, uri: Uri): String? {
        return try {
            var name = "import.bin"
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it.replace('/', '_') }
            }
            val dir = File(ctx.filesDir, "imports").apply { mkdirs() }
            val out = File(dir, name)
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it, 1 shl 16) }
            } ?: return null
            out.path
        } catch (e: Exception) {
            null
        }
    }
}
