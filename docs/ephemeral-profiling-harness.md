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
project or the design discussion that produced it. Read §1–§3 for what you are building and
why, §4–§6 for the constraints you must respect, §7–§9 for the code, and §10 for the ordered
task list with acceptance criteria.

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
| **Machine-readable diagnostics** | An agent will write bodies that get dead-code-eliminated. `"warning": "body_optimized_away"` is worth as much as the timing. |

### The ephemerality is already free

The existing architecture is already stateless. `usingTempDirectory` creates a UUID-named
directory and `deleteRecursively`s it in a `finally`; every run compiles into a fresh temp dir
and executes in a fresh child JVM with a freshly written policy file. There is no persistent
state to discard because there is none. **This is why this repository is the right host** for
the idea rather than a long-lived benchmark service. You are adding a mode, not an
architecture.

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

### Module layout

| Module | Produces | Notes |
|---|---|---|
| root | Spring Boot server | |
| `:common` | `component.KotlinEnvironment` | shared with `:indexation` |
| `:executors` | `executors.jar` → **`libJVMFolder`** | on the child JVM's `-cp`; has policy grants |
| `:indexation` | `indexes*.json` | completion indexes |
| `:dependencies` | populates `2.1.20/`, `2.1.20-compiler-plugins/`, … | copy tasks only |

`:executors` is the important one: its jar lands in `libJVMFolder` (`2.1.20/`), which is
exactly the directory `KotlinEnvironmentConfiguration` globs to build the child classpath. New
child-side harness code belongs there.

### Files you will touch

| Location | What it is |
|---|---|
| `KotlinCompiler.kt:55` | `addByteCode()` — **the precedent for parent-side result enrichment. Copy this pattern.** |
| `KotlinCompiler.kt:92` | `compile()` — builds the `K2JVMCompiler` argument list |
| `KotlinCompiler.kt:129` | `findMainClasses()` — existing ASM scan. Extend for contract discovery. |
| `KotlinCompiler.kt:139` | `execute()` — orchestration; enrichment hook at line 150 |
| `KotlinCompiler.kt:177` | `argsFrom()` — builds `CommandLineArgument` |
| `KotlinCompiler.kt:190` | `memoryLimit = 32`, hardcoded |
| `JavaExecutor.kt:19` | `MAX_OUTPUT_SIZE = 100 * 1024` |
| `JavaExecutor.kt:20` | `EXECUTION_TIMEOUT = 10000L` — a `const val`; must become a parameter |
| `JavaExecutor.kt:57` | Exceeding the output cap **discards all output** |
| `JavaExecutor.kt:73` | `destroy()` — the race that constrains dump ordering |
| `JavaExecutor.kt:113` | `CommandLineArgument.toList()` — the one place child-JVM flags are built |
| `JavaRunnerExecutor.kt:27` | `mainMethod.invoke` — the invocation the harness replaces |
| `JavaRunnerExecutor.kt:47` | `defaultOutputStream.print(...)` — the JSON channel |
| `JavaRunnerExecutor.kt:56` | `RunOutput` — child→parent DTO (Jackson both sides) |
| `FailureSerializers.kt:12` | `executors.mapper` — an `ObjectMapper` already in `:executors`. Reuse it. |
| `executor.policy:22` | The default `grant {}` block |
| `ExecutionResult.kt` | `JvmExecutionResult`, already carries optional `jvmByteCode` |
| `ProgramOutput.kt` | `asExecutionResult()` — deserializes child stdout |
| `CompilerRestController.kt` | `/run` with `addByteCode` — precedent for a `profile` param |
| `buildSrc/src/main/kotlin/properties.kt` | folder-name constants |
| `build.gradle.kts:100` | `generateProperties()` |
| `build.gradle.kts:157` | `buildLambda` packaging |
| `Dockerfile` | **has a bug — see §6.5** |
| `src/test/resources/test-compile-data/jvm` | **580 Kotlin snippets** — your regression suite |

---

## 3. The contract (normative)

The submitted snippet exposes two zero-argument static entry points instead of a `main`.
This single shape serves both backends without modification — that is the central claim of
this design, and §5 is the evidence.

```kotlin
import executors.Blackhole

object Profile {
  private lateinit var data: List<Person>

  // Runs ONCE. Excluded from all measurement.
  // Class init, <clinit>, cache warming, fixture construction.
  @JvmStatic fun setup() {
    data = (1..1000).map { Person(it, "n$it") }
  }

  // The measured unit of work. Must be safe to call repeatedly.
  // Must consume its result via Blackhole or it may be optimized away.
  @JvmStatic fun body() {
    Blackhole.consume(capture { Table<Person>().filter { it.age > 42 } }.buildFor.Postgres())
  }
}
```

### Execution timeline

