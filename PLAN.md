# Bureaucracy Manager: Project Plan (v3)

Java concurrency lab project for a team of 4. This file holds:
- the scope and the cases we deliver;
- what is **done**, with the concurrency mechanism behind each part;
- a **real-life analogy** for every mechanism and every bug, to use at the oral exam;
- what is **left to implement** (deadlock and livelock), with a concrete starting guide.

How to run everything is in [README.md](README.md).

---

## 1. Scope

A simulation of a Romanian bureaucratic system:

- **Offices** issue **documents**. Each office has one **queue** and one or more **counters** (ghișee).
- A document can't be obtained directly: you first need other documents (for example, an ID card needs a birth certificate and proof of address).
- Counters close at random for **coffee breaks**.
- Some documents need the institution's **shared resources**: the official **stamp** (ștampila) and the **entry register** (registrul de intrări).
- **Clients** are threads.

No database: JSON config plus in-memory state. A database brings its own locking and transactions, and would hide the very concurrency problems we have to show.

### The three programs

| Program | Concurrency cases | Main class | Status |
|---|---|---|---|
| **Correct** | Queues, counters, clients, statistics, serial numbers, **C1 coffee breaks** | `correct.CorrectMain` | ✅ done |
| **Faulty #1: Data races** | R1, R2, R3, R6: one data-consistency scenario | `faulty.races.RacesMain` | ✅ done |
| **Faulty #2: Liveness & performance** | **P** performance (P1, P2) · **L2** lost wakeup · **D** deadlock · **L** livelock | `faulty.locks.LocksMain <perf\|lostwakeup\|deadlock\|livelock>` | ✅ P, L2, D and L done |

### Decisions taken

- **C2 (a correct shared stamp/register) is dropped.** The shared resources exist **only in the faulty version**, as the setting for the deadlock (D) and the livelock (L). At the oral exam, the *fix* of D and L explains what the correct version would do (section 5.3).
- **Dropped from v1** (not needed for the requirements): R4 (non-volatile flag), R5 (shared portfolio), D2 (nested monitors), S1 (starvation).

### State today (branch `fix_problems`)

- **50 tests pass** (`mvn test`, including the M4 livelock symptom test).
- All three programs run end to end.
- Correct version, 200 clients: 743 documents issued, all invariants hold. Stress run with 500 clients: 1840 documents issued, all invariants hold.
- `RacesMain --runs 5`: 5 / 5 runs corrupted, none hangs.
- `LocksMain perf`: P1 takes 4.9× the time; P2 burns 12× the CPU.
- `LocksMain lostwakeup`: 0 of 5 clients served with the bug, 5 of 5 without it.

---

## 2. How the simulation works

1. The main thread loads the JSON config and validates it. It rejects:
   - dependency cycles;
   - documents that no office issues;
   - unknown resources.
2. Everything shared is built **before any thread starts**: the offices, the document → office map, and each client's **plan**. The plan is every document the client needs, in a valid order (a topological sort of the dependency graph).
3. Counter threads start, then the coffee-break scheduler, then **one virtual thread per client**.
4. For each document in its plan, a client:
   - joins that office's queue with a `Request` carrying the documents it already holds;
   - waits;
   - receives the new document.
5. A counter:
   - takes the next request;
   - works for a random service time;
   - checks the presented documents;
   - issues the document with a **unique serial number**.
6. The main thread waits, with a timeout, until every client is done. It then shuts down in order (clients → coffee breaks → offices) and runs the **invariant checker**:
   - the number of documents issued equals the number needed;
   - every serial number is unique;
   - every client got exactly its plan;
   - all clients finished;
   - the statistics match the ledger;
   - every office always had an open counter.

The faulty versions use the **same checker**. That is how we *prove* they are broken.

---

## 3. Correct version: mechanisms and analogies

### 3.1 Infrastructure (done)

