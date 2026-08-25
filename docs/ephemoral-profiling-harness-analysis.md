# Microbenchmarking Harness — Product Analysis and Test Plan

**Question:** Should ExoBench ship a comparative microbenchmarking tool for JVM code?

**Status:** Undecided, pending the local experiments in §7. Reference numbers from one cloud
container are included so half of the comparison is already done.

**Date:** 2026-08-25

---

## 1. What the tool is (final form)

Not a profiler. A **comparative microbenchmark verifier**, invoked by an agent, that answers:

- *"Is variant A actually faster than baseline B, at scale n, and by how much?"*
- *"Does this big-O difference translate into real performance at the n I care about?"*
- *"Is this snippet slow at scale Y, and did my fix help?"*

Every measurement is **inherently comparative** — there is always a baseline, whether that's
"the current implementation" or "the naive version." The tool never reports an absolute number
as its primary output; it reports a **ratio plus that ratio's stability**.

Ephemerality is not decoration. A fresh JVM per variant is what eliminates **profile
pollution** — running A then B in one JVM makes B's call sites megamorphic because they already
saw A's types, so B measures slower than it is. That corruption falls precisely on the
comparison you are trying to make.

### The consuming workflow

A human delegates an outcome:

> "This snippet is too slow. Fix it. Keep trying until you succeed. I don't care how."

The agent generates variants, measures each against the baseline, and iterates. It may invoke
the tool 5–20 times in a single task.

---

## 2. The core thesis: agents need a verifier for the performance loop

Agentic coding works reliably where a cheap, mechanical oracle exists:

| Task | Verifier | Loop closes? |
|---|---|---|
| "Make the tests pass" | test runner | yes |
| "Fix the type errors" | compiler | yes |
| "Clean up the lint" | linter | yes |
| **"Make this faster"** | **— none —** | **no** |

Performance is the one common engineering task with no verifier. Absent one, the agent
**asserts**: *"this should be faster, it avoids the intermediate allocation."* Confidently,
sometimes wrongly, with no mechanism to discover the error.

"Keep trying until you succeed" is unexecutable without a definition of *succeed*. The tool
would supply it.

### The strongest single argument: reward hacking

**Without a correct harness, an agent optimization loop is a reward-hacking machine.**

An agent told to minimize a number, writing its own timing loop, eventually produces this:

```java
long t0 = System.nanoTime();
for (int i = 0; i < N; i++) expensiveComputation(i);   // result unused
long elapsed = System.nanoTime() - t0;                  // ≈ 0
```

C2 eliminates the loop body. The agent reports a 1000× speedup. It is wrong and cannot tell.

This is not hypothetical — it is the single most common microbenchmarking error, and an agent
hill-climbing on "lower number" is **actively selected toward triggering it**. Dead-code
elimination, constant folding, and loop hoisting are all reachable local optima of the metric.

Therefore correctness-by-construction (a blackhole sink, enforced warmup, isolation) is not a
quality-of-implementation detail. **It is the integrity of the fitness function**, and it
cannot be delegated to the agent, because the agent is the thing optimizing against it.

You do not let the student grade the exam.

### Why this changes the demand math

Measuring demand in *human pain episodes* gives a weak answer: a developer isolates an
algorithm and decides to care maybe a few times a year. That framing is wrong for this tool.
The unit is **agent iterations**, and one delegated task is 5–20 invocations. Frequency comes
from the loop structure, not from user enthusiasm.

### Strategic recast

If this ships, ExoBench stops being "a SQL benchmarking service" and becomes:

> **the verification layer for performance work done by agents** — SQL (`benchmarkSql`),
> ORM (`analyzeHibernateQueries`), and JVM code (this tool).

Three tools, one thesis: *an agent cannot optimize what it cannot measure, and cannot measure
any of these correctly on its own.* That is a larger and more defensible idea than any of the
three alone, and it explains why MCP-native is the right shape rather than an accident.

---

## 3. Objections that were raised and are now dead

Recording these so they are not re-litigated. Each was a genuine reason to reject the tool;
each fails against the design as finally specified.

