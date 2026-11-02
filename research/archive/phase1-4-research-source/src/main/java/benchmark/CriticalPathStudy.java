package benchmark;

import backend.SchedulerConfig;
import backend.StaticGraphAnalysis;
import backend.Task;
import benchmark.ExperimentRunner.Results;
import benchmark.ExperimentRunner.Variant;
import benchmark.InstanceGenerator.DurationDistribution;
import benchmark.InstanceGenerator.Family;
import benchmark.InstanceGenerator.Instance;
import benchmark.InstanceGenerator.SizeBucket;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

/**
 * Phase 3 research harness: where and why Critical Path First (CP-first) schedules
 * badly, and whether selective lookahead or targeted priority changes help.
 *
 * Experimental code only. Nothing in backend calls it, and it changes no
 * production behaviour. It contains its own array-based list scheduler (verified
 * to reproduce BenchmarkEngine's CP-first with BATCHED completions exactly), a
 * counterfactual "swap one decision, then continue CP-first" analysis, and an
 * experimental pilot (rollout) policy with pluggable triggers.
 *
 * Usage: java -cp target/classes benchmark.CriticalPathStudy <stage> [dir] [threads]
 */
public final class CriticalPathStudy {

    static final long EXPLORE_SEED = 81001;
    static final long EXACT_SEED = 81002;
    static final long DEV_SEED = 81003;
    static final long FINAL_SEED = 81004;
    static final long OOD_SEED = 81005;
    static final long FINAL_EXACT_SEED = 81006;
    static final long PARETO_SEED = 81007;
    static final long TIMING_SEED = 81008;

    /** Families and durations used for exploration and selection; FFT, Gaussian, BIMODAL, Pareto and XL are held out. */
    static final Family[] CORE_FAMILIES = WeightOptimizer.TRAIN_FAMILIES;
    static final DurationDistribution[] CORE_DURATIONS = WeightOptimizer.TRAIN_DURATIONS;
    static final SizeBucket[] CORE_BUCKETS = {SizeBucket.SMALL, SizeBucket.MEDIUM, SizeBucket.LARGE};
    static final int EXACT_MAX_TASKS = 18;
    static final long EXACT_NODE_LIMIT = 20_000_000L;
    static final int FULL = Integer.MAX_VALUE;

    final Path dir;
    final ForkJoinPool pool;

    CriticalPathStudy(Path dir, int threads) {
        this.dir = dir;
        this.pool = new ForkJoinPool(threads);
    }

    // ------------------------------------------------------------------ graph

    /** Immutable array view of an instance; task index = position in the task list (the tie-break order). */
    static final class Graph {
        final Instance inst;
        final int n;
        final int m;
        final int[] dur;
        final int[][] children;
        final int[][] parents;
        final int[] topo;
        final int[] cp;        // bottom level: longest path from the task's start to the end, including itself
        final int[] est;       // top level: earliest start ignoring workers
        final int[] slack;     // CP - (est + cp)
        final int[] desc;      // number of descendants
        final long[] reach;    // total duration of descendants
        final int[] level;     // longest edge count from a source
        final int criticalPath;
        final long work;

        Graph(Instance x) {
            inst = x;
            n = x.tasks.size();
            m = x.workers;
            dur = new int[n];
            Map<Integer, Integer> index = new HashMap<>(2 * n);
            int k = 0;
            for (Task t : x.tasks) index.put(t.getId(), k++);
            List<List<Integer>> ch = new ArrayList<>();
            for (int i = 0; i < n; i++) ch.add(new ArrayList<>());
            parents = new int[n][];
            k = 0;
            long w = 0;
            for (Task t : x.tasks) {
                dur[k] = t.getDuration();
                w += dur[k];
                parents[k] = t.getDependsOnIDS().stream().mapToInt(index::get).distinct().toArray();
                for (int p : parents[k]) ch.get(p).add(k);
                k++;
            }
            work = w;
            children = new int[n][];
            for (int i = 0; i < n; i++) children[i] = ch.get(i).stream().mapToInt(Integer::intValue).toArray();
            topo = new int[n];
            int[] indeg = new int[n];
            for (int i = 0; i < n; i++) indeg[i] = parents[i].length;
            ArrayDeque<Integer> q = new ArrayDeque<>();
            for (int i = 0; i < n; i++) if (indeg[i] == 0) q.add(i);
            int c = 0;
            while (!q.isEmpty()) {
                int v = q.poll();
                topo[c++] = v;
                for (int ch2 : children[v]) if (--indeg[ch2] == 0) q.add(ch2);
            }
            if (c != n) throw new IllegalStateException("cycle in " + x.id);
            cp = new int[n];
            desc = new int[n];
            reach = new long[n];
            BitSet[] below = new BitSet[n];
            for (int j = n - 1; j >= 0; j--) {
                int v = topo[j];
                int best = 0;
                BitSet b = new BitSet(n);
                for (int ch2 : children[v]) {
                    best = Math.max(best, cp[ch2]);
                    b.set(ch2);
                    b.or(below[ch2]);
                }
                cp[v] = dur[v] + best;
                below[v] = b;
                desc[v] = b.cardinality();
                long r = 0;
                for (int d = b.nextSetBit(0); d >= 0; d = b.nextSetBit(d + 1)) r += dur[d];
                reach[v] = r;
            }
            est = new int[n];
            level = new int[n];
            int cpMax = 0;
            for (int v : topo) {
                for (int p : parents[v]) {
                    est[v] = Math.max(est[v], est[p] + dur[p]);
                    level[v] = Math.max(level[v], level[p] + 1);
                }
                cpMax = Math.max(cpMax, cp[v]);
            }
            criticalPath = cpMax;
            slack = new int[n];
            for (int i = 0; i < n; i++) slack[i] = cpMax - est[i] - cp[i];
        }

        int lowerBound() {
            return (int) Math.max(criticalPath, (work + m - 1) / m);
        }

        /** Number of distinct longest (critical) source-to-sink paths. */
        double criticalPathCount() {
            double[] cnt = new double[n];
            double total = 0;
            for (int v : topo) {
                if (slack[v] != 0) continue;
                if (est[v] == 0) cnt[v] = 1;
                for (int p : parents[v]) if (slack[p] == 0 && est[p] + dur[p] == est[v]) cnt[v] += cnt[p];
                if (est[v] + dur[v] == criticalPath) total += cnt[v];
            }
            return total;
        }
    }

    // ------------------------------------------------------------------ heaps

    /** Ready tasks, best first: larger key, then smaller index. Supports removal of any member. */
    static final class ReadyHeap {
        final long[] key;
        int[] heap;
        int[] pos;
        int size;

        ReadyHeap(long[] key, int n) {
            this.key = key;
            heap = new int[n];
            pos = new int[n];
            Arrays.fill(pos, -1);
        }

        ReadyHeap copy() {
            ReadyHeap h = new ReadyHeap(key, 0);
            h.heap = heap.clone();
            h.pos = pos.clone();
            h.size = size;
            return h;
        }

        boolean better(int a, int b) {
            return key[a] > key[b] || (key[a] == key[b] && a < b);
        }

        void add(int v) {
            heap[size] = v;
            pos[v] = size;
            up(size++);
        }

        int peek() {
            return heap[0];
        }

        void remove(int v) {
            int i = pos[v];
            int last = heap[--size];
            pos[v] = -1;
            if (i == size) return;
            heap[i] = last;
            pos[last] = i;
            up(i);
            down(pos[last]);
        }

        boolean contains(int v) {
            return pos[v] >= 0;
        }

        /** The best k members in order, without modifying the heap. */
        int[] top(int k) {
            k = Math.min(k, size);
            int[] out = new int[k];
            // best-first expansion of the heap tree with a small frontier
            int[] frontier = new int[2 * k + 2];
            int fs = 0;
            if (size > 0) frontier[fs++] = 0;
            for (int j = 0; j < k; j++) {
                int bi = 0;
                for (int q = 1; q < fs; q++) if (better(heap[frontier[q]], heap[frontier[bi]])) bi = q;
                int node = frontier[bi];
                frontier[bi] = frontier[--fs];
                out[j] = heap[node];
                if (2 * node + 1 < size) frontier[fs++] = 2 * node + 1;
                if (2 * node + 2 < size) frontier[fs++] = 2 * node + 2;
            }
            return out;
        }

        private void up(int i) {
            int v = heap[i];
            while (i > 0) {
                int p = (i - 1) / 2;
                if (!better(v, heap[p])) break;
                heap[i] = heap[p];
                pos[heap[i]] = i;
                i = p;
            }
            heap[i] = v;
            pos[v] = i;
        }

        private void down(int i) {
            int v = heap[i];
            while (true) {
                int l = 2 * i + 1;
                if (l >= size) break;
                int r = l + 1;
                int c = (r < size && better(heap[r], heap[l])) ? r : l;
                if (!better(heap[c], v)) break;
                heap[i] = heap[c];
                pos[heap[i]] = i;
                i = c;
            }
            heap[i] = v;
            pos[v] = i;
        }
    }

    /** Running tasks by finish time. */
    static final class FinishHeap {
        int[] heap;
        int size;

        FinishHeap(int n) {
            heap = new int[Math.max(1, n)];
        }

        FinishHeap copy() {
            FinishHeap h = new FinishHeap(0);
            h.heap = heap.clone();
            h.size = size;
            return h;
        }

        void add(int v, int[] fin) {
            int i = size++;
            while (i > 0) {
                int p = (i - 1) / 2;
                if (fin[heap[p]] <= fin[v]) break;
                heap[i] = heap[p];
                i = p;
            }
            heap[i] = v;
        }

        int poll(int[] fin) {
            int top = heap[0];
            int v = heap[--size];
            int i = 0;
            while (true) {
                int l = 2 * i + 1;
                if (l >= size) break;
                int r = l + 1;
                int c = (r < size && fin[heap[r]] < fin[heap[l]]) ? r : l;
                if (fin[heap[c]] >= fin[v]) break;
                heap[i] = heap[c];
                i = c;
            }
            if (size > 0) heap[i] = v;
            return top;
        }
    }

    // ------------------------------------------------------------------ list scheduler

    /** Chooses the next task for a free worker (only called when a worker is free and a task is ready). */
    interface Policy {
        int choose(Sim s);
    }

    /**
     * Non-delay list scheduling with BATCHED completions: at each event time all
     * completions are applied, then every free worker takes a ready task.
     * {@code key} is the continuation priority used when no policy is given.
     */
    /** Per-thread count of task starts in all simulations: an implementation-independent cost measure. */
    static final ThreadLocal<long[]> WORK = ThreadLocal.withInitial(() -> new long[1]);

    static final class Sim {
        final Graph g;
        final long[] key;
        final long[] work;
        int t;
        int free;
        int done;
        int events;
        long unfinishedWork;
        int[] rem;
        int[] start;
        int[] fin;
        ReadyHeap ready;
        FinishHeap running;

        Sim(Graph g, long[] key) {
            this.g = g;
            this.key = key;
            this.work = WORK.get();
            free = g.m;
            unfinishedWork = g.work;
            rem = new int[g.n];
            start = new int[g.n];
            fin = new int[g.n];
            Arrays.fill(start, -1);
            ready = new ReadyHeap(key, g.n);
            running = new FinishHeap(g.m);
            for (int i = 0; i < g.n; i++) {
                rem[i] = g.parents[i].length;
                if (rem[i] == 0) ready.add(i);
            }
        }

        private Sim(Sim o) {
            g = o.g;
            key = o.key;
            work = o.work;
            t = o.t;
            free = o.free;
            done = o.done;
            events = o.events;
            unfinishedWork = o.unfinishedWork;
            rem = o.rem.clone();
            start = o.start.clone();
            fin = o.fin.clone();
            ready = o.ready.copy();
            running = o.running.copy();
        }

        Sim copy() {
            return new Sim(this);
        }

        void startTask(int v) {
            work[0]++;
            ready.remove(v);
            start[v] = t;
            fin[v] = t + g.dur[v];
            running.add(v, fin);
            free--;
        }

        void advance() {
            t = fin[running.heap[0]];
            while (running.size > 0 && fin[running.heap[0]] == t) {
                int v = running.poll(fin);
                done++;
                free++;
                unfinishedWork -= g.dur[v];
                for (int c : g.children[v]) if (--rem[c] == 0) ready.add(c);
            }
            events++;
        }

