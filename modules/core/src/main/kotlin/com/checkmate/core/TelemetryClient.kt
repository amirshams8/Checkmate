package com.checkmate.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Fire-and-forget telemetry to the Termux sidecar (~/workspaces/telemetry, API.md).
 *
 * Contract: POST http://127.0.0.1:8910/telemetry {"events":[{ts_ms, trace_id, source, event,
 * status, expect_event, deadline_ms, payload}]}. A pending event with expect_event+deadline_ms
 * expires server-side into an alert if no later same-trace event matches.
 *
 * Never throws, never blocks the caller: every emit is queued on one background thread, held in a
 * small ring buffer in [CheckmatePrefs] and flushed in order. If the sidecar is down the buffer
 * is kept (oldest dropped past [MAX_BUFFER]) and retried on the next emit. Missing fields are
 * omitted, never invented.
 */
object TelemetryClient {

    private const val ENDPOINT = "http://127.0.0.1:8910/telemetry"
    private const val KEY_BUFFER = "telemetry_buffer"
    private const val MAX_BUFFER = 200
    private const val TIMEOUT_MS = 2000
    const val SOURCE = "checkmate"

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "telemetry").apply { isDaemon = true }
    }

    fun emit(
        event: String,
        traceId: String?,
        status: String = "ok",
        expectEvent: String? = null,
        deadlineMs: Long? = null,
        payload: JSONObject? = null
    ) {
        val ts = System.currentTimeMillis()
        try {
            executor.execute {
                try {
                    val ev = JSONObject()
                        .put("ts_ms", ts)
                        .put("source", SOURCE)
                        .put("event", event)
                        .put("status", status)
                    if (traceId != null) ev.put("trace_id", traceId)
                    if (expectEvent != null) ev.put("expect_event", expectEvent)
                    if (deadlineMs != null) ev.put("deadline_ms", deadlineMs)
                    if (payload != null) ev.put("payload", payload)
                    append(ev)
                    flush()
                } catch (_: Throwable) {
                    // telemetry must never affect the app
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** Pending-with-deadline helper: "[event] happened, expect [expect] within [withinMs]". */
    fun expect(
        event: String, traceId: String?, expect: String, withinMs: Long, payload: JSONObject? = null
    ) = emit(
        event, traceId, status = "pending", expectEvent = expect,
        deadlineMs = System.currentTimeMillis() + withinMs, payload = payload
    )

    private fun readBuffer(): JSONArray =
        try {
            JSONArray(CheckmatePrefs.getString(KEY_BUFFER, null) ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }

    private fun writeBuffer(items: List<Any>) {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        CheckmatePrefs.putString(KEY_BUFFER, arr.toString())
    }

    private fun append(ev: JSONObject) {
        val cur = readBuffer()
        val items = ArrayList<Any>()
        for (i in 0 until cur.length()) items.add(cur.get(i))
        items.add(ev)
        writeBuffer(if (items.size > MAX_BUFFER) items.subList(items.size - MAX_BUFFER, items.size) else items)
    }

    /** Runs on the telemetry thread only. Removes exactly the events the sidecar accepted. */
    private fun flush() {
        val cur = readBuffer()
        val n = cur.length()
        if (n == 0) return
        val body = JSONObject().put("events", cur).toString().toByteArray()
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body) }
            if (conn.responseCode == 200) {
                val rest = readBuffer()
                val keep = ArrayList<Any>()
                for (i in n until rest.length()) keep.add(rest.get(i))
                writeBuffer(keep)
            } else if (conn.responseCode == 400) {
                // sidecar rejected the batch as invalid: drop it so one bad event cannot wedge the queue
                val rest = readBuffer()
                val keep = ArrayList<Any>()
                for (i in n until rest.length()) keep.add(rest.get(i))
                writeBuffer(keep)
            }
        } catch (_: Exception) {
            // sidecar down / offline: keep the buffer for the next emit
        } finally {
            conn.disconnect()
        }
    }
}
