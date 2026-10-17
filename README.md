# Bureaucracy Manager

A software system able to guide and assist clients that try to progress through the mind-numbing procedures of a classic bureaucratic system. A configuration specifies the existence of an arbitrary number of offices and any number of documents which may be required by customers. 

No documents may be obtained directly; to receive it, the customer must obtain a number of intermediate documents.

The offices feature queues, and some offices have more than one counter for working with the public. At any time, a situation may occur where any of the counters be temporarily closed because of important coffee breaks. 

The application must simulate the whole system, based on a Configuration information containing at least the following elements:

- Offices and documents issued by them
- Dependencies between documents (which documents are needed to receive another document)

Customers will be simulated by threads.

---

## The three programs

| Program | What it shows | Main class |
|---|---|---|
| **Correct** | The full simulation, thread-safe: office queues, counters, coffee breaks, clients as virtual threads | `ro.cebp.bureaucracy.correct.CorrectMain` |
| **Faulty #1: Data races** | Lost updates, duplicate serial numbers, clients served twice or lost, an unsafe singleton (R1, R2, R3, R6) | `ro.cebp.bureaucracy.faulty.races.RacesMain` |
| **Faulty #2: Locks** | Performance (P1 global lock, P2 busy waiting / break holding a lock), lost wakeup (L2), deadlock (D), livelock (L) | `ro.cebp.bureaucracy.faulty.locks.LocksMain` |

The full plan, the team split and a real-life analogy for every mechanism and bug are in [PLAN.md](PLAN.md).

## How to run

Requires JDK 25. All commands run from `RomBureaucraticSystem/`. Maven is not on the PATH on every machine: IntelliJ's bundled Maven works, or run the main classes from the IDE.

```bash
mvn test                                                                   # all tests
mvn exec:java -Dexec.args="--quiet"                                        # correct version, config/default.json
mvn exec:java -Dexec.args="--config config/small.json"                     # correct version, small config, full log
mvn exec:java -Dexec.mainClass=ro.cebp.bureaucracy.faulty.races.RacesMain -Dexec.args="--runs 20"
mvn exec:java -Dexec.mainClass=ro.cebp.bureaucracy.faulty.locks.LocksMain -Dexec.args="perf"
mvn exec:java -Dexec.mainClass=ro.cebp.bureaucracy.faulty.locks.LocksMain -Dexec.args="lostwakeup"
mvn exec:java -Dexec.mainClass=ro.cebp.bureaucracy.faulty.locks.LocksMain -Dexec.args="deadlock"    # M3 deadlock demo
mvn exec:java -Dexec.mainClass=ro.cebp.bureaucracy.faulty.locks.LocksMain -Dexec.args="livelock"    # M4, not yet
```

Options: `--config <path>` (a file path or a classpath resource such as `config/small.json`), `--quiet` (report only), `--runs <n>` (RacesMain).

## Example results

From one machine with 16 cores; your numbers will differ.

| Command | Result |
|---|---|
| `CorrectMain` (default.json, 200 clients) | 743 documents issued, all invariants hold, `Status: OK` |
| `RacesMain --runs 5` | `Runs with broken invariants: 5 / 5` (160–280 violations per run) |
| `LocksMain perf` | P1: 4.9× the wall-clock time of the correct version. P2: 12× the CPU time |
| `LocksMain lostwakeup` | With the bug, counter 1 is "open" but `WAITING` forever: 0 of 5 clients served. Without it: 5 of 5 |
| `LocksMain deadlock` | `DEADLOCK detected` prints the locked/waiting resources and counter owners; the simulation times out and shuts down |

## Configuration

`src/main/resources/config/`:

| File | Used for |
|---|---|
| `default.json` | Correct version: 5 offices, 7 documents, 200 clients |
| `small.json` | Quick runs and tests: 20 clients, fixed seed |
| `races.json` | RacesMain: 100 clients, 10 s timeout |
| `benchmark.json` | `LocksMain perf`: 60 clients, fixed seed |
| `shared-resources.json` | Deadlock / livelock setting: two offices list `Stampila` and `Registru` in opposite orders |

Format: `offices` (name, counters, documents issued), `documents` (name, `requires`, optional `uses`), optional `resources` (name, `permits`), and `simulation` (clients, service and coffee-break times, break chance, timeout, seed). The loader rejects dependency cycles, documents without an office and unknown resources.
