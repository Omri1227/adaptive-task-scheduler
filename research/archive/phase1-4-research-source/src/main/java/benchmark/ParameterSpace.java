package benchmark;

import backend.SchedulerConfig;

import java.util.Arrays;
import java.util.Locale;

/**
 * Maps a point of the Phase 2 search space to a {@link SchedulerConfig}.
 *
 * The full space has 9 coordinates u in R (the box [0, 1] is the region of
 * interest; values outside it are valid):
 * <ul>
 *   <li>u0-u2: logits of the static weights (critical path, blocking power, reach),</li>
 *   <li>u3-u7: logits of the dynamic weights (openToBlock, openChildren,
 *       childImportance, durationToReady, childrenToReadyQueue),</li>
 *   <li>u8: logit of lambda (static share).</li>
 * </ul>
 * A logit is z = -5 + 10 u. Each weight group is the softmax of its logits, so
 * the weights are non-negative and sum to 1; lambda = 1 / (1 + e^-z).
 * Scheduler rankings do not depend on the scale of a weight group, so this
 * covers every configuration up to equivalence. Softmax is invariant to a
 * common shift of a group's logits; that direction is flat.
 *
 * Coordinates can be frozen: a frozen coordinate keeps a fixed value and the
 * optimizer works on the remaining "free" coordinates only.
 */
public final class ParameterSpace {

    public static final int FULL_DIMENSION = 9;
    static final double LOGIT_MIN = -5;
    static final double LOGIT_RANGE = 10;

    public static final String[] NAMES = {
            "s.criticalPath", "s.blockingPower", "s.reach",
            "d.openToBlock", "d.openChildren", "d.childImportance", "d.durationToReady", "d.childrenToReadyQueue",
            "lambda"
    };

    private final SchedulerConfig base;
    private final boolean[] free;
    private final double[] fixed;
    private final int[] freeIndex;

    /**
     * @param base   supplies every non-weight setting (lookahead, K, flags)
     * @param free   which of the 9 coordinates the optimizer controls
     * @param fixed  full-space values used for the frozen coordinates
     */
    public ParameterSpace(SchedulerConfig base, boolean[] free, double[] fixed) {
        if (free.length != FULL_DIMENSION || fixed.length != FULL_DIMENSION) {
            throw new IllegalArgumentException("expected " + FULL_DIMENSION + " coordinates");
        }
        this.base = base;
        this.free = free.clone();
        this.fixed = fixed.clone();
        this.freeIndex = java.util.stream.IntStream.range(0, FULL_DIMENSION).filter(i -> free[i]).toArray();
    }

    /** All 9 coordinates free; frozen values (unused) are those of {@code base}. */
    public static ParameterSpace full(SchedulerConfig base) {
        boolean[] all = new boolean[FULL_DIMENSION];
        Arrays.fill(all, true);
        return new ParameterSpace(base, all, encode(base));
    }

    /** Same free/fixed layout over another base configuration. */
    public ParameterSpace withBase(SchedulerConfig newBase) {
        return new ParameterSpace(newBase, free, fixed);
    }

    /** Same base, with the given coordinates frozen at the given full-space values. */
    public ParameterSpace freeze(boolean[] newFree, double[] fixedValues) {
        return new ParameterSpace(base, newFree, fixedValues);
    }

    public int dimension() {
        return freeIndex.length;
    }

    public SchedulerConfig base() {
        return base;
    }

    public boolean isFree(int fullCoordinate) {
        return free[fullCoordinate];
    }

    public double[] fixedValues() {
        return fixed.clone();
    }

    /** Full 9-coordinate vector for a vector of free coordinates. */
    public double[] expand(double[] x) {
        if (x.length != freeIndex.length) {
            throw new IllegalArgumentException("expected " + freeIndex.length + " free coordinates, got " + x.length);
        }
        double[] u = fixed.clone();
        for (int k = 0; k < freeIndex.length; k++) u[freeIndex[k]] = x[k];
        return u;
    }

    /** Free coordinates of a full vector. */
    public double[] restrict(double[] u) {
        double[] x = new double[freeIndex.length];
        for (int k = 0; k < freeIndex.length; k++) x[k] = u[freeIndex[k]];
        return x;
    }

    public SchedulerConfig toConfig(double[] x) {
        return decode(base, expand(x));
    }

    /** Configuration of a full 9-coordinate vector over {@code base}. */
    public static SchedulerConfig decode(SchedulerConfig base, double[] u) {
        double[] s = softmax(u, 0, 3);
        double[] d = softmax(u, 3, 5);
        double lambda = 1 / (1 + Math.exp(-logit(u[8])));
        return base.toBuilder()
                .staticWeights(s[0], s[1], s[2])
                .dynamicWeights(d[0], d[1], d[2], d[3], d[4])
                .staticShare(lambda)
                .build();
    }

    /**
     * Full vector whose decoding has the weights of {@code c} (inverse of decode).
     * Zero weights map to the lowest logit that keeps them finite.
     */
    public static double[] encode(SchedulerConfig c) {
        double[] s = normalized(c.getCriticalPathWeight(), c.getBlockingPowerWeight(), c.getReachableDurationWeight());
        double[] d = normalized(c.getOpenToBlockWeight(), c.getOpenChildrenWeight(), c.getChildImportanceWeight(),
                c.getDurationToReadyWeight(), c.getChildrenToReadyQueueWeight());
        double[] u = new double[FULL_DIMENSION];
        for (int i = 0; i < 3; i++) u[i] = unit(Math.log(Math.max(s[i], 1e-300)));
        for (int i = 0; i < 5; i++) u[3 + i] = unit(Math.log(Math.max(d[i], 1e-300)));
        double lambda = c.getStaticShare();
        u[8] = unit(Math.log(Math.max(lambda, 1e-300)) - Math.log(Math.max(1 - lambda, 1e-300)));
        return u;
    }

    static double logit(double u) {
        return LOGIT_MIN + LOGIT_RANGE * u;
    }

    static double unit(double z) {
        return (z - LOGIT_MIN) / LOGIT_RANGE;
    }

    private static double[] softmax(double[] u, int from, int count) {
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) max = Math.max(max, logit(u[from + i]));
        double[] w = new double[count];
        double sum = 0;
        for (int i = 0; i < count; i++) {
            w[i] = Math.exp(logit(u[from + i]) - max);
            sum += w[i];
        }
        for (int i = 0; i < count; i++) w[i] /= sum;
        return w;
    }

    private static double[] normalized(double... w) {
        double sum = 0;
        for (double x : w) sum += x;
        double[] r = new double[w.length];
        for (int i = 0; i < w.length; i++) r[i] = w[i] / sum;
        return r;
    }

    /** Compact description of the weights of a full vector, for logs. */
    public static String describe(double[] u) {
        SchedulerConfig c = decode(SchedulerConfig.DEFAULT, u);
        return String.format(Locale.ROOT, "static=[%.4f %.4f %.4f] dynamic=[%.4f %.4f %.4f %.4f %.4f] lambda=%.4f",
                c.getCriticalPathWeight(), c.getBlockingPowerWeight(), c.getReachableDurationWeight(),
                c.getOpenToBlockWeight(), c.getOpenChildrenWeight(), c.getChildImportanceWeight(),
                c.getDurationToReadyWeight(), c.getChildrenToReadyQueueWeight(), c.getStaticShare());
    }
}
