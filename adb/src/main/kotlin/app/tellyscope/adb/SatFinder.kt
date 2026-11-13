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
 * What the TV is tuned to, pieced together from lines in its own log. The tuner can't be asked
 * (it only answers signal reads), so a field stays null until a line says it.
 */
data class TuneInfo(
    /** Intermediate frequency, MHz: what the dish cable carries. */
    val ifMhz: Int? = null,
    /** Downlink frequency, MHz. */
    val mhz: Int? = null,
    val vertical: Boolean? = null,
    val sid: Int? = null,
    /** Satellite position in degrees, negative for west. */
    val longitude: Double? = null,
) {
    /** This with one log line folded in; the same when the line says nothing about tuning. */
    fun with(line: String): TuneInfo {
        LOCK.find(line)?.let {
            val ifNow = it.groupValues[1].toInt() / 1000
            // A downlink that doesn't fit a universal LNB with this IF belongs to the last channel.
            val fits = mhz != null && (kotlin.math.abs(mhz - ifNow - 9750) <= 5 || kotlin.math.abs(mhz - ifNow - 10600) <= 5)
            return if (fits) copy(ifMhz = ifNow) else TuneInfo(ifMhz = ifNow)
        }
        LOGO.find(line)?.let {
            val g = it.groupValues
            return copy(
                mhz = g[7].toInt(),
                vertical = g[3] == "1",
                sid = g[4].toInt(),
                longitude = (g[5].toDouble() + g[6].toDouble()) / 2,
            )
        }
        return this
    }

    private companion object {
        val LOCK = Regex("""lock centre freq (\d+)""")
        val LOGO = Regex("""freq >= (\d+) and freq <= (\d+) and pol = (\d+) and sid = (\d+) and longitude >= (-?[\d.]+) and longitude <= (-?[\d.]+) order by abs\(freq - (\d+)\)""")
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

    /**
     * What the TV is tuned to, read from its log. The log buffer replays the recent past first,
     * so the first values are the latest known; each channel change then updates them.
     */
    fun tuning(session: AdbSession): Flow<TuneInfo> = flow {
        var pipe: AdbPipe? = null
        try {
            val p = session.exec("logcat AM_DEBUG:I TVProgramLogo:E '*:S' 2>&1")
            pipe = p
            var info = TuneInfo()
            while (true) {
                val line = runInterruptible(Dispatchers.IO) { p.stdout.readUtf8Line() } ?: break
                val next = info.with(line)
                if (next != info) {
                    info = next
                    emit(info)
                }
            }
        } finally {
            withContext(NonCancellable) { runCatching { pipe?.close() } }
        }
    }

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