        /** Lower bound on the final makespan from the current state (used as the depth-limited rollout estimate). */
        int estimate() {
            long e = t;
            long elapsed = 0;
            for (int q = 0; q < running.size; q++) {
                int v = running.heap[q];
                e = Math.max(e, start[v] + g.cp[v]);
                elapsed += t - start[v];
            }
            for (int q = 0; q < ready.size; q++) e = Math.max(e, t + g.cp[ready.heap[q]]);
            long remaining = unfinishedWork - elapsed;
            e = Math.max(e, t + (remaining + g.m - 1) / g.m);
            return (int) e;
        }

        /** Runs to the end (or for maxEvents completion events, then returns the estimate). */
        int run(Policy p, int maxEvents) {
            int base = events;
            while (done < g.n) {
                while (free > 0 && ready.size > 0) startTask(p == null ? ready.peek() : p.choose(this));
                if (events - base >= maxEvents) return estimate();
                advance();
            }
            return t;
        }
    }

    // ------------------------------------------------------------------ priority rules

    static final long SHIFT = 1L << 32;

    /** Static priority keys; larger is better, ties by task index. */
    enum Rule {
        CP("CP-first"),
        CP_TIE_CHILDREN("CP, ties: more children"),
        CP_TIE_DESC("CP, ties: more descendants"),
        CP_TIE_REACH("CP, ties: more downstream work"),
        CP_TIE_LONG("CP, ties: longer task"),
        CP_TIE_SHORT("CP, ties: shorter task"),
        CP_TIE_UNLOCK("CP, ties: more sole-parent children"),
        LDW("largest downstream work"),
        LPT("longest task"),
        SPT("shortest task"),
        MOST_DESC("most descendants");

        final String label;

        Rule(String label) {
            this.label = label;
        }

        long[] keys(Graph g) {
            long[] k = new long[g.n];
            for (int i = 0; i < g.n; i++) {
                long cp = g.cp[i];
                k[i] = switch (this) {
                    case CP -> cp;
                    case CP_TIE_CHILDREN -> cp * SHIFT + g.children[i].length;
                    case CP_TIE_DESC -> cp * SHIFT + g.desc[i];
                    case CP_TIE_REACH -> cp * SHIFT + g.reach[i];
                    case CP_TIE_LONG -> cp * SHIFT + g.dur[i];
                    case CP_TIE_SHORT -> cp * SHIFT + (SHIFT - 1 - g.dur[i]);
                    case CP_TIE_UNLOCK -> cp * SHIFT + soleParentChildren(g, i);
                    case LDW -> g.reach[i] + g.dur[i];   // production definition includes the task itself
                    case LPT -> g.dur[i];
                    case SPT -> -g.dur[i];
                    case MOST_DESC -> g.desc[i];
                };
            }
            return k;
        }
    }

    static int soleParentChildren(Graph g, int v) {
        int c = 0;
        for (int ch : g.children[v]) if (g.parents[ch].length == 1) c++;
        return c;
    }

    static int listSchedule(Graph g, long[] key) {
        return new Sim(g, key).run(null, FULL);
    }

    // ------------------------------------------------------------------ pilot (selective rollout)

    /** Decides at a contested pick whether the pilot evaluates alternatives. */
    interface Trigger {
        /** {@code ranked}: best ready tasks by CP order; ranked[free-1] is the last task CP-first would start now, ranked[free] the first it would defer. */
        boolean fire(Sim s, int[] ranked, int free);
    }

    static final Trigger ALWAYS = (s, r, f) -> true;

    /** Relative CP gap between the last task CP-first starts and the first it defers is at most theta. */
    static Trigger gapAtMost(double theta) {
        return (s, r, f) -> {
            int a = s.g.cp[r[f - 1]];
            int b = s.g.cp[r[f]];
            return a - b <= theta * a;
        };
    }

    /**
     * Pilot method on top of a priority rule: at a contested pick, the rule's choice
     * is compared with the L-1 best tasks it would defer; each is started now and
     * the schedule is continued with the rule (to the end, or {@code depth} events
     * and estimated). The smallest value wins; ties keep the rule's choice.
     */
    static final class Pilot implements Policy {
        final int candidates;
        final int depth;
        final Trigger trigger;
        long picks;
        long contested;
        long fired;
        long simulations;
        private int settledTime = -1;

        Pilot(int candidates, int depth, Trigger trigger) {
            this.candidates = candidates;
            this.depth = depth;
            this.trigger = trigger;
        }

        @Override
        public int choose(Sim s) {
            picks++;
            int r = s.ready.size;
            int f = s.free;
            if (r <= f || s.t == settledTime) return s.ready.peek();
            contested++;
            int[] ranked = s.ready.top(f + candidates - 1);
            if (!trigger.fire(s, ranked, f)) {
                settledTime = s.t;
                return ranked[0];
            }
            fired++;
            int best = ranked[0];
            int bestValue = evaluate(s, ranked[0]);
            for (int q = f; q < ranked.length; q++) {
                int v = evaluate(s, ranked[q]);
                if (v < bestValue) {
                    best = ranked[q];
                    bestValue = v;
                }
            }
            // with the rule's own choice kept, later picks at this time face the same comparison
            if (best == ranked[0]) settledTime = s.t;
            return best;
        }

        private int evaluate(Sim s, int candidate) {
            Sim x = s.copy();
            x.startTask(candidate);
            simulations++;
            return x.run(null, depth);
        }
    }

    static int pilotSchedule(Graph g, long[] key, Pilot p) {
        return new Sim(g, key).run(p, FULL);
    }

    /** Deferred task is near-critical: its CP is at least rho times the estimated remaining time. */
    static Trigger nearCritical(double rho) {
        return (s, r, f) -> s.g.cp[r[f]] >= rho * Math.max(1, s.estimate() - s.t);
    }

    /** Only one worker is free (the others are busy). */
    static final Trigger SINGLE_FREE = (s, r, f) -> f == 1;

    /**
     * Next-completion-aware CP (mechanism-derived, no simulation). Failure mechanism
     * from the case studies: with one free worker, CP-first gives it the most critical
     * task a, and the best deferred task b waits until another worker frees at
     * t_next; a shorter b started now would have released its successors earlier.
     * Compares the critical-path bounds of the two orders:
     * A (a now, b at t_next): max(t + cp(a), t_next + cp(b));
     * B (b now, a at min(t + dur(b), t_next)): max(t + cp(b), min(t + dur(b), t_next) + cp(a)),
     * and starts the deferred task with the largest strict improvement. Applies only
     * to contested picks with exactly one free worker.
     */
    static final class NextCompletionAware implements Policy {
        final int candidates;

        NextCompletionAware(int candidates) {
            this.candidates = candidates;
        }

        @Override
        public int choose(Sim s) {
            int a = s.ready.peek();
            if (s.free != 1 || s.ready.size <= 1 || s.running.size == 0) return a;
            Graph g = s.g;
            int tNext = s.fin[s.running.heap[0]];
            int[] ranked = s.ready.top(candidates);
            int best = a;
            long bestGain = 0;
            for (int q = 1; q < ranked.length; q++) {
                int b = ranked[q];
                long boundA = Math.max(s.t + g.cp[a], tNext + g.cp[b]);
                long boundB = Math.max(s.t + g.cp[b], Math.min(s.t + g.dur[b], tNext) + g.cp[a]);
                if (boundA - boundB > bestGain) {
                    bestGain = boundA - boundB;
                    best = b;
                }
            }
            return best;
        }
    }

    /** Best of CP-first and R list schedules with CP priorities multiplied by (1 + eps * U[0,1)); offline only. */
    static int multiStart(Graph g, int restarts, double eps) {
        long[] key = Rule.CP.keys(g);
        int best = listSchedule(g, key);
        Random r = new Random(g.inst.id.hashCode());
        long[] noisy = new long[g.n];
        for (int k = 0; k < restarts; k++) {
            for (int i = 0; i < g.n; i++) noisy[i] = (long) (g.cp[i] * (1 + eps * r.nextDouble()) * 4096);
            best = Math.min(best, listSchedule(g, noisy));
        }
        return best;
    }

    // ------------------------------------------------------------------ variants

    /** A scheduler under study: production code (via ExperimentRunner.Variant) or a study policy. */
    interface Scheduler {
        String name();

        int run(Instance x, Graph g, StaticGraphAnalysis an);
    }

    record Production(String name, Variant v) implements Scheduler {
        public int run(Instance x, Graph g, StaticGraphAnalysis an) {
            return v.run(x, an);
        }
    }

    record ListRule(String name, Rule rule) implements Scheduler {
        public int run(Instance x, Graph g, StaticGraphAnalysis an) {
            return listSchedule(g, rule.keys(g));
        }
    }

    /** Pilot on CP-first; {@code maxTasks} > 0 gates the pilot to graphs with at most that many tasks. */
    record PilotVariant(String name, int candidates, int depth, java.util.function.DoubleFunction<Trigger> trigger,
                        double theta, int maxTasks) implements Scheduler {
        public int run(Instance x, Graph g, StaticGraphAnalysis an) {
            long[] key = Rule.CP.keys(g);
            if (maxTasks > 0 && g.n > maxTasks) return listSchedule(g, key);
            return pilotSchedule(g, key, new Pilot(candidates, depth, trigger.apply(theta)));
        }
    }

    /** Pilot on CP-first with an arbitrary trigger. */
    record TriggeredPilot(String name, int candidates, int depth, Trigger trigger) implements Scheduler {
        public int run(Instance x, Graph g, StaticGraphAnalysis an) {
            return pilotSchedule(g, Rule.CP.keys(g), new Pilot(candidates, depth, trigger));
        }
    }

    /** A policy-driven list schedule (no simulation unless the policy simulates). */
    record PolicyVariant(String name, java.util.function.Supplier<Policy> policy) implements Scheduler {
        public int run(Instance x, Graph g, StaticGraphAnalysis an) {
            return new Sim(g, Rule.CP.keys(g)).run(policy.get(), FULL);
        }
    }

    record MultiStart(String name, int restarts, double eps) implements Scheduler {
        public int run(Instance x, Graph g, StaticGraphAnalysis an) {
            return multiStart(g, restarts, eps);
        }
    }

    /** Graph-level selection: {@code chosen} where the predicate holds, otherwise {@code fallback}. */
    record Gate(String name, java.util.function.Predicate<Graph> when, Scheduler chosen, Scheduler fallback) implements Scheduler {
        public int run(Instance x, Graph g, StaticGraphAnalysis an) {
            return when.test(g) ? chosen.run(x, g, an) : fallback.run(x, g, an);
        }
    }

    static final Variant CP_PROD = Variant.baseline(BenchmarkStrategy.CRITICAL_PATH_FIRST, SchedulerConfig.CompletionMode.BATCHED);
    static final Scheduler ORIGINAL = new Production("ORIGINAL", Variant.adaptive("ORIGINAL", "ref", SchedulerConfig.DEFAULT));
    static final Scheduler CP_FIRST = new Production("CP-first", CP_PROD);
    static final Scheduler FAST = new Production("A* FAST_OPTIMIZED", Variant.adaptive("A*", "ref", SchedulerConfig.FAST_OPTIMIZED));
    static final Scheduler QUALITY = new Production("B* QUALITY_OPTIMIZED", Variant.adaptive("B*", "ref", SchedulerConfig.QUALITY_OPTIMIZED));
    static final Scheduler PHASE1 = new Production("B0 PHASE1_ROLLOUT", Variant.adaptive("B0", "ref", SchedulerConfig.PHASE1_ROLLOUT));

    static Scheduler quality(String name, SchedulerConfig.GapCondition c, double tau) {
        return new Production(name, Variant.adaptive(name, "prod-selective",
                SchedulerConfig.QUALITY_OPTIMIZED.toBuilder().gapCondition(c).lookaheadGapThreshold(tau).build()));
    }

    static Scheduler pilot(int l, int depth, double theta) {
        String d = depth == FULL ? "full" : "d" + depth;
        String trig = theta >= 1 ? "every contested pick" : "CP gap <= " + theta;
        return new PilotVariant("pilot L" + l + " " + d + ", " + trig, l, depth, CriticalPathStudy::gapAtMost, theta, 0);
    }

    /** Variants of the exploration and exact stages (references, list rules, one strong pilot). */
    static List<Scheduler> exploreSchedulers() {
        List<Scheduler> v = new ArrayList<>(List.of(CP_FIRST, ORIGINAL, FAST, PHASE1, QUALITY));
        for (Rule r : Rule.values()) v.add(new ListRule(r == Rule.CP ? "CP-first (study sim)" : r.label, r));
        v.add(pilot(4, FULL, 1));
        v.add(pilot(8, FULL, 1));
        return v;
    }

