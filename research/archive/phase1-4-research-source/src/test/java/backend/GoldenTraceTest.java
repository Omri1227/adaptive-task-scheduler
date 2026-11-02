package backend;

import org.junit.Test;

import java.util.*;

import static org.junit.Assert.assertEquals;

/**
 * Golden-trace equivalence test.
 *
 * The expected values were recorded from the original, unmodified scheduler
 * (commit be6d109) before any Phase 1 change was made. Each entry is
 * "makespan:hash", where the hash covers every static score component and the
 * complete schedule (task, worker, start time) plus the outcome of every runtime
 * event. Any change to the default scheduling behaviour breaks this test.
 *
 * To regenerate (only ever from the reference commit), run main().
 */
public class GoldenTraceTest {

    static final int INSTANCES = 360;
    static final int EVENT_INSTANCES = 60;

    // ------------------------------------------------------------------ instances

    static LinkedList<Task> instance(int index) {
        Random r = new Random(1_000_003L * index + 17);
        int family = index % 6;
        int n;
        List<List<Integer>> deps = new ArrayList<>();

        switch (family) {
            case 0 -> { // random order-based DAG
                n = 3 + r.nextInt(38);
                double p = new double[]{0.05, 0.15, 0.3, 0.5}[r.nextInt(4)];
                for (int j = 0; j < n; j++) {
                    List<Integer> d = new ArrayList<>();
                    for (int i = 0; i < j; i++) if (r.nextDouble() < p) d.add(i);
                    deps.add(d);
                }
            }
            case 1 -> { // layered
                int layers = 2 + r.nextInt(6);
                List<List<Integer>> ly = new ArrayList<>();
                int id = 0;
                for (int l = 0; l < layers; l++) {
                    int w = 1 + r.nextInt(7);
                    List<Integer> layer = new ArrayList<>();
                    for (int k = 0; k < w; k++) layer.add(id++);
                    ly.add(layer);
                }
                n = id;
                for (int i = 0; i < n; i++) deps.add(new ArrayList<>());
                for (int l = 1; l < layers; l++) {
                    for (int x : ly.get(l)) {
                        List<Integer> prev = new ArrayList<>(ly.get(l - 1));
                        Collections.shuffle(prev, r);
                        int k = 1 + r.nextInt(Math.min(3, prev.size()));
                        deps.get(x).addAll(prev.subList(0, k));
                    }
                }
            }
            case 2 -> { // fork-join chain of stages
                n = 0;
                int stages = 1 + r.nextInt(5);
                int prevJoin = -1;
                for (int s = 0; s < stages; s++) {
                    int fork = n++;
                    deps.add(prevJoin < 0 ? new ArrayList<>() : new ArrayList<>(List.of(prevJoin)));
                    int width = 1 + r.nextInt(6);
                    List<Integer> mids = new ArrayList<>();
                    for (int k = 0; k < width; k++) {
                        mids.add(n++);
                        deps.add(new ArrayList<>(List.of(fork)));
                    }
                    prevJoin = n++;
                    deps.add(new ArrayList<>(mids));
                }
            }
            case 3 -> { // independent chains
                int chains = 1 + r.nextInt(6);
                n = 0;
                for (int c = 0; c < chains; c++) {
                    int len = 1 + r.nextInt(6);
                    for (int k = 0; k < len; k++) {
                        deps.add(k == 0 ? new ArrayList<>() : new ArrayList<>(List.of(n - 1)));
                        n++;
                    }
                }
            }
            case 4 -> { // out-tree or in-tree
                n = 2 + r.nextInt(25);
                boolean in = r.nextBoolean();
                for (int j = 0; j < n; j++) deps.add(new ArrayList<>());
                for (int j = 1; j < n; j++) {
                    int parent = r.nextInt(j);
                    if (in) deps.get(parent).add(j); else deps.get(j).add(parent);
                }
                if (in) { // reverse ids so dependencies point backwards in the list
                    List<List<Integer>> rev = new ArrayList<>();
                    for (int j = n - 1; j >= 0; j--) {
                        List<Integer> d = new ArrayList<>();
                        for (int x : deps.get(j)) d.add(n - 1 - x);
                        rev.add(d);
                    }
                    deps = rev;
                }
            }
            default -> { // independent tasks
                n = 1 + r.nextInt(20);
                for (int j = 0; j < n; j++) deps.add(new ArrayList<>());
            }
        }

        int durationKind = r.nextInt(4);
        LinkedList<Task> tasks = new LinkedList<>();
        for (int j = 0; j < n; j++) {
            int d = switch (durationKind) {
                case 0 -> 1 + r.nextInt(10);
                case 1 -> 1;
                case 2 -> 1 + r.nextInt(100);
                default -> r.nextBoolean() ? 1 + r.nextInt(3) : 15 + r.nextInt(10);
            };
            LinkedList<Integer> dependsOn = new LinkedList<>();
            for (int x : deps.get(j)) dependsOn.add(x + 1);
            tasks.add(new Task("T" + (j + 1), j + 1, d, dependsOn));
        }
        // shuffle list order for some instances (ids keep the dependency structure)
        if (index % 4 == 3) Collections.shuffle(tasks, r);
        return tasks;
    }

