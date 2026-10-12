package ro.cebp.bureaucracy.faulty.locks;

import ro.cebp.bureaucracy.common.IssuedLedger;
import ro.cebp.bureaucracy.common.Statistics;
import ro.cebp.bureaucracy.common.model.OfficeConfig;
import ro.cebp.bureaucracy.common.model.SimulationConfig;
import ro.cebp.bureaucracy.correct.Request;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

// FAULTY Office using non-blocking queue and shared archive lock (BUG P2).
public final class Office {

    private final OfficeConfig config;
    // Non-blocking queue forcing counters into busy-waiting loops.
    private final Queue<Request> queue = new ConcurrentLinkedQueue<>();
    // Shared archive lock contended by counters and held during breaks.
    private final Object archiveLock = new Object();
    private final List<Counter> counters;
    private final List<Thread> threads;

    public Office(OfficeConfig config, SimulationConfig simulation, Statistics stats, IssuedLedger ledger) {
        this.config = config;
        List<Counter> counterList = new ArrayList<>();
        List<Thread> threadList = new ArrayList<>();
        for (int i = 1; i <= config.counters(); i++) {
            Counter counter = new Counter(i, config.name(), queue, archiveLock, simulation, stats, ledger);
            counterList.add(counter);
            Thread thread = new Thread(counter, config.name() + "-counter-" + i);
            threadList.add(thread);
        }
        this.counters = List.copyOf(counterList);
        this.threads = List.copyOf(threadList);
    }

    public String name() {
        return config.name();
    }

    public List<Counter> counters() {
        return counters;
    }

    public void start() {
        for (Thread thread : threads) {
            thread.start();
        }
    }

    public void submit(Request request) {
        queue.add(request);
    }

    public void shutdown() throws InterruptedException {
        for (int i = 0; i < counters.size(); i++) {
            queue.add(new Request(0, null, List.of(), new CompletableFuture<>()));
        }
        for (Counter counter : counters) {
            counter.stop();
        }
        for (Thread thread : threads) {
            thread.join(1000);
        }
    }

    static boolean isPoisonPill(Request request) {
        return request.wanted() == null;
    }
}
