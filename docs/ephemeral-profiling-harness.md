# Ephemeral Profiling Harness — Implementation Specification

A stateless, agent-driven profiling endpoint for Kotlin playground code. One contract,
two interchangeable measurement backends, throwaway state, and results small enough for
a language model to diff.

| | |
|---|---|
| **Repository** | `deusaquilus/kotlin-compiler-server` |
| **Branch** | `claude/exoquery-kotlin-compiler-btw1dy` |
| **Kotlin** | 2.1.20 |
| **Target JDK** | 17 |
| **Status** | Design — all mechanisms empirically verified against the real sandbox |

### Legend

Claims in this document are tagged so an implementer knows what is fact and what is judgment:

- **[MEASURED]** — verified by direct experiment against this project's sandbox constraints.
- **[DECISION]** — design judgment, not measured. Push back freely.
- **[TRAP]** — a failure mode that will cost you a day if you don't know about it.

---

## 1. What this is, and why it exists

The consumer of this endpoint is **an AI agent, not a human**. The agent writes a variant of
a Kotlin snippet, fires it at the server, receives a small structured result, and discards
everything — then repeats that loop dozens or hundreds of times to answer questions like
*"which of these three ExoQuery formulations is actually cheaper?"*

That consumer shapes every decision in this document:

- **Determinism beats fidelity.** An agent comparing variant A to variant B will read
  run-to-run sampling noise as signal. Reproducible numbers matter more than
  production-realistic ones.
- **Output size is a hard constraint.** Every byte returned costs the agent context. A
  collapsed-stack dump is actively harmful; a top-N table with stable keys is what is
  needed. The agent's primary operation is *subtracting two results*.
- **Throughput is the binding constraint.** Two hundred experiments at 3 s each is ten
  minutes; at 0.5 s it is ninety seconds. That difference decides whether the loop is
  usable at all.
- **Self-correction needs machine-readable diagnostics.** An agent will write bodies that
  get dead-code-eliminated. A `"warning": "body_optimized_away"` field is worth as much as
  the timing.

### The ephemerality is already free

The existing architecture is already stateless. `usingTempDirectory` creates a UUID-named
directory and `deleteRecursively`s it in a `finally`; every run compiles into a fresh temp
dir and executes in a fresh child JVM with a freshly written policy file. There is no
persistent state to discard because there is none. **This is why this repository is the
right host for the idea** rather than a long-lived benchmark service.

---

## 2. Repository context

This is a fork of the JetBrains *kotlin-compiler-server* (the Kotlin Playground backend) at
Kotlin 2.1.20, with the ExoQuery compiler plugin wired in.

### Request flow for a JVM run

```
CompilerRestController  (POST /api/compiler/run)
  -> KotlinProjectExecutor.run
     -> KotlinCompiler.run
        -> KotlinCompiler.execute
           -> compile()                  (K2JVMCompiler CLI, in-process)
           -> JavaExecutor.execute()     (separate JVM, sandboxed)
```

`KotlinCompiler.compile()` does **not** use the in-memory `KotlinCoreEnvironment` for
codegen. It writes the `KtFile`s to a temp directory and invokes the real `K2JVMCompiler`
CLI entry point (`doMainNoExit`), passing `-cp` with every jar in the lib folder,
`-no-stdlib -no-reflect -progressive`, and one `-Xplugin=` argument per jar in the
compiler-plugins folder. That last mechanism is how the ExoQuery plugin is applied.

The compiled classes are then written to a second temp directory and executed in a
**separate JVM** spawned by `JavaExecutor`: `-Xmx32M`, `-Djava.security.manager` with a
generated `executor.policy`, a 10-second timeout, and a 100 KB output cap. The child's
entry point is `executors.JavaRunnerExecutor`, which reflectively invokes the user's `main`
and prints a single JSON blob to the real stdout.

### Files you will touch

