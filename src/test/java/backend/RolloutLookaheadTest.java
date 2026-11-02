package backend;

import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

/**
 * Tests for the rollout lookahead used by TaskEngine.getNextTask.
 */
public class RolloutLookaheadTest {

    private static final int FULL = Integer.MAX_VALUE;

    @Test
    public void knownLegacyCounterexample_isScheduledOptimally() {
        // P(1)->A(1), P->B(10), X(6), two workers. The legacy idle-window lookahead
        // starts A at t=1 (makespan 12); the optimum starts B first (makespan 11).
        LinkedList<Task> tasks = new LinkedList<>(Arrays.asList(
                new Task("P", 1, 1, new LinkedList<>()),
                new Task("X", 2, 6, new LinkedList<>()),
                new Task("A", 3, 1, new LinkedList<>(List.of(1))),
                new Task("B", 4, 10, new LinkedList<>(List.of(1)))));
        assertEquals(11, runToEnd(tasks, 2).getGlobalTime());
    }

    @Test
    public void schedulesAreValid_eachTaskRunsExactlyOnce_andReadyQueueStaysConsistent() {
        // Few workers and wide graphs make contested decisions with 5+ ready tasks
        // common, so candidates beyond the three polled ones are often selected.
        Random r = new Random(77);
        for (int it = 0; it < 400; it++) {
            LinkedList<Task> tasks = randomDag(r, 2 + r.nextInt(60), r.nextDouble() * 0.25, 15);
            int m = 1 + r.nextInt(5);
            assertValidSchedule("instance " + it, tasks, m);
        }
    }

    @Test
    public void fullRolloutFromStart_equalsStaticPriorityListSchedule() {
        // From t=0 with all workers idle, starting the top static task and then
        // continuing by static priority is exactly a static-priority list schedule.
        Random r = new Random(21);
        int compared = 0;
        for (int it = 0; it < 400; it++) {
            LinkedList<Task> tasks = randomDag(r, 2 + r.nextInt(40), r.nextDouble() * 0.3, 20);
            int m = 1 + r.nextInt(6);
            TaskEngine engine = new TaskEngine(copy(tasks));
            engine.initial();
            if (!distinctScores(engine)) continue;
            compared++;
            List<GraphTaskNode> ready = new ArrayList<>(engine.getReadyPQ());
            ready.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
            GraphTaskNode top = ready.remove(0);
            int rollout = new DynamicScoreLogic().rolloutMakespan(
                    new DynamicScoreLogic.RolloutModel(engine.getTopologicalSort()),
                    top, ready, List.of(), m - 1, 0, FULL);
            assertEquals("instance " + it, staticListSchedule(engine, m), rollout);
        }
        assertTrue(compared > 100);
    }

    @Test
    public void truncatedRollout_neverExceedsFullRolloutMakespan() {
        // The truncated estimate is a lower bound on the makespan of the same continuation.
        Random r = new Random(8);
        DynamicScoreLogic logic = new DynamicScoreLogic();
        for (int it = 0; it < 300; it++) {
            LinkedList<Task> tasks = randomDag(r, 2 + r.nextInt(40), r.nextDouble() * 0.3, 20);
            int m = 1 + r.nextInt(6);
            TaskEngine engine = new TaskEngine(copy(tasks));
            engine.initial();
            DynamicScoreLogic.RolloutModel model = new DynamicScoreLogic.RolloutModel(engine.getTopologicalSort());
            List<GraphTaskNode> ready = new ArrayList<>(engine.getReadyPQ());
            for (GraphTaskNode candidate : ready) {
                List<GraphTaskNode> others = new ArrayList<>(ready);
                others.remove(candidate);
                int full = logic.rolloutMakespan(model, candidate, others, List.of(), m - 1, 0, FULL);
                for (int depth : new int[]{0, 1, 2, 4, 8, 16}) {
                    int bound = logic.rolloutMakespan(model, candidate, others, List.of(), m - 1, 0, depth);
                    assertTrue(bound <= full);
                }
            }
        }
    }

