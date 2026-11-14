// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.helper;

import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The remote's key sender. Runs on the TV as the shell user, through {@code app_process}, and stays
 * up for as long as the remote is open: {@code input keyevent} starts a fresh VM for every key,
 * which is most of a second on a slow TV. Reads one Android key code per line on stdin, injects a
 * press and release the way {@code input} does, and answers {@code 1} when the TV took both or
 * {@code 0} when it didn't. Prints {@code ready} first. Only key codes are read, so nothing typed
 * reaches a shell. The argument is the jar's own path, deleted once the helper is up; it exits at
 * the end of stdin. A line starting with {@code t} is text instead: the rest is typed the way
 * {@code input text} types it, one key press per character. {@code p0} turns the TV's picture off
 * and {@code p2} back on, through SurfaceFlinger: the TV stays awake, so ADB stays connected, and
 * the picture stays off after the helper exits.
 */
public final class Keys {
    /** InputManager's INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH: answers once the focused app is done with it. */
    private static final int WAIT_FOR_FINISH = 2;

    public static void main(String[] args) throws Exception {
        Object manager;
        Method inject;
        try {
            // Android 14 moved injection to InputManagerGlobal; older builds have it on InputManager.
            Class<?> global = Class.forName("android.hardware.input.InputManagerGlobal");
            manager = global.getDeclaredMethod("getInstance").invoke(null);
            inject = global.getMethod("injectInputEvent", InputEvent.class, int.class);
        } catch (ClassNotFoundException e) {
            Class<?> im = Class.forName("android.hardware.input.InputManager");
            manager = im.getDeclaredMethod("getInstance").invoke(null);
            inject = im.getMethod("injectInputEvent", InputEvent.class, int.class);
        }
        if (args.length == 1) new File(args[0]).delete();

        PrintStream out = new PrintStream(System.out, false, "UTF-8");
        out.print("ready\n");
        out.flush();
        KeyCharacterMap keymap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        for (String line; (line = in.readLine()) != null; ) {
            boolean ok;
            try {
                if (line.startsWith("p")) {
                    out.print(power(Integer.parseInt(line.substring(1).trim())) ? "1\n" : "0\n");
                    out.flush();
                    continue;
                }
                if (line.startsWith("m")) {
                    String[] d = line.substring(1).trim().split(" ");
                    out.print(mouse(Integer.parseInt(d[0]), Integer.parseInt(d[1]), false) ? "1\n" : "0\n");
                    out.flush();
                    continue;
                }
                if (line.equals("c")) {
                    out.print(mouse(0, 0, true) ? "1\n" : "0\n");
                    out.flush();
                    continue;
                }
                if (line.startsWith("t")) {
                    out.print(type(manager, inject, keymap, line.substring(1)) ? "1\n" : "0\n");
                    out.flush();
                    continue;
                }
                int code = Integer.parseInt(line.trim());
                long now = SystemClock.uptimeMillis();
                ok = send(manager, inject, now, KeyEvent.ACTION_DOWN, code, 0)
                    && send(manager, inject, now, KeyEvent.ACTION_UP, code, 0);
            } catch (Throwable t) {
                ok = false;
            }
            out.print(ok ? "1\n" : "0\n");
            out.flush();
        }
    }

    /**
     * SurfaceControl's display power mode: 0 is off, 2 is on. Android 10 to 13 name the display by
     * getInternalDisplayToken, older builds by getBuiltInDisplay(0). Android 14 moved both to a
     * class the shell's VM can't load, so there it answers 0.
     */
    private static boolean power(int mode) {
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            Object display;
            try {
                display = sc.getMethod("getInternalDisplayToken").invoke(null);
            } catch (NoSuchMethodException e) {
                display = sc.getMethod("getBuiltInDisplay", int.class).invoke(null, 0);
            }
            if (display == null) return false;
            sc.getMethod("setDisplayPowerMode", IBinder.class, int.class).invoke(null, display, mode);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final int EV_SYN = 0, EV_KEY = 1, EV_REL = 2;
    private static final int REL_X = 0, REL_Y = 1, BTN_LEFT = 0x110;
    /** input_event is 16 bytes with a 32-bit timeval and 24 with a 64-bit one. */
    private static final boolean WIDE = Build.SUPPORTED_64_BIT_ABIS.length > 0;
    private static FileOutputStream pointer;

    /** The remote's input device: the one whose own mouse mode the TV already treats as a pointer. */
    private static String pointerDevice() {
        for (int i = 0; i < 16; i++) {
            BufferedReader r = null;
            try {
                r = new BufferedReader(new FileReader("/sys/class/input/event" + i + "/device/name"));
                String name = r.readLine();
                if (name != null && name.contains("aml_keypad")) return "/dev/input/event" + i;
            } catch (Throwable t) {
                // Not there: try the next one.
            } finally {
                if (r != null) try { r.close(); } catch (Throwable t) { }
            }
        }
        return "/dev/input/event1";
    }

    private static void put(ByteBuffer b, int type, int code, int value) {
        if (WIDE) { b.putLong(0); b.putLong(0); } else { b.putInt(0); b.putInt(0); }
        b.putShort((short) type);
        b.putShort((short) code);
        b.putInt(value);
    }

    /**
     * Writes relative motion, and a left click when asked, into the remote's input device, the same
     * events its own mouse mode reports. The shell user is in the input group, which may write it.
     */
    private static synchronized boolean mouse(int dx, int dy, boolean click) {
        try {
            if (pointer == null) pointer = new FileOutputStream(pointerDevice());
            ByteBuffer b = ByteBuffer.allocate(8 * (WIDE ? 24 : 16)).order(ByteOrder.LITTLE_ENDIAN);
            if (dx != 0) put(b, EV_REL, REL_X, dx);
            if (dy != 0) put(b, EV_REL, REL_Y, dy);
            if (click) {
                put(b, EV_KEY, BTN_LEFT, 1);
                put(b, EV_SYN, 0, 0);
                put(b, EV_KEY, BTN_LEFT, 0);
            }
            put(b, EV_SYN, 0, 0);
            pointer.write(b.array(), 0, b.position());
            pointer.flush();
            return true;
        } catch (Throwable t) {
            pointer = null;
            return false;
        }
    }

    /** Null from getEvents means a character the keymap has no key for. */
    private static boolean type(Object manager, Method inject, KeyCharacterMap keymap, String text) {
        try {
            KeyEvent[] events = keymap.getEvents(text.toCharArray());
            if (events == null) return false;
            for (KeyEvent e : events) {
                KeyEvent now = KeyEvent.changeTimeRepeat(e, SystemClock.uptimeMillis(), 0);
                now.setSource(InputDevice.SOURCE_KEYBOARD);
                if (!(Boolean) inject.invoke(manager, now, WAIT_FOR_FINISH)) return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean send(Object manager, Method inject, long downTime, int action, int code, int meta) throws Exception {
        KeyEvent e = new KeyEvent(downTime, SystemClock.uptimeMillis(), action, code, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        return (Boolean) inject.invoke(manager, e, WAIT_FOR_FINISH);
    }
}
