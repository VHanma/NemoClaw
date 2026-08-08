package com.k2fsa.sherpa.onnx.tts.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

object DocumentReader {
    suspend fun read(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val name = displayName(context, uri).lowercase()
        when {
            name.endsWith(".pdf") -> readPdf(context, uri)
            name.endsWith(".docx") -> readDocx(context, uri)
            name.endsWith(".epub") -> readEpub(context, uri)
            name.endsWith(".html") || name.endsWith(".htm") -> stripHtml(readPlain(context, uri))
            else -> readPlain(context, uri)
        }.replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "document"
        }
        return uri.lastPathSegment ?: "document"
    }

    private fun readPlain(context: Context, uri: Uri): String {
        return context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open document" }
            input.bufferedReader().readText()
        }
    }

    private fun readPdf(context: Context, uri: Uri): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        return context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open PDF" }
            PDDocument.load(input).use { doc -> PDFTextStripper().getText(doc) }
        }
    }

    private fun readDocx(context: Context, uri: Uri): String {
        val xml = readZipEntries(context, uri) { it == "word/document.xml" }.firstOrNull().orEmpty()
        if (xml.isBlank()) return ""
        val expanded = xml
            .replace(Regex("<w:tab[^>]*/>"), "\t")
            .replace(Regex("</w:p>"), "\n")
            .replace(Regex("</w:tr>"), "\n")
        return stripHtml(expanded)
    }

    private fun readEpub(context: Context, uri: Uri): String {
        val parts = readZipEntries(context, uri) { path ->
            val p = path.lowercase()
            p.endsWith(".xhtml") || p.endsWith(".html") || p.endsWith(".htm")
        }
        return parts.joinToString("\n\n") { stripHtml(it) }
    }

    private fun readZipEntries(context: Context, uri: Uri, include: (String) -> Boolean): List<String> {
        val out = mutableListOf<String>()
        context.contentResolver.openInputStream(uri).use { raw ->
            requireNotNull(raw) { "Could not open archive" }
            ZipInputStream(raw.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && include(entry.name)) {
                        val bytes = ByteArrayOutputStream()
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            bytes.write(buf, 0, n)
                            if (bytes.size() > 12 * 1024 * 1024) break
                        }
                        out += bytes.toString(Charsets.UTF_8.name())
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return out
    }

    @Suppress("DEPRECATION")
    private fun stripHtml(source: String): String {
        return Html.fromHtml(source, Html.FROM_HTML_MODE_LEGACY).toString()
    }
}