    // ------------------------------------------------------------------ datasets

    static List<Instance> coreSet(long seed, int perCell, SizeBucket... buckets) {
        List<Instance> list = new ArrayList<>();
        for (SizeBucket b : buckets) {
            for (Family f : CORE_FAMILIES) {
                for (int i = 0; i < perCell; i++) list.add(InstanceGenerator.generate(seed, i, f, b, CORE_DURATIONS));
            }
        }
        return list;
    }

    /** Small instances (at most EXACT_MAX_TASKS tasks) from the SMALL bucket, perFamily per core family. */
    static List<Instance> exactSet(long seed, int perFamily) {
        List<Instance> list = new ArrayList<>();
        for (Family f : CORE_FAMILIES) {
            int found = 0;
            for (int i = 0; found < perFamily; i++) {
                Instance x = InstanceGenerator.generate(seed, i, f, SizeBucket.SMALL, CORE_DURATIONS);
                if (x.size() <= EXACT_MAX_TASKS) {
                    list.add(x);
                    found++;
                }
            }
        }
        return list;
    }

    /** Same graph and pressure with heavy-tailed (Pareto, alpha 1.5, minimum 5, capped at 1000) durations; workers re-derived. */
    static Instance pareto(Instance base, long seed, int index) {
        Random r = new Random(seed * 1_000_003L + index);
        LinkedList<Task> tasks = new LinkedList<>();
        for (Task t : base.tasks) {
            int d = (int) Math.min(1000, Math.ceil(5 * Math.pow(1 - r.nextDouble(), -1 / 1.5)));
            tasks.add(new Task(t.getName(), t.getId(), d, new LinkedList<>(t.getDependsOnIDS())));
        }
        int[] stats = InstanceGenerator.stats(tasks);
        int workers = (int) Math.round((double) stats[0] / stats[1] / base.pressure);
        workers = Math.max(2, Math.min(64, workers));
        return new Instance(base.id + "-PARETO", base.family, base.bucket, base.durations, base.pressure, tasks, workers);
    }

    static String durationLabel(Instance x) {
        return x.id.endsWith("-PARETO") ? "PARETO" : x.durations.name();
    }

    // ------------------------------------------------------------------ evaluation

    static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    /** Makespans and per-run thread CPU time of every scheduler on every instance. */
    Results evaluate(List<Instance> instances, List<Scheduler> schedulers) {
        return evaluateWithCost(instances, schedulers, null);
    }

    /** As evaluate; {@code starts[v][i]} (if given) receives the simulated task starts of study schedulers (0 for production code). */
    Results evaluateWithCost(List<Instance> instances, List<Scheduler> schedulers, long[][] starts) {
        List<Variant> names = new ArrayList<>();
        for (Scheduler s : schedulers) {
            names.add(new Variant(s.name(), "study", SchedulerConfig.DEFAULT, BenchmarkStrategy.CRITICAL_PATH_FIRST,
                    SchedulerConfig.CompletionMode.BATCHED));
        }
        Results res = new Results(instances, names);
        pool.submit(() -> IntStream.range(0, instances.size()).parallel().forEach(i -> {
            Instance x = instances.get(i);
            Graph g = new Graph(x);
            StaticGraphAnalysis an = StaticGraphAnalysis.of(x.tasks);
            long[] work = WORK.get();
            for (int v = 0; v < schedulers.size(); v++) {
                work[0] = 0;
                long c0 = THREADS.getCurrentThreadCpuTime();
                res.makespan[v][i] = schedulers.get(v).run(x, g, an);
                res.nanos[v][i] = THREADS.getCurrentThreadCpuTime() - c0;
                if (starts != null) starts[v][i] = work[0];
            }
        })).join();
        return res;
    }

    // ------------------------------------------------------------------ instance features

    static final String[] FEATURES = {"n", "workers", "edges_per_task", "levels", "max_width", "parallelism",
            "load", "cp_over_lb", "cp_bound", "duration_cv", "max_over_mean_duration", "critical_fraction",
            "near_critical_fraction", "log10_critical_paths", "sources_fraction", "join_fraction", "fork_fraction",
            "max_in_degree", "max_out_degree", "pressure"};

    static double[] features(Graph g) {
        int n = g.n;
        int levels = 0;
        int[] perLevel = new int[n + 1];
        for (int i = 0; i < n; i++) {
            levels = Math.max(levels, g.level[i] + 1);
            perLevel[g.level[i]]++;
        }
        int width = Arrays.stream(perLevel).max().orElse(0);
        double mean = (double) g.work / n;
        double var = 0;
        int maxDur = 0;
        int crit = 0;
        int near = 0;
        int sources = 0;
        int joins = 0;
        int forks = 0;
        int maxIn = 0;
        int maxOut = 0;
        long edges = 0;
        for (int i = 0; i < n; i++) {
            var += (g.dur[i] - mean) * (g.dur[i] - mean);
            maxDur = Math.max(maxDur, g.dur[i]);
            if (g.slack[i] == 0) crit++;
            if (g.slack[i] <= 0.1 * g.criticalPath) near++;
            if (g.parents[i].length == 0) sources++;
            if (g.parents[i].length >= 2) joins++;
            if (g.children[i].length >= 2) forks++;
            maxIn = Math.max(maxIn, g.parents[i].length);
            maxOut = Math.max(maxOut, g.children[i].length);
            edges += g.parents[i].length;
        }
        double workBound = (double) g.work / g.m;
        return new double[]{n, g.m, (double) edges / n, levels, width, (double) g.work / g.criticalPath,
                g.work / (g.m * (double) g.criticalPath), g.criticalPath / (double) g.lowerBound(),
                g.criticalPath >= workBound ? 1 : 0, Math.sqrt(var / n) / mean, maxDur / mean, (double) crit / n,
                (double) near / n, Math.log10(Math.max(1, g.criticalPathCount())), (double) sources / n,
                (double) joins / n, (double) forks / n, maxIn, maxOut, g.inst.pressure};
    }

    // ------------------------------------------------------------------ decision analysis

    static final String[] DECISION_FEATURES = {"time_fraction", "ready", "free", "excess", "ready_per_free",
            "gap_rel", "exact_tie", "near_ties_5pct", "deferred_cp_over_remaining", "duration_ratio",
            "reach_diff_over_work", "unlock_diff", "children_diff", "desc_diff_over_n", "busy_fraction",
            "next_completion_over_duration", "remaining_over_makespan", "deferred_slack_over_cp",
            "started_slack_over_cp"};

    /** Decision-level record: features at a contested batch of CP-first, and its counterfactual swap values. */
    record Decision(int instance, int order, int time, double[] f, int makespan, int bestSwap, int boundarySwap,
                    int swappedOutRank, int swappedInRank) {
        int regret() {
            return Math.max(0, makespan - bestSwap);
        }

        int boundaryRegret() {
            return Math.max(0, makespan - boundarySwap);
        }
    }

    static final int SWAP_OUT = 8;   // the SWAP_OUT lowest-ranked tasks CP-first starts in a batch
    static final int SWAP_IN = 4;    // the SWAP_IN best tasks it defers

    /**
     * Replays CP-first; at every contested batch (more ready tasks than free workers)
     * tries every swap of one started task (the SWAP_OUT lowest-ranked) for one
     * deferred task (the SWAP_IN best), continuing with CP-first, and records the
     * best swap's makespan together with cheap features of the state.
     */
    static List<Decision> decisions(int instance, Graph g) {
        long[] key = Rule.CP.keys(g);
        int makespan = listSchedule(g, key);
        List<Decision> out = new ArrayList<>();
        Sim s = new Sim(g, key);
        int order = 0;
        while (s.done < g.n) {
            if (s.free > 0 && s.ready.size > 0) {
                int r = s.ready.size;
                int f = s.free;
                if (r > f) {
                    int[] ranked = s.ready.top(f + SWAP_IN);
                    int best = Integer.MAX_VALUE;
                    int boundary = Integer.MAX_VALUE;
                    int outRank = -1;
                    int inRank = -1;
                    for (int so = Math.max(0, f - SWAP_OUT); so < f; so++) {
                        for (int si = f; si < ranked.length; si++) {
                            Sim c = s.copy();
                            for (int q = 0; q < f; q++) if (q != so) c.startTask(ranked[q]);
                            c.startTask(ranked[si]);
                            int v = c.run(null, FULL);
                            if (so == f - 1) boundary = Math.min(boundary, v);
                            if (v < best) {
                                best = v;
                                outRank = f - 1 - so;
                                inRank = si - f;
                            }
                        }
                    }
                    out.add(new Decision(instance, order++, s.t, decisionFeatures(s, ranked, f, makespan),
                            makespan, best, boundary, outRank, inRank));
                }
                while (s.free > 0 && s.ready.size > 0) s.startTask(s.ready.peek());
            }
            s.advance();
        }
        return out;
    }

    static double[] decisionFeatures(Sim s, int[] ranked, int f, int makespan) {
        Graph g = s.g;
        int last = ranked[f - 1];
        int deferred = ranked[f];
        double gap = (g.cp[last] - g.cp[deferred]) / (double) g.cp[last];
        int near = 0;
        for (int q = 0; q < s.ready.size; q++) {
            int v = s.ready.heap[q];
            if (Math.abs(g.cp[v] - g.cp[last]) <= 0.05 * g.cp[last]) near++;
        }
        int nextCompletion = Integer.MAX_VALUE;
        for (int q = 0; q < s.running.size; q++) nextCompletion = Math.min(nextCompletion, s.fin[s.running.heap[q]]);
        double nextRel = nextCompletion == Integer.MAX_VALUE ? 10 : Math.min(10, (nextCompletion - s.t) / (double) g.dur[last]);
        int remaining = s.estimate() - s.t;
        return new double[]{s.t / (double) makespan, s.ready.size, f, s.ready.size - f, s.ready.size / (double) f,
                gap, g.cp[last] == g.cp[deferred] ? 1 : 0, near, g.cp[deferred] / (double) Math.max(1, remaining),
                g.dur[deferred] / (double) g.dur[last], (g.reach[deferred] - g.reach[last]) / (double) g.work,
                soleParentChildren(g, deferred) - soleParentChildren(g, last),
                g.children[deferred].length - g.children[last].length, (g.desc[deferred] - g.desc[last]) / (double) g.n,
                (g.m - f) / (double) g.m, nextRel, remaining / (double) makespan,
                g.slack[deferred] / (double) g.criticalPath, g.slack[last] / (double) g.criticalPath};
    }

    // ------------------------------------------------------------------ statistics helpers

    /** Spearman rank correlation. */
    static double spearman(double[] a, double[] b) {
        double[] ra = ranks(a);
        double[] rb = ranks(b);
        double ma = ExperimentRunner.mean(ra);
        double mb = ExperimentRunner.mean(rb);
        double num = 0, da = 0, db = 0;
        for (int i = 0; i < a.length; i++) {
            num += (ra[i] - ma) * (rb[i] - mb);
            da += (ra[i] - ma) * (ra[i] - ma);
            db += (rb[i] - mb) * (rb[i] - mb);
        }
        return da == 0 || db == 0 ? 0 : num / Math.sqrt(da * db);
    }

    static double[] ranks(double[] x) {
        Integer[] o = new Integer[x.length];
        for (int i = 0; i < x.length; i++) o[i] = i;
        Arrays.sort(o, Comparator.comparingDouble(i -> x[i]));
        double[] r = new double[x.length];
        for (int i = 0; i < x.length; ) {
            int j = i;
            while (j + 1 < x.length && x[o[j + 1]] == x[o[i]]) j++;
            for (int k = i; k <= j; k++) r[o[k]] = (i + j) / 2.0;
            i = j + 1;
        }
        return r;
    }

    /** Area under the ROC curve of score for the positive label (0.5 = no signal). */
    static double auc(double[] score, boolean[] label) {
        double[] r = ranks(score);
        double pos = 0, sum = 0;
        for (int i = 0; i < score.length; i++) {
            if (label[i]) {
                pos++;
                sum += r[i] + 1;
            }
        }
        double neg = score.length - pos;
        if (pos == 0 || neg == 0) return 0.5;
        return (sum - pos * (pos + 1) / 2) / (pos * neg);
    }

    static String f4(double x) {
        return String.format(Locale.ROOT, "%.4f", x);
    }

    static String f3(double x) {
        return String.format(Locale.ROOT, "%.3f", x);
    }

