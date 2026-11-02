package backend;

import benchmark.BenchmarkEngine;
import benchmark.BenchmarkReport;
import benchmark.BenchmarkStrategy;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.assertEquals;

public class BenchmarkCompletionModeTest {

    private static Task task(int id, int duration, Integer... deps) {
        return new Task("T" + id, id, duration, new LinkedList<>(Arrays.asList(deps)));
    }

    private static LinkedList<String> workers(int m) {
        LinkedList<String> l = new LinkedList<>();
        for (int i = 0; i < m; i++) l.add("W" + i);
        return l;
    }

    @Test
    public void originalOverload_equalsDefaultConfigWithBatchedBaselines() {
        for (ProjectScenario s : ScenarioLibrary.getScenarios()) {
            BenchmarkReport a = BenchmarkEngine.benchmark(s);
            BenchmarkReport b = BenchmarkEngine.benchmark(s, SchedulerConfig.DEFAULT, SchedulerConfig.CompletionMode.BATCHED);
            assertEquals(s.getName(), a.asMap(), b.asMap());
        }
    }

    @Test
    public void sequentialCompletions_canChangeBaselineMakespan() {
        // Found by search: tasks 1 and 4 both finish at t=4; CP-first decides
        // differently when it sees only one of the two completions.
        ProjectScenario s = new ProjectScenario(1, "seq-vs-batch", "", "", new LinkedList<>(List.of(
                task(1, 2), task(2, 2, 1), task(3, 4, 1), task(4, 4),
                task(5, 3, 1, 2, 3, 4), task(6, 2, 5), task(7, 4, 1, 2), task(8, 3, 1, 4))), workers(2));
        assertEquals(12, BenchmarkEngine.makespan(s, BenchmarkStrategy.CRITICAL_PATH_FIRST,
                SchedulerConfig.DEFAULT, SchedulerConfig.CompletionMode.BATCHED));
        assertEquals(13, BenchmarkEngine.makespan(s, BenchmarkStrategy.CRITICAL_PATH_FIRST,
                SchedulerConfig.DEFAULT, SchedulerConfig.CompletionMode.SEQUENTIAL));
    }

    @Test
    public void sequentialStaticBaseline_matchesControllerRunningStaticOnly() {
        // lambda = 1 and mu = 0 reduce the adaptive scheduler to "highest static
        // score"; with distinct scores the two implementations must agree exactly
        // when they use the same completion semantics.
        SchedulerConfig staticOnly = SchedulerConfig.builder().staticShare(1).lookaheadWeight(0).build();
        Random r = new Random(5);
        int compared = 0;
        for (int it = 0; it < 600; it++) {
            int n = 3 + r.nextInt(30);
            LinkedList<Task> tasks = new LinkedList<>();
            for (int j = 0; j < n; j++) {
                LinkedList<Integer> d = new LinkedList<>();
                for (int i = 0; i < j; i++) if (r.nextDouble() < 0.2) d.add(i + 1);
                tasks.add(new Task("T" + (j + 1), j + 1, 1 + r.nextInt(it % 2 == 0 ? 4 : 20), d));
            }
            ProjectScenario s = new ProjectScenario(1, "x", "", "", tasks, workers(1 + r.nextInt(5)));
            TaskEngine engine = new TaskEngine(s.getTasks());
            engine.initial();
            Set<Double> scores = new HashSet<>();
            for (GraphTaskNode g : engine.getTopologicalSort()) scores.add(g.getScore());
            if (scores.size() != n) continue;
            compared++;
            assertEquals("instance " + it,
                    BenchmarkEngine.makespan(s, BenchmarkStrategy.HIGHEST_STATIC_SCORE, staticOnly,
                            SchedulerConfig.CompletionMode.SEQUENTIAL),
                    BenchmarkEngine.makespan(s, BenchmarkStrategy.ADAPTIVE, staticOnly,
                            SchedulerConfig.CompletionMode.SEQUENTIAL));
        }
        assertEquals(true, compared > 100);
    }
}
