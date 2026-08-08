package com.anezium.rokidbus.ink

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

internal typealias InkObject = LinkedHashMap<String, Any?>

internal fun JSONObject.toInkObject(): InkObject = LinkedHashMap<String, Any?>().also { result ->
    keys().asSequence().toList().sorted().forEach { key ->
        result[key] = get(key).toInkValue()
    }
}

private fun Any?.toInkValue(): Any? = when (this) {
    null, JSONObject.NULL -> null
    is JSONObject -> toInkObject()
    is JSONArray -> MutableList(length()) { index -> get(index).toInkValue() }
    is Number, is String, is Boolean -> this
    else -> toString()
}

internal fun Any?.deepCopyInk(): Any? = when (this) {
    is Map<*, *> -> LinkedHashMap<String, Any?>().also { copy ->
        entries.sortedBy { it.key.toString() }.forEach { (key, value) ->
            copy[key.toString()] = value.deepCopyInk()
        }
    }
    is List<*> -> mapTo(mutableListOf()) { it.deepCopyInk() }
    else -> this
}

internal fun InkObject.deepCopyObject(): InkObject = LinkedHashMap<String, Any?>().also { copy ->
    entries.sortedBy { it.key }.forEach { (key, value) -> copy[key] = value.deepCopyInk() }
}

internal fun deepMerge(base: InkObject, override: InkObject): InkObject {
    val result = base.deepCopyObject()
    override.forEach { (key, value) ->
        val previous = result[key]
        result[key] = if (previous is Map<*, *> && value is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            deepMerge(previous as InkObject, value as InkObject)
        } else {
            value.deepCopyInk()
        }
    }
    return result
}

internal fun Any?.toJsonValue(): Any = when (this) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject().also { json ->
        entries.sortedBy { it.key.toString() }.forEach { (key, value) ->
            json.put(key.toString(), value.toJsonValue())
        }
    }
    is List<*> -> JSONArray().also { json -> forEach { json.put(it.toJsonValue()) } }
    else -> this
}

internal fun Any?.jsonByteSize(): Int = DeterministicJson.stringify(toJsonValue()).toByteArray(StandardCharsets.UTF_8).size

internal fun renderString(value: Any?): String = when (value) {
    null -> ""
    is Double -> if (value.isFinite() && value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    is Float -> if (value.isFinite() && value == value.toLong().toFloat()) value.toLong().toString() else value.toString()
    is Map<*, *>, is List<*> -> DeterministicJson.stringify(value.toJsonValue())
    else -> value.toString()
}
