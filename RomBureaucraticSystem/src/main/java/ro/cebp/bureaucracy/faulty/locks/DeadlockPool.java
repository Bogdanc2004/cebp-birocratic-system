package ro.cebp.bureaucracy.faulty.locks;

import ro.cebp.bureaucracy.common.Log;
import ro.cebp.bureaucracy.common.model.ResourceConfig;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/** Deliberately acquires locks in each document's procedure order, creating BUG [D]. */
final class DeadlockPool implements ResourcePool, AutoCloseable {
    private final Map<String, ReentrantLock> locks = new HashMap<>();
    private final ConcurrentMap<Long, CopyOnWriteArrayList<String>> heldByThread = new ConcurrentHashMap<>();
    private final AtomicBoolean detected = new AtomicBoolean();
    private final CountDownLatch bothFirstResourcesHeld = new CountDownLatch(2);
    private volatile boolean watching = true;
    private final Thread watchdog;

    DeadlockPool(List<ResourceConfig> resources) {
        resources.forEach(resource -> locks.put(resource.name(), new ReentrantLock()));
        watchdog = Thread.ofPlatform().daemon().name("deadlock-watchdog").start(this::watch);
    }

    @Override public void acquire(List<String> uses) throws InterruptedException {
        if (uses.size() > 1) {
            lock(uses.getFirst()).lock();
            recordHeld(uses.getFirst());
            // Script the hold-and-wait overlap: both opposite procedures now hold their
            // first resource before either tries for its second one.
            bothFirstResourcesHeld.countDown();
            bothFirstResourcesHeld.await();
            for (int i = 1; i < uses.size(); i++) {
                lock(uses.get(i)).lock();
                recordHeld(uses.get(i));
            }
            return;
        }
        for (String name : uses) {
            lock(name).lock();
            recordHeld(name);
        }
    }

    @Override public void release(List<String> uses) {
        for (int i = uses.size() - 1; i >= 0; i--) {
            lock(uses.get(i)).unlock();
            CopyOnWriteArrayList<String> held = heldByThread.get(Thread.currentThread().threadId());
            if (held != null) {
                held.remove(uses.get(i));
                if (held.isEmpty()) heldByThread.remove(Thread.currentThread().threadId(), held);
            }
        }
    }

    boolean detected() { return detected.get(); }

    private ReentrantLock lock(String name) {
        ReentrantLock lock = locks.get(name);
        if (lock == null) throw new IllegalArgumentException("Unknown resource: " + name);
        return lock;
    }

    private void recordHeld(String resource) {
        heldByThread.computeIfAbsent(Thread.currentThread().threadId(), ignored -> new CopyOnWriteArrayList<>())
                .add(resource);
    }

    private List<String> heldResources(long threadId) {
        List<String> held = heldByThread.get(threadId);
        return held == null ? List.of() : List.copyOf(held);
    }

    private void watch() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        while (watching && !detected.get()) {
            long[] ids = bean.findDeadlockedThreads();
            if (ids != null) {
                detected.set(true);
                Log.info("DEADLOCK detected:");
                for (ThreadInfo info : bean.getThreadInfo(ids, true, true)) {
                    if (info != null) {
                        List<String> held = heldResources(info.getThreadId());
                        List<String> ownerHeld = heldResources(info.getLockOwnerId());
                        if (ownerHeld.size() == 1) {
                            Log.info("  %s holds %s and waits for %s (held by %s)",
                                    info.getThreadName(), held, ownerHeld.getFirst(), info.getLockOwnerName());
                        } else {
                            Log.info("  %s holds %s and waits for a lock held by %s (that counter holds %s)",
                                    info.getThreadName(), held, info.getLockOwnerName(), ownerHeld);
                        }
                    }
                }
                return;
            }
            try { Thread.sleep(1000); }
            catch (InterruptedException e) { return; }
        }
    }

    @Override public void close() {
        watching = false;
        watchdog.interrupt();
    }
}
