// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import kotlinx.coroutines.CancellationException

/**
 * The TV's remote. Keys are a fixed list of Android key codes, so nothing the user does reaches
 * the shell line except text, which [typeCommand] quotes. [repeats] keys repeat while held.
 */
enum class RemoteKey(val code: Int, val repeats: Boolean = false) {
    UP(19, repeats = true),
    DOWN(20, repeats = true),
    LEFT(21, repeats = true),
    RIGHT(22, repeats = true),
    OK(23),
    BACK(4),
    HOME(3),
    MENU(82),
    PLAY_PAUSE(85),
    VOLUME_UP(24, repeats = true),
    VOLUME_DOWN(25, repeats = true),
    MUTE(164),
    /** Backspace in the focused text box. */
    DEL(67, repeats = true),
    // Vogue: the keys a satellite receiver's own remote has. Plain Android key codes.
    POWER(26),
    SLEEP(223),
    WAKEUP(224),
    CHANNEL_UP(166),
    CHANNEL_DOWN(167),
    INFO(165),
    GUIDE(172),
    RED(183),
    GREEN(184),
    YELLOW(185),
    BLUE(186),
    REWIND(89),
    FAST_FORWARD(90),
    STOP(86),
    NUM_0(7), NUM_1(8), NUM_2(9), NUM_3(10), NUM_4(11),
    NUM_5(12), NUM_6(13), NUM_7(14), NUM_8(15), NUM_9(16),
}

class Remote(private val session: AdbSession, private val keys: KeyHelper? = null) {

    /**
     * False when the TV answered with an error. Throws if the link is gone. Goes through [keys]
     * while its helper runs, and `input keyevent` when it can't.
     */
    suspend fun press(key: RemoteKey): Boolean =
        keys?.press(key.code) ?: (session.shell("input keyevent ${key.code}").exitCode == 0)

    /**
     * Home that opens [launcher], the activity Android resolves Home to (see [Actions.currentHome]).
     * For Fire TV, whose Home key goes to Amazon's launcher whatever the default is. Null, a name
     * that isn't a plain component, or a TV that won't start it fall back to the Home key.
     */
    suspend fun home(launcher: String?): Boolean {
        if (launcher != null && COMPONENT.matches(launcher)) {
            val r = session.shell("${Commands.OPEN_HOME} '$launcher' 2>&1")
            if (r.exitCode == 0 && "Error" !in r.output) return true
        }
        return press(RemoteKey.HOME)
    }

    /**
     * Opens the TV's own system update screen. Some builds have none, and older `am` reports a
     * missing screen in its output with exit code 0, so both are checked.
     */
    suspend fun openUpdates(): Boolean {
        val r = session.shell("am start -a android.settings.SYSTEM_UPDATE_SETTINGS 2>&1")
        return r.exitCode == 0 && !r.output.contains("Error")
    }

    /** Moves the TV's pointer. Only the key helper can: false when it isn't running or the TV refused. */
    suspend fun move(dx: Int, dy: Int): Boolean = keys?.move(dx, dy) ?: false

    /** A left click of the TV's pointer, through [keys] like [move]. */
    suspend fun click(): Boolean = keys?.click() ?: false

    /**
     * Arms the TV to press Power in [minutes], if it is still awake then; 0 cancels. The wait runs
     * on the TV, so it outlives this link and the app.
     */
    suspend fun sleepIn(minutes: Int): Boolean {
        val cancel = "kill \$(cat $SLEEP_PID 2>/dev/null) 2>/dev/null; rm -f $SLEEP_PID"
        val script = "sleep ${minutes * 60}; case \"\$(dumpsys power)\" in *mWakefulness=Awake*) input keyevent ${RemoteKey.POWER.code};; esac"
        val command = if (minutes <= 0) cancel else "$cancel; nohup sh -c '$script' >/dev/null 2>&1 & echo \$! > $SLEEP_PID"
        return session.shell(command).exitCode == 0
    }

    /** Types [text] into whatever box has focus on the TV, through [keys] like [press]. Check [typeable] first. */
    suspend fun type(text: String): Boolean = keys?.type(text) ?: (session.shell(typeCommand(text)).exitCode == 0)

    /**
     * Turns the TV's picture off or back on, leaving the TV awake so the link stays up. Only the
     * key helper can: false when it isn't running or the TV refused. Throws if the link is gone.
     */
    suspend fun screen(on: Boolean): Boolean = keys?.screen(on) ?: false

    /**
     * Restarts the TV. adbd's `reboot:` service works for the shell user on stock builds; where a
     * build refuses it, `svc power reboot` goes through the power service instead. Returns false
     * only when the TV said no. A link that drops on the way counts as restarting.
     */
    suspend fun reboot(): Boolean {
        try {
            session.reboot()
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Refused, or the link went: the fallback tells which.
        }
        val r = try {
            session.shell("svc power reboot 2>&1")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return true
        }
        return r.exitCode == 0 && r.output.isBlank()
    }

    companion object {
        const val MAX_TEXT = 200
        private const val SLEEP_PID = "/data/local/tmp/tellyscope-sleep.pid"

        /** `pkg/.Activity` or `pkg/pkg.Activity`; a nested class has a `$`, which the single quotes keep. */
        private val COMPONENT = Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")

        /**
         * What `input text` can type: printable ASCII only (it has no key for anything else), and
         * never `%s`, which it reads as a space.
         */
        fun typeable(text: String): Boolean =
            text.isNotEmpty() && text.length <= MAX_TEXT && text.all { it in ' '..'~' } && "%s" !in text

        /** `input text` reads `%s` as a space, and the whole thing is single-quoted for the shell. */
        fun typeCommand(text: String): String {
            require(typeable(text)) { "input text can't type this" }
            return "input text " + shellQuote(text.replace(" ", "%s"))
        }
    }
}