| Problem | Primitive | Where | Real-life analogy |
|---|---|---|---|
| Office queue, several counters per queue | `LinkedBlockingDeque` (producer–consumer), `take()` | `correct/Office`, `Counter` | The **ticket machine and display** at the post office. Citizens take a number, and whichever clerk is free calls the next one. Two clerks never call the same number, and an idle clerk waits instead of pacing around asking "anyone?". |
| Client waits for its document | `CompletableFuture<Document>` in `Request` (`complete()` → `get()`) | `Request`, `Client` | The **pickup slip**. You hand in your file, sit down, and get called when the document is ready. You never get a half-printed document, and you don't stand at the window pestering the clerk. |
| Unique serial numbers | `AtomicLong.getAndIncrement()` | `Counter` | A **numbering stamp that advances by itself**: one press gives a number and moves on. Two clerks can never get the same number, even if they press at the same instant. |
| Statistics updated by everyone | `ConcurrentHashMap` + `LongAdder`, `LongAccumulator(Math::max)` | `common/ConcurrentStatistics` | **Each clerk keeps tally marks on their own sheet**, and the manager adds up the sheets at the end of the day. Nobody fights over one shared notebook. |
| Ledger of issued documents | `ConcurrentLinkedQueue` (lock-free) | `common/IssuedLedger` | The **official archive tray**: every clerk drops a copy in, and nobody waits for anybody else to do it. |
| The client's own documents | **Thread confinement**: only the client's thread touches its list; the counter receives an immutable copy (`List.copyOf`) | `Client`, `Request` | The citizen **keeps their folder in their own hands** and gives the clerk **photocopies**. Nobody can flip through the original while someone else slides a page in. |
| Config, dependency graph, plans | Immutable objects, built before the threads start | `common/` | The **printed rulebook on the wall**. Everyone reads it at the same time and nobody can change it, so nobody has to guard it. |
| Thousands of waiting clients | Virtual threads (`newThreadPerTaskExecutor(Thread.ofVirtual()...)`) | `Simulation` | **Citizens in the waiting room.** Waiting costs almost nothing, so we can have thousands of them without hiring a security guard (an OS thread) for each one. |
| End of the simulation | `CountDownLatch` (with timeout) + poison pill + `join` | `Simulation`, `Office` | The **doorman counting the people still inside**. At zero they lock the front door. Each clerk then gets an "end of shift" note (the poison pill) and goes home, and the building closes when everyone is out. |
| Client always "checks out" | `countDown()` in `finally` | `Client` | Even a citizen who is refused, or leaves angry, **is ticked off at the door**. Otherwise the doorman would wait for them all night. |
| Thread-safe logging | One `println` per line, `DateTimeFormatter` | `common/Log` | Each clerk writes **a whole line in the logbook at once**, never word by word over someone else's line. |

### 3.2 C1: Coffee breaks, done correctly (done)

**How it works:**
- A `ScheduledExecutorService` ticks every 100 ms. At each tick, every open counter goes on break with probability `coffeeBreakChance`, and its return is scheduled `coffeeBreakMs` later.
- **The office never closes completely.** A counter may leave only if another one stays open, enforced with `AtomicInteger.compareAndSet` on `openCounters`.
- **The gate is checked before the next client is called.** A counter that is sent on break while waiting in `take()` puts the client it just took back at the **front** of the queue (`putFirst`), so an open counter serves them.
- **Waiting for the end of a break** uses `while (onBreak) wait()` on `pauseLock`, and `returnFromBreak()` changes the flag and calls `notifyAll()` under the **same** lock.
- **`stop()` calls everyone back** before the offices hand out their poison pills.
- **Tests:** `CoffeeBreakTest`.

| Mechanism | Real-life analogy |
|---|---|
| Gate checked before calling the next number | A clerk who wants a coffee **finishes the citizen at the window first**, then puts up the "Pauză" sign. They don't call number 57 and then walk off leaving that person standing there. |
| `putFirst` when sent on break while idle | The clerk had just called someone when the supervisor said "coffee, now". They **send the citizen to the front of the line** for the next window, not to the back. |
| `while (onBreak) wait()` + `notifyAll()` under the same lock | The clerk comes back and **announces it to the whole room**. Everyone who wakes up **checks the sign again** before getting up. The sign is only changed while holding the microphone, so nobody can miss the announcement between looking at the sign and falling asleep. |
| `compareAndSet` on `openCounters` | "At least one window stays open." To leave, a clerk takes the last free "on break" badge from the hook **in a single move**. If two clerks reach for it at once, only one gets it, and the other stays at the window. |
| Scheduler stopped before the offices | At closing time the supervisor **calls everyone back from the break room first**, then hands out the "end of shift" notes. |

---

## 4. Faulty #1: data races (done)

Run with `RacesMain --runs 20`. Every bug is marked in the code with `// BUG [ID]`. Small artificial pauses (`paperwork()`) widen the race windows, so the bugs show up on every run. The races exist without them too.

