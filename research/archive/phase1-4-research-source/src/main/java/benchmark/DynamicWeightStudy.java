package benchmark;

import backend.StaticGraphAnalysis;
import benchmark.CriticalPathStudy.Graph;

import benchmark.CriticalPathStudy.Policy;
import benchmark.CriticalPathStudy.Rule;
import benchmark.CriticalPathStudy.Scheduler;
import benchmark.CriticalPathStudy.Sim;
import benchmark.CriticalPathStudy.Summary;
import benchmark.CriticalPathStudy.Trigger;
import benchmark.ExperimentRunner.Results;
import benchmark.InstanceGenerator.DurationDistribution;
import benchmark.InstanceGenerator.Family;
import benchmark.InstanceGenerator.Instance;
import benchmark.InstanceGenerator.SizeBucket;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.IntStream;

/**
 * Phase 4 research harness: can lookahead detect states where CP-first is about to
 * decide badly, and can switching the weights of the existing scoring components
 * (critical path, blocking power, reachable workload, and the five dynamic terms)
 * in those states improve the schedule — compared with letting the lookahead pick
 * the task directly?
 *
 * Experimental code only; reuses the Phase 3 simulator (CriticalPathStudy) and
 * changes no production behaviour. The scoring terms reproduce TimeConstraints.scoreSet
 * and DynamicScoreLogic.makeDynamicScore on the simulator state (see score()).
 *
 * Usage: java -cp target/classes benchmark.DynamicWeightStudy <stage> [dir] [threads]
 */
public final class DynamicWeightStudy {

    static final long EXPLORE_SEED = 91001;
    static final long DEV_SEED = 91002;
    static final long FINAL_SEED = 91003;
    static final long OOD_SEED = 91004;
    static final long PARETO_SEED = 91005;
    static final long FINAL_EXACT_SEED = 91006;
    static final long TIMING_SEED = 91007;

    static final int ALL = Integer.MAX_VALUE;
    static final int PROBE_EVENTS = 8;

    final Path dir;
    final CriticalPathStudy base;

    DynamicWeightStudy(Path dir, int threads) {
        this.dir = dir;
        this.base = new CriticalPathStudy(dir, threads);
    }

    // ------------------------------------------------------------------ weight profiles

    /**
     * Weights of the existing scoring components. Static: critical path, blocking
     * power, reachable workload. Dynamic: open-to-block, open children, child
     * importance, duration-to-ready, children-to-ready-queue. Score = lambda * static
     * + (1 - lambda) * dynamic; {@code pool} = candidate pool size K (best static scores).
     */
    record Profile(String name, double[] ws, double[] wd, double lambda, int pool) {
        Profile withLambda(double l) {
            return new Profile(name + " (lambda " + String.format(Locale.ROOT, "%.2f", l) + ")", ws, wd, l, pool);
        }
    }

    static final double[] NO_DYNAMIC = {0, 0, 0, 0, 0};
    static final double[] ORIGINAL_DYNAMIC = {0.2, 0.1, 0.15, 0.3, 0.25};

    static final Profile CP = new Profile("CP (critical path only)", new double[]{1, 0, 0}, NO_DYNAMIC, 1, ALL);

    /** The profile library: CP, the project's own weight sets, and one profile per scoring component family. */
    static final List<Profile> LIBRARY = List.of(
            CP,
            new Profile("ORIGINAL weights (K=3)", new double[]{0.5, 0.25, 0.25}, ORIGINAL_DYNAMIC, 0.5, 3),
            new Profile("A* weights (K=2)", new double[]{0.9419505011700513, 0.014377117009955828, 0.04367238181999288},
                    new double[]{0.018118364036328133, 0.14181848692297505, 0.3210363092104712, 0.09321126939904587, 0.4258155704311797},
                    0.9893181762524133, 2),
            new Profile("CP + blocking power", new double[]{0.5, 0.5, 0}, NO_DYNAMIC, 1, ALL),
            new Profile("CP + reachable workload", new double[]{0.5, 0, 0.5}, NO_DYNAMIC, 1, ALL),
            new Profile("CP + unlocking (open children, child importance)", new double[]{1, 0, 0}, new double[]{0, 0.5, 0.5, 0, 0}, 0.5, ALL),
            new Profile("CP + open-to-block", new double[]{1, 0, 0}, new double[]{1, 0, 0, 0, 0}, 0.5, ALL),
            new Profile("CP + duration-to-ready", new double[]{1, 0, 0}, new double[]{0, 0, 0, 1, 0}, 0.5, ALL),
            new Profile("CP + children-to-ready-queue", new double[]{1, 0, 0}, new double[]{0, 0, 0, 0, 1}, 0.5, ALL));

    /** Graph-static inputs of the scoring terms, normalized as in TimeConstraints.scoreSet. */
    static final class Terms {
        final double[] cp;
        final double[] block;
        final double[] reach;
        final int maxDuration;
        final int maxChildren;
        final double maxCp;
        final double maxBlock;
        final double work;

        Terms(Graph g) {
            cp = new double[g.n];
            block = new double[g.n];
            reach = new double[g.n];
            double maxBlock = 0;
            int maxD = 1, maxC = 0;
            for (int i = 0; i < g.n; i++) {
                double b = 0;
                for (int c : g.children[i]) b += 1.0 / g.parents[c].length;
                block[i] = b;
                maxBlock = Math.max(maxBlock, b);
                maxD = Math.max(maxD, g.dur[i]);
                maxC = Math.max(maxC, g.children[i].length);
            }
            for (int i = 0; i < g.n; i++) {
                cp[i] = g.cp[i];
                reach[i] = g.reach[i] + g.dur[i];   // production reachable duration includes the task
            }
            maxCp = g.criticalPath;
            this.maxBlock = maxBlock;
            work = g.work;
            maxDuration = maxD;
            maxChildren = maxC;
        }

        /** TimeConstraints.scoreSet, with the same operation order. */
        double staticScore(Profile p, int v) {
            double c = maxCp == 0 ? 0.0 : p.ws()[0] * cp[v] / maxCp;
            double b = maxBlock == 0 ? 0.0 : p.ws()[1] * block[v] / maxBlock;
            double r = work == 0 ? 0.0 : p.ws()[2] * reach[v] / work;
            return c + b + r;
        }

        private final Map<Profile, Double> maxStatic = new IdentityHashMap<>();