```
        |<-- excluded -->|<- timed ->|<--- excluded --->|<===== MEASUREMENT WINDOW =====>|
        +----------------+-----------+------------------+--------------------------------+
        |    setup()     | body() #1 |   body() x W     |          body() x N            |
        |     once       |   alone   |     warmup       |   sampled OR instrumented      |
        +----------------+-----------+------------------+--------------------------------+
                |              |                        ^                                |
                v              v                        |                                v
            setupNs      firstCallNs            arm/recording start          dump -> parent folds

Three timings are reported separately, so one-time cost is never averaged into invisibility.
```

### Normative terms

All six are required. Terms 1–3 make profiling *correct*; terms 4–6 make the two backends
*interchangeable*.

1. **`setup()` / `body()` split.** Initialization excluded from the window by construction.
2. **Warmup iterations** (`W`) run before the window opens.
3. **`N` iterations** run inside the window.
4. **Pinned JIT tier: `-XX:TieredStopAtLevel=1`.** **[MEASURED]** The load-bearing term; §5.1.
5. **A blackhole sink** so bodies cannot be dead-code-eliminated.
6. **A fixed harness-frame filter**, applied identically by both backends:
   `executors.*`, `java.lang.reflect.*`, `jdk.internal.reflect.*`, `jdk.jfr.*`,
   `io.exoquery.profiler.*`.

### Defaults

| Parameter | Default | Notes |
|---|---:|---|
| `W` (warmup) | 5 000 | **[DECISION]** enough for C1 to compile the body |
| `N` (measured) | 20 000 | tune against P0 latency measurement |
| `backend` | `instrument` | §5.3 |
| heap | 256 MB | 32 MB is too tight |
| timeout | 60 s | 10 s is too tight for a profiled run |

---

## 4. Component inventory

Everything you will create or modify, in one table. Nothing else is required.

| # | Component | Module | Kind | §  |
|---|---|---|---|---|
| C1 | `Blackhole` | `:executors` | new | 7.1 |
| C2 | `ProfileHarness` | `:executors` | new | 7.2 |
| C3 | `ProfileResult` DTOs | `:executors` | new | 7.3 |
| C4 | `Agent` (premain) | `:profiler-agent` | new module | 8.1 |
| C5 | `Probe` (counters + dump) | `:profiler-agent` | new | 8.2 |
| C6 | `ProfilingTransformer` (ASM) | `:profiler-agent` | new | 8.3 |
| C7 | Contract discovery | `KotlinCompiler.kt` | modify | 9.1 |
| C8 | Child JVM flags | `JavaExecutor.kt` | modify | 9.2 |
| C9 | Orchestration + profile read-back | `KotlinCompiler.kt` | modify | 9.3 |
| C10 | JFR fold | new server file | new | 9.4 |
| C11 | Server-side schema | `ExecutionResult.kt` | modify | 9.5 |
| C12 | Endpoint | `CompilerRestController.kt` | modify | 9.6 |
| C13 | Build wiring | Gradle, `properties.kt`, Docker | modify | 6.4–6.5 |
| C14 | Policy grants | `executor.policy` | modify | 6.2 |

---

## 5. Evidence

Every table here was produced by running both backends against an identical
`setup()`/`body()`×N contract over a target with a known cost distribution: four methods
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

### 5.2 Why the split, and not "just run main N times"

Naive amplification fixes sample density but silently destroys the thing you most want to see.
Modelling the ExoQuery pattern — an expensive step memoized on first call, then cheap
per-execution work **[MEASURED]**:

| Run shape | Wall time | Samples | In the memoized setup |
|---|---:|---:|---|
| N = 1 | 63 ms | 6 | 2 of 6 — **33%** |
| N = 500 | 180 ms | 105 | **0 — erased** |

A third of the single-run profile became *zero*. Not reduced — erased. For a 10 ms snippet,
one-time work (classloading, `<clinit>`, serializer descriptor construction, memoized query
compilation) often *is* the runtime.

The split reports the three costs separately instead of conflating them. **[MEASURED]** on the
same target:

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
> isolation exceeds its cost in situ. Under C1 the raw numbers are good enough that
> compensation is optional. **Ship with compensation off; add it behind a flag.**

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
`%%AGENT_DIR%%` to that substitution (see §9.3).

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
};
```

> **Why both grants.** `AccessController` checks *every* protection domain on the stack. When
> `ProfileHarness` (executors.jar) calls `Probe.dump()` (agent jar), both domains must permit
> the write. Granting `executors.jar` write on `%%GENERATED%%/-` covers it without
> `doPrivileged` gymnastics. If you prefer to keep `executors.jar` minimal, wrap the write in
> `AccessController.doPrivileged` inside `Probe` instead — the agent jar has `AllPermission`,
> so the stack walk stops there.

### 6.3 Child JVM flags (C8)

```
-XX:TieredStopAtLevel=1                     contract term 4 — makes backends comparable
-Xlog:jfr*=off                              keeps JFR logging off the JSON channel
-javaagent:<agentDir>/profiler-agent.jar    backend "instrument" only
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
> `from(configurations…)` block plus a normal `jar` task handles this; a hand-rolled
> `jar cfm` with an explicit class list does not. **[MEASURED]** — this exact failure occurred
> during prototyping.

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
- **`EXECUTION_TIMEOUT = 10000L`** is a `const val` and must become a parameter (§9.2).
- **SecurityManager is deprecated** (JEP 411), throws on JDK 24+. Fine at JDK 17; the debt is
  inherited by anything leaning on policy grants.
