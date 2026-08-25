import java.util.*;
import java.util.function.*;

/**
 * Minimal CORRECT comparative microbenchmark harness.
 *   java Bench <workload> <n> <warmupMs> <measureMs>
 * Prints one line: ns/op for ONE fresh JVM. Fork externally for isolation.
 */
public class Bench {
  // volatile sink: prevents dead-code elimination of the measured work
  public static volatile long SINK = 0;
  public static volatile boolean TOUCHED = false;

  static int[] arr;  static HashSet<Integer> set;  static int[] probes;

  static void setup(String w, int n) {
    Random r = new Random(42);                 // fixed seed = reproducible
    arr = new int[n];
    for (int i = 0; i < n; i++) arr[i] = i * 2;
    set = new HashSet<>();
    for (int v : arr) set.add(v);
    probes = new int[1024];
    for (int i = 0; i < probes.length; i++) probes[i] = r.nextInt(n * 2);
  }

  static long body(String w) {
    switch (w) {
      case "arith-a": case "arith-b": {        // identical workloads: noise floor
        long s = 0; for (int i = 0; i < 1000; i++) s += (i ^ 0x5f) * 31L; return s;
      }
      case "linear": {                          // O(n) scan - cache friendly
        long hits = 0;
        for (int p : probes) { for (int v : arr) if (v == p) { hits++; break; } }
        return hits;
      }
      case "hashset": {                         // O(1) lookup - pointer chasing
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