    @Test
    public void rollout_doesNotModifyNodesWorkersOrQueues() {
        Random r = new Random(5);
        for (int it = 0; it < 100; it++) {
            LinkedList<Task> tasks = randomDag(r, 5 + r.nextInt(30), 0.15, 9);
            Controller c = new Controller(copy(tasks), workers(2), false);
            c.initial();
            if (c.getTasksLeft() > 1) {
                c.workerFinishOnTime();
            }
            TaskEngine engine = c.getTaskEngine();
            List<GraphTaskNode> ready = new ArrayList<>(engine.getReadyPQ());
            if (ready.isEmpty()) continue;

            Map<Integer, Integer> parentsBefore = new HashMap<>();
            for (GraphTaskNode g : engine.getTopologicalSort()) {
                parentsBefore.put(g.getId(), g.getParentsRemained().size());
            }
            List<Worker> employed = new ArrayList<>(c.getActiveWorkers());
            Map<Integer, String> workersBefore = new HashMap<>();
            for (Worker w : employed) workersBefore.put(w.getId(), describe(w));

            GraphTaskNode candidate = ready.remove(0);
            new DynamicScoreLogic().rolloutMakespan(
                    new DynamicScoreLogic.RolloutModel(engine.getTopologicalSort()),
                    candidate, ready, employed, c.getIdleWorkers().size(), c.getGlobalTime(), 8);

            for (GraphTaskNode g : engine.getTopologicalSort()) {
                assertEquals(parentsBefore.get(g.getId()).intValue(), g.getParentsRemained().size());
            }
            for (Worker w : employed) assertEquals(workersBefore.get(w.getId()), describe(w));
            assertEquals(ready.size() + 1, engine.getReadyPQ().size());
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void assertValidSchedule(String label, LinkedList<Task> tasks, int m) {
        Map<Integer, Task> byId = new HashMap<>();
        for (Task t : tasks) byId.put(t.getId(), t);
        Controller c = new Controller(copy(tasks), workers(m), false);
        c.initial();

        Set<String> assignments = new HashSet<>();   // worker:task:start
        Map<Integer, Integer> start = new HashMap<>();
        int guard = 0;
        while (true) {
            for (Worker w : c.getActiveWorkers()) {
                int id = w.getDesk().getId();
                if (assignments.add(w.getId() + ":" + id + ":" + w.getStartTime())) {
                    assertNull(label + ": task " + id + " started twice", start.put(id, w.getStartTime()));
                }
            }
            assertReadyQueueConsistent(label, c);
            if (c.getTasksLeft() == 0) break;
            assertFalse(label + ": deadlock", c.getActiveWorkers().isEmpty());
            c.workerFinishOnTime();
            assertTrue(guard++ < 100_000);
        }

        assertEquals(label, tasks.size(), start.size());
        assertEquals(label, tasks.size(), c.getTasksCompleted());
        int makespan = 0;
        List<int[]> intervals = new ArrayList<>();
        for (Task t : tasks) {
            int s = start.get(t.getId());
            for (int p : t.getDependsOnIDS()) {
                assertTrue(label + ": precedence", s >= start.get(p) + byId.get(p).getDuration());
            }
            makespan = Math.max(makespan, s + t.getDuration());
            intervals.add(new int[]{s, s + t.getDuration()});
        }
        assertEquals(label, makespan, c.getGlobalTime());
        for (int[] a : intervals) {
            int overlapping = 0;
            for (int[] b : intervals) if (b[0] <= a[0] && a[0] < b[1]) overlapping++;
            assertTrue(label + ": capacity", overlapping <= m);
        }
    }

    /** The ready queue holds exactly the waiting tasks whose parents are all complete, once each. */
    private static void assertReadyQueueConsistent(String label, Controller c) {
        List<GraphTaskNode> queue = new ArrayList<>(c.getTaskEngine().getReadyPQ());
        Set<GraphTaskNode> queued = Collections.newSetFromMap(new IdentityHashMap<>());
        queued.addAll(queue);
        assertEquals(label + ": duplicate in ready queue", queue.size(), queued.size());
        for (GraphTaskNode g : c.getTaskEngine().getTopologicalSort()) {
            boolean shouldBeReady = !g.isCompleted()
                    && !c.getWorkerManager().isTaskInProgress(g)
                    && g.getParentsRemained().isEmpty();
            assertEquals(label + ": ready queue membership of task " + g.getId(), shouldBeReady, queued.contains(g));
        }
    }

    /** Reference: static-priority list schedule with simultaneous completions applied together. */
    private static int staticListSchedule(TaskEngine engine, int m) {
        List<GraphTaskNode> nodes = engine.getTopologicalSort();
        Map<GraphTaskNode, Integer> remaining = new IdentityHashMap<>();
        for (GraphTaskNode g : nodes) remaining.put(g, g.getParents().size());
        PriorityQueue<GraphTaskNode> ready = new PriorityQueue<>((a, b) -> Double.compare(b.getScore(), a.getScore()));
        for (GraphTaskNode g : nodes) if (g.getParents().isEmpty()) ready.add(g);
        PriorityQueue<long[]> running = new PriorityQueue<>(Comparator.comparingLong(x -> x[0]));
        List<GraphTaskNode> byIndex = new ArrayList<>(nodes);
        int t = 0;
        int free = m;
        while (true) {
            while (free > 0 && !ready.isEmpty()) {
                GraphTaskNode g = ready.poll();
                running.add(new long[]{t + g.getDuration(), byIndex.indexOf(g)});
                free--;
            }
            if (running.isEmpty()) return t;
            t = (int) running.peek()[0];
            while (!running.isEmpty() && running.peek()[0] == t) {
                GraphTaskNode g = byIndex.get((int) running.poll()[1]);
                free++;
                for (GraphTaskNode ch : g.getChildren()) {
                    if (remaining.merge(ch, -1, Integer::sum) == 0) ready.add(ch);
                }
            }
        }
    }

    private static String describe(Worker w) {
        return w.getDesk().getId() + "/" + w.getTaskEstimatedEndTime() + "/" + w.getStartTime()
                + "/" + w.copyUnemployedTime() + "/" + w.getEmploymentStatus();
    }

    private static Controller runToEnd(LinkedList<Task> tasks, int m) {
        Controller c = new Controller(copy(tasks), workers(m), false);
        c.initial();
        while (c.getTasksLeft() > 0) c.workerFinishOnTime();
        return c;
    }

    private static boolean distinctScores(TaskEngine e) {
        Set<Double> s = new HashSet<>();
        for (GraphTaskNode g : e.getTopologicalSort()) s.add(g.getScore());
        return s.size() == e.getTopologicalSort().size();
    }

    private static LinkedList<Task> randomDag(Random r, int n, double p, int maxDuration) {
        LinkedList<Task> tasks = new LinkedList<>();
        for (int j = 0; j < n; j++) {
            LinkedList<Integer> d = new LinkedList<>();
            for (int i = 0; i < j; i++) if (r.nextDouble() < p) d.add(i + 1);
            tasks.add(new Task("T" + (j + 1), j + 1, 1 + r.nextInt(maxDuration), d));
        }
        return tasks;
    }

    private static LinkedList<String> workers(int m) {
        LinkedList<String> l = new LinkedList<>();
        for (int i = 0; i < m; i++) l.add("W" + i);
        return l;
    }

    private static LinkedList<Task> copy(LinkedList<Task> tasks) {
        LinkedList<Task> c = new LinkedList<>();
        for (Task t : tasks) c.add(new Task(t.getName(), t.getId(), t.getDuration(), new LinkedList<>(t.getDependsOnIDS())));
        return c;
    }
}