        /** Largest static score over all tasks (TaskEngine.maxStaticScore). */
        double maxStatic(Profile p) {
            return maxStatic.computeIfAbsent(p, q -> {
                double m = 0;
                for (int i = 0; i < cp.length; i++) m = Math.max(m, staticScore(q, i));
                return m;
            });
        }
    }

    /** Per-thread cache of the last graph's terms (a run uses one graph). */
    static final ThreadLocal<Object[]> TERMS = ThreadLocal.withInitial(() -> new Object[2]);

    static Terms terms(Graph g) {
        Object[] c = TERMS.get();
        if (c[0] != g) {
            c[0] = g;
            c[1] = new Terms(g);
        }
        return (Terms) c[1];
    }

    /** Blended score of ready task v in state s (DynamicScoreLogic.makeDynamicScore on the simulator state). */
    static double score(Sim s, Terms tm, Profile p, int v, int readySize) {
        double st = tm.staticScore(p, v);
        if (p.lambda() >= 1) return st;
        Graph g = s.g;
        int children = g.children[v].length;
        int open = 0;
        double openImportance = 0;
        for (int c : g.children[v]) {
            if (s.rem[c] == 1) {
                open++;
                openImportance += tm.staticScore(p, c);
            }
        }
        double maxStatic = tm.maxStatic(p);
        double openToBlock = children == 0 ? 0 : open / (double) children;
        double openChildren = Math.min(1, open / (double) g.m);
        double childImportance = open == 0 ? 0 : openImportance / (open * maxStatic);
        double alpha = Math.min(1.0, readySize / (double) g.m);
        double durN = g.dur[v] / (double) tm.maxDuration;
        double durationToReady = alpha * durN + (1 - alpha) * (1 - durN);
        double childrenToQueue = tm.maxChildren == 0 ? 0 : (1 - alpha) * open / (double) tm.maxChildren;
        double[] w = p.wd();
        double dyn = w[0] * openToBlock + w[1] * openChildren + w[2] * childImportance + w[3] * durationToReady + w[4] * childrenToQueue;
        return p.lambda() * st + (1 - p.lambda()) * dyn;
    }

    /**
     * The ready task the profile would start now, as TaskEngine.getNextTask (lookahead
     * off) does: the pool is the K best static scores (ties by task id), and a candidate
     * replaces an earlier one only with a strictly greater blended score. The CP
     * profile is exactly CP-first (ties by list position).
     */
    static int profileChoice(Sim s, Profile p) {
        if (p == CP) return s.ready.peek();
        Terms tm = terms(s.g);
        int r = s.ready.size;
        if (r == 1) return s.ready.heap[0];
        Integer[] all = new Integer[r];
        for (int q = 0; q < r; q++) all[q] = s.ready.heap[q];
        Arrays.sort(all, (a, b) -> {
            int c = Double.compare(tm.staticScore(p, b), tm.staticScore(p, a));
            return c != 0 ? c : Integer.compare(s.g.inst.tasks.get(a).getId(), s.g.inst.tasks.get(b).getId());
        });
        int k = Math.min(p.pool(), r);
        int best = all[0];
        double bestScore = score(s, tm, p, best, r);
        for (int q = 1; q < k; q++) {
            double sc = score(s, tm, p, all[q], r);
            if (sc > bestScore) {
                best = all[q];
                bestScore = sc;
            }
        }
        return best;
    }

    /** List scheduling with a fixed profile. */
    static final class ProfilePolicy implements Policy {
        final Profile p;

        ProfilePolicy(Profile p) {
            this.p = p;
        }

        public int choose(Sim s) {
            return s.ready.size <= s.free ? s.ready.peek() : profileChoice(s, p);
        }
    }

    // ------------------------------------------------------------------ lookahead detector

    /**
     * Starvation probe: continue CP-first from the current state for PROBE_EVENTS
     * completion events (or to the end) and return the fraction of worker-time spent
     * idle while tasks remain (free workers with nothing ready).
     */
    static double starvationProbe(Sim s, int events) {
        Sim c = s.copy();
        int t0 = c.t;
        long idle = 0;
        int base = c.events;
        while (c.done < c.g.n) {
            while (c.free > 0 && c.ready.size > 0) c.startTask(c.ready.peek());
            if (c.events - base >= events || c.running.size == 0) break;
            int next = c.fin[c.running.heap[0]];
            idle += (long) c.free * (next - c.t);
            c.advance();
        }
        int span = Math.max(1, c.t - t0);
        return idle / (double) (c.g.m * (long) span);
    }

    static Trigger probeAtLeast(double theta) {
        return (s, r, f) -> starvationProbe(s, PROBE_EVENTS) >= theta;
    }

    // ------------------------------------------------------------------ approaches

    /** Detector-gated weight switching: when the probe fires at a contested batch, the batch is scheduled with profile alt. */
    static final class SwitchPolicy implements Policy {
        final double theta;
        final Profile alt;
        final double beta;     // > 0: continuous lambda = max(0, 1 - beta * probe) with alt's dynamic weights
        int decided = -1;
        Profile active;

        SwitchPolicy(double theta, Profile alt, double beta) {
            this.theta = theta;
            this.alt = alt;
            this.beta = beta;
        }

        public int choose(Sim s) {
            if (s.ready.size <= s.free) return s.ready.peek();
            if (s.t != decided) {
                decided = s.t;
                double probe = starvationProbe(s, PROBE_EVENTS);
                active = null;
                if (probe >= theta && probe > 0) {
                    active = beta > 0 ? new Profile("continuous", new double[]{1, 0, 0}, alt.wd(), Math.max(0, 1 - beta * probe), ALL) : alt;
                }
            }
            return active == null ? s.ready.peek() : profileChoice(s, active);
        }
    }

    /**
     * Lookahead chooses among the tasks the profile library proposes (instead of
     * CP-first's next-ranked tasks): at a contested pick where the gate fires, each
     * task some profile would start but CP-first would defer is started and the
     * schedule continued with CP-first (full depth or {@code depth} events); the best
     * value wins, ties keep CP-first's choice.
     */
    static final class ProfileCandidatePilot implements Policy {
        final double theta;
        final int depth;
        int settled = -1;

        ProfileCandidatePilot(double theta, int depth) {
            this.theta = theta;
            this.depth = depth;
        }

