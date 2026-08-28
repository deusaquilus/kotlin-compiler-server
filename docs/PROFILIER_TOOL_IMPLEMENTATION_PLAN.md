# Profiler Tool — Implementation Plan (MCP + API)

A stateless, ephemeral JVM profiling and microbenchmarking service with two front doors — an
MCP tool and a REST API — over one shared core. Two measurement backends (instrumentation and
sampling), selectable through a coproduct in the wire contract. Optional ORM runtimes
(Hibernate / EclipseLink, version-selectable) so a profiled workload can exercise a real
persistence stack against an ephemeral in-memory database.

| | |
|---|---|
| **Host repository** | `deusaquilus/kotlin-compiler-server` (core + API); MCP handler in the ExoBench MCP host |
| **Kotlin** | 2.1.20 |
| **Target JDK** | 17 (`jvmToolchain` 17, Amazon Corretto) |
| **Status** | Ready to implement. Every mechanism cited as [MEASURED] was verified experimentally; sources in §0 |
| **Date** | 2026-08-28 |

---

## 0. How to use this document, and its evidence base

This plan is self-contained for implementation, but it sits on top of a body of prior work.
Where full source code already exists in another document, this plan gives the shape and the
pointer rather than repeating 200 lines.

| Document | What this plan takes from it |
|---|---|
| `ephemeral-profiling-harness.md` | Full source for the child-side harness (`ProfileBootstrap`, `JfrWindow`, `Probes`), the instrumentation agent (`Agent`, `Probe`, `ProfilingTransformer`), server plumbing (`ProfilingFlags`, policy substitution, JFR fold), and build wiring. **§7–§9 of that document are the reference implementations for this plan's C-components.** Its §5 evidence tables justify the load-bearing flags. |
| `ephemoral-profiling-harness-analysis.md` | Why the product is comparative; the noise-floor and GC-collapse measurements (§6); the T1–T15 test plan; statistical-honesty requirements (T3, T10) |
| `agent-instructions-scoring.md` | The verdict discipline: never name a winner inside the noise; the INFLATED-vs-artifact distinction |
| `PROFILING_HARNESS_IMPLEMENTATION_PLAN.md` (study rig, external) | The 80-trial study results and the arbiter's own defects (T-16–T-20), which harden §10 of this plan |
| ExoBench `analyzeHibernateQueries` (shipped) | The envelope conventions, the ORM version axis, `HibBootstrap`/`ElBootstrap`, the docs-page pattern |

**Claim tags:** **[MEASURED]** — experimentally verified, source named. **[DECISION]** — design
judgment; push back before P1, not after. **[TRAP]** — a failure that has already cost real
time in this project's history; every one is consolidated in §19.

---

## 1. Positioning — what this tool is sold as, and what it is not

The 80-trial study (2026-08-27/28) settled this. Frontier agents asked to optimise code
**did not cheat** (reward hacking 0 of 40 on a nothing-to-win control; correctness failures
0 of 79) but **could not measure** (claim accuracy 0 of 39 where a real win existed; median
overstatement ~3×). Two consequences bind this plan:

1. **The pitch is not "unlike your LLM, this is trustworthy."** That claim is dead on data.
2. **The pitch is throughput and correctness-without-effort:** *run a thousand correct JVM
   measurements with zero infrastructure* — no JMH project, no fork configuration, no noisy
   shared laptop, no warmup folklore. Correct-by-construction measurement at agent speed.

Two consumption patterns, one engine, both first-class targets:

| Consumer | Pattern | What it needs from the contract |
|---|---|---|
| **Agent loop** (MCP) | 5–20 calls per delegated "make this faster" task | Small stable-keyed output an agent can diff; findings with actionable hints; hard clamps because the caller is a model |
| **CI / automation** (API) | Same measurement on every PR, forever | Deterministic JSON diffable across runs; machine-readable verdicts; a stable schema version |

The MCP tool and the REST API are thin adapters over the same service (§3). Nothing in the
core knows which door the request came through.

---

## 2. Binding requirements

1. **Two front doors, one core.** An `mcp/` section and an `api/` section in the code, both
   delegating to the same `ProfileService`. No measurement logic in either adapter.
2. **Both backends, selectable via a coproduct.** Instrumentation-based and sampling-based
   profiling as previously designed and measured, chosen by a tagged union in the request —
   plus a `both` variant that runs the two and cross-checks them (§9).
3. **Ephemeral.** Fresh temp dirs, fresh child JVM per execution, everything destroyed after
   the response is assembled. No state survives a request. `correlationId` is tracking only.
4. **Selectable ORM runtime.** Hibernate 5.6 / 6.2 / 6.6 / 7.0 and EclipseLink 2.7 / 3.0 /
   4.0 / 5.0, exactly as `analyzeHibernateQueries` offers, with the same bootstrap classes,
   the same javax/jakarta namespace rule, and an ephemeral H2 built from a caller-supplied
   schema. Profiling a persistence workload — reflection, proxy init, entity hydration — is a
   first-class use case, not an afterthought.

Derived requirements carried over from the harness design (they are acceptance criteria, not
aspirations): determinism over fidelity (pinned JIT tier), top-N-capped output with stable
keys, per-request latency as a feature, machine-readable findings with hints.

---

## 3. Architecture — one core, two front doors

### 3.1 Module layout

```
kotlin-compiler-server/
├── profiler-core/                    ← ALL measurement logic. Transport-agnostic.
│   └── src/main/kotlin/io/exoquery/profiler/core/
│       ├── ProfileService.kt         ← the single entry point (§3.2)
│       ├── contract/                 ← request/response types, the coproducts (§4)
│       ├── pipeline/                 ← compile → execute → collect → fold → assemble (§6)
│       ├── runtimes/                 ← ORM classpath resolution + bootstrap injection (§11)
│       ├── stats/                    ← CV, bootstrap CI, verdicts, auto-warmup (§10)
│       └── fold/                     ← JFR fold + instrument-dump fold + reconciliation (§9)
│
├── profiler-api/                     ← REST adapter. Spring controller + DTO mapping ONLY.
│   └── src/main/kotlin/io/exoquery/profiler/api/
│       └── ProfilerRestController.kt (§14)
│
├── profiler-mcp/                     ← MCP adapter. Tool schema + handler + docs pages ONLY.
│   └── src/main/kotlin/io/exoquery/profiler/mcp/
│       ├── ProfileJvmTool.kt         (§13)
│       └── docs/                     ← getMcpDocs pages: profile-jvm, profiling-modes, profile-orm
│
├── executors/                        ← existing module. Gains ProfileBootstrap, Blackhole,
│                                       JfrWindow, Probes, ProfileRunner (child-side, §5–§6)
├── profiler-agent/                   ← NEW module: the -javaagent jar (§7)
└── (existing: KotlinCompiler, JavaExecutor, executor.policy, dependencies/, …)
```

