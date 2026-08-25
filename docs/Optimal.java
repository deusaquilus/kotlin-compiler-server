import java.util.*;

public class Optimal {
    /** Returns, for each value, how many times it occurs. Single pass. */
    public static int[] histogram(int[] values, int range) {
        int[] counts = new int[range];
        for (int v : values) counts[v]++;
        return counts;
    }
    public static void main(String[] args) {
        Random rnd = new Random(7);
        int[] values = new int[60_000_000];
        for (int i = 0; i < values.length; i++) values[i] = rnd.nextInt(1_000);
                int[] h = histogram(values, 1_000);
        System.out.println(h[900]);
    }
}
