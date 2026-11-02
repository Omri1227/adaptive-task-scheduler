package benchmark;

import backend.ProjectScenario;
import backend.ScenarioLibrary;
import backend.SchedulerConfig;
import backend.StaticGraphAnalysis;
import backend.Task;
import benchmark.ExperimentRunner.Variant;
import benchmark.InstanceGenerator.DurationDistribution;
import benchmark.InstanceGenerator.Family;
import benchmark.InstanceGenerator.Instance;
import benchmark.InstanceGenerator.SizeBucket;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

/**
 * Phase 2 weight optimization (see PHASE2_REPORT.md for the methodology and
 * results). Stages, run in order, each reading the previous stages' outputs
 * from the run directory:
 * <pre>
 *   calibrate  cost per evaluation of each track (no outputs used later)
 *   datasets   generate train / validation / test / OOD / exact sets, write manifest and checksums
 *   sobol      Stage 0: global sensitivity of the Track A objective (K = 3)
 *   trackA     Stage 1: CMA-ES and equal-budget random search per K in {1, 2, 3, 5}
 *   raceA      Stage 2: pre-screen on the full training set, racing on validation -> A*, S*
 *   trackB     Stage 1: CMA-ES per rollout cell {L 3, 5} x {depth 4, 8}, random-search control in cell (5, 8)
 *   raceB      Stage 2 for Track B -> B*
 *   test       Stage 3: single final evaluation on the independent test sets (refuses to run twice)
 *   timing     single-threaded runtime and allocation per run
 * </pre>
 * Every stage is deterministic. trackA / trackB append every evaluation to
 * {@code evaluations.csv}; an interrupted stage is resumed by running it again:
 * recorded evaluations are replayed instead of recomputed, which reproduces
 * the identical search trajectory.
 *
 * Usage: {@code java -cp target/classes benchmark.WeightOptimizer <stage> [runDir] [threads]}
 */
public final class WeightOptimizer {

    // ------------------------------------------------------------------ seeds (disjoint from Phase 1: 1001, 2002, 3003, 4004)

    static final long TRAIN_SEED = 10001;
    static final long VALIDATION_SEED = 20001;
    static final long TEST_SEED = 30001;
    static final long OOD_SEED = 30002;
    static final long EXACT_SEED = 40001;
    static final long TIMING_SEED = 50001;
    static final long MINIBATCH_SEED = 61001;
    static final long SOBOL_SEED = 62001;
    static final long CMA_SEED = 63001;
    static final long RANDOM_SEARCH_SEED = 64001;
    static final long RACE_SEED = 65001;

    static final Family[] TRAIN_FAMILIES = {Family.ERDOS_RENYI, Family.LAYERED, Family.FORK_JOIN, Family.TREE,
            Family.CHAINS, Family.STENCIL};
    static final Family[] HELD_OUT_FAMILIES = {Family.FFT, Family.GAUSSIAN};
    static final DurationDistribution[] TRAIN_DURATIONS = {DurationDistribution.UNIFORM_1_10,
            DurationDistribution.UNIFORM_1_100, DurationDistribution.LOGNORMAL, DurationDistribution.UNIT};
    static final SizeBucket[] SML = {SizeBucket.SMALL, SizeBucket.MEDIUM, SizeBucket.LARGE};

    // ------------------------------------------------------------------ budgets
    // PHASE1_REPORT.md section 11, scaled after calibration (PHASE2_REPORT.md section 4): the planned
    // 3 x 150 (Track A) and 2 x 60 (Track B) generations would take ~16 h against the 5-6 h ceiling.

    static final int MINIBATCH = 1500;
    static final int POPULATION = 20;
    static final double SIGMA0 = 0.3;
    static final double TOL_X = 1e-3;
    static final int[] TRACK_A_POOL_SIZES = {1, 2, 3, 5};
    /** Generations of IPOP run r (population 20 * 2^r) for K = 1 and for K > 1. */
    static final int[] TRACK_A_GENERATIONS_K1 = {60, 40};
    static final int[] TRACK_A_GENERATIONS = {100, 50};
    static final int TRACK_B_GENERATIONS = 30;
    static final int[][] TRACK_B_CELLS = {{3, 4}, {3, 8}, {5, 4}, {5, 8}};   // {L, depth}
    /** Cell with the second run (from the Phase 1 B0 weights) and the random-search control. */
    static final int[] TRACK_B_CONTROL_CELL = {5, 8};
    static final int SOBOL_POINTS = 1024;
    static final int SALTELLI_BASE = 256;
    static final int SENSITIVITY_INSTANCES = 1000;
    static final double FREEZE_RULE = 0.02;
    static final int PRESCREEN_PER_RUN = 15;
    static final int RACE_SIZE = 30;
    static final double RACE_ALPHA = 0.01;
    static final double CVAR_WEIGHT = 0.5;
    static final double CVAR_LEVEL = 0.95;

    // ------------------------------------------------------------------ configurations

    static final SchedulerConfig ORIGINAL = SchedulerConfig.DEFAULT;

    /** A0: Phase 1 low-cost reference (STATIC_ONLY + reach/max + batched). */
    static final SchedulerConfig A0 = SchedulerConfig.DEFAULT.toBuilder().candidatePoolSize(1)
            .reachNormalizedByMaximum(true).completionMode(SchedulerConfig.CompletionMode.BATCHED).build();

    /** B0: recommended Phase 1 rollout configuration, default weights. */
    static final SchedulerConfig B0 = trackB(5, 8);

    /** Track A search base: no lookahead, batched, reach/max, id ties, pool size K. */
    static SchedulerConfig trackA(int k) {
        return SchedulerConfig.DEFAULT.toBuilder()
                .lookaheadMode(SchedulerConfig.LookaheadMode.NONE)
                .candidatePoolSize(k)
                .completionMode(SchedulerConfig.CompletionMode.BATCHED)
                .reachNormalizedByMaximum(true)
                .deterministicTieBreak(true)
                .build();
    }

    /** Track B search base: contested-only rollout with L candidates and the given depth, K = 5. */
    static SchedulerConfig trackB(int candidates, int depth) {
        return SchedulerConfig.DEFAULT.toBuilder()
                .lookaheadMode(SchedulerConfig.LookaheadMode.ROLLOUT)
                .rolloutDepth(depth)
                .lookaheadCandidates(candidates)
                .candidatePoolSize(5)
                .legacyLoadGate(false)
                .gapCondition(SchedulerConfig.GapCondition.ANY_GAP)
                .contestedDecisionsOnly(true)
                .completionMode(SchedulerConfig.CompletionMode.BATCHED)
                .reachNormalizedByMaximum(true)
                .deterministicTieBreak(true)
                .build();
    }

    // ------------------------------------------------------------------ data

    /** Instances with their reusable static analyses and ORIGINAL makespans. */
    static final class DataSet {
        final String name;
        final List<Instance> instances;
        final StaticGraphAnalysis[] analyses;
        final int[] original;

        DataSet(String name, List<Instance> instances, ForkJoinPool pool) {
            this.name = name;
            this.instances = instances;
            this.analyses = new StaticGraphAnalysis[instances.size()];
            this.original = new int[instances.size()];
            Variant orig = Variant.adaptive("ORIGINAL", "reference", ORIGINAL);
            run(pool, instances.size(), i -> {
                analyses[i] = StaticGraphAnalysis.of(instances.get(i).tasks);
                original[i] = orig.run(instances.get(i), analyses[i]);
            });
        }

        int size() {
            return instances.size();
        }

        int[] all() {
            return IntStream.range(0, size()).toArray();
        }

        int[] where(java.util.function.Predicate<Instance> p) {
            return IntStream.range(0, size()).filter(i -> p.test(instances.get(i))).toArray();
        }
    }

    static List<Instance> trainInstances() {
        List<Instance> list = new ArrayList<>();
        for (SizeBucket b : SML) for (Family f : TRAIN_FAMILIES) for (int i = 0; i < 400; i++) {
            list.add(InstanceGenerator.generate(TRAIN_SEED, i, f, b, TRAIN_DURATIONS));
        }
        return list;
    }

    /** 8 families x S/M/L x 100 with every duration distribution, plus 80 XLARGE. */
    static List<Instance> validationInstances() {
        List<Instance> list = new ArrayList<>(InstanceGenerator.dataset(VALIDATION_SEED, 100, SML));
        for (Family f : Family.values()) for (int i = 0; i < 10; i++) {
            list.add(InstanceGenerator.generate(VALIDATION_SEED, 1000 + i, f, SizeBucket.XLARGE));
        }
        return list;
    }

    static List<Instance> testInstances() {
        List<Instance> list = new ArrayList<>();
        for (SizeBucket b : SML) for (Family f : TRAIN_FAMILIES) for (int i = 0; i < 250; i++) {
            list.add(InstanceGenerator.generate(TEST_SEED, i, f, b, TRAIN_DURATIONS));
        }
        return list;
    }

    /** Held-out families (1,500), BIMODAL durations on every family (1,200), XLARGE (200). */
    static List<Instance> oodInstances() {
        List<Instance> list = new ArrayList<>();
        for (SizeBucket b : SML) for (Family f : HELD_OUT_FAMILIES) for (int i = 0; i < 250; i++) {
            list.add(InstanceGenerator.generate(OOD_SEED, i, f, b, TRAIN_DURATIONS));
        }
        for (SizeBucket b : SML) for (Family f : Family.values()) for (int i = 0; i < 50; i++) {
            list.add(InstanceGenerator.generate(OOD_SEED, 1000 + i, f, b, DurationDistribution.BIMODAL));
        }
        for (Family f : Family.values()) for (int i = 0; i < 25; i++) {
            list.add(InstanceGenerator.generate(OOD_SEED, 2000 + i, f, SizeBucket.XLARGE, TRAIN_DURATIONS));
        }
        return list;
    }

    static List<Instance> exactInstances() {
        return InstanceGenerator.dataset(EXACT_SEED, 100, SizeBucket.TINY);
    }

