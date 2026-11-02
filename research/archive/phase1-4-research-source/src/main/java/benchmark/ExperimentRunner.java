package benchmark;

import backend.*;
import benchmark.InstanceGenerator.Family;
import benchmark.InstanceGenerator.Instance;
import benchmark.InstanceGenerator.SizeBucket;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

/**
 * Batch evaluation of scheduler variants on generated instances, with paired
 * statistics against a reference variant. Every result is deterministic: an
 * instance is defined by (master seed, index, family, size bucket) and each
 * (instance, variant) run is independent of thread scheduling.
 *
 * Usage: ExperimentRunner <experiment> [outputDir] [threads]
 * where experiment is pilot | main | confirm | xl | exact | shuffle | curated | timing | all.
 */
public final class ExperimentRunner {

    public static final long MAIN_SEED = 1001;
    public static final long EXACT_SEED = 2002;
    public static final long CONFIRM_SEED = 3003;
    public static final long XL_SEED = 4004;

    private ExperimentRunner() {
    }

    // ------------------------------------------------------------------ variants

    /** A scheduler variant: the adaptive scheduler with a config, or a greedy baseline. */
    public record Variant(String name, String group, SchedulerConfig config,
                          BenchmarkStrategy strategy, SchedulerConfig.CompletionMode baselineMode) {

        static Variant adaptive(String name, String group, SchedulerConfig config) {
            return new Variant(name, group, config, BenchmarkStrategy.ADAPTIVE, SchedulerConfig.CompletionMode.SEQUENTIAL);
        }

        static Variant baseline(BenchmarkStrategy strategy, SchedulerConfig.CompletionMode mode) {
            return new Variant(strategy.name() + "/" + mode, "baseline", SchedulerConfig.DEFAULT, strategy, mode);
        }

        int run(Instance instance, StaticGraphAnalysis analysis) {
            ProjectScenario scenario = new ProjectScenario(1, instance.id, "", "", instance.tasks, instance.workerNames());
            return BenchmarkEngine.makespan(scenario, strategy, config, baselineMode, analysis);
        }

        /** Completion semantics this result uses (for the report). */
        String semantics() {
            return strategy == BenchmarkStrategy.ADAPTIVE ? config.getCompletionMode().name() : baselineMode.name();
        }
    }

    static final SchedulerConfig ORIGINAL = SchedulerConfig.DEFAULT;
    static final SchedulerConfig NO_LOOKAHEAD = ORIGINAL.toBuilder().lookaheadMode(SchedulerConfig.LookaheadMode.NONE).build();
    static final SchedulerConfig STATIC_ONLY = ORIGINAL.toBuilder().candidatePoolSize(1).build();

    /** Rollout with the original activation gates (legacy load gate and gap > tau). */
    static SchedulerConfig rollout(int depth, int candidates, boolean contested) {
        SchedulerConfig.Builder b = ORIGINAL.toBuilder()
                .lookaheadMode(SchedulerConfig.LookaheadMode.ROLLOUT)
                .rolloutDepth(depth)
                .lookaheadCandidates(candidates)
                .candidatePoolSize(Math.max(3, candidates));
        if (contested) {
            b.legacyLoadGate(false).gapCondition(SchedulerConfig.GapCondition.ANY_GAP).contestedDecisionsOnly(true);
        }
        return b.build();
    }

    static String depthName(int depth) {
        return depth == SchedulerConfig.FULL_ROLLOUT ? "full" : String.valueOf(depth);
    }

    static List<Variant> coreVariants() {
        List<Variant> v = new ArrayList<>();
        v.add(Variant.adaptive("ORIGINAL", "reference", ORIGINAL));
        v.add(Variant.adaptive("NO_LOOKAHEAD", "reference", NO_LOOKAHEAD));
        v.add(Variant.adaptive("STATIC_ONLY(K=1)", "reference", STATIC_ONLY));
        return v;
    }