| Location | What it is |
|---|---|
| `KotlinCompiler.kt:55` | `addByteCode()` — the precedent for parent-side result enrichment. **Copy this pattern.** |
| `KotlinCompiler.kt:92` | `compile()` — builds the `K2JVMCompiler` argument list |
| `KotlinCompiler.kt:129` | `findMainClasses()` — existing ASM scan for `main([Ljava/lang/String;)V`. Extend this to discover the contract. |
| `KotlinCompiler.kt:139` | `execute()` — orchestration; the enrichment hook sits at line 150 |
| `KotlinCompiler.kt:190` | `memoryLimit = 32`, hardcoded. Must be raised for profiled runs. |
| `JavaExecutor.kt:19` | `MAX_OUTPUT_SIZE = 100 * 1024` |
| `JavaExecutor.kt:20` | `EXECUTION_TIMEOUT = 10000L` — a `const val`; must become a parameter |
| `JavaExecutor.kt:57` | Exceeding the output cap **discards all output** |
| `JavaExecutor.kt:73` | `destroy()` — the race that constrains dump ordering |
| `JavaExecutor.kt:113` | `CommandLineArgument.toList()` — the single place child-JVM flags are built |
| `JavaRunnerExecutor.kt:27` | `mainMethod.invoke` — the invocation point the harness replaces |
| `JavaRunnerExecutor.kt:47` | `defaultOutputStream.print(...)` — the JSON channel |
| `JavaRunnerExecutor.kt:56` | `RunOutput` — the child→parent DTO. Jackson on both sides. |
| `executor.policy:22` | The default `grant {}` block |
| `ExecutionResult.kt` | `JvmExecutionResult`, which already carries an optional `jvmByteCode` payload |
| `ProgramOutput.kt` | `asExecutionResult()` — deserializes child stdout |
| `CompilerRestController.kt` | `/run` with its `addByteCode` query param — the precedent for a `profile` param |
| `src/test/resources/test-compile-data/jvm` | **580 Kotlin snippets** driven by `ResourceCompileTest`. Your regression suite. |

---

## 3. The contract

The submitted snippet exposes two zero-argument static entry points instead of a `main`.
This single shape serves both backends without modification — that is the central claim of
this design, and §5 is the evidence for it.

```kotlin
// Submitted by the agent. Compiled by the existing pipeline, no changes.
object Profile {
  // Runs ONCE. Excluded from all measurement.
  // Class initialization, <clinit>, cache warming, data construction.
  @JvmStatic fun setup() { ... }

  // The measured unit of work. Must be safe to call repeatedly.
  // Must consume its result via Blackhole to survive dead-code elimination.
  @JvmStatic fun body() { ... }
}
```

### Execution timeline

```
        |<-- excluded -->|<- timed ->|<--- excluded --->|<===== MEASUREMENT WINDOW =====>|
        +----------------+-----------+------------------+--------------------------------+
        |    setup()     | body() #1 |   body() x W     |          body() x N            |
        |     once       |   alone   |     warmup       |   sampled OR instrumented      |
        +----------------+-----------+------------------+--------------------------------+
                |              |                                                    |
                v              v                                                    v
            setupNs      firstCallNs                                   dump() -> parent folds

Three timings are reported separately, so one-time cost is never averaged into invisibility.
```

The window opens only after `setup()` and warmup. This is what keeps initialization cost out
of the profile while still reporting it as its own number.

### Normative terms

All six are required. Terms 1–3 make profiling *correct*; terms 4–6 make the two backends
*interchangeable*.

1. **`setup()` / `body()` split.** Initialization is excluded from the window by construction.
2. **Warmup iterations** (`W`) run before the window opens.
3. **`N` iterations** run inside the window.
4. **Pinned JIT tier: `-XX:TieredStopAtLevel=1`.** **[MEASURED]** This is the load-bearing
   term. See §5.1.
5. **A blackhole sink** so bodies cannot be dead-code-eliminated.
6. **A fixed harness-frame filter** — `Harness.*`, `java.lang.reflect.Method.invoke`,
   `jdk.internal.reflect.*`, `jdk.jfr.*` — applied identically by both backends.

### Discovery

`findMainClasses()` at `KotlinCompiler.kt:129` already walks every emitted class with an ASM
`ClassVisitor` looking for `main([Ljava/lang/String;)V`. Extend that same visitor to also
record classes exposing `setup()V` and `body()V`. **[DECISION]** Prefer this over
annotations: it needs no new runtime type on the classpath and reuses a code path that
already exists.

---

## 4. The two backends

### Backend I — instrumentation (default)

A `java.lang.instrument` agent installed via `-javaagent:` registers a `ClassFileTransformer`
that rewrites classes with ASM **as they load**.

