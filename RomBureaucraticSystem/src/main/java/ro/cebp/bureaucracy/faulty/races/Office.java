package ro.cebp.bureaucracy.faulty.races;

import ro.cebp.bureaucracy.common.IssuedLedger;
import ro.cebp.bureaucracy.common.Statistics;
import ro.cebp.bureaucracy.common.model.OfficeConfig;
import ro.cebp.bureaucracy.common.model.SimulationConfig;
import ro.cebp.bureaucracy.correct.Request;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

// FAULTY Office holding a plain unsynchronized ArrayList queue (BUG R1).
public final class Office {

    private final OfficeConfig config;
    // BUG [R1]: Plain ArrayList queue without synchronization causes race conditions.
    private final List<Request> queue = new ArrayList<>();
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

    // Starts all counter threads.
    public void start() {
        for (Thread thread : threads) {
            thread.start();
        }
    }

    // BUG [R1]: Plain ArrayList add without synchronization can corrupt internal buffer or lose elements.
    public void submit(Request request) {
        queue.add(request);
    }

    // Shuts down counters by appending poison pills and joining threads.
    public void shutdown() throws InterruptedException {
        for (int i = 0; i < counters.size(); i++) {
            queue.add(new Request(0, null, List.of(), new CompletableFuture<>()));
        }
        for (Thread thread : threads) {
            thread.join();
        }
    }

    // Identifies poison pill requests.
    static boolean isPoisonPill(Request request) {
        return request.wanted() == null;
    }
}
