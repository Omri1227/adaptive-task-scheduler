package backend;

import benchmark.BenchmarkEngine;
import benchmark.BenchmarkStrategy;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BatchedCompletionTest {

    private static final SchedulerConfig BATCHED = SchedulerConfig.builder()
            .completionMode(SchedulerConfig.CompletionMode.BATCHED).build();

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

    @Test
    public void batchedMode_producesValidSchedules() {
        Random r = new Random(41);
        for (int it = 0; it < 300; it++) {
            // short durations make simultaneous completions frequent
            LinkedList<Task> tasks = randomDag(r, 1 + r.nextInt(50), r.nextDouble() * 0.3, 1 + r.nextInt(4));
            RolloutLookaheadTest.assertValid(tasks, 1 + r.nextInt(6), BATCHED);
        }
    }

    @Test
    public void batchedStaticOnly_matchesBatchedStaticBaseline() {
        SchedulerConfig staticOnly = BATCHED.toBuilder().staticShare(1).lookaheadWeight(0).build();
        Random r = new Random(6);
        int compared = 0;
        for (int it = 0; it < 800; it++) {
            LinkedList<Task> tasks = randomDag(r, 3 + r.nextInt(30), 0.2, it % 2 == 0 ? 4 : 20);
            ProjectScenario s = new ProjectScenario(1, "x", "", "", tasks, workers(1 + r.nextInt(5)));
            TaskEngine engine = new TaskEngine(s.getTasks());
            engine.initial();
            Set<Double> scores = new HashSet<>();
            for (GraphTaskNode g : engine.getTopologicalSort()) scores.add(g.getScore());
            if (scores.size() != tasks.size()) continue;
            compared++;
            assertEquals("instance " + it,
                    BenchmarkEngine.makespan(s, BenchmarkStrategy.HIGHEST_STATIC_SCORE, staticOnly,
                            SchedulerConfig.CompletionMode.BATCHED),
                    BenchmarkEngine.makespan(s, BenchmarkStrategy.ADAPTIVE, staticOnly,
                            SchedulerConfig.CompletionMode.BATCHED));
        }
        assertTrue(compared > 100);
    }

    @Test
    public void batchedMode_finishesAllSimultaneousTasksInOneEvent() {
        LinkedList<Task> tasks = new LinkedList<>(Arrays.asList(
                new Task("A", 1, 3, new LinkedList<>()),
                new Task("B", 2, 3, new LinkedList<>()),
                new Task("C", 3, 2, new LinkedList<>(List.of(1, 2)))));
        Controller c = new Controller(tasks, workers(2), false, BATCHED);
        c.initial();
        c.workerFinishOnTime();
        assertEquals(3, c.getGlobalTime());
        assertEquals(2, c.getTasksCompleted());
        assertEquals(1, c.getActiveWorkers().size());
        c.workerFinishOnTime();
        assertEquals(5, c.getGlobalTime());
        assertEquals(0, c.getTasksLeft());
    }
}