> **[TRAP] Do not post-process the compiler output map.**
> The obvious implementation — transforming the `Map<String, ByteArray>` that
> `KotlinCompiler.compile()` already produces — sees **only the user's own classes**, because
> `-no-stdlib -no-reflect` with `-d outputDir` means nothing else lands there. That yields
> invocation counts for methods the agent already wrote and knows about. It is useless for
> the actual question, which is what happens *underneath* those methods.

**[MEASURED]** A class loaded from a jar on `-classpath` is offered to the transformer as a
non-bootstrap class. This is decisive for Kotlin: `kotlin-stdlib`, `exoquery-engine`,
`kotlinx-serialization`, `decomat` and `terpal-runtime` are all ordinary classpath jars
loaded by the application classloader, so **they are all fully instrumentable**. In Java the
"library" is the JDK and therefore bootstrap; in Kotlin it is not.

Class loading for a trivial program, with a transformer installed **[MEASURED]**:

| Quantity | Count | Consequence |
|---|---:|---|
| Classes the JVM loads | 551 | — |
| Offered to the transformer | 119 | Transformable |
| Loaded before `premain` | ~432 (78%) | JDK core; needs `retransformClasses`, and even then no schema changes |

#### Instrument the call site, not the callee

You cannot practically transform `java.lang.String` or `java.util.HashMap`. **You do not need
to.** When you rewrite a method you can see every `INVOKEVIRTUAL java/lang/StringBuilder.append`
in its bytecode and count it *there*. Key the counter on
`(caller, callee owner + name + descriptor)` and you get exact counts of low-level JDK calls
made as a result of instrumented code — without ever touching the JDK. Because kotlin-stdlib
and exoquery-engine are themselves instrumentable, their call sites are counted too, giving
the full transitive picture down to the JDK boundary.

#### Two insertion modes, very different cost

- **Counting** — `LDC id; INVOKESTATIC Probe.hit(I)V` at method entry and at each counted
  call site. Adds no branches, so existing `StackMapTable` entries stay valid;
  `COMPUTE_MAXS` suffices.
- **Timing** — entry plus exit requires a try/finally, which adds an exception handler, which
  requires new stack map frames, which requires `COMPUTE_FRAMES` and therefore a
  `ClassWriter.getCommonSuperClass` implementation backed by a `URLClassLoader` over
  `kotlinEnvironment.classpath`. This is a step change in complexity and in failure modes —
  get it wrong and you emit bytecode that fails verification, surfacing to the user as an
  inexplicable `VerifyError` on code that compiled cleanly.

### Backend S — sampling

A programmatic `jdk.jfr.Recording` started and stopped inside the child JVM, around the
`body() × N` loop only.

> **[TRAP] Do not use `-XX:StartFlightRecording`.**
> Two independent failures. First, it writes
> `[0.569s][info][jfr,startup] Started recording 1...` to **stdout**, which corrupts the JSON
> that `ProgramOutput.asExecutionResult()` parses, turning every run into an exception
> descriptor. Second, it records JVM startup, so **[MEASURED]** most samples land in
> `sun.security.provider.PolicyFile.*` and JFR's own initialization rather than user code.
> The programmatic window fixes both. With `-Xlog:jfr*=off` as well, stdout is clean — verified.

**[MEASURED]** With the window opened after setup and warmup: **396 samples, 377 (95%) in
`body()`, zero leaked from `setup()`**. The remaining ~19 were in JFR's own `Recording.start`,
removed by the harness-frame filter.

#### Ordering constraint

`r.dump(path)` must complete **synchronously before** `defaultOutputStream.print(...)` at
`JavaRunnerExecutor.kt:47`. `JavaExecutor` stops reading as soon as both stream futures
complete and then calls `destroy()` at line 73; a `dumponexit=true` shutdown hook races that
and loses the recording.

---

## 5. Why this works — the evidence

Every table in this section was produced by running both backends against an identical
`setup()`/`body()`×N contract over a target with a known cost distribution: four methods
(`heavy`, `medium`, `light`, `tiny`) whose isolated, unprobed costs were measured first to
establish ground truth.

Ground truth — each method timed alone, unprobed, fully JIT-compiled **[MEASURED]**:

| Method | ns / call | Share of total |
|---|---:|---:|
| heavy | 1164.6 | 69.7% |
| medium | 351.9 | 21.1% |
| light | 130.1 | 7.8% |
| tiny | 24.4 | 1.5% |

### 5.1 The pinned JIT tier is load-bearing

