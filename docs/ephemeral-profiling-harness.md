# Ephemeral Profiling Harness — Implementation Plan

A stateless, agent-driven profiling endpoint for Kotlin playground code. One contract,
two interchangeable measurement backends, throwaway state, and results small enough for
a language model to diff.

| | |
|---|---|
| **Repository** | `deusaquilus/kotlin-compiler-server` |
| **Branch** | `claude/exoquery-kotlin-compiler-btw1dy` |
| **Kotlin** | 2.1.20 |
| **Target JDK** | 17 (`jvmToolchain` 17, Amazon Corretto) |
| **Status** | Ready to implement — all mechanisms empirically verified against the real sandbox |

---

## 0. How to use this document

This is a **self-contained implementation plan**. It assumes no prior context about the
project. Read §1–§3 for what you are building and why, §4–§6 for the constraints you must
respect, §7–§9 for the code, and §10 for the ordered task list with acceptance criteria.

Code in §7–§9 is written to be typed in more or less as-is. It is not pseudocode. Where a
decision is genuinely open, it is marked and both options are given.

**Claim tags used throughout:**

- **[MEASURED]** — verified by direct experiment against this project's sandbox constraints.
  Appendix A tells you how to reproduce each one.
- **[DECISION]** — design judgment, not measured. Push back freely.
- **[TRAP]** — a failure mode that will cost you a day if you don't know about it.

---

## 1. Goal and consumer

The consumer of this endpoint is **an AI agent, not a human**. The agent writes a variant of
a Kotlin snippet, POSTs it, receives a small structured result, and discards everything —
then repeats dozens or hundreds of times to answer questions like *"which of these three
ExoQuery formulations is actually cheaper?"*

Four consequences drive every decision below:

| Property | Why it dominates |
|---|---|
| **Determinism over fidelity** | An agent comparing A to B reads run-to-run noise as signal. Reproducible numbers beat production-realistic ones. |
| **Small output** | Every returned byte costs agent context. A collapsed-stack dump is harmful; a top-N table with stable keys is what's needed. The agent's primary operation is *subtracting two results*. |
| **Throughput** | 200 experiments at 3 s each is ten minutes; at 0.5 s it's ninety seconds. That gap decides whether the loop is usable. |
| **Machine-readable diagnostics** | An agent will write bodies that get dead-code-eliminated. A finding with an actionable `hint` is worth as much as the timing. |

### The ephemerality is already free

The existing architecture is already stateless. `usingTempDirectory` creates a UUID-named
directory and `deleteRecursively`s it in a `finally`; every run compiles into a fresh temp dir
and executes in a fresh child JVM with a freshly written policy file. There is no persistent
state to discard because there is none. **This is why this repository is the right host** for
the idea rather than a long-lived benchmark service. You are adding a mode, not an
architecture.

### Prior art in the same product family

ExoBench already ships `analyzeHibernateQueries`, which follows exactly this shape: compile
Kotlin or Java, run it against an ephemeral in-memory H2, capture an instrumentation
transcript, return structured findings, destroy everything. **This plan deliberately mirrors
its conventions** — the bootstrap-lambda contract (§3), the response envelope (§9.5), and the
findings-with-hints format — so the consuming agent meets one idiom rather than two.

Its trusted entrypoint is `hib.bootstrap.HibBootstrap.withSession(entityClasses) { sf -> … }`.
Ours is `executors.ProfileBootstrap.measure { … }`. Same idea, same reason.

The two tools are complementary: `analyzeHibernateQueries` answers *what SQL was emitted*;
this one answers *where the JVM time went*. Profiling a Hibernate run through this harness —
seeing reflection, proxy initialization and entity hydration costs — is a strong use case for
both.

---

## 2. Existing architecture

A fork of the JetBrains *kotlin-compiler-server* (the Kotlin Playground backend) at Kotlin
2.1.20, with the ExoQuery compiler plugin wired in.

### Request flow for a JVM run

```
CompilerRestController  (POST /api/compiler/run?addByteCode=…)
  └─> KotlinProjectExecutor.run(project, addByteCode)
      └─> KotlinCompiler.run(files, addByteCode, args)
          └─> KotlinCompiler.execute(files, addByteCode) { output, compiled -> … }
              ├─> compile(files)                 K2JVMCompiler CLI, in-process
              ├─> write(classes, outputDir)      + renders executor.policy
              └─> JavaExecutor.execute(argv)     separate JVM, sandboxed
                  └─> child: executors.JavaRunnerExecutor
                      └─> reflectively invokes user main, prints JSON to stdout
```

`KotlinCompiler.compile()` does **not** use the in-memory `KotlinCoreEnvironment` for codegen.
It writes the `KtFile`s to a temp directory and invokes the real `K2JVMCompiler` CLI entry
point (`doMainNoExit`) with `-cp <every jar in the lib folder>`,
`-no-stdlib -no-reflect -progressive`, `-d <outputDir>`, and one `-Xplugin=` per jar in the
compiler-plugins folder. That last mechanism is how the ExoQuery plugin is applied.

The child JVM runs with `-Xmx32M`, `-Djava.security.manager`, a generated `executor.policy`,
a 10 s timeout and a 100 KB output cap.

### The fact the contract rests on **[MEASURED]**

`executors.jar` is on the **compile** classpath, not just the runtime one. The chain:

```
executors/build.gradle.kts:17   jar.destinationDirectory = libJVMFolder
KotlinEnvironment.kt:17         classPath = librariesFile.jvm.listFiles()
KotlinCompiler.kt:96            "-cp", kotlinEnvironment.classpath…      ← compile
KotlinCompiler.kt:183           kotlinEnvironment.classpath…             ← runtime
```

So submitted user code can `import executors.ProfileBootstrap` and `import executors.Blackhole`
and it will compile. **Verify this still holds before starting** — the entire contract in §3
depends on it.

### Module layout

| Module | Produces | Notes |
|---|---|---|
| root | Spring Boot server | |
| `:common` | `component.KotlinEnvironment` | shared with `:indexation` |
| `:executors` | `executors.jar` → **`libJVMFolder`** | on the child's compile **and** runtime `-cp`; has policy grants |
| `:indexation` | `indexes*.json` | completion indexes |
| `:dependencies` | populates `2.1.20/`, `2.1.20-compiler-plugins/`, … | copy tasks only |

### Files you will touch

| Location | What it is |
|---|---|
| `KotlinCompiler.kt:55` | `addByteCode()` — **the precedent for parent-side result enrichment. Copy this pattern.** |
| `KotlinCompiler.kt:92` | `compile()` — builds the `K2JVMCompiler` argument list |
| `KotlinCompiler.kt:129` | `findMainClasses()` — **unchanged**; the contract reuses it as-is |
| `KotlinCompiler.kt:139` | `execute()` — orchestration; enrichment hook at line 150 |
| `KotlinCompiler.kt:177` | `argsFrom()` — builds `CommandLineArgument` |
| `KotlinCompiler.kt:190` | `memoryLimit = 32`, hardcoded |
| `JavaExecutor.kt:19` | `MAX_OUTPUT_SIZE = 100 * 1024` |
| `JavaExecutor.kt:20` | `EXECUTION_TIMEOUT = 10000L` — a `const val`; must become a parameter |
| `JavaExecutor.kt:57` | Exceeding the output cap **discards all output** |
| `JavaExecutor.kt:73` | `destroy()` — the race that constrains dump ordering |
| `JavaExecutor.kt:113` | `CommandLineArgument.toList()` — the one place child-JVM flags are built |
| `JavaRunnerExecutor.kt:27` | `mainMethod.invoke` — the shape `ProfileRunner` copies |
| `JavaRunnerExecutor.kt:47` | `defaultOutputStream.print(...)` — the JSON channel |
| `JavaRunnerExecutor.kt:56` | `RunOutput` — child→parent DTO (Jackson both sides) |
| `FailureSerializers.kt:12` | `executors.mapper` — an `ObjectMapper` already in `:executors`. Reuse it. |
| `OutputStreams.kt` | `OutStream` / `ErrorStream` — stdout capture. Reuse verbatim. |
| `executor.policy:22` | The default `grant {}` block |
| `ExecutionResult.kt` | `JvmExecutionResult`, already carries optional `jvmByteCode` |
| `ProgramOutput.kt` | `asExecutionResult()` — deserializes child stdout |
| `CompilerRestController.kt` | `/run` with `addByteCode` — precedent for the new endpoint |
| `buildSrc/src/main/kotlin/properties.kt` | folder-name constants |
| `build.gradle.kts:100` | `generateProperties()` |
| `build.gradle.kts:157` | `buildLambda` packaging |
| `Dockerfile` | **has a bug — see §6.5** |
| `src/test/resources/test-compile-data/jvm` | **580 Kotlin snippets** — your regression suite |

---

## 3. The contract (normative)

The submitted snippet is **an ordinary Kotlin program with an ordinary `main`**. It marks the
work to be measured by wrapping it in a call to a bootstrap function the harness provides.

```kotlin
import executors.ProfileBootstrap.measure
import executors.Blackhole

fun main() {
  // Anything before the first measure() call is SETUP.
  // Not measured, but its wall time is reported as setupNs.
  val people = (1..1000).map { Person(it, "n$it") }

  measure {
    Blackhole.consume(
      capture { Table<Person>().filter { it.age > 42 } }.buildFor.Postgres()
    )
  }
}
```

