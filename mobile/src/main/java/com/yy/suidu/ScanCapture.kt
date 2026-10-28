package com.yy.suidu

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Expose one camera output under a narrow cache-only FileProvider root. */
object ScanCapture {
    fun create(context: Context): File {
        val root = File(context.cacheDir, "scans").apply { mkdirs() }.canonicalFile
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60_000L
        root.listFiles()?.filter { it.isFile && it.canonicalFile.parentFile == root && it.lastModified() < cutoff }?.forEach { it.delete() }
        return File.createTempFile("scan-", ".jpg", root)
    }
    fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".scan-files", file)
    fun discard(context: Context, path: String) {
        runCatching {
            val file = File(path).canonicalFile
            val root = File(context.cacheDir, "scans").canonicalFile
            if (file.parentFile == root && file.name.startsWith("scan-") && file.isFile) file.delete()
        }
    }
}