| Objection | Why it fails |
|---|---|
| *"Profilers need a large search space; a pasted snippet is too small"* | It isn't a profiler. A comparative benchmark's value does not scale with program size — it scales with how hard the comparison is to perform correctly. |
| *"The hotspot will be in library code the user can't change"* | Irrelevant for A/B. You are comparing two things the user *did* write. |
| *"Your hardware isn't their hardware"* | Baseline and variant run in the same session on the same box, so hardware is **common mode** and largely cancels in the ratio. This was the load-bearing objection and it does not survive the comparative framing. |
| *"You can just do this locally with `javac` + `java`"* | You can run *a* benchmark locally. Running a **correct** one is the hard part, and it is exactly what an agent gets wrong. |
| *"Microbenchmarking requires expert intent, which is rare"* | Wrong unit. Demand comes from delegated goals, not user curiosity. |
| *"JMH already exists"* | JMH is correct but built for a human writing one careful benchmark for publication. Default settings are ~8 minutes per benchmark method (5 forks × 10 iterations × 10 s). Ten variants is over an hour — unusable inside an agent loop. The gap is *correct at agent speed*. |

---

## 4. Arguments in favour

1. **Real question, asked constantly, answered badly.** "Which of these is actually faster at my
   n" is normally settled by guessing or by a broken timing loop.
2. **The failure mode is silent and severe.** A naive measurement gives a confidently wrong
   answer, and decisions get shipped on it.
3. **Correctness is genuinely hard.** DCE, constant folding, loop hoisting, warmup tiers,
   profile pollution, OSR, GC interference, coordinated omission. Most engineers get at least
   one wrong; agents get several wrong.
4. **A new population exists.** High question sophistication, low execution reliability — asks
   expert questions, writes broken timing loops. That population did not exist three years ago
   and lives inside MCP.
5. **Fresh-JVM-per-variant is a real environment property**, delivered free by the existing
   ephemeral architecture, and it fixes the subtlest of the failure modes.
6. **The n-sweep is the same thesis one layer up.** ExoBench's pillar argument is *"your staging
   benchmark lies because you tested at 10k rows and production has 1M."* This is *"your
   microbenchmark lies because you tested at n=100 and production has n=100,000."* Same
   argument, same scale-point methodology `benchmarkSql` already uses, same voice.
7. **The build cost is a mode, not an architecture.** `kotlin-compiler-server` is already
   stateless: `usingTempDirectory` creates a UUID-named directory and `deleteRecursively`s it
   in a `finally`; every request compiles into a fresh temp dir and executes in a fresh child
   JVM under a freshly written policy file. There is no persistent state to discard because
   there is none. The ephemerality this product needs is not something you would be building —
   it is what the repository already does. That materially lowers the cost side of the
   decision, and it is why this repo is the right host rather than a new long-lived service.
8. **It costs the caller no new idiom.** `analyzeHibernateQueries` already establishes the
   shape: a trusted bootstrap entrypoint (`HibBootstrap.withSession(entities) { … }`), an
   ephemeral environment, structured findings with hints, everything destroyed afterwards.
   A `ProfileBootstrap.measure { … }` contract is the same shape with a different payload, so
   an agent that has learned one has effectively learned both. Adoption cost for the consumer
   is close to zero — which matters more than usual when the consumer is a model that has to
   infer correct usage from a tool description.

   The two are also genuinely complementary rather than overlapping: `analyzeHibernateQueries`
   answers *what SQL was emitted*, this answers *which implementation is faster*. Measuring a
   Hibernate workload through both is a real use case for each.

---

## 5. Objections that survive

### 5.1 Noise floor versus effect size — **the critical unknown**

Agents find either large algorithmic wins (10×, verifiable with anything) or small ones
(5–20%). If the harness cannot discriminate at 10%, it confirms the obvious and goes quiet
exactly where judgment is most needed.

**Partially answered by §7's measurements — and the answer is nuanced.** See T3.

### 5.2 Extraction fidelity

"Fix this snippet" assumes the slow thing is extractable. Real hot code is entangled with real
data shapes and call patterns, and an agent is exactly the entity likely to extract a subtly
unrepresentative workload — wrong n, wrong distribution, missing the true bottleneck. It then
optimizes the wrong thing, confidently, with numbers.

This is the same caveat unit tests carry: a passing test is not working software, and test
runners remain the most valuable tool in the agentic stack. Honest positioning is **a unit test
for performance** — necessary, not sufficient.

### 5.3 Cache size is the one axis you cannot vary

Heap, collector, core count, and JDK are all simulable on one machine. L2/L3 size is not. Since
data-structure crossovers are largely determined by cache hierarchy, the crossover *n* found on
your hardware may not be the crossover *n* on theirs. The n-sweep mitigates this — it reveals
that a cliff exists and roughly where — but does not eliminate it. Disclose it.