| ID | Bug | Symptom | Real-life analogy | Fix |
|---|---|---|---|---|
| **R1** | The queue is a plain `ArrayList`: `if (!isEmpty()) remove(0)` (check-then-act), and `add` from many clients at once | The same client served twice, clients lost, `IndexOutOfBounds` | Two clerks look up at the same moment, both see "one person waiting", and both shout "next!": the citizen is served twice, or the second clerk grabs at an empty chair. With no ticket machine, sometimes a name is erased from the paper list and that citizen waits forever. | `BlockingQueue.take()`: the check and the removal are one atomic step |
| **R2** | Statistics in a `HashMap` + `count++` (read-modify-write) | Totals smaller than the number of documents issued | Two clerks share **one tally sheet on the wall**. Both read "57", both write "58". Two citizens were served, one is counted. | `LongAdder`, `AtomicInteger`, `ConcurrentHashMap.computeIfAbsent(...).increment()` |
| **R3** | `nextSerial++` without synchronization | Duplicate serial numbers | Two clerks share **a paper notebook of serial numbers**: both read "last was 1041" and both write 1042. Two citizens hold IDs with the same number. | `AtomicLong.incrementAndGet()` |
| **R6** | Lazy singleton `OfficeRegistry` without synchronization | Some offices "not found", their clients fail | On opening day, **two managers each see there is no directory yet and each prints their own**. The one hung in the lobby is missing ANAF, so citizens are told ANAF doesn't exist. | Eager `static final`, holder idiom, `synchronized getInstance()`, or double-checked locking with `volatile` |

**Shutdown that always ends.** This is not a studied bug. R1 can lose the poison pills, so `Office.shutdown()` sends the pills, then sets a stop flag, then interrupts any counter that is left, each step with a timeout. The run always **ends and reports** the corruption instead of hanging.

---

## 5. Faulty #2: liveness and performance

Each bug can be switched on alone with `LockBug` (`BUSY_WAIT`, `BREAK_HOLDING_ARCHIVE`, `LOST_WAKEUP`), so every problem is shown and measured separately. `LockDemonstrationTest` measures each real symptom against a control run with the bug switched off.

### 5.1 P: Performance (done)

`LocksMain perf` runs the correct version, P1 and P2 on the same clients, and compares **wall-clock time** and **CPU time**.

| ID | Bug | Symptom | Real-life analogy | Fix |
|---|---|---|---|---|
| **P1** | One global `synchronized` lock around each client's whole visit (`GlobalLockSimulation`, built on the correct classes, so P1 is measured alone) | Correct results, but about **5× slower**. "Avg wait" looks small because it only starts counting once the client reaches a queue, not while it waits outside for the lock. | The building lets **only one citizen inside at a time**. Ten clerks sit idle while one works. Nothing goes wrong, it is just painfully slow. | Lock only the shared state that needs it, as narrowly as possible (per-office queues, atomic counters) |
| **P2a** `BUSY_WAIT` | An idle counter spins `while (queue.isEmpty())` instead of blocking | About **12× more CPU** for the same work | A clerk who **jumps up every second to look at the empty waiting room** instead of waiting for the bell. Exhausted, and nothing gets done. | Block: `take()` / `poll(timeout)` / `Condition.await()` |
| **P2b** `BREAK_HOLDING_ARCHIVE` | The whole coffee break is spent inside `synchronized (archiveLock)`. `wait()` releases only `pauseLock`, not the archive lock | The other counters of the office block on the archive until the break ends | A clerk who **takes the only archive key on their coffee break**. Nobody can file anything until they come back. | Never sleep or wait while holding a lock other people need |

### 5.2 L2: Lost wakeup (done)

**Bug:**
1. The counter sees "I'm on break" (checked **without** the lock).
2. It drinks its coffee.
3. Only then it calls `wait()`, **with no `while` loop and no timeout**.

If the supervisor calls it back during the coffee, the `notifyAll()` reaches nobody: nobody is waiting yet. The counter then waits for a signal that already came. The office counts it as **open**, but it **sleeps forever**.

**Demo:** `LocksMain lostwakeup` runs a scripted, step-by-step demo, first with the bug (0 of 5 clients served, counter `WAITING`), then without it (5 of 5).

**Why a scripted demo:** in the full simulation, the "back from break" signal of a *later* break wakes the sleeping counter again, so a later signal **hides** the lost one. That is exactly why lost wakeups are intermittent and hard to catch, and it is a good point to make at the oral exam.

**Fix:** check the condition and wait **under the same lock, in a loop**: `synchronized (lock) { while (onBreak) lock.wait(); }`.

