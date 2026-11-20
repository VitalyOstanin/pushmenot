package io.github.vitalyostanin.pushmenot.storage

import com.pushmenot.core.FrequencyKey
import com.pushmenot.core.FrequencyState
import com.pushmenot.core.RuleVersion
import org.json.JSONArray
import org.json.JSONObject

internal object FrequencyJson {
    fun encode(state: FrequencyState): String? {
        if (state.windows.isEmpty()) return null
        val windows = JSONArray()
        for ((key, timestamps) in state.windows) {
            windows.put(
                JSONObject()
                    .put("id", key.rule.id)
                    .put("revision", key.rule.revision)
                    .put("profile", key.profileId)
                    .put("package", key.packageName)
                    .put("group", key.group)
                    .put("timestamps", JSONArray(timestamps)),
            )
        }
        return JSONObject().put("last", state.lastElapsedMillis).put("windows", windows).toString()
    }

    fun decode(payload: String?): FrequencyState {
        if (payload == null) return FrequencyState()
        val data = JSONObject(payload)
        val windows = data.getJSONArray("windows")
        return FrequencyState(
            windows = buildMap {
                for (index in 0 until windows.length()) {
                    val entry = windows.getJSONObject(index)
                    val key = FrequencyKey(
                        rule = RuleVersion(entry.getString("id"), entry.getLong("revision")),
                        profileId = entry.getString("profile"),
                        packageName = entry.getString("package"),
                        group = if (entry.isNull("group")) null else entry.getString("group"),
                    )
                    val timestamps = entry.getJSONArray("timestamps")
                    put(key, List(timestamps.length()) { timestamps.getLong(it) })
                }
            },
            lastElapsedMillis = if (data.isNull("last")) null else data.getLong("last"),
        )
    }
}
