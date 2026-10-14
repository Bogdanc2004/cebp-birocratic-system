# Bureaucracy Manager: Project Plan (v2)

Java concurrency lab project for a team of 4. This version replaces the first plan. It narrows the scope to the cases the lab actually asks for, records what already exists, and lists the remaining work as **work packages**, still unassigned. Every mechanism and bug comes with a real-life analogy for the oral exam.

---

## 1. Scope

A simulation of a Romanian bureaucratic system:

- **Offices** issue **documents**. Each office has one **queue** and one or more **counters** (ghișee).
- A document can't be obtained directly: you first need other documents.
- Counters close at random for **coffee breaks**.
- Some documents need the institution's **shared resources**: the official **stamp** (ștampila) and the **entry register** (registrul de intrări).
- **Clients** are threads.

No database: JSON config plus in-memory state. A database would hide the concurrency problems we have to show.

### The cases we must deliver

| Program | Concurrency cases | Entry point |
|---|---|---|
| **Correct** | **C1** coffee breaks (counter open/closed), on top of the already-correct queues, counters, statistics and serial numbers | `correct.CorrectMain` |
| **Faulty #1: Data races** | **F1**: one data-consistency scenario. We already have it, built from several bugs: R1, R2, R3, R6 | `faulty.races.RacesMain` |
| **Faulty #2: Liveness & performance** | **D** deadlock · **L** livelock · **P** performance (P1 + P2) · **L2** lost wakeup | `faulty.locks.LocksMain <deadlock\|livelock\|perf>` |

**Decisions (v2.1):**
- **C2 (a correct shared stamp/register) is dropped.** The shared resources exist **only in the faulty version**, as the setting for the deadlock (D) and the livelock (L). At the oral exam, the *fix* section of D and L explains what the correct version would do (global acquisition order).
- **Dropped from v1** (not needed for the requirements): **R4** (non-volatile flag), **R5** (shared portfolio), **D2** (nested monitors), **S1** (starvation).
- **L2** (lost wakeup) is kept and fixed so it really hangs (W8).

---

## 2. What exists today (`main`, all 30 tests pass)

### Infrastructure: done ✅

| Piece | Where | Primitive |
|---|---|---|
| Config loading + validation, dependency graph (topological sort, cycle detection) | `common/ConfigLoader`, `DependencyGraph` | immutable objects, built before threads start |
| Statistics | `common/ConcurrentStatistics` | `ConcurrentHashMap` + `LongAdder`, `LongAccumulator`, `AtomicInteger` |
| Ledger of issued documents + invariant checks + report | `common/IssuedLedger`, `InvariantChecker`, `Report` | `ConcurrentLinkedQueue` |
| Simulation lifecycle | `correct/Simulation` | virtual threads, `CountDownLatch` with timeout, ordered shutdown |
| Office queue + counters | `correct/Office`, `Counter` | `LinkedBlockingQueue` (producer–consumer), poison pill, `CompletableFuture` in `Request` |
| Unique serial numbers | `correct/Counter` | `AtomicLong` |

### Checklist of required cases

| Case | Status | Detail |
|---|---|---|
| **C1** Coffee breaks, counter open/closed | ✅ **done** (W2) | `CoffeeBreakScheduler` (scheduled ticks + scheduled reopen, `stop()` reopens all before shutdown); the gate is checked before `take()`, and a counter sent on break while idle hands its client back to the front of the queue (`LinkedBlockingDeque.putFirst`); "never fully closed" rule with `compareAndSet`; invariant "every office always had an open counter". Tests: `CoffeeBreakTest`. |
| **C2** Shared stamp / register | ➖ **dropped** (v2.1) | The resources are still built, but only in the faulty version, for D and L. |
| **F1** Data races | ✅ **done** | R1 (queue as an `ArrayList`, check-then-act), R2 (statistics lost updates), R3 (duplicate serial numbers), R6 (unsafe singleton). Tests prove each one. The shutdown hang is fixed (W7a): pills, then the stop flag, then interrupt, each with a timeout. |
| **D** Deadlock | ❌ **not started** | |
| **L** Livelock | ❌ **not started** | |
| **P** Performance | ✅ **done** (needs 1 fix) | P1 (global lock, `GlobalLockSimulation` + `Benchmark`) and P2 (busy waiting + sleeping on a coffee break while holding the archive lock, in `faulty/locks/Counter`). **Fix:** `GlobalLockSimulation` now uses the `faulty/locks` classes, so the benchmark measures P1 + P2 mixed together. P1 must use the `correct/` classes. |
| L2 Lost wakeup | ✅ **done** (W8) | The counter checks the break flag without the lock, has its coffee, then calls `wait()` with no loop and no timeout. A reopen signal sent during the coffee is lost, and the counter is "open" but asleep forever. The bugs are now selectable with `LockBug` (`BUSY_WAIT`, `BREAK_HOLDING_ARCHIVE`, `LOST_WAKEUP`), so P2 and L2 are shown separately. `LockDemonstrationTest` measures each real symptom against a control run. |