**Rule: adapters contain zero measurement logic.** A test enforces it structurally:
`profiler-api` and `profiler-mcp` depend on `profiler-core`'s `contract` and `ProfileService`
packages and on nothing else in core. If a change to a fold or a verdict requires touching an
adapter, the seam is broken.

### 3.2 The service seam

```kotlin
// profiler-core — the one entry point both doors call.
interface ProfileService {
  /**
   * Compile the submission, execute it in a fresh sandboxed child JVM under the
   * requested mode and runtime, and assemble a ProfileReport.
   * Throws nothing request-derived: every user-caused failure is a structured
   * ProfileReport with status != "ok". Only infrastructure failures raise.
   */
  fun profile(request: ProfileRequest): ProfileReport
}
```

Everything user-caused — compile errors, missing `measure` blocks, wrong ORM namespace, child
timeout — comes back **inside the envelope** with `status` and findings, mirroring
`analyzeHibernateQueries`' `{"status":"compileError","errors":…}` convention, so an agent can
iterate without special-casing transport errors.

### 3.3 Request flow

```
MCP tool call ─┐                                       ┌─> compile (K2JVMCompiler, temp dir A)
               ├─> ProfileService.profile(request) ────┤
REST POST ─────┘         │                             ├─> write classes + policy (temp dir B)
                         │                             ├─> child JVM (ProfileRunner main)
                         │                             │     setup → first → warmup → window
                         │                             │     dumps: instrument.json / sample.jfr
                         │                             ├─> read dumps from temp dir B (parent)
                         │                             ├─> fold + stats + verdicts
                         │                             └─> delete temp dirs A and B
                         └────────────────── ProfileReport (JSON)
```

The pipeline reuses `KotlinCompiler.compile()` / `JavaExecutor` verbatim where possible; the
deltas to those files are enumerated in §6.4 and are the same ones specified in
`ephemeral-profiling-harness.md` §9.

---

## 4. The wire contract

### 4.1 Request shape

One request type for both doors. The MCP tool's input schema and the REST body are the same
JSON; the adapters do nothing but deserialize and delegate.

```jsonc
{
  // Kotlin or Java. Same dual form as analyzeHibernateQueries:
  // a bare source string (one file, language detected), or a JSON array
  // [{"name": "Main.kt", "text": "…"}, …] which may mix both languages.
  "code": "…",

  // WHICH BACKEND — the mode coproduct (§4.2). Discriminated on "kind".
  "mode": { "kind": "instrument", "callSites": true, "timing": false },

  // WHICH RUNTIME — the runtime coproduct (§4.3). Discriminated on "kind".
  "runtime": { "kind": "plain" },

  // Optional; defaults applied and clamped server-side (§4.5).
  "iterations": { "warmup": 5000, "measured": 20000 },

  // Tracking only. Never preserves state. Echoed in the response.
  "correlationId": "…"
}
```

### 4.2 The mode coproduct

**[DECISION]** A tagged union, not a string enum, because the variants carry different
payloads and the compiler should force exhaustive handling of them. On the wire it is a JSON
object discriminated on `"kind"`; in Kotlin it is a sealed interface; in the MCP schema it is
a `oneOf`.

```jsonc
// Variant 1 — instrumentation. Exact counts; deterministic; the default.
{ "kind": "instrument",
  "callSites": true,        // count (caller → callee) edges incl. into the JDK boundary
  "timing": false }         // per-method wall time via entry/exit probes. OFF by default (§7)

// Variant 2 — sampling. JFR ExecutionSample; statistical self/total time.
{ "kind": "sample",
  "samplePeriodMs": 1 }     // clamped to [1, 20]

// Variant 3 — both. Runs the window under both backends and cross-checks (§9).
{ "kind": "both",
  "callSites": true,
  "samplePeriodMs": 1 }
```

```kotlin
// profiler-core/contract/ProfileMode.kt
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
  JsonSubTypes.Type(ProfileMode.Instrument::class, name = "instrument"),
  JsonSubTypes.Type(ProfileMode.Sample::class,     name = "sample"),
  JsonSubTypes.Type(ProfileMode.Both::class,       name = "both"),
)
sealed interface ProfileMode {
  data class Instrument(
    val callSites: Boolean = true,
    val timing: Boolean = false,
  ) : ProfileMode

  data class Sample(
    val samplePeriodMs: Int = 1,
  ) : ProfileMode

  data class Both(
    val callSites: Boolean = true,
    val samplePeriodMs: Int = 1,
  ) : ProfileMode
}
```

Why instrumentation is the default, in one paragraph **[MEASURED]** (full tables:
`ephemeral-profiling-harness.md` §5.3, analysis doc §6): a sampling window over a short
program is starved (6 samples for a 63 ms run at 1 ms period), while counting probes cost
1.1× interpreted / 4.4× at C2 and produce byte-identical results run over run — and
reproducibility is what both consumers (agent diffing two results; CI diffing two commits)
actually consume. Timing probes default OFF because they cost 34.7× on tiny methods at C2 and
force `COMPUTE_FRAMES` bytecode rewriting (§7); counting carries most of the value at a
fraction of the risk.

### 4.3 The runtime coproduct

```jsonc
// Variant 1 — plain JVM. Classpath = the standard playground set.
{ "kind": "plain" }

// Variant 2 — Hibernate. Same wire names as analyzeHibernateQueries.
{ "kind": "hibernate",
  "version": "6.6",                    // "5.6" | "6.2" | "6.6" (default) | "7.0"
  "schema": "CREATE TABLE …; INSERT …" }

// Variant 3 — EclipseLink.
{ "kind": "eclipselink",
  "version": "4.0",                    // "2.7" | "3.0" | "4.0" | "5.0"
  "schema": "CREATE TABLE …; INSERT …" }
```

