import java.util.*;

public class PairCounter {

    /**
     * Counts the number of index pairs (i, j) with i < j where
     * values[i] + values[j] == target.
     */
    public static long countPairs(int[] values, int target) {
        long count = 0;
        for (int i = 0; i < values.length; i++) {
            for (int j = i + 1; j < values.length; j++) {
                if (values[i] + values[j] == target) {
                    count++;
                }
            }
        }
        return count;
    }

    public static void main(String[] args) {
        Random rnd = new Random(7);
        int[] values = new int[60_000];
        for (int i = 0; i < values.length; i++) {
            values[i] = rnd.nextInt(1_000);
        }
        System.out.println(countPairs(values, 900));
    }
}