    static String pct(double x) {
        return String.format(Locale.ROOT, "%.1f%%", 100 * x);
    }

    static double gm(int[] a, int[] b, int[] subset) {
        double s = 0;
        for (int i : subset) s += Math.log((double) a[i] / b[i]);
        return Math.exp(s / subset.length);
    }

    // ------------------------------------------------------------------ reporting helpers

    /** Min makespan over the given schedulers per instance. */
    static int[] bestOf(Results res, List<Integer> variants) {
        int[] best = new int[res.instances.size()];
        Arrays.fill(best, Integer.MAX_VALUE);
        for (int v : variants) for (int i = 0; i < best.length; i++) best[i] = Math.min(best[i], res.makespan[v][i]);
        return best;
    }

    static List<Integer> range(int n) {
        return IntStream.range(0, n).boxed().toList();
    }

    /** Failure rates and severity of a against reference b: P(a > b), P(a >= 1.01 b), P(a >= 1.05 b), GM, quantiles. */
    static String failureRow(String label, int[] a, int[] b, int[] subset) {
        int any = 0, one = 0, five = 0;
        double[] ratio = new double[subset.length];
        for (int j = 0; j < subset.length; j++) {
            int i = subset[j];
            ratio[j] = (double) a[i] / b[i];
            if (a[i] > b[i]) any++;
            if (a[i] >= 1.01 * b[i]) one++;
            if (a[i] >= 1.05 * b[i]) five++;
        }
        Arrays.sort(ratio);
        int k = subset.length;
        return String.format(Locale.ROOT, "| %s | %d | %s | %s | %s | %s | %s | %s | %s |%n", label, k, pct(any / (double) k),
                pct(one / (double) k), pct(five / (double) k), f4(gm(a, b, subset)), f3(ratio[(int) (0.9 * (k - 1))]),
                f3(ratio[(int) (0.99 * (k - 1))]), f3(ratio[k - 1]));
    }

    static final String FAILURE_HEADER = "| comparison | n | worse | worse >= 1% | worse >= 5% | GM ratio | p90 ratio | p99 ratio | max ratio |\n|---|---|---|---|---|---|---|---|---|\n";

    /** Groups for breakdowns. */
    static Map<String, java.util.function.Predicate<Instance>> groups(boolean withFamilies) {
        Map<String, java.util.function.Predicate<Instance>> g = new LinkedHashMap<>();
        if (withFamilies) for (Family f : Family.values()) g.put("family " + f, x -> x.family == f);
        for (SizeBucket b : SizeBucket.values()) g.put("size " + b, x -> x.bucket == b);
        for (String d : new String[]{"UNIFORM_1_10", "UNIFORM_1_100", "LOGNORMAL", "UNIT", "BIMODAL", "PARETO"}) {
            g.put("durations " + d, x -> durationLabel(x).equals(d));
        }
        for (double p : InstanceGenerator.PRESSURES) g.put("pressure " + p, x -> x.pressure == p);
        g.put("workers 2-3", x -> x.workers <= 3);
        g.put("workers 4-8", x -> x.workers >= 4 && x.workers <= 8);
        g.put("workers 9-20", x -> x.workers >= 9 && x.workers <= 20);
        g.put("workers 21-64", x -> x.workers >= 21);
        return g;
    }

    static int[] where(List<Instance> list, java.util.function.Predicate<Instance> p) {
        return IntStream.range(0, list.size()).filter(i -> p.test(list.get(i))).toArray();
    }

    /** Per-feature association with a continuous target and a binary label. */
    static String associationTable(String[] names, double[][] x, double[] target, boolean[] label, Instance[] inst) {
        StringBuilder sb = new StringBuilder("| feature | Spearman with target | median within-family Spearman | AUC for label | mean (label) | mean (no label) |\n|---|---|---|---|---|---|\n");
        for (int j = 0; j < names.length; j++) {
            double[] col = new double[x.length];
            for (int i = 0; i < x.length; i++) col[i] = x[i][j];
            List<Double> within = new ArrayList<>();
            if (inst != null) {
                for (Family f : Family.values()) {
                    int[] idx = IntStream.range(0, x.length).filter(i -> inst[i].family == f).toArray();
                    if (idx.length < 30) continue;
                    double[] a = new double[idx.length], b = new double[idx.length];
                    for (int q = 0; q < idx.length; q++) {
                        a[q] = col[idx[q]];
                        b[q] = target[idx[q]];
                    }
                    within.add(spearman(a, b));
                }
            }
            double withinMedian = within.isEmpty() ? Double.NaN
                    : ExperimentRunner.median(within.stream().mapToDouble(Double::doubleValue).toArray());
            double mPos = 0, mNeg = 0;
            int nPos = 0;
            for (int i = 0; i < x.length; i++) {
                if (label[i]) {
                    mPos += col[i];
                    nPos++;
                } else {
                    mNeg += col[i];
                }
            }
            sb.append(String.format(Locale.ROOT, "| %s | %+.3f | %s | %.3f | %.3f | %.3f |%n", names[j], spearman(col, target),
                    Double.isNaN(withinMedian) ? "-" : String.format(Locale.ROOT, "%+.3f", withinMedian), auc(col, label),
                    nPos == 0 ? 0 : mPos / nPos, x.length == nPos ? 0 : mNeg / (x.length - nPos)));
        }
        return sb.toString();
    }

    /** Label rate and mean target per quintile of one feature. */
    static String quintileTable(String name, double[] col, double[] target, boolean[] label) {
        Integer[] o = new Integer[col.length];
        for (int i = 0; i < col.length; i++) o[i] = i;
        Arrays.sort(o, Comparator.comparingDouble(i -> col[i]));
        StringBuilder sb = new StringBuilder("| " + name + " quintile | range | n | label rate | mean target |\n|---|---|---|---|---|\n");
        for (int q = 0; q < 5; q++) {
            int from = q * col.length / 5, to = (q + 1) * col.length / 5;
            int pos = 0;
            double t = 0;
            for (int k = from; k < to; k++) {
                if (label[o[k]]) pos++;
                t += target[o[k]];
            }
            sb.append(String.format(Locale.ROOT, "| Q%d | %.3g - %.3g | %d | %s | %.4f |%n", q + 1, col[o[from]], col[o[to - 1]],
                    to - from, pct(pos / (double) (to - from)), t / (to - from)));
        }
        return sb.toString();
    }

    /** Best single-feature threshold rule (either direction) for the label, by F1. */
    static String thresholdRules(String[] names, double[][] x, boolean[] label) {
        StringBuilder sb = new StringBuilder("| feature | rule | flagged | precision | recall | F1 |\n|---|---|---|---|---|---|\n");
        int pos = 0;
        for (boolean b : label) if (b) pos++;
        List<String[]> rows = new ArrayList<>();
        for (int j = 0; j < names.length; j++) {
            double[] col = new double[x.length];
            for (int i = 0; i < x.length; i++) col[i] = x[i][j];
            double[] cuts = Arrays.stream(col).distinct().sorted().toArray();
            int step = Math.max(1, cuts.length / 200);
            double bestF1 = -1;
            String best = "";
            for (int c = 0; c < cuts.length; c += step) {
                for (int dirn = 0; dirn < 2; dirn++) {
                    int tp = 0, flagged = 0;
                    for (int i = 0; i < col.length; i++) {
                        boolean fire = dirn == 0 ? col[i] <= cuts[c] : col[i] >= cuts[c];
                        if (fire) {
                            flagged++;
                            if (label[i]) tp++;
                        }
                    }
                    if (flagged == 0 || tp == 0) continue;
                    double prec = tp / (double) flagged, rec = tp / (double) pos, f1 = 2 * prec * rec / (prec + rec);
                    if (f1 > bestF1) {
                        bestF1 = f1;
                        best = String.format(Locale.ROOT, "| %s | %s %s %.4g | %s | %s | %s | %.3f |%n", names[j], names[j],
                                dirn == 0 ? "<=" : ">=", cuts[c], pct(flagged / (double) col.length), pct(prec), pct(rec), f1);
                    }
                }
            }
            rows.add(new String[]{String.valueOf(bestF1), best});
        }
        rows.sort((a, b) -> Double.compare(Double.parseDouble(b[0]), Double.parseDouble(a[0])));
        for (String[] r : rows) sb.append(r[1]);
        return sb.toString();
    }

    // ------------------------------------------------------------------ stages: exploration

    static final int CP_INDEX = 0;

    void explore() throws IOException {
        List<Instance> list = coreSet(EXPLORE_SEED, 150, CORE_BUCKETS);
        List<Scheduler> sch = exploreSchedulers();
        Results res = evaluate(list, sch);
        int quality = res.index(QUALITY.name());
        int pilot8 = res.index(pilot(8, FULL, 1).name());
        int[] best = bestOf(res, range(sch.size()));
        int[] bestHeuristic = bestOf(res, range(sch.size()).stream()
                .filter(v -> !sch.get(v).name().startsWith("pilot") && v != quality && v != res.index(PHASE1.name())).toList());
        int[] cp = res.makespan[CP_INDEX];
        int n = list.size();
        int[] all = IntStream.range(0, n).toArray();

        // csv
        StringBuilder csv = new StringBuilder("instance,family,bucket,durations,pressure,lower_bound,best_observed");
        for (String f : FEATURES) csv.append(',').append(f);
        for (Scheduler s : sch) csv.append(",\"").append(s.name()).append('"');
        csv.append('\n');
        double[][] feat = new double[n][];
        for (int i = 0; i < n; i++) {
            Instance x = list.get(i);
            feat[i] = features(new Graph(x));
            csv.append(x.id).append(',').append(x.family).append(',').append(x.bucket).append(',').append(durationLabel(x))
                    .append(',').append(x.pressure).append(',').append(x.lowerBound).append(',').append(best[i]);
            for (double v : feat[i]) csv.append(',').append(String.format(Locale.ROOT, "%.5g", v));
            for (int v = 0; v < sch.size(); v++) csv.append(',').append(res.makespan[v][i]);
            csv.append('\n');
        }
        Files.writeString(dir.resolve("explore.csv"), csv);

        StringBuilder md = new StringBuilder("# Exploration set: where CP-first loses\n\n");
        md.append("Seed ").append(EXPLORE_SEED).append(": ").append(ExperimentRunner.datasetSummary(list)).append(".\n");
        md.append("6 core families x S/M/L x 150. Best observed = minimum over all ").append(sch.size())
                .append(" schedulers below (not an optimum). CPU ms = thread CPU time per run, 12 runs in parallel.\n\n");
        md.append("## All schedulers vs CP-first\n\n");
        md.append(ExperimentRunner.comparisonTable(res, CP_INDEX, all, range(sch.size())));
        md.append("\n## CP-first failure rates and severity\n\n").append(FAILURE_HEADER);
        md.append(failureRow("CP-first vs best observed", cp, best, all));
        md.append(failureRow("CP-first vs best non-rollout heuristic", cp, bestHeuristic, all));
        md.append(failureRow("CP-first vs B* QUALITY_OPTIMIZED", cp, res.makespan[quality], all));
        md.append(failureRow("CP-first vs pilot L8 full", cp, res.makespan[pilot8], all));
        md.append(failureRow("CP-first vs lower bound", cp, list.stream().mapToInt(x -> x.lowerBound).toArray(), all));
        md.append(failureRow("B* vs CP-first (B* worse)", res.makespan[quality], cp, all));
        md.append(failureRow("pilot L8 full vs CP-first (pilot worse)", res.makespan[pilot8], cp, all));

        md.append("\n## Breakdown: CP-first vs best observed\n\n").append(FAILURE_HEADER);
        for (var e : groups(true).entrySet()) {
            int[] sub = where(list, e.getValue());
            if (sub.length > 0) md.append(failureRow(e.getKey(), cp, best, sub));
        }

        // feature association
        double[] target = new double[n];
        boolean[] fail = new boolean[n];
        for (int i = 0; i < n; i++) {
            target[i] = Math.log((double) cp[i] / best[i]);
            fail[i] = cp[i] >= 1.01 * best[i];
        }
        md.append("\n## Graph features vs CP-first gap (target: log(CP-first / best observed); label: gap >= 1%)\n\n");
        md.append(associationTable(FEATURES, feat, target, fail, list.toArray(new Instance[0])));
        md.append("\n### Best single-feature threshold rules for the label\n\n").append(thresholdRules(FEATURES, feat, fail));
        for (String q : new String[]{"load", "workers", "parallelism", "duration_cv", "near_critical_fraction", "n", "cp_over_lb"}) {
            int j = Arrays.asList(FEATURES).indexOf(q);
            double[] col = new double[n];
            for (int i = 0; i < n; i++) col[i] = feat[i][j];
            md.append('\n').append(quintileTable(q, col, target, fail));
        }
        Files.writeString(dir.resolve("explore.md"), md);
        System.out.println(md);
    }