```kotlin
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
sealed interface ProfileRuntime {
  data object Plain : ProfileRuntime

  data class Hibernate(
    val version: HibernateVersion = HibernateVersion.V6_6,
    val schema: String,
  ) : ProfileRuntime

  data class EclipseLink(
    val version: EclipseLinkVersion = EclipseLinkVersion.V4_0,
    val schema: String,
  ) : ProfileRuntime
}

enum class HibernateVersion(val wire: String, val namespace: JpaNamespace, val artifact: String) {
  V5_6("5.6", JpaNamespace.JAVAX,   "5.6.15.Final"),
  V6_2("6.2", JpaNamespace.JAKARTA, "6.2.52.Final"),
  V6_6("6.6", JpaNamespace.JAKARTA, "6.6.55.Final"),
  V7_0("7.0", JpaNamespace.JAKARTA, "7.0.10.Final"),
}
```

Unknown versions are rejected **with the list of available names**, matching the shipped
tool's behaviour. The javax/jakarta rule carries over verbatim and deliberately: 5.6 code
must import `javax.persistence.*` and will not compile against 6.x — *"a silently-unmapped
entity is worse than a compile error."*

**Orthogonality is the point of two coproducts.** Any mode × any runtime is legal:
`instrument × hibernate-6.6` counts every `org.hibernate.**` call the workload triggers;
`sample × eclipselink-4.0` shows where wall time goes inside unwoven EclipseLink;
`both × plain` is the cross-checked microbenchmark. The pipeline treats them as independent
axes and a matrix test asserts every combination executes (§17.4).

### 4.4 Validation and clamps

The caller is a language model. It will eventually send `iterations: 100000000`.

| Field | Default | Clamp | On violation |
|---|---|---|---|
| `iterations.warmup` | 5 000 | 0 – 200 000 | clamp silently, report the applied value |
| `iterations.measured` | 20 000 | 1 – 500 000 | clamp silently, report |
| `samplePeriodMs` | 1 | 1 – 20 | clamp |
| `code` total size | — | 512 KB | `status:"rejected"`, finding `codeTooLarge` |
| `schema` size | — | 256 KB | `status:"rejected"` |
| measure blocks per submission | — | 8 | `status:"rejected"`, finding `tooManyBlocks` |
| wall clock per request | — | 60 s child + 30 s compile | `status:"timeout"` with partial timings if available |

Defaults are applied in `profiler-core`, not in the adapters, so MCP and REST cannot drift.

---

## 5. The in-code contract — `measure { }`

Unchanged from the harness design (`ephemeral-profiling-harness.md` §3), restated normatively.
The submission is **an ordinary program with an ordinary `main`** that wraps measured work in
a bootstrap call. No annotations, no reserved method names, no discovery scan —
`findMainClasses` is reused as-is.

```kotlin
import executors.ProfileBootstrap.measure
import executors.Blackhole

fun main() {
  // Everything before the first measure() call is SETUP: not profiled,
  // but its wall time is reported as setupNs (includes <clinit> + classloading).
  val people = (1..1000).map { Person(it, "n$it") }

  measure("filter-then-map") { Blackhole.consume(variantA(people)) }
  measure("map-then-filter") { Blackhole.consume(variantB(people)) }   // optional 2nd block
}
```

Timeline per block: `setup → block #1 (timed alone) → warmup × W (excluded) → armed window
× N (measured) → dump`. Three timings reported per block (`setupNs`, `firstCallNs`,
`steadyNsPerIter`) so one-time cost — including ORM session-factory construction and memoised
query compilation — is **reported, never averaged into invisibility**. [MEASURED: naive
amplification erased a cost that was 33% of a single run to 0% at N=500; analysis §5.2.]

Named blocks amortise the compile cost across variants (compile once, measure k variants) —
directly serving the "1000 microbenchmarks" positioning. The cross-block JIT-state caveat and
the one-block-per-request recommendation for precise head-to-heads carry over, surfaced as
the `multiBlockOrdering` finding.

With an ORM runtime, `measure` composes with the bootstrap exactly as in the shipped tool:

```kotlin
fun main() = HibBootstrap.withSession(arrayOf(Person::class.java)) { sf ->
  sf.openSession().use { s ->
    measure("load-with-children") {
      Blackhole.consume(s.createQuery("from Person", Person::class.java).list())
    }
  }
}
```

`ProfileBootstrap` is a library call, not a scaffold — nothing about the ORM path needs a
different contract.

---

## 6. Execution pipeline — ephemeral by construction

### 6.1 Stages

1. **Compile** — `KotlinCompiler.compile()` in a fresh `usingTempDirectory`, K2JVMCompiler
   CLI, classpath = playground set ∪ runtime set (§11.2). Compile diagnostics propagate into
   the envelope unchanged.
2. **Materialise** — classes + rendered `executor.policy` into a second temp dir. Policy
   placeholders: `%%GENERATED%%`, `%%LIB_DIR%%`, `%%AGENT_DIR%%`, and (ORM only) `%%ORM_DIR%%`.
3. **Execute** — one fresh child JVM, `ProfileRunner` main, flags from §6.3. For
   `mode.kind == "both"`: **two** fresh child JVMs, sequential, same compiled classes (§9.1).
4. **Collect** — parent reads `profile-instrument-<block>.json` / `profile-sample-<block>.jfr`
   from the temp dir **before** teardown. Never via stdout. **[TRAP T-3]**
5. **Fold + verdict** — §9, §10.
6. **Destroy** — both temp dirs `deleteRecursively` in `finally`. The only surviving artifact
   is the `ProfileReport`. For the ORM path the H2 database is `mem:` scoped to the child JVM
   and dies with it — there is nothing to clean.

### 6.2 Ephemerality invariants

- No cache keyed on user code anywhere in the pipeline. (Compile caching, if it ever lands
  per the P0 latency measurement, keys on the *dependency classpath*, never on submissions.)
- `correlationId` reaches logging/analytics only; a test asserts the pipeline has no read
  path from it.
