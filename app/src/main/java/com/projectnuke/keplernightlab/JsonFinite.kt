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

/**
 * Null-faithful optional-field readers for intermediate job rewrites.
 * `optInt`/`optLong`/`optString` collapse both a missing key and an explicit
 * JSON null into a default (0 / "" / "null"), which silently corrupts unknown
 * diagnostics into observed zeros or literal "null" strings. These helpers
 * preserve the distinction: missing key or JSONObject.NULL -> null, actual
 * value -> the exact stored value.
 */
internal fun JSONObject.optNullableInt(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)

internal fun JSONObject.optNullableLong(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key)

internal fun JSONObject.optNullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key)

private fun JSONArray.toStringList(): List<String> {
    val values = ArrayList<String>(length())
    for (index in 0 until length()) values += optString(index)
    return values
}
