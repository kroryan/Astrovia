package com.kroryan.astrovia.model

import org.json.JSONArray
import org.json.JSONObject

/** A named position the user keeps in a target list. */
data class Target(val name: String, val raDeg: Double, val decDeg: Double) {
    companion object {
        fun listToJson(list: List<Target>): String = JSONArray().also { a ->
            list.forEach { a.put(JSONObject().put("name", it.name).put("ra", it.raDeg).put("dec", it.decDeg)) }
        }.toString()

        fun listFromJson(text: String): List<Target> {
            val a = JSONArray(text)
            return List(a.length()) { i -> a.getJSONObject(i).let { Target(it.getString("name"), it.getDouble("ra"), it.getDouble("dec")) } }
        }
    }
}