- Child JVMs are never pooled or reused. A warm-JVM pool is an explicit non-goal of v1: it
  would trade the profile-pollution guarantee (fresh JIT state per execution, [MEASURED] as
  the D6 defect: shared-JVM measurement systematically inflates the second variant) for
  latency. Revisit only with data from P0.

### 6.3 Child JVM flags

```
-XX:TieredStopAtLevel=1        ← LOAD-BEARING. Makes the two backends agree (§9.2) and
                                 makes results reproducible. [MEASURED: backends diverge
                                 3–4× on sub-130ns methods at C2; agree within 0.6pp at C1.]
-Xlog:jfr*=off                 ← keeps JFR banners off the JSON stdout channel [TRAP T-1]
-Xmx256M                       ← plain runtime; 768M for ORM runtimes (H2 + entities + SF)
-Dexo.profile.mode=…           ← read by ProfileBootstrap; never settable from user source
-Dexo.profile.warmup=… -Dexo.profile.iterations=… -Dexo.profile.outDir=…
-javaagent:…/profiler-agent.jar=include=…;callsites=…;timing=…    ← instrument/both only
```

All assembled in `CommandLineArgument.toList()` **before** `-classpath` (anything after it
parses as a class name). `EXECUTION_TIMEOUT` becomes a parameter (`PROFILE_TIMEOUT = 60_000L`).

C1 pinning is stated in the response (`jitTier: "c1"`) and in the docs: these numbers are
deliberately reproducibility-first, not production-representative. A C2 mode is a possible
later axis — and in it the backends stop being interchangeable, so it ships only with
`sample` and a `c2NotCrossCheckable` note, if ever.

### 6.4 Deltas to existing files

Exactly the set already specified in `ephemeral-profiling-harness.md` §9, summarised:

| File | Delta |
|---|---|
| `KotlinCompiler.kt` | `profile()` entry point beside `run`/`test`; `%%AGENT_DIR%%`/`%%ORM_DIR%%` substitution in `write()`; dump read-back inside `usingTempDirectory` before deletion (the `addByteCode` pattern) |
| `JavaExecutor.kt` | `timeoutMs` parameter; `ProfilingFlags` (per §6.3) on `CommandLineArgument` |
| `executor.policy` | agent-jar codeBase grant (`AllPermission`); `executors.jar` gains `FlightRecorderPermission`, write on `%%GENERATED%%/-`, read on `exo.profile.*` properties; ORM grants §11.4 |
| `executors/` | `ProfileBootstrap`, `Blackhole`, `JfrWindow`, `Probes`, `ProfileRunner`, `ProfileOutput` — full source in `ephemeral-profiling-harness.md` §7 |
| `ExecutionResult.kt` | nothing — `ProfileReport` is a `profiler-core` type; the profiler pipeline does not extend `JvmExecutionResult` **[DECISION]** (the envelope belongs to the service seam, not to the playground DTOs) |

---

## 7. Backend: `instrument` (the `-javaagent`)

Reference implementation: `ephemeral-profiling-harness.md` §8 (Agent, Probe,
ProfilingTransformer — complete source). Plan-level requirements:

- **Load-time transformation, never post-compile.** [TRAP T-6] Post-processing the compiler
  output map sees only user classes; the value is counting *into* kotlin-stdlib,
  exoquery-engine, and — with an ORM runtime — `org.hibernate.**` / `org.eclipse.persistence.**`.
  A classpath jar's classes ARE offered to the transformer [MEASURED: 119 of 551 loaded
  classes offered; all non-bootstrap].
- **Call-site counting** (`CountingMV.visitMethodInsn`) keyed `(caller, callee)` gives exact
  counts of JDK calls *caused by* instrumented code without transforming the JDK.
- **Counting uses `COMPUTE_MAXS`** (no new branches, existing stack maps stay valid);
  **timing uses `COMPUTE_FRAMES`** with a loader-aware `ClassWriter` and ships *second*,
  behind `timing: true`. [TRAP T-7: a bad frame computation surfaces as `VerifyError` on code
  that compiled cleanly; degrade per class — `catch (Throwable) → return null` — never break.]
- **Include scope by runtime:** base `io.exoquery.`, `kotlin.`, `kotlinx.` + user classes;
  `+ org.hibernate., org.hibernate.orm., com.zaxxer.` for Hibernate;
  `+ org.eclipse.persistence.` for EclipseLink. Always excluded: `java.`, `jdk.`, `sun.`,
  `executors.`, `io.exoquery.profiler.`, `hib.bootstrap.`, `org.h2.` **[DECISION** — H2 is
  the database standing in for I/O; counting inside it reports noise the caller cannot act
  on; its cost still appears as call-site edges *into* `org.h2.`**]**.
- **Probe overhead compensation ships OFF.** [MEASURED: compensation over-subtracts at C2,
  erasing a real 1.5% method to 0.0%; at C1 raw numbers are already within 0.6pp.] The
  calibrated probe cost is *reported* (`probeNs`) so a consumer can reason about it; it is
  not silently applied.