    static List<Instance> timingInstances() {
        List<Instance> list = new ArrayList<>();
        for (SizeBucket b : new SizeBucket[]{SizeBucket.SMALL, SizeBucket.MEDIUM, SizeBucket.LARGE, SizeBucket.XLARGE}) {
            for (Family f : Family.values()) for (int i = 0; i < 5; i++) list.add(InstanceGenerator.generate(TIMING_SEED, i, f, b));
        }
        return list;
    }

    static boolean inDistribution(Instance x) {
        return Arrays.asList(TRAIN_FAMILIES).contains(x.family) && x.durations != DurationDistribution.BIMODAL
                && x.bucket != SizeBucket.XLARGE;
    }

    /** Order-sensitive checksum of the instances (ids, workers, durations, dependencies). */
    static String checksum(List<Instance> list) {
        long h = 1125899906842597L;
        for (Instance x : list) {
            h = 31 * h + x.id.hashCode();
            h = 31 * h + x.workers;
            for (Task t : x.tasks) {
                h = 31 * h + t.getId();
                h = 31 * h + t.getDuration();
                h = 31 * h + t.getDependsOnIDS().hashCode();
            }
        }
        return Long.toHexString(h);
    }

    /** {@code total} indices drawn without replacement, stratified evenly over family x size cells. */
    static int[] stratified(DataSet data, int[] from, int total, Random r) {
        Map<String, List<Integer>> cells = new TreeMap<>();
        for (int i : from) {
            Instance x = data.instances.get(i);
            cells.computeIfAbsent(x.family + "/" + x.bucket, k -> new ArrayList<>()).add(i);
        }
        List<List<Integer>> groups = new ArrayList<>(cells.values());
        int base = total / groups.size();
        int extra = total - base * groups.size();
        List<Integer> extraCells = new ArrayList<>();
        for (int g = 0; g < groups.size(); g++) extraCells.add(g);
        Collections.shuffle(extraCells, r);
        List<Integer> chosen = new ArrayList<>();
        for (int g = 0; g < groups.size(); g++) {
            List<Integer> cell = new ArrayList<>(groups.get(g));
            Collections.shuffle(cell, r);
            int take = Math.min(cell.size(), base + (extraCells.indexOf(g) < extra ? 1 : 0));
            chosen.addAll(cell.subList(0, take));
        }
        Collections.sort(chosen);
        return chosen.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Training minibatch number {@code index}; shared by every run that uses the same index (common random numbers). */
    static int[] minibatch(DataSet train, int index) {
        return stratified(train, train.all(), MINIBATCH, new Random(MINIBATCH_SEED * 1_000_003L + index));
    }

    // ------------------------------------------------------------------ evaluation

    final ForkJoinPool pool;
    final int threads;
    final Path dir;
    long evaluationsRun;
    long schedulerRuns;

    WeightOptimizer(Path dir, int threads) {
        this.dir = dir;
        this.threads = threads;
        this.pool = new ForkJoinPool(threads);
    }

    static void run(ForkJoinPool pool, int n, java.util.function.IntConsumer body) {
        try {
            pool.submit(() -> IntStream.range(0, n).parallel().forEach(body)).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Makespans [config][j] of each configuration on data.instances[subset[j]]. */
    int[][] makespans(List<SchedulerConfig> configs, DataSet data, int[] subset) {
        int m = subset.length;
        int[][] out = new int[configs.size()][m];
        List<Variant> variants = new ArrayList<>();
        for (SchedulerConfig c : configs) variants.add(Variant.adaptive("candidate", "phase2", c));
        run(pool, configs.size() * m, t -> {
            int c = t / m;
            int j = t % m;
            int i = subset[j];
            try {
                out[c][j] = variants.get(c).run(data.instances.get(i), data.analyses[i]);
            } catch (RuntimeException e) {
                // a failed run counts as infinitely bad and is reported, never skipped silently
                System.err.println("evaluation failed on " + data.instances.get(i).id + ": " + e);
                out[c][j] = Integer.MAX_VALUE / 4;
            }
        });
        evaluationsRun += configs.size();
        schedulerRuns += (long) configs.size() * m;
        return out;
    }

    static double[] logRatios(int[] ms, DataSet data, int[] subset) {
        double[] l = new double[subset.length];
        for (int j = 0; j < subset.length; j++) l[j] = Math.log((double) ms[j] / data.original[subset[j]]);
        return l;
    }

    /** Mean of the worst (largest) (1 - level) fraction of the values. */
    static double cvar(double[] x, double level) {
        double[] s = x.clone();
        Arrays.sort(s);
        int k = Math.max(1, (int) Math.ceil((1 - level) * s.length - 1e-9));
        double sum = 0;
        for (int i = s.length - k; i < s.length; i++) sum += s[i];
        return sum / k;
    }

    /** J = mean log ratio + 0.5 CVaR_0.95(log ratio), ratios against ORIGINAL; lower is better. */
    static double objective(double[] logs) {
        return ExperimentRunner.mean(logs) + CVAR_WEIGHT * cvar(logs, CVAR_LEVEL);
    }

    /** [J, mean log ratio, CVaR] per configuration. */
    double[][] score(List<SchedulerConfig> configs, DataSet data, int[] subset) {
        int[][] ms = makespans(configs, data, subset);
        double[][] r = new double[configs.size()][];
        for (int c = 0; c < configs.size(); c++) {
            double[] l = logRatios(ms[c], data, subset);
            r[c] = new double[]{objective(l), ExperimentRunner.mean(l), cvar(l, CVAR_LEVEL)};
        }
        return r;
    }

    // ------------------------------------------------------------------ evaluation log (checkpoint)

    /** One evaluated point of a search run. */
    record Evaluation(String run, int generation, int member, double[] u, double j, double meanLog, double cvar) {
    }

    static final String EVALUATION_HEADER = "run,generation,member,u,J,meanLog,cvar";
    final Map<String, Evaluation> recorded = new HashMap<>();
    PrintWriter evaluationLog;

    static String key(String run, int generation, int member) {
        return run + "|" + generation + "|" + member;
    }

    static String vector(double[] u) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < u.length; i++) {
            if (i > 0) sb.append(';');
            sb.append(Double.toString(u[i]));
        }
        return sb.toString();
    }

    static double[] parseVector(String s) {
        String[] p = s.split(";");
        double[] u = new double[p.length];
        for (int i = 0; i < p.length; i++) u[i] = Double.parseDouble(p[i]);
        return u;
    }

    void openEvaluationLog() throws IOException {
        Path file = dir.resolve("evaluations.csv");
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.equals(EVALUATION_HEADER)) continue;
                String[] p = line.split(",");
                if (p.length < 7) continue;   // a line cut by an interruption is ignored and recomputed
                Evaluation e = new Evaluation(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), parseVector(p[3]),
                        Double.parseDouble(p[4]), Double.parseDouble(p[5]), Double.parseDouble(p[6]));
                recorded.put(key(e.run, e.generation, e.member), e);
            }
        }
        boolean header = !Files.exists(file);
        evaluationLog = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND));
        if (header) evaluationLog.println(EVALUATION_HEADER);
        evaluationLog.flush();
    }

    /**
     * Fitness of a generation: replayed from the log when every member was
     * recorded with the identical vector, otherwise evaluated and appended.
     */
    List<Evaluation> evaluateGeneration(String run, int generation, double[][] fullVectors, SchedulerConfig base,
                                        DataSet data, int[] subset) {
        List<Evaluation> out = new ArrayList<>();
        boolean replay = true;
        for (int k = 0; k < fullVectors.length; k++) {
            Evaluation e = recorded.get(key(run, generation, k));
            if (e == null || !Arrays.equals(e.u, fullVectors[k])) {
                replay = false;
                break;
            }
            out.add(e);
        }
        if (replay) return out;
        out.clear();
        List<SchedulerConfig> configs = new ArrayList<>();
        for (double[] u : fullVectors) configs.add(ParameterSpace.decode(base, u));
        double[][] s = score(configs, data, subset);
        for (int k = 0; k < fullVectors.length; k++) {
            Evaluation e = new Evaluation(run, generation, k, fullVectors[k].clone(), s[k][0], s[k][1], s[k][2]);
            out.add(e);
            recorded.put(key(run, generation, k), e);
            evaluationLog.printf(Locale.ROOT, "%s,%d,%d,%s,%s,%s,%s%n", run, generation, k, vector(e.u),
                    Double.toString(e.j), Double.toString(e.meanLog), Double.toString(e.cvar));
        }
        evaluationLog.flush();
        return out;
    }

    // ------------------------------------------------------------------ search runs

    /** Outcome of one CMA-ES or random-search run. */
    record RunResult(String run, int generations, int evaluations, String stopReason, double[] finalMean,
                     List<Evaluation> evaluations_) {
    }

    static long runSeed(long base, String run) {
        long h = base;
        for (char ch : run.toCharArray()) h = h * 1_000_003L + ch;
        return h;
    }

    RunResult cmaRun(String run, ParameterSpace space, double[] start, int population, int maxGenerations,
                     DataSet train, int minibatchOffset) {
        CmaEs es = new CmaEs(start, SIGMA0, population, runSeed(CMA_SEED, run));
        List<Evaluation> all = new ArrayList<>();
        String stop = "maxGenerations";
        while (es.generation() < maxGenerations) {
            String reason = es.stopReason(TOL_X);
            if (reason != null) {
                stop = reason;
                break;
            }
            int g = es.generation();
            double[][] pop = es.ask();
            double[][] full = new double[pop.length][];
            for (int k = 0; k < pop.length; k++) full[k] = space.expand(pop[k]);
            List<Evaluation> evals = evaluateGeneration(run, g, full, space.base(), train, minibatch(train, minibatchOffset + g));
            double[] f = new double[pop.length];
            for (int k = 0; k < pop.length; k++) f[k] = evals.get(k).j;
            es.tell(f);
            all.addAll(evals);
            if (g % 10 == 0 || g == maxGenerations - 1) {
                System.out.printf(Locale.ROOT, "  %s gen %d: best J %.5f, median J %.5f, sigma %.4f%n", run, g,
                        Arrays.stream(f).min().orElse(0), ExperimentRunner.median(f), es.sigma());
            }
        }
        writeCheckpoint(run, es, stop);
        return new RunResult(run, es.generation(), es.evaluations(), stop, space.expand(es.mean()), all);
    }

    /** CMA-ES run on a small data set (every "minibatch" is the whole set); used by tests. */
    RunResult cmaRunOn(DataSet data, String run, ParameterSpace space, double[] start, int population, int maxGenerations) {
        return cmaRun(run, space, start, population, maxGenerations, data, 0);
    }

    /** Random search with the same generations, population and minibatches as a CMA-ES run. */
    RunResult randomRun(String run, ParameterSpace space, int population, int generations, DataSet train,
                        int minibatchOffset) {
        Random r = new Random(runSeed(RANDOM_SEARCH_SEED, run));
        List<Evaluation> all = new ArrayList<>();
        for (int g = 0; g < generations; g++) {
            double[][] full = new double[population][];
            for (int k = 0; k < population; k++) {
                double[] x = new double[space.dimension()];
                for (int i = 0; i < x.length; i++) x[i] = r.nextDouble();
                full[k] = space.expand(x);
            }
            all.addAll(evaluateGeneration(run, g, full, space.base(), train, minibatch(train, minibatchOffset + g)));
        }
        return new RunResult(run, generations, generations * population, "budget", null, all);
    }

    void writeCheckpoint(String run, CmaEs es, String stop) {
        try {
            Files.writeString(dir.resolve("checkpoint-" + run + ".txt"), String.format(Locale.ROOT,
                    "run=%s%ngeneration=%d%nevaluations=%d%nsigma=%s%nmean=%s%nbestMinibatchJ=%s%nstop=%s%n"
                            + "resume=rerun the stage; evaluations.csv replays every recorded generation%n",
                    run, es.generation(), es.evaluations(), es.sigma(), vector(es.mean()), es.bestFitness(), stop));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------ candidates, pre-screen, racing

    /** A configuration competing for selection. */
    static final class Candidate {
        final String origin;
        final SchedulerConfig base;
        final double[] u;
        final String label;
        double trainJ = Double.NaN;
        double trainGm = Double.NaN;
        double validationJ = Double.NaN;
        double validationGm = Double.NaN;
        int[] trainMakespans;

        Candidate(String origin, String label, SchedulerConfig base, double[] u) {
            this.origin = origin;
            this.label = label;
            this.base = base;
            this.u = u.clone();
        }

        SchedulerConfig config() {
            return ParameterSpace.decode(base, u);
        }
    }

    /** Best distinct points of a run by minibatch J, plus its final mean. */
    static List<double[]> topPoints(RunResult r, int count) {
        List<Evaluation> sorted = new ArrayList<>(r.evaluations_());
        sorted.sort(Comparator.comparingDouble(Evaluation::j));
        List<double[]> out = new ArrayList<>();
        for (Evaluation e : sorted) {
            if (out.size() >= count) break;
            boolean dup = false;
            for (double[] u : out) if (Arrays.equals(u, e.u)) dup = true;
            if (!dup) out.add(e.u);
        }
        if (r.finalMean() != null) out.add(r.finalMean());
        return out;
    }

    /** Scores every candidate on the full training set and drops candidates with identical training schedules. */
    List<Candidate> prescreen(List<Candidate> pool, DataSet train) {
        List<SchedulerConfig> configs = new ArrayList<>();
        for (Candidate c : pool) configs.add(c.config());
        int[] all = train.all();
        int[][] ms = makespans(configs, train, all);
        List<Candidate> distinct = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int k = 0; k < pool.size(); k++) {
            Candidate c = pool.get(k);
            c.trainMakespans = ms[k];
            double[] l = logRatios(ms[k], train, all);
            c.trainJ = objective(l);
            c.trainGm = Math.exp(ExperimentRunner.mean(l));
            String sig = c.base.getCandidatePoolSize() + "/" + c.base.getLookaheadCandidates() + "/"
                    + c.base.getRolloutDepth() + "/" + Arrays.hashCode(ms[k]);
            if (seen.add(sig)) distinct.add(c);
        }
        distinct.sort(Comparator.comparingDouble(c -> c.trainJ));
        return distinct;
    }

    /** Result of racing: survivors per stage and the winner. */
    static final class RaceResult {
        Candidate winner;
        final StringBuilder log = new StringBuilder();
        final List<Candidate> finalists = new ArrayList<>();
        final List<Candidate> entrants = new ArrayList<>();
        double[] finalJ;
    }

    /**
     * Successive halving on growing prefixes (25%, 50%, 100%) of a fixed random
     * order of the selection subset. At each stage a candidate is eliminated if
     * it is significantly worse than the leader (paired Wilcoxon on log ratios,
     * p < 0.01) and then only the better half is kept; the winner has the lowest
     * J on the whole subset.
     */
    RaceResult race(String name, List<Candidate> entrants, DataSet val, int[] selection) {
        RaceResult rr = new RaceResult();
        rr.entrants.addAll(entrants);
        int[] order = selection.clone();
        Random r = new Random(runSeed(RACE_SEED, name));
        for (int i = order.length - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = order[i];
            order[i] = order[j];
            order[j] = t;
        }
        List<SchedulerConfig> configs = new ArrayList<>();
        for (Candidate c : entrants) configs.add(c.config());
        int[][] ms = makespans(configs, val, order);
        double[][] logs = new double[entrants.size()][];
        for (int k = 0; k < entrants.size(); k++) {
            logs[k] = logRatios(ms[k], val, order);
            entrants.get(k).validationJ = objective(logs[k]);
            entrants.get(k).validationGm = Math.exp(ExperimentRunner.mean(logs[k]));
        }

        List<Integer> alive = new ArrayList<>();
        for (int k = 0; k < entrants.size(); k++) alive.add(k);
        double[] fractions = {0.25, 0.5, 1.0};
        rr.log.append("| stage | instances | entrants | eliminated (Wilcoxon) | kept |\n|---|---|---|---|---|\n");
        double[] lastJ = null;
        for (int s = 0; s < fractions.length; s++) {
            int len = (int) Math.round(order.length * fractions[s]);
            double[] j = new double[entrants.size()];
            for (int k : alive) j[k] = objective(Arrays.copyOf(logs[k], len));
            int leader = alive.get(0);
            for (int k : alive) if (j[k] < j[leader]) leader = k;
            List<Integer> next = new ArrayList<>();
            int eliminated = 0;
            for (int k : alive) {
                if (k == leader) { next.add(k); continue; }
                double[] diff = new double[len];
                for (int q = 0; q < len; q++) diff[q] = logs[k][q] - logs[leader][q];
                if (ExperimentRunner.wilcoxon(diff) < RACE_ALPHA && ExperimentRunner.mean(diff) > 0) eliminated++;
                else next.add(k);
            }
            next.sort(Comparator.comparingDouble(k -> j[k]));
            int entrantsNow = alive.size();
            if (s < fractions.length - 1) next = new ArrayList<>(next.subList(0, Math.max(1, (next.size() + 1) / 2)));
            rr.log.append(String.format(Locale.ROOT, "| %d | %d | %d | %d | %d |%n", s + 1, len, entrantsNow, eliminated, next.size()));
            alive = next;
            lastJ = j;
        }
        rr.finalJ = lastJ;
        for (int k : alive) rr.finalists.add(entrants.get(k));
        rr.winner = entrants.get(alive.get(0));
        return rr;
    }

    // ------------------------------------------------------------------ persistence of selected configurations

    void saveSelection(String name, Candidate c) throws IOException {
        Properties p = new Properties();
        p.setProperty("origin", c.origin);
        p.setProperty("label", c.label);
        p.setProperty("u", vector(c.u));
        p.setProperty("candidatePoolSize", String.valueOf(c.base.getCandidatePoolSize()));
        p.setProperty("lookaheadMode", c.base.getLookaheadMode().name());
        p.setProperty("lookaheadCandidates", String.valueOf(c.base.getLookaheadCandidates()));
        p.setProperty("rolloutDepth", String.valueOf(c.base.getRolloutDepth()));
        p.setProperty("config", c.config().toString());
        try (var w = Files.newBufferedWriter(dir.resolve("selected-" + name + ".properties"))) {
            p.store(w, "Phase 2 selected configuration " + name);
        }
    }

    Candidate loadSelection(String name) throws IOException {
        Properties p = new Properties();
        try (var rd = Files.newBufferedReader(dir.resolve("selected-" + name + ".properties"))) {
            p.load(rd);
        }
        int k = Integer.parseInt(p.getProperty("candidatePoolSize"));
        SchedulerConfig base = SchedulerConfig.LookaheadMode.valueOf(p.getProperty("lookaheadMode")) == SchedulerConfig.LookaheadMode.ROLLOUT
                ? trackB(Integer.parseInt(p.getProperty("lookaheadCandidates")), Integer.parseInt(p.getProperty("rolloutDepth")))
                : trackA(k);
        return new Candidate(p.getProperty("origin"), p.getProperty("label"), base, parseVector(p.getProperty("u")));
    }

    // ------------------------------------------------------------------ stages

    DataSet train;
    DataSet validation;

    DataSet loadTrain() {
        if (train == null) train = timed("load train", () -> new DataSet("train", trainInstances(), pool));
        return train;
    }

    DataSet loadValidation() {
        if (validation == null) validation = timed("load validation", () -> new DataSet("validation", validationInstances(), pool));
        return validation;
    }

    static <T> T timed(String what, java.util.function.Supplier<T> s) {
        long t0 = System.currentTimeMillis();
        T r = s.get();
        System.out.printf(Locale.ROOT, "%s: %.1f s%n", what, (System.currentTimeMillis() - t0) / 1000.0);
        return r;
    }

    void stageTime(String stage, long startMillis) throws IOException {
        Files.writeString(dir.resolve("stage-times.csv"), String.format(Locale.ROOT, "%s,%.1f,%d,%d,%d%n", stage,
                        (System.currentTimeMillis() - startMillis) / 1000.0, evaluationsRun, schedulerRuns, threads),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    void calibrate() {
        DataSet tr = loadTrain();
        int[] mb = minibatch(tr, 0);
        List<SchedulerConfig> cfgs = new ArrayList<>();
        for (int i = 0; i < threads; i++) cfgs.add(trackA(3));
        score(cfgs, tr, mb);   // warm-up
        for (int k : TRACK_A_POOL_SIZES) {
            List<SchedulerConfig> c = new ArrayList<>();
            for (int i = 0; i < POPULATION; i++) c.add(trackA(k));
            long t0 = System.nanoTime();
            score(c, tr, mb);
            System.out.printf(Locale.ROOT, "Track A K=%d: %.2f s per generation of %d on %d instances%n", k,
                    (System.nanoTime() - t0) / 1e9, POPULATION, mb.length);
        }
        for (int[] cell : TRACK_B_CELLS) {
            List<SchedulerConfig> c = new ArrayList<>();
            for (int i = 0; i < POPULATION; i++) c.add(trackB(cell[0], cell[1]));
            long t0 = System.nanoTime();
            score(c, tr, mb);
            System.out.printf(Locale.ROOT, "Track B L=%d d=%d: %.2f s per generation of %d on %d instances%n", cell[0], cell[1],
                    (System.nanoTime() - t0) / 1e9, POPULATION, mb.length);
        }
    }

    void datasets() throws IOException {
        long t0 = System.currentTimeMillis();
        StringBuilder m = new StringBuilder("{\n");
        m.append("  \"phase\": 2,\n  \"baseCommit\": \"be6d109 + uncommitted Phase 1/2 working tree\",\n");
        m.append(String.format(Locale.ROOT, "  \"seeds\": {\"train\": %d, \"validation\": %d, \"test\": %d, \"ood\": %d, \"exact\": %d, "
                        + "\"timing\": %d, \"minibatch\": %d, \"sobol\": %d, \"cma\": %d, \"randomSearch\": %d, \"race\": %d},%n",
                TRAIN_SEED, VALIDATION_SEED, TEST_SEED, OOD_SEED, EXACT_SEED, TIMING_SEED, MINIBATCH_SEED, SOBOL_SEED,
                CMA_SEED, RANDOM_SEARCH_SEED, RACE_SEED));
        m.append("  \"parameterSpace\": \"9 logits u in R, z = -5 + 10u; softmax(static 3), softmax(dynamic 5), logistic(lambda)\",\n");
        m.append(String.format(Locale.ROOT, "  \"objective\": \"mean log(ms/ms_ORIGINAL) + %.1f * CVaR_%.2f\",%n", CVAR_WEIGHT, CVAR_LEVEL));
        m.append(String.format(Locale.ROOT, "  \"budgets\": {\"minibatch\": %d, \"population\": \"%d (IPOP restart: x2)\", \"sigma0\": %.2f, "
                        + "\"trackA\": \"K in %s; generations per IPOP run %s (K=1: %s); random search matched to run 0\", "
                        + "\"trackB\": \"cells %s; %d generations from A* weights; cell %s also from B0 weights + random search matched to run 0\", "
                        + "\"sobol\": %d, \"saltelliBase\": %d, \"race\": %d},%n",
                MINIBATCH, POPULATION, SIGMA0, Arrays.toString(TRACK_A_POOL_SIZES), Arrays.toString(TRACK_A_GENERATIONS),
                Arrays.toString(TRACK_A_GENERATIONS_K1), Arrays.deepToString(TRACK_B_CELLS), TRACK_B_GENERATIONS,
                Arrays.toString(TRACK_B_CONTROL_CELL), SOBOL_POINTS, SALTELLI_BASE, RACE_SIZE));
        m.append("  \"datasets\": {\n");
        Object[][] sets = {
                {"train", trainInstances()}, {"validation", validationInstances()}, {"test", testInstances()},
                {"ood", oodInstances()}, {"exact", exactInstances()}, {"timing", timingInstances()}};
        for (int s = 0; s < sets.length; s++) {
            @SuppressWarnings("unchecked") List<Instance> list = (List<Instance>) sets[s][1];
            m.append(String.format(Locale.ROOT, "    \"%s\": {\"instances\": %d, \"checksum\": \"%s\", \"summary\": \"%s\"}%s%n",
                    sets[s][0], list.size(), checksum(list), ExperimentRunner.datasetSummary(list), s < sets.length - 1 ? "," : ""));
        }
        m.append("  }\n}\n");
        Files.writeString(dir.resolve("manifest.json"), m.toString());
        System.out.println(m);
        // disjointness: no instance id (which embeds the seed) appears in two sets
        Set<String> ids = new HashSet<>();
        for (Object[] s : sets) {
            @SuppressWarnings("unchecked") List<Instance> list = (List<Instance>) s[1];
            for (Instance x : list) if (!ids.add(x.id)) throw new IllegalStateException("instance in two sets: " + x.id);
        }
        // references on validation, to check that Phase 1 conclusions reproduce on the new seeds
        DataSet val = loadValidation();
        List<SchedulerConfig> refs = List.of(A0, B0, trackA(1), trackA(3));
        double[][] s = score(refs, val, val.where(WeightOptimizer::inDistribution));
        String[] names = {"A0", "B0", "trackA(K=1) default weights", "trackA(K=3) default weights"};
        StringBuilder md = new StringBuilder("# Reference configurations on validation (in-distribution part)\n\n| config | J | GM vs ORIGINAL |\n|---|---|---|\n");
        for (int i = 0; i < refs.size(); i++) {
            md.append(String.format(Locale.ROOT, "| %s | %.5f | %.4f |%n", names[i], s[i][0], Math.exp(s[i][1])));
        }
        Files.writeString(dir.resolve("references.md"), md.toString());
        System.out.println(md);
        stageTime("datasets", t0);
    }

    void sobol() throws IOException {
        long t0 = System.currentTimeMillis();
        DataSet tr = loadTrain();
        int[] subset = stratified(tr, tr.all(), SENSITIVITY_INSTANCES, new Random(SOBOL_SEED));
        int d = ParameterSpace.FULL_DIMENSION;
        SchedulerConfig base = trackA(3);

        // (1) 1,024 scrambled Sobol points: given-data first-order effects (16-bin correlation ratio)
        SobolSequence sobol = new SobolSequence(d, SOBOL_SEED);
        double[][] pts = new double[SOBOL_POINTS][];
        List<SchedulerConfig> cfgs = new ArrayList<>();
        for (int k = 0; k < SOBOL_POINTS; k++) {
            pts[k] = sobol.point(k);
            cfgs.add(ParameterSpace.decode(base, pts[k]));
        }
        double[][] s = score(cfgs, tr, subset);
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(dir.resolve("sobol.csv")))) {
            w.println("point,u,J,meanLog,cvar,weights");
            for (int k = 0; k < SOBOL_POINTS; k++) {
                w.printf(Locale.ROOT, "%d,%s,%s,%s,%s,\"%s\"%n", k, vector(pts[k]), s[k][0], s[k][1], s[k][2], ParameterSpace.describe(pts[k]));
            }
        }
        double[] f = new double[SOBOL_POINTS];
        for (int k = 0; k < SOBOL_POINTS; k++) f[k] = s[k][0];
        double var = variance(f);
        double[] firstOrderBinned = new double[d];
        for (int i = 0; i < d; i++) {
            double[] sum = new double[16];
            int[] cnt = new int[16];
            for (int k = 0; k < SOBOL_POINTS; k++) {
                int b = Math.min(15, (int) (pts[k][i] * 16));
                sum[b] += f[k];
                cnt[b]++;
            }
            double mean = ExperimentRunner.mean(f), between = 0;
            for (int b = 0; b < 16; b++) if (cnt[b] > 0) between += cnt[b] * Math.pow(sum[b] / cnt[b] - mean, 2);
            firstOrderBinned[i] = between / SOBOL_POINTS / var;
        }

        // (2) Saltelli design: A = Sobol points 1024..1279, B independent seeded uniform points, AB_i = A with
        // column i from B. (B must not be another block of the same sequence: consecutive blocks of a digital
        // sequence differ by a fixed digital shift, which made B nearly equal to A in coordinate 0.)
        int n = SALTELLI_BASE;
        double[][] a = new double[n][], b = new double[n][];
        Random independent = new Random(SOBOL_SEED * 7 + 1);
        for (int k = 0; k < n; k++) {
            a[k] = sobol.point(SOBOL_POINTS + k);
            b[k] = new double[d];
            for (int i = 0; i < d; i++) b[k][i] = independent.nextDouble();
        }
        List<SchedulerConfig> design = new ArrayList<>();
        for (int k = 0; k < n; k++) design.add(ParameterSpace.decode(base, a[k]));
        for (int k = 0; k < n; k++) design.add(ParameterSpace.decode(base, b[k]));
        for (int i = 0; i < d; i++) {
            for (int k = 0; k < n; k++) {
                double[] ab = a[k].clone();
                ab[i] = b[k][i];
                design.add(ParameterSpace.decode(base, ab));
            }
        }
        double[][] ds = score(design, tr, subset);
        double[] fa = new double[n], fb = new double[n];
        double[][] fab = new double[d][n];
        for (int k = 0; k < n; k++) {
            fa[k] = ds[k][0];
            fb[k] = ds[n + k][0];
        }
        for (int i = 0; i < d; i++) for (int k = 0; k < n; k++) fab[i][k] = ds[2 * n + i * n + k][0];
        double[] st = new double[d], s1 = new double[d], stLo = new double[d], stHi = new double[d];
        for (int i = 0; i < d; i++) {
            double[] sti = saltelli(fa, fb, fab[i]);
            s1[i] = sti[0];
            st[i] = sti[1];
            // bootstrap over design rows
            Random r = new Random(SOBOL_SEED + i);
            double[] boot = new double[500];
            for (int q = 0; q < boot.length; q++) {
                double[] xa = new double[n], xb = new double[n], xab = new double[n];
                for (int k = 0; k < n; k++) {
                    int idx = r.nextInt(n);
                    xa[k] = fa[idx];
                    xb[k] = fb[idx];
                    xab[k] = fab[i][idx];
                }
                boot[q] = saltelli(xa, xb, xab)[1];
            }
            Arrays.sort(boot);
            stLo[i] = boot[12];
            stHi[i] = boot[487];
        }
        double maxSt = Arrays.stream(st).max().orElse(1);
        StringBuilder md = new StringBuilder("# Stage 0: sensitivity of the Track A objective (K = 3)\n\n");
        md.append(String.format(Locale.ROOT, "Objective J on %d stratified training instances. %d scrambled Sobol points; Saltelli design N = %d (%d evaluations). "
                        + "J over the Sobol sample: min %.5f, median %.5f, max %.5f (default weights: see references.md).%n%n",
                subset.length, SOBOL_POINTS, n, n * (d + 2), Arrays.stream(f).min().orElse(0), ExperimentRunner.median(f),
                Arrays.stream(f).max().orElse(0)));
        md.append("| coordinate | first order (binned, Sobol sample) | first order S_i (Saltelli) | total effect S_Ti (Jansen) [95% bootstrap] | S_Ti / max | frozen (< 2% of max) |\n|---|---|---|---|---|---|\n");
        boolean[] free = new boolean[d];
        for (int i = 0; i < d; i++) {
            free[i] = st[i] >= FREEZE_RULE * maxSt;
            md.append(String.format(Locale.ROOT, "| %s | %.3f | %.3f | %.3f [%.3f, %.3f] | %.3f | %s |%n", ParameterSpace.NAMES[i],
                    firstOrderBinned[i], s1[i], st[i], stLo[i], stHi[i], st[i] / maxSt, free[i] ? "no" : "yes"));
        }
        // best Sobol points (exploration)
        Integer[] order = new Integer[SOBOL_POINTS];
        for (int k = 0; k < SOBOL_POINTS; k++) order[k] = k;
        Arrays.sort(order, Comparator.comparingDouble(k -> f[k]));
        md.append("\nBest Sobol points (on the sensitivity subset):\n\n| rank | J | GM vs ORIGINAL | weights |\n|---|---|---|---|\n");
        for (int q = 0; q < 10; q++) {
            int k = order[q];
            md.append(String.format(Locale.ROOT, "| %d | %.5f | %.4f | %s |%n", q + 1, f[k], Math.exp(s[k][1]), ParameterSpace.describe(pts[k])));
        }
        Files.writeString(dir.resolve("sensitivity.md"), md.toString());
        Properties p = new Properties();
        StringBuilder fr = new StringBuilder();
        for (int i = 0; i < d; i++) fr.append(free[i] ? '1' : '0');
        p.setProperty("free", fr.toString());
        try (var w = Files.newBufferedWriter(dir.resolve("frozen.properties"))) {
            p.store(w, "Track A coordinates left free by the Stage 0 rule (1 = free)");
        }
        System.out.println(md);
        stageTime("sobol", t0);
    }

    /** [first-order S_i (Saltelli 2010), total effect S_Ti (Jansen 1999)]. */
    static double[] saltelli(double[] fa, double[] fb, double[] fab) {
        int n = fa.length;
        double[] both = new double[2 * n];
        System.arraycopy(fa, 0, both, 0, n);
        System.arraycopy(fb, 0, both, n, n);
        double var = variance(both);
        if (var <= 0) return new double[]{0, 0};
        double s1 = 0, st = 0;
        for (int k = 0; k < n; k++) {
            s1 += fb[k] * (fab[k] - fa[k]);
            st += (fa[k] - fab[k]) * (fa[k] - fab[k]);
        }
        return new double[]{s1 / n / var, st / (2.0 * n) / var};
    }

    static double variance(double[] x) {
        double m = ExperimentRunner.mean(x), s = 0;
        for (double v : x) s += (v - m) * (v - m);
        return x.length > 1 ? s / (x.length - 1) : 0;
    }

    boolean[] trackAFree(int k) throws IOException {
        boolean[] free = new boolean[ParameterSpace.FULL_DIMENSION];
        if (k == 1) {
            // with a pool of one task only the static ranking is used: dynamic weights and lambda have no effect
            free[0] = free[1] = free[2] = true;
            return free;
        }
        Properties p = new Properties();
        try (var r = Files.newBufferedReader(dir.resolve("frozen.properties"))) {
            p.load(r);
        }
        String s = p.getProperty("free");
        for (int i = 0; i < free.length; i++) free[i] = s.charAt(i) == '1';
        return free;
    }

    List<RunResult> trackASearch() throws IOException {
        long t0 = System.currentTimeMillis();
        DataSet tr = loadTrain();
        openEvaluationLog();
        List<RunResult> results = new ArrayList<>();
        for (int k : TRACK_A_POOL_SIZES) {
            SchedulerConfig base = trackA(k);
            ParameterSpace space = ParameterSpace.full(base).freeze(trackAFree(k), ParameterSpace.encode(SchedulerConfig.DEFAULT));
            int[] generations = k == 1 ? TRACK_A_GENERATIONS_K1 : TRACK_A_GENERATIONS;
            for (int restart = 0; restart < generations.length; restart++) {
                String run = "A-K" + k + "-cma-r" + restart;
                // run 0 starts from the original weights, the IPOP restart from a random point of the box
                double[] start = restart == 0 ? space.restrict(ParameterSpace.encode(SchedulerConfig.DEFAULT))
                        : randomStart(space.dimension(), run);
                int popSize = POPULATION << restart;
                System.out.printf(Locale.ROOT, "%s: dimension %d, population %d%n", run, space.dimension(), popSize);
                RunResult cma = cmaRun(run, space, start, popSize, generations[restart], tr, restart * 1000);
                results.add(cma);
                if (restart == 0) {
                    // equal-budget control: same population, generations and minibatches as CMA-ES run 0
                    results.add(randomRun("A-K" + k + "-rnd-r0", space, popSize, cma.generations(), tr, 0));
                }
                stageTime(run, t0);
            }
        }
        evaluationLog.close();
        writeRunSummary("runs-A.md", results);
        stageTime("trackA", t0);
        return results;
    }

    static double[] randomStart(int dimension, String run) {
        Random r = new Random(runSeed(CMA_SEED + 1, run));
        double[] x = new double[dimension];
        for (int i = 0; i < dimension; i++) x[i] = r.nextDouble();
        return x;
    }

    void writeRunSummary(String file, List<RunResult> results) throws IOException {
        StringBuilder md = new StringBuilder("| run | generations | evaluations | stop | best minibatch J | final mean (weights) |\n|---|---|---|---|---|---|\n");
        for (RunResult r : results) {
            double best = r.evaluations_().stream().mapToDouble(Evaluation::j).min().orElse(Double.NaN);
            md.append(String.format(Locale.ROOT, "| %s | %d | %d | %s | %.5f | %s |%n", r.run(), r.generations(), r.evaluations(), r.stopReason(),
                    best, r.finalMean() == null ? "-" : ParameterSpace.describe(r.finalMean())));
        }
        Files.writeString(dir.resolve(file), md.toString());
    }

    /** Re-runs the search stage from the evaluation log (pure replay when complete). */
    List<RunResult> replayA() throws IOException {
        return trackASearch();
    }

    void raceA() throws IOException {
        long t0 = System.currentTimeMillis();
        List<RunResult> runs = replayA();
        DataSet tr = loadTrain();
        DataSet val = loadValidation();
        List<Candidate> pool = new ArrayList<>();
        for (int k : TRACK_A_POOL_SIZES) {
            pool.add(new Candidate("default-weights", "A K=" + k + " default weights", trackA(k), ParameterSpace.encode(SchedulerConfig.DEFAULT)));
        }
        for (RunResult r : runs) {
            int k = Integer.parseInt(r.run().substring(3, r.run().indexOf('-', 3)));
            int q = 0;
            for (double[] u : topPoints(r, PRESCREEN_PER_RUN)) {
                pool.add(new Candidate(r.run(), r.run() + "#" + q++, trackA(k), u));
            }
        }
        List<Candidate> ranked = prescreen(pool, tr);
        writePrescreen("prescreen-A.md", ranked, tr);
        int[] selection = val.where(WeightOptimizer::inDistribution);

        List<Candidate> entrants = new ArrayList<>(ranked.subList(0, Math.min(RACE_SIZE, ranked.size())));
        RaceResult a = race("A", entrants, val, selection);
        List<Candidate> staticOnly = new ArrayList<>();
        for (Candidate c : ranked) if (c.base.getCandidatePoolSize() == 1 && staticOnly.size() < RACE_SIZE) staticOnly.add(c);
        RaceResult s = race("S", staticOnly, val, selection);

        // CMA-ES versus random search at equal budget: best of each method per K by full-training J
        StringBuilder md = new StringBuilder("# Track A selection\n\n");
        md.append(String.format(Locale.ROOT, "Pool: %d candidates (top %d distinct points per run by minibatch J + final CMA mean + default weights per K); "
                + "%d distinct after pre-screen on the full training set (%d instances).%n%n", pool.size(), PRESCREEN_PER_RUN, ranked.size(), tr.size()));
        md.append("## CMA-ES vs random search (equal evaluations, same minibatches)\n\n");
        md.append(methodComparison(ranked, runs, val, selection));
        md.append("\n## Racing for A* (all K), selection on validation in-distribution part (").append(selection.length).append(" instances)\n\n");
        md.append(a.log).append('\n').append(finalists(a)).append('\n');
        md.append("## Racing for S* (static only, K = 1)\n\n").append(s.log).append('\n').append(finalists(s)).append('\n');
        Files.writeString(dir.resolve("selection-A.md"), md.toString());
        saveSelection("A", a.winner);
        saveSelection("S", s.winner);
        System.out.println(md);
        stageTime("raceA", t0);
    }

    String finalists(RaceResult rr) {
        StringBuilder sb = new StringBuilder("| entrant | origin | K | L | depth | train J | validation J | validation GM vs ORIGINAL | weights |\n|---|---|---|---|---|---|---|---|---|\n");
        for (Candidate c : rr.entrants) {
            String tag = c == rr.winner ? " **(winner)**" : rr.finalists.contains(c) ? " (finalist)" : "";
            sb.append(String.format(Locale.ROOT, "| %s%s | %s | %d | %d | %s | %.5f | %.5f | %.4f | %s |%n", c.label, tag,
                    c.origin, c.base.getCandidatePoolSize(), c.base.getLookaheadCandidates(),
                    ExperimentRunner.depthName(c.base.getRolloutDepth()), c.trainJ, c.validationJ, c.validationGm,
                    ParameterSpace.describe(c.u)));
        }
        return sb.toString();
    }

    String methodComparison(List<Candidate> ranked, List<RunResult> runs, DataSet val, int[] selection) {
        // only run 0 of each method: the random search is matched to CMA-ES run 0 (same evaluations and minibatches)
        StringBuilder sb = new StringBuilder("| group | method | evaluations | best full-train J | its GM vs ORIGINAL (train) | its validation J | its validation GM |\n|---|---|---|---|---|---|---|\n");
        Map<String, Integer> evals = new TreeMap<>();
        for (RunResult r : runs) {
            if (r.run().endsWith("-r0")) evals.put(r.run(), r.evaluations());
        }
        List<Candidate> best = new ArrayList<>();
        List<String> groups = new ArrayList<>(evals.keySet());
        for (String g : groups) {
            Candidate b = null;
            for (Candidate c : ranked) if (c.origin.equals(g) && (b == null || c.trainJ < b.trainJ)) b = c;
            best.add(b);
        }
        List<SchedulerConfig> cfgs = new ArrayList<>();
        for (Candidate c : best) cfgs.add(c == null ? SchedulerConfig.DEFAULT : c.config());
        double[][] vs = score(cfgs, val, selection);
        for (int i = 0; i < groups.size(); i++) {
            Candidate c = best.get(i);
            String g = groups.get(i);
            sb.append(String.format(Locale.ROOT, "| %s | %s | %d | %.5f | %.4f | %.5f | %.4f |%n", g.replaceAll("-(cma|rnd)-r0$", ""),
                    g.contains("-cma-") ? "CMA-ES" : "random search", evals.get(g), c == null ? Double.NaN : c.trainJ,
                    c == null ? Double.NaN : c.trainGm, vs[i][0], Math.exp(vs[i][1])));
        }
        return sb.toString();
    }

    void writePrescreen(String file, List<Candidate> ranked, DataSet tr) throws IOException {
        StringBuilder md = new StringBuilder("| rank | candidate | K | L | depth | train J | train GM vs ORIGINAL | weights |\n|---|---|---|---|---|---|---|---|\n");
        for (int r = 0; r < ranked.size(); r++) {
            Candidate c = ranked.get(r);
            md.append(String.format(Locale.ROOT, "| %d | %s | %d | %d | %s | %.5f | %.4f | %s |%n", r + 1, c.label, c.base.getCandidatePoolSize(),
                    c.base.getLookaheadCandidates(), ExperimentRunner.depthName(c.base.getRolloutDepth()), c.trainJ, c.trainGm,
                    ParameterSpace.describe(c.u)));
        }
        Files.writeString(dir.resolve(file), md.toString());
    }

    List<RunResult> trackBSearch() throws IOException {
        long t0 = System.currentTimeMillis();
        DataSet tr = loadTrain();
        Candidate aStar = loadSelection("A");
        openEvaluationLog();
        List<RunResult> results = new ArrayList<>();
        for (int[] cell : TRACK_B_CELLS) {
            SchedulerConfig base = trackB(cell[0], cell[1]);
            ParameterSpace space = ParameterSpace.full(base);
            boolean control = Arrays.equals(cell, TRACK_B_CONTROL_CELL);
            for (int restart = 0; restart < (control ? 2 : 1); restart++) {
                String run = "B-L" + cell[0] + "-d" + cell[1] + "-cma-r" + restart;
                // run 0 starts from the Track A winner's weights, run 1 from the Phase 1 B0 (original) weights
                double[] start = restart == 0 ? aStar.u.clone() : ParameterSpace.encode(SchedulerConfig.DEFAULT);
                System.out.printf(Locale.ROOT, "%s: dimension %d, population %d%n", run, space.dimension(), POPULATION);
                RunResult cma = cmaRun(run, space, start, POPULATION, TRACK_B_GENERATIONS, tr, 10_000 + restart * 1000);
                results.add(cma);
                if (control && restart == 0) {
                    results.add(randomRun("B-L" + cell[0] + "-d" + cell[1] + "-rnd-r0", space, POPULATION,
                            cma.generations(), tr, 10_000));
                }
                stageTime(run, t0);
            }
        }
        evaluationLog.close();
        writeRunSummary("runs-B.md", results);
        stageTime("trackB", t0);
        return results;
    }

    void raceB() throws IOException {
        long t0 = System.currentTimeMillis();
        List<RunResult> runs = trackBSearch();
        DataSet tr = loadTrain();
        DataSet val = loadValidation();
        Candidate aStar = loadSelection("A");
        List<Candidate> pool = new ArrayList<>();
        for (int[] cell : TRACK_B_CELLS) {
            pool.add(new Candidate("default-weights", "B L=" + cell[0] + " d=" + cell[1] + " default weights",
                    trackB(cell[0], cell[1]), ParameterSpace.encode(SchedulerConfig.DEFAULT)));
            pool.add(new Candidate("A*-weights", "B L=" + cell[0] + " d=" + cell[1] + " A* weights",
                    trackB(cell[0], cell[1]), aStar.u));
        }
        for (RunResult r : runs) {
            String[] p = r.run().split("-");
            int l = Integer.parseInt(p[1].substring(1));
            int d = Integer.parseInt(p[2].substring(1));
            int q = 0;
            for (double[] u : topPoints(r, PRESCREEN_PER_RUN)) pool.add(new Candidate(r.run(), r.run() + "#" + q++, trackB(l, d), u));
        }
        List<Candidate> ranked = prescreen(pool, tr);
        writePrescreen("prescreen-B.md", ranked, tr);
        int[] selection = val.where(WeightOptimizer::inDistribution);
        List<Candidate> entrants = new ArrayList<>(ranked.subList(0, Math.min(RACE_SIZE, ranked.size())));
        RaceResult b = race("B", entrants, val, selection);
        StringBuilder md = new StringBuilder("# Track B selection\n\n");
        md.append(String.format(Locale.ROOT, "Pool: %d candidates; %d distinct after pre-screen on the full training set.%n%n", pool.size(), ranked.size()));
        md.append("## CMA-ES vs random search (cell L=5, depth 8; equal evaluations, same minibatches)\n\n");
        List<RunResult> control = new ArrayList<>();
        for (RunResult r : runs) if (r.run().startsWith("B-L5-d8-")) control.add(r);
        md.append(methodComparison(ranked, control, val, selection));
        md.append("\n## Best candidate per cell (full training set)\n\n| cell | best train J | GM vs ORIGINAL (train) | weights |\n|---|---|---|---|\n");
        for (int[] cell : TRACK_B_CELLS) {
            Candidate best = null;
            for (Candidate c : ranked) {
                if (c.base.getLookaheadCandidates() == cell[0] && c.base.getRolloutDepth() == cell[1]
                        && (best == null || c.trainJ < best.trainJ)) best = c;
            }
            if (best != null) md.append(String.format(Locale.ROOT, "| L=%d d=%d | %.5f | %.4f | %s |%n", cell[0], cell[1], best.trainJ, best.trainGm,
                    ParameterSpace.describe(best.u)));
        }
        md.append("\n## Racing for B*, selection on validation in-distribution part (").append(selection.length).append(" instances)\n\n");
        md.append(b.log).append('\n').append(finalists(b)).append('\n');
        Files.writeString(dir.resolve("selection-B.md"), md.toString());
        saveSelection("B", b.winner);
        System.out.println(md);
        stageTime("raceB", t0);
    }

    // ------------------------------------------------------------------ validation summary, test, timing

    List<Variant> finalVariants() throws IOException {
        List<Variant> v = new ArrayList<>();
        v.add(Variant.adaptive("ORIGINAL", "reference", ORIGINAL));
        v.add(Variant.adaptive("NO_LOOKAHEAD", "reference", ExperimentRunner.NO_LOOKAHEAD));
        v.add(Variant.adaptive("STATIC_ONLY (K=1)", "reference", ExperimentRunner.STATIC_ONLY));
        v.add(Variant.adaptive("A0 (Phase 1 low-cost)", "reference", A0));
        v.add(Variant.adaptive("B0 (Phase 1 rollout)", "reference", B0));
        v.add(Variant.adaptive("S* (best static-only)", "optimized", loadSelection("S").config()));
        v.add(Variant.adaptive("A* FAST_OPTIMIZED", "optimized", loadSelection("A").config()));
        v.add(Variant.adaptive("B* QUALITY_OPTIMIZED", "optimized", loadSelection("B").config()));
        SchedulerConfig.CompletionMode seq = SchedulerConfig.CompletionMode.SEQUENTIAL;
        SchedulerConfig.CompletionMode bat = SchedulerConfig.CompletionMode.BATCHED;
        v.add(Variant.baseline(BenchmarkStrategy.CRITICAL_PATH_FIRST, seq));
        v.add(Variant.baseline(BenchmarkStrategy.CRITICAL_PATH_FIRST, bat));
        v.add(Variant.baseline(BenchmarkStrategy.LARGEST_DOWNSTREAM_WORK, seq));
        v.add(Variant.baseline(BenchmarkStrategy.HIGHEST_STATIC_SCORE, seq));
        v.add(Variant.baseline(BenchmarkStrategy.FIFO, seq));
        v.add(Variant.baseline(BenchmarkStrategy.LONGEST_TASK_FIRST, seq));
        v.add(Variant.baseline(BenchmarkStrategy.SHORTEST_TASK_FIRST, seq));
        return v;
    }

    /** Validation report of the selected configurations (selection part and monitoring-only part). */
    void validationReport() throws IOException {
        long t0 = System.currentTimeMillis();
        List<Variant> v = finalVariants();
        ExperimentRunner.Results res = ExperimentRunner.evaluate(validationInstances(), v, threads);
        StringBuilder md = new StringBuilder("# Validation results of the selected configurations\n\n");
        appendSetReport(md, res, "Validation, in-distribution part (used for selection)", ExperimentRunner.where(res, WeightOptimizer::inDistribution));
        appendSetReport(md, res, "Validation, monitoring-only part (held-out families, BIMODAL, XLARGE; never used for selection)",
                ExperimentRunner.where(res, x -> !inDistribution(x)));
        Files.writeString(dir.resolve("validation.md"), md.toString());
        ExperimentRunner.writeCsv(res, dir.resolve("validation.csv"));
        System.out.println(md);
        stageTime("validation", t0);
    }

    void appendSetReport(StringBuilder md, ExperimentRunner.Results res, String title, int[] subset) {
        if (subset.length == 0) return;
        int orig = res.index("ORIGINAL");
        int b0 = res.index("B0 (Phase 1 rollout)");
        md.append("## ").append(title).append(" — ").append(subset.length).append(" instances\n\n");
        md.append("### vs ORIGINAL\n\n").append(ExperimentRunner.comparisonTable(res, orig, subset, all(res))).append('\n');
        md.append("### vs B0 (Phase 1 rollout)\n\n").append(ExperimentRunner.comparisonTable(res, b0, subset,
                List.of(res.index("A* FAST_OPTIMIZED"), res.index("B* QUALITY_OPTIMIZED"), res.index("S* (best static-only)")))).append('\n');
        md.append("### vs A0 (Phase 1 low-cost)\n\n").append(ExperimentRunner.comparisonTable(res, res.index("A0 (Phase 1 low-cost)"), subset,
                List.of(res.index("A* FAST_OPTIMIZED"), res.index("S* (best static-only)")))).append('\n');
    }

    static List<Integer> all(ExperimentRunner.Results res) {
        List<Integer> l = new ArrayList<>();
        for (int v = 0; v < res.variants.size(); v++) l.add(v);
        return l;
    }

    /** Per-group GM vs a reference, with W/L, for each selected variant. */
    static String groupTable(ExperimentRunner.Results res, int ref, int[] subset, List<Integer> variants,
                             Map<String, java.util.function.Predicate<Instance>> groups) {
        StringBuilder sb = new StringBuilder("| group | n |");
        for (int v : variants) sb.append(' ').append(res.variants.get(v).name()).append(" |");
        sb.append("\n|---|---|");
        for (int i = 0; i < variants.size(); i++) sb.append("---|");
        sb.append('\n');
        for (Map.Entry<String, java.util.function.Predicate<Instance>> g : groups.entrySet()) {
            int[] s = Arrays.stream(subset).filter(i -> g.getValue().test(res.instances.get(i))).toArray();
            if (s.length == 0) continue;
            sb.append("| ").append(g.getKey()).append(" | ").append(s.length).append(" |");
            for (int v : variants) {
                ExperimentRunner.Comparison c = ExperimentRunner.compare(res, v, ref, s);
                sb.append(String.format(Locale.ROOT, " %.4f (%d/%d, worst %.3f) |", c.gmRatio, c.wins, c.losses, c.worstRatio));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static Map<String, java.util.function.Predicate<Instance>> breakdowns() {
        Map<String, java.util.function.Predicate<Instance>> g = new LinkedHashMap<>();
        for (Family f : Family.values()) g.put("family " + f, x -> x.family == f);
        for (SizeBucket b : SizeBucket.values()) g.put("size " + b, x -> x.bucket == b);
        for (DurationDistribution d : DurationDistribution.values()) g.put("durations " + d, x -> x.durations == d);
        g.put("workers 2-3", x -> x.workers <= 3);
        g.put("workers 4-8", x -> x.workers >= 4 && x.workers <= 8);
        g.put("workers 9-20", x -> x.workers >= 9 && x.workers <= 20);
        g.put("workers 21-64", x -> x.workers >= 21);
        for (double p : InstanceGenerator.PRESSURES) g.put("pressure " + p, x -> x.pressure == p);
        return g;
    }

    void test() throws IOException {
        Path marker = dir.resolve("test.done");
        if (Files.exists(marker)) {
            throw new IllegalStateException("the final test was already run (" + marker + "); it is run exactly once");
        }
        long t0 = System.currentTimeMillis();
        // freeze: record the configurations being tested before any test instance is evaluated
        StringBuilder frozen = new StringBuilder();
        for (String s : new String[]{"S", "A", "B"}) frozen.append(s).append(": ").append(loadSelection(s).config()).append('\n');
        // the tested selections must be exactly the shipped presets
        if (!loadSelection("A").config().toString().equals(SchedulerConfig.FAST_OPTIMIZED.toString())
                || !loadSelection("B").config().toString().equals(SchedulerConfig.QUALITY_OPTIMIZED.toString())
                || !B0.toString().equals(SchedulerConfig.PHASE1_ROLLOUT.toString())) {
            throw new IllegalStateException("SchedulerConfig presets differ from the selected configurations");
        }
        Files.writeString(dir.resolve("frozen-configs.txt"), frozen.toString());
        Files.writeString(marker, "started " + new Date() + "\n");

        List<Variant> v = finalVariants();
        StringBuilder md = new StringBuilder("# Final test (run once, configurations frozen in frozen-configs.txt)\n\n");
        List<Integer> sel = List.of(4, 5, 6, 7, 8);   // B0, S*, A*, B*, CP-first/SEQ
        for (String[] set : new String[][]{{"test", "In-distribution test"}, {"ood", "Out-of-distribution test"}}) {
            List<Instance> inst = set[0].equals("test") ? testInstances() : oodInstances();
            ExperimentRunner.Results res = ExperimentRunner.evaluate(inst, v, threads);
            ExperimentRunner.writeCsv(res, dir.resolve(set[0] + ".csv"));
            int orig = res.index("ORIGINAL");
            md.append("# ").append(set[1]).append(" (").append(ExperimentRunner.datasetSummary(inst)).append(")\n\n");
            if (set[0].equals("test")) {
                appendSetReport(md, res, "All", ExperimentRunner.all(res));
            } else {
                appendSetReport(md, res, "Held-out families FFT and GAUSSIAN",
                        ExperimentRunner.where(res, x -> Arrays.asList(HELD_OUT_FAMILIES).contains(x.family) && x.bucket != SizeBucket.XLARGE && x.durations != DurationDistribution.BIMODAL));
                appendSetReport(md, res, "Held-out duration distribution BIMODAL (all families)",
                        ExperimentRunner.where(res, x -> x.durations == DurationDistribution.BIMODAL));
                appendSetReport(md, res, "XLARGE (n 601-1500)", ExperimentRunner.where(res, x -> x.bucket == SizeBucket.XLARGE));
            }
            md.append("## Breakdown vs ORIGINAL (GM, wins/losses, worst ratio)\n\n");
            md.append(groupTable(res, orig, ExperimentRunner.all(res), sel, breakdowns())).append('\n');
            md.append("## Breakdown vs B0\n\n");
            md.append(groupTable(res, res.index("B0 (Phase 1 rollout)"), ExperimentRunner.all(res), List.of(5, 6, 7, 8), breakdowns())).append('\n');
            md.append(worstCases(res, orig, List.of(6, 7))).append('\n');
        }

        // exact set
        List<Instance> exact = exactInstances();
        ExperimentRunner.Results ex = ExperimentRunner.evaluate(exact, v, threads);
        int[] opt = new int[exact.size()];
        int[] list = new int[exact.size()];
        run(pool, exact.size(), i -> {
            int ub = Integer.MAX_VALUE;
            for (int q = 0; q < v.size(); q++) ub = Math.min(ub, ex.makespan[q][i]);
            Instance x = exact.get(i);
            list[i] = ExactSolver.bestNonDelay(x.tasks, x.workers, ub, 200_000_000L);
            opt[i] = ExactSolver.optimal(x.tasks, x.workers, list[i] > 0 ? list[i] : ub, 200_000_000L);
        });
        md.append("# Exact set (seed ").append(EXACT_SEED).append(", ").append(ExperimentRunner.datasetSummary(exact)).append(")\n\n");
        md.append(exactTable(ex, opt, list)).append('\n');

        // curated
        md.append("# Curated scenarios (hold-out)\n\n").append(curatedTable(v)).append('\n');
        Files.writeString(dir.resolve("test.md"), md.toString());
        Files.writeString(marker, "completed " + new Date() + "\n", StandardOpenOption.APPEND);
        System.out.println(md);
        stageTime("test", t0);
    }

    static String worstCases(ExperimentRunner.Results res, int ref, List<Integer> variants) {
        StringBuilder sb = new StringBuilder("## Worst regressions vs ORIGINAL\n\n| variant | instance | family | size | durations | n | workers | ORIGINAL | variant | ratio |\n|---|---|---|---|---|---|---|---|---|---|\n");
        for (int v : variants) {
            Integer[] idx = new Integer[res.instances.size()];
            for (int i = 0; i < idx.length; i++) idx[i] = i;
            Arrays.sort(idx, Comparator.comparingDouble(i -> -(double) res.makespan[v][i] / res.makespan[ref][i]));
            for (int q = 0; q < 5; q++) {
                int i = idx[q];
                Instance x = res.instances.get(i);
                if (res.makespan[v][i] <= res.makespan[ref][i]) break;
                sb.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %d | %d | %d | %d | %.3f |%n", res.variants.get(v).name(), x.id,
                        x.family, x.bucket, x.durations, x.size(), x.workers, res.makespan[ref][i], res.makespan[v][i],
                        (double) res.makespan[v][i] / res.makespan[ref][i]));
            }
        }
        return sb.toString();
    }

    static String exactTable(ExperimentRunner.Results ex, int[] opt, int[] list) {
        StringBuilder md = new StringBuilder();
        int solved = 0;
        for (int i = 0; i < opt.length; i++) if (opt[i] > 0 && list[i] > 0) solved++;
        md.append("Solved exactly: ").append(solved).append(" / ").append(opt.length).append(".\n\n");
        md.append("| variant | GM ms/OPT | % optimal | GM ms/best-list | worst ms/OPT |\n|---|---|---|---|---|\n");
        for (int v = 0; v < ex.variants.size(); v++) {
            double so = 0, sl = 0, worst = 0;
            int hit = 0, k = 0;
            for (int i = 0; i < opt.length; i++) {
                if (opt[i] <= 0 || list[i] <= 0) continue;
                k++;
                int ms = ex.makespan[v][i];
                so += Math.log((double) ms / opt[i]);
                sl += Math.log((double) ms / list[i]);
                worst = Math.max(worst, (double) ms / opt[i]);
                if (ms == opt[i]) hit++;
            }
            md.append(String.format(Locale.ROOT, "| %s | %.4f | %.1f | %.4f | %.3f |%n", ex.variants.get(v).name(), Math.exp(so / k),
                    100.0 * hit / k, Math.exp(sl / k), worst));
        }
        return md.toString();
    }

    static String curatedTable(List<Variant> variants) {
        StringBuilder md = new StringBuilder("| variant |");
        List<ProjectScenario> scenarios = ScenarioLibrary.getScenarios();
        for (ProjectScenario s : scenarios) md.append(" #").append(s.getNumber()).append(" |");
        md.append(" sum |\n|---|");
        for (int i = 0; i <= scenarios.size(); i++) md.append("---|");
        md.append('\n');
        for (Variant v : variants) {
            md.append("| ").append(v.name()).append(" |");
            int sum = 0;
            for (ProjectScenario s : scenarios) {
                int ms = BenchmarkEngine.makespan(s, v.strategy(), v.config(), v.baselineMode());
                sum += ms;
                md.append(' ').append(ms).append(" |");
            }
            md.append(' ').append(sum).append(" |\n");
        }
        return md.toString();
    }

    /** Single-threaded time and allocation per run (5 instances per family x size cell, median of 3 after warm-up). */
    void timing() throws IOException {
        List<Variant> v = finalVariants();
        List<Instance> inst = timingInstances();
        Map<SizeBucket, List<Integer>> byBucket = new EnumMap<>(SizeBucket.class);
        for (int i = 0; i < inst.size(); i++) byBucket.computeIfAbsent(inst.get(i).bucket, k -> new ArrayList<>()).add(i);
        StaticGraphAnalysis[] an = new StaticGraphAnalysis[inst.size()];
        double[] analysisMs = new double[inst.size()];
        for (int i = 0; i < inst.size(); i++) {
            StaticGraphAnalysis.of(inst.get(i).tasks);
            long t0 = System.nanoTime();
            an[i] = StaticGraphAnalysis.of(inst.get(i).tasks);
            analysisMs[i] = (System.nanoTime() - t0) / 1e6;
        }
        for (Variant x : v) for (int i = 0; i < inst.size(); i++) x.run(inst.get(i), an[i]);
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        StringBuilder md = new StringBuilder("# Single-threaded runtime and allocation per run (static analysis reused)\n\n");
        md.append("Mean over instances of the median of 3 repetitions, after a warm-up pass; ").append(inst.size()).append(" instances (seed ")
                .append(TIMING_SEED).append(", 5 per family x size). Allocation = bytes allocated by the scheduling thread per run (median).\n\n");
        md.append("| variant |");
        for (SizeBucket b : byBucket.keySet()) md.append(' ').append(b).append(" ms |");
        for (SizeBucket b : byBucket.keySet()) md.append(' ').append(b).append(" MB alloc |");
        md.append(" peak heap MB (XL pass) |\n|---|");
        for (int i = 0; i < 2 * byBucket.size() + 1; i++) md.append("---|");
        md.append('\n');
        List<java.lang.management.MemoryPoolMXBean> pools = java.lang.management.ManagementFactory.getMemoryPoolMXBeans();
        for (Variant x : v) {
            md.append("| ").append(x.name()).append(" |");
            StringBuilder alloc = new StringBuilder();
            for (List<Integer> list : byBucket.values()) {
                double total = 0, allocTotal = 0;
                for (int i : list) {
                    double[] reps = new double[3];
                    double[] bytes = new double[3];
                    for (int rep = 0; rep < 3; rep++) {
                        long b0 = mx.getThreadAllocatedBytes(tid);
                        long t0 = System.nanoTime();
                        x.run(inst.get(i), an[i]);
                        reps[rep] = (System.nanoTime() - t0) / 1e6;
                        bytes[rep] = mx.getThreadAllocatedBytes(tid) - b0;
                    }
                    total += ExperimentRunner.median(reps);
                    allocTotal += ExperimentRunner.median(bytes);
                }
                md.append(String.format(Locale.ROOT, " %.2f |", total / list.size()));
                alloc.append(String.format(Locale.ROOT, " %.2f |", allocTotal / list.size() / 1e6));
            }
            md.append(alloc);
            System.gc();
            for (var p : pools) if (p.getType() == java.lang.management.MemoryType.HEAP) p.resetPeakUsage();
            long base = 0;
            for (var p : pools) if (p.getType() == java.lang.management.MemoryType.HEAP) base += p.getUsage().getUsed();
            for (int i : byBucket.get(SizeBucket.XLARGE)) x.run(inst.get(i), an[i]);
            long peak = 0;
            for (var p : pools) if (p.getType() == java.lang.management.MemoryType.HEAP) peak += p.getPeakUsage().getUsed();
            md.append(String.format(Locale.ROOT, " %.1f (base %.1f) |%n", peak / 1e6, base / 1e6));
        }
        md.append("| (one-off) StaticGraphAnalysis.of |");
        for (List<Integer> list : byBucket.values()) {
            double t = 0;
            for (int i : list) t += analysisMs[i];
            md.append(String.format(Locale.ROOT, " %.2f |", t / list.size()));
        }
        md.append('\n');
        Files.writeString(dir.resolve("timing.md"), md.toString());
        System.out.println(md);
    }

    /** Re-runs every 10th test instance with the shipped presets and compares against the stored test.csv. */
    void reproduce() throws IOException {
        Map<String, Integer> stored = new HashMap<>();
        List<String> lines = Files.readAllLines(dir.resolve("test.csv"));
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(",");
            stored.put(f[0] + "|" + f[12].replace("\"", ""), Integer.parseInt(f[14]));
        }
        Map<String, SchedulerConfig> presets = new LinkedHashMap<>();
        presets.put("ORIGINAL", SchedulerConfig.DEFAULT);
        presets.put("B0 (Phase 1 rollout)", SchedulerConfig.PHASE1_ROLLOUT);
        presets.put("A* FAST_OPTIMIZED", SchedulerConfig.FAST_OPTIMIZED);
        presets.put("B* QUALITY_OPTIMIZED", SchedulerConfig.QUALITY_OPTIMIZED);
        List<Instance> test = testInstances();
        List<Instance> subset = new ArrayList<>();
        for (int i = 0; i < test.size(); i += 10) subset.add(test.get(i));
        StringBuilder md = new StringBuilder("# Reproducibility check (fresh JVM, presets from SchedulerConfig)\n\n");
        md.append("Every 10th test instance (").append(subset.size()).append("), regenerated from seed; makespans compared with test.csv.\n\n");
        md.append("| preset | compared | identical | different |\n|---|---|---|---|\n");
        boolean allEqual = true;
        for (var e : presets.entrySet()) {
            Variant v = Variant.adaptive(e.getKey(), "preset", e.getValue());
            int[] diff = pool.submit(() -> subset.parallelStream().mapToInt(x -> {
                Integer s = stored.get(x.id + "|" + e.getKey());
                return s != null && s == v.run(x, StaticGraphAnalysis.of(x.tasks)) ? 0 : 1;
            }).toArray()).join();
            int different = Arrays.stream(diff).sum();
            allEqual &= different == 0;
            md.append("| ").append(e.getKey()).append(" | ").append(subset.size()).append(" | ")
                    .append(subset.size() - different).append(" | ").append(different).append(" |\n");
        }
        md.append("\nResult: ").append(allEqual ? "all identical" : "MISMATCH").append('\n');
        Files.writeString(dir.resolve("reproduce.md"), md.toString());
        System.out.println(md);
        if (!allEqual) throw new IllegalStateException("reproducibility check failed");
    }

    // ------------------------------------------------------------------ entry point

    public static void main(String[] args) throws Exception {
        String stage = args.length > 0 ? args[0] : "calibrate";
        Path dir = Path.of(args.length > 1 ? args[1] : "target/phase2/run1");
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : Runtime.getRuntime().availableProcessors();
        Files.createDirectories(dir);
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"));
        WeightOptimizer o = new WeightOptimizer(dir, threads);
        try {
            switch (stage) {
                case "calibrate" -> o.calibrate();
                case "datasets" -> o.datasets();
                case "sobol" -> o.sobol();
                case "trackA" -> o.trackASearch();
                case "raceA" -> o.raceA();
                case "trackB" -> o.trackBSearch();
                case "raceB" -> o.raceB();
                case "validation" -> o.validationReport();
                case "test" -> o.test();
                case "timing" -> o.timing();
                case "reproduce" -> o.reproduce();
                default -> throw new IllegalArgumentException("unknown stage " + stage);
            }
        } finally {
            o.pool.shutdown();
        }
    }
}