- **Clocksource is an ops risk.** Verified `tsc` here. On a host stuck on `xen`, `hpet` or
  `acpi_pm`, `nanoTime()` costs 500 ns–1 µs and every overhead number in §5.3 inverts. Check
  `/sys/devices/system/clocksource/clocksource0/current_clocksource` in the deployed container;
  if it is not `tsc`, refuse timing and return counts only with a
  `"warning": "unreliable_clocksource"`.

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

  /** Set true by the first consume(); read by the harness to detect an empty body. */
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

### 7.2 C2 — `ProfileHarness`

`executors/src/main/kotlin/ProfileHarness.kt`

This replaces `JavaRunnerExecutor` as the child's main class for profiled runs. It implements
the timeline in §3 exactly.

```kotlin
package executors

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.Method
import java.nio.file.Path

/**
 * argv: <className> <backend> <warmup> <iterations> <outDir>
 *   backend: "instrument" | "sample" | "both"
 */
class ProfileHarness {
  companion object {
    private val outputStream = ByteArrayOutputStream()

    @JvmStatic
    fun main(args: Array<String>) {
      val real = System.out
      try {
        // Capture user stdout/stderr exactly as JavaRunnerExecutor does, so the
        // JSON channel stays clean.
        System.setOut(PrintStream(OutStream(outputStream)))
        System.setErr(PrintStream(ErrorStream(outputStream)))

        val cls = Class.forName(args[0])
        val backend = args[1]
        val warmup = args[2].toInt()
        val n = args[3].toInt()
        val outDir = Path.of(args[4])

        val setup: Method = cls.getMethod("setup")
        val body: Method = cls.getMethod("body")

        // ---- 1. setup: once, excluded ----------------------------------
        val t0 = System.nanoTime()
        setup.invoke(null)
        val setupNs = System.nanoTime() - t0

        // ---- 2. first body call: timed alone ---------------------------
        val t1 = System.nanoTime()
        body.invoke(null)
        val firstCallNs = System.nanoTime() - t1

        // ---- 3. warmup: excluded ---------------------------------------
        repeat(warmup) { body.invoke(null) }

        // ---- 4. arm backends, then the measured window -----------------
        val jfr = if (backend == "sample" || backend == "both")
          JfrWindow.start() else null
        if (backend == "instrument" || backend == "both") Probes.arm()

        val t2 = System.nanoTime()
        repeat(n) { body.invoke(null) }
        val steadyNs = System.nanoTime() - t2

        // ---- 5. disarm and dump BEFORE printing JSON -------------------
        if (backend == "instrument" || backend == "both") {
          Probes.disarm()
          Probes.dump(outDir.resolve("profile-instrument.json").toString())
        }
        jfr?.stopAndDump(outDir.resolve("profile-sample.jfr"))

        System.out.flush(); System.err.flush()

        val warnings = buildList {
          if (!Blackhole.touched) add("body_optimized_away")
          if (steadyNs / n < 1_000L) add("body_below_timer_resolution")
        }

        val result = ProfileOutput(
          text = synchronized(outputStream) { outputStream.toString() }
            .replace("</errStream><errStream>".toRegex(), "")
            .replace("</outStream><outStream>".toRegex(), ""),
          setupNs = setupNs,
          firstCallNs = firstCallNs,
          steadyNsTotal = steadyNs,
          iterations = n,
          warmup = warmup,
          backend = backend,
          warnings = warnings,
        )
        real.print(mapper.writeValueAsString(result))
      } catch (e: Throwable) {
        real.println(mapper.writeValueAsString(ProfileOutput(exception = unwrap(e))))
      }
    }

    private fun unwrap(e: Throwable): Throwable =
      if (e is java.lang.reflect.InvocationTargetException) e.cause ?: e else e
  }
}
```

**Reflective bridge to the agent.** `Probes` isolates the reflective lookup so `:executors` has
no compile-time dependency on `:profiler-agent`. The agent jar is appended to the system class
path by the JVM, so `Class.forName` resolves it when the agent is loaded and fails cleanly when
it is not.

```kotlin
package executors

internal object Probes {
  private val probe: Class<*>? = runCatching {
    Class.forName("io.exoquery.profiler.Probe")
  }.getOrNull()

  fun arm()    { probe?.getMethod("arm")?.invoke(null) }
  fun disarm() { probe?.getMethod("disarm")?.invoke(null) }
  fun dump(path: String) {
    probe?.getMethod("dump", String::class.java)?.invoke(null, path)
  }
}
```

**The JFR window.** Programmatic, scoped to the measured loop only.

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

