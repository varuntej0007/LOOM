package com.loom.runtime.telemetry

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Per-frame CSV writer (blueprint §9). Writes happen on their own thread so disk I/O never
 * adds latency to the frame thread.
 *
 * Pull with:
 *   adb pull /sdcard/Android/data/com.loom.runtime/files/ ./csv/
 */
class CsvLogger(dir: File, prefix: String = "loom") : Closeable {

    val file: File = File(
        dir,
        prefix + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
    )

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "loom-csv") }
    private var writer: BufferedWriter? = null
    private var sinceFlush = 0

    init {
        io.execute {
            writer = BufferedWriter(FileWriter(file)).apply {
                write(HEADER.joinToString(","))
                newLine()
            }
        }
    }

    fun log(fields: List<Any?>) {
        val line = fields.joinToString(",") { format(it) }
        try {
            io.execute {
                val w = writer ?: return@execute
                w.write(line)
                w.newLine()
                if (++sinceFlush >= 30) {
                    w.flush()
                    sinceFlush = 0
                }
            }
        } catch (_: RejectedExecutionException) {
            // Logger was rotated/closed between frames; the row goes to the new file next frame.
        }
    }

    private fun format(v: Any?): String = when (v) {
        null -> ""
        is Double -> if (v.isNaN()) "" else "%.3f".format(Locale.US, v)
        is Float -> if (v.isNaN()) "" else "%.3f".format(Locale.US, v)
        is String -> if (v.isEmpty()) "" else "\"" + v.replace("\"", "'") + "\""
        else -> v.toString()
    }

    override fun close() {
        try {
            io.execute {
                writer?.flush()
                writer?.close()
                writer = null
            }
        } catch (_: RejectedExecutionException) {
            return
        }
        io.shutdown()
        io.awaitTermination(2, TimeUnit.SECONDS)
    }

    companion object {
        /**
         * Blueprint schema first, in its exact order; LOOM additions appended after it.
         * Never reorder — only append.
         */
        val HEADER = listOf(
            "timestamp_ms", "frame_no", "capture_ms", "preprocess_ms", "inference_ms",
            "narrator_ms", "total_ms", "fps_1s", "detections", "mean_conf",
            "profile_capture", "profile_inference", "profile_narrator",
            "thermal_status", "thermal_headroom", "battery_current_ua",
            "weaver_event", "swap_gap_ms",
            // additions
            "display_ms", "camera_fps", "mode", "stress", "target_fps", "narration",
            "thermal_forecast", "pace_fps",
        )
    }
}