Under default tiered compilation the two backends **do not agree**. They agree within 10% on
methods above ~350 ns, and diverge by 3–4× below ~130 ns.

Default JIT (C2) — backends diverge on cheap methods **[MEASURED]**:

| Method | Truth | Backend I | Backend S † | Verdict |
|---|---:|---:|---:|---|
| heavy | 69.7% | 68.9% | 74.3% | agree |
| medium | 21.1% | 21.2% | 23.3% | agree |
| **light** | **7.8%** | **8.2%** | **1.9%** | **4× disagreement** |
| **tiny** | **1.5%** | **1.7%** | **0.5%** | **3× disagreement** |

† renormalized over the four methods; a further 7.1% of samples landed in the calling loop —
those are inlined frames.

**The cause is inlining.** Instrumentation prevents the JIT from inlining a probed method;
sampling does not. Under C2, `light` and `tiny` get inlined into their caller, while under
instrumentation they cannot be. *The two backends are executing different machine code.*
This is not a calibration error and cannot be corrected away.

Pinning to C1 — which performs far less inlining — collapses them onto each other.
`-XX:TieredStopAtLevel=1` **[MEASURED]**:

| Method | Backend I | Backend S | Δ |
|---|---:|---:|---:|
| heavy | 70.4% | 70.1% | 0.3 pp |
| medium | 21.4% | 21.7% | 0.3 pp |
| light | 7.4% | 7.1% | 0.3 pp |
| tiny | 0.8% | 0.8% | 0.0 pp |

Two bonuses fall out. Sample density **more than doubled** — 1007 samples versus 466 at the
same iteration count, because C1 code runs slower and is therefore sampled more. And
instrumentation's overhead compensation became a small correct adjustment instead of a
distortion.

> **State this in the API.** C1-pinned numbers are **not production-representative**. You are
> deliberately trading absolute realism for reproducibility and cross-backend comparability.
> For an agent comparing variants of the same query, that is the right trade. If
> production-realistic numbers are ever wanted, that is a separate C2 mode — and in that mode
> the backends stop being interchangeable.

### 5.2 Why the setup/body split, and not "just run main N times"

The naive amplification — call `main` N times — fixes sample density but silently destroys
the thing you most want to see. Modelling the ExoQuery pattern (an expensive step that is
memoized on first call, then cheap per-execution work) **[MEASURED]**:

| Run shape | Wall time | Samples | In the memoized setup |
|---|---:|---:|---|
| N = 1 | 63 ms | 6 | 2 of 6 — **33%** |
| N = 500 | 180 ms | 105 | **0 — erased** |

A cost that was a third of the single-run profile became *zero*. Not reduced — erased. For a
10 ms snippet, one-time work (classloading, `<clinit>`, serializer descriptor construction,
memoized query compilation) often *is* the runtime.

The split solves this by reporting the three costs separately rather than conflating them.
**[MEASURED]** on the same target:

```
setup   = 17.00 ms       one-time init, excluded from the window, reported
body#1  =  5.30 ms       22x steady state - first-call cost preserved
steady  =  0.2366 ms/iter (n=2000)
```

Idiomatic Kotlin also makes naive rerunning subtly wrong: `object` singletons and top-level
`val`s initialize in `<clinit>`, which runs **once per classloader**, not once per `main()`
call. Iteration 2 quietly skips work iteration 1 did. The split makes that a feature —
initialization is *supposed* to be excluded — rather than a silent corruption.

### 5.3 Why instrumentation is the default

Probe cost is dominated entirely by `System.nanoTime()`. **[MEASURED]** on a `tsc`
clocksource: 23.6 ns per call JIT-compiled, 55.9 ns interpreted — so a probe (two calls) is
47.2 ns or 111.9 ns. Predicted probe cost of 111.9 ns versus observed 111.5 ns is a 0.4%
error, which is why calibration works.

What changes dramatically is the denominator. Instrumentation overhead by compilation regime
**[MEASURED]**:

| Measurement | JIT (C2) | Interpreted |
|---|---:|---:|
| Tiny method, plain | 1.4 ns | 65.7 ns |
| + counting probe | 6.1 ns (**4.4×**) | 73.0 ns (**1.1×**) |
| + timing probe | 48.2 ns (**34.7×**) | 177.2 ns (**2.7×**) |
| Medium method + timing | 69.7 ns (**2.5×**) | 714.3 ns (**1.3×**) |