- **Arming:** probes count only inside the measured window (`Probe.armed`), reset per block,
  dumped per block to `profile-instrument-<block>.json` by a hand-rolled writer (no Jackson
  on the child's system classpath, where it could shadow the copy under test).
- [TRAP T-8] The agent fat-jar **must include inner classes** — a missing `Agent$1` dies in
  `premain` as an opaque `InvocationTargetException`. The Gradle `jar` task from
  `ephemeral-profiling-harness.md` §6.4 handles this; a smoke test asserts
  `jar tf | grep '\$'` is non-empty.

## 8. Backend: `sample` (programmatic JFR)

Reference implementation: `ephemeral-profiling-harness.md` §7.2 (`JfrWindow`) and §9.3
(`JfrFold`). Plan-level requirements:

- **Programmatic `jdk.jfr.Recording` per block**, `jdk.ExecutionSample` at
  `samplePeriodMs`, window opened *after* setup + warmup, closed before the next block.
  [MEASURED: window-after-warmup yields 95% of samples in the block, 0 leaked from setup.]
- **Never `-XX:StartFlightRecording`.** [TRAP T-1] It prints a banner **to stdout**,
  corrupting the JSON channel, and records JVM startup so samples land in
  `PolicyFile.*` parsing instead of user code. Programmatic window + `-Xlog:jfr*=off`,
  verified clean.
- **`r.dump(path)` completes synchronously before the child prints its JSON.** [TRAP T-2]
  `JavaExecutor` destroys the process as soon as the stream futures complete; a
  `dumponexit` hook loses the recording. Dumping inside `measure()` satisfies this by
  construction.
- **Fold in the parent** (`jdk.jfr.consumer.RecordingFile`, in-JDK, no new dependency):
  leaf frame = self time, distinct frames per stack = total time, harness prefixes filtered
  (`executors.`, `java.lang.reflect.`, `jdk.internal.reflect.`, `jdk.jfr.`,
  `io.exoquery.profiler.`), JDK-internal frames rolled up to the nearest includable ancestor.
- **Starvation is surfaced, not papered over:** `samples` reported per block; below 100 →
  finding `insufficientSamples` with hint *"raise iterations, or use mode.kind=instrument"*.
  This is the honest failure mode of sampling short blocks and the reason it is not the
  default.

## 9. `both` — cross-checked measurement

### 9.1 Execution

Two fresh child JVMs, sequential, same compiled classes: one under the agent, one under JFR.
**Not both collectors in one JVM** **[DECISION]** — instrumentation changes what the sampler
sees (probe frames, blocked inlining even at C1), so co-resident collectors measure a third
thing that is neither backend. Sequential fresh JVMs cost one extra execution and keep both
measurements clean.

### 9.2 Reconciliation

Join folded frames on frame key. Rules (from the harness design, hardened by the study):

1. `count` is instrumentation-only; `null` under sample — never fabricated.
2. JDK frames roll up before joining, or the rows don't line up.
3. Per joined frame: agreement within 2 pp of self% → `confidence: "high"`; within 5 pp →
   `"medium"`; beyond → `"low"` **plus** a `backendDisagreement` finding naming the frame.

The premise is [MEASURED]: at C1 the two backends agree within 0.6 pp on every frame of the
four-method fixture; at C2 they diverge up to 4×. The cross-check is only meaningful because
of the §6.3 pin, which is why the pin is non-negotiable.

`both` costs ~2× execution time and is the recommended mode in the MCP docs for "the number
is about to be acted on" moments; `instrument` for loop iteration; `sample` for "where does
wall time go in this ORM call."

---

## 10. Statistical honesty — verdicts, not numbers

The study's sharpest lesson is reflexive: **the deterministic arbiter built to catch agents
measuring badly was itself measuring badly** — under-warmed on one arm (T-16), 43% CV against
a 25% threshold (T-19). This tool is that arbiter, productised. These rules are therefore
hard requirements, each traceable to a measured failure:

1. **Every per-block result carries dispersion.** `steadyNsPerIter` is a median over the
   window's chunked sub-intervals, reported with `cv`. [MEASURED: CV 2.8–6.2% in the
   allocation-free regime collapsing to 51–56% under GC pressure, with mean/median diverging —
   a mean alone is a lie in exactly the cases that matter.]
2. **A resolution gate, applied server-side.** `cv > 0.25` → the block's `verdict` is
   `"indeterminate"` and a `noisyMeasurement` finding explains what to change (fewer
   allocations, more iterations, one block per request). The tool **refuses to publish a
   ratio it cannot support** — the T-19 failure, made structurally impossible.
3. **Comparisons never name a bare winner.** When ≥2 blocks are present, the response includes
   pairwise `comparisons` with verdict
   `"faster" | "slower" | "no_significant_difference" | "indeterminate"`, decided by
   non-overlap of bootstrap CIs — not by which median is smaller. [MEASURED: at 5% threshold
   and low reps, 10–22% of identical-workload comparisons produce a false winner.]
4. **Auto-warmup adequacy.** After warmup, the harness compares the first and second halves of
   the measured window; drift > 10% → finding `warmupInsufficient` with the drift attached
   (the T-16 defect, self-diagnosed). It does not silently extend the window — budget belongs
   to the caller.
5. **First-call visibility.** `firstCallNs / steadyNsPerIter > 20` → finding
   `firstCallDominates` — memoisation or lazy init inside the block; the steady profile is
   real but partial.

---

## 11. ORM runtimes

### 11.1 What "keep the capability" means concretely

The shipped `analyzeHibernateQueries` capability set, embedded as this tool's runtime axis:
trusted bootstrap entrypoints, per-version classpaths, javax/jakarta namespace enforcement,
Kotlin `allopen`/`noarg` for entity annotations in whichever namespace the selected version
uses, ephemeral in-memory H2 built from the caller's `schema`, destroyed with the child JVM.

What this tool does **with** it is new: the profile of the workload. `analyzeHibernateQueries`
answers *what SQL was emitted*; this answers *where the JVM time went and what got called how
many times* — reflection, proxy construction, dirty-checking, hydration. The two tools
cross-reference each other in their docs pages.

### 11.2 Classpath assembly

Per-version directories, populated by the `dependencies` module exactly like the existing
`2.1.20-compiler-plugins` pattern, one Gradle configuration per version so transitive sets
never mix:

```
2.1.20-orm-hibernate-5.6/     hibernate-core 5.6.15.Final, javax.persistence-api, …
2.1.20-orm-hibernate-6.2/     hibernate-core 6.2.52.Final, jakarta.persistence-api, …
2.1.20-orm-hibernate-6.6/     hibernate-core 6.6.55.Final, …
2.1.20-orm-hibernate-7.0/     hibernate-core 7.0.10.Final, …
2.1.20-orm-eclipselink-{2.7,3.0,4.0,5.0}/
2.1.20-orm-common/            h2, hib-bootstrap.jar (HibBootstrap + ElBootstrap + StatementInspector glue)
```

`runtimes/OrmClasspath.kt` maps the coproduct variant → directory list. Compile classpath and
child `-cp` both get `orm-common` + exactly one version directory. **Exactly one** — a matrix
test asserts no request can see two ORM versions at once (classpath poisoning across
namespaces produces undiagnosable `NoSuchMethodError`s).

