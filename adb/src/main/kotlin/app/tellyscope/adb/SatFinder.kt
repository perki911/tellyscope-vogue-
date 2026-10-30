// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * One reading of the receiver's satellite tuner (a DVB frontend): the lock flags, signal strength,
 * SNR and bit error rate, as the driver reports them. [supported] is false when the driver refused
 * any of the four reads, and then the numbers mean nothing.
 */
data class FeReading(val status: Int, val strength: Int, val snr: Int, val ber: Long, val supported: Boolean = true) {
    /** FE_HAS_LOCK: the tuner is locked on a transponder. */
    val locked: Boolean get() = status and HAS_LOCK != 0

    companion object {
        private const val HAS_LOCK = 0x10
        private val LINE = Regex("""status=0x([0-9a-fA-F]+) strength=(\d+) snr=(\d+) ber=(\d+) err=(-?\d+),(-?\d+),(-?\d+),(-?\d+)""")

        /** One line of the `fe_read` helper, or null when the line isn't one. */
        fun parse(line: String): FeReading? {
            val m = LINE.find(line) ?: return null
            val g = m.groupValues
            val ok = (5..8).all { g[it] == "0" }
            return FeReading(g[1].toInt(16), g[2].toInt(), g[3].toInt(), g[4].toLong(), ok)
        }
    }
}

/**
 * Streams [FeReading]s from the TV, for a satellite finder. A tiny read-only helper
 * (`fe_read`, a static ARM binary in the app's assets) is pushed to /data/local/tmp, reads the
 * tuner about ten times a second, and is deleted when the stream ends. It only reads status and
 * signal values: it never tunes or changes the tuner.
 */
object SatFinder {
    const val PATH = "/data/local/tmp/tellyscope-fe"
    /** A stray helper stops by itself after an hour at ten reads a second. */
    private const val MAX_READS = 36000
    private const val INTERVAL_MS = 100

    fun readings(session: AdbSession, binary: ByteArray): Flow<FeReading> = flow {
        var pipe: AdbPipe? = null
        try {
            session.push(binary, PATH)
            session.shell("chmod 755 $PATH")
            val p = session.exec("$PATH $MAX_READS $INTERVAL_MS 2>&1")
            pipe = p
            while (true) {
                val line = runInterruptible(Dispatchers.IO) { p.stdout.readUtf8Line() } ?: break
                val reading = FeReading.parse(line) ?: throw IOException(line.take(120))
                emit(reading)
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { pipe?.close() }
                runCatching { session.shell("rm -f $PATH") }
            }
        }
    }
}