        public int choose(Sim s) {
            int r = s.ready.size, f = s.free;
            if (r <= f || s.t == settled) return s.ready.peek();
            if (theta > 0 && starvationProbe(s, PROBE_EVENTS) < theta) {
                settled = s.t;
                return s.ready.peek();
            }
            int[] cpSet = s.ready.top(f);
            Set<Integer> chosenByCp = new HashSet<>();
            for (int v : cpSet) chosenByCp.add(v);
            LinkedHashSet<Integer> cand = new LinkedHashSet<>();
            for (Profile p : LIBRARY) {
                // the profile's whole batch: its sequential choices for the f free workers
                Sim c = s.copy();
                for (int q = 0; q < f && c.ready.size > 0; q++) {
                    int v = profileChoice(c, p);
                    if (!chosenByCp.contains(v)) cand.add(v);
                    c.startTask(v);
                }
            }
            int best = cpSet[0];
            int bestValue = evaluate(s, best, depth);
            for (int v : cand) {
                int val = evaluate(s, v, depth);
                if (val < bestValue) {
                    best = v;
                    bestValue = val;
                }
            }
            if (best == cpSet[0]) settled = s.t;
            return best;
        }
    }

    static int evaluate(Sim s, int candidate, int depth) {
        Sim x = s.copy();
        x.startTask(candidate);
        return x.run(null, depth);
    }

    /**
     * Policy-level profile rollout ("rollout with multiple base heuristics"): at a
     * contested batch where the gate fires, every profile is run as the policy for the
     * whole remaining schedule; the best one becomes the active policy until the next
     * evaluation. The current active profile is always among the candidates, so the
     * predicted makespan never increases and the result is never worse than CP-first.
     */
    static final class PolicyRollout implements Policy {
        final double theta;
        Profile active = CP;
        int decided = -1;

        PolicyRollout(double theta) {
            this.theta = theta;
        }

        public int choose(Sim s) {
            if (s.ready.size <= s.free) return s.ready.peek();
            if (s.t != decided) {
                decided = s.t;
                if (theta <= 0 || starvationProbe(s, PROBE_EVENTS) >= theta) {
                    Profile best = active;
                    int bestValue = s.copy().run(new ProfilePolicy(active), CriticalPathStudy.FULL);
                    for (Profile p : LIBRARY) {
                        if (p == active) continue;
                        int v = s.copy().run(new ProfilePolicy(p), CriticalPathStudy.FULL);
                        if (v < bestValue) {
                            best = p;
                            bestValue = v;
                        }
                    }
                    active = best;
                }
            }
            return profileChoice(s, active);
        }
    }

    /** Distilled selector: no lookahead online; a small decision tree on cheap state features says when to use profile alt. */
    static final class DistilledPolicy implements Policy {
        final Tree tree;
        final Profile alt;
        int decided = -1;
        boolean use;

        DistilledPolicy(Tree tree, Profile alt) {
            this.tree = tree;
            this.alt = alt;
        }

        public int choose(Sim s) {
            int r = s.ready.size, f = s.free;
            if (r <= f) return s.ready.peek();
            if (s.t != decided) {
                decided = s.t;
                int[] ranked = s.ready.top(f + 1);
                use = tree.predict(CriticalPathStudy.decisionFeatures(s, ranked, f, 1));
            }
            return use ? profileChoice(s, alt) : s.ready.peek();
        }
    }

    // ------------------------------------------------------------------ a tiny decision tree (Gini, depth <= 2)

    /** Features never used by the tree: they need the final makespan. */
    static final Set<String> MAKESPAN_FEATURES = Set.of("time_fraction", "remaining_over_makespan");

    record Tree(int feature, double threshold, Tree left, Tree right, boolean leaf, boolean positive) {
        boolean predict(double[] x) {
            if (leaf) return positive;
            return x[feature] <= threshold ? left.predict(x) : right.predict(x);
        }

        String describe(String indent) {
            if (leaf) return indent + "-> " + (positive ? "use alternative profile" : "keep CP") + "\n";
            String name = CriticalPathStudy.DECISION_FEATURES[feature];
            return indent + "if " + name + " <= " + String.format(Locale.ROOT, "%.4g", threshold) + ":\n" + left.describe(indent + "  ")
                    + indent + "else:\n" + right.describe(indent + "  ");
        }

        static Tree fit(double[][] x, boolean[] y, int depth, int minLeaf) {
            int pos = 0;
            for (boolean b : y) if (b) pos++;
            boolean majority = pos * 2 > y.length;
            if (depth == 0 || y.length < 2 * minLeaf || pos == 0 || pos == y.length) return new Tree(-1, 0, null, null, true, majority);
            double bestGini = gini(pos, y.length);
            int bf = -1;
            double bt = 0;
            for (int j = 0; j < x[0].length; j++) {
                if (MAKESPAN_FEATURES.contains(CriticalPathStudy.DECISION_FEATURES[j])) continue;
                final int col = j;
                Integer[] o = new Integer[y.length];
                for (int i = 0; i < o.length; i++) o[i] = i;
                Arrays.sort(o, Comparator.comparingDouble(i -> x[i][col]));
                int leftPos = 0;
                for (int k = 0; k < o.length - 1; k++) {
                    if (y[o[k]]) leftPos++;
                    int nl = k + 1;
                    if (nl < minLeaf || o.length - nl < minLeaf || x[o[k]][col] == x[o[k + 1]][col]) continue;
                    double g = (nl * gini(leftPos, nl) + (o.length - nl) * gini(pos - leftPos, o.length - nl)) / o.length;
                    if (g < bestGini - 1e-9) {
                        bestGini = g;
                        bf = j;
                        bt = (x[o[k]][col] + x[o[k + 1]][col]) / 2;
                    }
                }
            }
            if (bf < 0) return new Tree(-1, 0, null, null, true, majority);
            List<Integer> l = new ArrayList<>(), r = new ArrayList<>();
            for (int i = 0; i < y.length; i++) (x[i][bf] <= bt ? l : r).add(i);
            return new Tree(bf, bt, fit(sub(x, l), sub(y, l), depth - 1, minLeaf), fit(sub(x, r), sub(y, r), depth - 1, minLeaf), false, false);
        }

        static double gini(int pos, int n) {
            double p = pos / (double) n;
            return 2 * p * (1 - p);
        }

        static double[][] sub(double[][] x, List<Integer> idx) {
            double[][] out = new double[idx.size()][];
            for (int k = 0; k < out.length; k++) out[k] = x[idx.get(k)];
            return out;
        }