That is the whole contract. No annotations, no naming convention, no reserved method names.

### Why a lambda rather than `setup()` / `body()` methods

**[DECISION]** An earlier draft of this plan discovered the entry points by ASM-scanning
compiled classes for `setup()V` / `body()V`. The bootstrap-lambda is better on four counts and
the change deletes a component:

1. **`findMainClasses` works unchanged.** No new discovery code, no new failure modes around
   Kotlin's `object` / top-level-function codegen.
2. **No custom child main class is needed** for entry-point resolution — the user's own `main`
   is the entry point, exactly as in a normal `/run`.
3. **Lexical scope makes "what is measured" visually unambiguous**, which matters when the
   author is a language model.
4. **It matches `HibBootstrap.withSession { … }`** — the convention the consuming agent
   already knows from `analyzeHibernateQueries`.

### Execution timeline

```
        |<----- setup ----->|<- first ->|<--- warmup --->|<==== MEASUREMENT WINDOW ====>|
        +-------------------+-----------+----------------+------------------------------+
        | main() until      | block()   |  block() x W   |        block() x N           |
        | measure() entered |  once     |    excluded    |   sampled OR instrumented    |
        +-------------------+-----------+----------------+------------------------------+
                |                 |                      ^                              |
                v                 v                      |                              v
            setupNs         firstCallNs          arm / recording start        dump -> parent folds

Three timings are reported separately, so one-time cost is never averaged into invisibility.
```

`setupNs` is measured by `ProfileRunner` as *(time `measure()` was entered) − (time `main` was
invoked)*. It therefore includes user fixture construction, `<clinit>` of everything that code
touched, and the classloading it triggered — which is exactly what "setup" means here.

### Normative terms

All six are required. Terms 1–3 make profiling *correct*; terms 4–6 make the two backends
*interchangeable*.

1. **Work to be measured is inside the `measure { … }` lambda.** Everything before the first
   call is setup and is excluded from the window.
2. **Warmup iterations** (`W`) run before the window opens.
3. **`N` iterations** run inside the window.
4. **Pinned JIT tier: `-XX:TieredStopAtLevel=1`.** **[MEASURED]** The load-bearing term; §5.1.
5. **A blackhole sink** so the lambda cannot be dead-code-eliminated.
6. **A fixed harness-frame filter**, applied identically by both backends:
   `executors.*`, `java.lang.reflect.*`, `jdk.internal.reflect.*`, `jdk.jfr.*`,
   `io.exoquery.profiler.*`.

### Named blocks — amortizing the compile cost

`measure` takes an optional name, and a program may call it more than once:

```kotlin
fun main() {
  val people = fixture()

  measure("filter-then-map") { Blackhole.consume(variantA(people)) }
  measure("map-then-filter") { Blackhole.consume(variantB(people)) }
}
```

Results are keyed by name. **This matters for throughput**: if compilation dominates per-run
latency (see P0 in §10), then two variants in one submission costs one compile instead of two,
and the agent's A/B loop gets roughly twice as fast.

For a block after the first, `setupNs` is measured from the end of the previous block rather
than from `main` entry.

> **[DECISION] Caveat to document in the API.** Blocks share a JVM, so block 2 runs with JIT
> state block 1 created. Each block has its own warmup and its own armed window, and C1 pinning
> caps how much cross-block state can accumulate — but the effect is not zero, and results can
> depend on declaration order. **For a precise head-to-head, use one block per request.** Use
> multiple blocks for breadth-first exploration where throughput matters more than the last
> percentage point.

### Defaults

Supplied by the harness through system properties; explicit arguments in source win.

| Parameter | Default | Notes |
|---|---:|---|
| `W` (warmup) | 5 000 | **[DECISION]** enough for C1 to compile the block |
| `N` (measured) | 20 000 | tune against the P0 latency measurement |
| `backend` | `instrument` | §5.3; never settable from source |
| heap | 256 MB | 32 MB is too tight |
| timeout | 60 s | 10 s is too tight for a profiled run |

### Error cases

| Condition | Result |
|---|---|
| `main` never calls `measure` | `status: "ok"`, finding `kind: "noMeasureBlock"` with a hint showing the contract |
| Two blocks share a name | second call fails fast with `IllegalArgumentException` |
| Lambda throws | that block reports `status: "error"` with the exception; other blocks still report |
| `Blackhole` never touched in a block | finding `kind: "bodyOptimizedAway"` |

---

## 4. Component inventory

Everything you will create or modify. Nothing else is required.

| # | Component | Module | Kind | § |
|---|---|---|---|---|
| C1 | `Blackhole` | `:executors` | new | 7.1 |
| C2 | `ProfileBootstrap` — the user-facing `measure { }` | `:executors` | new | 7.2 |
| C3 | `ProfileRunner` — child main class | `:executors` | new | 7.3 |
| C4 | `ProfileOutput` DTO | `:executors` | new | 7.4 |
| C5 | `Agent` (premain) | `:profiler-agent` | new module | 8.1 |
| C6 | `Probe` (counters + dump) | `:profiler-agent` | new | 8.2 |
| C7 | `ProfilingTransformer` (ASM) | `:profiler-agent` | new | 8.3 |
| C8 | Child JVM flags | `JavaExecutor.kt` | modify | 9.1 |
| C9 | Orchestration + profile read-back | `KotlinCompiler.kt` | modify | 9.2 |
| C10 | JFR fold | new server file | new | 9.3 |
| C11 | Response envelope | `ExecutionResult.kt` | modify | 9.4 |
| C12 | Endpoint | `CompilerRestController.kt` | modify | 9.5 |
| C13 | Build wiring | Gradle, `properties.kt`, Docker | modify | 6.4–6.5 |
| C14 | Policy grants | `executor.policy` | modify | 6.2 |

> **Deleted relative to the previous draft:** the ASM contract-discovery visitor. `findMainClasses`
> is used unchanged.

---

## 5. Evidence

Every table here was produced by running both backends against an identical
setup/warmup/N contract over a target with a known cost distribution: four methods
(`heavy`, `medium`, `light`, `tiny`) whose isolated, unprobed costs were measured first.
Appendix A reproduces all of it.

**Ground truth** — each method timed alone, unprobed, fully JIT-compiled **[MEASURED]**:

| Method | ns / call | Share |
|---|---:|---:|
| heavy | 1164.6 | 69.7% |
| medium | 351.9 | 21.1% |
| light | 130.1 | 7.8% |
| tiny | 24.4 | 1.5% |

### 5.1 The pinned JIT tier is load-bearing

Under default tiered compilation the two backends **do not agree** — within 10% above ~350 ns,
diverging 3–4× below ~130 ns. **[MEASURED]**

| Method | Truth | Backend I | Backend S † | Verdict |
|---|---:|---:|---:|---|
| heavy | 69.7% | 68.9% | 74.3% | agree |
| medium | 21.1% | 21.2% | 23.3% | agree |
| **light** | **7.8%** | **8.2%** | **1.9%** | **4× disagreement** |
| **tiny** | **1.5%** | **1.7%** | **0.5%** | **3× disagreement** |

† renormalized over the four methods; another 7.1% of samples landed in the calling loop —
those are inlined frames.

**The cause is inlining.** Instrumentation prevents the JIT from inlining a probed method;
sampling does not. Under C2, `light` and `tiny` get inlined into their caller while under
instrumentation they cannot be. *The two backends execute different machine code.* This is not
a calibration error and cannot be corrected away.

Pinning to C1 — far less inlining — collapses them. `-XX:TieredStopAtLevel=1` **[MEASURED]**:

| Method | Backend I | Backend S | Δ |
|---|---:|---:|---:|
| heavy | 70.4% | 70.1% | 0.3 pp |
| medium | 21.4% | 21.7% | 0.3 pp |
| light | 7.4% | 7.1% | 0.3 pp |
| tiny | 0.8% | 0.8% | 0.0 pp |

Two bonuses: sample density **more than doubled** (1007 vs 466 samples at the same iteration
count, because C1 code runs slower and is sampled more), and instrumentation's overhead
compensation became a small correct adjustment instead of a distortion.

> **State this in the API.** C1-pinned numbers are **not production-representative**. You are
> deliberately trading absolute realism for reproducibility and cross-backend comparability.
> For an agent comparing variants of the same query that is the right trade. A
> production-realistic C2 mode is a separate feature — and in it the backends stop being
> interchangeable.

### 5.2 Why setup is excluded rather than amortized

Naive amplification — running the whole program N times — fixes sample density but silently
destroys the thing you most want to see. Modelling the ExoQuery pattern, an expensive step
memoized on first call followed by cheap per-execution work **[MEASURED]**:

| Run shape | Wall time | Samples | In the memoized setup |
|---|---:|---:|---|
| N = 1 | 63 ms | 6 | 2 of 6 — **33%** |
| N = 500 | 180 ms | 105 | **0 — erased** |

A third of the single-run profile became *zero*. Not reduced — erased. For a 10 ms snippet,
one-time work (classloading, `<clinit>`, serializer descriptor construction, memoized query
compilation) often *is* the runtime.

Excluding setup from the window and reporting it separately solves this. **[MEASURED]** on the
same target:

```
setup   = 17.00 ms       one-time init, excluded from the window, reported
first   =  5.30 ms       22x steady state - first-call cost preserved
steady  =  0.2366 ms/iter (n=2000)
```