    static List<Variant> phaseOneVariants() {
        List<Variant> v = new ArrayList<>(coreVariants());
        // E1 lookahead ablation and contribution weight
        for (double mu : new double[]{0.0, 0.25, 0.5}) {
            v.add(Variant.adaptive("E1 mu=" + mu, "E1", ORIGINAL.toBuilder().lookaheadWeight(mu).build()));
        }
        v.add(Variant.adaptive("E1 gap<=tau", "E1", ORIGINAL.toBuilder().gapCondition(SchedulerConfig.GapCondition.GAP_AT_MOST_THRESHOLD).build()));
        v.add(Variant.adaptive("E1 gap any", "E1", ORIGINAL.toBuilder().gapCondition(SchedulerConfig.GapCondition.ANY_GAP).build()));
        v.add(Variant.adaptive("E1 bounded", "E1", ORIGINAL.toBuilder().boundedLookaheadScore(true).build()));
        v.add(Variant.adaptive("E1 bounded mu=0.5", "E1", ORIGINAL.toBuilder().boundedLookaheadScore(true).lookaheadWeight(0.5).build()));
        v.add(Variant.adaptive("E1 legacy contested", "E1", ORIGINAL.toBuilder().legacyLoadGate(false)
                .gapCondition(SchedulerConfig.GapCondition.ANY_GAP).contestedDecisionsOnly(true).build()));
        // E8 idle-worker assignment inside the legacy simulation
        v.add(Variant.adaptive("E8 legacy idle-fix", "E8", ORIGINAL.toBuilder().simulationAssignsIdleWorkers(true).build()));
        v.add(Variant.adaptive("E8 legacy idle-fix contested", "E8", ORIGINAL.toBuilder().simulationAssignsIdleWorkers(true)
                .legacyLoadGate(false).gapCondition(SchedulerConfig.GapCondition.ANY_GAP).contestedDecisionsOnly(true).build()));
        // E2 rollout depth x candidates x activation
        int[] depths = {0, 1, 2, 4, 8, 16, SchedulerConfig.FULL_ROLLOUT};
        for (boolean contested : new boolean[]{false, true}) {
            for (int candidates : new int[]{2, 3, 5}) {
                for (int depth : depths) {
                    v.add(Variant.adaptive("E2 rollout d=" + depthName(depth) + " L=" + candidates
                            + (contested ? " contested" : " legacy-gate"), "E2", rollout(depth, candidates, contested)));
                }
            }
        }
        // E3 candidate pool size, with and without the legacy lookahead
        for (int k : new int[]{1, 2, 5, 10, SchedulerConfig.ALL_READY_TASKS}) {
            String kn = k == SchedulerConfig.ALL_READY_TASKS ? "all" : String.valueOf(k);
            v.add(Variant.adaptive("E3 K=" + kn + " legacy-LA", "E3", ORIGINAL.toBuilder().candidatePoolSize(k).build()));
            v.add(Variant.adaptive("E3 K=" + kn + " no-LA", "E3", NO_LOOKAHEAD.toBuilder().candidatePoolSize(k).build()));
        }
        // E4 completion semantics
        SchedulerConfig.CompletionMode batched = SchedulerConfig.CompletionMode.BATCHED;
        v.add(Variant.adaptive("E4 ORIGINAL batched", "E4", ORIGINAL.toBuilder().completionMode(batched).build()));
        v.add(Variant.adaptive("E4 NO_LOOKAHEAD batched", "E4", NO_LOOKAHEAD.toBuilder().completionMode(batched).build()));
        v.add(Variant.adaptive("E4 STATIC_ONLY batched", "E4", STATIC_ONLY.toBuilder().completionMode(batched).build()));
        // E5 workload pressure
        v.add(Variant.adaptive("E5 ORIGINAL avail-workers", "E5", ORIGINAL.toBuilder().workloadUsesAvailableWorkers(true).build()));
        v.add(Variant.adaptive("E5 NO_LOOKAHEAD avail-workers", "E5", NO_LOOKAHEAD.toBuilder().workloadUsesAvailableWorkers(true).build()));
        // E6 reach normalization
        v.add(Variant.adaptive("E6 ORIGINAL reach/max", "E6", ORIGINAL.toBuilder().reachNormalizedByMaximum(true).build()));
        v.add(Variant.adaptive("E6 NO_LOOKAHEAD reach/max", "E6", NO_LOOKAHEAD.toBuilder().reachNormalizedByMaximum(true).build()));
        v.add(Variant.adaptive("E6 STATIC_ONLY reach/max", "E6", STATIC_ONLY.toBuilder().reachNormalizedByMaximum(true).build()));
        // tie-breaking (also used in E7)
        v.add(Variant.adaptive("E7 ORIGINAL id-ties", "E7", ORIGINAL.toBuilder().deterministicTieBreak(true).build()));
        v.add(Variant.adaptive("E7 STATIC_ONLY id-ties", "E7", STATIC_ONLY.toBuilder().deterministicTieBreak(true).build()));
        // greedy baselines under both completion semantics
        for (BenchmarkStrategy s : BenchmarkStrategy.values()) {
            if (s == BenchmarkStrategy.ADAPTIVE) continue;
            v.add(Variant.baseline(s, SchedulerConfig.CompletionMode.BATCHED));
            v.add(Variant.baseline(s, SchedulerConfig.CompletionMode.SEQUENTIAL));
        }
        return v;
    }

    // ------------------------------------------------------------------ evaluation

    public static final class Results {
        final List<Instance> instances;
        final List<Variant> variants;
        final int[][] makespan;   // [variant][instance]
        final long[][] nanos;     // [variant][instance]