| Real-life analogy |
|---|
| The clerk tells the supervisor "call me when the break is over", then **goes to the kitchen for a coffee**. The supervisor shouts "back to work!" **while the clerk is in the kitchen**: nobody hears it. The clerk comes back, sits in the break room and **waits for a call that already happened**. On the board the window is "open", in reality nobody is there, and the citizens wait in front of an empty chair. |
| The `while` fix: before dozing off in the break room, the clerk **looks at the board once more**. It already says "open", so they go straight back to the window. |

### 5.3 Shared resources: the setting for D and L

**Config (done):** deadlock uses [config/shared-resources.json](RomBureaucraticSystem/src/main/resources/config/shared-resources.json); the small, fixed-seed livelock walkthrough uses [config/livelock.json](RomBureaucraticSystem/src/main/resources/config/livelock.json).

```json
"resources": [
  { "name": "Registru", "permits": 1 },
  { "name": "Stampila", "permits": 1 }
],
"documents": [
  { "name": "AdeverintaDomiciliu", "requires": ["CertificatNastere"], "uses": ["Registru", "Stampila"] },
  { "name": "CertificatFiscal",    "requires": ["CertificatNastere"], "uses": ["Stampila", "Registru"] }
]
```

- `resources` lists the institution's shared items. `permits` is how many copies exist. Their **order in the list is the global acquisition order**.
- `uses` lists what a counter needs while issuing that document, **in the order the office's procedure says**. The Primărie (`AdeverintaDomiciliu`) and ANAF (`CertificatFiscal`) list them **in opposite orders on purpose**.
- In code: `Bureaucracy.resources()` and `DocumentType.uses()`.

**The correct way (the fix to explain at the oral exam, not built):**
1. Acquire the resources **in the global order**, whatever `uses` says.
2. Issue the document.
3. Release them in reverse order, in `finally`.

With a global order, no cycle "I hold X and wait for Y" can form, so a deadlock is impossible.

| Mechanism | Real-life analogy |
|---|---|
| `Semaphore(permits)` / a lock per resource | The institution has **one official stamp**. Whoever needs it takes it from the secretary's desk and brings it back. If it's gone, you wait at the desk. |
| Global acquisition order | An internal regulation: **"always take the register first, then the stamp"**. Since everybody follows it, two clerks can never end up each holding one item and waiting for the other's. |
| Release in `finally` | Whatever happens, even if the file is rejected, **the stamp goes back on the desk**. |

### 5.4 D: Deadlock (to do, M3)

**Bug:** counters acquire the resources **in the order written in `uses`**:
- the Primărie counter takes `Registru`, then waits for `Stampila`;
- the ANAF counter takes `Stampila`, then waits for `Registru`.

**Symptom:** the simulation freezes after a few seconds. A watchdog (`ThreadMXBean.findDeadlockedThreads()`) prints the two counters, the lock each one holds and the lock each one waits for.

**Fix:** global acquisition order (5.3), or `tryLock(timeout)` with release and retry.

| Real-life analogy |
|---|
| **The ANAF clerk holds the stamp and waits for the register. The Primărie clerk holds the register and waits for the stamp.** Both are patient and neither lets go. Every other clerk who needs either item joins the wait, and the citizens wait until closing time. |

### 5.5 L: Livelock (done, M4)

**Bug:** counters use `tryLock()` and are **over-polite**:
- if a counter gets the first resource but not the second, it releases the first and retries after the **same fixed delay** as everybody else;
- and if it sees someone else waiting for a resource it holds, it **hands it over** first.

**Demo:** `LocksMain livelock` runs a six-client, fixed-seed scenario with no coffee breaks. It prints each step for the first three retry rounds and periodic retry counts after that. Opposing resource counters use `tryLock()` in procedure order, rendezvous, politely release the held resource, then retry after the same fixed spin back-off. The progress monitor reports `LIVELOCK` after 2 seconds without an issued document while the resource counters remain `RUNNABLE` and `findDeadlockedThreads()` returns `null`.

**Fix:** random back-off (jitter), or simply the global acquisition order.

| Real-life analogy |
|---|
| Two very polite clerks at the stamp desk: "Please, you go first." "No, no, after you!" Each puts the stamp down for the other, then both reach for it at the same moment, then both put it down again… Like two people in a narrow corridor who keep stepping to the same side, they are moving all the time and the stamp never touches a single document. |

---

## 6. Implementation notes for M3 (deadlock) and M4 (livelock)

### What already exists and should be reused