        static boolean[] sub(boolean[] y, List<Integer> idx) {
            boolean[] out = new boolean[idx.size()];
            for (int k = 0; k < out.length; k++) out[k] = y[idx.get(k)];
            return out;
        }

        String serialize() {
            return leaf ? "L" + (positive ? 1 : 0) : "N" + feature + ":" + threshold + "(" + left.serialize() + ")(" + right.serialize() + ")";
        }

        static Tree parse(String s) {
            int[] pos = {0};
            return parse(s, pos);
        }

        private static Tree parse(String s, int[] pos) {
            char c = s.charAt(pos[0]++);
            if (c == 'L') return new Tree(-1, 0, null, null, true, s.charAt(pos[0]++) == '1');
            int colon = s.indexOf(':', pos[0]);
            int open = s.indexOf('(', colon);
            int feature = Integer.parseInt(s.substring(pos[0], colon));
            double threshold = Double.parseDouble(s.substring(colon + 1, open));
            pos[0] = open + 1;
            Tree left = parse(s, pos);
            pos[0] += 2;   // ")("
            Tree right = parse(s, pos);
            pos[0] += 1;   // ")"
            return new Tree(feature, threshold, left, right, false, false);
        }
    }

    // ------------------------------------------------------------------ schedulers

    record PolicyScheduler(String name, java.util.function.Supplier<Policy> policy) implements Scheduler {
        public int run(Instance x, Graph g, StaticGraphAnalysis an) {
            return new Sim(g, Rule.CP.keys(g)).run(policy.get(), CriticalPathStudy.FULL);
        }
    }

    /** Lookahead picks the single best fixed profile for the whole instance (one full run per profile). */
    record BestProfilePerInstance(String name) implements Scheduler {
        public int run(Instance x, Graph g, StaticGraphAnalysis an) {
            int best = Integer.MAX_VALUE;
            for (Profile p : LIBRARY) best = Math.min(best, new Sim(g, Rule.CP.keys(g)).run(new ProfilePolicy(p), CriticalPathStudy.FULL));
            return best;
        }
    }

    // ------------------------------------------------------------------ datasets

    static List<Instance> exploreSet() {
        List<Instance> list = CriticalPathStudy.coreSet(EXPLORE_SEED, 100, SizeBucket.SMALL, SizeBucket.MEDIUM);
        list.addAll(CriticalPathStudy.coreSet(EXPLORE_SEED, 20, SizeBucket.LARGE));
        return list;
    }

    // ------------------------------------------------------------------ stage: decisions (evidence for detection and weight choice)

    /** One contested batch of a CP-first run with every counterfactual value needed by E1 and E2. */
    record Batch(int instance, int makespan, int bestSwap, double probe8, double probeFull, double probeAfterBestSwap,
                 double[] features, int[] profileBatchValue, int[] profilePolicyValue, boolean[] profileDiffers) {
        int regret() {
            return Math.max(0, makespan - bestSwap);
        }
    }

    static List<Batch> batches(int instance, Graph g) {
        long[] key = Rule.CP.keys(g);
        int ms = CriticalPathStudy.listSchedule(g, key);
        List<Batch> out = new ArrayList<>();
        Sim s = new Sim(g, key);
        int np = LIBRARY.size();
        while (s.done < g.n) {
            if (s.free > 0 && s.ready.size > s.free) {
                int f = s.free;
                int[] ranked = s.ready.top(f + CriticalPathStudy.SWAP_IN);
                int best = Integer.MAX_VALUE;
                Sim bestState = null;
                for (int so = Math.max(0, f - CriticalPathStudy.SWAP_OUT); so < f; so++) {
                    for (int si = f; si < ranked.length; si++) {
                        Sim c = s.copy();
                        for (int q = 0; q < f; q++) if (q != so) c.startTask(ranked[q]);
                        c.startTask(ranked[si]);
                        Sim state = c.copy();
                        int v = c.run(null, CriticalPathStudy.FULL);
                        if (v < best) {
                            best = v;
                            bestState = state;
                        }
                    }
                }
                double probeAfter = Double.NaN;
                if (bestState != null && best < ms) probeAfter = starvationProbe(bestState, PROBE_EVENTS);
                int[] batchValue = new int[np];
                int[] policyValue = new int[np];
                boolean[] differs = new boolean[np];
                Set<Integer> cpSet = new HashSet<>();
                for (int q = 0; q < f; q++) cpSet.add(ranked[q]);
                for (int p = 0; p < np; p++) {
                    Profile pr = LIBRARY.get(p);
                    Sim c = s.copy();
                    for (int q = 0; q < f && c.ready.size > 0; q++) {
                        int v = profileChoice(c, pr);
                        if (!cpSet.contains(v)) differs[p] = true;
                        c.startTask(v);
                    }
                    Sim policy = c.copy();
                    batchValue[p] = c.run(null, CriticalPathStudy.FULL);
                    policyValue[p] = policy.run(new ProfilePolicy(pr), CriticalPathStudy.FULL);
                }
                out.add(new Batch(instance, ms, Math.min(best, ms), starvationProbe(s, PROBE_EVENTS),
                        starvationProbe(s, CriticalPathStudy.FULL), probeAfter,
                        CriticalPathStudy.decisionFeatures(s, ranked, f, ms), batchValue, policyValue, differs));
                while (s.free > 0 && s.ready.size > 0) s.startTask(s.ready.peek());
            }
            if (s.done == g.n) break;
            if (s.free > 0 && s.ready.size > 0) {
                while (s.free > 0 && s.ready.size > 0) s.startTask(s.ready.peek());
            }
            s.advance();
        }
        return out;
    }

