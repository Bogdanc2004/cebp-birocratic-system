# Bureaucracy Manager: Project Plan

Java concurrency lab project for a team of 4. This file holds the whole plan: what we build, who builds which part, and a real-life analogy for every concurrency mechanism and every bug. Use the analogies to explain the code at the oral exam.

---

## 1. What we build

A simulation of a Romanian bureaucratic system:

- **Offices** issue **documents**. Each office has one **queue** and one or more **counters** (ghișee).
- A document can't be obtained directly. You first need other documents (for example, an ID card needs a birth certificate and proof of address).
- Counters close at random for **coffee breaks**.
- **Clients** are threads. Each client wants one document and has to visit offices in the right order to collect everything it needs.

We deliver **3 programs**:

| Program | Purpose | Entry point |
|---|---|---|
| **Correct** | Full simulation, thread-safe | `ro.cebp.bureaucracy.correct.CorrectMain` |
| **Faulty #1: Races** | Data races and inconsistent data | `ro.cebp.bureaucracy.faulty.races.RacesMain` |
| **Faulty #2: Locks** | Deadlock, livelock, starvation, performance | `ro.cebp.bureaucracy.faulty.locks.LocksMain` |

### Why there is no database

We use **no PostgreSQL**, only JSON config files and in-memory state.

A database brings its own locking and transactions. It would hide the very concurrency bugs we are supposed to show, and the setup work earns no marks. Think of it this way: if every clerk handed their paperwork to a perfectly organized archivist, you would never see clerks stepping on each other's toes, and seeing that is the whole point of the lab.

- **Input:** `src/main/resources/config/*.json` (offices, counters, documents, dependencies, simulation parameters), read with Jackson.
- **Output:** a console log (`HH:mm:ss.SSS [thread] message`) plus a final report with statistics and **invariant checks**. The invariant checks are what prove the faulty versions are broken.

### Config format

```json
{
  "offices": [
    { "name": "Evidenta Populatiei", "counters": 2, "issues": ["CertificatNastere", "CarteIdentitate"] },
    { "name": "Primarie",            "counters": 3, "issues": ["AdeverintaDomiciliu"] }
  ],
  "documents": [
    { "name": "CertificatNastere",   "requires": [] },
    { "name": "AdeverintaDomiciliu", "requires": ["CertificatNastere"] },
    { "name": "CarteIdentitate",     "requires": ["CertificatNastere", "AdeverintaDomiciliu"] }
  ],
  "simulation": {
    "clients": 200,
    "serviceTimeMs": { "min": 20, "max": 80 },
    "coffeeBreakMs": { "min": 200, "max": 600 },
    "coffeeBreakChance": 0.1,
    "timeoutSeconds": 120,
    "seed": null
  }
}
```

Shipped configs: `default.json` (5 offices, 7 documents, 200 clients), `small.json` (quick runs) and `benchmark.json` (used by the performance comparison).

When a config is loaded we check that every document has exactly one office, that every required document exists, and that **there are no dependency cycles**. A cycle would mean that to get A you need B, and to get B you need A. A cycle is a configuration error, not a concurrency issue, so we reject it before any thread starts.

---

## 2. How the simulation works (same in all 3 programs)

1. The main thread loads the config and builds everything shared **before** starting any thread: offices, the document → office map, and each client's plan.
2. Each client gets a **plan**: the documents it needs in a valid order (topological sort of the dependency graph). Example: `CertificatNastere → AdeverintaDomiciliu → CarteIdentitate`.
3. Counter threads start, then the coffee-break scheduler, then one **virtual thread per client**.
4. For each document in its plan, the client:
   - joins that office's queue with a `Request` that carries the documents it already has;
   - waits;
   - receives the document.
5. A counter takes a request from the queue, works for a random service time, checks the presented documents, and issues the new document with a **unique serial number**.
6. The main thread waits (with a timeout) until every client is done. It then shuts down in order (clients → coffee breaks → offices) and runs the invariant checker.

**Invariants checked at the end:**
- The number of documents issued equals the number of documents the clients needed.
- Every serial number is unique.
- Every client received exactly the documents in its plan: none missing, none twice.
- All clients finished.
- The per-office statistics match the ledger of issued documents.

---

## 3. Team split

Each member owns **one vertical slice**: their part of the correct version **plus** the matching bugs in both faulty versions. Each bug is written by the person who best understands the correct code it breaks, and every member works on all 3 programs.

