package harness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

/**
 * Paired comparison of two Run CSVs (reference = original scheduler, variant =
 * updated scheduler) on identical instances. Statistics follow the earlier phases:
 * geometric-mean makespan ratio, 95% percentile-bootstrap CI (2,000 resamples),
 * two-sided Wilcoxon signed-rank test, wins/ties/losses, worst and best ratio.
 *
 * Usage: java -cp <harness classes> harness.Compare <reference.csv> <variant.csv> <title> [optima.csv]
 */
public final class Compare {

    record Row(String id, String family, String bucket, String durations, double pressure, int n, int workers,
               int lowerBound, int makespan, String scheduleHash, String staticHash) {}

    public static void main(String[] args) throws Exception {
        Map<String, Row> ref = read(Path.of(args[0]));
        Map<String, Row> var = read(Path.of(args[1]));
        String title = args[2];
        Map<String, int[]> optima = args.length > 3 ? readOptima(Path.of(args[3])) : Map.of();
        if (!ref.keySet().equals(var.keySet())) throw new IllegalStateException("instance sets differ");

        List<String> ids = new ArrayList<>(ref.keySet());
        StringBuilder md = new StringBuilder("## ").append(title).append("\n\n");

        int sameStatic = 0, sameSchedule = 0;
        for (String id : ids) {
            if (ref.get(id).staticHash.equals(var.get(id).staticHash)) sameStatic++;
            if (ref.get(id).scheduleHash.equals(var.get(id).scheduleHash)) sameSchedule++;
        }
        md.append(String.format(Locale.ROOT,
                "Instances: %d. Static scores bit-identical: %d / %d. Schedules identical: %d / %d (%.1f%%).%n%n",
                ids.size(), sameStatic, ids.size(), sameSchedule, ids.size(), 100.0 * sameSchedule / ids.size()));

        md.append("| Subset | n | GM new/original [95% CI] | Mean / median improvement | Wins / ties / losses | Worst / best ratio | Wilcoxon p | GM ms/LB original -> new |\n");
        md.append("|---|---|---|---|---|---|---|---|\n");
        md.append(line("All", ids, ref, var));
        breakdown(md, "Family", ids, ref, var, Row::family);
        breakdown(md, "Size", ids, ref, var, Row::bucket);
        breakdown(md, "Workers", ids, ref, var, r -> r.workers <= 3 ? "2-3" : r.workers <= 8 ? "4-8" : r.workers <= 20 ? "9-20" : "21-64");
        breakdown(md, "Durations", ids, ref, var, Row::durations);
        breakdown(md, "Pressure", ids, ref, var, r -> String.valueOf(r.pressure));

        if (!optima.isEmpty()) {
            md.append("\nExact optima (branch and bound, idle time allowed) and best non-delay schedule:\n\n");
            md.append("| Scheduler | Solved | GM ms/OPT | % optimal | GM ms/best non-delay | Worst ms/OPT |\n|---|---|---|---|---|---|\n");
            md.append(exactLine("Original", ids, ref, optima));
            md.append(exactLine("Original + new lookahead", ids, var, optima));
        }

        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(Comparator.comparingDouble(id -> -(double) var.get(id).makespan / ref.get(id).makespan));
        md.append("\nLargest regressions (new/original):\n\n| Instance | n | workers | original | new | ratio |\n|---|---|---|---|---|---|\n");
        for (int k = 0; k < Math.min(5, sorted.size()); k++) {
            Row a = ref.get(sorted.get(k)), b = var.get(sorted.get(k));
            if (b.makespan <= a.makespan) break;
            md.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %d | %.3f |%n", a.id, a.n, a.workers, a.makespan, b.makespan,
                    (double) b.makespan / a.makespan));
        }
        System.out.println(md);
    }

    static void breakdown(StringBuilder md, String name, List<String> ids, Map<String, Row> ref, Map<String, Row> var, Function<Row, String> key) {
        TreeMap<String, List<String>> groups = new TreeMap<>();
        for (String id : ids) groups.computeIfAbsent(key.apply(ref.get(id)), k -> new ArrayList<>()).add(id);
        if (groups.size() < 2) return;
        groups.forEach((k, sub) -> md.append(line(name + ": " + k, sub, ref, var)));
    }

    static String line(String label, List<String> ids, Map<String, Row> ref, Map<String, Row> var) {
        Map<String, Row> v = var;
        int k = ids.size();
        double[] logs = new double[k], impr = new double[k];
        int wins = 0, ties = 0, losses = 0;
        double worst = 0, best = Double.MAX_VALUE, lbRef = 0, lbVar = 0;
        for (int j = 0; j < k; j++) {
            Row a = ref.get(ids.get(j)), b = v.get(ids.get(j));
            double ratio = (double) b.makespan / a.makespan;
            logs[j] = Math.log(ratio);
            impr[j] = 100.0 * (a.makespan - b.makespan) / a.makespan;
            if (b.makespan < a.makespan) wins++; else if (b.makespan == a.makespan) ties++; else losses++;
            worst = Math.max(worst, ratio);
            best = Math.min(best, ratio);
            lbRef += Math.log((double) a.makespan / a.lowerBound);
            lbVar += Math.log((double) b.makespan / a.lowerBound);
        }
        double[] ci = bootstrapMeanCi(logs, 2000, 12345L);
        return String.format(Locale.ROOT, "| %s | %d | %.4f [%.4f, %.4f] | %+.2f%% / %+.2f%% | %d / %d / %d | %.3f / %.3f | %s | %.4f -> %.4f |%n",
                label, k, Math.exp(mean(logs)), Math.exp(ci[0]), Math.exp(ci[1]), mean(impr), median(impr),
                wins, ties, losses, worst, best, formatP(wilcoxon(logs)), Math.exp(lbRef / k), Math.exp(lbVar / k));
    }

    static String exactLine(String label, List<String> ids, Map<String, Row> rows, Map<String, int[]> optima) {
        int k = 0, hit = 0;
        double so = 0, sl = 0, worst = 0;
        for (String id : ids) {
            int[] o = optima.get(id);
            if (o == null || o[0] <= 0 || o[1] <= 0) continue;
            int ms = rows.get(id).makespan;
            k++;
            so += Math.log((double) ms / o[0]);
            sl += Math.log((double) ms / o[1]);
            worst = Math.max(worst, (double) ms / o[0]);
            if (ms == o[0]) hit++;
        }
        return String.format(Locale.ROOT, "| %s | %d / %d | %.4f | %.1f%% | %.4f | %.3f |%n", label, k, ids.size(),
                Math.exp(so / k), 100.0 * hit / k, Math.exp(sl / k), worst);
    }

    static Map<String, Row> read(Path p) throws Exception {
        Map<String, Row> m = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(p);
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(",");
            m.put(f[0], new Row(f[0], f[1], f[2], f[3], Double.parseDouble(f[4]), Integer.parseInt(f[5]),
                    Integer.parseInt(f[6]), Integer.parseInt(f[7]), Integer.parseInt(f[8]), f[9], f[10]));
        }
        return m;
    }

    static Map<String, int[]> readOptima(Path p) throws Exception {
        Map<String, int[]> m = new HashMap<>();
        List<String> lines = Files.readAllLines(p);
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(",");
            m.put(f[0], new int[]{Integer.parseInt(f[1]), Integer.parseInt(f[2])});
        }
        return m;
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
        double t = 1 / (1 + 0.2316419 * z);
        double poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        return 1 - Math.exp(-z * z / 2) / Math.sqrt(2 * Math.PI) * poly;
    }

    static String formatP(double p) {
        return p < 1e-4 ? "<1e-4" : String.format(Locale.ROOT, "%.4f", p);
    }
}