    // ------------------------------------------------------------------ stages: decisions

    void decisionStudy() throws IOException {
        List<Instance> list = new ArrayList<>(coreSet(EXPLORE_SEED, 150, SizeBucket.SMALL, SizeBucket.MEDIUM));
        list.addAll(coreSet(EXPLORE_SEED, 40, SizeBucket.LARGE));
        List<List<Decision>> per = pool.submit(() -> IntStream.range(0, list.size()).parallel()
                .mapToObj(i -> decisions(i, new Graph(list.get(i)))).toList()).join();
        List<Decision> all = new ArrayList<>();
        per.forEach(all::addAll);
        writeDecisions(dir.resolve("decisions.csv"), list, all);
        String md = decisionReport("Decision-level analysis (exploration set: S/M x 150 and L x 40 per core family)", list, per, null);
        Files.writeString(dir.resolve("decisions.md"), md);
        System.out.println(md);
    }

    static void writeDecisions(Path file, List<Instance> list, List<Decision> all) throws IOException {
        StringBuilder csv = new StringBuilder("instance,order,time,makespan,best_swap,boundary_swap,regret,boundary_regret,swapped_out_rank,swapped_in_rank");
        for (String f : DECISION_FEATURES) csv.append(',').append(f);
        csv.append('\n');
        for (Decision d : all) {
            csv.append(list.get(d.instance()).id).append(',').append(d.order()).append(',').append(d.time()).append(',')
                    .append(d.makespan()).append(',').append(d.bestSwap()).append(',').append(d.boundarySwap()).append(',')
                    .append(d.regret()).append(',').append(d.boundaryRegret()).append(',').append(d.swappedOutRank())
                    .append(',').append(d.swappedInRank());
            for (double v : d.f()) csv.append(',').append(String.format(Locale.ROOT, "%.5g", v));
            csv.append('\n');
        }
        Files.writeString(file, csv);
    }