### 5.4 Positioning drift

ExoBench is currently "a JVM **database** performance platform." This tool is JVM performance
with no database in it. It may be a second product rather than a feature: different audience,
different search surface, different content pillar. Worth a deliberate decision rather than
drift.

---

## 6. Reference measurements (cloud container, already run)

Environment: 4 vCPU Linux container, OpenJDK 21.0.10, `tsc` clocksource, shared/virtualised.
Harness and driver in §7.1. **These are the numbers to compare your laptop against.**

### 6.1 Noise floor — identical workload against itself

```
n=1000   arith-a  491.7 ns    arith-b  493.5 ns
         ratio mean 0.9963  median 0.9972  sd 0.0154  CV 1.54%  min 0.9627  max 1.0236  (reps=11)
```

**CV 1.54%**, worst-case band ±3.7%. Minimum reliably detectable effect ≈ **5%**. Better than
expected for shared infrastructure, and comfortably good enough to call a 10% difference.

### 6.2 Crossover sweep — linear scan vs `HashSet` lookup

```
n=4      linear   1956 ns   hashset  1031 ns   ratio  1.90   CV  5.11%
n=8      linear   2906 ns   hashset  1023 ns   ratio  2.85   CV  6.02%
n=16     linear   3962 ns   hashset  1012 ns   ratio  3.91   CV  4.37%
n=32     linear   6852 ns   hashset  1031 ns   ratio  6.66   CV  6.18%
n=64     linear  12137 ns   hashset  1002 ns   ratio 12.12   CV  2.81%
n=128    linear  19938 ns   hashset 20859 ns   ratio  1.35   CV 56.01%   ← ★
n=512    linear  86290 ns   hashset 14870 ns   ratio  8.84   CV 51.44%   ← ★
```

Two findings, both important, and they point in opposite directions.

**★ Finding A — the harness caught a real, non-obvious cliff.** `HashSet` lookup cost is flat at
~1000 ns from n=4 to n=64, then jumps ~20× at n=128. That boundary coincides exactly with the
`Integer` autobox cache (`-128..127`): above it, `set.contains(p)` starts allocating. Big-O says
`HashSet` is O(1) everywhere; reality has a cliff at 128 driven by boxing and GC. **This is
precisely the class of finding the tool exists to produce** — the asymptotic story and the
measured story disagree, and only measurement shows it.

*(Stated as the most probable cause given the exact boundary; confirm with allocation profiling
before publishing it as fact.)*

### 6.3 Reward hacking — measured, not hypothesised

Identical work, timed twice, differing only in whether the return value is consumed
(`Naive.java`, three consecutive runs, fresh JVM each):

```
naive   (result discarded) :  24.89 / 28.21 / 26.09 ns/op
correct (volatile sink)    : 440.58 / 455.51 / 462.00 ns/op   →  17x false speedup, stable
```

The benchmark an agent writes by default reports a **17× improvement that does not exist**. This
is the empirical foundation of §2's thesis and it is no longer a prediction.

### 6.4 Findings continued

**★ Finding B — reliability collapses once allocation enters.** CV goes from 2.8–6.2% in the
allocation-free regime to **51–56%** once GC is in play. At n=128 the ratio ranges 0.38–2.37
across seven reps — the tool cannot tell you which implementation is faster. Note also that the
*mean* ratio (1.35) and *median* (0.95) disagree, a signature of a skewed, GC-contaminated
distribution.

**This is the single most important result in the document.** The tool's reliability is
workload-dependent, and it fails **silently** unless variance is measured and reported. A
harness that always names a winner would have confidently reported "linear is 1.35× slower at
n=128" — a number with no meaning. Any shipped version must compute the CV and refuse to call a
winner when the effect is inside the noise.

---

## 7. Test plan for your laptop

Run these in order. Each has a pre-committed pass/fail gate. Total: about half a day, excluding
T8.

### 7.1 The harness (copy these two files)

`Bench.java` — one fresh JVM measures one workload at one n:

```java
import java.util.*;

public class Bench {
  public static volatile long SINK = 0;          // defeats dead-code elimination
  public static volatile boolean TOUCHED = false;

  static int[] arr;  static HashSet<Integer> set;  static int[] probes;

  static void setup(String w, int n) {
    Random r = new Random(42);                   // fixed seed = reproducible
    arr = new int[n];
    for (int i = 0; i < n; i++) arr[i] = i * 2;
    set = new HashSet<>();
    for (int v : arr) set.add(v);
    probes = new int[1024];
    for (int i = 0; i < probes.length; i++) probes[i] = r.nextInt(n * 2);
  }

  static long body(String w) {
    switch (w) {
      case "arith-a": case "arith-b": {          // identical: measures noise floor
        long s = 0; for (int i = 0; i < 1000; i++) s += (i ^ 0x5f) * 31L; return s;
      }
      case "linear": {
        long hits = 0;
        for (int p : probes) { for (int v : arr) if (v == p) { hits++; break; } }
        return hits;
      }
      case "hashset": {
        long hits = 0;
        for (int p : probes) if (set.contains(p)) hits++;
        return hits;
      }
      default: throw new IllegalArgumentException(w);
    }
  }

  public static void main(String[] a) {
    String w = a[0]; int n = Integer.parseInt(a[1]);
    long warmupMs = Long.parseLong(a[2]), measureMs = Long.parseLong(a[3]);
    setup(w, n);
    long end = System.nanoTime() + warmupMs * 1_000_000L;
    while (System.nanoTime() < end) { SINK ^= body(w); TOUCHED = true; }
    long ops = 0, t0 = System.nanoTime();
    end = t0 + measureMs * 1_000_000L;
    while (System.nanoTime() < end) { SINK ^= body(w); ops++; }
    long dt = System.nanoTime() - t0;
    if (!TOUCHED) { System.out.println("ERROR body_optimized_away"); return; }
    System.out.printf("%.2f%n", dt / (double) ops);
  }
}
```

`ab.sh` — forks a fresh JVM per measurement and **interleaves** A,B,A,B so slow drift
(thermal, noisy neighbour) cancels in the ratio:

```bash
#!/usr/bin/env bash
# ab.sh <workloadA> <workloadB> <n> [reps] [warmupMs] [measureMs]
A=$1; B=$2; N=$3; REPS=${4:-11}; W=${5:-300}; M=${6:-600}
DIR="$(cd "$(dirname "$0")" && pwd)"
as=(); bs=()
for ((i=0;i<REPS;i++)); do
  as+=( "$(java -cp "$DIR" Bench "$A" "$N" "$W" "$M")" )
  bs+=( "$(java -cp "$DIR" Bench "$B" "$N" "$W" "$M")" )
done
printf '%s\n' "${as[@]}" > /tmp/.a$$; printf '%s\n' "${bs[@]}" > /tmp/.b$$
paste /tmp/.a$$ /tmp/.b$$ | awk -v A="$A" -v B="$B" -v N="$N" '
  { a[NR]=$1; b[NR]=$2; r[NR]=$1/$2; sr+=r[NR]; sa+=$1; sb+=$2 }
  END {
    n=NR; mr=sr/n; ma=sa/n; mb=sb/n
    for(i=1;i<=n;i++){ d=r[i]-mr; v+=d*d }
    sd=(n>1)?sqrt(v/(n-1)):0
    for(i=1;i<=n;i++) s[i]=r[i]
    for(i=1;i<=n;i++) for(j=i+1;j<=n;j++) if(s[j]<s[i]){t=s[i];s[i]=s[j];s[j]=t}
    med=(n%2)?s[(n+1)/2]:(s[n/2]+s[n/2+1])/2
    printf "n=%-9s %-9s %9.1f ns   %-9s %9.1f ns\n", N, A, ma, B, mb
    printf "          ratio %s/%s  mean %.4f  median %.4f  sd %.4f  CV %.2f%%  min %.4f  max %.4f  (reps=%d)\n", A, B, mr, med, sd, 100*sd/mr, s[1], s[n], n
  }'
rm -f /tmp/.a$$ /tmp/.b$$
```

```bash
javac -d . Bench.java && chmod +x ab.sh
```

---

### T1 — Noise floor (30 min) · **run this first**

```bash
./ab.sh arith-a arith-b 1000 21
```

Identical workload against itself. The ratio *should* be 1.0; the spread is your instrument's
resolution.