    static int workers(int index) {
        return 1 + new Random(7L * index + 3).nextInt(8);
    }

    static LinkedList<String> names(int m) {
        LinkedList<String> l = new LinkedList<>();
        for (int i = 0; i < m; i++) l.add("W" + i);
        return l;
    }

    // ---------------------------------------------------------------- trace

    static final class Tracer {
        final StringBuilder sb = new StringBuilder();
        final Set<Integer> seen = new HashSet<>();

        void record(Controller c) {
            List<Worker> active = new ArrayList<>(c.getActiveWorkers());
            active.sort(Comparator.comparingInt(Worker::getId));
            for (Worker w : active) {
                if (w.getDesk() != null && seen.add(w.getDesk().getId())) {
                    sb.append('a').append(w.getDesk().getId()).append(',')
                            .append(w.getId()).append(',').append(w.getStartTime()).append(';');
                }
            }
        }

        void statics(Controller c) {
            List<GraphTaskNode> nodes = new ArrayList<>(c.getAllTasks());
            nodes.sort(Comparator.comparingInt(GraphTaskNode::getId));
            for (GraphTaskNode g : nodes) {
                sb.append('s').append(g.getId()).append(',').append(g.getCriticalPaths())
                        .append(',').append(g.getReachableDuration())
                        .append(',').append(Double.doubleToLongBits(g.getBlockingPower()))
                        .append(',').append(Double.doubleToLongBits(g.getScore())).append(';');
            }
        }
    }

    static String run(int index, boolean withEvents) {
        LinkedList<Task> tasks = instance(index);
        int m = workers(index);
        Controller c = new Controller(tasks, names(m), false);
        c.initial();
        Tracer t = new Tracer();
        t.statics(c);
        t.record(c);
        Random r = new Random(31L * index + 5);
        int step = 0;
        int nextId = 10_000;
        int guard = 0;
        while (c.getTasksLeft() > 0 && guard++ < 10_000) {
            if (withEvents && step % 3 == 1) {
                int kind = r.nextInt(6);
                try {
                    switch (kind) {
                        case 0 -> c.addWorker(500 + step, "X" + step);
                        case 1 -> {
                            List<Worker> all = new ArrayList<>(c.getActiveWorkers());
                            all.addAll(c.getIdleWorkers());
                            all.sort(Comparator.comparingInt(Worker::getId));
                            if (all.size() > 1) c.removeWorker(all.get(r.nextInt(all.size())).getId());
                        }
                        case 2 -> {
                            List<Worker> act = new ArrayList<>(c.getActiveWorkers());
                            act.sort(Comparator.comparingInt(Worker::getId));
                            if (!act.isEmpty()) {
                                Worker w = act.get(r.nextInt(act.size()));
                                c.workerIsLate(w.getId(), w.getTaskEstimatedEndTime() + 1 + r.nextInt(5));
                            }
                        }
                        case 3 -> {
                            Worker first = c.getWorkerManager().getEmploymentsQ().peek();
                            if (first != null) {
                                int time = Math.max(c.getGlobalTime(), first.getTaskEstimatedEndTime() - 1);
                                c.workerFinishEarly(first.getId(), time);
                                t.sb.append("fe;");
                                t.record(c);
                                step++;
                                continue;
                            }
                        }
                        case 4 -> {
                            List<GraphTaskNode> ready = new ArrayList<>(c.getTaskEngine().getReadyPQ());
                            ready.sort(Comparator.comparingInt(GraphTaskNode::getId));
                            LinkedList<Integer> children = new LinkedList<>();
                            if (!ready.isEmpty()) children.add(ready.get(r.nextInt(ready.size())).getId());
                            c.addTask(new Task("N" + nextId, nextId, 1 + r.nextInt(9), new LinkedList<>()), children);
                            nextId++;
                        }
                        default -> {
                            List<GraphTaskNode> ready = new ArrayList<>(c.getTaskEngine().getReadyPQ());
                            ready.sort(Comparator.comparingInt(GraphTaskNode::getId));
                            if (!ready.isEmpty()) {
                                GraphTaskNode g = ready.get(r.nextInt(ready.size()));
                                c.removeTask(new Task(g.getName(), g.getId(), g.getDuration(), new LinkedList<>()));
                            }
                        }
                    }
                    t.sb.append('e').append(kind).append(';');
                } catch (RuntimeException ex) {
                    t.sb.append('x').append(kind).append(ex.getClass().getSimpleName()).append(';');
                }
                t.record(c);
            }
            if (c.getTasksLeft() == 0) break;
            if (c.getActiveWorkers().isEmpty()) {
                t.sb.append("deadlock;");
                break;
            }
            c.workerFinishOnTime();
            t.record(c);
            step++;
        }
        return c.getGlobalTime() + ":" + Long.toHexString(fnv(t.sb.toString()));
    }