The lambda contract makes this robust in a way repeated `main()` invocation never could:
`object` singletons and top-level `val`s initialize in `<clinit>`, which runs **once per
classloader**, so re-invoking `main` would silently skip work the first invocation did. Here
that code simply lives before `measure` and is excluded by construction.

### 5.3 Why instrumentation is the default

Probe cost is dominated entirely by `System.nanoTime()`. **[MEASURED]** on a `tsc` clocksource:
23.6 ns per call JIT-compiled, 55.9 ns interpreted — a two-call probe is 47.2 ns or 111.9 ns.
Predicted 111.9 ns vs observed 111.5 ns is a 0.4% error, which is why calibration works.

What changes dramatically is the denominator **[MEASURED]**:

| Measurement | JIT (C2) | Interpreted |
|---|---:|---:|
| Tiny method, plain | 1.4 ns | 65.7 ns |
| + counting probe | 6.1 ns (**4.4×**) | 73.0 ns (**1.1×**) |
| + timing probe | 48.2 ns (**34.7×**) | 177.2 ns (**2.7×**) |
| Medium method + timing | 69.7 ns (**2.5×**) | 714.3 ns (**1.3×**) |

Timing instrumentation is catastrophic in a warmed-up application and modest in the regime a
10 ms playground snippet occupies. Combined with C1 pinning it is accurate across the whole
cost range while remaining perfectly reproducible — same input, same number, every time.

> **[TRAP] Overhead compensation is regime-dependent.** Subtracting
> `callCount × calibratedProbeCost` helps interpreted and at C1. **[MEASURED]** under C2 it
> actively *hurts*: it pushed `heavy` from 68.9% (truth 69.7%) to 73.4% and drove `tiny` from
> 1.7% to **0.0%** — a real method erased by over-subtraction, because probe cost measured in
> isolation exceeds its cost in situ. **Ship with compensation off; add it behind a flag.**

### 5.4 What you get free

Because the contract is identical, the same source can run under **both** backends and their
agreement becomes a confidence signal. Where they agree within a couple of points the number is
trustworthy; where they diverge, flag it. For an agent consumer this stops it chasing a phantom
3× difference that is really an inlining artifact — and it costs nothing extra, since both
backends exist anyway.

---

## 6. Constraints and build wiring

### 6.1 Sandbox permissions **[MEASURED]**

The child JVM runs under `-Djava.security.manager` with a generated policy. Every row was
probed directly against a replica of `executor.policy` at `-Xmx32M`. **This table saves a day
of guessing.**

| Operation | Result | Required grant |
|---|---|---|
| JFR writes its dump into the read-only generated dir | **allowed** | none — JFR is JVM-internal |
| Programmatic `jdk.jfr.Recording` | **denied** → allowed | `jdk.jfr.FlightRecorderPermission "accessFlightRecorder"` + write on dump path |
| `-javaagent:` with a shutdown hook | **denied** → allowed | narrow `grant codeBase` on the agent jar |
| `Thread.getAllStackTraces()` | **denied** | rules out a hand-rolled sampler |
| `new Throwable().getStackTrace()` | **allowed** | — |
| Spawning a daemon thread | **allowed** | — |
| User code writing a file | **denied** | profile data must not transit user code |

### 6.2 Policy additions (C14)

`executor.policy` is read and placeholder-substituted in `KotlinCompiler.write()`. Add
`%%AGENT_DIR%%` to that substitution (§9.2).

```
grant codeBase "file:%%AGENT_DIR%%/profiler-agent.jar" {
  permission java.security.AllPermission;
};

grant codeBase "file:%%LIB_DIR%%/executors.jar" {
  permission java.lang.reflect.ReflectPermission "suppressAccessChecks";
  permission java.lang.RuntimePermission "setIO";
  permission java.io.FilePermission "<<ALL FILES>>", "read";
  permission java.lang.RuntimePermission "accessDeclaredMembers";
  // ---- added for the profiling harness ----
  permission jdk.jfr.FlightRecorderPermission "accessFlightRecorder";
  permission java.io.FilePermission "%%GENERATED%%/-", "read,write";
  permission java.util.PropertyPermission "exo.profile.*", "read";
};
```

> **Why both grants.** `AccessController` checks *every* protection domain on the stack. When
> `ProfileBootstrap` (executors.jar) calls `Probe.dump()` (agent jar), both domains must permit
> the write. Granting `executors.jar` write on `%%GENERATED%%/-` covers it without
> `doPrivileged` gymnastics. If you prefer to keep `executors.jar` minimal, wrap the write in
> `AccessController.doPrivileged` inside `Probe` instead — the agent jar has `AllPermission`,
> so the stack walk stops there.
>
> The `PropertyPermission` is needed because `ProfileBootstrap` reads its defaults from
> `-Dexo.profile.*`. The existing `grant {}` block already whitelists specific property reads;
> this follows that pattern.

### 6.3 Child JVM flags (C8)

```
-XX:TieredStopAtLevel=1                     contract term 4 — makes backends comparable
-Xlog:jfr*=off                              keeps JFR logging off the JSON channel
-javaagent:<agentDir>/profiler-agent.jar    backend "instrument" only
-Dexo.profile.backend=instrument            read by ProfileBootstrap
-Dexo.profile.warmup=5000
-Dexo.profile.iterations=20000
-Dexo.profile.outDir=<generated>
-Xmx256M                                    32 MB is too tight for a profiled run
```

### 6.4 Build wiring (C13)

**`settings.gradle.kts`** — add the module:

```kotlin
include(":executors")
include(":indexation")
include(":common")
include(":dependencies")
include(":profiler-agent")          // NEW
```

**`buildSrc/src/main/kotlin/properties.kt`** — add the folder, mirroring `compilerPluginsForJVM`:

```kotlin
val Project.profilerAgent
    get() = "$kotlinVersion-profiler-agent"

val Project.profilerAgentFolder
    get() = rootProject.layout.projectDirectory.dir(profilerAgent)
```

**`profiler-agent/build.gradle.kts`** — new file. A fat jar, because the agent is loaded by
`-javaagent:` and must carry ASM with it.

```kotlin
plugins { java }

java.toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
    vendor.set(JvmVendorSpec.AMAZON)
}

repositories { mavenCentral() }

dependencies {
    implementation("org.ow2.asm:asm:9.7")
    implementation("org.ow2.asm:asm-commons:9.7")   // AdviceAdapter
}

tasks.jar {
    archiveFileName.set("profiler-agent.jar")
    destinationDirectory.set(profilerAgentFolder)
    manifest {
        attributes(
            "Premain-Class" to "io.exoquery.profiler.Agent",
            "Can-Retransform-Classes" to "false",
            "Can-Redefine-Classes" to "false",
        )
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
    }
}
```

> **[TRAP] Include inner classes in the agent jar.** A jar missing `Agent$1` fails inside
> `premain` with `NoClassDefFoundError`, surfacing as an opaque `InvocationTargetException`
> from `sun.instrument.InstrumentationImpl.loadClassAndStartAgent` with no useful message. The
> `from(configurations…)` block plus a normal `jar` task handles this; a hand-rolled `jar cfm`
> with an explicit class list does not. **[MEASURED]** — this exact failure occurred during
> prototyping.

**Root `build.gradle.kts`** — three edits:

```kotlin
// 1. generateProperties(), after libraries.folder.compiler-plugins
libraries.folder.profiler-agent=${prefix + profilerAgent}

// 2. compileKotlin dependencies
tasks.withType<KotlinCompile> {
    // …existing…
    dependsOn(":profiler-agent:jar")        // NEW
}

// 3. buildLambda packaging, next to the compilerPluginsForJVMFolder line
from(profilerAgentFolder) { into(profilerAgent) }
```

**`ApplicationConfiguration.kt`** and **`LibrariesFile.kt`** — add the folder, mirroring
`compilerPlugins` exactly:

```kotlin
class LibrariesFile(
  val jvm: File,
  val js: File,
  val wasm: File,
  val composeWasm: File,
  val composeWasmComposeCompiler: File,
  val compilerPlugins: File,
  val profilerAgent: File,          // NEW
)

// LibrariesFolderProperties
lateinit var profilerAgent: String  // NEW  (binds libraries.folder.profiler-agent)
```

### 6.5 Dockerfile — fix an existing bug and add the agent

> **[TRAP] Pre-existing defect.** The `Dockerfile` copies the jvm, js, wasm, compose-wasm and
> compose-wasm-compiler-plugins directories but **never copies
> `${KOTLIN_VERSION}-compiler-plugins`**. In a Docker build that directory is absent,
> `listFiles()` returns null, `compilerPlugins` is empty, and **the ExoQuery plugin silently
> does not run**. `buildLambda` handles it correctly (`build.gradle.kts:157`); the Dockerfile
> does not. Fix this before profiling anything in a container, or you will profile a build with
> no ExoQuery plugin and not notice.

```dockerfile
ENV KOTLIN_COMPILER_PLUGINS=${KOTLIN_VERSION}-compiler-plugins
ENV KOTLIN_PROFILER_AGENT=${KOTLIN_VERSION}-profiler-agent

COPY --from=build /kotlin-compiler-server/${KOTLIN_COMPILER_PLUGINS} /kotlin-compiler-server/${KOTLIN_COMPILER_PLUGINS}
COPY --from=build /kotlin-compiler-server/${KOTLIN_PROFILER_AGENT}   /kotlin-compiler-server/${KOTLIN_PROFILER_AGENT}
```