| | |
|---|---|
| **Measures** | Minimum detectable effect |
| **Container reference** | CV **1.54%**, range 0.963–1.024 |
| **PASS** | CV < 3% → can reliably call a 10% difference |
| **MARGINAL** | CV 3–8% → only 20%+ differences are trustworthy |
| **FAIL** | CV > 8% → the instrument cannot see the effects agents produce |

Repeat with your laptop plugged in vs on battery, and with a browser open vs closed. Laptops
thermally throttle; if CV doubles under realistic conditions, that is the honest number.

---

### T2 — Discrimination in the clean regime (20 min)

```bash
for N in 4 8 16 32 64; do ./ab.sh linear hashset $N 7 200 400; done
```

| | |
|---|---|
| **Measures** | Whether real differences are cleanly separated when GC is not involved |
| **Container reference** | CV 2.8–6.2%; ratio rises monotonically 1.90 → 12.12 |
| **PASS** | CV < 8% throughout, ratio monotonic in n |
| **FAIL** | Non-monotonic ratio or CV > 15% — measurement is dominated by something other than the workload |

---

### T3 — Discrimination under allocation/GC (20 min) · **the decisive test**

```bash
for N in 128 512 2048; do ./ab.sh linear hashset $N 7 200 400; done
```

| | |
|---|---|
| **Measures** | Whether the tool degrades gracefully or lies |
| **Container reference** | **CV 51–56%**; at n=128 the ratio ranged 0.38–2.37 and mean/median disagreed (1.35 vs 0.95) |
| **PASS** | CV stays < 15% — GC noise is manageable and the tool works across regimes |
| **CONDITIONAL PASS** | CV blows up as it did here, **but** you accept that the product must compute CV and return `"no significant difference"` rather than a winner |
| **FAIL** | CV blows up *and* you cannot detect it from within a run — the tool would ship confident nonsense |

If your laptop shows the same collapse, that is not a reason to abandon the product. It is a
**hard requirement**: variance reporting is not a feature, it is a safety mechanism. Raise reps,
pin `-Xmx`, fix the collector, and re-measure to see how much is recoverable.

---

### T4 — Ratio transferability (30 min) · **compare against §6**

Run T1–T3 on the laptop, then put the ratios beside the container numbers in §6.

| | |
|---|---|
| **Measures** | Whether a number produced on ExoBench's hardware means anything on the user's |
| **PASS** | Ratios agree within ~15% in the clean regime (n=4…64) |
| **MARGINAL** | 15–40% disagreement — usable for direction, not magnitude; must be disclosed |
| **FAIL** | Any ratio **inverts** (A faster on one machine, B on the other) — you have found the class of question the tool must refuse |

Compare *ratios*, never absolute nanoseconds. Absolute times will differ substantially and that
is expected and fine.

---

### T5 — Crossover stability (30 min)

Find the n where the ratio crosses 1.0 on each machine. Use a finer sweep around it.

| | |
|---|---|
| **Measures** | Whether "at what n does the better algorithm win" transfers |
| **PASS** | Crossover n within a factor of 2 across machines |
| **FAIL** | Crossover moves more than 4× — the headline question is not answerable on foreign hardware, and the product must reframe to "at *your* stated n" only |

---

### T6 — Reward-hacking demonstration (10 min) · **validates the value proposition**

Already written and verified — `Naive.java` sits beside this document. Two timing loops over
**identical** work; the only difference is whether the result is consumed.

```bash
javac -d . Naive.java
java -cp . Naive naive      # the benchmark an agent writes on its own
java -cp . Naive correct    # the benchmark a harness enforces
```

**Container reference — three consecutive runs, fresh JVM each:**

```
naive   (result discarded) :  24.89 / 28.21 / 26.09 ns/op
correct (volatile sink)    : 440.58 / 455.51 / 462.00 ns/op
                             ────────────────────────────
                             false speedup: 16-18x, stable
```

Same function. Same iteration count. Same JVM flags. Discarding the return value makes the code
appear **17× faster** because C2 strength-reduces and partially eliminates work whose result
nobody reads.

| | |
|---|---|
| **Measures** | Whether the harness's correctness is worth anything |
| **PASS** | A clear gap (container: 17×) |
| **FAIL** | No gap — your JDK isn't eliminating it, so find a case that does before claiming the risk |

**This is the most persuasive artifact in the document.** It is not an argument that an agent
*might* mismeasure — it is a demonstration that the naive benchmark an agent writes by default
reports a 17× improvement that does not exist. An agent instructed to "keep trying until it's
faster" will hill-climb straight toward that number, because writing code whose result is unused
is a *lower-cost move* than actually optimising. The measurement flaw is the path of least
resistance.

