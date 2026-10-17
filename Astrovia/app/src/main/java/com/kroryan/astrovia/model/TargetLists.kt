package com.kroryan.astrovia.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Named target lists kept on the device, plus the built-in ones (Messier). Import/export as JSON, CSV or one name per line. */
class TargetLists(private val ctx: Context) {
    private val prefs get() = ctx.getSharedPreferences("astrovia_lists", Context.MODE_PRIVATE)

    fun builtIn(): Map<String, List<Target>> = runCatching {
        val txt = ctx.assets.open("lists.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(txt).getJSONArray("lists")
        (0 until arr.length()).associate { i ->
            val o = arr.getJSONObject(i); val t = o.getJSONArray("targets")
            o.getString("name") to List(t.length()) { k -> t.getJSONObject(k).let { Target(it.getString("name"), it.getDouble("ra"), it.getDouble("dec")) } }
        }
    }.getOrDefault(emptyMap())

    /** The user's lists. The first start migrates the old single target list into "My targets". */
    fun user(): Map<String, List<Target>> {
        val raw = prefs.getString("lists", null)
        if (raw == null) {
            val old = ctx.getSharedPreferences("astrovia_targets", Context.MODE_PRIVATE).getString("list", "[]").orEmpty()
            val migrated = runCatching { Target.listFromJson(old) }.getOrDefault(emptyList())
            return mapOf("My targets" to migrated)
        }
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { k -> Target.listFromJson(o.getJSONArray(k).toString()) }
        }.getOrDefault(mapOf("My targets" to emptyList()))
    }

    fun save(lists: Map<String, List<Target>>) {
        val o = JSONObject()
        lists.forEach { (k, v) -> o.put(k, JSONArray(Target.listToJson(v))) }
        prefs.edit().putString("lists", o.toString()).apply()
    }

    companion object {
        fun toCsv(list: List<Target>): String = "name,ra_deg,dec_deg\n" + list.joinToString("\n") { "\"${it.name.replace("\"", "\"\"")}\",${it.raDeg},${it.decDeg}" }

        /** Reads CSV (name,ra,dec) or JSON ([{name,ra,dec}]) into targets; lines without coordinates are returned as names to resolve. */
        /** Splits a CSV/TSV/semicolon line honouring double quotes. */
        internal fun splitLine(line: String): List<String> {
            val d = when { '\t' in line -> '\t'; ';' in line && ',' !in line -> ';'; else -> ',' }
            val out = ArrayList<String>(); val sb = StringBuilder(); var q = false; var i = 0
            while (i < line.length) {
                val c = line[i]
                if (q) { if (c == '"') { if (i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i++ } else q = false } else sb.append(c) }
                else if (c == '"') q = true else if (c == d) { out.add(sb.toString().trim()); sb.setLength(0) } else sb.append(c)
                i++
            }
            out.add(sb.toString().trim())
            return out
        }

        fun parse(text: String): Pair<List<Target>, List<String>> {
            val t = text.trim()
            if (t.startsWith("[")) return runCatching { Target.listFromJson(t) to emptyList<String>() }.getOrDefault(emptyList<Target>() to emptyList())
            val found = ArrayList<Target>(); val names = ArrayList<String>()
            for (line in t.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && !it.lowercase().startsWith("name,") }) {
                val f = splitLine(line)
                val ra = f.getOrNull(f.size - 2)?.toDoubleOrNull(); val dec = f.lastOrNull()?.toDoubleOrNull()
                if (f.size >= 3 && ra != null && dec != null) found.add(Target(f.subList(0, f.size - 2).joinToString(",").trim(), ra, dec)) else names.add(line)
            }
            return found to names
        }
    }
}