### 6.6 Other limits

- **`MAX_OUTPUT_SIZE = 100 * 1024`** (`JavaExecutor.kt:19`) — and exceeding it *discards all
  output* (line 57). **Never push stack data through stdout.** Write the profile to a file in
  the temp dir and read it in the parent, mirroring `addByteCode`.
- **`EXECUTION_TIMEOUT = 10000L`** is a `const val` and must become a parameter (§9.1).
- **SecurityManager is deprecated** (JEP 411), throws on JDK 24+. Fine at JDK 17; the debt is
  inherited by anything leaning on policy grants.
- **Clocksource is an ops risk.** Verified `tsc` here. On a host stuck on `xen`, `hpet` or
  `acpi_pm`, `nanoTime()` costs 500 ns–1 µs and every overhead number in §5.3 inverts. Check
  `/sys/devices/system/clocksource/clocksource0/current_clocksource` in the deployed container;
  if it is not `tsc`, refuse timing and return counts only with an `unreliableClocksource`
  finding.

---

## 7. Child-side harness (`:executors`)

### 7.1 C1 — `Blackhole`

`executors/src/main/kotlin/Blackhole.kt`

```kotlin
package executors

/**
 * Consumes values so the JIT cannot eliminate the work that produced them.
 * Volatile writes create a happens-before edge the optimizer must respect.
 */
object Blackhole {
  @JvmStatic @Volatile var sinkLong: Long = 0L
  @JvmStatic @Volatile var sinkRef: Any? = null

  /** Reset per measured block; read by ProfileBootstrap to detect an empty body. */
  @JvmStatic @Volatile var touched: Boolean = false

  @JvmStatic fun consume(v: Long)    { touched = true; sinkLong = sinkLong xor v }
  @JvmStatic fun consume(v: Int)     { consume(v.toLong()) }
  @JvmStatic fun consume(v: Double)  { consume(java.lang.Double.doubleToRawLongBits(v)) }
  @JvmStatic fun consume(v: Boolean) { consume(if (v) 1L else 0L) }
  @JvmStatic fun consume(v: Any?)    {
    touched = true
    sinkRef = v
    sinkLong = sinkLong xor (v?.hashCode()?.toLong() ?: 0L)
  }
}
```

### 7.2 C2 — `ProfileBootstrap`

`executors/src/main/kotlin/ProfileBootstrap.kt`

The user-facing API, and the piece that implements the timeline in §3. This is what makes the
contract a library call rather than a naming convention.

```kotlin
package executors

import java.nio.file.Path
import java.nio.file.Paths

object ProfileBootstrap {

  // ---- configuration, injected by the harness via -D ------------------
  private val backend: String  = System.getProperty("exo.profile.backend", "instrument")
  private val defWarmup: Int   = System.getProperty("exo.profile.warmup", "5000").toInt()
  private val defIters: Int    = System.getProperty("exo.profile.iterations", "20000").toInt()
  private val outDir: Path     = Paths.get(System.getProperty("exo.profile.outDir", "."))

  // ---- state read back by ProfileRunner -------------------------------
  internal val blocks = mutableListOf<BlockResult>()
  internal var mainInvokedNs: Long = 0L          // stamped by ProfileRunner
  private var lastBlockEndNs: Long = 0L

  /**
   * Marks the unit of work to measure. Everything before the first call is setup.
   * May be called more than once with distinct names; results are keyed by name.
   */
  @JvmStatic
  @JvmOverloads
  fun measure(
    name: String = "default",
    warmup: Int = defWarmup,
    iterations: Int = defIters,
    block: () -> Unit,
  ) {
    require(blocks.none { it.name == name }) { "duplicate measure block name: $name" }

    val entered = System.nanoTime()
    val setupNs = entered - (if (blocks.isEmpty()) mainInvokedNs else lastBlockEndNs)

    Blackhole.touched = false

    // ---- 1. first call, timed alone ----------------------------------
    val t1 = System.nanoTime()
    val error = try { block(); null } catch (t: Throwable) { t }
    val firstCallNs = System.nanoTime() - t1

    if (error != null) {
      blocks += BlockResult(name, setupNs, firstCallNs, 0, 0, warmup, error)
      lastBlockEndNs = System.nanoTime()
      return
    }

    // ---- 2. warmup, excluded -----------------------------------------
    repeat(warmup) { block() }

    // ---- 3. arm backends, then the measured window --------------------
    val jfr = if (backend == "sample" || backend == "both") JfrWindow.start() else null
    if (backend == "instrument" || backend == "both") Probes.arm()

    val t2 = System.nanoTime()
    repeat(iterations) { block() }
    val steadyNs = System.nanoTime() - t2

    // ---- 4. disarm and dump, per block --------------------------------
    if (backend == "instrument" || backend == "both") {
      Probes.disarm()
      Probes.dump(outDir.resolve("profile-instrument-$name.json").toString())
    }
    jfr?.stopAndDump(outDir.resolve("profile-sample-$name.jfr"))

    blocks += BlockResult(
      name = name,
      setupNs = setupNs,
      firstCallNs = firstCallNs,
      steadyNsTotal = steadyNs,
      iterations = iterations,
      warmup = warmup,
      error = null,
      blackholeTouched = Blackhole.touched,
    )
    lastBlockEndNs = System.nanoTime()
  }
}

internal data class BlockResult(
  val name: String,
  val setupNs: Long,
  val firstCallNs: Long,
  val steadyNsTotal: Long,
  val iterations: Int,
  val warmup: Int,
  val error: Throwable?,
  val blackholeTouched: Boolean = false,
)
```

**Reflective bridge to the agent.** `Probes` isolates the lookup so `:executors` has no
compile-time dependency on `:profiler-agent`. The agent jar is appended to the system class
path by the JVM, so `Class.forName` resolves it when the agent is loaded and degrades to a
no-op when it is not.

```kotlin
package executors

internal object Probes {
  private val probe: Class<*>? = runCatching {
    Class.forName("io.exoquery.profiler.Probe")
  }.getOrNull()

  fun arm()    { probe?.getMethod("arm")?.invoke(null) }
  fun disarm() { probe?.getMethod("disarm")?.invoke(null) }
  fun dump(path: String) { probe?.getMethod("dump", String::class.java)?.invoke(null, path) }
}
```

**The JFR window.** Programmatic, scoped to one block's measured loop.

```kotlin
package executors

import jdk.jfr.Configuration
import jdk.jfr.Recording
import java.nio.file.Path
import java.time.Duration

internal class JfrWindow private constructor(private val r: Recording) {
  companion object {
    fun start(): JfrWindow? = runCatching {
      val r = Recording(Configuration.getConfiguration("profile"))
      r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(1))
      r.disable("jdk.ObjectAllocationInNewTLAB")
      r.disable("jdk.ObjectAllocationOutsideTLAB")
      r.start()
      JfrWindow(r)
    }.getOrNull()
  }

  fun stopAndDump(path: Path) {
    r.stop()
    r.dump(path)          // MUST complete before the JSON is printed
    r.close()
  }
}
```

> **[TRAP] Ordering.** Every `dump()` must finish **before** `ProfileRunner` prints the JSON.
> `JavaExecutor` stops reading as soon as both stream futures complete and then calls
> `destroy()` (`JavaExecutor.kt:73`); a `dumponexit=true` shutdown hook races that and loses
> the recording. Dumping inside `measure()` — as above — satisfies this by construction.
>
> **[TRAP] Never use `-XX:StartFlightRecording`.** It writes
> `[0.569s][info][jfr,startup] Started recording 1…` to **stdout**, corrupting the JSON that
> `ProgramOutput.asExecutionResult()` parses — every run becomes an exception descriptor. It
> also records JVM startup, so **[MEASURED]** most samples land in
> `sun.security.provider.PolicyFile.*` and JFR's own init rather than user code. The
> programmatic window plus `-Xlog:jfr*=off` fixes both — verified clean.

**[MEASURED]** With the window opened after setup and warmup: **396 samples, 377 (95%) in the
measured block, zero leaked from setup.** The remaining ~19 were in JFR's own
`Recording.start`, removed by the harness-frame filter.

### 7.3 C3 — `ProfileRunner`

`executors/src/main/kotlin/ProfileRunner.kt`

The child's main class for profiled runs. Deliberately a near-copy of `JavaRunnerExecutor` —
it captures stdout the same way and prints one JSON blob the same way. Its only additions are
stamping `mainInvokedNs` and collecting `ProfileBootstrap.blocks` afterwards.

```kotlin
package executors

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException

/** argv: <userMainClass> [user args…] */
class ProfileRunner {
  companion object {
    private val outputStream = ByteArrayOutputStream()

    @JvmStatic
    fun main(args: Array<String>) {
      val real = System.out
      try {
        System.setOut(PrintStream(OutStream(outputStream)))
        System.setErr(PrintStream(ErrorStream(outputStream)))

        var thrown: Throwable? = null
        try {
          val main = Class.forName(args[0])
            .getMethod("main", Array<String>::class.java)
          ProfileBootstrap.mainInvokedNs = System.nanoTime()      // ← setup clock starts
          main.invoke(null, args.copyOfRange(1, args.size) as Any)
        } catch (e: InvocationTargetException) {
          thrown = e.cause
        }

        System.out.flush(); System.err.flush()

        real.print(mapper.writeValueAsString(ProfileOutput(
          text = synchronized(outputStream) { outputStream.toString() }
            .replace("</errStream><errStream>".toRegex(), "")
            .replace("</outStream><outStream>".toRegex(), ""),
          exception = thrown,
          backend = System.getProperty("exo.profile.backend", "instrument"),
          blocks = ProfileBootstrap.blocks.map {
            BlockOutput(it.name, it.setupNs, it.firstCallNs, it.steadyNsTotal,
                        it.iterations, it.warmup, it.blackholeTouched, it.error)
          },
        )))
      } catch (e: Throwable) {
        real.println(mapper.writeValueAsString(ProfileOutput(exception = e)))
      }
    }
  }
}
```