> **[TRAP] Ordering.** `dump()` must finish **before** `real.print(...)`. `JavaExecutor` stops
> reading as soon as both stream futures complete and then calls `destroy()`
> (`JavaExecutor.kt:73`); a `dumponexit=true` shutdown hook races that and loses the recording.
>
> **[TRAP] Never use `-XX:StartFlightRecording`.** It writes
> `[0.569s][info][jfr,startup] Started recording 1…` to **stdout**, corrupting the JSON that
> `ProgramOutput.asExecutionResult()` parses — every run becomes an exception descriptor. It
> also records JVM startup, so **[MEASURED]** most samples land in
> `sun.security.provider.PolicyFile.*` and JFR's own init rather than user code. The
> programmatic window plus `-Xlog:jfr*=off` fixes both — verified clean.

**[MEASURED]** With the window opened after setup and warmup: **396 samples, 377 (95%) in
`body()`, zero leaked from `setup()`.** The remaining ~19 were in JFR's own `Recording.start`,
removed by the harness-frame filter.

### 7.3 C3 — child→parent DTO

`executors/src/main/kotlin/ProfileOutput.kt`

Field names must match the server-side deserialization target. Jackson is already configured in
`FailureSerializers.kt:12` (`executors.mapper`) with a `Throwable` serializer — reuse it.

```kotlin
package executors

data class ProfileOutput(
  var text: String = "",
  var exception: Throwable? = null,
  var setupNs: Long = 0,
  var firstCallNs: Long = 0,
  var steadyNsTotal: Long = 0,
  var iterations: Int = 0,
  var warmup: Int = 0,
  var backend: String = "",
  var warnings: List<String> = emptyList(),
)
```

---

## 8. The instrumentation agent (`:profiler-agent`)

Java, not Kotlin — `premain` and ASM callbacks are simpler without the Kotlin runtime, and the
agent must not drag `kotlin-stdlib` onto the child's system class path where it could shadow
the copy under test.

### 8.1 C4 — `Agent`

`profiler-agent/src/main/java/io/exoquery/profiler/Agent.java`

```java
package io.exoquery.profiler;

import java.lang.instrument.Instrumentation;

public final class Agent {
  public static void premain(String args, Instrumentation inst) {
    Config cfg = Config.parse(args);          // include=…,timing=true|false
    Probe.init(cfg.timing);
    inst.addTransformer(new ProfilingTransformer(cfg), false);
  }
}
```

`Config` parses the agent argument string
(`-javaagent:profiler-agent.jar=include=io.exoquery.,kotlin.,kotlinx.;timing=false`) into an
include-prefix list and a timing flag.

**Scope.** **[DECISION]** Instrument `io.exoquery.`, `kotlin.`, `kotlinx.`, `com.github.vertical_blank.`
and the user's own classes. Always skip `java.`, `jdk.`, `sun.`, `executors.`,
`io.exoquery.profiler.`, and any class whose defining loader is `null` (bootstrap).

### 8.2 C5 — `Probe`

`profiler-agent/src/main/java/io/exoquery/profiler/Probe.java`

The hot path must be allocation-free and branch-predictable. Arrays indexed by a dense int id
assigned at transform time; names held in a parallel array and only touched at dump.

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

  /** Gate so warmup iterations are not counted. Read on every probe. */
  public static volatile boolean armed = false;

  private static final String[] entryName = new String[MAX];
  private static final String[] callerName = new String[MAX];
  private static final String[] calleeName = new String[MAX];
  private static final AtomicInteger nextEntry = new AtomicInteger();
  private static final AtomicInteger nextCall  = new AtomicInteger();
  private static boolean timing = false;
  private static double probeNs = 0.0;

  static void init(boolean withTiming) {
    timing = withTiming;
    probeNs = calibrate();
  }

  // ---- registration, called from the transformer at class-load time ----
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

  // ---- hot path: referenced symbolically by injected bytecode ----------
  public static void hit(int id)  { if (armed) entryCount[id]++; }
  public static void call(int id) { if (armed) callCount[id]++; }
  public static long enter()      { return armed ? System.nanoTime() : 0L; }
  public static void exit(int id, long t0) {
    if (armed) { entryNanos[id] += System.nanoTime() - t0; entryCount[id]++; }
  }

  // ---- lifecycle, called reflectively by ProfileHarness ----------------
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

> **Why a raw hand-written JSON writer.** The agent must not pull Jackson onto the child's
> system class path, where it could shadow the copy the user's code sees. The output is a flat
> two-array structure; a 30-line writer is the correct amount of machinery.

