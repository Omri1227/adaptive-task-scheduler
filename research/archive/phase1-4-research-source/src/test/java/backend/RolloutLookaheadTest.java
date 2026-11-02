package backend;

import benchmark.BenchmarkEngine;
import benchmark.BenchmarkStrategy;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

public class RolloutLookaheadTest {

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

    private static boolean distinctScores(TaskEngine e) {
        Set<Double> s = new HashSet<>();
        for (GraphTaskNode g : e.getTopologicalSort()) s.add(g.getScore());
        return s.size() == e.getTopologicalSort().size();
    }

    @Test
    public void fullRolloutFromStart_equalsStaticPriorityListSchedule() {
        // From t=0 with all workers idle, starting the top static task and
        // continuing by static priority is exactly the batched static baseline.
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
                    top, ready, List.of(), m - 1, 0, SchedulerConfig.FULL_ROLLOUT);
            int baseline = BenchmarkEngine.makespan(
                    new ProjectScenario(1, "x", "", "", copy(tasks), workers(m)),
                    BenchmarkStrategy.HIGHEST_STATIC_SCORE, SchedulerConfig.DEFAULT,
                    SchedulerConfig.CompletionMode.BATCHED);
            assertEquals("instance " + it, baseline, rollout);
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
                int full = logic.rolloutMakespan(model, candidate, others, List.of(), m - 1, 0, SchedulerConfig.FULL_ROLLOUT);
                for (int depth : new int[]{0, 1, 2, 4, 8, 16}) {
                    int bound = logic.rolloutMakespan(model, candidate, others, List.of(), m - 1, 0, depth);
                    assertTrue(bound <= full);
                }
            }
        }
    }

    @Test
    public void rolloutMode_fixesKnownLegacyCounterexample() {
        // P(1)->A(1), P->B(10), X(6), two workers: legacy picks A at t=1 (12), optimum is 11.
        LinkedList<Task> tasks = new LinkedList<>(Arrays.asList(
                new Task("P", 1, 1, new LinkedList<>()),
                new Task("X", 2, 6, new LinkedList<>()),
                new Task("A", 3, 1, new LinkedList<>(List.of(1))),
                new Task("B", 4, 10, new LinkedList<>(List.of(1)))));
        SchedulerConfig rollout = SchedulerConfig.builder()
                .lookaheadMode(SchedulerConfig.LookaheadMode.ROLLOUT).build();
        assertEquals(12, makespan(copy(tasks), 2, SchedulerConfig.DEFAULT));
        assertEquals(11, makespan(copy(tasks), 2, rollout));
        assertEquals(11, makespan(copy(tasks), 2, rollout.toBuilder().rolloutDepth(1).build()));
    }

    @Test
    public void rolloutMode_producesValidSchedules() {
        Random r = new Random(77);
        for (int it = 0; it < 200; it++) {
            LinkedList<Task> tasks = randomDag(r, 2 + r.nextInt(60), r.nextDouble() * 0.3, 15);
            int m = 1 + r.nextInt(6);
            for (int depth : new int[]{0, 2, SchedulerConfig.FULL_ROLLOUT}) {
                SchedulerConfig cfg = SchedulerConfig.builder().lookaheadMode(SchedulerConfig.LookaheadMode.ROLLOUT)
                        .rolloutDepth(depth).lookaheadCandidates(3).build();
                assertValid(copy(tasks), m, cfg);
            }
        }
    }

    static void assertValid(LinkedList<Task> tasks, int m, SchedulerConfig cfg) {
        Map<Integer, Task> byId = new HashMap<>();
        for (Task t : tasks) byId.put(t.getId(), t);
        Controller c = new Controller(copy(tasks), workers(m), false, cfg);
        c.initial();
        Map<Integer, Integer> start = new HashMap<>();
        int guard = 0;
        while (true) {
            for (Worker w : c.getActiveWorkers()) start.putIfAbsent(w.getDesk().getId(), w.getStartTime());
            if (c.getTasksLeft() == 0) break;
            assertFalse("deadlock", c.getActiveWorkers().isEmpty());
            c.workerFinishOnTime();
            assertTrue(guard++ < 100_000);
        }
        assertEquals(tasks.size(), start.size());
        int makespan = 0;
        List<int[]> intervals = new ArrayList<>();
        for (Task t : tasks) {
            int s = start.get(t.getId());
            for (int p : t.getDependsOnIDS()) {
                assertTrue("precedence", s >= start.get(p) + byId.get(p).getDuration());
            }
            makespan = Math.max(makespan, s + t.getDuration());
            intervals.add(new int[]{s, s + t.getDuration()});
        }
        assertEquals(makespan, c.getGlobalTime());
        for (int[] a : intervals) {
            int overlapping = 0;
            for (int[] b : intervals) if (b[0] <= a[0] && a[0] < b[1]) overlapping++;
            assertTrue("capacity", overlapping <= m);
        }
    }

    private static int makespan(LinkedList<Task> tasks, int m, SchedulerConfig cfg) {
        Controller c = new Controller(tasks, workers(m), false, cfg);
        c.initial();
        while (c.getTasksLeft() > 0) c.workerFinishOnTime();
        return c.getGlobalTime();
    }

    static LinkedList<Task> copy(LinkedList<Task> tasks) {
        LinkedList<Task> c = new LinkedList<>();
        for (Task t : tasks) c.add(new Task(t.getName(), t.getId(), t.getDuration(), new LinkedList<>(t.getDependsOnIDS())));
        return c;
    }
}