### 7.4 C4 — child→parent DTO

`executors/src/main/kotlin/ProfileOutput.kt`

Jackson is already configured in `FailureSerializers.kt:12` (`executors.mapper`) with a
`Throwable` serializer — reuse it.

```kotlin
package executors

data class ProfileOutput(
  var text: String = "",
  var exception: Throwable? = null,
  var backend: String = "",
  var blocks: List<BlockOutput> = emptyList(),
)

data class BlockOutput(
  var name: String = "",
  var setupNs: Long = 0,
  var firstCallNs: Long = 0,
  var steadyNsTotal: Long = 0,
  var iterations: Int = 0,
  var warmup: Int = 0,
  var blackholeTouched: Boolean = false,
  var error: Throwable? = null,
)
```

---

## 8. The instrumentation agent (`:profiler-agent`)

Java, not Kotlin — `premain` and ASM callbacks are simpler without the Kotlin runtime, and the
agent must not drag `kotlin-stdlib` onto the child's system class path where it could shadow
the copy under test.

### 8.1 C5 — `Agent`

`profiler-agent/src/main/java/io/exoquery/profiler/Agent.java`

```java
package io.exoquery.profiler;

import java.lang.instrument.Instrumentation;

public final class Agent {
  public static void premain(String args, Instrumentation inst) {
    Config cfg = Config.parse(args);          // include=…;timing=true|false
    Probe.init(cfg.timing);
    inst.addTransformer(new ProfilingTransformer(cfg), false);
  }
}
```

`Config` parses the agent argument string
(`-javaagent:profiler-agent.jar=include=io.exoquery.,kotlin.,kotlinx.;timing=false`) into an
include-prefix list and a timing flag.

**Scope.** **[DECISION]** Instrument `io.exoquery.`, `kotlin.`, `kotlinx.`,
`com.github.vertical_blank.` and the user's own classes. Always skip `java.`, `jdk.`, `sun.`,
`executors.`, `io.exoquery.profiler.`, and any class whose defining loader is `null`
(bootstrap).

### 8.2 C6 — `Probe`

`profiler-agent/src/main/java/io/exoquery/profiler/Probe.java`

The hot path must be allocation-free and branch-predictable. Arrays indexed by a dense int id
assigned at transform time; names in a parallel array, touched only at dump.

```java
package io.exoquery.profiler;

import java.io.Writer;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

public final class Probe {
  private static final int MAX = 1 << 16;

  // Hot-path state. Plain long[] - single measured thread, no contention.
  public static final long[] entryCount = new long[MAX];
  public static final long[] entryNanos = new long[MAX];
  public static final long[] callCount  = new long[MAX];

  /** Gate so setup and warmup are not counted. Read on every probe. */
  public static volatile boolean armed = false;

  private static final String[] entryName = new String[MAX];
  private static final String[] callerName = new String[MAX];
  private static final String[] calleeName = new String[MAX];
  private static final AtomicInteger nextEntry = new AtomicInteger();
  private static final AtomicInteger nextCall  = new AtomicInteger();
  private static boolean timing = false;
  private static double probeNs = 0.0;

  static void init(boolean withTiming) { timing = withTiming; probeNs = calibrate(); }

  // ---- registration, from the transformer at class-load time ----------
  static int registerEntry(String fqMethod) {
    int id = nextEntry.getAndIncrement();
    if (id >= MAX) throw new IllegalStateException("probe id space exhausted");
    entryName[id] = fqMethod;
    return id;
  }

  static int registerCall(String caller, String callee) {
    int id = nextCall.getAndIncrement();
    if (id >= MAX) throw new IllegalStateException("probe id space exhausted");
    callerName[id] = caller; calleeName[id] = callee;
    return id;
  }

  // ---- hot path: referenced symbolically by injected bytecode ---------
  public static void hit(int id)  { if (armed) entryCount[id]++; }
  public static void call(int id) { if (armed) callCount[id]++; }
  public static long enter()      { return armed ? System.nanoTime() : 0L; }
  public static void exit(int id, long t0) {
    if (armed) { entryNanos[id] += System.nanoTime() - t0; entryCount[id]++; }
  }

  // ---- lifecycle, called reflectively by ProfileBootstrap -------------
  public static void arm() {
    java.util.Arrays.fill(entryCount, 0L);
    java.util.Arrays.fill(entryNanos, 0L);
    java.util.Arrays.fill(callCount, 0L);
    armed = true;
  }

  public static void disarm() { armed = false; }

  public static void dump(String path) throws Exception {
    Files.createDirectories(Paths.get(path).getParent());
    try (Writer w = new BufferedWriter(new OutputStreamWriter(
             Files.newOutputStream(Paths.get(path)), StandardCharsets.UTF_8))) {
      w.write("{\"probeNs\":"); w.write(Double.toString(probeNs));
      w.write(",\"timing\":"); w.write(Boolean.toString(timing));
      w.write(",\"entries\":[");
      boolean first = true;
      for (int i = 0; i < nextEntry.get(); i++) {
        if (entryCount[i] == 0L) continue;
        if (!first) w.write(','); first = false;
        w.write("{\"m\":\""); w.write(esc(entryName[i]));
        w.write("\",\"c\":"); w.write(Long.toString(entryCount[i]));
        w.write(",\"ns\":"); w.write(Long.toString(entryNanos[i]));
        w.write('}');
      }
      w.write("],\"calls\":[");
      first = true;
      for (int i = 0; i < nextCall.get(); i++) {
        if (callCount[i] == 0L) continue;
        if (!first) w.write(','); first = false;
        w.write("{\"from\":\""); w.write(esc(callerName[i]));
        w.write("\",\"to\":\""); w.write(esc(calleeName[i]));
        w.write("\",\"c\":"); w.write(Long.toString(callCount[i]));
        w.write('}');
      }
      w.write("]}");
    }
  }

  /** Two nanoTime calls, measured in the regime we will actually run in. */
  private static double calibrate() {
    long q = 0; int n = 200_000;
    for (int i = 0; i < n / 10; i++) q += System.nanoTime();
    long t0 = System.nanoTime();
    for (int i = 0; i < n; i++) q += System.nanoTime();
    double per = (System.nanoTime() - t0) / (double) n;
    if (q == 1) System.out.print("");     // defeat DCE
    return 2 * per;
  }

  private static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }
}
```

> **Why a hand-written JSON writer.** The agent must not pull Jackson onto the child's system
> class path, where it could shadow the copy the user's code sees. The output is a flat
> two-array structure; a 30-line writer is the correct amount of machinery.

### 8.3 C7 — `ProfilingTransformer`

`profiler-agent/src/main/java/io/exoquery/profiler/ProfilingTransformer.java`