### Still a stub (blocks running any full simulation)

- `correct/Client`: **nothing can run end to end without it.**
- `correct/CoffeeBreakScheduler`.
- The `Client` / `CoffeeBreakScheduler` copies in `faulty/races` and `faulty/locks`.
- `LocksMain` modes `deadlock` and `livelock` (they print "not implemented").

---

## 3. Target design per case

### C1: Coffee breaks, done correctly

**What it does:** a `ScheduledExecutorService` ticks every ~100 ms. At each tick, each open counter goes on break with probability `coffeeBreakChance`, for `coffeeBreakMs` milliseconds, and is then reopened by a scheduled task.

**Rules:**
1. A counter checks the gate **before** `take()`, never between `take()` and serving. A client it has already called is always served first.
2. **An office never closes completely:** a counter may leave only if at least one other counter of that office stays open. Each office has an `AtomicInteger openCounters`, decremented with `compareAndSet` only while `> 1`. A plain `if (open > 1) open--` would be check-then-act: two counters could both leave at the same moment.
3. `stop()` cancels future breaks, reopens every counter, and shuts the scheduler down **before** the offices send their poison pills. Otherwise a counter on break would never see its pill.

**Primitives:** `ScheduledExecutorService`, `AtomicBoolean` + `wait`/`notifyAll` in a `while` loop (already written), `AtomicInteger.compareAndSet`.

**New invariant:** "an office never had 0 open counters". `openCounters` is sampled by the scheduler and the minimum seen is recorded.

| Mechanism | Real-life analogy |
|---|---|
| Gate checked before calling the next number | A clerk who wants a coffee **finishes the citizen at the window first**, then puts up the "Pauză" sign. They don't call number 57 and then walk off leaving that person standing there. |
| `while (onBreak) wait()` + `notifyAll()` | The citizens doze in the waiting room. When the clerk comes back, they announce "next please" **to the whole room**, and everyone who wakes up **checks the sign again** before getting up. |
| `compareAndSet` on `openCounters` | The rule "at least one window stays open". Before leaving, a clerk **takes the last free "on break" badge from the hook in a single move**. If two clerks reach for it at once, only one gets it, and the other stays at the window. |
| Scheduler stopped before the offices | At closing time the supervisor **calls everyone back from the break room first**, then hands out the "end of shift" notes. |

### Shared resources: stamp + register (setting for D and L only)

> C2 as a *correct* feature is dropped (v2.1). The config extension and the resource pool below are still needed, because the deadlock (D) and the livelock (L) happen around them. Only their faulty versions get built. The "correct" description stays here because it is the **fix** to explain at the oral exam.

**Config extension:**

```json
"resources": [
  { "name": "Registru", "permits": 1 },
  { "name": "Stampila", "permits": 1 }
],
"documents": [
  { "name": "CertificatFiscal", "requires": ["CarteIdentitate"], "uses": ["Stampila", "Registru"] },
  { "name": "CertificatUrbanism", "requires": ["ExtrasCarteFunciara"], "uses": ["Registru", "Stampila"] }
]
```

- `resources` is the institution's shared items. `permits` says how many copies exist: 1 means one stamp for everybody, 2 means two identical stamps.
- `uses` lists which resources a counter needs while issuing that document. Different documents, from **different offices**, list them in **different orders on purpose**, because the faulty version reuses the same config.

**What it does:** a `ResourcePool` holds one `Semaphore(permits, fair = true)` per resource. Before issuing a document that has `uses`, a counter:
1. acquires the resources in **global order** (their position in `resources`), **regardless of the order written in `uses`**;
2. issues the document;
3. releases them in reverse order, in a `finally` block.

The global order makes a deadlock impossible: no cycle of "I hold X and wait for Y" can form.

**New invariant:** each resource tracks how many users it has right now (an `AtomicInteger`) and the maximum ever seen, which must be ≤ `permits`. Check: "the stamp was never used by more people than there are stamps".

| Mechanism | Real-life analogy |
|---|---|
| `Semaphore(permits)` | The institution has **one official stamp** (or two). Whoever needs it takes it from the secretary's desk and brings it back. If it's gone, you wait at the desk. Nobody can stamp with a stamp that isn't there. |
| Fair semaphore | Clerks waiting for the stamp **take it in the order they arrived**, so no clerk is left without it all day. |
| Global acquisition order | An internal regulation: **"always take the register first, then the stamp"**. Since everybody follows it, two clerks can never end up each holding one item and waiting for the other's. |
| Release in `finally` | Whatever happens, even if the citizen's file is rejected, **the stamp goes back on the desk**. |