    /** Decision statistics; {@code reference} (optional) gives a per-instance target makespan (e.g. the optimum). */
    static String decisionReport(String title, List<Instance> list, List<List<Decision>> per, int[] reference) {
        List<Decision> all = new ArrayList<>();
        per.forEach(all::addAll);
        int nd = all.size();
        long positive = all.stream().filter(d -> d.regret() > 0).count();
        long boundaryPositive = all.stream().filter(d -> d.boundaryRegret() > 0).count();
        long boundaryFull = all.stream().filter(d -> d.regret() > 0 && d.boundaryRegret() == d.regret()).count();
        StringBuilder md = new StringBuilder("# " + title + "\n\n");
        md.append(String.format(Locale.ROOT, "%d instances, %d contested batches (more ready tasks than free workers). "
                + "Swap = start one of the %d best deferred tasks instead of one of the %d lowest-ranked started tasks, "
                + "then continue CP-first to the end.%n%n", list.size(), nd, SWAP_IN, SWAP_OUT));
        md.append("| quantity | value |\n|---|---|\n");
        md.append("| contested batches with an improving swap (regret > 0) | ").append(pct(positive / (double) nd)).append(" |\n");
        md.append("| ... improvable by swapping the boundary task (the last one CP-first starts) | ").append(pct(boundaryPositive / (double) nd)).append(" |\n");
        md.append("| improving batches where the boundary swap achieves the full best-swap gain | ").append(pct(boundaryFull / (double) Math.max(1, positive))).append(" |\n");
        int instancesImprovable = 0, instancesMeaningful = 0;
        List<Double> firstTimes = new ArrayList<>();
        List<Double> firstOrders = new ArrayList<>();
        int bestReachesReference = 0, referenceGapInstances = 0;
        for (int i = 0; i < per.size(); i++) {
            List<Decision> ds = per.get(i);
            int bestSwap = Integer.MAX_VALUE;
            Decision first = null;
            for (Decision d : ds) {
                bestSwap = Math.min(bestSwap, d.bestSwap());
                if (first == null && d.regret() > 0) first = d;
            }
            if (first != null) {
                instancesImprovable++;
                if (ds.get(0).makespan() >= 1.01 * bestSwap) instancesMeaningful++;
                firstTimes.add(first.time() / (double) first.makespan());
                firstOrders.add(first.order() / (double) ds.size());
            }
            if (reference != null && !ds.isEmpty() && ds.get(0).makespan() > reference[i]) {
                referenceGapInstances++;
                if (bestSwap <= reference[i]) bestReachesReference++;
            }
        }
        md.append("| instances where one swap improves CP-first | ").append(pct(instancesImprovable / (double) per.size())).append(" |\n");
        md.append("| ... by at least 1% | ").append(pct(instancesMeaningful / (double) per.size())).append(" |\n");
        if (!firstTimes.isEmpty()) {
            double[] ft = firstTimes.stream().mapToDouble(Double::doubleValue).toArray();
            double[] fo = firstOrders.stream().mapToDouble(Double::doubleValue).toArray();
            md.append("| first improvable batch: median time / makespan | ").append(f3(ExperimentRunner.median(ft))).append(" |\n");
            md.append("| first improvable batch: median position among contested batches | ").append(f3(ExperimentRunner.median(fo))).append(" |\n");
        }
        if (reference != null) {
            md.append("| instances where CP-first is above the reference | ").append(referenceGapInstances).append(" |\n");
            md.append("| ... where one swap reaches the reference | ").append(pct(bestReachesReference / (double) Math.max(1, referenceGapInstances))).append(" |\n");
        }
        // swap ranks
        int[] outRank = new int[SWAP_OUT];
        int[] inRank = new int[SWAP_IN];
        for (Decision d : all) {
            if (d.regret() > 0) {
                outRank[d.swappedOutRank()]++;
                inRank[d.swappedInRank()]++;
            }
        }
        md.append("\nBest improving swap, task swapped out (0 = boundary task, k = k-th above it): ");
        for (int k = 0; k < SWAP_OUT; k++) md.append(k).append(": ").append(pct(outRank[k] / (double) Math.max(1, positive))).append("  ");
        md.append("\nTask swapped in (0 = best deferred): ");
        for (int k = 0; k < SWAP_IN; k++) md.append(k).append(": ").append(pct(inRank[k] / (double) Math.max(1, positive))).append("  ");
        md.append("\n\n");
        // features
        double[][] x = new double[nd][];
        double[] target = new double[nd];
        boolean[] label = new boolean[nd];
        for (int k = 0; k < nd; k++) {
            Decision d = all.get(k);
            x[k] = d.f();
            target[k] = d.regret() / (double) d.makespan();
            label[k] = d.regret() > 0;
        }
        md.append("## Decision features vs improving swap (target: regret / makespan; label: regret > 0)\n\n");
        md.append(associationTable(DECISION_FEATURES, x, target, label, null));
        md.append("\n### Best single-feature trigger rules\n\n").append(thresholdRules(DECISION_FEATURES, x, label));
        // gap bins
        double[][] bins = {{-1, 0}, {0, 0.01}, {0.01, 0.02}, {0.02, 0.05}, {0.05, 0.1}, {0.1, 0.2}, {0.2, 2}};
        int gapCol = Arrays.asList(DECISION_FEATURES).indexOf("gap_rel");
        double totalRegret = all.stream().mapToDouble(d -> d.regret() / (double) d.makespan()).sum();
        md.append("\n### By CP gap between the boundary task and the best deferred task\n\n");
        md.append("| relative CP gap | share of batches | improvable | share of total regret | mean regret when improvable |\n|---|---|---|---|---|\n");
        for (double[] b : bins) {
            int cnt = 0, pos = 0;
            double reg = 0;
            for (int k = 0; k < nd; k++) {
                double gv = x[k][gapCol];
                if (b[0] < 0 ? gv == 0 : gv > b[0] && gv <= b[1]) {
                    cnt++;
                    if (label[k]) pos++;
                    reg += target[k];
                }
            }
            md.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s |%n", b[0] < 0 ? "0 (exact tie)" : "(" + b[0] + ", " + b[1] + "]",
                    pct(cnt / (double) nd), pct(pos / (double) Math.max(1, cnt)), pct(reg / Math.max(1e-12, totalRegret)),
                    pos == 0 ? "-" : pct(reg / pos)));
        }
        // trigger coverage
        md.append("\n### Gap triggers: cost (share of contested batches fired) vs coverage\n\n");
        md.append("| trigger | batches fired | improving batches caught | share of total regret caught | improvable instances with >= 1 caught batch |\n|---|---|---|---|---|\n");
        for (double th : new double[]{0, 0.01, 0.02, 0.05, 0.1, 0.2, 1}) {
            int fired = 0, caught = 0;
            double reg = 0;
            for (int k = 0; k < nd; k++) {
                if (x[k][gapCol] <= th) {
                    fired++;
                    if (label[k]) caught++;
                    reg += target[k];
                }
            }
            int inst = 0, instPos = 0;
            for (List<Decision> ds : per) {
                boolean any = false, hit = false;
                for (Decision d : ds) {
                    if (d.regret() > 0) {
                        any = true;
                        if (d.f()[gapCol] <= th) hit = true;
                    }
                }
                if (any) {
                    instPos++;
                    if (hit) inst++;
                }
            }
            md.append(String.format(Locale.ROOT, "| gap <= %s | %s | %s | %s | %s |%n", th, pct(fired / (double) nd),
                    pct(caught / (double) Math.max(1, positive)), pct(reg / Math.max(1e-12, totalRegret)), pct(inst / (double) Math.max(1, instPos))));
        }
        return md.toString();
    }

    // ------------------------------------------------------------------ stages: exact

    record ExactResult(int optimal, int nonDelay) {
    }

    void exact() throws IOException {
        List<Instance> list = exactSet(EXACT_SEED, 150);
        exactStudy(list, "exact", "Exact set (seed " + EXACT_SEED + ")");
    }

    void exactStudy(List<Instance> list, String name, String title) throws IOException {
        List<Scheduler> sch = exploreSchedulers();
        Results res = evaluate(list, sch);
        List<Integer> nonDelayStudy = range(sch.size()).stream().filter(v -> sch.get(v) instanceof ListRule
                || sch.get(v) instanceof PilotVariant).toList();
        int[] ubNonDelay = bestOf(res, nonDelayStudy);
        int[] ubAll = bestOf(res, range(sch.size()));
        ExactResult[] ex = pool.submit(() -> IntStream.range(0, list.size()).parallel().mapToObj(i -> {
            Instance x = list.get(i);
            int opt = ExactSolver.optimal(x.tasks, x.workers, ubAll[i], EXACT_NODE_LIMIT);
            int nd = ExactSolver.bestNonDelay(x.tasks, x.workers, ubNonDelay[i], EXACT_NODE_LIMIT);
            return new ExactResult(opt, nd);
        }).toArray(ExactResult[]::new)).join();
        int[] solved = IntStream.range(0, list.size()).filter(i -> ex[i].optimal() > 0 && ex[i].nonDelay() > 0).toArray();
        int[] opt = new int[list.size()];
        int[] nd = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            opt[i] = ex[i].optimal();
            nd[i] = ex[i].nonDelay();
        }
        List<List<Decision>> per = pool.submit(() -> IntStream.range(0, list.size()).parallel()
                .mapToObj(i -> decisions(i, new Graph(list.get(i)))).toList()).join();

        StringBuilder csv = new StringBuilder("instance,family,n,workers,durations,lower_bound,optimal,best_non_delay");
        for (Scheduler s : sch) csv.append(",\"").append(s.name()).append('"');
        csv.append('\n');
        for (int i = 0; i < list.size(); i++) {
            Instance x = list.get(i);
            csv.append(x.id).append(',').append(x.family).append(',').append(x.size()).append(',').append(x.workers).append(',')
                    .append(durationLabel(x)).append(',').append(x.lowerBound).append(',').append(opt[i]).append(',').append(nd[i]);
            for (int v = 0; v < sch.size(); v++) csv.append(',').append(res.makespan[v][i]);
            csv.append('\n');
        }
        Files.writeString(dir.resolve(name + ".csv"), csv);

        StringBuilder md = new StringBuilder("# " + title + ": CP-first against the optimum and the best list schedule\n\n");
        md.append(ExperimentRunner.datasetSummary(list)).append(". Solved exactly (both problems, node limit ")
                .append(EXACT_NODE_LIMIT).append("): ").append(solved.length).append(" / ").append(list.size()).append(".\n\n");
        md.append("OPT = optimal schedule (delays allowed). ND = best non-delay schedule: the best any list-scheduling priority can produce.\n\n");
        md.append("| scheduler | GM ms/OPT | % optimal | GM ms/ND | % equal to ND | worst ms/OPT |\n|---|---|---|---|---|---|\n");
        for (int v = 0; v < sch.size(); v++) {
            int[] ms = res.makespan[v];
            md.append(exactRow(sch.get(v).name(), ms, opt, nd, solved));
        }
        md.append(exactRow("ND (best list schedule)", nd, opt, nd, solved));
        int ndAboveOpt = 0, cpAboveNd = 0, cpAboveOpt = 0;
        double logCpOpt = 0, logCpNd = 0, logNdOpt = 0;
        for (int i : solved) {
            int c = res.makespan[CP_INDEX][i];
            if (nd[i] > opt[i]) ndAboveOpt++;
            if (c > nd[i]) cpAboveNd++;
            if (c > opt[i]) cpAboveOpt++;
            logCpOpt += Math.log((double) c / opt[i]);
            logCpNd += Math.log((double) c / nd[i]);
            logNdOpt += Math.log((double) nd[i] / opt[i]);
        }
        md.append("\n## Gap decomposition for CP-first (solved instances)\n\n| quantity | value |\n|---|---|\n");
        md.append("| CP-first above OPT | ").append(pct(cpAboveOpt / (double) solved.length)).append(" |\n");
        md.append("| CP-first above ND (fixable by priorities alone) | ").append(pct(cpAboveNd / (double) solved.length)).append(" |\n");
        md.append("| ND above OPT (only fixable by inserting idle time) | ").append(pct(ndAboveOpt / (double) solved.length)).append(" |\n");
        md.append("| GM CP-first / OPT | ").append(f4(Math.exp(logCpOpt / solved.length))).append(" |\n");
        md.append("| ... of which priority part, GM CP-first / ND | ").append(f4(Math.exp(logCpNd / solved.length))).append(" |\n");
        md.append("| ... of which non-delay part, GM ND / OPT | ").append(f4(Math.exp(logNdOpt / solved.length))).append(" |\n");
        md.append("| share of the log gap fixable by priorities | ").append(pct(logCpNd / Math.max(1e-12, logCpOpt))).append(" |\n");
        int delayBeats = 0;
        for (int v = 0; v < sch.size(); v++) for (int i : solved) if (res.makespan[v][i] < nd[i]) delayBeats++;
        md.append("| scheduler runs below ND (would require idle insertion) | ").append(delayBeats).append(" |\n\n");
        md.append("### By family (solved instances)\n\n| family | n | CP>OPT | CP>ND | ND>OPT | GM CP/OPT | GM ND/OPT | GM B*/OPT | GM pilot L8/OPT |\n|---|---|---|---|---|---|---|---|---|\n");
        int q = res.index(QUALITY.name()), p8 = res.index(pilot(8, FULL, 1).name());
        for (Family f : CORE_FAMILIES) {
            int[] sub = Arrays.stream(solved).filter(i -> list.get(i).family == f).toArray();
            if (sub.length == 0) continue;
            int a = 0, b = 0, c = 0;
            for (int i : sub) {
                if (res.makespan[CP_INDEX][i] > opt[i]) a++;
                if (res.makespan[CP_INDEX][i] > nd[i]) b++;
                if (nd[i] > opt[i]) c++;
            }
            md.append(String.format(Locale.ROOT, "| %s | %d | %s | %s | %s | %s | %s | %s | %s |%n", f, sub.length, pct(a / (double) sub.length),
                    pct(b / (double) sub.length), pct(c / (double) sub.length), f4(gm(res.makespan[CP_INDEX], opt, sub)),
                    f4(gm(nd, opt, sub)), f4(gm(res.makespan[q], opt, sub)), f4(gm(res.makespan[p8], opt, sub))));
        }
        md.append('\n');
        int[] reference = new int[list.size()];
        for (int i = 0; i < list.size(); i++) reference[i] = nd[i] > 0 ? nd[i] : 0;
        md.append(decisionReport("Decisions on the exact set (reference = best list schedule ND)", list, per, reference)
                .replaceFirst("^# ", "## "));
        Files.writeString(dir.resolve(name + ".md"), md);
        System.out.println(md);
    }

    static String exactRow(String name, int[] ms, int[] opt, int[] nd, int[] solved) {
        int optimal = 0, equalNd = 0;
        double worst = 0;
        for (int i : solved) {
            if (ms[i] == opt[i]) optimal++;
            if (ms[i] == nd[i]) equalNd++;
            worst = Math.max(worst, (double) ms[i] / opt[i]);
        }
        return String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %.3f |%n", name, f4(gm(ms, opt, solved)),
                pct(optimal / (double) solved.length), f4(gm(ms, nd, solved)), pct(equalNd / (double) solved.length), worst);
    }

    // ------------------------------------------------------------------ stages: case studies

    void cases() throws IOException {
        List<Instance> list = exactSet(EXACT_SEED, 150);
        Map<String, int[]> exactValues = new HashMap<>();
        for (String line : Files.readAllLines(dir.resolve("exact.csv")).subList(1, list.size() + 1)) {
            String[] f = line.split(",");
            exactValues.put(f[0], new int[]{Integer.parseInt(f[6]), Integer.parseInt(f[7])});
        }
        // the worst CP-first / OPT instances, at most two per family
        List<Integer> order = new ArrayList<>(range(list.size()));
        int[] cp = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Graph g = new Graph(list.get(i));
            cp[i] = listSchedule(g, Rule.CP.keys(g));
        }
        order.removeIf(i -> exactValues.get(list.get(i).id)[0] <= 0);
        order.sort(Comparator.comparingDouble(i -> -(double) cp[i] / exactValues.get(list.get(i).id)[0]));
        StringBuilder md = new StringBuilder("# Case studies: worst CP-first instances on the exact set\n\n");
        Map<Family, Integer> perFamily = new EnumMap<>(Family.class);
        int shown = 0;
        for (int i : order) {
            Instance x = list.get(i);
            if (perFamily.merge(x.family, 1, Integer::sum) > 2) continue;
            md.append(caseStudy(x, exactValues.get(x.id)[0], exactValues.get(x.id)[1]));
            if (++shown == 10) break;
        }
        Files.writeString(dir.resolve("cases.md"), md);
        System.out.println(md);
    }

    /** DAG, CP-first schedule, the first improving decision, the alternative, and the resulting schedule. */
    static String caseStudy(Instance x, int opt, int nd) {
        Graph g = new Graph(x);
        long[] key = Rule.CP.keys(g);
        Sim base = new Sim(g, key);
        int ms = base.copy().run(null, FULL);
        StringBuilder md = new StringBuilder();
        md.append(String.format(Locale.ROOT, "## %s — %d tasks, %d workers, durations %s%n%n", x.id, g.n, g.m, durationLabel(x)));
        md.append(String.format(Locale.ROOT, "CP-first %d, best list schedule (ND) %d, optimum %d, lower bound %d (critical path %d, work/workers %.1f).%n%n",
                ms, nd, opt, g.lowerBound(), g.criticalPath, g.work / (double) g.m));
        md.append("| task | duration | parents | cp (bottom level) | slack | descendants | sole-parent children |\n|---|---|---|---|---|---|---|\n");
        for (int v : g.topo) {
            StringBuilder ps = new StringBuilder();
            for (int p : g.parents[v]) ps.append(ps.length() == 0 ? "" : " ").append(name(g, p));
            md.append(String.format(Locale.ROOT, "| %s | %d | %s | %d | %d | %d | %d |%n", name(g, v), g.dur[v],
                    ps.length() == 0 ? "-" : ps, g.cp[v], g.slack[v], g.desc[v], soleParentChildren(g, v)));
        }
        Sim cpRun = base.copy();
        cpRun.run(null, FULL);
        md.append("\nCP-first schedule:\n\n```\n").append(gantt(g, cpRun.start)).append("```\n\n");
        // replay to the first improving batch
        Sim s = base.copy();
        int batch = 0;
        while (s.done < g.n) {
            if (s.free > 0 && s.ready.size > s.free) {
                int f = s.free;
                int[] ranked = s.ready.top(s.ready.size);
                int best = ms, bestOut = -1, bestIn = -1;
                Sim bestSim = null;
                for (int so = Math.max(0, f - SWAP_OUT); so < f; so++) {
                    for (int si = f; si < Math.min(ranked.length, f + SWAP_IN); si++) {
                        Sim c = s.copy();
                        for (int q = 0; q < f; q++) if (q != so) c.startTask(ranked[q]);
                        c.startTask(ranked[si]);
                        Sim done = c.copy();
                        int v = done.run(null, FULL);
                        if (v < best) {
                            best = v;
                            bestOut = ranked[so];
                            bestIn = ranked[si];
                            bestSim = done;
                        }
                    }
                }
                if (bestSim != null) {
                    md.append(String.format(Locale.ROOT, "First improving decision: contested batch %d at time %d, %d free worker(s), %d ready tasks.%n%n",
                            batch, s.t, f, ranked.length));
                    md.append("| ready task (CP order) | cp | duration | slack | descendants | downstream work | sole-parent children | CP-first |\n|---|---|---|---|---|---|---|---|\n");
                    for (int q = 0; q < ranked.length; q++) {
                        int v = ranked[q];
                        md.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %d | %d | %d | %s |%n", name(g, v), g.cp[v], g.dur[v],
                                g.slack[v], g.desc[v], g.reach[v], soleParentChildren(g, v), q < f ? "starts" : "waits"));
                    }
                    md.append(String.format(Locale.ROOT, "%nAlternative: start %s instead of %s, then continue CP-first: makespan %d instead of %d (%s; ND %d, OPT %d).%n%n",
                            name(g, bestIn), name(g, bestOut), best, ms, best == opt ? "optimal" : best == nd ? "best list schedule" : "still above ND", nd, opt));
                    md.append("Schedule after the swap:\n\n```\n").append(gantt(g, bestSim.start)).append("```\n\n");
                    return md.toString();
                }
                batch++;
                while (s.free > 0 && s.ready.size > 0) s.startTask(s.ready.peek());
            } else {
                while (s.free > 0 && s.ready.size > 0) s.startTask(s.ready.peek());
            }
            s.advance();
        }
        md.append("No single swap improves CP-first on this instance (the gap needs several changed decisions or idle time).\n\n");
        return md.toString();
    }

    static String name(Graph g, int v) {
        return g.inst.tasks.get(v).getName();
    }

    /** One line per worker: tasks in start order, assigned to the lowest-numbered free worker. */
    static String gantt(Graph g, int[] start) {
        Integer[] o = new Integer[g.n];
        for (int i = 0; i < g.n; i++) o[i] = i;
        Arrays.sort(o, Comparator.comparingInt((Integer i) -> start[i]).thenComparingInt(i -> i));
        int[] busyUntil = new int[g.m];
        List<StringBuilder> lines = new ArrayList<>();
        for (int w = 0; w < g.m; w++) lines.add(new StringBuilder(String.format(Locale.ROOT, "W%-2d", w)));
        for (int v : o) {
            int w = 0;
            while (busyUntil[w] > start[v]) w++;
            busyUntil[w] = start[v] + g.dur[v];
            lines.get(w).append(String.format(Locale.ROOT, " %s[%d-%d]", name(g, v), start[v], start[v] + g.dur[v]));
        }
        StringBuilder sb = new StringBuilder();
        for (StringBuilder l : lines) sb.append(l).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------ stages: development selection

    static final String FULL_PILOT = "pilot L4 full, every contested pick";
    static final String CP_STUDY = "CP-first (study sim)";

    static Scheduler studyPilot(String name, int l, int depth, Trigger t) {
        return new TriggeredPilot(name, l, depth, t);
    }

    /** Everything evaluated on the development set. */
    static List<Scheduler> devSchedulers() {
        Scheduler cpStudy = new ListRule(CP_STUDY, Rule.CP);
        Scheduler full = studyPilot(FULL_PILOT, 4, FULL, ALWAYS);
        List<Scheduler> v = new ArrayList<>(List.of(CP_FIRST, cpStudy, FAST, QUALITY, full));
        // production configurations (existing SchedulerConfig options only)
        v.add(quality("prod: B* only when blended-score gap <= 0.01", SchedulerConfig.GapCondition.GAP_AT_MOST_THRESHOLD, 0.01));
        v.add(quality("prod: B* only when blended-score gap <= 0.05", SchedulerConfig.GapCondition.GAP_AT_MOST_THRESHOLD, 0.05));
        v.add(new Gate("prod: B* if n <= 150, else A*", g -> g.n <= 150, QUALITY, FAST));
        v.add(new Gate("prod: B* if workers <= 3, else A*", g -> g.m <= 3, QUALITY, FAST));
        v.add(new Gate("prod: B* if workers <= 8, else A*", g -> g.m <= 8, QUALITY, FAST));
        // study pilots: depth, candidates, decision triggers
        v.add(studyPilot("pilot L4 d8, every contested pick", 4, 8, ALWAYS));
        v.add(studyPilot("pilot L2 full, every contested pick", 2, FULL, ALWAYS));
        v.add(studyPilot("pilot L4 full, CP gap <= 0.05", 4, FULL, gapAtMost(0.05)));
        v.add(studyPilot("pilot L4 full, CP gap <= 0.2", 4, FULL, gapAtMost(0.2)));
        v.add(studyPilot("pilot L4 full, deferred near-critical (rho 0.35)", 4, FULL, nearCritical(0.35)));
        v.add(studyPilot("pilot L4 full, deferred near-critical (rho 0.5)", 4, FULL, nearCritical(0.5)));
        v.add(studyPilot("pilot L4 full, single free worker", 4, FULL, SINGLE_FREE));
        // study pilots: graph-level gates (CP-first elsewhere)
        v.add(new Gate("pilot L4 full if n <= 150, else CP", g -> g.n <= 150, full, cpStudy));
        v.add(new Gate("pilot L4 full if workers <= 3, else CP", g -> g.m <= 3, full, cpStudy));
        v.add(new Gate("pilot L4 full if workers <= 8, else CP", g -> g.m <= 8, full, cpStudy));
        v.add(new Gate("pilot L4 full if n <= 150 or workers <= 3, else CP", g -> g.n <= 150 || g.m <= 3, full, cpStudy));
        v.add(new Gate("pilot L4 d8 if workers <= 8, else CP", g -> g.m <= 8,
                studyPilot("pilot L4 d8", 4, 8, ALWAYS), cpStudy));
        // targeted zero-simulation rules
        v.add(new PolicyVariant("rule: next-completion-aware CP (L2)", () -> new NextCompletionAware(2)));
        v.add(new PolicyVariant("rule: next-completion-aware CP (L4)", () -> new NextCompletionAware(4)));
        v.add(new ListRule("rule: CP, ties by downstream work", Rule.CP_TIE_REACH));
        // offline reference: randomized multi-start list scheduling
        v.add(new MultiStart("offline: best of 8 perturbed CP schedules", 8, 0.1));
        v.add(new MultiStart("offline: best of 32 perturbed CP schedules", 32, 0.1));
        return v;
    }

    static List<Instance> devSet() {
        List<Instance> list = coreSet(DEV_SEED, 100, CORE_BUCKETS);
        list.addAll(coreSet(DEV_SEED, 10, SizeBucket.XLARGE));
        return list;
    }

    /** Study variants that run a pilot somewhere (for the selection rules). */
    static boolean isPilot(Scheduler s) {
        return s instanceof TriggeredPilot || (s instanceof Gate gt && gt.chosen() instanceof TriggeredPilot);
    }

    static boolean isProduction(Scheduler s) {
        return s instanceof Production || (s instanceof Gate gt && gt.chosen() instanceof Production);
    }

    void dev() throws IOException {
        List<Instance> list = devSet();
        List<Scheduler> sch = devSchedulers();
        long[][] starts = new long[sch.size()][list.size()];
        Results res = evaluateWithCost(list, sch, starts);
        Summary sum = new Summary(res, starts, sch);
        StringBuilder md = new StringBuilder("# Development set: selective lookahead and targeted rules\n\n");
        md.append("Seed ").append(DEV_SEED).append(": ").append(ExperimentRunner.datasetSummary(list))
                .append(" (core families, S/M/L x 100 and XL x 10 per family).\n\n");
        md.append(sum.table(range(sch.size())));
        md.append("\n").append(sum.bySize(range(sch.size()), list));

        // pre-registered selection rules (fixed before the development results were computed)
        int full = res.index(FULL_PILOT);
        int b = res.index(QUALITY.name());
        double fullPasses = sum.passes[full];
        List<String> selected = new ArrayList<>();
        md.append("\n## Selection (rules fixed before running this stage)\n\n");
        int s1 = -1, s2 = -1, s3 = -1, s4 = -1;
        for (int v = 0; v < sch.size(); v++) {
            Scheduler s = sch.get(v);
            if (isPilot(s) && v != full && sum.gmVs(v, full) <= 1.0005 && (s1 < 0 || sum.passes[v] < sum.passes[s1])) s1 = v;
            if (isPilot(s) && sum.passes[v] <= 0.2 * fullPasses && (s2 < 0 || sum.gmVs(v, CP_INDEX) < sum.gmVs(s2, CP_INDEX))) s2 = v;
            if (s.name().startsWith("rule:")) {
                ExperimentRunner.Comparison c = ExperimentRunner.compare(res, v, CP_INDEX, ExperimentRunner.all(res));
                if (c.gmRatio < 1 && c.pValue < 0.01 && c.worstRatio <= 1.05 && (s3 < 0 || c.gmRatio < sum.gmVs(s3, CP_INDEX))) s3 = v;
            }
            if (isProduction(s) && s.name().startsWith("prod:") && sum.gmVs(v, b) <= 1.0005
                    && (s4 < 0 || sum.cpu[v] < sum.cpu[s4])) s4 = v;
        }
        md.append("- S1 selective pilot (fewest simulated starts with GM <= 1.0005 x full pilot): ").append(s1 < 0 ? "none" : sch.get(s1).name()).append('\n');
        md.append("- S2 budget pilot (best GM vs CP-first with <= 20% of the full pilot's simulated starts): ").append(s2 < 0 ? "none" : sch.get(s2).name()).append('\n');
        md.append("- S3 zero-simulation rule (GM < 1 vs CP-first, Wilcoxon p < 0.01, worst <= 1.05): ").append(s3 < 0 ? "none" : sch.get(s3).name()).append('\n');
        md.append("- S4 production selective configuration (least CPU with GM <= 1.0005 x B*): ").append(s4 < 0 ? "none" : sch.get(s4).name()).append('\n');
        for (int v : new int[]{s1, s2, s3, s4}) if (v >= 0 && !selected.contains(sch.get(v).name())) selected.add(sch.get(v).name());
        Files.writeString(dir.resolve("dev-selection.txt"), String.join("\n", selected) + "\n");
        Files.writeString(dir.resolve("dev.md"), md);
        System.out.println(md);
    }

    /** Per-variant summary: GM vs references, CPU and simulated starts. */
    static final class Summary {
        final Results res;
        final long[][] starts;
        final List<Scheduler> sch;
        final double[] cpu;
        final double[] passes;
        final int[] all;

        Summary(Results res, long[][] starts, List<Scheduler> sch) {
            this.res = res;
            this.starts = starts;
            this.sch = sch;
            all = ExperimentRunner.all(res);
            cpu = new double[sch.size()];
            passes = new double[sch.size()];
            for (int v = 0; v < sch.size(); v++) {
                for (int i : all) {
                    cpu[v] += res.nanos[v][i] / 1e6;
                    passes[v] += starts[v][i] / (double) res.instances.get(i).size();
                }
                cpu[v] /= all.length;
                passes[v] /= all.length;
            }
        }

        double gmVs(int v, int ref) {
            return gm(res.makespan[v], res.makespan[ref], all);
        }

        String table(List<Integer> variants) {
            int cpStudy = res.index(CP_STUDY);
            int b = res.index(QUALITY.name());
            int full = res.index(FULL_PILOT);
            StringBuilder sb = new StringBuilder("| variant | GM vs CP-first [95% CI] | W/T/L vs CP-first | worst vs CP-first | Wilcoxon p | GM vs B* | GM vs full pilot | CPU ms/run | CPU vs CP-first (same code base) | list passes per run |\n|---|---|---|---|---|---|---|---|---|---|\n");
            for (int v : variants) {
                ExperimentRunner.Comparison c = ExperimentRunner.compare(res, v, CP_INDEX, all);
                Scheduler s = sch.get(v);
                boolean prod = isProduction(s);
                double base = prod ? cpu[CP_INDEX] : cpu[cpStudy];
                sb.append(String.format(Locale.ROOT, "| %s | %.4f [%.4f, %.4f] | %d/%d/%d | %.3f | %s | %.4f | %.4f | %.2f | %.1fx%s | %s |%n",
                        s.name(), c.gmRatio, c.ciLow, c.ciHigh, c.wins, c.ties, c.losses, c.worstRatio,
                        ExperimentRunner.formatP(c.pValue), gmVs(v, b), gmVs(v, full), cpu[v], cpu[v] / base,
                        prod ? " (production)" : "", prod ? "-" : String.format(Locale.ROOT, "%.1f", passes[v])));
            }
            return sb.toString();
        }

        String bySize(List<Integer> variants, List<Instance> list) {
            SizeBucket[] buckets = {SizeBucket.SMALL, SizeBucket.MEDIUM, SizeBucket.LARGE, SizeBucket.XLARGE};
            StringBuilder sb = new StringBuilder("### GM vs CP-first and CPU ms per run by size\n\n| variant |");
            for (SizeBucket bk : buckets) sb.append(' ').append(bk).append(" GM | ").append(bk).append(" CPU ms |");
            sb.append("\n|---|");
            for (int k = 0; k < buckets.length; k++) sb.append("---|---|");
            sb.append('\n');
            for (int v : variants) {
                sb.append("| ").append(sch.get(v).name()).append(" |");
                for (SizeBucket bk : buckets) {
                    int[] sub = where(list, x -> x.bucket == bk);
                    if (sub.length == 0) {
                        sb.append(" - | - |");
                        continue;
                    }
                    double ms = 0;
                    for (int i : sub) ms += res.nanos[v][i] / 1e6;
                    sb.append(String.format(Locale.ROOT, " %.4f | %.2f |", gm(res.makespan[v], res.makespan[CP_INDEX], sub), ms / sub.length));
                }
                sb.append('\n');
            }
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------ stages: final (run once)

    List<Scheduler> finalSchedulers() throws IOException {
        List<Scheduler> dev = devSchedulers();
        List<String> names = new ArrayList<>(List.of(CP_FIRST.name(), CP_STUDY, FAST.name(), QUALITY.name(), FULL_PILOT));
        for (String s : Files.readAllLines(dir.resolve("dev-selection.txt"))) if (!s.isBlank() && !names.contains(s)) names.add(s);
        names.add("offline: best of 32 perturbed CP schedules");
        List<Scheduler> out = new ArrayList<>();
        for (String n : names) out.add(dev.stream().filter(s -> s.name().equals(n)).findFirst().orElseThrow());
        return out;
    }

    void finalTest() throws IOException {
        Path marker = dir.resolve("final.done");
        if (Files.exists(marker)) throw new IllegalStateException("the final evaluation was already run (" + marker + ")");
        List<Scheduler> sch = finalSchedulers();
        StringBuilder frozen = new StringBuilder();
        for (Scheduler s : sch) frozen.append(s.name()).append('\n');
        Files.writeString(dir.resolve("final-frozen.txt"), frozen);
        Files.writeString(marker, "started " + new Date() + "\n");

        Map<String, List<Instance>> sets = new LinkedHashMap<>();
        sets.put("In-distribution (core families, S/M/L x 150)", coreSet(FINAL_SEED, 150, CORE_BUCKETS));
        sets.put("XL graphs (core families, 601-1500 tasks, x 20)", coreSet(FINAL_SEED, 20, SizeBucket.XLARGE));
        List<Instance> heldOut = new ArrayList<>();
        for (SizeBucket bk : CORE_BUCKETS) {
            for (Family f : new Family[]{Family.FFT, Family.GAUSSIAN}) {
                for (int i = 0; i < 150; i++) heldOut.add(InstanceGenerator.generate(OOD_SEED, i, f, bk, CORE_DURATIONS));
            }
        }
        sets.put("OOD: held-out families FFT and Gaussian", heldOut);
        List<Instance> bimodal = new ArrayList<>();
        for (SizeBucket bk : CORE_BUCKETS) {
            for (Family f : Family.values()) {
                for (int i = 0; i < 25; i++) bimodal.add(InstanceGenerator.generate(OOD_SEED + 1, i, f, bk, DurationDistribution.BIMODAL));
            }
        }
        sets.put("OOD: BIMODAL durations (all 8 families)", bimodal);
        List<Instance> par = new ArrayList<>();
        List<Instance> parBase = coreSet(PARETO_SEED, 35, CORE_BUCKETS);
        for (int i = 0; i < parBase.size(); i++) par.add(pareto(parBase.get(i), PARETO_SEED, i));
        sets.put("OOD: Pareto heavy-tailed durations (never used in Phases 1-3)", par);

        StringBuilder md = new StringBuilder("# Final evaluation (run once; schedulers frozen in final-frozen.txt)\n\n");
        StringBuilder csv = new StringBuilder("set,instance,family,bucket,durations,workers,n,lower_bound");
        for (Scheduler s : sch) csv.append(",\"").append(s.name()).append('"');
        csv.append('\n');
        int b = 3;
        for (var e : sets.entrySet()) {
            List<Instance> list = e.getValue();
            long[][] starts = new long[sch.size()][list.size()];
            Results res = evaluateWithCost(list, sch, starts);
            Summary sum = new Summary(res, starts, sch);
            int[] all = ExperimentRunner.all(res);
            md.append("## ").append(e.getKey()).append(" — ").append(list.size()).append(" instances\n\n")
                    .append(ExperimentRunner.datasetSummary(list)).append(".\n\n");
            md.append(sum.table(range(sch.size())));
            md.append("\n### Regressions against CP-first and B*\n\n").append(FAILURE_HEADER);
            for (int v = 0; v < sch.size(); v++) {
                if (v == CP_INDEX || sch.get(v).name().equals(CP_STUDY)) continue;
                md.append(failureRow(sch.get(v).name() + " vs CP-first", res.makespan[v], res.makespan[CP_INDEX], all));
            }
            for (int v = 0; v < sch.size(); v++) {
                if (v == b || v == CP_INDEX || sch.get(v).name().equals(CP_STUDY)) continue;
                md.append(failureRow(sch.get(v).name() + " vs B*", res.makespan[v], res.makespan[b], all));
            }
            md.append("\n### Breakdown, GM vs CP-first\n\n| group | n |");
            for (Scheduler s : sch) md.append(' ').append(s.name()).append(" |");
            md.append("\n|---|---|");
            for (int v = 0; v < sch.size(); v++) md.append("---|");
            md.append('\n');
            for (var gr : groups(true).entrySet()) {
                int[] sub = where(list, gr.getValue());
                if (sub.length == 0) continue;
                md.append("| ").append(gr.getKey()).append(" | ").append(sub.length).append(" |");
                for (int v = 0; v < sch.size(); v++) md.append(' ').append(f4(gm(res.makespan[v], res.makespan[CP_INDEX], sub))).append(" |");
                md.append('\n');
            }
            md.append('\n');
            for (int i = 0; i < list.size(); i++) {
                Instance x = list.get(i);
                csv.append('"').append(e.getKey()).append("\",").append(x.id).append(',').append(x.family).append(',').append(x.bucket)
                        .append(',').append(durationLabel(x)).append(',').append(x.workers).append(',').append(x.size()).append(',').append(x.lowerBound);
                for (int v = 0; v < sch.size(); v++) csv.append(',').append(res.makespan[v][i]);
                csv.append('\n');
            }
            System.out.println("done: " + e.getKey());
        }
        Files.writeString(dir.resolve("final.csv"), csv);
        Files.writeString(dir.resolve("final.md"), md);

        // exact comparison on a fresh small set
        List<Instance> small = exactSet(FINAL_EXACT_SEED, 100);
        Results res = evaluate(small, sch);
        int[] ub = bestOf(res, range(sch.size()));
        ExactResult[] ex = pool.submit(() -> IntStream.range(0, small.size()).parallel().mapToObj(i -> new ExactResult(
                ExactSolver.optimal(small.get(i).tasks, small.get(i).workers, ub[i], EXACT_NODE_LIMIT),
                ExactSolver.bestNonDelay(small.get(i).tasks, small.get(i).workers, ub[i], EXACT_NODE_LIMIT)))
                .toArray(ExactResult[]::new)).join();
        int[] solved = IntStream.range(0, small.size()).filter(i -> ex[i].optimal() > 0 && ex[i].nonDelay() > 0).toArray();
        int[] opt = Arrays.stream(ex).mapToInt(ExactResult::optimal).toArray();
        int[] nd = Arrays.stream(ex).mapToInt(ExactResult::nonDelay).toArray();
        StringBuilder em = new StringBuilder("## Exact comparison (seed " + FINAL_EXACT_SEED + ", " + small.size() + " instances, "
                + ExperimentRunner.datasetSummary(small) + "; solved " + solved.length + ")\n\n");
        em.append("ND upper bounds come from all frozen schedulers; ND is valid because a non-delay schedule (CP-first) is among them, "
                + "and no production scheduler beat ND on the earlier exact set.\n\n");
        em.append("| scheduler | GM ms/OPT | % optimal | GM ms/ND | % equal to ND | worst ms/OPT |\n|---|---|---|---|---|---|\n");
        for (int v = 0; v < sch.size(); v++) em.append(exactRow(sch.get(v).name(), res.makespan[v], opt, nd, solved));
        em.append(exactRow("ND (best list schedule)", nd, opt, nd, solved));
        Files.writeString(dir.resolve("final.md"), em, java.nio.file.StandardOpenOption.APPEND);
        Files.writeString(marker, "completed " + new Date() + "\n", java.nio.file.StandardOpenOption.APPEND);
        System.out.println(em);
    }

    // ------------------------------------------------------------------ stages: timing

    /** Single-threaded CPU time (median of 3) and allocation per run of the final schedulers, by size. */
    void timing() throws IOException {
        List<Scheduler> sch = finalSchedulers();
        List<Instance> list = coreSet(TIMING_SEED, 5, SizeBucket.SMALL, SizeBucket.MEDIUM, SizeBucket.LARGE, SizeBucket.XLARGE);
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) THREADS;
        long tid = Thread.currentThread().threadId();
        SizeBucket[] buckets = {SizeBucket.SMALL, SizeBucket.MEDIUM, SizeBucket.LARGE, SizeBucket.XLARGE};
        double[][] ms = new double[sch.size()][buckets.length];
        double[][] mb = new double[sch.size()][buckets.length];
        int[] count = new int[buckets.length];
        for (Instance x : list) {   // warm-up
            Graph g = new Graph(x);
            StaticGraphAnalysis an = StaticGraphAnalysis.of(x.tasks);
            for (Scheduler s : sch) s.run(x, g, an);
        }
        for (Instance x : list) {
            int bk = Arrays.asList(buckets).indexOf(x.bucket);
            count[bk]++;
            Graph g = new Graph(x);
            StaticGraphAnalysis an = StaticGraphAnalysis.of(x.tasks);
            for (int v = 0; v < sch.size(); v++) {
                // wall clock on one thread (thread CPU time is ~15.6 ms granular on Windows);
                // each measurement repeats the run until at least 20 ms have passed
                long[] t = new long[3];
                long[] a = new long[3];
                for (int r = 0; r < 3; r++) {
                    long a0 = mx.getThreadAllocatedBytes(tid);
                    long c0 = System.nanoTime();
                    int reps = 0;
                    do {
                        sch.get(v).run(x, g, an);
                        reps++;
                    } while (System.nanoTime() - c0 < 20_000_000L);
                    t[r] = (System.nanoTime() - c0) / reps;
                    a[r] = (mx.getThreadAllocatedBytes(tid) - a0) / reps;
                }
                Arrays.sort(t);
                Arrays.sort(a);
                ms[v][bk] += t[1] / 1e6;
                mb[v][bk] += a[1] / 1e6;
            }
        }
        StringBuilder md = new StringBuilder("# Single-threaded CPU time and allocation per run\n\n");
        md.append("Seed ").append(TIMING_SEED).append(", 5 instances per core family and size (").append(list.size())
                .append("); median of 3 wall-clock measurements (each repeating the run for at least 20 ms) after a warm-up pass, one thread; graph construction excluded.\n\n| scheduler |");
        for (SizeBucket bk : buckets) md.append(' ').append(bk).append(" ms |");
        for (SizeBucket bk : buckets) md.append(' ').append(bk).append(" MB |");
        md.append("\n|---|");
        for (int k = 0; k < 2 * buckets.length; k++) md.append("---|");
        md.append('\n');
        for (int v = 0; v < sch.size(); v++) {
            md.append("| ").append(sch.get(v).name()).append(" |");
            for (int k = 0; k < buckets.length; k++) md.append(String.format(Locale.ROOT, " %.3f |", ms[v][k] / count[k]));
            for (int k = 0; k < buckets.length; k++) md.append(String.format(Locale.ROOT, " %.2f |", mb[v][k] / count[k]));
            md.append('\n');
        }
        Files.writeString(dir.resolve("timing.md"), md);
        System.out.println(md);
    }

    // ------------------------------------------------------------------ entry point

    public static void main(String[] args) throws Exception {
        String stage = args.length > 0 ? args[0] : "verify";
        Path dir = Path.of(args.length > 1 ? args[1] : "target/phase3/run1");
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : Runtime.getRuntime().availableProcessors();
        Files.createDirectories(dir);
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"));
        CriticalPathStudy s = new CriticalPathStudy(dir, threads);
        long t0 = System.currentTimeMillis();
        try {
            switch (stage) {
                case "verify" -> s.verify();
                case "explore" -> s.explore();
                case "decisions" -> s.decisionStudy();
                case "exact" -> s.exact();
                case "cases" -> s.cases();
                case "dev" -> s.dev();
                case "final" -> s.finalTest();
                case "timing" -> s.timing();
                default -> throw new IllegalArgumentException("unknown stage " + stage);
            }
        } finally {
            s.pool.shutdown();
            Files.writeString(dir.resolve("stage-times.csv"), String.format(Locale.ROOT, "%s,%.1f%n", stage,
                    (System.currentTimeMillis() - t0) / 1000.0), java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        }
    }

    /** The study simulator must reproduce production CP-first (BATCHED) exactly. */
    void verify() throws IOException {
        List<Instance> list = new ArrayList<>(coreSet(EXPLORE_SEED + 900, 40, SizeBucket.TINY, SizeBucket.SMALL,
                SizeBucket.MEDIUM, SizeBucket.LARGE));
        for (Family f : Family.values()) for (int i = 0; i < 20; i++) list.add(InstanceGenerator.generate(EXPLORE_SEED + 901, i, f, SizeBucket.XLARGE));
        int[] mismatch = pool.submit(() -> list.parallelStream().mapToInt(x -> {
            Graph g = new Graph(x);
            int prod = CP_PROD.run(x, StaticGraphAnalysis.of(x.tasks));
            int sim = listSchedule(g, Rule.CP.keys(g));
            int never = pilotSchedule(g, Rule.CP.keys(g), new Pilot(4, FULL, (st, r, fr) -> false));
            int ldwProd = Variant.baseline(BenchmarkStrategy.LARGEST_DOWNSTREAM_WORK, SchedulerConfig.CompletionMode.BATCHED)
                    .run(x, StaticGraphAnalysis.of(x.tasks));
            int ldwSim = listSchedule(g, Rule.LDW.keys(g));
            return (prod != sim ? 1 : 0) + (never != sim ? 2 : 0) + (ldwProd != ldwSim ? 4 : 0);
        }).toArray()).join();
        int cpBad = 0, pilotBad = 0, ldwBad = 0;
        for (int b : mismatch) {
            if ((b & 1) != 0) cpBad++;
            if ((b & 2) != 0) pilotBad++;
            if ((b & 4) != 0) ldwBad++;
        }
        String msg = String.format(Locale.ROOT, "verify: %d instances; CP sim vs production mismatches %d; "
                + "never-firing pilot vs CP %d; LDW sim vs production %d%n", list.size(), cpBad, pilotBad, ldwBad);
        Files.writeString(dir.resolve("verify.md"), msg);
        System.out.print(msg);
        if (cpBad + pilotBad + ldwBad > 0) throw new IllegalStateException("study simulator differs from production");
    }
}