```java
package io.exoquery.profiler;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public final class ProfilingTransformer implements ClassFileTransformer {
  private static final String PROBE = "io/exoquery/profiler/Probe";
  private final Config cfg;

  public ProfilingTransformer(Config cfg) { this.cfg = cfg; }

  @Override
  public byte[] transform(ClassLoader loader, String internalName, Class<?> beingRedefined,
                          ProtectionDomain pd, byte[] bytes) {
    if (loader == null || internalName == null) return null;   // bootstrap: skip
    if (!cfg.shouldInstrument(internalName)) return null;
    try {
      ClassReader cr = new ClassReader(bytes);
      // COMPUTE_MAXS suffices for counting-only insertion: we add no branches,
      // so existing StackMapTable entries remain valid.
      int flags = cfg.timing ? ClassWriter.COMPUTE_FRAMES : ClassWriter.COMPUTE_MAXS;
      ClassWriter cw = cfg.timing
          ? new FrameAwareWriter(cr, flags, loader)
          : new ClassWriter(cr, flags);
      cr.accept(new CV(cw, internalName, cfg), ClassReader.EXPAND_FRAMES);
      return cw.toByteArray();
    } catch (Throwable t) {
      return null;    // never break the program under test
    }
  }

  private static final class CV extends ClassVisitor {
    private final String owner; private final Config cfg;
    CV(ClassVisitor next, String owner, Config cfg) {
      super(Opcodes.ASM9, next); this.owner = owner; this.cfg = cfg;
    }
    @Override public MethodVisitor visitMethod(int access, String name, String desc,
                                               String sig, String[] exc) {
      MethodVisitor mv = super.visitMethod(access, name, desc, sig, exc);
      if (mv == null) return null;
      if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return mv;
      String fq = owner.replace('/', '.') + "." + name;
      return cfg.timing
          ? new TimingMV(mv, access, name, desc, fq)
          : new CountingMV(mv, fq, cfg);
    }
  }

  // ---- counting only: no new branches, no frame recomputation ---------
  private static final class CountingMV extends MethodVisitor {
    private final int entryId; private final String fq; private final Config cfg;
    CountingMV(MethodVisitor mv, String fq, Config cfg) {
      super(Opcodes.ASM9, mv);
      this.fq = fq; this.cfg = cfg;
      this.entryId = Probe.registerEntry(fq);
    }
    @Override public void visitCode() { super.visitCode(); emit(entryId, "hit"); }

    @Override public void visitMethodInsn(int op, String o, String n, String d, boolean itf) {
      if (cfg.countCallSites) {
        // Net stack effect zero: push int, INVOKESTATIC (I)V pops it.
        // Safe to emit immediately before the call being counted.
        emit(Probe.registerCall(fq, o.replace('/', '.') + "." + n), "call");
      }
      super.visitMethodInsn(op, o, n, d, itf);
    }
    private void emit(int id, String probeMethod) {
      mv.visitLdcInsn(id);
      mv.visitMethodInsn(Opcodes.INVOKESTATIC, PROBE, probeMethod, "(I)V", false);
    }
  }

  // ---- timing: entry + every return. Needs COMPUTE_FRAMES. ------------
  private static final class TimingMV extends AdviceAdapter {
    private final int entryId; private int slot = -1;
    TimingMV(MethodVisitor mv, int access, String name, String desc, String fq) {
      super(Opcodes.ASM9, mv, access, name, desc);
      this.entryId = Probe.registerEntry(fq);
    }
    @Override protected void onMethodEnter() {
      slot = newLocal(Type.LONG_TYPE);
      mv.visitMethodInsn(INVOKESTATIC, PROBE, "enter", "()J", false);
      mv.visitVarInsn(LSTORE, slot);
    }
    @Override protected void onMethodExit(int opcode) {
      mv.visitLdcInsn(entryId);
      mv.visitVarInsn(LLOAD, slot);
      mv.visitMethodInsn(INVOKESTATIC, PROBE, "exit", "(IJ)V", false);
    }
  }

  /** COMPUTE_FRAMES must resolve common supertypes across the app classpath. */
  private static final class FrameAwareWriter extends ClassWriter {
    private final ClassLoader loader;
    FrameAwareWriter(ClassReader cr, int flags, ClassLoader loader) {
      super(cr, flags); this.loader = loader;
    }
    @Override protected ClassLoader getClassLoader() { return loader; }
  }
}
```

**Two notes on the timing path, both real:**

1. `AdviceAdapter.onMethodExit` fires before every `xRETURN` **and** before `ATHROW` in the
   method itself, but does **not** catch exceptions propagating out of a nested call. For a
   profiling harness where an exception aborts the block anyway this is acceptable; if you need
   exception-safe timing, add an explicit catch-all via `visitTryCatchBlock` in `visitMaxs`.
   **[DECISION]** Ship without it.
2. `COMPUTE_FRAMES` with a `getClassLoader()` override is the standard fix for
   `getCommonSuperClass` failing on classes not visible to the writer's own loader. If it still
   throws for a class, the outer `catch (Throwable)` returns `null` and that class goes
   uninstrumented — degrade, never break.

### 8.4 What the agent can and cannot see **[MEASURED]**

| Quantity | Count | Consequence |
|---|---:|---|
| Classes the JVM loads (trivial program) | 551 | — |
| Offered to the transformer | 119 | Transformable |
| Loaded before `premain` | ~432 (78%) | JDK core; needs `retransformClasses`, and even then no schema changes |

**[MEASURED]** A class loaded from a jar on `-classpath` **is** offered as non-bootstrap. This
is decisive for Kotlin: `kotlin-stdlib`, `exoquery-engine`, `kotlinx-serialization`, `decomat`
and `terpal-runtime` are ordinary classpath jars on the application classloader, so **they are
all fully instrumentable**. In Java the "library" is the JDK and therefore bootstrap; in Kotlin
it is not.

> **[TRAP] Do not post-process the compiler output map.** Transforming the
> `Map<String, ByteArray>` that `KotlinCompiler.compile()` already produces sees **only the
> user's own classes**, because `-no-stdlib -no-reflect` with `-d outputDir` means nothing else
> lands there. That yields invocation counts for methods the agent already wrote and knows
> about, which answers nothing. The load-time transformer is the whole point.

**Instrument the call site, not the callee.** You cannot practically transform
`java.lang.String` or `java.util.HashMap`. You do not need to: when you rewrite a method you
can see every `INVOKEVIRTUAL java/lang/StringBuilder.append` in its bytecode and count it
*there* — that is what `CountingMV.visitMethodInsn` does. Because kotlin-stdlib and
exoquery-engine are themselves instrumented, their call sites are counted too, giving the full
transitive picture down to the JDK boundary.

---

## 9. Server-side changes

### 9.1 C8 — child JVM flags

`JavaExecutor.kt`. Make the timeout injectable and add the profiling flags.

```kotlin
@Component
class JavaExecutor {
  companion object {
    const val MAX_OUTPUT_SIZE = 100 * 1024
    const val EXECUTION_TIMEOUT = 10_000L
    const val PROFILE_TIMEOUT   = 60_000L      // NEW
  }

  fun execute(args: List<String>, timeoutMs: Long = EXECUTION_TIMEOUT): ProgramOutput {
    // …existing body, with EXECUTION_TIMEOUT replaced by timeoutMs at line 36…
  }
}

class CommandLineArgument(
  val classPaths: String,
  val mainClass: String?,
  val policy: Path,
  val memoryLimit: Int,
  val arguments: List<String>,
  val profiling: ProfilingFlags? = null,        // NEW
) {
  fun toList(): List<String> = (
    listOf(
      getJavaPath(),
      "-Xmx${memoryLimit}M",
      "-Djava.security.manager",
      "-Djava.security.policy=$policy",
      "-ea",
    )
      + (profiling?.jvmFlags() ?: emptyList())
      + listOf("-classpath") + classPaths + mainClass + arguments
    ).filterNotNull()
}

data class ProfilingFlags(
  val backend: String,
  val warmup: Int,
  val iterations: Int,
  val outDir: Path,
  val agentJar: Path?,
) {
  fun jvmFlags(): List<String> = buildList {
    add("-XX:TieredStopAtLevel=1")            // contract term 4
    add("-Xlog:jfr*=off")                     // keep JFR off the JSON channel
    add("-Dexo.profile.backend=$backend")
    add("-Dexo.profile.warmup=$warmup")
    add("-Dexo.profile.iterations=$iterations")
    add("-Dexo.profile.outDir=$outDir")
    if (backend != "sample" && agentJar != null) {
      add("-javaagent:$agentJar=include=io.exoquery.,kotlin.,kotlinx.;timing=false")
    }
  }
}
```

> Flags go **before** `-classpath`. `toList()` concatenates `classPaths + mainClass + arguments`
> after the fixed prefix; anything inserted after `-classpath` would be read as a class name.

### 9.2 C9 — orchestration and read-back

`KotlinCompiler.kt`.

**Policy substitution** — `write()` gains the agent directory:

```kotlin
private fun write(classes: JvmClasses, outputDir: Path): OutputDirectory {
  val libDir = librariesFile.jvm.absolutePath
  val agentDir = librariesFile.profilerAgent.absolutePath        // NEW
  val policy = policyFile.readText()
    .replace("%%GENERATED%%", outputDir.toString().replace('\\', '/'))
    .replace("%%LIB_DIR%%", libDir.replace('\\', '/'))
    .replace("%%AGENT_DIR%%", agentDir.replace('\\', '/'))       // NEW
  // …unchanged…
}
```

**The profile entry point**, beside `run` and `test`. Note it reuses `compiled.mainClasses`
verbatim — **no new discovery code**:

```kotlin
fun profile(files: List<KtFile>, opts: ProfileOptions): JvmExecutionResult =
  execute(files, addByteCode = false) { output, compiled ->
    val userMain = when (compiled.mainClasses.size) {
      1 -> compiled.mainClasses.single()
      0 -> return@execute JvmExecutionResult(exception = IllegalArgumentException(
             "No main method found — a profiling submission is an ordinary program " +
             "whose main() calls executors.ProfileBootstrap.measure { … }"
           ).toExceptionDescriptor())
      else -> return@execute JvmExecutionResult(exception = IllegalArgumentException(
             "Multiple classes contain main methods: ${compiled.mainClasses.sorted().joinToString()}"
           ).toExceptionDescriptor())
    }

    val argv = argsFrom(
      mainClass = ProfileRunner::class.java.name,
      outputDirectory = output,
      args = listOf(userMain),
      memoryLimitMb = 256,
      profiling = ProfilingFlags(
        backend = opts.backend,
        warmup = opts.warmup,
        iterations = opts.iterations,
        outDir = output.path,
        agentJar = librariesFile.profilerAgent.toPath().resolve("profiler-agent.jar"),
      ),
    )
    javaExecutor.execute(argv, timeoutMs = JavaExecutor.PROFILE_TIMEOUT)
      .asProfileResult()
  }
```

**Read-back**, inside `execute`'s `usingTempDirectory` block — the `addByteCode` pattern
verbatim, and it must happen before the temp dir is deleted. One assembly pass per block:

```kotlin
block(output, compilationResult.result).also {
  it.addWarnings(compilationResult.compilerDiagnostics)
  if (addByteCode) it.addByteCode(compilationResult.result)
  if (it is JvmExecutionResult && it.profile != null) {
    it.profile = ProfileAssembler.assemble(it.profile!!, outputDir)
  }
}
```

