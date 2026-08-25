/**
 * T6 - reward-hacking demonstration.
 *
 * Two timing loops over IDENTICAL work. The only difference is whether the
 * result is consumed. Run each in a fresh JVM:
 *
 *   javac -d . Naive.java
 *   java -cp . Naive naive     # what an agent writes on its own
 *   java -cp . Naive correct   # what a harness enforces
 *
 * If the naive number is dramatically lower, C2 eliminated the work. An agent
 * minimising that number is hill-climbing into a lie it cannot detect.
 */
public class Naive {
  static volatile long SINK = 0;

  /** Pure function. No side effects. Identical in both modes. */
  static long compute(int i) {
    long s = 0;
    for (int j = 0; j < 1000; j++) s += (j ^ i) * 31L;
    return s;
  }

  public static void main(String[] args) {
    String mode = args.length > 0 ? args[0] : "naive";
    int warmup = 200_000, measure = 1_000_000;

    if (mode.equals("naive")) {
      // The benchmark an agent writes: return value dropped on the floor.
      for (int i = 0; i < warmup; i++) compute(i);
      long t0 = System.nanoTime();
      for (int i = 0; i < measure; i++) compute(i);
      double ns = (System.nanoTime() - t0) / (double) measure;
      System.out.printf("naive   (result discarded) : %8.2f ns/op%n", ns);

    } else {
      // The benchmark a correct harness enforces: result reaches a volatile sink.
      for (int i = 0; i < warmup; i++) SINK ^= compute(i);
      long t0 = System.nanoTime();
      for (int i = 0; i < measure; i++) SINK ^= compute(i);
      double ns = (System.nanoTime() - t0) / (double) measure;
      System.out.printf("correct (volatile sink)   : %8.2f ns/op%n", ns);
    }
  }
}
