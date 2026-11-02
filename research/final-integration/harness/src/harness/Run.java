package harness;

import backend.Controller;
import backend.GraphTaskNode;
import backend.ProjectScenario;
import backend.ScenarioLibrary;
import backend.Task;
import backend.Worker;
import harness.InstanceGenerator.Family;
import harness.InstanceGenerator.Instance;
import harness.InstanceGenerator.SizeBucket;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

/**
 * Runs whichever scheduler build is on the classpath (original or updated) through
 * its public Controller API and writes one CSV row per instance. The two builds are
 * run in separate JVMs; Compare pairs the rows by instance id.
 *
 * Usage: java -cp <scheduler classes>;<harness classes> harness.Run <set> <out.csv> [threads]
 *   sets: main, confirm, xl, exact, phase1-confirm, curated, events, timing
 */
public final class Run {

    /** Fresh seeds, disjoint from every seed used in Phases 1-4. */
    static final long MAIN_SEED = 95001;
    static final long CONFIRM_SEED = 95002;
    static final long XL_SEED = 95003;
    static final long EXACT_SEED = 95004;
    static final long TIMING_SEED = 95005;
    static final long EVENTS_SEED = 95006;
    /** Phase 1 CONFIRM seed: consistency check against the Phase 1 rollout measurement. */
    static final long PHASE1_CONFIRM_SEED = 3003;