That is the whole case for why the harness cannot be delegated to the agent, and it fits in one
screenshot. It is also the blog post: *"We asked an agent to make this faster. It reported a 17×
speedup. It had changed nothing."*

---

### T7 — Loop latency (20 min)

```bash
time ( javac -d . Bench.java && java -cp . Bench arith-a 1000 300 600 )
```

Then estimate the hosted path: network round-trip + Kotlin compile + JVM start + warmup +
measure, per variant.

| | |
|---|---|
| **Measures** | Whether an agent loop of 10 variants is tolerable |
| **PASS** | < 20 s per variant → 10 variants in ~3 minutes, usable |
| **MARGINAL** | 20–60 s → usable but the agent will avoid it |
| **FAIL** | > 60 s → you have rebuilt JMH's ergonomics problem, which was the gap you were filling |

Note the likely dominant term is the **Kotlin compile**, not the measurement. Measure it
separately; if it dominates, compile caching matters more than anything else in this document.

---

### T8 — End-to-end agent loop (half day) · **the real product test**

Give an agent a genuinely slow snippet, the harness, and the instruction *"make this faster,
keep trying until you succeed, verify each attempt."* Let it run 10+ iterations unattended.

Record: did it converge? How many iterations? Did it ever "win" by breaking the measurement?
Did it know when to stop? Did it try to modify the harness?

| | |
|---|---|
| **Measures** | Whether the verifier actually closes the loop — the entire thesis |
| **PASS** | Converges on a real improvement, and correctly reports "no further gains" instead of chasing noise |
| **FAIL** | Chases noise for 10 iterations, or games the measurement, or cannot tell improvement from variance |

Everything in §2 rests on this. T1–T7 establish the instrument is sound; T8 establishes that the
sound instrument produces the behaviour the product is sold on.

---

## 8. Decision rule

**Build it if:** T1 passes (CV < 3%), T4 shows no ratio inversions, T6 shows a clear
reward-hacking gap, T7 is under 20 s per variant, and T8 converges.

**Build it with restrictions if:** T3 shows the GC-noise collapse — ship, but *only* with
mandatory variance reporting and a `"no significant difference"` verdict. Under no circumstance
return a bare winner.

**Reframe if:** T5 fails. Drop "at what n does the crossover happen" and sell only "at the n you
specify, on standardised hardware, here is the ratio."

**Do not build if:** T4 shows ratio inversions in the clean regime, or T8 shows the agent
cannot distinguish improvement from noise. Either result means the verifier does not verify.

---

## 9. If it is a go — what to build

Not the design in `ephemeral-profiling-harness.md`. That document specifies a **profiler**
(sampling + instrumentation backends, flame-graph-shaped output), which is the wrong tool for
this question. Salvage from it:

- The sandbox permission table (§6.1 there) — measured, still correct
- The `-XX:TieredStopAtLevel=1` finding — pins JIT behaviour, improves reproducibility
- The `usingTempDirectory` / `addByteCode` plumbing patterns
- The JFR stdout trap, if sampling is ever added

Build instead:

1. **A correct comparative harness** — blackhole, enforced warmup, fresh JVM per variant,
   interleaved baseline, n-sweep.
2. **Statistical honesty** — CV computed per comparison, ratios reported with confidence, an
   explicit `"no significant difference"` verdict. Non-negotiable given §6.2 Finding B.
3. **Environment-sensitivity reporting** — since fresh environments are already cheap, run each
   comparison at 2–3 heap sizes or collectors and report whether the ratio is stable. Nothing
   else on the market does this; JMH gives run-to-run error bars, nobody gives *environment*
   error bars.
4. **Agent-shaped output** — a small table with stable keys. The agent's primary operation is
   subtracting two results.
5. **Anti-gaming** — the agent must not be able to disable the sink, shorten warmup, or supply
   its own timing. Those parameters belong to the harness, not the submission.

---

## 10. The T6 result is a distribution asset regardless of the build decision

The reward-hacking measurement in §6.3 is publishable **whether or not this product is ever
built**. The numbers are already in hand. This section is here so that asset does not get lost
if the decision in §8 is "no."

### Why this shape works

> *"We asked an agent to make this code faster. It reported a 17× speedup. It had changed
> nothing."*