    static String runScenario(ProjectScenario s) {
        Controller c = new Controller(s.getTasks(), s.getWorkers(), false);
        c.initial();
        Tracer t = new Tracer();
        t.statics(c);
        t.record(c);
        while (c.getTasksLeft() > 0) {
            c.workerFinishOnTime();
            t.record(c);
        }
        return c.getGlobalTime() + ":" + Long.toHexString(fnv(t.sb.toString()));
    }

    static long fnv(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    // ---------------------------------------------------------------- tests

    @Test
    public void defaultConfiguration_reproducesReferenceSchedules() {
        for (int i = 0; i < INSTANCES; i++) {
            assertEquals("instance " + i, GoldenTraceData.PLAIN[i], run(i, false));
        }
    }

    @Test
    public void defaultConfiguration_reproducesReferenceRuntimeEventBehaviour() {
        for (int i = 0; i < EVENT_INSTANCES; i++) {
            assertEquals("event instance " + i, GoldenTraceData.EVENTS[i], run(i, true));
        }
    }

    @Test
    public void defaultConfiguration_reproducesReferenceCuratedScenarios() {
        List<ProjectScenario> scenarios = ScenarioLibrary.getScenarios();
        for (int i = 0; i < scenarios.size(); i++) {
            assertEquals(scenarios.get(i).getName(), GoldenTraceData.CURATED[i], runScenario(scenarios.get(i)));
        }
    }

    /** Prints GoldenTraceData. Only run against the reference implementation. */
    public static void main(String[] args) {
        java.io.PrintStream out = System.out;
        System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        StringBuilder sb = new StringBuilder();
        sb.append("package backend;\n\n/** Generated from the reference implementation (commit be6d109) by GoldenTraceTest.main. Do not edit. */\n");
        sb.append("final class GoldenTraceData {\n    private GoldenTraceData() {\n    }\n\n");
        sb.append("    static final String[] PLAIN = {\n");
        for (int i = 0; i < INSTANCES; i++) sb.append("            \"").append(run(i, false)).append("\",\n");
        sb.append("    };\n\n    static final String[] EVENTS = {\n");
        for (int i = 0; i < EVENT_INSTANCES; i++) sb.append("            \"").append(run(i, true)).append("\",\n");
        sb.append("    };\n\n    static final String[] CURATED = {\n");
        for (ProjectScenario s : ScenarioLibrary.getScenarios()) sb.append("            \"").append(runScenario(s)).append("\",\n");
        sb.append("    };\n}\n");
        out.print(sb);
    }
}