Timing instrumentation is catastrophic in a warmed-up application and modest in the regime a
10 ms playground snippet actually occupies. Combined with C1 pinning, instrumentation is
accurate across the whole cost range while remaining perfectly reproducible — the same input
yields the same number every time, which is what the agent consumer needs.

> **[TRAP] Overhead compensation is regime-dependent.**
> Subtracting `callCount × calibratedProbeCost` helps in the interpreted and C1 regimes.
> **[MEASURED]** under C2 it actively *hurts*: it pushed `heavy` from 68.9% (truth 69.7%) to
> 73.4%, and drove `tiny` from 1.7% to **0.0%** — a real method erased by over-subtraction,
> because the calibrated probe cost measured in isolation exceeds its cost in situ. Under C1
> the raw numbers are already good enough that compensation is optional.

### 5.4 The property you get for free

Because the contract is identical, the same source can be run under **both** backends and
their agreement used as a confidence signal. Where they agree within a couple of percentage
points, the number is trustworthy. Where they diverge, flag it.

For an agent consumer this is disproportionately valuable: it stops the agent chasing a
phantom 3× difference that is really an inlining artifact. And it costs nothing extra,
because both backends are being built anyway.

---

## 6. Sandbox constraints

The child JVM runs under `-Djava.security.manager` with a generated policy. Every row below
was probed directly against a replica of `executor.policy` at `-Xmx32M`. **This table will
save an implementer a day of guessing.** **[MEASURED]**

| Operation | Result | Required grant |
|---|---|---|
| JFR writes its dump into the read-only generated dir | **allowed** | none — JFR is JVM-internal |
| Programmatic `jdk.jfr.Recording` | **denied** → allowed | `jdk.jfr.FlightRecorderPermission "accessFlightRecorder"` plus write on the dump path |
| `-javaagent:` with a shutdown hook | **denied** → allowed | narrow `grant codeBase` on the agent jar |
| `Thread.getAllStackTraces()` | **denied** | rules out a hand-rolled sampler |
| `new Throwable().getStackTrace()` | **allowed** | — |
| Spawning a daemon thread | **allowed** | — |
| User code writing a file | **denied** | profile data must not transit user code |

### Policy additions

```
// executor.policy - the agent jar gets its own codeBase grant,
// exactly the pattern already used for executors.jar
grant codeBase "file:%%LIB_DIR%%/profiler-agent.jar" {
  permission java.security.AllPermission;
};

// added to the existing default grant block at line 22
grant {
  permission jdk.jfr.FlightRecorderPermission "accessFlightRecorder";
  permission java.io.FilePermission "%%GENERATED%%/-", "read,write";
};
```

`%%LIB_DIR%%` and `%%GENERATED%%` are substituted in `KotlinCompiler.write()`.

### Child JVM flags

```
// all added in CommandLineArgument.toList() - JavaExecutor.kt:113
-XX:TieredStopAtLevel=1               // contract term 4 - makes backends comparable
-Xlog:jfr*=off                        // keeps JFR logging off the JSON channel
-javaagent:<lib>/profiler-agent.jar   // backend I only
-Xmx<raised>M                         // 32 MB is too tight for a profiled run
```

### Other limits to respect

- **`MAX_OUTPUT_SIZE = 100 * 1024`** (`JavaExecutor.kt:19`) — and exceeding it *discards all
  output* (line 57). Never push stack data through stdout. Write the profile to a file in the
  temp dir and read it in the parent, mirroring `addByteCode`.
- **`EXECUTION_TIMEOUT = 10000L`** is a `const val` and must become a parameter. Profiled runs
  need longer, and timing instrumentation inflates total runtime enough that a snippet near
  the limit unprofiled can exceed it profiled.
- **SecurityManager is deprecated** (JEP 411) and throws outright on JDK 24+. The project pins
  JDK 17, so this is fine today, but any design leaning on policy grants inherits that debt.
- **Clocksource is an ops risk.** Verified `tsc` here. On a host stuck on `xen`, `hpet` or
  `acpi_pm`, `nanoTime()` costs 500 ns–1 µs and every overhead number in §5.3 inverts. Check
  `/sys/devices/system/clocksource/clocksource0/current_clocksource` in the deployed
  container; if it is not `tsc`, fall back to counts only.

---

## 7. Result schema

Designed for an agent that will diff two of these. Stable keys matter more than completeness;
keep the frame list to a top-N.