| Member | Correct version | Faulty #1 (races) | Faulty #2 (locks/perf) |
|---|---|---|---|
| **M1: Core & Config** | model, `ConfigLoader`, `DependencyGraph`, `Simulation`/`CorrectMain`, statistics, `IssuedLedger`, `InvariantChecker`, `Report` | R2, R6 | P1 + `Benchmark` |
| **M2: Queues & Counters** | `Office`, `Counter`, `Request`, `BlockingQueue`, `CompletableFuture`, poison pill | R1, R3 | L2, P2 |
| **M3: Coffee breaks** | `CoffeeBreakScheduler`, `ReentrantLock` + `Condition` open/closed gate | R4 | L1, S1 |
| **M4: Clients & shared resources** | `Client` (virtual threads), `Semaphore` / fixed lock order for shared resources, wait-time statistics | R5 | D1, D2 + deadlock watchdog |

### Status

| Member | Status |
|---|---|
| M1 | **Done.** Compiles, 25 tests pass. |
| M2 | To do. `Office` and `Request` exist as **stubs** (agreed signatures, `// TODO M2`). |
| M3 | To do. `CoffeeBreakScheduler` is a stub. |
| M4 | To do. `Client` is a stub. |

### Contracts between members (already in the stubs)

- `Office(OfficeConfig, SimulationConfig, Statistics, IssuedLedger)`, with `start()`, `submit(Request)`, `shutdown()` and `name()`.
  - When a counter issues a document, it must call `ledger.record(doc)`, `stats.recordIssued(officeName)` and `request.result().complete(doc)`.
- `Request(clientId, wanted, presented, CompletableFuture<Document> result)`.
- `CoffeeBreakScheduler(List<Office>, SimulationConfig)`, with `start()` and `stop()`. M2 and M3 agree on how a counter is closed and reopened.
- `Client(id, plan, Map<String, Office> officeByDocument, Statistics, CountDownLatch done)` implements `Runnable`.
  - It must call `stats.recordWait(ms)` **exactly once per document** and `stats.clientFinished()` at the end.
  - It must **always** call `done.countDown()` in a `finally` block.

---

## 4. Correct version: primitives and real-life analogies

| Problem | Primitive | Real-life analogy |
|---|---|---|
| Office queue, several counters per queue | `LinkedBlockingQueue<Request>` (producer–consumer) | The **ticket machine and display** at the post office. Citizens take a number (put), and whichever clerk is free calls the next number (take). The machine guarantees that two clerks never call the same number, and an idle clerk waits instead of pacing around asking "anyone?". |
| Client waits for its document | `CompletableFuture<Document>` in the `Request` | The **receipt with a pickup slip**. You hand in your file, get a slip, and sit down. When the clerk finishes, they call your slip. You don't stand at the window pestering them, and you can't pick up a half-printed document. |
| Coffee break, counter open/closed | `ReentrantLock` + `Condition` (`await` / `signalAll`), `volatile` state | The **"Pauză" sign** on the window. The clerk turns the sign and leaves. When they come back, they flip it and announce "next please" to everyone waiting. The citizens doze until the announcement instead of knocking every second. |
| Scheduling the breaks | `ScheduledExecutorService` | The **shift supervisor with a timetable** who tells clerks "you, coffee now, back in 10 minutes". |
| Unique ticket and serial numbers | `AtomicLong` | A **numbering stamp that advances by itself**: one press gives a number and moves on. Two clerks can never get the same number, even if they press at the same instant. |
| Statistics (documents per office, wait times) | `ConcurrentHashMap<String, LongAdder>`, `LongAccumulator(Math::max)` | **Each clerk keeps tally marks on their own sheet**, and the manager adds up all the sheets at the end of the day. Nobody fights over one shared notebook. |
| A limited resource shared by several offices (e.g. one official stamp) | `Semaphore` / locks always taken in a **fixed global order** | The **single official stamp** of the institution. A rule says "always take the register first, then the stamp", so two clerks never block each other holding one item each. |
| Many clients | `Executors.newVirtualThreadPerTaskExecutor()` | **Citizens sitting in the waiting room.** Waiting costs almost nothing, so we can have thousands of them without hiring a security guard (an OS thread) for each one. |
| End of the simulation | `CountDownLatch(clients)` + poison pill + `awaitTermination` | The **doorman with a counter of people still inside**. When it reaches zero, they lock the front door. Then each clerk gets an "end of shift" note (poison pill), finishes the current citizen, and goes home. The building is closed only after everyone has left. |
| Shared read-only data (dependency graph, plans, config) | Immutable objects, built before threads start | The **printed rulebook posted on the wall**. Everyone may read it at the same time and nobody can change it, so nobody has to guard it. |
| Thread-safe logging | One `println` per line, `DateTimeFormatter` | Each clerk **writes a whole line in the logbook at once**, instead of several clerks writing words into the same line at the same time. |

---

## 5. Faulty #1: data races and consistency (`faulty/races`)

Run with `RacesMain --runs 20`. The invariant checker prints `EXPECTED vs ACTUAL` and the number of runs with broken invariants. Every bug is marked in the code with `// BUG [ID]`.