### 8.3 C6 — `ProfilingTransformer`

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
      // COMPUTE_MAXS is sufficient for counting-only insertion: we add no
      // branches, so existing StackMapTable entries remain valid.
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

  // ------------------------------------------------------------------
  private static final class CV extends ClassVisitor {
    private final String owner; private final Config cfg;
    CV(ClassVisitor next, String owner, Config cfg) {
      super(Opcodes.ASM9, next); this.owner = owner; this.cfg = cfg;
    }
    @Override public MethodVisitor visitMethod(int access, String name, String desc,
                                               String sig, String[] exc) {
      MethodVisitor mv = super.visitMethod(access, name, desc, sig, exc);
      if (mv == null) return null;
      boolean abstractOrNative =
          (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0;
      if (abstractOrNative) return mv;
      String fq = owner.replace('/', '.') + "." + name;
      return cfg.timing
          ? new TimingMV(mv, access, name, desc, owner, fq, cfg)
          : new CountingMV(mv, owner, fq, cfg);
    }
  }

  // ---- counting only: no new branches, no frame recomputation ----------
  private static final class CountingMV extends MethodVisitor {
    private final int entryId; private final String owner, fq; private final Config cfg;
    CountingMV(MethodVisitor mv, String owner, String fq, Config cfg) {
      super(Opcodes.ASM9, mv);
      this.owner = owner; this.fq = fq; this.cfg = cfg;
      this.entryId = Probe.registerEntry(fq);
    }
    @Override public void visitCode() {
      super.visitCode();
      emit(entryId, "hit");
    }
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

  // ---- timing: entry + every return. Needs COMPUTE_FRAMES. -------------
  private static final class TimingMV extends AdviceAdapter {
    private final int entryId; private int slot = -1;
    TimingMV(MethodVisitor mv, int access, String name, String desc,
             String owner, String fq, Config cfg) {
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

  /** COMPUTE_FRAMES needs to resolve common supertypes across the app classpath. */
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
   method itself, but it does **not** catch exceptions propagating out of a nested call. For a
   profiling harness where an exception aborts the run anyway this is acceptable; if you need
   exception-safe timing, add an explicit catch-all handler via `visitTryCatchBlock` in
   `visitMaxs`. **[DECISION]** Ship without it.
2. `COMPUTE_FRAMES` with a `getClassLoader()` override is the standard fix for
   `getCommonSuperClass` failing on classes not visible to the writer's own loader. If it still
   throws `TypeNotPresentException` for a class, the outer `catch (Throwable)` returns `null`
   and that class simply goes uninstrumented — degrade, never break.

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

> **[TRAP] Do not post-process the compiler output map.** The obvious implementation —
> transforming the `Map<String, ByteArray>` that `KotlinCompiler.compile()` already produces —
> sees **only the user's own classes**, because `-no-stdlib -no-reflect` with `-d outputDir`
> means nothing else lands there. That yields invocation counts for methods the agent already
> wrote and knows about, which answers nothing. The load-time transformer is the whole point.

**Instrument the call site, not the callee.** You cannot practically transform
`java.lang.String` or `java.util.HashMap`. You do not need to: when you rewrite a method you
can see every `INVOKEVIRTUAL java/lang/StringBuilder.append` in its bytecode and count it
*there* — that is what `CountingMV.visitMethodInsn` does. Because kotlin-stdlib and
exoquery-engine are themselves instrumented, their call sites are counted too, giving the full
transitive picture down to the JDK boundary.

---

## 9. Server-side changes

### 9.1 C7 — contract discovery

`KotlinCompiler.kt`, beside `findMainClasses` (line 129). Same visitor shape, same flags.

```kotlin
private fun findProfileClasses(outputFiles: Map<String, ByteArray>): Set<String> =
  outputFiles.mapNotNull { (name, bytes) ->
    if (!name.endsWith(".class")) return@mapNotNull null
    var hasSetup = false
    var hasBody = false
    ClassReader(bytes).accept(object : ClassVisitor(ASM9) {
      override fun visitMethod(
        access: Int, name: String?, descriptor: String?, signature: String?,
        exceptions: Array<out String>?
      ): MethodVisitor? {
        val eligible = (access and ACC_PUBLIC != 0) &&
                       (access and ACC_STATIC != 0) && descriptor == "()V"
        if (eligible && name == "setup") hasSetup = true
        if (eligible && name == "body") hasBody = true
        return null
      }
    }, SKIP_CODE or SKIP_DEBUG or SKIP_FRAMES)
    if (hasSetup && hasBody) name.removeSuffix(".class").replace(File.separatorChar, '.')
    else null
  }.toSet()
```

`object Profile { @JvmStatic fun setup() }` emits a static `setup()V` on class `Profile`
alongside the instance method, so this matches. A top-level `fun setup()` in `Foo.kt` emits
`FooKt.setup()V` and also matches — **[DECISION]** accept both; document `object` as the
recommended form since it gives the user somewhere to hold fixture state.

Resolution rules, mirroring `findMainClasses`: zero matches → error
`"No setup()/body() pair found — see the profiling contract"`; more than one → error listing
the candidates.

### 9.2 C8 — child JVM flags

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

data class ProfilingFlags(val backend: String, val agentJar: Path?) {
  fun jvmFlags(): List<String> = buildList {
    add("-XX:TieredStopAtLevel=1")            // contract term 4
    add("-Xlog:jfr*=off")                     // keep JFR off the JSON channel
    if (backend != "sample" && agentJar != null) {
      add("-javaagent:$agentJar=include=io.exoquery.,kotlin.,kotlinx.;timing=false")
    }
  }
}
```

> Note the flags go **before** `-classpath`. `CommandLineArgument.toList()` currently
> concatenates `classPaths + mainClass + arguments` after the fixed prefix; inserting anywhere
> after `-classpath` would be read as a class name.

### 9.3 C9 — orchestration and read-back

`KotlinCompiler.kt`. Three edits, all mirroring `addByteCode`.

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

**The profile entry point**, beside `run` and `test`:

```kotlin
fun profile(files: List<KtFile>, opts: ProfileOptions): JvmExecutionResult =
  execute(files, addByteCode = false) { output, compiled ->
    val target = compiled.profileClasses.singleOrNull()
      ?: return@execute JvmExecutionResult(
        exception = IllegalArgumentException(
          if (compiled.profileClasses.isEmpty())
            "No setup()/body() pair found — see the profiling contract"
          else
            "Multiple profiling targets: ${compiled.profileClasses.sorted().joinToString()}"
        ).toExceptionDescriptor()
      )

    val argv = argsFrom(
      mainClass = ProfileHarness::class.java.name,
      outputDirectory = output,
      args = listOf(target, opts.backend, opts.warmup.toString(),
                    opts.iterations.toString(), output.path.toString()),
      memoryLimitMb = 256,
      profiling = ProfilingFlags(opts.backend, librariesFile.profilerAgent.toPath()
        .resolve("profiler-agent.jar")),
    )
    javaExecutor.execute(argv, timeoutMs = JavaExecutor.PROFILE_TIMEOUT)
      .asProfileResult()
  }
```

**Read-back**, inside `execute`'s `usingTempDirectory` block — this is the `addByteCode`
pattern verbatim, and it must happen before the temp dir is deleted:

```kotlin
block(output, compilationResult.result).also {
  it.addWarnings(compilationResult.compilerDiagnostics)
  if (addByteCode) it.addByteCode(compilationResult.result)
  if (it is JvmExecutionResult && it.profile != null) {
    it.profile = ProfileAssembler.assemble(
      partial   = it.profile!!,
      instrument = outputDir.resolve("profile-instrument.json").takeIf { p -> p.exists() },
      sample     = outputDir.resolve("profile-sample.jfr").takeIf { p -> p.exists() },
    )
  }
}
```

`JvmClasses` grows one field:

```kotlin
data class JvmClasses(
  val files: Map<String, ByteArray> = emptyMap(),
  val mainClasses: Set<String> = emptySet(),
  val profileClasses: Set<String> = emptySet(),     // NEW
)
```

### 9.4 C10 — JFR fold

New file `src/main/kotlin/com/compiler/server/compiler/components/JfrFold.kt`. Runs in the
**parent** JVM; `jdk.jfr.consumer` is part of JDK 17, so there is no new dependency.

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

        val named = frames.map { f ->
          "${f.method.type.name}.${f.method.name}"
        }.filterNot { n -> HARNESS_PREFIXES.any(n::startsWith) }
        if (named.isEmpty()) continue

        samples++
        self.merge(named.first(), 1, Int::plus)              // leaf == self time
        named.distinct().forEach { total.merge(it, 1, Int::plus) }
      }
    }

    val out = self.keys.plus(total.keys).distinct().map {
      FoldedFrame(it, self[it] ?: 0, total[it] ?: 0)
    }.sortedByDescending { it.self }
    return out to samples
  }
}
```

### 9.5 C11 — server-side schema

`ExecutionResult.kt`. Mirrors how `jvmByteCode` hangs off `JvmExecutionResult`.

```kotlin
open class JvmExecutionResult(
  compilerDiagnostics: CompilerDiagnostics = CompilerDiagnostics(),
  exception: ExceptionDescriptor? = null,
  var jvmByteCode: String? = null,
  var profile: ProfileReport? = null,          // NEW
) : ExecutionResult(compilerDiagnostics, exception)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ProfileReport(
  val backend: String,
  val jitTier: String = "c1",
  val iterations: Iterations,
  val timings: Timings,
  val frames: List<Frame> = emptyList(),
  val calls: List<CallEdge> = emptyList(),
  val samples: Int? = null,
  val warnings: List<String> = emptyList(),
) {
  data class Iterations(val warmup: Int, val measured: Int)
  data class Timings(val setupNs: Long, val firstCallNs: Long, val steadyNsPerIter: Long)
  data class Frame(
    val frame: String,
    val selfPct: Double,
    val totalPct: Double,
    val count: Long? = null,          // null for backend "sample"
    val confidence: String? = null,   // only when backend == "both"
  )
  data class CallEdge(val caller: String, val callee: String, val count: Long)
}
```

**`ProfileAssembler`** normalizes both backends into this shape. Two reconciliation rules:

1. **`count` is instrumentation-only.** Sampling cannot count invocations. Emit `null`, never a
   fabricated estimate.
2. **Roll up unreachable frames.** Sampling sees `java.lang.StringCoding.*`; instrumentation
   cannot reach inside the JDK. Attribute those samples to the nearest instrumented ancestor so
   rows line up. **Without this rule the schemas match but the numbers do not.**

Cap `frames` and `calls` at **top 25 by `selfPct` / `count`** before returning.
**[DECISION]** Output size is a product requirement, not a detail.

### 9.6 C12 — endpoint

`CompilerRestController.kt`, mirroring the `addByteCode` param:

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

Validate `backend ∈ {instrument, sample, both}` and clamp `warmup`/`iterations` to sane maxima
before use — the caller is a language model and will eventually send `iterations=100000000`.

### 9.7 Response example

```jsonc
{
  "backend": "instrument",
  "jitTier": "c1",
  "iterations": { "warmup": 5000, "measured": 20000 },
  "timings": {
    "setupNs": 17004312,        // one-time init - excluded from frames
    "firstCallNs": 5301887,     // body() #1 - catches memoization
    "steadyNsPerIter": 236612
  },
  "frames": [
    { "frame": "io.exoquery.SqlCompiler.build",
      "selfPct": 70.4, "totalPct": 92.1, "count": 20000 }
  ],
  "calls": [
    { "caller": "io.exoquery.SqlCompiler.build",
      "callee": "java.lang.StringBuilder.append", "count": 480000 }
  ],
  "warnings": [],
  "errors": {}
}
```

**Diagnostics the agent can act on:**

| Warning | Meaning | What the agent should do |
|---|---|---|
| `body_optimized_away` | `Blackhole.touched` false, or steady time below a floor | Add a `Blackhole.consume(...)` call |
| `insufficient_samples` | backend S produced < 100 samples | Raise `iterations`, or switch to `instrument` |
| `backend_disagreement` | `both` mode, frames differ beyond threshold | Distrust that frame's magnitude; ranking is still valid |
| `first_call_dominates` | `firstCallNs` ≫ `steadyNsPerIter` | Memoization inside `body()`; steady profile is real but partial |
| `unreliable_clocksource` | host not on `tsc` | Use counts, ignore times |

---

## 10. Task list

Ordered. Each step is independently testable; do not start a step until the previous one's
acceptance criteria pass.

### P0 — Measure the latency budget (½ day) — **do this first**

> Throughput is the entire value proposition, and the strong prior is that **the Kotlin compile
> dominates, not the profiled execution**. If compile is 2 s and the profiled run is 200 ms,
> the backend choice barely matters and the real work is compile caching or warm-environment
> reuse. This measurement can reorder everything below it.

1. Instrument `KotlinCompiler.execute` with timers around `compile()`, `write()`, and
   `javaExecutor.execute()`.
2. Run a representative ExoQuery snippet 20 times; report the three medians.

**Acceptance:** a per-run latency breakdown is recorded in this document. If `compile()` is
> 70% of wall time, open a separate task for compile caching and re-evaluate `N` defaults.

### P1 — Contract, plumbing, three timings (1 day)

1. `:executors`: add `Blackhole` (§7.1), `ProfileOutput` (§7.3), `Probes` stub, `ProfileHarness`
   (§7.2) **without** the JFR window or probe calls — timings only.
2. `KotlinCompiler`: `findProfileClasses` (§9.1), `JvmClasses.profileClasses`, `profile()`
   entry point (§9.3).
3. `JavaExecutor`: `timeoutMs` parameter, `ProfilingFlags` with only
   `-XX:TieredStopAtLevel=1` and the raised heap (§9.2).
4. `ExecutionResult`: `ProfileReport` with `timings` populated, `frames` empty (§9.5).
5. `CompilerRestController`: `/profile` (§9.6).

**Acceptance:** `POST /api/compiler/profile` on a snippet with `setup()`/`body()` returns the
three timings. A snippet without the pair returns a clear error. A snippet with an empty `body()`
returns `body_optimized_away`.

**This is already useful on its own** — `firstCallNs` vs `steadyNsPerIter` answers real
questions about memoization without any frame data.

### P2 — Backend S, sampling (1–2 days)

1. `:executors`: `JfrWindow` (§7.2), wired into `ProfileHarness`.
2. `executor.policy`: `FlightRecorderPermission` + write grant on `executors.jar` (§6.2).
3. `ProfilingFlags`: add `-Xlog:jfr*=off`.
4. Server: `JfrFold` (§9.4) and `ProfileAssembler` sampling path.

**Acceptance:** `?backend=sample` returns a populated `frames` list; no `jdk.jfr.*` or
`executors.*` frames appear; `samples` > 100 for a body doing ≥ 1 µs of work; `setup()` work
does not appear in `frames`.

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
> value, and cannot produce a `VerifyError`. Timing is where the complexity and the risk are.

### P4 — Unified schema and confidence signal (2–3 days)

1. `ProfileAssembler`: JDK-frame roll-up, top-N cap, `count: null` for sampling.
2. `backend=both`: run once per backend, join on frame key, emit `confidence` and
   `backend_disagreement`.
3. All diagnostics from §9.7.

**Acceptance:** on the §11.2 fixture, `backend=both` reports `confidence: "high"` for every
frame and no `backend_disagreement`. Injecting a deliberately C2-compiled run produces
`backend_disagreement` on the cheap methods.

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
  override fun request(code: String, platform: ProjectType) = runProfiled(code)

  @Test
  fun `agent does not alter program behaviour`() {
    checkResourceExamples(listOf(testDirJVM)) { result, code ->
      val plain = run(code, "")
      val agented = runProfiled(code)
      assertEquals(plain.text, agented.text)          // identical stdout
      assertTrue(agented.exception == null)
    }
  }
}
```

### 11.2 Backend agreement test

Port the four-method fixture from §5 into the test suite: known cost ratios, both backends,
assert agreement within **1 pp** under C1.

**This test is what protects contract term 4** from being "optimized away" by a future
contributor who does not know why `-XX:TieredStopAtLevel=1` is there. Name it accordingly and
put the reason in a comment.

```kotlin
@Test
fun `both backends agree within 1pp under C1`() {
  val i = profile(FIXTURE, backend = "instrument").profile!!
  val s = profile(FIXTURE, backend = "sample").profile!!
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
  val first = runs.first().calls.associate { it.caller to it.callee to it.count }
  runs.drop(1).forEach { r ->
    assertEquals(first, r.calls.associate { it.caller to it.callee to it.count })
  }
}
```

Steady-state timings will not be bit-identical; assert within a tolerance (≤ 5% relative).

---

## 12. Risks and open questions

### Unresolved

- **Per-run latency budget is unmeasured.** P0 exists to close this. It is the highest-value
  half-day in the plan.
- **Library-internal memoization inside `body()` is detectable but not attributable.** The
  `firstCallNs` / `steadyNsPerIter` gap reveals that it happened; it does not say where.
  Attributing it requires instrumenting iteration 1 specifically — counts are exact regardless
  of duration, so backend I can answer this where backend S structurally cannot. This is the
  strongest argument for eventually running `both`.

### Known and accepted

- **Kotlin inline functions carry SMAP line numbers** from the callee's source file (JSR-45
  `SourceDebugExtension`). Method-level attribution is unaffected; if line-level attribution is
  ever added, lines beyond the user file's length must be clamped or dropped.
- **Lambdas become synthetic classes** (`Foo$body$1`) and **`suspend` functions become state
  machines**. Frame names will not always resemble the source. Demangle for display; there is
  no cheap fix for coroutines.
- **C1-pinned numbers are not production-representative.** Deliberate; state it in the response.
- **Probe id space is capped at 65 536.** A very large classpath could exhaust it. `Probe`
  throws on exhaustion; the transformer's `catch (Throwable)` turns that into "this class goes
  uninstrumented," which degrades rather than breaks. Raise `MAX` if it ever fires.

---

## Appendix A — reproducing the measurements

Every **[MEASURED]** claim came from a standalone probe run against a replica of the sandbox.
To re-verify:

**Sandbox permission probes (§6.1).** Write a Java class that attempts each operation inside a
`try`/`catch (Throwable)` and prints allowed/denied. Run it as:

```bash
java -Xmx32M \
  -Djava.security.manager \
  -Djava.security.policy=<replica.policy> \
  -ea -cp <dir> Probe
```

where `replica.policy` grants only `FilePermission "<dir>", "read"` and `"<dir>/*", "read"` —
mirroring the `grant {}` block at `executor.policy:22`.

**JFR stdout corruption.** Run any program with `-XX:StartFlightRecording=filename=x.jfr` and
observe `[…][info][jfr,startup]` on **stdout**, not stderr. Adding `-Xlog:jfr*=off` silences it.

**Sample density.** `jfr summary <file>.jfr | grep ExecutionSample`.

**Backend agreement (§5.1).** Build two copies of the same four-method target — one plain, one
with hand-written `System.nanoTime()` probes in the exact shape `TimingMV` emits. Run the plain
copy under a programmatic JFR window and the probed copy with accumulator arrays, both with
`setup()`/warmup/N. Compare self-time percentages. Repeat with and without
`-XX:TieredStopAtLevel=1`; the divergence appears only without it.

**Probe cost (§5.3).** Time a loop of `System.nanoTime()` calls; compare `-Xint` against
default. Predicted probe cost is exactly `2 ×` the per-call figure.

**Classloading (§8.4).** An agent whose transformer merely counts invocations and reports
`loader == null` vs non-null at shutdown, compared against `-Xlog:class+load=info | wc -l`.

---

## Appendix B — effort summary

| Phase | Scope | Estimate |
|---|---|---:|
| P0 | Measure per-run latency breakdown | ½ day |
| P1 | Contract, plumbing, three timings | 1 day |
| P2 | Backend S — sampling | 1–2 days |
| P3 | Backend I — instrumentation agent | 3–5 days |
| P4 | Unified schema, confidence signal | 2–3 days |
| | **Total** | **8–12 days** |

**If only one backend ships, ship P1 + P3 (instrumentation).** It answers the original
question — what low-level calls does my code cause — it degrades gracefully rather than silently
on short programs, and its numbers are reproducible, which is what the agent consumer needs
most. Sampling is the better second addition, not the better first.