### F1: Data races (already built, one fix)

| ID | Bug | Symptom | Real-life analogy |
|---|---|---|---|
| R1 | The queue is a plain `ArrayList`: `if (!isEmpty()) remove(0)` | The same client served twice, clients lost, the "empty chair" exception | Two clerks look up at the same moment, both see "one person waiting", and both shout "next!": the citizen is served twice, or the second clerk grabs at an empty chair. With no ticket machine, sometimes a name is erased from the paper list and that citizen waits forever. |
| R2 | Statistics in a `HashMap` + `count++` | Totals smaller than the number of documents issued | Two clerks share one tally sheet on the wall. Both read "57", both write "58". Two citizens were served, one is counted. |
| R3 | `nextSerial++` without synchronization | Duplicate serial numbers | Two clerks share a paper notebook of serial numbers: both read "last was 1041" and both write 1042. Two citizens hold IDs with the same number. |
| R6 | Lazy singleton `OfficeRegistry` without synchronization | Some offices "not found", clients fail | On opening day, two managers each see there is no directory yet and each prints their own. The one hung in the lobby is missing ANAF, so citizens are told ANAF doesn't exist. |

**Fix:** `faulty/races/Office.shutdown()` must stop its counters with a `stop()` flag and `join(timeout)`, as `faulty/locks/Office` already does. The run must **end and report** the corruption, not hang.

### D: Deadlock (the shared stamp done wrong)

**Bug:** the faulty `ResourcePool` acquires resources **in the order written in `uses`**. With the config above, the ANAF counter takes `Stampila` then waits for `Registru`, while the Primărie counter takes `Registru` then waits for `Stampila`.
- Uses `ReentrantLock`s (`permits = 1`) instead of semaphores, because `ThreadMXBean.findDeadlockedThreads()` can only detect owned locks (monitors and `ReentrantLock`), not semaphores.
- A **watchdog** daemon thread checks every second. When it finds a deadlock, it prints each blocked thread, the lock it holds and the lock it waits for, then ends the run as `DEADLOCK`.

**Symptom:** the simulation freezes after a few seconds and the watchdog prints the cycle.

**Fix:** global acquisition order (C2), or `tryLock(timeout)` with release and retry.

| Real-life analogy |
|---|
| **The ANAF clerk holds the stamp and waits for the register. The Primărie clerk holds the register and waits for the stamp.** Both are patient and neither lets go. Every other clerk who needs either item joins the wait, and the citizens wait until closing time. |

### L: Livelock (the shared stamp, "over-polite" version)

**Bug:** every counter uses `tryLock()` and is **polite**:
- if it got the first resource but not the second, it releases the first and retries after a **fixed** delay (the same value for everyone);
- and if it sees another counter waiting (an `AtomicBoolean wanted` flag on each resource), it **gives its resource away** before trying again.

Two counters that need both resources keep handing them to each other. All threads are `RUNNABLE`, no lock is stuck and CPU is busy, yet no document gets issued. A **progress monitor** (the number of documents issued per second, from the statistics) detects "0 progress for N seconds while threads are active" and stops the run as `LIVELOCK`.

**Symptom:** threads keep running, high CPU, zero progress. Unlike the deadlock, `findDeadlockedThreads()` finds **nothing**, which is a good point to make at the oral exam.

**Fix:** random back-off (jitter), or simply a global acquisition order.

| Real-life analogy |
|---|
| Two very polite clerks at the stamp desk: "Please, you go first." "No, no, after you!" Each puts the stamp down for the other, then both reach for it at the same moment, then both put it down again… Both are moving all the time, and the stamp never touches a single document. |

### P: Performance (already built, one fix)

| ID | Bug | Symptom | Real-life analogy |
|---|---|---|---|
| P1 | One global `synchronized` lock around each client's whole visit (`GlobalLockSimulation`) | Clients are served one at a time; `Benchmark` shows it several times slower | The building lets only **one citizen inside at a time**. Ten clerks sit idle while one works. |
| P2 | Busy waiting on an empty queue + sleeping on a coffee break while holding the archive lock (`faulty/locks/Counter`) | 100% CPU, huge wait times | A clerk who **jumps up every second to look at the empty waiting room** instead of waiting for the bell, and another who **takes the only archive key on their coffee break**. |

**Fix:** `GlobalLockSimulation` must use the `correct/` classes, so P1 is measured on its own. `perf` mode then runs: correct vs P1, then correct vs P2.

---

## 4. Work packages

Effort: **S** = a few hours, **M** = about a day, **L** = 2 days or more.

