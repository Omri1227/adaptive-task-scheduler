package backend;

import benchmark.BenchmarkEngine;
import benchmark.BenchmarkStrategy;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CandidatePoolSizeTest {

    private static LinkedList<Task> randomDag(Random r, int n) {
        LinkedList<Task> tasks = new LinkedList<>();
        for (int j = 0; j < n; j++) {
            LinkedList<Integer> d = new LinkedList<>();
            for (int i = 0; i < j; i++) if (r.nextDouble() < 0.15) d.add(i + 1);
            tasks.add(new Task("T" + (j + 1), j + 1, 1 + r.nextInt(15), d));
        }
        return tasks;
    }

    private static LinkedList<String> workers(int m) {
        LinkedList<String> l = new LinkedList<>();
        for (int i = 0; i < m; i++) l.add("W" + i);
        return l;
    }

    @Test
    public void poolOfOne_isStaticPriorityScheduling() {
        // default weights and lookahead: with K = 1 there is nothing to compare
        SchedulerConfig k1 = SchedulerConfig.builder().candidatePoolSize(1).build();
        Random r = new Random(17);
        int compared = 0;
        for (int it = 0; it < 500; it++) {
            ProjectScenario s = new ProjectScenario(1, "x", "", "", randomDag(r, 3 + r.nextInt(35)), workers(1 + r.nextInt(5)));
            TaskEngine e = new TaskEngine(s.getTasks());
            e.initial();
            Set<Double> scores = new HashSet<>();
            for (GraphTaskNode g : e.getTopologicalSort()) scores.add(g.getScore());
            if (scores.size() != s.getTaskCount()) continue;
            compared++;
            assertEquals(
                    BenchmarkEngine.makespan(s, BenchmarkStrategy.HIGHEST_STATIC_SCORE, k1, SchedulerConfig.CompletionMode.SEQUENTIAL),
                    BenchmarkEngine.makespan(s, BenchmarkStrategy.ADAPTIVE, k1, SchedulerConfig.CompletionMode.SEQUENTIAL));
        }
        assertTrue(compared > 100);
    }

    @Test
    public void largerPools_produceValidSchedules() {
        Random r = new Random(23);
        for (int k : new int[]{1, 2, 3, 5, 10, SchedulerConfig.ALL_READY_TASKS}) {
            SchedulerConfig cfg = SchedulerConfig.builder().candidatePoolSize(k).build();
            for (int it = 0; it < 60; it++) {
                RolloutLookaheadTest.assertValid(randomDag(r, 1 + r.nextInt(50)), 1 + r.nextInt(6), cfg);
            }
        }
    }

    @Test
    public void largePool_canSelectTaskOutsideStaticTopThree() {
        // Static order: four long-CP chain heads (0.573) outrank U (0.497), so U is
        // 5th. With lambda = 0 (dynamic only), 8 workers and 5 ready tasks, U, which
        // unlocks eight children, has the best dynamic score (0.562 vs 0.514).
        LinkedList<Task> tasks = new LinkedList<>();
        for (int c = 0; c < 4; c++) {
            int head = 1 + c * 3;
            tasks.add(new Task("C" + head, head, 3, new LinkedList<>()));
            tasks.add(new Task("C" + (head + 1), head + 1, 3, new LinkedList<>(List.of(head))));
            tasks.add(new Task("C" + (head + 2), head + 2, 3, new LinkedList<>(List.of(head + 1))));
        }
        tasks.add(new Task("U", 100, 1, new LinkedList<>()));
        for (int k = 0; k < 8; k++) tasks.add(new Task("K" + k, 101 + k, 2, new LinkedList<>(List.of(100))));
        SchedulerConfig dynamicOnly = SchedulerConfig.builder().staticShare(0)
                .lookaheadMode(SchedulerConfig.LookaheadMode.NONE).build();
        assertEquals(1, firstPick(tasks, dynamicOnly));
        assertEquals(100, firstPick(tasks, dynamicOnly.toBuilder().candidatePoolSize(5).build()));
    }

    private static int firstPick(LinkedList<Task> tasks, SchedulerConfig cfg) {
        TaskEngine e = new TaskEngine(RolloutLookaheadTest.copy(tasks), cfg);
        e.initial();
        return e.getNextTask(8, new PriorityQueue<>(Comparator.comparingInt(Worker::getTaskEstimatedEndTime)),
                new ArrayDeque<>(), 0).getId();
    }
}
