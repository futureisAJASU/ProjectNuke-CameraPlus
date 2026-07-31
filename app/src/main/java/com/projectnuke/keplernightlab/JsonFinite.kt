package com.projectnuke.keplernightlab

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.putFiniteNumber(key: String, value: Double): JSONObject {
    if (value.isFinite()) return put(key, value)
    put(key, JSONObject.NULL)
    val errors = optJSONArray("nonFiniteFields") ?: JSONArray().also { put("nonFiniteFields", it) }
    if (!errors.toStringList().contains(key)) errors.put(key)
    put("metadataError", "NON_FINITE_VALUE")
    return this
}

internal fun JSONObject.putFiniteNumber(key: String, value: Float): JSONObject =
    putFiniteNumber(key, value.toDouble())

private fun JSONArray.toStringList(): List<String> {
    val values = ArrayList<String>(length())
    for (index in 0 until length()) values += optString(index)
    return values
}