```jsonc
{
  "backend": "instrument" | "sample" | "both",
  "jitTier": "c1",
  "iterations": { "warmup": 5000, "measured": 20000 },

  "timings": {
    "setupNs": 17004312,        // one-time init - excluded from frames
    "firstCallNs": 5301887,     // body() #1 - catches memoization
    "steadyNsPerIter": 236612
  },

  "frames": [                   // top-N by selfPct, descending
    { "frame": "io.exoquery.SqlCompiler.build",
      "selfPct": 70.4, "totalPct": 92.1,
      "count": 20000,           // null for backend "sample"
      "confidence": "high" }    // only when backend == "both"
  ],

  "calls": [                    // backend "instrument" only - the call-site census
    { "caller": "io.exoquery.SqlCompiler.build",
      "callee": "java.lang.StringBuilder.append",
      "count": 480000 }
  ],

  "warnings": ["body_optimized_away"],
  "errors": {}                  // existing CompilerDiagnostics shape
}
```

### Two reconciliation rules

1. **`count` is instrumentation-only.** Sampling cannot count invocations. Emit `null`, never
   a fabricated estimate.
2. **Roll up unreachable frames.** Sampling sees `java.lang.StringCoding.*` and similar;
   instrumentation cannot reach inside the JDK. Attribute those samples to the nearest
   instrumented ancestor so the two backends' rows line up. **Without this rule the schemas
   match but the numbers do not.**

### Diagnostics the agent can act on

- `body_optimized_away` — steady-state time below a floor, or the blackhole never observed a write.
- `insufficient_samples` — backend S produced fewer than ~100 samples; the agent should raise
  `N` or switch to backend I.
- `backend_disagreement` — with `backend: "both"`, any frame where the two differ by more than
  a threshold.
- `first_call_dominates` — `firstCallNs` greatly exceeds `steadyNsPerIter`, indicating
  memoization inside `body()`. The steady-state profile is real but is not the whole story.

---

## 8. Implementation plan

Phased so that each stage is independently useful and independently testable. The ordering is
a real dependency chain: phase 2 needs the contract from phase 1, and phase 4's confidence
signal needs both backends.

> **Do this before P1.** Measure where per-run latency actually goes. Throughput is the entire
> value proposition, and the strong prior is that **the Kotlin compile dominates, not the
> profiled execution**. If compile is 2 s and the profiled run is 200 ms, then the backend
> choice barely matters and the real work is compile caching or warm-environment reuse. This
> measurement could reorder everything below it, and it costs an afternoon.

### P1 — Contract, plumbing, and the harness entry point (≈ 1 day)

- Extend the ASM visitor at `KotlinCompiler.kt:129` to discover `setup()V` / `body()V`
  alongside `main`.
- Add a harness main class to `executors.jar`, beside `JavaRunnerExecutor`: reflectively
  resolve `setup`/`body`, run the timeline in §3, emit the three timings.
- Add `Blackhole` to `executors.jar`.
- Thread a `profile` request parameter through `CompilerRestController` →
  `KotlinProjectExecutor` → `KotlinCompiler`, mirroring `addByteCode` exactly.
- Add `-XX:TieredStopAtLevel=1` and a raised heap in `CommandLineArgument.toList()`;
  parameterize `EXECUTION_TIMEOUT`.
- Extend `JvmExecutionResult` with the profile payload, mirroring `jvmByteCode`.

**Deliverable:** the three timings, no frame data. Already useful — `firstCallNs` versus
`steadyNsPerIter` answers real questions on its own.

### P2 — Backend S, sampling (≈ 1–2 days)

- Programmatic `jdk.jfr.Recording` in the harness, window around the measured loop only;
  `enable("jdk.ExecutionSample").withPeriod(1ms)`.
- Dump synchronously *before* printing the result JSON.
- Policy grants and `-Xlog:jfr*=off` per §6.
- Parent-side fold with `jdk.jfr.consumer.RecordingFile` — a JDK class, no new dependency.
  Weight each sample by its **leaf** frame for self time. Apply the harness-frame filter.

Cheapest path to a real flamegraph-shaped result. The fold was ~20 lines in prototype.

### P3 — Backend I, instrumentation agent (≈ 3–5 days)

- New Gradle subproject producing `profiler-agent.jar`, built and copied like `:executors`.
  **Include inner classes in the jar** — a missing `Agent$1` fails with a misleading
  `NoClassDefFoundError` inside `premain`.
