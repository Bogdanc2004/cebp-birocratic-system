package ro.cebp.bureaucracy.faulty.locks;

import ro.cebp.bureaucracy.common.model.ResourceConfig;
import ro.cebp.bureaucracy.common.Log;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Deliberately over-polite resource pool for BUG [L]. Opposing two-resource procedures
 * rendezvous at each retry, see that the other counter needs the resource they hold, and
 * release it together. The fixed back-off keeps their attempts synchronized.
 */
final class PoliteLivelockPool implements ResourcePool {
    private static final long FIXED_BACKOFF_NANOS = 1_000_000L;

    private final Map<String, ReentrantLock> locks;
    private final boolean traceEnabled;
    private final AtomicInteger arrivals = new AtomicInteger();
    private final AtomicInteger generation = new AtomicInteger();
    private final Map<Long, Thread> cyclingThreads = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, String> waitingFor = new ConcurrentHashMap<>();

    PoliteLivelockPool(List<ResourceConfig> resources) {
        this(resources, false);
    }

    PoliteLivelockPool(List<ResourceConfig> resources, boolean traceEnabled) {
        locks = resources.stream().collect(Collectors.toUnmodifiableMap(
                ResourceConfig::name, ignored -> new ReentrantLock()));
        this.traceEnabled = traceEnabled;
    }

    @Override
    public void acquire(List<String> uses) throws InterruptedException {
        if (uses.isEmpty()) return;
        if (uses.size() != 2) {
            acquireNormally(uses);
            return;
        }

        ReentrantLock first = lock(uses.getFirst());
        ReentrantLock second = lock(uses.getLast());
        Thread current = Thread.currentThread();
        cyclingThreads.put(current.threadId(), current);
        try {
            while (true) {
                checkInterrupted();
                long round = generation.get() / 3L + 1;
                if (!first.tryLock()) {
                    fixedBackoff();
                    continue;
                }
                trace(round, "STEP 1: holds %s; next it will try for %s", uses.getFirst(), uses.getLast());

                rendezvous(); // Both opposite procedures now hold their first resource.
                trace(round, "STEP 2: tries for %s while still holding %s", uses.getLast(), uses.getFirst());
                if (second.tryLock()) {
                    first.unlock();
                    // If this unexpectedly succeeds, don't manufacture a successful issue;
                    // yield it politely and retry in sync with the other counter.
                    second.unlock();
                    fixedBackoff();
                    continue;
                }

                // We are now waiting for the resource held by the other participant.
                // Rendezvous so both wait-for relationships are visible before either yields.
                waitingFor.put(current.threadId(), uses.getLast());
                rendezvous();
                trace(round, "STEP 3: %s is held by the other counter; both counters now know the conflict",
                        uses.getLast());
                boolean anotherCounterNeedsHeldResource = waitingFor.entrySet().stream()
                        .anyMatch(waiter -> waiter.getKey() != current.threadId()
                                && waiter.getValue().equals(uses.getFirst()));
                if (anotherCounterNeedsHeldResource) {
                    first.unlock();
                    trace(round, "STEP 4: sees the other counter needs %s; politely releases it", uses.getFirst());
                }
                rendezvous(); // Both releases happen before either retries.
                waitingFor.remove(current.threadId());
                trace(round, "STEP 5: waits the same fixed 1 ms, then retries from STEP 1");
                fixedBackoff();
            }
        } finally {
            if (first.isHeldByCurrentThread()) first.unlock();
            if (second.isHeldByCurrentThread()) second.unlock();
            waitingFor.remove(current.threadId());
            cyclingThreads.remove(current.threadId());
        }
    }

    private void acquireNormally(List<String> uses) throws InterruptedException {
        int acquired = 0;
        try {
            for (String name : uses) {
                ReentrantLock lock = lock(name);
                while (!lock.tryLock()) {
                    checkInterrupted();
                    fixedBackoff();
                }
                acquired++;
            }
        } catch (InterruptedException | RuntimeException e) {
            for (int i = acquired - 1; i >= 0; i--) lock(uses.get(i)).unlock();
            throw e;
        }
    }

    @Override
    public void release(List<String> uses) {
        for (int i = uses.size() - 1; i >= 0; i--) lock(uses.get(i)).unlock();
    }

    boolean hasRunnableCycle() {
        return cyclingThreads.size() >= 2
                && cyclingThreads.values().stream().allMatch(t -> t.getState() == Thread.State.RUNNABLE);
    }

    long retryCycles() {
        return generation.get() / 3L;
    }

    private void trace(long round, String message, Object... args) {
        if (traceEnabled && (round <= 3 || round % 500 == 0)) {
            Log.info("[L round %d] " + message, prepend(round, args));
        }
    }

    private static Object[] prepend(long round, Object[] args) {
        Object[] values = new Object[args.length + 1];
        values[0] = round;
        System.arraycopy(args, 0, values, 1, args.length);
        return values;
    }

    private ReentrantLock lock(String name) {
        ReentrantLock lock = locks.get(name);
        if (lock == null) throw new IllegalArgumentException("Unknown resource: " + name);
        return lock;
    }

    /** A wait-free two-party barrier: the deliberate livelock stays CPU-active. */
    private void rendezvous() throws InterruptedException {
        checkInterrupted();
        int observedGeneration = generation.get();
        if (arrivals.incrementAndGet() == 2) {
            arrivals.set(0);
            generation.incrementAndGet();
            return;
        }
        while (generation.get() == observedGeneration) {
            checkInterrupted();
            Thread.onSpinWait();
        }
    }

    private static void fixedBackoff() throws InterruptedException {
        long until = System.nanoTime() + FIXED_BACKOFF_NANOS;
        while (System.nanoTime() < until) {
            checkInterrupted();
            Thread.onSpinWait();
        }
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("livelock demo stopped");
    }
}