        Results(List<Instance> instances, List<Variant> variants) {
            this.instances = instances;
            this.variants = variants;
            this.makespan = new int[variants.size()][instances.size()];
            this.nanos = new long[variants.size()][instances.size()];
        }

        int index(String name) {
            for (int v = 0; v < variants.size(); v++) if (variants.get(v).name().equals(name)) return v;
            throw new IllegalArgumentException("unknown variant " + name);
        }
    }

    public static Results evaluate(List<Instance> instances, List<Variant> variants, int threads) {
        Results res = new Results(instances, variants);
        ForkJoinPool pool = new ForkJoinPool(threads);
        try {
            pool.submit(() -> IntStream.range(0, instances.size()).parallel().forEach(i -> {
                Instance inst = instances.get(i);
                StaticGraphAnalysis analysis = StaticGraphAnalysis.of(inst.tasks);
                for (int v = 0; v < variants.size(); v++) {
                    long t0 = System.nanoTime();
                    res.makespan[v][i] = variants.get(v).run(inst, analysis);
                    res.nanos[v][i] = System.nanoTime() - t0;
                }
            })).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            pool.shutdown();
        }
        return res;
    }

    // ------------------------------------------------------------------ statistics

    /** Paired comparison of variant v against reference ref over the given instance indices. */
    public static final class Comparison {
        int count;
        double gmRatio;           // geometric mean of ms_v / ms_ref
        double ciLow;
        double ciHigh;
        double meanImprovementPct;
        double medianImprovementPct;
        int wins;
        int ties;
        int losses;
        double worstRatio;
        double pValue;
        double gmToLowerBound;    // geometric mean of ms_v / LB
    }

    static Comparison compare(Results res, int v, int ref, int[] subset) {
        Comparison c = new Comparison();
        int k = subset.length;
        c.count = k;
        double[] logs = new double[k];
        double[] impr = new double[k];
        double sumLb = 0;
        c.worstRatio = 0;
        for (int j = 0; j < k; j++) {
            int i = subset[j];
            int a = res.makespan[v][i];
            int b = res.makespan[ref][i];
            logs[j] = Math.log((double) a / b);
            impr[j] = 100.0 * (b - a) / b;
            if (a < b) c.wins++; else if (a == b) c.ties++; else c.losses++;
            c.worstRatio = Math.max(c.worstRatio, (double) a / b);
            sumLb += Math.log((double) a / res.instances.get(i).lowerBound);
        }
        c.gmRatio = Math.exp(mean(logs));
        c.gmToLowerBound = Math.exp(sumLb / k);
        c.meanImprovementPct = mean(impr);
        c.medianImprovementPct = median(impr);
        double[] ci = bootstrapMeanCi(logs, 2000, 12345L);
        c.ciLow = Math.exp(ci[0]);
        c.ciHigh = Math.exp(ci[1]);
        c.pValue = wilcoxon(logs);
        return c;
    }

    static double mean(double[] x) {
        double s = 0;
        for (double d : x) s += d;
        return x.length == 0 ? 0 : s / x.length;
    }

    static double median(double[] x) {
        double[] s = x.clone();
        Arrays.sort(s);
        int n = s.length;
        if (n == 0) return 0;
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
    }

    /** Percentile bootstrap 95% interval of the mean. */
    static double[] bootstrapMeanCi(double[] x, int resamples, long seed) {
        if (x.length == 0) return new double[]{0, 0};
        Random r = new Random(seed);
        double[] means = new double[resamples];
        for (int b = 0; b < resamples; b++) {
            double s = 0;
            for (int j = 0; j < x.length; j++) s += x[r.nextInt(x.length)];
            means[b] = s / x.length;
        }
        Arrays.sort(means);
        return new double[]{means[(int) (0.025 * resamples)], means[(int) (0.975 * resamples) - 1]};
    }