    public static void main(String[] args) throws Exception {
        String set = args[0];
        Path out = Path.of(args[1]);
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : Runtime.getRuntime().availableProcessors();
        PrintStream realOut = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));   // Controller prints events
        List<String> rows = switch (set) {
            case "main" -> bench(InstanceGenerator.dataset(MAIN_SEED, 150,
                    SizeBucket.SMALL, SizeBucket.MEDIUM, SizeBucket.LARGE), threads);
            case "confirm" -> bench(InstanceGenerator.dataset(CONFIRM_SEED, 100,
                    SizeBucket.SMALL, SizeBucket.MEDIUM, SizeBucket.LARGE), threads);
            case "xl" -> bench(InstanceGenerator.dataset(XL_SEED, 25, SizeBucket.XLARGE), threads);
            case "exact" -> bench(InstanceGenerator.dataset(EXACT_SEED, 100, SizeBucket.TINY), threads);
            case "phase1-confirm" -> bench(InstanceGenerator.dataset(PHASE1_CONFIRM_SEED, 100,
                    SizeBucket.SMALL, SizeBucket.MEDIUM, SizeBucket.LARGE), threads);
            case "curated" -> curated();
            case "events" -> events(threads);
            case "timing" -> timing();
            case "exact-opt" -> exactOptima(threads);
            default -> throw new IllegalArgumentException("unknown set " + set);
        };
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, rows);
        realOut.println(set + ": " + (rows.size() - 1) + " rows -> " + out);
    }

    // ------------------------------------------------------------ static benchmark

    static final String HEADER = "id,family,bucket,durations,pressure,n,workers,lowerBound,makespan,scheduleHash,staticHash";

    static List<String> bench(List<Instance> instances, int threads) throws Exception {
        String[] rows = new String[instances.size()];
        ForkJoinPool pool = new ForkJoinPool(threads);
        try {
            pool.submit(() -> IntStream.range(0, instances.size()).parallel().forEach(i -> {
                Instance x = instances.get(i);
                Outcome o = runToEnd(x.copyTasks(), x.workerNames());
                rows[i] = String.join(",", x.id, x.family.name(), x.bucket.name(), x.durations.name(),
                        String.valueOf(x.pressure), String.valueOf(x.size()), String.valueOf(x.workers),
                        String.valueOf(x.lowerBound), String.valueOf(o.makespan),
                        Long.toHexString(o.scheduleHash), Long.toHexString(o.staticHash));
            })).get();
        } finally {
            pool.shutdown();
        }
        List<String> list = new ArrayList<>();
        list.add(HEADER);
        list.addAll(Arrays.asList(rows));
        return list;
    }

    static List<String> curated() {
        List<String> list = new ArrayList<>();
        list.add(HEADER);
        for (ProjectScenario s : ScenarioLibrary.getScenarios()) {
            Outcome o = runToEnd(s.getTasks(), s.getWorkers());
            int[] stats = InstanceGenerator.stats(s.getTasks());
            int lb = Math.max(stats[1], (stats[0] + s.getWorkerCount() - 1) / s.getWorkerCount());
            list.add(String.join(",", "CURATED-" + s.getNumber(), "CURATED", "CURATED", "-", "0",
                    String.valueOf(s.getTaskCount()), String.valueOf(s.getWorkerCount()), String.valueOf(lb),
                    String.valueOf(o.makespan), Long.toHexString(o.scheduleHash), Long.toHexString(o.staticHash)));
        }
        return list;
    }

    record Outcome(int makespan, long scheduleHash, long staticHash) {}

    /** Runs the live Controller with on-time completions only, recording (task, worker, start). */
    static Outcome runToEnd(LinkedList<Task> tasks, LinkedList<String> workers) {
        Controller c = new Controller(tasks, workers, false);
        c.initial();
        long staticHash = staticHash(c);
        TreeSet<String> schedule = new TreeSet<>();
        int guard = 0;
        while (true) {
            for (Worker w : c.getActiveWorkers()) {
                schedule.add(w.getDesk().getId() + ":" + w.getId() + ":" + w.getStartTime());
            }
            if (c.getTasksLeft() == 0) break;
            if (c.getActiveWorkers().isEmpty()) throw new IllegalStateException("deadlock");
            if (guard++ > 10_000_000) throw new IllegalStateException("no progress");
            c.workerFinishOnTime();
        }
        if (schedule.size() != tasks.size()) {
            throw new IllegalStateException("expected " + tasks.size() + " task starts, saw " + schedule.size());
        }
        long h = 1125899906842597L;
        for (String s : schedule) h = 31 * h + s.hashCode();
        return new Outcome(c.getGlobalTime(), h, staticHash);
    }

    /** Bit-exact hash of every static score component, in topological order. */
    static long staticHash(Controller c) {
        long h = 7;
        for (GraphTaskNode g : c.getTaskEngine().getTopologicalSort()) {
            h = 31 * h + g.getId();
            h = 31 * h + Double.doubleToLongBits(g.getScore());
            h = 31 * h + g.getCriticalPaths();
            h = 31 * h + g.getReachableDuration();
            h = 31 * h + Double.doubleToLongBits(g.getBlockingPower());
        }
        return h;
    }

    // ------------------------------------------------------------ runtime-event scripts

    /**
     * Random scripts mixing every runtime event of the Controller (finish on time,
     * finish early, late, add/remove worker, add/remove task), including invalid
     * requests. Each step records the outcome (or exception message) and the full
     * observable state, so two builds can be compared step by step.
     */
    static List<String> events(int threads) throws Exception {
        int scripts = 3000;
        String[] rows = new String[scripts];
        Family[] families = Family.values();
        ForkJoinPool pool = new ForkJoinPool(threads);
        try {
            pool.submit(() -> IntStream.range(0, scripts).parallel().forEach(s -> {
                SizeBucket bucket = s % 3 == 0 ? SizeBucket.MEDIUM : SizeBucket.SMALL;
                Instance x = InstanceGenerator.generate(EVENTS_SEED, s, families[s % families.length], bucket);
                rows[s] = "EVENTS-" + s + "," + script(x, new Random(EVENTS_SEED * 31 + s));
            })).get();
        } finally {
            pool.shutdown();
        }
        List<String> list = new ArrayList<>();
        list.add("id,traceHash,steps,unusualExceptions");
        list.addAll(Arrays.asList(rows));
        return list;
    }

    /** Returns "traceHash,steps,unusual"; unusual lists exception types other than plain RuntimeException. */
    static String script(Instance x, Random r) {
        Controller c = new Controller(x.copyTasks(), x.workerNames(), false);
        c.initial();
        StringBuilder trace = new StringBuilder();
        int nextWorkerId = 1000;
        int nextTaskId = 100_000;
        TreeMap<String, Integer> unusual = new TreeMap<>();
        int steps = 0;
        for (int step = 0; step < 400 && c.getTasksLeft() > 0; step++) {
            int op = r.nextInt(100);
            String outcome;
            try {
                if (op < 55) {
                    c.workerFinishOnTime();
                    outcome = "ontime";
                } else if (op < 65) {
                    List<Worker> active = c.getActiveWorkers();
                    Worker w = active.isEmpty() ? null : active.get(r.nextInt(active.size()));
                    int id = w == null ? 0 : w.getId();
                    int t = w == null ? c.getGlobalTime() : c.getGlobalTime()
                            + r.nextInt(Math.max(1, w.getTaskEstimatedEndTime() - c.getGlobalTime() + 2));
                    c.workerFinishEarly(id, t);
                    outcome = "early " + id + "@" + t;
                } else if (op < 72) {
                    List<Worker> active = c.getActiveWorkers();
                    Worker w = active.isEmpty() ? null : active.get(r.nextInt(active.size()));
                    int id = w == null ? 0 : w.getId();
                    int t = (w == null ? c.getGlobalTime() : w.getTaskEstimatedEndTime()) + r.nextInt(6) - 1;
                    c.workerIsLate(id, t);
                    outcome = "late " + id + "@" + t;
                } else if (op < 79) {
                    int id = nextWorkerId++;
                    c.addWorker(id, "X" + id);
                    outcome = "addWorker " + id;
                } else if (op < 85) {
                    List<Worker> all = new ArrayList<>(c.getActiveWorkers());
                    all.addAll(c.getIdleWorkers());
                    int id = all.isEmpty() ? 0 : all.get(r.nextInt(all.size())).getId();
                    c.removeWorker(id);
                    outcome = "removeWorker " + id;
                } else if (op < 93) {
                    List<GraphTaskNode> nodes = new ArrayList<>(c.getAllTasks());
                    LinkedList<Integer> parents = new LinkedList<>();
                    LinkedList<Integer> children = new LinkedList<>();
                    for (int k = r.nextInt(3); k > 0 && !nodes.isEmpty(); k--) {
                        int id = nodes.get(r.nextInt(nodes.size())).getId();
                        if (!parents.contains(id)) parents.add(id);
                    }
                    for (int k = r.nextInt(3); k > 0 && !nodes.isEmpty(); k--) {
                        int id = nodes.get(r.nextInt(nodes.size())).getId();
                        if (!children.contains(id)) children.add(id);
                    }
                    int id = nextTaskId++;
                    c.addTask(new Task("N" + id, id, 1 + r.nextInt(20), parents), children);
                    outcome = "addTask " + id + " " + parents + " " + children;
                } else {
                    List<GraphTaskNode> nodes = new ArrayList<>(c.getAllTasks());
                    int id = nodes.isEmpty() ? 0 : nodes.get(r.nextInt(nodes.size())).getId();
                    c.removeTask(new Task("", id, 1, new LinkedList<>()));
                    outcome = "removeTask " + id;
                }
            } catch (RuntimeException e) {
                outcome = "EX " + e.getClass().getSimpleName() + ": " + e.getMessage();
                if (e.getClass() != RuntimeException.class) unusual.merge(e.getClass().getSimpleName(), 1, Integer::sum);
            }
            steps++;
            trace.append(step).append(' ').append(outcome).append(" | ").append(state(c)).append('\n');
            if (c.getActiveWorkers().isEmpty() && c.getTasksLeft() > 0 && c.getTaskEngine().getReadyPQ().isEmpty()) {
                trace.append("stalled\n");
                break;
            }
            if (c.getActiveWorkers().isEmpty() && c.getTasksLeft() > 0) {
                trace.append("no active workers\n");
                break;
            }
        }
        long hash = trace.toString().hashCode() * 1_000_003L + trace.length();
        StringBuilder u = new StringBuilder();
        unusual.forEach((k, v) -> u.append(u.length() == 0 ? "" : ";").append(k).append('=').append(v));
        return Long.toHexString(hash) + "," + steps + "," + (u.length() == 0 ? "-" : u);
    }

    static String state(Controller c) {
        StringBuilder s = new StringBuilder();
        s.append("t=").append(c.getGlobalTime()).append(" left=").append(c.getTasksLeft())
                .append(" done=").append(c.getTasksCompleted()).append(" A[");
        List<Worker> active = new ArrayList<>(c.getActiveWorkers());
        active.sort(Comparator.comparingInt(Worker::getId));
        for (Worker w : active) {
            s.append(w.getId()).append(':').append(w.getDesk().getId()).append('@')
                    .append(w.getStartTime()).append('-').append(w.getTaskEstimatedEndTime()).append(' ');
        }
        s.append("] I[");
        List<Worker> idle = new ArrayList<>(c.getIdleWorkers());
        idle.sort(Comparator.comparingInt(Worker::getId));
        for (Worker w : idle) s.append(w.getId()).append(' ');
        s.append("] S").append(Long.toHexString(staticHash(c)));
        return s.toString();
    }

    // ------------------------------------------------------------ exact optima

    static List<String> exactOptima(int threads) throws Exception {
        List<Instance> instances = InstanceGenerator.dataset(EXACT_SEED, 100, SizeBucket.TINY);
        String[] rows = new String[instances.size()];
        ForkJoinPool pool = new ForkJoinPool(threads);
        try {
            pool.submit(() -> IntStream.range(0, instances.size()).parallel().forEach(i -> {
                Instance x = instances.get(i);
                int ub = runToEnd(x.copyTasks(), x.workerNames()).makespan;
                int list = ExactSolver.bestNonDelay(x.tasks, x.workers, ub, 200_000_000L);
                int opt = ExactSolver.optimal(x.tasks, x.workers, list > 0 ? list : ub, 200_000_000L);
                rows[i] = x.id + "," + opt + "," + list;
            })).get();
        } finally {
            pool.shutdown();
        }
        List<String> list = new ArrayList<>();
        list.add("id,optimal,bestNonDelay");
        list.addAll(Arrays.asList(rows));
        return list;
    }

    // ------------------------------------------------------------ timing

    /** Single-threaded wall time per run: median of 3 measurements, each repeating the run for >= 50 ms. */
    static List<String> timing() {
        List<Instance> instances = new ArrayList<>();
        for (SizeBucket b : new SizeBucket[]{SizeBucket.SMALL, SizeBucket.MEDIUM, SizeBucket.LARGE, SizeBucket.XLARGE}) {
            for (Family f : Family.values()) {
                for (int i = 0; i < 5; i++) instances.add(InstanceGenerator.generate(TIMING_SEED, i, f, b));
            }
        }
        for (Instance x : instances) runToEnd(x.copyTasks(), x.workerNames());   // warm-up
        List<String> list = new ArrayList<>();
        list.add("id,bucket,n,workers,msPerRun");
        for (Instance x : instances) {
            double[] m = new double[3];
            for (int k = 0; k < 3; k++) {
                long start = System.nanoTime();
                int reps = 0;
                do {
                    runToEnd(x.copyTasks(), x.workerNames());
                    reps++;
                } while (System.nanoTime() - start < 50_000_000L);
                m[k] = (System.nanoTime() - start) / 1e6 / reps;
            }
            Arrays.sort(m);
            list.add(x.id + "," + x.bucket + "," + x.size() + "," + x.workers + "," + String.format(Locale.ROOT, "%.4f", m[1]));
        }
        return list;
    }
}