The Hibernate 5.6 vs 6.x namespace difference is enforced by compilation, deliberately.
`allopen`/`noarg` plugin args are namespace-keyed:
`jakarta.persistence.{Entity,Embeddable,MappedSuperclass}` for 6.x/EclipseLink 3+,
`javax.persistence.*` for 5.6/EclipseLink 2.7 — resolved from the coproduct at compile time.

### 11.3 The database

`HibBootstrap.withSession` / `ElBootstrap.withEntityManagerFactory` (ported from the shipped
tool into `orm-common`) run the caller's `schema` DDL against `jdbc:h2:mem:<uuid>` before the
user block executes. Schema execution time is reported as `sessionFactoryBuildMs` in the
top-level timings, mirroring the shipped envelope. The H2 URL is uuid-scoped and in-memory:
teardown is process death.

### 11.4 Policy additions (ORM runtimes only)

```
grant codeBase "file:%%ORM_DIR%%/-" {
  permission java.lang.reflect.ReflectPermission "suppressAccessChecks";
  permission java.lang.RuntimePermission "accessDeclaredMembers";
  permission java.lang.RuntimePermission "getClassLoader";
  permission java.lang.RuntimePermission "createClassLoader";   // Hibernate proxies
  permission java.util.PropertyPermission "*", "read";
  permission java.io.FilePermission "<<ALL FILES>>", "read";
};
```

No socket permission: H2 is `mem:`, and the child stays network-dark. **[MEASURED** for the
base grants (sandbox permission table, `ephemeral-profiling-harness.md` §6.1); the ORM grant
set must be validated in P4 by running the shipped tool's own example suite under this
policy — budget a day, reflection-heavy frameworks always need one more permission than
planned.**]**

### 11.5 Profiling-specific ORM findings

| Finding | Trigger | Hint |
|---|---|---|
| `sessionFactoryDominates` | `sessionFactoryBuildMs` > 5× total measured time | "SF construction dominates; it is one-time cost in production — read the block timings, not the wall clock" |
| `lazyInitInWindow` | first-call ≫ steady and runtime is ORM | "First iteration triggered lazy loading; steady state measures the cached path" |

---

## 12. Response envelope

House style: `analyzeHibernateQueries`' envelope, extended. `schemaVersion` is present from
day one because the CI consumer diffs this JSON across months.

```jsonc
{
  "status": "ok",                      // "ok" | "compileError" | "timeout" | "rejected" | "runtimeError"
  "schemaVersion": "1",
  "mode": { "kind": "instrument", "callSites": true, "timing": false },   // echoed, post-clamp
  "runtime": { "kind": "hibernate", "version": "6.6" },                   // echoed
  "jitTier": "c1",
  "database": "h2-mem",                // ORM runtimes only
  "output": "…stdout…",
  "timings": { "compileMs": 1840, "executeMs": 940, "sessionFactoryBuildMs": 390 },

  "blocks": [
    {
      "name": "load-with-children",
      "iterations": { "warmup": 5000, "measured": 20000 },   // as applied
      "timings": { "setupNs": 17004312, "firstCallNs": 5301887,
                   "steadyNsPerIter": 236612, "cv": 0.041 },
      "verdict": "measured",           // "measured" | "indeterminate"
      "frames": [                      // top 25 by selfPct
        { "frame": "org.hibernate.metamodel.…", "selfPct": 31.2, "totalPct": 44.0,
          "count": 20000, "confidence": "high" }              // count null under sample;
      ],                                                      // confidence only under both
      "calls": [                       // instrument/both only; top 25 by count
        { "caller": "org.hibernate.….AbstractEntityPersister.hydrate",
          "callee": "java.lang.reflect.Field.set", "count": 480000 }
      ],
      "samples": 1007                  // sample/both only
    }
  ],

  "comparisons": [                     // present when ≥2 blocks
    { "a": "filter-then-map", "b": "map-then-filter",
      "ratio": 1.42, "ci95": [1.31, 1.55], "verdict": "faster" }
  ],

  "findings": [
    { "kind": "firstCallDominates", "block": "load-with-children",
      "detail": "firstCallNs is 22x steadyNsPerIter",
      "hint": "Memoisation or lazy init inside the block; the steady profile is real but partial." }
  ],

  "errors": {},                        // CompilerDiagnostics shape on compileError
  "correlationId": "…",
  "serverVersion": "…"
}
```

Full findings vocabulary: `noMeasureBlock` (with the contract snippet as the hint),
`bodyOptimizedAway` (Blackhole untouched), `insufficientSamples`, `noisyMeasurement`,
`warmupInsufficient`, `firstCallDominates`, `backendDisagreement`, `multiBlockOrdering`,
`unreliableClocksource`, `sessionFactoryDominates`, `lazyInitInWindow`, `codeTooLarge`,
`tooManyBlocks`, `wrongJpaNamespace` (compile error against 5.6 with jakarta imports gets a
targeted hint, since it is the predictable mistake).

Caps: 25 frames, 25 call edges per block, findings unlimited (they are small). Output size is
a product requirement — the agent consumer pays context for every byte.

---

## 13. The MCP section

`profiler-mcp` owns three things and nothing else: the tool schema, the handler (deserialize
→ `ProfileService.profile` → serialize), and the docs pages.

**Tool name: `profileJvm`** **[DECISION]** — house verb-style (`benchmarkSql`,
`analyzeHibernateQueries`); short because agents type it. Alternative `microbenchmarkJvm`
oversells one mode.

Description text, following the house template (server-version tag, WHEN TO USE, EPHEMERAL,
INPUT, OUTPUT, docs pointer):