| ID | Owner | Bug | Visible symptom | Real-life analogy | Fix |
|---|---|---|---|---|---|
| **R1** | M2 | Queue is a plain `ArrayList`: `if (!isEmpty()) remove(0)` (check-then-act) | The same client served twice, `IndexOutOfBoundsException`, or lost clients | Two clerks **look up at the same moment**, both see "one person waiting", and both shout "next!". Either they serve the same citizen twice, or the second clerk grabs at an empty chair. With no ticket machine, sometimes a citizen's name gets erased from the paper list and they wait forever. | `BlockingQueue.take()` (the check and the removal are one atomic step) |
| **R2** | M1 | Statistics in a `HashMap` + `count++` (read-modify-write) | Totals smaller than the real number of documents issued (lost updates) | Two clerks share **one tally sheet on the wall**. Both read "57", both go back to their desks, and both write "58". Two documents were issued, but the sheet shows only one more. At the end of the month the report says fewer people were served than actually came. | `LongAdder` / `AtomicInteger` / `ConcurrentHashMap.computeIfAbsent(...).increment()` |
| **R3** | M2 | `nextSerial++` without synchronization | Duplicate document serial numbers | Two clerks share a **paper notebook of serial numbers**. Both read "the last one was 1041" and both write 1042 on their documents. Two citizens now hold IDs with the same number, which is a disaster at the next police check. | `AtomicLong.incrementAndGet()` |
| **R4** | M3 | `boolean open` not `volatile`, read in a loop | A counter never "notices" its coffee break, or never comes back from it | The supervisor changes the **"Open/Pauză" sign on the back of the window**, but the clerk keeps looking at their **own cached copy** of the sign, a sticky note on their monitor. They keep serving during the break, or they stay on break forever because their sticky note still says "Pauză". | `volatile`, or read the flag under the lock |
| **R5** | M4 | Client's document portfolio is a shared `HashSet`, read while being written | `ConcurrentModificationException`, or a document that was issued is missing from the portfolio | The citizen's **document folder is being flipped through by the clerk** checking the papers while **another clerk slides in the new document**. Pages fall out, the checker loses count, or the new page lands in the wrong place and later "isn't there". | Give each request its own copy (`List.copyOf`) / a thread-confined portfolio / a concurrent collection |
| **R6** | M1 | Lazy singleton `OfficeRegistry` without synchronization (check-then-act on `instance == null`) | Two registries exist, some offices are "not found", and the clients needing them fail | On opening day, **two office managers each see there is no central directory yet**, and each prints **their own** directory. Some offices are listed only in the first one, and the one hung in the lobby is the second. Citizens look up "ANAF" in the lobby directory and are told it doesn't exist. | Eager `static final` instance, holder-class idiom, `synchronized getInstance()`, or double-checked locking with `volatile` |

---

## 6. Faulty #2: deadlock, livelock, starvation, performance (`faulty/locks`)

Run with `LocksMain <deadlock|livelock|starvation|perf>`, so each problem can be shown separately at the presentation.

| ID | Owner | Bug | Symptom | Real-life analogy | Fix |
|---|---|---|---|---|---|
| **D1** | M4 | Lock-order inversion: counter A locks `Stamp` → `Registry`, counter B locks `Registry` → `Stamp` | The simulation freezes. A watchdog with `ThreadMXBean.findDeadlockedThreads()` prints the cycle | **Clerk A holds the official stamp and waits for the register book. Clerk B holds the register book and waits for the stamp.** Both are polite and patient, neither lets go, and the whole office stops. The citizens wait until closing time. | Always acquire locks in the same global order, or use `tryLock` with a timeout |
| **D2** | M4 | Nested monitors: office A verifies with office B while B verifies with A (`synchronized` methods calling each other) | Freeze | The **Primăria** clerk calls **ANAF** to confirm a citizen's tax status and **keeps the line open**. Meanwhile ANAF calls the Primăria to confirm that citizen's address and also waits on the line. Each is waiting for the other to answer a phone they are both holding. | Never call another lock-holding object while holding your own lock (open calls), or use a fixed order |
| **L1** | M3 | Livelock: two parties use `tryLock` and "politely" back off and retry with **the same fixed delay** | High CPU, zero progress | Two very polite people in a **narrow corridor**: both step left, then both step right, then left again… They are moving all the time and never get past each other. Two clerks who each say "no, you take the stamp first" forever. | Random back-off (jitter), or a fixed acquisition order |
| **L2** | M2 | Lost wakeup: `wait()` without a `while` loop, or `notify()` instead of `notifyAll()` when a counter reopens | Clients stuck forever even though a counter is open | The clerk returns from break and **whispers "next" to just one person**, who has already left. Or someone was asleep and missed the announcement, then wakes up, doesn't re-check the sign, and goes back to sleep. The room stays full in front of an open window. | Always `wait()` in a `while (condition)` loop, use `notifyAll()` / `Condition.signalAll()`, or use a `BlockingQueue` |
| **P1** | M1 | One global `synchronized` lock around each client's whole visit | Everything runs one client at a time. `Benchmark` measures how much slower it is | The building lets **only one citizen inside at a time**. The others wait in the street, while inside 10 clerks sit idle and one serves. Nothing goes wrong, but it is painfully slow. | Narrow locks: per-office queue and atomic counters, as in the correct version |
| **P2** | M2 | Busy waiting `while (queue.isEmpty()) {}`, plus `Thread.sleep` inside `synchronized` (coffee break taken while holding the lock) | 100% CPU, huge wait times | A clerk who **jumps up every second to look at the empty waiting room** instead of waiting for the bell, exhausted and getting nothing done. Another clerk **takes the only key to the archive on their coffee break**: nobody can file anything until they come back. | `BlockingQueue.take()` / `Condition.await()`; never sleep or do slow work while holding a lock |
| **S1** | M3 | Starvation: unfair lock + "VIP" clients who always jump the queue | Normal clients never finish within the time limit | **"Pile" (connections)**: whoever knows someone goes straight to the window. As long as VIPs keep arriving, the ordinary citizen who came at 8:00 is still there at closing time. | Fair locks (`new ReentrantLock(true)`), FIFO queues, limiting priority |

