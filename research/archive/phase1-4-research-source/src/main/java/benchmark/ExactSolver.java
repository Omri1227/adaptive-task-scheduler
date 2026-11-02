package benchmark;

import backend.Task;

import java.util.*;

/**
 * Exact makespan for small P|prec|Cmax instances (identical workers), by branch
 * and bound over decision times.
 *
 * optimal(): at every completion time any subset of ready tasks (at most the free
 * workers) may start, including none while others run. Every active schedule
 * starts tasks only at time 0 or at completion times, and an optimal active
 * schedule always exists, so this is the true optimum.
 *
 * bestNonDelay(): free workers must take ready tasks whenever possible (exactly
 * min(free, ready) tasks start), i.e. the best schedule any list-scheduling
 * priority rule, and therefore any weight setting of the adaptive scheduler,
 * can produce.
 *
 * Both return -1 if the node limit is exceeded.
 */
public final class ExactSolver {

    private final int n;
    private final int m;
    private final int[] duration;
    private final long[] parentMask;
    private final int[] tail;          // bottom level: longest path from task start to the end, including itself
    private final boolean nonDelay;
    private final long nodeLimit;
    private long nodes;
    private int best;
    private final int lowerBound;
    private final long all;

    private ExactSolver(LinkedList<Task> tasks, int workers, boolean nonDelay, long nodeLimit) {
        if (tasks.size() > 62) throw new IllegalArgumentException("exact solver supports at most 62 tasks");
        this.n = tasks.size();
        this.m = workers;
        this.nonDelay = nonDelay;
        this.nodeLimit = nodeLimit;
        this.duration = new int[n];
        this.parentMask = new long[n];
        this.tail = new int[n];
        Map<Integer, Integer> index = new HashMap<>();
        int k = 0;
        for (Task t : tasks) index.put(t.getId(), k++);
        k = 0;
        int work = 0;
        for (Task t : tasks) {
            duration[k] = t.getDuration();
            work += t.getDuration();
            for (int p : t.getDependsOnIDS()) parentMask[k] |= 1L << index.get(p);
            k++;
        }
        // tails in reverse topological order (repeat relaxation; n is small)
        for (int iter = 0; iter < n; iter++) {
            for (int i = 0; i < n; i++) {
                int maxChild = 0;
                for (int c = 0; c < n; c++) if ((parentMask[c] & (1L << i)) != 0) maxChild = Math.max(maxChild, tail[c]);
                tail[i] = duration[i] + maxChild;
            }
        }
        int cp = 0;
        for (int i = 0; i < n; i++) cp = Math.max(cp, tail[i]);
        this.lowerBound = Math.max(cp, (work + m - 1) / m);
        this.all = n == 64 ? -1L : (1L << n) - 1;
    }

    public static int optimal(LinkedList<Task> tasks, int workers, int upperBound, long nodeLimit) {
        return new ExactSolver(tasks, workers, false, nodeLimit).run(upperBound);
    }

    public static int bestNonDelay(LinkedList<Task> tasks, int workers, int upperBound, long nodeLimit) {
        return new ExactSolver(tasks, workers, true, nodeLimit).run(upperBound);
    }

    private int run(int upperBound) {
        best = upperBound + 1; // search for anything at most upperBound
        int[] end = new int[n];
        Arrays.fill(end, -1);
        boolean complete = search(0, 0L, 0L, end);
        if (!complete) return -1;
        return best <= upperBound ? best : upperBound;
    }

    /** @return false if the node limit was hit */
    private boolean search(int t, long done, long started, int[] end) {
        if (++nodes > nodeLimit) return false;
        if (best == lowerBound) return true;
        if (done == all) {
            best = Math.min(best, t);
            return true;
        }
        int running = Long.bitCount(started & ~done);
        int free = m - running;
        long ready = 0;
        for (int i = 0; i < n; i++) {
            if ((started & (1L << i)) == 0 && (parentMask[i] & ~done) == 0) ready |= 1L << i;
        }
        int readyCount = Long.bitCount(ready);
        int take = Math.min(free, readyCount);
        int[] readyIdx = new int[readyCount];
        for (int i = 0, k = 0; i < n; i++) if ((ready & (1L << i)) != 0) readyIdx[k++] = i;

        // subsets of the ready tasks, larger first (good schedules first improve pruning)
        int minSize = nonDelay ? take : 0;
        for (int size = take; size >= minSize; size--) {
            if (size == 0 && running == 0) continue; // nothing would ever happen
            if (!subsets(readyIdx, size, 0, 0L, t, done, started, end)) return false;
            if (best == lowerBound) return true;
        }
        return true;
    }

    private boolean subsets(int[] readyIdx, int size, int from, long chosen, int t, long done, long started, int[] end) {
        if (Long.bitCount(chosen) == size) {
            return branch(chosen, t, done, started, end);
        }
        int need = size - Long.bitCount(chosen);
        for (int i = from; i <= readyIdx.length - need; i++) {
            if (!subsets(readyIdx, size, i + 1, chosen | (1L << readyIdx[i]), t, done, started, end)) return false;
            if (best == lowerBound) return true;
        }
        return true;
    }

    private boolean branch(long chosen, int t, long done, long started, int[] end) {
        long newStarted = started | chosen;
        int[] newEnd = end.clone();
        for (int i = 0; i < n; i++) if ((chosen & (1L << i)) != 0) newEnd[i] = t + duration[i];

        int next = Integer.MAX_VALUE;
        long runningMask = newStarted & ~done;
        long remainingWork = 0;
        int bound = t;
        for (int i = 0; i < n; i++) {
            long bit = 1L << i;
            if ((runningMask & bit) != 0) {
                next = Math.min(next, newEnd[i]);
                remainingWork += newEnd[i] - t;
                bound = Math.max(bound, newEnd[i] - duration[i] + tail[i]);
            } else if ((newStarted & bit) == 0) {
                remainingWork += duration[i];
            }
        }
        // ready tasks not started now can start at the next completion at the earliest
        for (int i = 0; i < n; i++) {
            long bit = 1L << i;
            if ((newStarted & bit) == 0 && (parentMask[i] & ~done) == 0) {
                bound = Math.max(bound, next + tail[i]);
            }
        }
        bound = (int) Math.max(bound, t + (remainingWork + m - 1) / m);
        if (bound >= best) return true;

        long newDone = done;
        for (int i = 0; i < n; i++) {
            if ((runningMask & (1L << i)) != 0 && newEnd[i] == next) newDone |= 1L << i;
        }
        return search(next, newDone, newStarted, newEnd);
    }
}