```
[ExoBench server version: X.Y.Z]

Profile or microbenchmark Kotlin/Java code in an ephemeral sandboxed JVM and return
exact call counts (instrumentation), statistical time profiles (sampling), or both
cross-checked — plus per-block timings that separate one-time setup cost from
steady-state cost, and verdicts that refuse to name a winner inside measurement noise.
Optionally run the workload against Hibernate or EclipseLink (version-selectable) with
an ephemeral in-memory H2 built from your schema, to profile where ORM time actually
goes. Call getMcpDocs('profile-jvm') first for the measure{} contract, mode selection,
and worked examples; getMcpDocs('profile-orm') for the ORM axis.

WHEN TO USE: the user wants to know which of several implementations is actually
faster, what a snippet's real cost is at a given scale, how many low-level calls a
piece of code causes, or where JVM time goes inside an ORM workload — measured, not
guessed. For the SQL a query emits use analyzeHibernateQueries; for database-side
cost use benchmarkSql.

EPHEMERAL: each call compiles fresh, runs in a fresh JVM, and destroys everything.
correlationId is tracking only.

INPUT: complete Kotlin or Java whose main() wraps measured work in
executors.ProfileBootstrap.measure("name") { ... }, consuming results via
Blackhole.consume(...). mode selects the backend; runtime selects plain JVM or an
ORM stack; both are tagged unions — see the docs page.

OUTPUT: JSON with status, per-block timings (setupNs / firstCallNs /
steadyNsPerIter / cv), top-N frames and call edges, pairwise comparisons with
confidence intervals, and findings with hints. Compile failures return
{"status":"compileError","errors":...} in the same envelope.
```

Docs pages (the `getMcpDocs` pattern): `profile-jvm` (contract, modes, minimal + comparison
examples, the C1 statement), `profile-orm` (versions, namespace rule, schema requirements,
HibBootstrap composition, the two ORM findings), `profiling-modes` (when instrument vs sample
vs both, with the measured tables that justify the guidance).

The MCP handler is < 100 lines. If it grows, logic is leaking through the seam.

## 14. The API section

`profiler-api`, Spring REST beside the existing controllers:

```
POST /api/profiler/run        body = ProfileRequest (§4.1)  → ProfileReport (§12)
GET  /api/profiler/runtimes   → available runtime variants + versions (feeds UIs and
                                lets CI fail fast on an unavailable version)
GET  /api/profiler/schema     → JSON Schema of request/response at current schemaVersion
```

Same DTOs, same Jackson config as the MCP handler — both registered from `profiler-core` so
the coproduct discriminator can never drift between doors. Auth/quota follow the existing
ExoBench gateway conventions (the tool is pool-gated like `benchmarkSql`); rate limiting is
the gateway's job, `profiler-api` only enforces the §4.5 clamps.

The CI consumer targets this endpoint directly (a GitHub Action wrapping `POST /run` and
diffing `blocks[].timings` against a baseline artifact is the natural v2 — out of scope here,
but `schemaVersion` and stable keys exist so it needs no server change).

---

## 15. Build wiring and deployment

Everything from `ephemeral-profiling-harness.md` §6.4 applies (settings.gradle modules,
`properties.kt` folder constants, `profiler-agent` fat-jar with manifest + inner classes,
`generateProperties()`, `buildLambda`, `LibrariesFile`/`ApplicationConfiguration` additions),
plus:

- New folder constants and copy tasks for each `2.1.20-orm-*` directory (one Gradle
  configuration each, `isTransitive = true` — unlike the compiler-plugin configs, ORM needs
  its transitive closure).
- **Dockerfile: fix the existing defect while adding the new COPYs.** [TRAP T-9] The current
  Dockerfile never copies `${KOTLIN_VERSION}-compiler-plugins`, so containerised builds run
  with the ExoQuery plugin silently absent. Add that COPY, plus `-profiler-agent` and every
  `-orm-*` directory. A container smoke test (§17.5) exists because this class of defect is
  invisible until someone profiles in prod.

---

## 16. Security and sandbox

The child runs under `-Djava.security.manager` with the generated policy. The base permission
surface is [MEASURED] (`ephemeral-profiling-harness.md` §6.1): JFR dumps need
`FlightRecorderPermission` + write on the generated dir; the `-javaagent` needs its own
codeBase grant; user code cannot write files or enumerate threads — profile data therefore
never transits user-writable channels.

Additional rules for this tool:

- The agent jar and `executors.jar` are the only `AllPermission`/near-privileged codebases;
  ORM jars get the §11.4 grant, not `AllPermission`.
- `-Dexo.profile.*` properties are set by the parent only; the policy grants user code no
  `write` on them, so a submission cannot re-aim `outDir` or inflate its own iteration budget.
- SecurityManager is deprecated (JEP 411, removed in JDK 24). Pinned JDK 17 makes this safe
  today; the debt is acknowledged and inherited by the whole compiler-server, not created here.
- Untrusted code + wall-clock timing is a timing side-channel *of its own execution only*;
  the child is network-dark and single-tenant-per-JVM, so there is nothing cross-tenant to
  leak. Multi-tenant hosts still isolate at the container level per existing ExoBench
  practice.

---

## 17. Testing and validation

1. **Backend agreement** — port the four-method fixture (heavy/medium/light/tiny, known
   distribution): both backends under C1, assert self% agreement within 1 pp per frame. This
   test **is the guard on `-XX:TieredStopAtLevel=1`**; name it so nobody deletes the flag
   without meeting it.
2. **Determinism** — same submission 10× under `instrument`: counts byte-identical, steady
   timings within 5% relative. This is the product guarantee both consumers buy.
3. **Playground corpus** — all 580 snippets in `test-compile-data/jvm` run agented with no
   behaviour change (byte-identical stdout). The bytecode-rewriting safety net; instrument
   backend cannot ship without it green.
4. **Mode × runtime matrix** — {instrument, sample, both} × {plain, hib 5.6/6.2/6.6/7.0,
   el 2.7/3.0/4.0/5.0}: one smoke submission each, `status:"ok"`, non-empty blocks. Plus the
   namespace-enforcement negatives (jakarta imports on 5.6 → `compileError` +
   `wrongJpaNamespace` finding).
5. **Container smoke** — build the Docker image, call `/api/profiler/run` with an ORM request
   inside it. Exists solely because of TRAP T-9.
6. **Statistical gates** — fixture with deliberate GC churn → `verdict:"indeterminate"` +
   `noisyMeasurement`; identical-blocks fixture 50× → false-winner rate < 5% under the CI
   comparison rule; under-warmed fixture → `warmupInsufficient` fires.
7. **Seam tests** — adapters import nothing from core but `contract` + `ProfileService`;
   clamps applied identically through both doors; unknown ORM version rejected with the
   available-names list through both doors.
8. **Contract negatives** — no `measure` call → `noMeasureBlock` with the snippet hint;
   Blackhole untouched → `bodyOptimizedAway`; 9 blocks → `rejected`.