`ProfileAssembler` locates `profile-instrument-<name>.json` and `profile-sample-<name>.jfr`
per block name and merges them into the response envelope.

`JvmClasses` is **unchanged**.

### 9.3 C10 — JFR fold

New file `src/main/kotlin/com/compiler/server/compiler/components/JfrFold.kt`. Runs in the
**parent** JVM; `jdk.jfr.consumer` is part of JDK 17, so no new dependency.

Weight each sample by its **leaf** frame — that is self time. Total-time attribution walks the
whole stack instead.

```kotlin
package com.compiler.server.compiler.components

import jdk.jfr.consumer.RecordingFile
import java.nio.file.Path

private val HARNESS_PREFIXES = listOf(
  "executors.", "java.lang.reflect.", "jdk.internal.reflect.",
  "jdk.jfr.", "io.exoquery.profiler.",
)

data class FoldedFrame(val frame: String, val self: Int, val total: Int)

object JfrFold {
  fun fold(jfr: Path): Pair<List<FoldedFrame>, Int> {
    val self = HashMap<String, Int>()
    val total = HashMap<String, Int>()
    var samples = 0

    RecordingFile(jfr).use { rf ->
      while (rf.hasMoreEvents()) {
        val e = rf.readEvent()
        if (e.eventType.name != "jdk.ExecutionSample") continue
        val frames = e.stackTrace?.frames ?: continue

        val named = frames
          .map { f -> "${f.method.type.name}.${f.method.name}" }
          .filterNot { n -> HARNESS_PREFIXES.any(n::startsWith) }
        if (named.isEmpty()) continue

        samples++
        self.merge(named.first(), 1, Int::plus)              // leaf == self time
        named.distinct().forEach { total.merge(it, 1, Int::plus) }
      }
    }

    val out = (self.keys + total.keys).distinct().map {
      FoldedFrame(it, self[it] ?: 0, total[it] ?: 0)
    }.sortedByDescending { it.self }
    return out to samples
  }
}
```

### 9.4 C11 — response envelope

`ExecutionResult.kt`. Mirrors how `jvmByteCode` hangs off `JvmExecutionResult`, and mirrors the
`analyzeHibernateQueries` envelope so the agent meets one idiom.

```kotlin
open class JvmExecutionResult(
  compilerDiagnostics: CompilerDiagnostics = CompilerDiagnostics(),
  exception: ExceptionDescriptor? = null,
  var jvmByteCode: String? = null,
  var profile: ProfileReport? = null,          // NEW
) : ExecutionResult(compilerDiagnostics, exception)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ProfileReport(
  val status: String,                    // "ok" | "compileError"
  val backend: String,
  val jitTier: String = "c1",
  val output: String = "",               // captured stdout
  val blocks: List<Block> = emptyList(),
  val timings: Timings,                  // whole-request, like analyzeHibernateQueries
  val findings: List<Finding> = emptyList(),
  val serverVersion: String? = null,
) {
  data class Timings(val compileMs: Long, val executeMs: Long)

  data class Block(
    val name: String,
    val iterations: Iterations,
    val timings: BlockTimings,
    val frames: List<Frame> = emptyList(),
    val calls: List<CallEdge> = emptyList(),
    val samples: Int? = null,
  )
  data class Iterations(val warmup: Int, val measured: Int)
  data class BlockTimings(val setupNs: Long, val firstCallNs: Long, val steadyNsPerIter: Long)
  data class Frame(
    val frame: String,
    val selfPct: Double,
    val totalPct: Double,
    val count: Long? = null,          // null for backend "sample"
    val confidence: String? = null,   // only when backend == "both"
  )
  data class CallEdge(val caller: String, val callee: String, val count: Long)

  /** Same shape as analyzeHibernateQueries findings: actionable, not a bare string. */
  data class Finding(
    val kind: String,
    val block: String? = null,
    val detail: String,
    val hint: String,
  )
}
```

**Two reconciliation rules for `ProfileAssembler`:**

1. **`count` is instrumentation-only.** Sampling cannot count invocations. Emit `null`, never a
   fabricated estimate.
2. **Roll up unreachable frames.** Sampling sees `java.lang.StringCoding.*`; instrumentation
   cannot reach inside the JDK. Attribute those samples to the nearest instrumented ancestor so
   rows line up. **Without this rule the schemas match but the numbers do not.**

Cap `frames` and `calls` at **top 25 per block**. **[DECISION]** Output size is a product
requirement, not a detail.

### 9.5 C12 — endpoint

`CompilerRestController.kt`:

```kotlin
@PostMapping("/profile")
fun profileKotlinProjectEndpoint(
  @RequestBody project: Project,
  @RequestParam(defaultValue = "instrument") backend: String,
  @RequestParam(defaultValue = "5000") warmup: Int,
  @RequestParam(defaultValue = "20000") iterations: Int,
): ExecutionResult =
  kotlinProjectExecutor.profile(project, ProfileOptions(backend, warmup, iterations))
```

Validate `backend ∈ {instrument, sample, both}` and clamp `warmup` / `iterations` to sane
maxima — the caller is a language model and will eventually send `iterations=100000000`.

### 9.6 Response example

```jsonc
{
  "status": "ok",
  "backend": "instrument",
  "jitTier": "c1",
  "output": "",
  "timings": { "compileMs": 1840, "executeMs": 940 },
  "blocks": [
    {
      "name": "filter-then-map",
      "iterations": { "warmup": 5000, "measured": 20000 },
      "timings": {
        "setupNs": 17004312,      // main() entry -> measure() entry
        "firstCallNs": 5301887,   // first lambda call - catches memoization
        "steadyNsPerIter": 236612
      },
      "frames": [
        { "frame": "io.exoquery.SqlCompiler.build",
          "selfPct": 70.4, "totalPct": 92.1, "count": 20000 }
      ],
      "calls": [
        { "caller": "io.exoquery.SqlCompiler.build",
          "callee": "java.lang.StringBuilder.append", "count": 480000 }
      ]
    }
  ],
  "findings": [],
  "errors": {}
}
```

**Findings the agent can act on** — each carries a `hint`, following the
`analyzeHibernateQueries` convention:

| `kind` | Trigger | `hint` |
|---|---|---|
| `noMeasureBlock` | `main` never called `measure` | Shows the contract snippet |
| `bodyOptimizedAway` | `Blackhole.touched` false for a block | "Pass the result to Blackhole.consume(…)" |
| `insufficientSamples` | backend S produced < 100 samples | "Raise iterations, or use backend=instrument" |
| `backendDisagreement` | `both` mode, frames differ beyond threshold | "Magnitude is unreliable for this frame; ranking still holds" |
| `firstCallDominates` | `firstCallNs` ≫ `steadyNsPerIter` | "Memoization inside the block; steady profile is partial" |
| `unreliableClocksource` | host not on `tsc` | "Counts are valid; ignore times" |
| `multiBlockOrdering` | more than one block present | "Blocks share JIT state; use one block per request for head-to-head" |

---

## 10. Task list

Ordered. Each step is independently testable; do not start a step until the previous one's
acceptance criteria pass.

### P0 — Measure the latency budget (½ day) — **do this first**

> Throughput is the entire value proposition, and the strong prior is that **the Kotlin compile
> dominates, not the profiled execution**. The `analyzeHibernateQueries` docs show a `timings`
> block with `compileMs` in the ~1.8 s range for a comparable workload — illustrative rather
> than measured here, but it means a sibling tool already tracks exactly this split and puts
> compile in seconds, not milliseconds. If that holds, the backend choice barely matters and
> the real work is compile caching or warm-environment reuse. **It also decides how much the
> named-blocks feature (§3) is worth** — amortizing a 2 s compile over four variants is a
> large win; amortizing a 50 ms compile is not.

1. Instrument `KotlinCompiler.execute` with timers around `compile()`, `write()`, and
   `javaExecutor.execute()`.
2. Run a representative ExoQuery snippet 20 times; report the three medians.

**Acceptance:** a per-run latency breakdown is recorded in this document. If `compile()` is
> 70% of wall time, open a separate task for compile caching and raise the priority of named
blocks.

### P1 — Contract, plumbing, three timings (1 day)

1. `:executors`: `Blackhole` (§7.1), `ProfileOutput` (§7.4), `Probes` stub, `JfrWindow` stub,
   `ProfileBootstrap` (§7.2) with backend hooks no-oped, `ProfileRunner` (§7.3).
2. `KotlinCompiler`: `profile()` entry point (§9.2), policy substitution.
3. `JavaExecutor`: `timeoutMs` parameter, `ProfilingFlags` with `-XX:TieredStopAtLevel=1`, the
   `-D` properties and the raised heap (§9.1).
4. `ExecutionResult`: `ProfileReport` with `blocks[].timings` populated, `frames` empty (§9.4).
5. `CompilerRestController`: `/profile` (§9.5).

**Acceptance:** `POST /api/compiler/profile` on a program whose `main` calls `measure { … }`
returns per-block timings. A program with no `measure` call returns a `noMeasureBlock` finding.
An empty block returns `bodyOptimizedAway`. **Two named blocks in one submission return two
entries.**

**Already useful on its own** — `firstCallNs` vs `steadyNsPerIter` answers real questions about
memoization with no frame data at all.

