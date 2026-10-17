@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.data.archive

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * VOTable reader for TAP answers: TABLEDATA and BINARY (base64 stream) serialisations, first table only.
 * Some archives (HEASARC) only answer in BINARY, others (MAST) in TABLEDATA.
 */
object VotableTable {
    private class Field(val name: String, val datatype: String, val arraysize: String?)

    fun parse(text: String): List<Map<String, String>> {
        val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()
        p.setInput(text.reader())
        val fields = ArrayList<Field>()
        val rows = ArrayList<Map<String, String>>()
        var inData = false
        var cur = ArrayList<String>()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "INFO" -> if (p.getAttributeValue(null, "name") == "QUERY_STATUS" && p.getAttributeValue(null, "value") == "ERROR") {
                        throw java.io.IOException(p.nextText().trim().ifEmpty { "The archive rejected the query" })
                    }
                    "FIELD" -> fields.add(Field(p.getAttributeValue(null, "name") ?: "col${fields.size + 1}", p.getAttributeValue(null, "datatype") ?: "char", p.getAttributeValue(null, "arraysize")))
                    "TABLEDATA" -> inData = true
                    "TR" -> if (inData) cur = ArrayList()
                    "TD" -> if (inData) cur.add(p.nextText())
                    "STREAM" -> {
                        val b64 = p.nextText().filter { !it.isWhitespace() }
                        return decodeBinary(Base64.getDecoder().decode(b64), fields)
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (p.name) {
                    "TR" -> if (inData) rows.add(LinkedHashMap<String, String>().also { m -> fields.forEachIndexed { i, f -> m[f.name] = cur.getOrElse(i) { "" } } })
                    "TABLEDATA" -> return rows
                }
            }
            ev = p.next()
        }
        return rows
    }

    private fun decodeBinary(bytes: ByteArray, fields: List<Field>): List<Map<String, String>> {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val rows = ArrayList<Map<String, String>>()
        while (bb.hasRemaining()) {
            val row = LinkedHashMap<String, String>()
            try {
                for (f in fields) row[f.name] = readValue(bb, f)
            } catch (e: java.nio.BufferUnderflowException) {
                break
            }
            rows.add(row)
        }
        return rows
    }

    private fun readValue(bb: ByteBuffer, f: Field): String {
        val size = f.arraysize
        fun count(): Int = when {
            size == null -> 1
            size.endsWith("*") -> bb.getInt()
            else -> size.split('x').map { it.toIntOrNull() ?: 1 }.fold(1) { a, b -> a * b }
        }
        return when (f.datatype) {
            "char" -> { val n = count(); String(ByteArray(n).also { bb.get(it) }, Charsets.ISO_8859_1).trim { it <= ' ' } }
            "unicodeChar" -> { val n = count(); CharArray(n) { bb.getChar() }.concatToString().trim() }
            else -> {
                val n = count()
                val parts = (0 until n).map { one(bb, f.datatype) }
                if (n == 1) parts[0] else parts.take(4).joinToString(",") + if (n > 4) ",…" else ""
            }
        }
    }

    private fun one(bb: ByteBuffer, dt: String): String = when (dt) {
        "double" -> bb.getDouble().let { if (it.isNaN()) "" else it.toString() }
        "float" -> bb.getFloat().let { if (it.isNaN()) "" else it.toString() }
        "int" -> bb.getInt().toString()
        "short" -> bb.getShort().toString()
        "long" -> bb.getLong().toString()
        "unsignedByte" -> (bb.get().toInt() and 0xFF).toString()
        "boolean" -> if (bb.get().toInt() == 'T'.code || bb.get(bb.position() - 1).toInt() == 1) "true" else "false"
        else -> throw java.io.IOException("Unsupported VOTable datatype $dt")
    }
}