---

## 7. Project structure

```
RomBureaucraticSystem/
  pom.xml                                  Java 25, Jackson, JUnit 5, exec-maven-plugin
  src/main/java/ro/cebp/bureaucracy/
    common/          shared by all 3 programs (M1)
      model/         config + domain records (immutable)
      ConfigLoader, DependencyGraph, Bureaucracy, ClientPlans
      Statistics, ConcurrentStatistics, StatisticsSnapshot, IssuedLedger
      InvariantChecker, Report, SimulationResult, Log, CommandLine
    correct/         Simulation, CorrectMain (M1); Office, Request (M2); CoffeeBreakScheduler (M3); Client (M4)
    faulty/races/    copy of correct/ with R1–R6 injected; RacesSimulation, RacesMain
    faulty/locks/    copy of correct/ with D/L/P/S bugs injected; GlobalLockSimulation, Benchmark, LocksMain
  src/main/resources/config/   default.json, small.json, benchmark.json
  src/test/                    JUnit tests + invalid configs
```

Each faulty package is a **copy of `correct/` with bugs injected**, so a diff against the correct version shows each bug and its fix. Until M2–M4 finish, `RacesSimulation` and `GlobalLockSimulation` still use the classes from `correct/` (marked `TODO`).

---

## 8. Workflow

1. **Interfaces first (done).** The stubs in `correct/` fix the contracts, so everyone can work in parallel.
2. **Correct version.** Each member implements their slice on a branch `feature/<member>-<slice>` and opens a PR into `main`. **Another member reviews each PR**, which forces everyone to understand code they didn't write.
3. **Stress test the correct version** with 500–1000 clients. The invariants must hold on every run.
4. **Faulty versions.** Copy `correct/` into `faulty/races` and `faulty/locks`. Each member injects their bugs, marked `// BUG [ID]`, with a comment on the cause and the fix.
5. **Oral prep.** Each member presents their slice to the others (about 30 min). Everyone should be able to explain **every** row in sections 4, 5 and 6, analogy included.

---

## 9. How to run and verify

Maven is not on the PATH on every machine. IntelliJ's bundled Maven works, or run the main classes from the IDE.

```bash
mvn test                                                                  # all unit tests
mvn exec:java                                                             # correct version, config/default.json
mvn exec:java -Dexec.args="--config config/small.json"
mvn exec:java -Dexec.mainClass=ro.cebp.bureaucracy.faulty.races.RacesMain -Dexec.args="--runs 20"
mvn exec:java -Dexec.mainClass=ro.cebp.bureaucracy.faulty.locks.LocksMain -Dexec.args="perf"
```

Expected results:

| Command | Expected |
|---|---|
| `mvn test` | `DependencyGraph`, `ConfigLoader`, `ConcurrentStatistics` and `InvariantChecker` tests pass; `RaceDemonstrationTest` shows that R2 and R6 really break |
| `CorrectMain` | Finishes; report shows `Invariants: all hold`, `Status: OK` |
| `RacesMain --runs 20` | Most runs `FAILED`: lost counts, duplicate serials, double service, offices not found |
| `LocksMain deadlock` | Freezes; the watchdog prints the deadlocked threads |
| `LocksMain livelock` | No progress, high CPU |
| `LocksMain starvation` | Normal clients don't finish within the time limit |
| `LocksMain perf` | The global-lock version is several times slower than the correct one |

Optional at the oral exam: inspect a frozen run with `jstack <pid>` or VisualVM to show the threads and the locks they hold.