    void decisions() throws IOException {
        List<Instance> list = exploreSet();
        List<List<Batch>> per = base.pool.submit(() -> IntStream.range(0, list.size()).parallel()
                .mapToObj(i -> batches(i, new Graph(list.get(i)))).toList()).join();
        List<Batch> all = new ArrayList<>();
        per.forEach(all::addAll);
        int n = all.size();
        int np = LIBRARY.size();
        boolean[] bad = new boolean[n];
        boolean[] severe = new boolean[n];
        double[] probe8 = new double[n], probeFull = new double[n];
        double totalRegret = 0;
        int positives = 0;
        for (int k = 0; k < n; k++) {
            Batch b = all.get(k);
            bad[k] = b.regret() > 0;
            severe[k] = b.regret() >= 0.01 * b.makespan();
            probe8[k] = b.probe8();
            probeFull[k] = b.probeFull();
            totalRegret += b.regret() / (double) b.makespan();
            if (bad[k]) positives++;
        }
        int cheap = Arrays.asList(CriticalPathStudy.DECISION_FEATURES).indexOf("deferred_cp_over_remaining");
        double[] cheapScore = new double[n];
        for (int k = 0; k < n; k++) cheapScore[k] = all.get(k).features()[cheap];

        StringBuilder md = new StringBuilder("# E1/E2: detecting bad CP-first decisions and choosing better weights\n\n");
        md.append(String.format(Locale.ROOT, "Exploration set (seed %d): %s. %d contested batches of CP-first runs; "
                        + "a batch is *bad* if some swap (one of the %d lowest-ranked started tasks for one of the %d best deferred tasks) "
                        + "followed by CP-first gives a smaller makespan (%s of batches); *severe* if by >= 1%% (%s).%n%n",
                EXPLORE_SEED, ExperimentRunner.datasetSummary(list), n, CriticalPathStudy.SWAP_OUT, CriticalPathStudy.SWAP_IN,
                CriticalPathStudy.pct(positives / (double) n), CriticalPathStudy.pct(count(severe) / (double) n)));

        // ---- E1: detection
        md.append("## E1. Can lookahead detect bad CP-first decisions?\n\n");
        md.append("Starvation probe = idle worker-time fraction while tasks remain, in a CP-first continuation of the current state "
                + "for 8 completion events (probe8, cost 1 partial simulation) or to the end (probe-full, 1 full simulation).\n\n");
        md.append("| detector | cost per contested batch | AUC (bad) | AUC (severe) | mean on bad | mean on good |\n|---|---|---|---|---|---|\n");
        md.append(detectorRow("probe8 (lookahead, 8 events)", "1 partial simulation", probe8, bad, severe));
        md.append(detectorRow("probe-full (lookahead to the end)", "1 full simulation", probeFull, bad, severe));
        md.append(detectorRow("deferred CP / remaining time (cheap, best Phase 3 feature)", "none", cheapScore, bad, severe));
        md.append("\n| probe8 threshold | batches flagged | precision (bad) | recall (bad) | share of total regret in flagged batches |\n|---|---|---|---|---|\n");
        for (double th : new double[]{0.0001, 0.05, 0.1, 0.15, 0.2, 0.3, 0.5}) {
            int flagged = 0, tp = 0;
            double reg = 0;
            for (int k = 0; k < n; k++) {
                if (probe8[k] >= th) {
                    flagged++;
                    if (bad[k]) tp++;
                    reg += all.get(k).regret() / (double) all.get(k).makespan();
                }
            }
            md.append(String.format(Locale.ROOT, "| >= %s | %s | %s | %s | %s |%n", th, CriticalPathStudy.pct(flagged / (double) n),
                    CriticalPathStudy.pct(tp / (double) Math.max(1, flagged)), CriticalPathStudy.pct(tp / (double) positives),
                    CriticalPathStudy.pct(reg / totalRegret)));
        }
        int starvationFix = 0, withIdle = 0, badWithIdle = 0;
        for (int k = 0; k < n; k++) {
            Batch b = all.get(k);
            if (b.probe8() > 0) withIdle++;
            if (bad[k] && b.probe8() > 0) badWithIdle++;
            if (bad[k] && !Double.isNaN(b.probeAfterBestSwap()) && b.probeAfterBestSwap() < b.probe8()) starvationFix++;
        }
        md.append(String.format(Locale.ROOT, "%nStarvation as the mechanism: %s of bad batches show idle workers in the 8-event CP continuation "
                        + "(vs %s of all batches); in %s of bad batches the best swap reduces that near-term idle time.%n%n",
                CriticalPathStudy.pct(badWithIdle / (double) positives), CriticalPathStudy.pct(withIdle / (double) n),
                CriticalPathStudy.pct(starvationFix / (double) positives)));

        // ---- E2: weight choice
        md.append("## E2. Do other weights of the existing components choose better tasks?\n\n");
        md.append("Each profile schedules the batch (its own choices for the free workers); value = final makespan with CP-first afterwards "
                + "(\"one decision\"), or with the profile kept as the policy to the end (\"as policy\"). Improve/worse are relative to CP-first's batch.\n\n");
        boolean[] fired = new boolean[n];
        for (int k = 0; k < n; k++) fired[k] = probe8[k] >= 0.15;
        md.append("| profile | differs from CP (all batches) | on bad batches: improves / worse | on probe-flagged batches (probe8 >= 0.15): improves / worse | net regret captured on flagged batches | net regret, all batches | as policy to the end, all batches: improves / worse |\n|---|---|---|---|---|---|---|\n");
        int bestProfile = -1;
        double bestNet = -1e9;
        for (int p = 1; p < np; p++) {
            int differs = 0, impBad = 0, worseBad = 0, impF = 0, worseF = 0, flagged = 0, polImp = 0, polWorse = 0;
            double netF = 0, netAll = 0;
            for (int k = 0; k < n; k++) {
                Batch b = all.get(k);
                int v = b.profileBatchValue()[p];
                if (b.profileDiffers()[p]) differs++;
                double rel = (b.makespan() - v) / (double) b.makespan();
                netAll += rel;
                if (bad[k]) {
                    if (v < b.makespan()) impBad++;
                    if (v > b.makespan()) worseBad++;
                }
                if (fired[k]) {
                    flagged++;
                    if (v < b.makespan()) impF++;
                    if (v > b.makespan()) worseF++;
                    netF += rel;
                }
                if (b.profilePolicyValue()[p] < b.makespan()) polImp++;
                if (b.profilePolicyValue()[p] > b.makespan()) polWorse++;
            }
            if (netF > bestNet) {
                bestNet = netF;
                bestProfile = p;
            }
            md.append(String.format(Locale.ROOT, "| %s | %s | %s / %s | %s / %s | %+.1f%% | %+.1f%% | %s / %s |%n", LIBRARY.get(p).name(),
                    CriticalPathStudy.pct(differs / (double) n), CriticalPathStudy.pct(impBad / (double) positives), CriticalPathStudy.pct(worseBad / (double) positives),
                    CriticalPathStudy.pct(impF / (double) Math.max(1, flagged)), CriticalPathStudy.pct(worseF / (double) Math.max(1, flagged)),
                    100 * netF / totalRegret, 100 * netAll / totalRegret, CriticalPathStudy.pct(polImp / (double) n), CriticalPathStudy.pct(polWorse / (double) n)));
        }
        // oracles
        double oracleProfile = 0, oraclePolicy = 0, oracleSwap = 0;
        int oracleProfileFixes = 0;
        for (int k = 0; k < n; k++) {
            Batch b = all.get(k);
            int bp = b.makespan(), bpol = b.makespan();
            for (int p = 0; p < np; p++) {
                bp = Math.min(bp, b.profileBatchValue()[p]);
                bpol = Math.min(bpol, b.profilePolicyValue()[p]);
            }
            oracleProfile += (b.makespan() - bp) / (double) b.makespan();
            oraclePolicy += (b.makespan() - bpol) / (double) b.makespan();
            oracleSwap += b.regret() / (double) b.makespan();
            if (bad[k] && bp < b.makespan()) oracleProfileFixes++;
        }
        md.append(String.format(Locale.ROOT, "%nOracles over all batches (sum of per-batch makespan reduction, relative to the best-swap total = 100%%):%n%n"
                        + "| oracle | regret captured | bad batches improved |%n|---|---|---|%n"
                        + "| best of %d CP-ranked swaps (direct lookahead's candidate set) | 100.0%% | 100.0%% |%n"
                        + "| best profile choice for the batch | %.1f%% | %s |%n| best profile as policy to the end | %.1f%% | - |%n%n",
                CriticalPathStudy.SWAP_OUT * CriticalPathStudy.SWAP_IN, 100 * oracleProfile / totalRegret,
                CriticalPathStudy.pct(oracleProfileFixes / (double) positives), 100 * oraclePolicy / totalRegret));
        md.append("Alternative profile for detector-gated switching (best net regret on flagged batches): **")
                .append(LIBRARY.get(bestProfile).name()).append("**\n\n");
        Files.writeString(dir.resolve("alt-profile.txt"), LIBRARY.get(bestProfile).name() + "\n");

        // ---- distilled selector: when does the alternative profile's choice beat CP-first's?
        double[][] x = new double[n][];
        boolean[] y = new boolean[n];
        for (int k = 0; k < n; k++) {
            x[k] = all.get(k).features();
            y[k] = all.get(k).profileBatchValue()[bestProfile] < all.get(k).makespan();
        }
        Tree tree = Tree.fit(x, y, 2, Math.max(200, n / 50));
        int tp = 0, fp = 0, fn = 0;
        for (int k = 0; k < n; k++) {
            boolean pr = tree.predict(x[k]);
            if (pr && y[k]) tp++;
            if (pr && !y[k]) fp++;
            if (!pr && y[k]) fn++;
        }
        md.append("## Distilled selector (no online lookahead)\n\nDepth-2 tree on cheap state features predicting \"the alternative profile's batch beats CP-first's\" ("
                + CriticalPathStudy.pct(count(y) / (double) n) + " of batches):\n\n```\n" + tree.describe("") + "```\n\n"
                + String.format(Locale.ROOT, "Training fit: precision %s, recall %s.%n", CriticalPathStudy.pct(tp / (double) Math.max(1, tp + fp)),
                CriticalPathStudy.pct(tp / (double) Math.max(1, tp + fn))));
        Files.writeString(dir.resolve("distilled-tree.txt"), tree.serialize() + "\n");
        Files.writeString(dir.resolve("e1e2.md"), md);
        System.out.println(md);
    }