| Piece | Where | Use it for |
|---|---|---|
| Resources in the config | `Bureaucracy.resources()`, `DocumentType.uses()`, `config/shared-resources.json` | Which resources exist, and which a document needs, in which order |
| `LocksSimulation(bureaucracy, Set<LockBug>)` | `faulty/locks/LocksSimulation` | A full run with the faulty-locks offices. It returns a `SimulationResult` and a report, with a timeout |
| `LockBug` | `faulty/locks/LockBug` | `DEADLOCK_ORDER` and `POLITE_LIVELOCK` select each demonstration separately |
| The place where a document is issued | `faulty/locks/Counter.serve(...)` | Acquire the resources of `request.wanted().uses()` around the "issue document" part |
| `LocksMain` | modes `deadlock` and `livelock` | `deadlock` runs `config/shared-resources.json`; `livelock` runs `config/livelock.json` |

### Suggested steps (in this order, to avoid conflicts)

1. **Together, first (small):**
   - add a `ResourcePool` interface in `faulty/locks`: `acquire(List<String> uses)` / `release(List<String> uses)`;
   - create **one** pool in `LocksSimulation`, shared by all offices (the stamp belongs to the institution, not to one office);
   - pass it through the `Office` → `Counter` constructors, and call it in `Counter.serve`.
   - A no-op pool keeps the existing modes and tests unchanged. **Agree on this before splitting up**, because both of you touch the same three files.
2. **M3: `DeadlockPool`.** One `ReentrantLock` per resource, `lock()` in `uses` order (BUG [D]). Add a watchdog daemon thread that calls `findDeadlockedThreads()` every second, prints the cycle (`ThreadInfo.getLockName()`, `getLockOwnerName()`) and ends the run. Then `LocksMain deadlock`, and a test: "a deadlock is detected within N seconds".
3. **M4: `PoliteLivelockPool` (done).** `tryLock()` on each resource in `uses` order. Opposing counters rendezvous, detect that another counter waits for a held resource, hand it over, then use the same fixed spin back-off (BUG [L]). The progress monitor, `LocksMain livelock`, and a focused symptom test are implemented.
4. **Both:** add a row to the README results table, mark the work package ✅ below, and be ready to explain the **fix** (5.3) at the oral exam.

---

## 7. Work packages

| # | Work package | Owner | Status |
|---|---|---|---|
| W1 | `correct/Client` + tests | M1 | ✅ |
| W2 | C1 coffee breaks: scheduler, gate before `take()`, never fully closed, invariant, tests | M1 | ✅ |
| W3 | Shared resources in config + validation + `shared-resources.json` | M1 | ✅ |
| ~~W4~~ | ~~Correct `ResourcePool`~~ (dropped, v2.1) | — | — |
| **W5** | **D deadlock** (section 6, step 2) | **M3** | ✅ done |
| **W6** | **L livelock** (section 6, step 3) | **M4** | ✅ done |
| W7a | Races shutdown never hangs | M1 | ✅ |
| W7b | P1 isolated on the correct classes; `perf` measures P1 and P2 separately | M1 | ✅ |
| W8 | L2 made real; bugs selectable with `LockBug`; real-symptom tests; `lostwakeup` demo | M1 | ✅ |
| W9 | `Client` / `CoffeeBreakScheduler` copies in both faulty packages | M1 | ✅ |
| W10 | End-to-end tests, 500-client stress run, README | M1 | ✅ |

### Who did what

| Member | Done |
|---|---|
| M1 (Madalina294) | Infrastructure (`common/`), `Simulation`/`CorrectMain`, `Client`, C1 coffee breaks, R2, R6, P1 + `Benchmark`, L2 rework, shared-resources config, races shutdown fix, faulty copies, configs, tests, README, plan |
| M2 (Bogdanc2004) | `Office`, `Counter`, R1, R3, P2, first version of L2, tests |
| M3 | W5 deadlock |
| M4 | W6 livelock |

---

## 8. Oral exam checklist

Everyone should be able to explain **every** row in sections 3, 4 and 5, analogy included. In particular:
- **Race condition vs data race:** R1 is check-then-act; R2 and R3 are read-modify-write.
- **Visibility and happens-before:** thread start; `CompletableFuture.complete` → `get`; `final` fields; `volatile`.
- **Why `wait()` always goes in a `while` loop** (L2), and why `notifyAll()` changes the flag under the same lock.
- **Deadlock vs livelock vs starvation,** and how each one is detected: `findDeadlockedThreads()` vs a progress monitor.
- **Why a lock that is too coarse is safe but slow** (P1), and why busy waiting burns CPU (P2).
- **Virtual threads vs platform threads:** why clients are virtual threads and counters are platform threads.