    /** Two-sided Wilcoxon signed-rank p-value (normal approximation, zeros dropped, tie-corrected). */
    static double wilcoxon(double[] d) {
        List<Double> nz = new ArrayList<>();
        for (double x : d) if (Math.abs(x) > 1e-12) nz.add(x);
        int n = nz.size();
        if (n == 0) return 1.0;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble(i -> Math.abs(nz.get(i))));
        double[] rank = new double[n];
        double tieCorrection = 0;
        for (int i = 0; i < n; ) {
            int j = i;
            while (j + 1 < n && Math.abs(Math.abs(nz.get(order[j + 1])) - Math.abs(nz.get(order[i]))) < 1e-12) j++;
            double avg = (i + j) / 2.0 + 1;
            for (int k = i; k <= j; k++) rank[order[k]] = avg;
            int t = j - i + 1;
            tieCorrection += (double) t * t * t - t;
            i = j + 1;
        }
        double wPlus = 0;
        for (int i = 0; i < n; i++) if (nz.get(i) > 0) wPlus += rank[i];
        double meanW = n * (n + 1) / 4.0;
        double var = n * (n + 1) * (2.0 * n + 1) / 24.0 - tieCorrection / 48.0;
        if (var <= 0) return 1.0;
        double z = (wPlus - meanW) / Math.sqrt(var);
        return 2 * (1 - normalCdf(Math.abs(z)));
    }

    static double normalCdf(double z) {
        // Abramowitz-Stegun 26.2.17
        double t = 1 / (1 + 0.2316419 * z);
        double poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        return 1 - Math.exp(-z * z / 2) / Math.sqrt(2 * Math.PI) * poly;
    }

    static int[] all(Results res) {
        return IntStream.range(0, res.instances.size()).toArray();
    }

    static int[] where(Results res, java.util.function.Predicate<Instance> p) {
        return IntStream.range(0, res.instances.size()).filter(i -> p.test(res.instances.get(i))).toArray();
    }

    // ------------------------------------------------------------------ reporting

    static String comparisonTable(Results res, int ref, int[] subset, List<Integer> variants) {
        StringBuilder sb = new StringBuilder();
        sb.append("| variant | semantics | GM ms/LB | GM vs ref [95% CI] | mean impr % | median impr % | W/T/L | worst ratio | Wilcoxon p | mean ms/run |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (int v : variants) {
            Comparison c = compare(res, v, ref, subset);
            double ms = 0;
            for (int i : subset) ms += res.nanos[v][i] / 1e6;
            ms /= subset.length;
            sb.append(String.format(Locale.ROOT, "| %s | %s | %.4f | %.4f [%.4f, %.4f] | %+.2f | %+.2f | %d/%d/%d | %.3f | %s | %.2f |%n",
                    res.variants.get(v).name(), res.variants.get(v).semantics(), c.gmToLowerBound, c.gmRatio, c.ciLow, c.ciHigh,
                    c.meanImprovementPct, c.medianImprovementPct, c.wins, c.ties, c.losses, c.worstRatio,
                    formatP(c.pValue), ms));
        }
        return sb.toString();
    }

    static String formatP(double p) {
        return p < 1e-4 ? "<1e-4" : String.format(Locale.ROOT, "%.4f", p);
    }

    static String breakdownTable(Results res, int ref, List<Integer> variants) {
        StringBuilder sb = new StringBuilder();
        sb.append("| variant |");
        List<String> cols = new ArrayList<>();
        List<int[]> subsets = new ArrayList<>();
        for (Family f : Family.values()) {
            int[] s = where(res, x -> x.family == f);
            if (s.length > 0) { cols.add(f.name()); subsets.add(s); }
        }
        for (SizeBucket b : SizeBucket.values()) {
            int[] s = where(res, x -> x.bucket == b);
            if (s.length > 0) { cols.add(b.name()); subsets.add(s); }
        }
        for (String c : cols) sb.append(' ').append(c).append(" |");
        sb.append('\n').append("|---|");
        for (int i = 0; i < cols.size(); i++) sb.append("---|");
        sb.append('\n');
        for (int v : variants) {
            sb.append("| ").append(res.variants.get(v).name()).append(" |");
            for (int[] s : subsets) {
                Comparison c = compare(res, v, ref, s);
                sb.append(String.format(Locale.ROOT, " %.4f (%d/%d) |", c.gmRatio, c.wins, c.losses));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static void writeCsv(Results res, Path file) throws IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file))) {
            w.println("instance,family,bucket,durations,pressure,n,edges,depth,workers,total_work,critical_path,lower_bound,variant,semantics,makespan,nanos");
            for (int i = 0; i < res.instances.size(); i++) {
                Instance x = res.instances.get(i);
                for (int v = 0; v < res.variants.size(); v++) {
                    w.printf(Locale.ROOT, "%s,%s,%s,%s,%.2f,%d,%d,%d,%d,%d,%d,%d,\"%s\",%s,%d,%d%n",
                            x.id, x.family, x.bucket, x.durations, x.pressure, x.size(), x.edges, x.depth, x.workers,
                            x.totalWork, x.criticalPath, x.lowerBound, res.variants.get(v).name(),
                            res.variants.get(v).semantics(), res.makespan[v][i], res.nanos[v][i]);
                }
            }
        }
    }

    static List<Integer> indices(Results res, java.util.function.Predicate<Variant> p) {
        List<Integer> l = new ArrayList<>();
        for (int v = 0; v < res.variants.size(); v++) if (p.test(res.variants.get(v))) l.add(v);
        return l;
    }

    // ------------------------------------------------------------------ experiments

    static String datasetSummary(List<Instance> list) {
        int minN = Integer.MAX_VALUE, maxN = 0, minM = Integer.MAX_VALUE, maxM = 0;
        double sumN = 0;
        Map<String, Integer> dist = new TreeMap<>();
        for (Instance x : list) {
            minN = Math.min(minN, x.size());
            maxN = Math.max(maxN, x.size());
            minM = Math.min(minM, x.workers);
            maxM = Math.max(maxM, x.workers);
            sumN += x.size();
            dist.merge(x.durations.name(), 1, Integer::sum);
        }
        return String.format(Locale.ROOT, "%d instances, tasks %d-%d (mean %.1f), workers %d-%d, durations %s",
                list.size(), minN, maxN, sumN / list.size(), minM, maxM, dist);
    }

    static Results runMain(Path out, long seed, int perCell, List<Variant> variants, int threads, String label) throws IOException {
        List<Instance> instances = InstanceGenerator.dataset(seed, perCell, SizeBucket.SMALL, SizeBucket.MEDIUM, SizeBucket.LARGE);
        return runSet(out, instances, variants, threads, label + " (seed " + seed + ", " + perCell + " per cell)", label);
    }

    static Results runSet(Path out, List<Instance> instances, List<Variant> variants, int threads, String label) throws IOException {
        return runSet(out, instances, variants, threads, label, label);
    }

    static Results runSet(Path out, List<Instance> instances, List<Variant> variants, int threads, String title,
                          String label) throws IOException {
        long t0 = System.currentTimeMillis();
        Results res = evaluate(instances, variants, threads);
        long elapsed = System.currentTimeMillis() - t0;
        writeCsv(res, out.resolve(label + ".csv"));
        int ref = res.index("ORIGINAL");
        StringBuilder md = new StringBuilder();
        md.append("# ").append(title).append("\n\n");
        md.append("Dataset: ").append(datasetSummary(instances)).append("\n\n");
        md.append(String.format(Locale.ROOT, "Wall time %.1f s on %d threads; %d variants.%n%n", elapsed / 1000.0, threads, variants.size()));
        md.append("Reference: ORIGINAL (SEQUENTIAL semantics). GM vs ref < 1 means shorter makespans.\n\n");
        List<String> groups = new ArrayList<>();
        for (Variant v : variants) if (!groups.contains(v.group())) groups.add(v.group());
        for (String g : groups) {
            md.append("## ").append(g).append("\n\n");
            md.append(comparisonTable(res, ref, all(res), indices(res, v -> v.group().equals(g)))).append('\n');
        }
        md.append("## Breakdown by family and size (GM vs ORIGINAL, wins/losses)\n\n");
        md.append(breakdownTable(res, ref, indices(res, v -> !v.group().equals("baseline") || v.name().contains("SEQUENTIAL")))).append('\n');
        Files.writeString(out.resolve(label + ".md"), md.toString());
        System.out.println(md);
        return res;
    }

    static void runExact(Path out, int perCell, List<Variant> variants, int threads) throws IOException {
        List<Instance> instances = InstanceGenerator.dataset(EXACT_SEED, perCell, SizeBucket.TINY);
        Results res = evaluate(instances, variants, threads);
        int[] opt = new int[instances.size()];
        int[] list = new int[instances.size()];
        ForkJoinPool pool = new ForkJoinPool(threads);
        try {
            pool.submit(() -> IntStream.range(0, instances.size()).parallel().forEach(i -> {
                int ub = Integer.MAX_VALUE;
                for (int v = 0; v < variants.size(); v++) ub = Math.min(ub, res.makespan[v][i]);
                Instance x = instances.get(i);
                list[i] = ExactSolver.bestNonDelay(x.tasks, x.workers, ub, 200_000_000L);
                opt[i] = ExactSolver.optimal(x.tasks, x.workers, list[i] > 0 ? list[i] : ub, 200_000_000L);
            })).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            pool.shutdown();
        }
        writeCsv(res, out.resolve("exact.csv"));
        StringBuilder md = new StringBuilder("# Exact comparison (small instances)\n\n");
        md.append("Dataset: seed ").append(EXACT_SEED).append(", ").append(datasetSummary(instances)).append("\n\n");
        int solved = 0, gapNonDelay = 0;
        double sumListOpt = 0, sumLbOpt = 0;
        for (int i = 0; i < instances.size(); i++) {
            if (opt[i] > 0 && list[i] > 0) {
                solved++;
                sumListOpt += Math.log((double) list[i] / opt[i]);
                sumLbOpt += Math.log((double) opt[i] / instances.get(i).lowerBound);
                if (list[i] > opt[i]) gapNonDelay++;
            }
        }
        md.append(String.format(Locale.ROOT, "Solved exactly: %d / %d. OPT / LB: GM %.4f. Best non-delay (list) / OPT: GM %.4f; instances where idling is required for optimality: %d.%n%n",
                solved, instances.size(), Math.exp(sumLbOpt / solved), Math.exp(sumListOpt / solved), gapNonDelay));
        md.append("| variant | semantics | GM ms/OPT | % optimal | GM ms/best-list | % best-list | worst ms/OPT |\n|---|---|---|---|---|---|---|\n");
        for (int v = 0; v < variants.size(); v++) {
            double so = 0, sl = 0, worst = 0;
            int hitOpt = 0, hitList = 0, k = 0;
            for (int i = 0; i < instances.size(); i++) {
                if (opt[i] <= 0 || list[i] <= 0) continue;
                k++;
                int ms = res.makespan[v][i];
                so += Math.log((double) ms / opt[i]);
                sl += Math.log((double) ms / list[i]);
                worst = Math.max(worst, (double) ms / opt[i]);
                if (ms == opt[i]) hitOpt++;
                if (ms == list[i]) hitList++;
            }
            md.append(String.format(Locale.ROOT, "| %s | %s | %.4f | %.1f | %.4f | %.1f | %.3f |%n", variants.get(v).name(),
                    variants.get(v).semantics(), Math.exp(so / k), 100.0 * hitOpt / k, Math.exp(sl / k), 100.0 * hitList / k, worst));
        }
        Files.writeString(out.resolve("exact.md"), md.toString());
        System.out.println(md);
    }

    /** E7: makespan sensitivity to the order of the input task list. */
    static void runShuffle(Path out, int perCell, int shuffles, List<Variant> variants, int threads) throws IOException {
        List<Instance> instances = InstanceGenerator.dataset(MAIN_SEED, perCell, SizeBucket.SMALL, SizeBucket.MEDIUM, SizeBucket.LARGE);
        int[][][] ms = new int[variants.size()][instances.size()][shuffles + 1];
        ForkJoinPool pool = new ForkJoinPool(threads);
        try {
            pool.submit(() -> IntStream.range(0, instances.size()).parallel().forEach(i -> {
                Instance x = instances.get(i);
                Random r = new Random(77L * i + 1);
                for (int s = 0; s <= shuffles; s++) {
                    LinkedList<Task> tasks = x.copyTasks();
                    if (s > 0) Collections.shuffle(tasks, r);
                    ProjectScenario sc = new ProjectScenario(1, x.id, "", "", tasks, x.workerNames());
                    for (int v = 0; v < variants.size(); v++) {
                        Variant var = variants.get(v);
                        ms[v][i][s] = BenchmarkEngine.makespan(sc, var.strategy(), var.config(), var.baselineMode(), null);
                    }
                }
            })).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            pool.shutdown();
        }
        StringBuilder md = new StringBuilder("# E7 Input-order sensitivity\n\n");
        md.append(String.format(Locale.ROOT, "%d instances (seed %d, %d per cell), original order + %d shuffles each.%n%n",
                instances.size(), MAIN_SEED, perCell, shuffles));
        md.append("| variant | % instances whose makespan depends on order | mean (max-min)/min % | max (max-min)/min % | mean CV % |\n|---|---|---|---|---|\n");
        for (int v = 0; v < variants.size(); v++) {
            int varying = 0;
            double sumRange = 0, maxRange = 0, sumCv = 0;
            for (int i = 0; i < instances.size(); i++) {
                int[] a = ms[v][i];
                int mn = Integer.MAX_VALUE, mx = 0;
                double s = 0, s2 = 0;
                for (int x : a) { mn = Math.min(mn, x); mx = Math.max(mx, x); s += x; s2 += (double) x * x; }
                if (mx != mn) varying++;
                double range = 100.0 * (mx - mn) / mn;
                sumRange += range;
                maxRange = Math.max(maxRange, range);
                double mean = s / a.length;
                sumCv += 100.0 * Math.sqrt(Math.max(0, s2 / a.length - mean * mean)) / mean;
            }
            int n = instances.size();
            md.append(String.format(Locale.ROOT, "| %s | %.1f | %.3f | %.2f | %.3f |%n", variants.get(v).name(),
                    100.0 * varying / n, sumRange / n, maxRange, sumCv / n));
        }
        Files.writeString(out.resolve("shuffle.md"), md.toString());
        System.out.println(md);
    }

    static void runCurated(Path out, List<Variant> variants) throws IOException {
        StringBuilder md = new StringBuilder("# Curated scenarios (hold-out, never used for selection)\n\n| variant |");
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
        Files.writeString(out.resolve("curated.md"), md.toString());
        System.out.println(md);
    }

    /**
     * Single-threaded runtime per variant on a stratified sample (5 per family x size
     * cell, incl. XLARGE). Every variant is warmed up on every instance first; the
     * reported value is the mean over instances of the median of 3 repetitions.
     */
    static void runTiming(Path out, List<Variant> variants) throws IOException {
        List<Instance> instances = InstanceGenerator.dataset(XL_SEED, 5, SizeBucket.SMALL, SizeBucket.MEDIUM, SizeBucket.LARGE, SizeBucket.XLARGE);
        Map<SizeBucket, List<Instance>> byBucket = new EnumMap<>(SizeBucket.class);
        for (Instance x : instances) byBucket.computeIfAbsent(x.bucket, k -> new ArrayList<>()).add(x);
        Map<Instance, StaticGraphAnalysis> analyses = new HashMap<>();
        for (Instance x : instances) analyses.put(x, StaticGraphAnalysis.of(x.tasks));
        for (Variant v : variants) for (Instance x : instances) v.run(x, analyses.get(x));
        StringBuilder md = new StringBuilder("# Single-threaded runtime per run (ms, static analysis reused; mean of per-instance medians of 3)\n\n| variant |");
        for (SizeBucket b : byBucket.keySet()) md.append(' ').append(b).append(" |");
        md.append("\n|---|");
        for (int i = 0; i < byBucket.size(); i++) md.append("---|");
        md.append('\n');
        for (Variant v : variants) {
            md.append("| ").append(v.name()).append(" |");
            for (List<Instance> list : byBucket.values()) {
                double total = 0;
                for (Instance x : list) {
                    double[] reps = new double[3];
                    for (int rep = 0; rep < 3; rep++) {
                        long t0 = System.nanoTime();
                        v.run(x, analyses.get(x));
                        reps[rep] = (System.nanoTime() - t0) / 1e6;
                    }
                    total += median(reps);
                }
                md.append(String.format(Locale.ROOT, " %.2f |", total / list.size()));
            }
            md.append('\n');
        }
        md.append("| (one-off) StaticGraphAnalysis.of |");
        for (List<Instance> list : byBucket.values()) {
            double total = 0;
            for (Instance x : list) {
                long t0 = System.nanoTime();
                StaticGraphAnalysis.of(x.tasks);
                total += (System.nanoTime() - t0) / 1e6;
            }
            md.append(String.format(Locale.ROOT, " %.2f |", total / list.size()));
        }
        md.append('\n');
        Files.writeString(out.resolve("timing.md"), md.toString());
        System.out.println(md);
    }

    // ------------------------------------------------------------------ entry point

    public static void main(String[] args) throws Exception {
        String experiment = args.length > 0 ? args[0] : "pilot";
        Path out = Path.of(args.length > 1 ? args[1] : "target/experiments");
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : Runtime.getRuntime().availableProcessors();
        Files.createDirectories(out);
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"));

        switch (experiment) {
            case "pilot" -> {
                // calibration: 2 instances per cell, all variants, including XLARGE
                List<Instance> instances = InstanceGenerator.dataset(XL_SEED, 2, SizeBucket.SMALL, SizeBucket.MEDIUM,
                        SizeBucket.LARGE, SizeBucket.XLARGE);
                List<Variant> variants = phaseOneVariants();
                long t0 = System.currentTimeMillis();
                Results res = evaluate(instances, variants, threads);
                System.out.printf(Locale.ROOT, "pilot: %s%nwall %.1f s%n", datasetSummary(instances), (System.currentTimeMillis() - t0) / 1000.0);
                for (int v = 0; v < variants.size(); v++) {
                    double[] perBucket = new double[SizeBucket.values().length];
                    int[] count = new int[SizeBucket.values().length];
                    for (int i = 0; i < instances.size(); i++) {
                        perBucket[instances.get(i).bucket.ordinal()] += res.nanos[v][i] / 1e6;
                        count[instances.get(i).bucket.ordinal()]++;
                    }
                    StringBuilder line = new StringBuilder(String.format("%-45s", variants.get(v).name()));
                    for (SizeBucket b : SizeBucket.values()) {
                        if (count[b.ordinal()] > 0) line.append(String.format(Locale.ROOT, " %s=%.1fms", b, perBucket[b.ordinal()] / count[b.ordinal()]));
                    }
                    System.out.println(line);
                }
            }
            case "main" -> runMain(out, MAIN_SEED, 150, phaseOneVariants(), threads, "main");
            case "confirm" -> runMain(out, CONFIRM_SEED, 100, confirmVariants(), threads, "confirm");
            case "xl" -> runSet(out, InstanceGenerator.dataset(XL_SEED, 25, SizeBucket.XLARGE), confirmVariants(), threads, "xl");
            case "exact" -> runExact(out, 100, phaseOneVariants(), threads);
            case "shuffle" -> runShuffle(out, 50, 10, shuffleVariants(), threads);
            case "curated" -> runCurated(out, phaseOneVariants());
            case "timing" -> runTiming(out, timingVariants());
            default -> throw new IllegalArgumentException("unknown experiment " + experiment);
        }
    }

    /** Variants re-tested on the independent confirmation seed (chosen from the main results). */
    static List<Variant> confirmVariants() {
        List<Variant> v = new ArrayList<>(coreVariants());
        SchedulerConfig.CompletionMode batched = SchedulerConfig.CompletionMode.BATCHED;
        for (int candidates : new int[]{3, 5}) {
            for (int depth : new int[]{4, 8, 16, SchedulerConfig.FULL_ROLLOUT}) {
                v.add(Variant.adaptive("rollout d=" + depthName(depth) + " L=" + candidates + " contested", "rollout",
                        rollout(depth, candidates, true)));
            }
        }
        SchedulerConfig best = rollout(8, 5, true);
        v.add(Variant.adaptive("rollout d=8 L=5 contested +batched", "combination", best.toBuilder().completionMode(batched).build()));
        v.add(Variant.adaptive("rollout d=8 L=5 contested +reach/max", "combination", best.toBuilder().reachNormalizedByMaximum(true).build()));
        v.add(Variant.adaptive("rollout d=8 L=5 contested +id-ties", "combination", best.toBuilder().deterministicTieBreak(true).build()));
        v.add(Variant.adaptive("rollout d=8 L=5 contested +batched+reach/max+id-ties", "combination",
                best.toBuilder().completionMode(batched).reachNormalizedByMaximum(true).deterministicTieBreak(true).build()));
        v.add(Variant.adaptive("STATIC_ONLY +reach/max+batched", "combination",
                STATIC_ONLY.toBuilder().reachNormalizedByMaximum(true).completionMode(batched).build()));
        v.add(Variant.adaptive("NO_LOOKAHEAD +reach/max+batched", "combination",
                NO_LOOKAHEAD.toBuilder().reachNormalizedByMaximum(true).completionMode(batched).build()));
        // single exploratory weight probe (not training): critical-path-only static score
        v.add(Variant.adaptive("PROBE rollout d=8 L=5 contested, static=CP only", "probe",
                best.toBuilder().staticWeights(1, 0, 0).build()));
        v.add(Variant.adaptive("PROBE STATIC_ONLY, static=CP only", "probe",
                STATIC_ONLY.toBuilder().staticWeights(1, 0, 0).build()));
        v.add(Variant.baseline(BenchmarkStrategy.CRITICAL_PATH_FIRST, SchedulerConfig.CompletionMode.SEQUENTIAL));
        v.add(Variant.baseline(BenchmarkStrategy.LARGEST_DOWNSTREAM_WORK, SchedulerConfig.CompletionMode.SEQUENTIAL));
        v.add(Variant.baseline(BenchmarkStrategy.HIGHEST_STATIC_SCORE, SchedulerConfig.CompletionMode.SEQUENTIAL));
        return v;
    }

    static List<Variant> shuffleVariants() {
        List<Variant> v = new ArrayList<>(coreVariants());
        v.add(Variant.adaptive("ORIGINAL id-ties", "E7", ORIGINAL.toBuilder().deterministicTieBreak(true).build()));
        v.add(Variant.adaptive("NO_LOOKAHEAD id-ties", "E7", NO_LOOKAHEAD.toBuilder().deterministicTieBreak(true).build()));
        v.add(Variant.adaptive("STATIC_ONLY id-ties", "E7", STATIC_ONLY.toBuilder().deterministicTieBreak(true).build()));
        v.add(Variant.adaptive("rollout d=full L=3 contested", "E7", rollout(SchedulerConfig.FULL_ROLLOUT, 3, true)));
        v.add(Variant.adaptive("rollout d=full L=3 contested id-ties", "E7",
                rollout(SchedulerConfig.FULL_ROLLOUT, 3, true).toBuilder().deterministicTieBreak(true).build()));
        v.add(Variant.baseline(BenchmarkStrategy.CRITICAL_PATH_FIRST, SchedulerConfig.CompletionMode.SEQUENTIAL));
        return v;
    }

    static List<Variant> timingVariants() {
        List<Variant> v = new ArrayList<>(coreVariants());
        for (int depth : new int[]{0, 2, 8, SchedulerConfig.FULL_ROLLOUT}) {
            v.add(Variant.adaptive("rollout d=" + depthName(depth) + " L=3 contested", "E2", rollout(depth, 3, true)));
            v.add(Variant.adaptive("rollout d=" + depthName(depth) + " L=3 legacy-gate", "E2", rollout(depth, 3, false)));
        }
        v.add(Variant.adaptive("rollout d=8 L=5 contested", "E2", rollout(8, 5, true)));
        v.add(Variant.adaptive("rollout d=16 L=5 contested", "E2", rollout(16, 5, true)));
        v.add(Variant.adaptive("rollout d=full L=5 contested", "E2", rollout(SchedulerConfig.FULL_ROLLOUT, 5, true)));
        v.add(Variant.baseline(BenchmarkStrategy.CRITICAL_PATH_FIRST, SchedulerConfig.CompletionMode.SEQUENTIAL));
        return v;
    }
}