- `ClassFileTransformer` scoped by package prefix: instrument `io.exoquery.**`, `kotlin.**`,
  `kotlinx.**` and user classes; skip `java.**`, `executors.**`, and the agent itself.
- Method-entry counters *and* call-site counters keyed on `(caller, callee)`.
- Timing via try/finally with `COMPUTE_FRAMES` and a `getCommonSuperClass` backed by a
  `URLClassLoader` over the compile classpath. **Ship counting first, add timing second** —
  counting needs no frame recomputation and is the larger share of the value.
- Probe-cost calibration loop in `premain`; apply compensation only at C1/interpreted.

### P4 — Unified schema and the confidence signal (≈ 2–3 days)

- Normalize both backends into the §7 shape: fold sampling to self-time per frame, filter
  harness frames, roll JDK frames up to the nearest instrumented ancestor.
- Implement `backend: "both"` — run once under each, join on frame key, emit `confidence` and
  `backend_disagreement`.
- Implement the diagnostics in §7.
- Enforce the top-N cap. Output size is a product requirement, not a detail.

---

## 9. Validation

### The regression suite you already own

**580 Kotlin snippets** live in `src/test/resources/test-compile-data/jvm`, already driven by
`ResourceCompileTest` and `ResourceE2ECompileTest`. Run every one of them through the
instrumenting agent and assert that they still compile, still run, and still produce
byte-identical output. For bytecode rewriting this is an unusually strong safety net, and it
is the single biggest reason instrumentation is a defensible choice here rather than a risky
one.

### Backend agreement test

Port the four-method fixture from §5 into the test suite: known cost ratios, both backends,
assert agreement within 1 pp under C1. This test is what protects contract term 4 from being
"optimized away" by a future contributor who does not know why `-XX:TieredStopAtLevel=1` is
there.

### Determinism test

Run the same source ten times under backend I and assert identical counts and steady-state
timings within a tight tolerance. Reproducibility is a product guarantee for the agent
consumer, so it needs a test.

---

## 10. Risks and open questions

### Unresolved

- **Per-run latency budget is unmeasured.** The top-priority unknown; see the callout in §8.
- **Library-internal memoization inside `body()` is detectable but not attributable.** The
  `firstCallNs` / `steadyNsPerIter` gap reveals that it happened; it does not say where.
  Attributing it requires instrumenting iteration 1 specifically — counts are exact regardless
  of duration, so backend I can answer this where backend S structurally cannot. This is the
  strongest argument for eventually running both.

### Known and accepted

- **Kotlin inline functions carry SMAP line numbers** from the callee's source file (JSR-45
  `SourceDebugExtension`). If line-level attribution is ever added, lines beyond the user
  file's length must be clamped or dropped. Method-level attribution is unaffected.
- **Lambdas become synthetic classes** (`Foo$body$1`) and **`suspend` functions become state
  machines**. Frame names will not always resemble the source. Demangle for display; there is
  no cheap fix for coroutines.
- **C1-pinned numbers are not production-representative.** Deliberate. State it in the API
  response.

### Unrelated bug found while surveying

> The `Dockerfile` copies the jvm, js, wasm, compose-wasm and compose-wasm-compiler-plugins
> directories — but **never copies `${KOTLIN_VERSION}-compiler-plugins`**. In a Docker build
> that directory is absent, `listFiles()` returns null, `compilerPlugins` is empty, and **the
> ExoQuery plugin silently does not run**. `buildLambda` handles it correctly
> (`build.gradle.kts:157`); the Dockerfile does not. Worth fixing before anyone tries to
> profile ExoQuery code in a container.

---

## 11. Effort summary

| Phase | Scope | Estimate |
|---|---|---:|
| P0 | Measure per-run latency breakdown | ½ day |
| P1 | Contract, plumbing, three timings | 1 day |
| P2 | Backend S — sampling | 1–2 days |
| P3 | Backend I — instrumentation agent | 3–5 days |
| P4 | Unified schema, confidence signal | 2–3 days |
| | **Total** | **8–12 days** |

If only one backend ships, ship **P1 + P3 (instrumentation)**. It answers the original
question — what low-level calls does my code cause — it degrades gracefully rather than
silently on short programs, and its numbers are reproducible, which is what the agent consumer
needs most. Sampling is the better second addition, not the better first.
