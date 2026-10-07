package ro.cebp.bureaucracy.common;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Minimal logger: {@code HH:mm:ss.SSS [thread] message}.
 *
 * <p>The whole line is built first and then written with a single {@code println}.
 * {@code PrintStream.println} is internally synchronized, so lines coming from
 * different threads never get mixed together.
 */
public final class Log {

    // DateTimeFormatter is immutable and thread-safe (unlike the old SimpleDateFormat).
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static volatile boolean enabled = true;

    private Log() {
    }

    public static void info(String format, Object... args) {
        if (!enabled) {
            return;
        }
        String thread = Thread.currentThread().getName();
        if (thread.isEmpty()) {
            // Virtual threads have no name unless one is given.
            thread = "vthread-" + Thread.currentThread().threadId();
        }
        System.out.println(LocalTime.now().format(TIME) + " [" + thread + "] " + String.format(format, args));
    }

    /** Turns event logging off, e.g. for benchmarks or repeated runs. */
    public static void setEnabled(boolean on) {
        enabled = on;
    }
}
