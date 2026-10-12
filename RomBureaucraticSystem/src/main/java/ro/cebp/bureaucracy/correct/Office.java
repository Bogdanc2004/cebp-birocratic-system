package ro.cebp.bureaucracy.correct;

import ro.cebp.bureaucracy.common.IssuedLedger;
import ro.cebp.bureaucracy.common.Statistics;
import ro.cebp.bureaucracy.common.model.OfficeConfig;
import ro.cebp.bureaucracy.common.model.SimulationConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

// Manages a single bureaucratic office, its shared request queue, and counter threads.
public final class Office {

    private final OfficeConfig config;
    private final BlockingQueue<Request> queue = new LinkedBlockingQueue<>();
    private final List<Counter> counters;
    private final List<Thread> threads;

    public Office(OfficeConfig config, SimulationConfig simulation, Statistics stats, IssuedLedger ledger) {
        this.config = config;
        List<Counter> counterList = new ArrayList<>();
        List<Thread> threadList = new ArrayList<>();
        for (int i = 1; i <= config.counters(); i++) {
            Counter counter = new Counter(i, config.name(), queue, simulation, stats, ledger);
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

    // Exposes counters for coffee break coordination.
    public List<Counter> counters() {
        return counters;
    }

    // Starts all counter worker threads.
    public void start() {
        for (Thread thread : threads) {
            thread.start();
        }
    }

    // Submits a client request into the office queue.
    public void submit(Request request) {
        try {
            queue.put(request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while submitting request to " + config.name(), e);
        }
    }

    // Sends poison pills to counters and waits for all threads to terminate.
    public void shutdown() throws InterruptedException {
        for (int i = 0; i < counters.size(); i++) {
            queue.put(new Request(0, null, List.of(), new CompletableFuture<>()));
        }
        for (Thread thread : threads) {
            thread.join();
        }
    }

    // Helper to identify poison pill requests.
    static boolean isPoisonPill(Request request) {
        return request.wanted() == null;
    }
}
