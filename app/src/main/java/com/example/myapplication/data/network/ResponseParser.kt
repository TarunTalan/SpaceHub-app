package com.example.myapplication.data.network

import okhttp3.ResponseBody
import org.json.JSONObject

/**
 * Small utility to parse common error bodies returned by the backend.
 * Keeps parsing behavior consistent across repositories and reduces duplication.
 */
object ResponseParser {

    fun parseError(errorBody: ResponseBody?): String {
        return try {
            val raw = errorBody?.string()
            if (raw.isNullOrBlank()) return "Request failed."
            return try {
                val json = JSONObject(raw)
                when {
                    json.has("message") -> json.optString("message")
                    json.has("error") -> json.optString("error")
                    json.has("errors") -> json.optString("errors")
                    else -> raw
                }
            } catch (_: Exception) {
                // Not JSON or parse failed; return raw body
                raw
            }
        } catch (_: Exception) {
            "Request failed."
        }
    }
}