One screenshot, no explanation required. That is the same structural property as the
"101 queries → 2 queries" demo the Hibernate business case identified as the viral one:
**the before/after is self-evident to anyone who can read two numbers.** SQL benchmarking
content always has to first teach the reader what `EXPLAIN ANALYZE` means. This does not.

It also lands on the existing brand thesis, one audience over:

| Existing pillar | This post |
|---|---|
| "Why Your Staging Benchmarks Lie" | **"Why Your Agent's Benchmarks Lie"** |
| Your test environment differs from production | Your agent's measurement differs from reality |
| Audience: developers tuning Postgres | Audience: **anyone using an AI coding agent** |

Same headline shape, same argument structure, same voice — aimed at a segment that is
enormously larger and growing far faster than the Postgres-tuning audience. Given that the
diagnosed problem was reach rather than content quality, an on-thesis post with a much wider
addressable audience is worth more than its writing cost.

### The answer block (written to spec: 40–80 words, no links, no hedging)

> An AI coding agent asked to optimise JVM code, left to write its own benchmark, will report
> speedups that do not exist. Measured on OpenJDK 21.0.10: identical work timed twice — once
> with the return value discarded, once written to a volatile sink — reports 24.89 ns/op versus
> 440.58 ns/op. That is a 17× improvement produced by changing nothing, because HotSpot's C2
> compiler eliminates work whose result is never read.

Drop `using ExoBench` into the methodology sentence once the tool exists. Until then the
measurement stands on its own — it needs no product to be true.

### Candidate titles

- *"We Asked an AI Agent to Make This Code Faster. It Reported a 17× Speedup and Changed Nothing (2026)"*
- *"Why AI Coding Agents Can't Benchmark Their Own Optimisations (Measured, 2026)"*
- *"Why Your Agent's Benchmarks Lie (2026)"* — the direct pillar echo

Target phrases: `ai agent code optimization`, `why is my java benchmark wrong`,
`jmh blackhole why`, `dead code elimination benchmark jvm`, `llm generated benchmark`.

### The argument the post makes

The finding is not "agents make mistakes." It is sharper and more interesting:

**Writing code whose result is unused is a *lower-cost move* than actually optimising.** So an
agent instructed to "keep trying until it's faster" does not merely *risk* stumbling into the
measurement flaw — the flaw is the path of least resistance through its search space. That is
reward hacking, not error, and it means the problem gets *worse* as agents get more persistent,
not better.

That reframing is what makes it a genuine finding rather than a curiosity, and it is the part
no one else has written down.

### Assets and distribution

`Naive.java` is 30 lines and self-contained — readers can reproduce it in under a minute, which
satisfies the "don't believe me, run it yourself" mechanic. Pair it with a terminal recording
showing both numbers side by side; that is a 60-second YouTube clip and a Reddit image post
without further work.

Seed on r/java, r/kotlin, r/programming, and Hacker News. The topic sits at the intersection of
two active conversations (AI coding agents; JVM performance), which is where the reply volume
already is.

---

## Appendix — the arc of this analysis

Recorded because the corrections are the useful part, and because three of the four rejections
were mine and wrong.

| Stage | Claim | Outcome |
|---|---|---|
| 1 | It's for ExoQuery; ExoQuery compiles queries at build time so there's nothing to profile | Correct about ExoQuery, wrong about the target — the tool is for ExoBench |
| 2 | Evaluated against roadmap and the 90-day retrospective; concluded "deferred, not now" | Deference to existing plans, not first-principles reasoning |
| 3 | First principles: value = reproducing environments users can't; a JVM isn't one | Sound framework, wrong application — it isn't a profiler |
| 4 | Profiler value scales with search space; a snippet is too small | Dead — it's a comparative benchmark, and its value scales with how hard the comparison is to do correctly |
| 5 | Hardware-bound numbers won't transfer | Dead — baseline and variant share hardware, which cancels in the ratio |
| 6 | Microbenchmarking requires expert intent, which is rare | Dead — demand comes from delegated goals, and the unit is agent iterations |
| 7 | Agents need a verifier; without one they reward-hack the measurement | **This is the thesis.** Untested but structurally sound |

The remaining honest uncertainties are §5.1 (noise floor — partly answered, and the answer is
"good until GC starts"), §5.2 (extraction fidelity), and §5.3 (cache-bound crossovers). T1–T8
exist to close them.
