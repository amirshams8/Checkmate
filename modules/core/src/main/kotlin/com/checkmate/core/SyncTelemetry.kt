package com.checkmate.core

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Up/down transition telemetry for repeating sync jobs, on top of [TelemetryClient].
 *
 * Sync managers run every few minutes; reporting each run would flood the sidecar table. Instead a
 * job reports once when it starts failing (`<NAME>_FAILED`, status=error) and once when it works
 * again (`<NAME>_RECOVERED`). State is in memory only, so a process restart may report one extra
 * failure; that is harmless. Never throws and never records secrets: only the job name, an
 * exception class name and a short detail such as `http_503`.
 */
object SyncTelemetry {

    private val down = ConcurrentHashMap<String, Boolean>()

    fun ok(name: String) {
        try {
            if (down.remove(name) != null) {
                TelemetryClient.emit("${name}_RECOVERED", "sync-${name.lowercase()}")
            }
        } catch (_: Throwable) {
        }
    }

    fun failed(name: String, error: Throwable? = null, detail: String? = null) {
        try {
            if (down.putIfAbsent(name, true) != null) return
            val payload = JSONObject()
            if (error != null) payload.put("error", error.javaClass.simpleName)
            if (detail != null) payload.put("detail", detail)
            TelemetryClient.emit("${name}_FAILED", "sync-${name.lowercase()}", status = "error", payload = payload)
        } catch (_: Throwable) {
        }
    }
}
