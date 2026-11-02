package benchmark;

import java.util.Random;

/**
 * Sobol low-discrepancy sequence in up to 13 dimensions (Joe-Kuo direction
 * numbers, new-joe-kuo-6.21201), randomized by a seeded digital shift.
 * Point k is a pure function of (dimension, seed, k).
 */
public final class SobolSequence {

    private static final int BITS = 32;
    // {degree s, coefficient a, m_1..m_s} for dimensions 2..13 (dimension 1 is the van der Corput sequence)
    private static final int[][] DIRECTIONS = {
            {1, 0, 1},
            {2, 1, 1, 3},
            {3, 1, 1, 3, 1},
            {3, 2, 1, 1, 1},
            {4, 1, 1, 1, 3, 3},
            {4, 4, 1, 3, 5, 13},
            {5, 2, 1, 1, 5, 5, 17},
            {5, 4, 1, 1, 5, 5, 5},
            {5, 7, 1, 1, 7, 11, 19},
            {5, 11, 1, 1, 5, 1, 1},
            {5, 13, 1, 1, 1, 3, 11},
            {5, 14, 1, 3, 5, 5, 31},
    };
    public static final int MAX_DIMENSION = DIRECTIONS.length + 1;

    private final int dimension;
    private final long[][] v;     // direction numbers, scaled to BITS bits
    private final long[] shift;

    public SobolSequence(int dimension, long seed) {
        if (dimension < 1 || dimension > MAX_DIMENSION) {
            throw new IllegalArgumentException("dimension must be in [1, " + MAX_DIMENSION + "]");
        }
        this.dimension = dimension;
        this.v = new long[dimension][BITS];
        for (int i = 0; i < BITS; i++) v[0][i] = 1L << (BITS - 1 - i);
        for (int dim = 1; dim < dimension; dim++) {
            int[] spec = DIRECTIONS[dim - 1];
            int s = spec[0];
            int a = spec[1];
            for (int i = 0; i < s && i < BITS; i++) v[dim][i] = (long) spec[2 + i] << (BITS - 1 - i);
            for (int i = s; i < BITS; i++) {
                long x = v[dim][i - s] ^ (v[dim][i - s] >>> s);
                for (int k = 1; k < s; k++) {
                    if (((a >>> (s - 1 - k)) & 1) != 0) x ^= v[dim][i - k];
                }
                v[dim][i] = x;
            }
        }
        Random r = new Random(seed);
        shift = new long[dimension];
        for (int dim = 0; dim < dimension; dim++) shift[dim] = r.nextLong() & ((1L << BITS) - 1);
    }

    /** Point number {@code index} (0-based) in [0, 1)^dimension. */
    public double[] point(long index) {
        double[] p = new double[dimension];
        long gray = index ^ (index >>> 1);
        for (int dim = 0; dim < dimension; dim++) {
            long x = 0;
            for (int bit = 0; bit < BITS && (gray >>> bit) != 0; bit++) {
                if (((gray >>> bit) & 1) != 0) x ^= v[dim][bit];
            }
            p[dim] = ((x ^ shift[dim]) & ((1L << BITS) - 1)) / (double) (1L << BITS);
        }
        return p;
    }
}
