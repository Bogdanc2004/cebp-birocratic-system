package ro.cebp.bureaucracy.faulty.locks;

/**
 * The bugs a faulty-locks {@link Office} can be built with. Each one can be switched on alone,
 * so every problem can be shown (and measured) separately.
 */
public enum LockBug {

    /** BUG [P2]: idle counters spin on {@code queue.isEmpty()} instead of blocking. 100% CPU. */
    BUSY_WAIT,

    /** BUG [P2]: a counter spends its whole coffee break holding the office's archive lock. */
    BREAK_HOLDING_ARCHIVE,

    /** BUG [L2]: the "back from break" signal can arrive before the counter waits for it, and is lost. */
    LOST_WAKEUP
}
