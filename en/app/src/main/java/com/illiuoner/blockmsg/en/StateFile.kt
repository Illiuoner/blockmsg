package com.illiuoner.blockmsg.en

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

internal class StateFile(file: File) {
    private val file = AtomicFile(file)

    fun read(): SessionState {
        val json = try {
            JSONObject(file.readFully().toString(Charsets.UTF_8))
        } catch (_: FileNotFoundException) {
            return SessionState()
        }
        val defaults = SessionState()
        val timer = json.optJSONObject("timer") ?: JSONObject()
        val options = json.optJSONObject("options") ?: JSONObject()
        val focus = json.optJSONObject("focus")
        return SessionState(
            workout = readMark(json.optJSONObject("workout")),
            lastWorkoutMillis = json.optLong("lastWorkoutMillis"),
            timer = Countdown(
                seconds = timer.optInt("seconds", defaults.timer.seconds).coerceIn(1, MAX_TIMER_SECONDS),
                deadline = readMark(timer.optJSONObject("deadline")),
                pausedMillis = timer.optLong("pausedMillis", -1).takeIf { it >= 0 },
                finished = timer.optBoolean("finished"),
            ),
            options = Options(
                pin = options.optBoolean("pin", defaults.options.pin),
                keepScreenOn = options.optBoolean("keepScreenOn"),
                volume = options.optInt("volume", defaults.options.volume).coerceIn(0, 100),
                sound = TimerSound.entries.find { it.name == options.optString("sound") }
                    ?: defaults.options.sound,
                vibration = options.optBoolean("vibration"),
                setupSeen = options.optBoolean("setupSeen"),
            ),
            presets = json.optJSONArray("presets")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let {
                        Preset(
                            it.getString("id"), it.getString("name"),
                            it.getInt("seconds").coerceIn(1, MAX_TIMER_SECONDS),
                        )
                    }
                }
            } ?: defaults.presets,
            focus = focus?.let {
                FocusChange(
                    ruleUri = it.optString("ruleUri").takeIf(String::isNotEmpty),
                    prior = it.optJSONObject("prior")?.let { prior ->
                        LegacyPolicy(
                            prior.getInt("filter"), prior.getInt("categories"), prior.getInt("calls"),
                            prior.getInt("messages"), prior.getInt("effects"),
                        )
                    },
                )
            },
            focusEnding = json.optBoolean("focusEnding"),
        )
    }

    fun write(state: SessionState) {
        val json = JSONObject().apply {
            put("workout", markJson(state.workout))
            put("lastWorkoutMillis", state.lastWorkoutMillis)
            put("timer", JSONObject().apply {
                put("seconds", state.timer.seconds)
                put("deadline", markJson(state.timer.deadline))
                put("pausedMillis", state.timer.pausedMillis)
                put("finished", state.timer.finished)
            })
            put("options", JSONObject().apply {
                put("pin", state.options.pin)
                put("keepScreenOn", state.options.keepScreenOn)
                put("volume", state.options.volume)
                put("sound", state.options.sound.name)
                put("vibration", state.options.vibration)
                put("setupSeen", state.options.setupSeen)
            })
            put("presets", JSONArray().apply {
                state.presets.forEach { preset ->
                    put(JSONObject().put("id", preset.id).put("name", preset.name).put("seconds", preset.seconds))
                }
            })
            state.focus?.let { focus ->
                put("focus", JSONObject().apply {
                    put("ruleUri", focus.ruleUri)
                    focus.prior?.let { prior ->
                        put("prior", JSONObject().put("filter", prior.filter).put("categories", prior.categories)
                            .put("calls", prior.calls).put("messages", prior.messages).put("effects", prior.effects))
                    }
                })
            }
            put("focusEnding", state.focusEnding)
        }
        val stream = file.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun readMark(json: JSONObject?) =
        json?.let { TimeMark(it.getLong("elapsed"), it.getLong("wall"), it.optInt("boot", -1)) }

    private fun markJson(mark: TimeMark?) =
        mark?.let { JSONObject().put("elapsed", it.elapsed).put("wall", it.wall).put("boot", it.boot) }
}