### P2 — Backend S, sampling (1–2 days)

1. `:executors`: real `JfrWindow` (§7.2), one window per block.
2. `executor.policy`: `FlightRecorderPermission`, write grant, `PropertyPermission` (§6.2).
3. `ProfilingFlags`: `-Xlog:jfr*=off`.
4. Server: `JfrFold` (§9.3) and the `ProfileAssembler` sampling path.

**Acceptance:** `?backend=sample` returns populated `frames` per block; no `jdk.jfr.*` or
`executors.*` frames appear; `samples` > 100 for a block doing ≥ 1 µs of work; setup work does
not appear in `frames`.

### P3 — Backend I, instrumentation agent (3–5 days)

1. New `:profiler-agent` module and Gradle wiring (§6.4). **Verify the jar contains inner
   classes** before anything else.
2. `Agent`, `Config`, `Probe` (§8.1–8.2).
3. `ProfilingTransformer`, **counting only** — `CountingMV`, `COMPUTE_MAXS` (§8.3).
4. `executor.policy`: agent codeBase grant. `ProfilingFlags`: `-javaagent:`.
5. `ProfileAssembler` instrumentation path.
6. **Only then** add `TimingMV` and `COMPUTE_FRAMES` behind `timing=true`.

**Acceptance after step 3:** all 580 corpus snippets still compile, run, and produce identical
output with the agent attached (§11.1). **After step 5:** `calls` contains
`java.lang.StringBuilder.append` edges attributed to ExoQuery callers. **After step 6:**
`frames[].selfPct` is populated and the §11.2 agreement test passes.

> **Ship counting before timing.** Counting needs no frame recomputation, carries most of the
> value, and cannot produce a `VerifyError`. Timing is where the complexity and risk live.

### P4 — Unified schema and confidence signal (2–3 days)

1. `ProfileAssembler`: JDK-frame roll-up, top-N cap, `count: null` for sampling.
2. `backend=both`: run once per backend, join on frame key per block, emit `confidence` and the
   `backendDisagreement` finding.
3. All findings from §9.6, each with its `hint`.

**Acceptance:** on the §11.2 fixture, `backend=both` reports `confidence: "high"` for every
frame and no `backendDisagreement`. A deliberately C2-compiled run produces
`backendDisagreement` on the cheap methods.

---

## 11. Validation

### 11.1 The regression suite you already own

**580 Kotlin snippets** in `src/test/resources/test-compile-data/jvm`, already driven by
`ResourceCompileTest` and `ResourceE2ECompileTest`. Run every one through the instrumenting
agent and assert they still compile, still run, and produce **byte-identical output**.

For bytecode rewriting this is an unusually strong safety net, and it is the single biggest
reason instrumentation is a defensible choice here rather than a risky one.

```kotlin
class InstrumentedCompileTest : BaseExecutorTest(), BaseResourceCompileTest {
  override fun request(code: String, platform: ProjectType) = runWithAgent(code)

  @Test
  fun `agent does not alter program behaviour`() {
    checkResourceExamples(listOf(testDirJVM)) { _, code ->
      val plain = run(code, "")
      val agented = runWithAgent(code)
      assertEquals(plain.text, agented.text)          // identical stdout
      assertNull(agented.exception)
    }
  }
}
```

Note these snippets have plain `main`s and no `measure` call — that is the point. The agent must
be transparent to arbitrary programs, not just contract-conforming ones.

### 11.2 Backend agreement test

Port the four-method fixture from §5 into the test suite: known cost ratios, both backends,
assert agreement within **1 pp** under C1.

**This test is what protects contract term 4** from being "optimized away" by a future
contributor who does not know why `-XX:TieredStopAtLevel=1` is there. Name it accordingly and
put the reason in a comment.

```kotlin
@Test
fun `both backends agree within 1pp under C1`() {
  val i = profile(FIXTURE, backend = "instrument").profile!!.blocks.single()
  val s = profile(FIXTURE, backend = "sample").profile!!.blocks.single()
  listOf("heavy", "medium", "light").forEach { m ->
    val a = i.frames.first { it.frame.endsWith(".$m") }.selfPct
    val b = s.frames.first { it.frame.endsWith(".$m") }.selfPct
    assertTrue(abs(a - b) < 1.0, "$m: instrument=$a sample=$b")
  }
}
```

### 11.3 Determinism test

Reproducibility is a **product guarantee** for the agent consumer, so it needs a test.

```kotlin
@Test
fun `instrumentation counts are byte-identical across runs`() {
  val runs = (1..10).map { profile(FIXTURE, backend = "instrument").profile!! }
  val first = runs.first().blocks.single().calls.associate { (it.caller to it.callee) to it.count }
  runs.drop(1).forEach { r ->
    assertEquals(first, r.blocks.single().calls.associate { (it.caller to it.callee) to it.count })
  }
}
```

Steady-state timings will not be bit-identical; assert within a tolerance (≤ 5% relative).

### 11.4 Contract tests

- A `main` with no `measure` call → `noMeasureBlock` finding, `status: "ok"`.
- Two `measure` calls with the same name → clean error, not a crash.
- A block whose lambda throws → that block reports the error, other blocks still report.
- A block that never touches `Blackhole` → `bodyOptimizedAway`.

---

## 12. Risks and open questions

### Unresolved

- **Per-run latency budget is unmeasured here.** P0 closes it. It is the highest-value half-day
  in the plan, and it also sets the value of named blocks.
- **Library-internal memoization inside a block is detectable but not attributable.** The
  `firstCallNs` / `steadyNsPerIter` gap reveals that it happened; it does not say where.
  Attributing it requires instrumenting the first call specifically — counts are exact
  regardless of duration, so backend I can answer this where backend S structurally cannot.
  The strongest argument for eventually running `both`.
- **Cross-block JIT interference is unquantified.** §3 documents the caveat and recommends one
  block per request for head-to-head comparisons, but nobody has measured how large the effect
  actually is under C1. A good follow-up experiment: run the same block as block 1 and as
  block 2 and diff.

### Known and accepted

- **Kotlin inline functions carry SMAP line numbers** from the callee's source file (JSR-45
  `SourceDebugExtension`). Method-level attribution is unaffected; if line-level attribution is
  ever added, lines beyond the user file's length must be clamped or dropped.
- **Lambdas become synthetic classes** (`MainKt$main$1`) and **`suspend` functions become state
  machines**. Frame names will not always resemble the source — and note the `measure { … }`
  lambda itself is one of these. Demangle for display; there is no cheap fix for coroutines.
- **C1-pinned numbers are not production-representative.** Deliberate; state it in the response.
- **Probe id space is capped at 65 536.** A very large classpath could exhaust it. `Probe`
  throws on exhaustion; the transformer's `catch (Throwable)` turns that into "this class goes
  uninstrumented," which degrades rather than breaks. Raise `MAX` if it ever fires.

---

## Appendix A — reproducing the measurements

Every **[MEASURED]** claim came from a standalone probe run against a replica of the sandbox.

**Sandbox permission probes (§6.1).** A Java class attempting each operation inside
`try`/`catch (Throwable)`, printing allowed/denied:

```bash
java -Xmx32M \
  -Djava.security.manager \
  -Djava.security.policy=<replica.policy> \
  -ea -cp <dir> Probe
```

where `replica.policy` grants only `FilePermission "<dir>", "read"` and `"<dir>/*", "read"` —
mirroring the `grant {}` block at `executor.policy:22`.

**JFR stdout corruption.** Run anything with `-XX:StartFlightRecording=filename=x.jfr` and
observe `[…][info][jfr,startup]` on **stdout**, not stderr. `-Xlog:jfr*=off` silences it.

**Sample density.** `jfr summary <file>.jfr | grep ExecutionSample`.

**Backend agreement (§5.1).** Build two copies of the same four-method target — one plain, one
with hand-written `System.nanoTime()` probes in the exact shape `TimingMV` emits. Run the plain
copy under a programmatic JFR window and the probed copy with accumulator arrays, both with
setup / warmup / N. Compare self-time percentages. Repeat with and without
`-XX:TieredStopAtLevel=1`; the divergence appears only without it.

**Probe cost (§5.3).** Time a loop of `System.nanoTime()` calls; compare `-Xint` against
default. Predicted probe cost is exactly `2 ×` the per-call figure.

**Classloading (§8.4).** An agent whose transformer merely counts invocations and reports
`loader == null` vs non-null at shutdown, compared against `-Xlog:class+load=info | wc -l`.

**Compile-classpath claim (§2).** `grep -n destinationDirectory executors/build.gradle.kts`
and `grep -n 'kotlinEnvironment.classpath' KotlinCompiler.kt` — line 96 is the compile
invocation, line 183 the runtime one.

---

## Appendix B — effort summary

| Phase | Scope | Estimate |
|---|---|---:|
| P0 | Measure per-run latency breakdown | ½ day |
| P1 | Contract, plumbing, per-block timings | 1 day |
| P2 | Backend S — sampling | 1–2 days |
| P3 | Backend I — instrumentation agent | 3–5 days |
| P4 | Unified schema, confidence signal | 2–3 days |
| | **Total** | **8–12 days** |

**If only one backend ships, ship P1 + P3 (instrumentation).** It answers the original
question — what low-level calls does my code cause — degrades gracefully rather than silently on
short programs, and its numbers are reproducible, which is what the agent consumer needs most.
Sampling is the better second addition, not the better first.