---

## 18. Milestones

Ordered; each gate blocks the next. Estimates assume the reference implementations in
`ephemeral-profiling-harness.md` are lifted, not rewritten.

| # | Milestone | Contents | Gate | Est |
|---|---|---|---|---|
| **P0** | **Latency budget** | Timers around compile / write / execute on a representative submission, 20 runs, medians recorded **in this doc** | The split is known. If compile > 70% of wall time, open the compile-caching track and re-weigh named-blocks guidance | ½ d |
| **P1** | Core + contract + timings | `profiler-core` skeleton, coproducts, `ProfileBootstrap`/`Blackhole`/`ProfileRunner`, `profile()` entry, flags, clamps. No frames yet | Timings-only report through a direct `ProfileService` call; §17.8 negatives pass | 2 d |
| **P2** | Sample backend | `JfrWindow`, policy grants, `JfrFold`, starvation finding | §17.4 sample×plain green; zero harness frames in output | 1–2 d |
| **P3** | Instrument backend | `profiler-agent` module, counting first, call sites, arming, dump; timing behind flag second | §17.3 corpus green, then §17.1 agreement, then §17.2 determinism | 3–5 d |
| **P4** | ORM runtimes | Classpath sets, `orm-common` bootstrap port, namespace-keyed allopen/noarg, H2, §11.4 grants, ORM findings | §17.4 full matrix green incl. negatives | 3–4 d |
| **P5** | Both-mode + statistics | Sequential dual execution, reconciliation, confidence, comparisons, CIs, resolution gate, auto-warmup check | §17.6 gates green; §17.1 re-run through `both` | 2–3 d |
| **P6** | Front doors | `profiler-api` endpoints, `profileJvm` MCP tool + description + three docs pages, seam tests | §17.7 green; docs pages reviewed against house style | 2 d |
| **P7** | Hardening + deploy | Dockerfile (incl. the T-9 fix), buildLambda, container smoke, quota wiring | §17.5 green in the built image | 1–2 d |

Total: **15–20 days.** If forced to cut: ship P0–P3 + P6 (instrument-only, plain-only, both
doors) — that is already the "1000 microbenchmarks, no infra" product; sampling, ORM, and
cross-checking are additive.

---

## 19. Trap register (consolidated)

Every one of these cost real time somewhere in this project's history. Each has a §-reference
where the mitigation is specified.

| # | Trap | Mitigation |
|---|---|---|
| T-1 | `-XX:StartFlightRecording` corrupts the stdout JSON channel and profiles JVM startup | Programmatic window + `-Xlog:jfr*=off` (§8) |
| T-2 | JFR dump raced by `JavaExecutor.destroy()` | Dump synchronously inside `measure()`, before the child prints (§8) |
| T-3 | `MAX_OUTPUT_SIZE` overflow **discards all output** | Profile data goes through temp-dir files, never stdout (§6.1) |
| T-4 | Naive amplification erases one-time costs | Three-number block timings; `firstCallDominates` (§5, §10.5) |
| T-5 | C2 makes the backends measure different machine code | `-XX:TieredStopAtLevel=1`, guarded by test §17.1 (§6.3) |
| T-6 | Post-compile transformation sees only user classes | Load-time `-javaagent` (§7) |
| T-7 | `COMPUTE_FRAMES` failure → `VerifyError` on clean code | Counting-first; per-class degrade, never break (§7) |
| T-8 | Agent jar missing inner classes dies opaquely in premain | Fat-jar task + jar-content smoke test (§7) |
| T-9 | Dockerfile silently omits a classpath directory | Fix existing compiler-plugins omission; container smoke test (§15, §17.5) |
| T-10 | Mean-only timing lies under GC pressure | Median + CV mandatory; resolution gate (§10.1–10.2) |
| T-11 | Low-rep comparisons produce 10–22% false winners | CI-based verdicts; `no_significant_difference` is a first-class outcome (§10.3) |
| T-12 | The arbiter itself under-warms (study T-16) | Auto-warmup adequacy check + finding (§10.4) |
| T-13 | Publishing ratios above the instrument's resolution (study T-19) | `cv > 0.25 → indeterminate`, server-enforced (§10.2) |
| T-14 | Probe-cost compensation over-subtracts at C2 | Compensation off; `probeNs` reported, not applied (§7) |
| T-15 | Mixed ORM versions on one classpath | One version directory per request, asserted (§11.2) |
| T-16 | jakarta imports on Hibernate 5.6 | Deliberate compile error + `wrongJpaNamespace` hint (§11.2, §12) |
| T-17 | Coproduct discriminator drift between MCP and REST | Shared Jackson registration from core; seam tests (§14, §17.7) |

---

## Appendix — measured reference numbers this plan relies on

All previously recorded; sources in §0. Re-verify on the deployment host where noted.

| Quantity | Value | Note |
|---|---|---|
| Backend agreement at C1 | ≤ 0.6 pp per frame | four-method fixture |
| Backend divergence at C2 | up to 4× on sub-130 ns methods | why the pin exists |
| Counting probe overhead | 1.1× interpreted / 4.4× C2 (tiny method) | acceptable |
| Timing probe overhead | 2.7× interpreted / 34.7× C2 (tiny method) | why timing is opt-in |
| `nanoTime` cost | 23.6 ns C2 / 55.9 ns interpreted (`tsc`) | **re-verify clocksource on deploy host**; non-`tsc` → `unreliableClocksource` finding |
| Noise floor, interleaved 11 reps | CV 1.54% | shared 4-vCPU container |
| CV under GC pressure | 51–56% | why the resolution gate exists |
| False winners, identical workloads, 5% threshold | 10–22% at low reps | why verdicts use CIs |
| JFR sample yield | 6 samples / 63 ms naive; 396 with setup/warmup exclusion; 2× density at C1 | why sample isn't default |
| Classes offered to transformer | 119 of 551 (all non-bootstrap) | classpath libs fully instrumentable |
| Sandbox grants | table in `ephemeral-profiling-harness.md` §6.1 | JFR-in-sandbox, agent grant, thread-dump denial |
| Agent-behaviour study | hack rate 0/40, correctness failures 0/79, claim accuracy 16/79 | the positioning basis (§1) |
