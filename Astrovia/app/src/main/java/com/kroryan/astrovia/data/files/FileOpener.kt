@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.data.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.kroryan.astrovia.data.archive.Csv
import com.kroryan.astrovia.data.fits.FitsFile
import com.kroryan.astrovia.data.vizier.VizieRService
import com.kroryan.astrovia.data.VotableParser
import com.kroryan.astrovia.model.CatalogRow
import java.io.File

/** What a file chosen by the user turned out to be. */
sealed class OpenedFile {
    /** A FITS file kept open for viewing (images and/or tables). */
    class Fits(val name: String, val fits: FitsFile) : OpenedFile()
    /** A table with sky positions (VOTable, CSV, TSV). */
    class Table(val name: String, val rows: List<CatalogRow>, val totalRows: Int) : OpenedFile()
    class Failed(val name: String, val reason: String) : OpenedFile()
}

/** Opens files from the system picker, from "Open with" and from "Share": FITS, VOTable, CSV/TSV. */
object FileOpener {
    private val vizier = VizieRService()

    fun displayName(ctx: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { return it }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    /** Copies the content to the cache (FITS needs random access) and detects the format from the content, not the name. */
    fun open(ctx: Context, uri: Uri): OpenedFile {
        val name = displayName(ctx, uri)
        val dir = File(ctx.cacheDir, "opened").apply { mkdirs() }
        dir.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 24 * 3600 * 1000L }?.forEach { it.delete() }
        val f = File(dir, System.currentTimeMillis().toString() + "_" + name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        try {
            ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                ?: return OpenedFile.Failed(name, "Could not read the file")
        } catch (e: Exception) {
            return OpenedFile.Failed(name, "Could not read the file (${e.message})")
        }
        return openFile(f, name)
    }

    fun openFile(f: File, name: String = f.name): OpenedFile {
        val head = f.inputStream().use { i -> ByteArray(600).let { b -> val n = i.read(b); String(b, 0, maxOf(n, 0), Charsets.ISO_8859_1) } }
        return try {
            when {
                head.startsWith("SIMPLE  =") -> OpenedFile.Fits(name, FitsFile.open(f))
                head.contains("<VOTABLE", ignoreCase = true) || head.trimStart().startsWith("<?xml") -> {
                    val all = VotableParser.parse(f.readText())
                    OpenedFile.Table(name, vizier.rowsFromMaps(all), all.size)
                }
                head.length > 1 && head[0] == '\u001f' && head[1] == '\u008b' ->
                    OpenedFile.Failed(name, "Compressed files (.gz, .fz) are not supported yet: unpack them first")
                else -> {
                    val text = f.readText()
                    val delim = if (text.lineSequence().firstOrNull().orEmpty().count { it == '\t' } > 0) '\t' else ','
                    val rows = if (delim == '\t') tsv(text) else Csv.parse(text)
                    if (rows.isEmpty()) OpenedFile.Failed(name, "Unknown format: expected FITS, VOTable, CSV or TSV")
                    else OpenedFile.Table(name, vizier.rowsFromMaps(rows), rows.size)
                }
            }
        } catch (e: Exception) {
            OpenedFile.Failed(name, e.message ?: "Could not open the file")
        }
    }

    private fun tsv(text: String): List<Map<String, String>> {
        val lines = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        if (lines.size < 2) return emptyList()
        val head = lines[0].split('\t')
        return lines.drop(1).map { l -> val c = l.split('\t'); head.indices.associate { head[it].trim() to c.getOrElse(it) { "" }.trim() } }
    }
}