    static int count(boolean[] b) {
        int c = 0;
        for (boolean x : b) if (x) c++;
        return c;
    }

    static String detectorRow(String name, String cost, double[] score, boolean[] bad, boolean[] severe) {
        double mBad = 0, mGood = 0;
        int nb = 0;
        for (int k = 0; k < score.length; k++) {
            if (bad[k]) {
                mBad += score[k];
                nb++;
            } else {
                mGood += score[k];
            }
        }
        return String.format(Locale.ROOT, "| %s | %s | %.3f | %.3f | %.4f | %.4f |%n", name, cost, CriticalPathStudy.auc(score, bad),
                CriticalPathStudy.auc(score, severe), mBad / Math.max(1, nb), mGood / Math.max(1, score.length - nb));
    }

    // ------------------------------------------------------------------ stages: development and final

    Profile altProfile() throws IOException {
        String name = Files.readAllLines(dir.resolve("alt-profile.txt")).get(0).trim();
        return LIBRARY.stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    static final String FULL_PILOT = "direct: pilot L4 full, every contested pick";

    List<Scheduler> devSchedulers() throws IOException {
        Profile alt = altProfile();
        Tree tree = Tree.parse(Files.readAllLines(dir.resolve("distilled-tree.txt")).get(0).trim());
        List<Scheduler> v = new ArrayList<>(List.of(CriticalPathStudy.CP_FIRST, new CriticalPathStudy.ListRule(CriticalPathStudy.CP_STUDY, Rule.CP),
                CriticalPathStudy.FAST, CriticalPathStudy.QUALITY));
        v.add(new CriticalPathStudy.TriggeredPilot(FULL_PILOT, 4, CriticalPathStudy.FULL, CriticalPathStudy.ALWAYS));
        v.add(new CriticalPathStudy.TriggeredPilot("direct: pilot L4 d8, every contested pick", 4, 8, CriticalPathStudy.ALWAYS));
        // fixed profiles (no lookahead)
        for (Profile p : LIBRARY) if (p != CP) v.add(new PolicyScheduler("fixed: " + p.name(), () -> new ProfilePolicy(p)));
        // M1/M2: lookahead detector -> switch weights (no candidate evaluation)
        for (double th : new double[]{0.05, 0.15, 0.3}) {
            v.add(new PolicyScheduler("M1 switch to " + alt.name() + " when probe8 >= " + th, () -> new SwitchPolicy(th, alt, 0)));
        }
        for (double beta : new double[]{1, 3}) {
            v.add(new PolicyScheduler("M2 continuous lambda = 1 - " + beta + " x probe8 (" + alt.name() + " dynamic weights)",
                    () -> new SwitchPolicy(0.0001, alt, beta)));
        }
        // M3/M4: lookahead chooses among the profiles' proposals
        v.add(new PolicyScheduler("M3 probe8 >= 0.15 -> lookahead over profile proposals (full)", () -> new ProfileCandidatePilot(0.15, CriticalPathStudy.FULL)));
        v.add(new PolicyScheduler("M4 lookahead over profile proposals, every contested pick (full)", () -> new ProfileCandidatePilot(0, CriticalPathStudy.FULL)));
        v.add(new PolicyScheduler("M4 lookahead over profile proposals, every contested pick (d8)", () -> new ProfileCandidatePilot(0, 8)));
        // M5: policy-level profile rollout
        v.add(new PolicyScheduler("M5 policy rollout over profiles when probe8 >= 0.15", () -> new PolicyRollout(0.15)));
        v.add(new PolicyScheduler("M5 policy rollout over profiles, every contested batch", () -> new PolicyRollout(0)));
        v.add(new BestProfilePerInstance("M5' best fixed profile per instance (9 full runs)"));
        // M6: distilled, no online lookahead
        v.add(new PolicyScheduler("M6 distilled tree -> " + alt.name() + " (no lookahead)", () -> new DistilledPolicy(tree, alt)));
        // direct lookahead with the same detector (the control for M1-M3)
        v.add(new CriticalPathStudy.TriggeredPilot("direct: probe8 >= 0.15 -> pilot L4 full", 4, CriticalPathStudy.FULL, probeAtLeast(0.15)));
        v.add(new CriticalPathStudy.TriggeredPilot("direct: probe8 >= 0.15 -> pilot L4 d8", 4, 8, probeAtLeast(0.15)));
        return v;
    }

    static boolean isPhase4Method(Scheduler s) {
        return s.name().startsWith("M");
    }

    void dev() throws IOException {
        List<Instance> list = CriticalPathStudy.coreSet(DEV_SEED, 80, CriticalPathStudy.CORE_BUCKETS);
        list.addAll(CriticalPathStudy.coreSet(DEV_SEED, 8, SizeBucket.XLARGE));
        List<Scheduler> sch = devSchedulers();
        long[][] starts = new long[sch.size()][list.size()];
        Results res = base.evaluateWithCost(list, sch, starts);
        Summary sum = new Summary(res, starts, sch);
        StringBuilder md = new StringBuilder("# E3 development set: lookahead-guided weights vs direct lookahead\n\n");
        md.append("Seed ").append(DEV_SEED).append(": ").append(ExperimentRunner.datasetSummary(list)).append(".\n\n");
        md.append(sum.table(CriticalPathStudy.range(sch.size()))).append('\n').append(sum.bySize(CriticalPathStudy.range(sch.size()), list));
        // pre-registered selection: (a) best Phase 4 method by GM vs CP-first; (b) cheapest Phase 4 method (list passes)
        // with GM <= 1.0005 x B*; plus the direct controls for comparison
        int b = res.index(CriticalPathStudy.QUALITY.name());
        int a = -1, c = -1;
        for (int v = 0; v < sch.size(); v++) {
            if (!isPhase4Method(sch.get(v))) continue;
            if (a < 0 || sum.gmVs(v, CriticalPathStudy.CP_INDEX) < sum.gmVs(a, CriticalPathStudy.CP_INDEX)) a = v;
            if (sum.gmVs(v, b) <= 1.0005 && (c < 0 || sum.passes[v] < sum.passes[c])) c = v;
        }
        md.append("\n## Selection (rules fixed before running this stage)\n\n- (a) best Phase 4 method: ").append(sch.get(a).name())
                .append("\n- (b) cheapest Phase 4 method within 0.05% of B*: ").append(c < 0 ? "none" : sch.get(c).name()).append('\n');
        List<String> sel = new ArrayList<>(List.of(sch.get(a).name()));
        if (c >= 0 && c != a) sel.add(sch.get(c).name());
        Files.writeString(dir.resolve("dev-selection.txt"), String.join("\n", sel) + "\n");
        Files.writeString(dir.resolve("dev.md"), md);
        System.out.println(md);
    }

    List<Scheduler> finalSchedulers() throws IOException {
        List<Scheduler> dev = devSchedulers();
        List<String> names = new ArrayList<>(List.of(CriticalPathStudy.CP_FIRST.name(), CriticalPathStudy.CP_STUDY,
                CriticalPathStudy.FAST.name(), CriticalPathStudy.QUALITY.name(), FULL_PILOT, "direct: pilot L4 d8, every contested pick",
                "direct: probe8 >= 0.15 -> pilot L4 full"));
        for (String s : Files.readAllLines(dir.resolve("dev-selection.txt"))) if (!s.isBlank() && !names.contains(s)) names.add(s);
        // the detector-gated weight switch is the literal form of the hypothesis: always tested
        String m1 = dev.stream().filter(s -> s.name().startsWith("M1") && s.name().endsWith("0.15")).findFirst().orElseThrow().name();
        if (!names.contains(m1)) names.add(m1);
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
        sets.put("In-distribution (core families, S/M/L x 120)", CriticalPathStudy.coreSet(FINAL_SEED, 120, CriticalPathStudy.CORE_BUCKETS));
        sets.put("XL graphs (core families, x 15)", CriticalPathStudy.coreSet(FINAL_SEED, 15, SizeBucket.XLARGE));
        List<Instance> held = new ArrayList<>();
        for (SizeBucket bk : CriticalPathStudy.CORE_BUCKETS) {
            for (Family f : new Family[]{Family.FFT, Family.GAUSSIAN}) {
                for (int i = 0; i < 120; i++) held.add(InstanceGenerator.generate(OOD_SEED, i, f, bk, CriticalPathStudy.CORE_DURATIONS));
            }
        }
        sets.put("OOD: held-out families FFT and Gaussian", held);
        List<Instance> bim = new ArrayList<>();
        for (SizeBucket bk : CriticalPathStudy.CORE_BUCKETS) {
            for (Family f : Family.values()) for (int i = 0; i < 20; i++) bim.add(InstanceGenerator.generate(OOD_SEED + 1, i, f, bk, DurationDistribution.BIMODAL));
        }
        sets.put("OOD: BIMODAL durations (all 8 families)", bim);
        List<Instance> par = new ArrayList<>();
        List<Instance> parBase = CriticalPathStudy.coreSet(PARETO_SEED, 30, CriticalPathStudy.CORE_BUCKETS);
        for (int i = 0; i < parBase.size(); i++) par.add(CriticalPathStudy.pareto(parBase.get(i), PARETO_SEED, i));
        sets.put("OOD: Pareto heavy-tailed durations", par);

        StringBuilder md = new StringBuilder("# E3 final evaluation (run once; schedulers frozen in final-frozen.txt)\n\n");
        int qualityIndex = 3;
        for (var e : sets.entrySet()) {
            List<Instance> list = e.getValue();
            long[][] starts = new long[sch.size()][list.size()];
            Results res = base.evaluateWithCost(list, sch, starts);
            Summary sum = new Summary(res, starts, sch);
            int[] all = ExperimentRunner.all(res);
            md.append("## ").append(e.getKey()).append(" — ").append(list.size()).append(" instances\n\n")
                    .append(ExperimentRunner.datasetSummary(list)).append(".\n\n").append(sum.table(CriticalPathStudy.range(sch.size())));
            md.append("\n").append(CriticalPathStudy.FAILURE_HEADER);
            for (int v = 4; v < sch.size(); v++) {
                md.append(CriticalPathStudy.failureRow(sch.get(v).name() + " vs CP-first", res.makespan[v], res.makespan[CriticalPathStudy.CP_INDEX], all));
                md.append(CriticalPathStudy.failureRow(sch.get(v).name() + " vs B*", res.makespan[v], res.makespan[qualityIndex], all));
            }
            md.append('\n');
            System.out.println("done: " + e.getKey());
        }
        List<Instance> small = CriticalPathStudy.exactSet(FINAL_EXACT_SEED, 80);
        Results res = base.evaluate(small, sch);
        int[] ub = CriticalPathStudy.bestOf(res, CriticalPathStudy.range(sch.size()));
        CriticalPathStudy.ExactResult[] ex = base.pool.submit(() -> IntStream.range(0, small.size()).parallel().mapToObj(i -> new CriticalPathStudy.ExactResult(
                ExactSolver.optimal(small.get(i).tasks, small.get(i).workers, ub[i], CriticalPathStudy.EXACT_NODE_LIMIT),
                ExactSolver.bestNonDelay(small.get(i).tasks, small.get(i).workers, ub[i], CriticalPathStudy.EXACT_NODE_LIMIT)))
                .toArray(CriticalPathStudy.ExactResult[]::new)).join();
        int[] solved = IntStream.range(0, small.size()).filter(i -> ex[i].optimal() > 0 && ex[i].nonDelay() > 0).toArray();
        int[] opt = Arrays.stream(ex).mapToInt(CriticalPathStudy.ExactResult::optimal).toArray();
        int[] nd = Arrays.stream(ex).mapToInt(CriticalPathStudy.ExactResult::nonDelay).toArray();
        md.append("## Exact comparison (seed ").append(FINAL_EXACT_SEED).append(", ").append(ExperimentRunner.datasetSummary(small))
                .append("; solved ").append(solved.length).append(")\n\n| scheduler | GM ms/OPT | % optimal | GM ms/ND | % equal to ND | worst ms/OPT |\n|---|---|---|---|---|---|\n");
        for (int v = 0; v < sch.size(); v++) md.append(CriticalPathStudy.exactRow(sch.get(v).name(), res.makespan[v], opt, nd, solved));
        Files.writeString(dir.resolve("final.md"), md);
        Files.writeString(marker, "completed " + new Date() + "\n", java.nio.file.StandardOpenOption.APPEND);
        System.out.println(md);
    }

    /** Single-thread wall time per run (median of 3 measurements, each repeated for >= 20 ms). */
    void timing() throws IOException {
        List<Scheduler> sch = finalSchedulers();
        List<Instance> list = CriticalPathStudy.coreSet(TIMING_SEED, 4, SizeBucket.SMALL, SizeBucket.MEDIUM, SizeBucket.LARGE, SizeBucket.XLARGE);
        SizeBucket[] buckets = {SizeBucket.SMALL, SizeBucket.MEDIUM, SizeBucket.LARGE, SizeBucket.XLARGE};
        double[][] ms = new double[sch.size()][4];
        int[] cnt = new int[4];
        for (Instance x : list) {
            Graph g = new Graph(x);
            StaticGraphAnalysis an = StaticGraphAnalysis.of(x.tasks);
            for (Scheduler s : sch) s.run(x, g, an);
        }
        for (Instance x : list) {
            int bk = Arrays.asList(buckets).indexOf(x.bucket);
            cnt[bk]++;
            Graph g = new Graph(x);
            StaticGraphAnalysis an = StaticGraphAnalysis.of(x.tasks);
            for (int v = 0; v < sch.size(); v++) {
                long[] t = new long[3];
                for (int r = 0; r < 3; r++) {
                    long c0 = System.nanoTime();
                    int reps = 0;
                    do {
                        sch.get(v).run(x, g, an);
                        reps++;
                    } while (System.nanoTime() - c0 < 20_000_000L);
                    t[r] = (System.nanoTime() - c0) / reps;
                }
                Arrays.sort(t);
                ms[v][bk] += t[1] / 1e6;
            }
        }
        StringBuilder md = new StringBuilder("# Single-threaded wall time per run (ms)\n\nSeed " + TIMING_SEED + ", 4 instances per core family and size; median of 3 measurements, each repeated for >= 20 ms.\n\n| scheduler | SMALL | MEDIUM | LARGE | XLARGE |\n|---|---|---|---|---|\n");
        for (int v = 0; v < sch.size(); v++) {
            md.append("| ").append(sch.get(v).name()).append(" |");
            for (int k = 0; k < 4; k++) md.append(String.format(Locale.ROOT, " %.3f |", ms[v][k] / cnt[k]));
            md.append('\n');
        }
        Files.writeString(dir.resolve("timing.md"), md);
        System.out.println(md);
    }

    // ------------------------------------------------------------------ entry point

    public static void main(String[] args) throws Exception {
        String stage = args.length > 0 ? args[0] : "decisions";
        Path dir = Path.of(args.length > 1 ? args[1] : "target/phase4/run1");
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : Runtime.getRuntime().availableProcessors();
        Files.createDirectories(dir);
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"));
        DynamicWeightStudy s = new DynamicWeightStudy(dir, threads);
        long t0 = System.currentTimeMillis();
        try {
            switch (stage) {
                case "decisions" -> s.decisions();
                case "dev" -> s.dev();
                case "final" -> s.finalTest();
                case "timing" -> s.timing();
                default -> throw new IllegalArgumentException("unknown stage " + stage);
            }
        } finally {
            s.base.pool.shutdown();
            Files.writeString(dir.resolve("stage-times.csv"), String.format(Locale.ROOT, "%s,%.1f%n", stage,
                    (System.currentTimeMillis() - t0) / 1000.0), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        }
    }
}