| # | Work package | Owner | Depends on | Effort |
|---|---|---|---|---|
| **W1** | **`correct/Client`**: walk the plan; for each document, submit a `Request` with the documents held so far and wait on the future; one `recordWait` per document; `clientFinished()`; `countDown()` in `finally`; handle interruption on timeout. Plus a test with the real `Office`. **Unblocks every end-to-end run.** | *unassigned* | — | M |
| **W2** | **C1 coffee breaks:** `CoffeeBreakScheduler` (scheduled ticks, scheduled reopen, ordered `stop()`); move the gate check before `take()`; "never fully closed" rule with `AtomicInteger.compareAndSet`; test "an office never had 0 open counters". | **M1** | W1 to run, not to write | M |
| **W3** | **Shared resources config:** `resources` and `uses` in the model and JSON, validation in `ConfigLoader` (unknown resource, `permits < 1`), a config where two offices list `Stampila` / `Registru` in opposite orders. Needed by both W5 and W6, so M3 and M4 agree on it first. | *unassigned* | — | S |
| ~~W4~~ | ~~Correct `ResourcePool`~~: dropped (v2.1). | — | — | — |
| **W5** | **D deadlock:** faulty `ResourcePool` with `ReentrantLock` in `uses` order; watchdog with `ThreadMXBean`; `LocksMain deadlock`; a test that shows the deadlock is detected. | **M3** | W3 | M |
| **W6** | **L livelock:** polite `tryLock` with fixed back-off and handover; progress monitor; `LocksMain livelock`; a test that shows 0 progress while threads are `RUNNABLE`. | **M4** | W3 | M |
| **W7a** | **Data-races fix:** `faulty/races/Office.shutdown` with stop + `join(timeout)` + interrupt, so a run with corrupted queues ends and reports instead of hanging. | **M1** | — | S |
| **W7b** | **P1 isolation:** `GlobalLockSimulation` back on the `correct/` classes; `Benchmark` / `LocksMain perf` measure P1 and P2 separately. | *unassigned* (M1's code) | — | S |
| **W8** | **L2 fix:** the break flag is checked outside the lock and `wait()` has no timeout, so a "reopen" signal sent in between is lost and the counter sleeps forever. Bugs selectable per mode, so P2 and L2 can be shown separately. Replace the test that always passes with ones that measure the real symptoms. | **M1** | — | S |
| **W9** | **Faulty copies:** `Client` and `CoffeeBreakScheduler` in `faulty/races` and `faulty/locks` become copies of the correct ones, with no bugs of their own. | *unassigned* | W1, W2 | S |
| **W10** | **End-to-end verification + docs:** stress-test the correct version (10 runs × 500 clients, all invariants OK); `RacesMain --runs 20` shows failures and never hangs; README with run commands; update the analogies tables. | *unassigned* | everything | M |

**Critical path:** W1 → W9 → W10 for a runnable project. W3 → (W5, W6) for the deadlock/livelock story.

---

## 5. Who has done what so far

| Member | Done |
|---|---|
| M1 (Madalina294) | Infrastructure (`common/`), `Simulation`/`CorrectMain`, R2, R6, P1 + `Benchmark`, configs, tests |
| M2 (Bogdanc2004) | `Office`, `Counter`, R1, R3, P2, L2 (to be fixed), tests |
| M3 | — (next: W5 deadlock) |
| M4 | — (next: W6 livelock) |

---

## 6. How to run and verify

Maven is not on the PATH on every machine. IntelliJ's bundled Maven works, or run the main classes from the IDE.

```bash
mvn test
mvn exec:java                                                              # correct version
mvn exec:java -Dexec.mainClass=ro.cebp.bureaucracy.faulty.races.RacesMain -Dexec.args="--runs 20"
mvn exec:java -Dexec.mainClass=ro.cebp.bureaucracy.faulty.locks.LocksMain -Dexec.args="deadlock"
mvn exec:java -Dexec.mainClass=ro.cebp.bureaucracy.faulty.locks.LocksMain -Dexec.args="livelock"
mvn exec:java -Dexec.mainClass=ro.cebp.bureaucracy.faulty.locks.LocksMain -Dexec.args="perf"
```

| Command | Expected |
|---|---|
| `CorrectMain` | Finishes; all invariants hold: counts, unique serials, every office always had an open counter |
| `RacesMain --runs 20` | Most runs `FAILED` (lost counts, duplicate serials, double service, offices not found); never hangs |
| `LocksMain deadlock` | Freezes; the watchdog prints the 2 counters and the locks each holds and waits for |
| `LocksMain livelock` | Threads `RUNNABLE`, high CPU, 0 documents/second; the progress monitor reports a livelock; `findDeadlockedThreads()` finds nothing |
| `LocksMain perf` | Correct vs P1 and correct vs P2, each measured separately, both clearly slower |
