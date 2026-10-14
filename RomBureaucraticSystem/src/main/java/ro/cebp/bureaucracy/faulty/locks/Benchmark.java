package ro.cebp.bureaucracy.faulty.locks;

import ro.cebp.bureaucracy.common.Bureaucracy;
import ro.cebp.bureaucracy.common.CommandLine;
import ro.cebp.bureaucracy.common.ConfigLoader;
import ro.cebp.bureaucracy.common.Log;
import ro.cebp.bureaucracy.common.SimulationResult;
import ro.cebp.bureaucracy.correct.Simulation;

import java.lang.management.ManagementFactory;
import java.util.EnumSet;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Runs the correct version and each performance bug on the same configuration, one at a time,
 * and compares wall-clock time and CPU time. With a fixed seed, every run gets the same clients.
 * <ul>
 *   <li>P1 (global lock) shows up in <b>wall-clock time</b>: clients are served one by one.</li>
 *   <li>P2 (busy waiting, break holding the archive lock) shows up mainly in <b>CPU time</b>:
 *       idle counters keep a core busy doing nothing useful.</li>
 * </ul>
 * Usage: {@code Benchmark [--config <path>]} (default {@code config/benchmark.json})
 */
public final class Benchmark {

    static final String DEFAULT_CONFIG = "config/benchmark.json";

    private Benchmark() {
    }

    public static void main(String[] args) throws InterruptedException {
        CommandLine cli = CommandLine.parse(args);
        run(cli.config().equals(CommandLine.DEFAULT_CONFIG) ? DEFAULT_CONFIG : cli.config());
    }

    static void run(String config) throws InterruptedException {
        Log.setEnabled(false);
        Bureaucracy bureaucracy = ConfigLoader.load(config);
        System.out.printf("Benchmark: %d clients, config %s, %d CPU cores%n%n",
                bureaucracy.simulation().clients(), config, Runtime.getRuntime().availableProcessors());
        System.out.printf("  %-34s %9s %10s %12s   %s%n", "Version", "wall", "CPU", "avg wait", "result");

        Measurement correct = measure("Correct", () -> new Simulation(bureaucracy).run());
        Measurement p1 = measure("P1  global lock", () -> new GlobalLockSimulation(bureaucracy).run());
        Measurement p2 = measure("P2  busy wait + archive on break", () -> new LocksSimulation(bureaucracy,
                EnumSet.of(LockBug.BUSY_WAIT, LockBug.BREAK_HOLDING_ARCHIVE)).run());

        System.out.printf("%nP1 takes %.1fx the wall-clock time of the correct version%n",
                ratio(p1.wallMs, correct.wallMs));
        System.out.printf("P2 burns %.1fx the CPU time of the correct version%n",
                ratio(p2.cpuMs, correct.cpuMs));
    }

    private record Measurement(long wallMs, long cpuMs) {
    }

    private static Measurement measure(String name, Callable<SimulationResult> run) throws InterruptedException {
        long cpuBefore = processCpuMs();
        SimulationResult result;
        try {
            result = run.call();
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(name + " failed", e);
        }
        long cpu = processCpuMs() - cpuBefore;
        long wall = result.elapsed().toMillis();
        System.out.printf("  %-34s %7d ms %7d ms %9.1f ms   %s%n", name, wall, cpu,
                result.statistics().averageWaitMs(), result.ok() ? "OK" : "FAILED (timeout or violations)");
        return new Measurement(wall, cpu);
    }

    /** CPU time used by the whole JVM process so far, or 0 if the platform does not report it. */
    private static long processCpuMs() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            return TimeUnit.NANOSECONDS.toMillis(Math.max(0, os.getProcessCpuTime()));
        }
        return 0;
    }

    private static double ratio(long value, long base) {
        return (double) value / Math.max(1, base);
    }
}
