package com.anezium.rokidbus.ink

import org.json.JSONArray
import org.json.JSONObject

internal object DeterministicJson {
    fun stringify(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
            "${JSONObject.quote(key)}:${stringify(value.get(key))}"
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { stringify(value.get(it)) }
        is Map<*, *> -> value.entries.sortedBy { it.key.toString() }.joinToString(",", "{", "}") { (key, item) ->
            "${JSONObject.quote(key.toString())}:${stringify(item)}"
        }
        is Iterable<*> -> value.joinToString(",", "[", "]") { stringify(it) }
        is String -> JSONObject.quote(value)
        is Boolean -> value.toString()
        is Number -> if (value.toDouble().isFinite()) JSONObject.numberToString(value) else "null"
        else -> JSONObject.quote(value.toString())
    }
}
