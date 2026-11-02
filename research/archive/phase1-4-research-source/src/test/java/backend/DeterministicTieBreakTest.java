package backend;

import org.junit.Test;

import java.util.*;

import static org.junit.Assert.assertEquals;

public class DeterministicTieBreakTest {

    /** In-degree at most 2 keeps blocking-power sums exact, so scores are order-independent bit for bit. */
    private static LinkedList<Task> dag(Random r, int n) {
        LinkedList<Task> tasks = new LinkedList<>();
        for (int j = 0; j < n; j++) {
            LinkedList<Integer> d = new LinkedList<>();
            if (j > 0 && r.nextInt(3) != 0) d.add(1 + r.nextInt(j));
            if (j > 1 && r.nextInt(3) == 0) {
                int p = 1 + r.nextInt(j);
                if (!d.contains(p)) d.add(p);
            }
            // few distinct durations -> many score ties
            tasks.add(new Task("T" + (j + 1), j + 1, 1 + r.nextInt(3), d));
        }
        return tasks;
    }

    private static Map<Integer, Integer> schedule(LinkedList<Task> tasks, int m, SchedulerConfig cfg) {
        LinkedList<String> w = new LinkedList<>();
        for (int i = 0; i < m; i++) w.add("W" + i);
        Controller c = new Controller(tasks, w, false, cfg);
        c.initial();
        Map<Integer, Integer> start = new TreeMap<>();
        while (true) {
            for (Worker wk : c.getActiveWorkers()) start.putIfAbsent(wk.getDesk().getId(), wk.getStartTime());
            if (c.getTasksLeft() == 0) break;
            c.workerFinishOnTime();
        }
        return start;
    }

    @Test
    public void staticOnlyWithIdTieBreak_isInvariantToInputOrder() {
        SchedulerConfig cfg = SchedulerConfig.builder().staticShare(1).lookaheadWeight(0)
                .deterministicTieBreak(true).build();
        Random r = new Random(2);
        for (int it = 0; it < 150; it++) {
            LinkedList<Task> tasks = dag(r, 2 + r.nextInt(40));
            int m = 1 + r.nextInt(5);
            Map<Integer, Integer> reference = schedule(RolloutLookaheadTest.copy(tasks), m, cfg);
            for (int p = 0; p < 5; p++) {
                LinkedList<Task> shuffled = RolloutLookaheadTest.copy(tasks);
                Collections.shuffle(shuffled, r);
                assertEquals("instance " + it, reference, schedule(shuffled, m, cfg));
            }
        }
    }

    @Test
    public void tieBreakFlag_ordersEqualScoresById() {
        LinkedList<Task> tasks = new LinkedList<>();
        for (int id = 5; id >= 1; id--) tasks.add(new Task("T" + id, id, 2, new LinkedList<>()));
        TaskEngine e = new TaskEngine(tasks, SchedulerConfig.builder().deterministicTieBreak(true).build());
        e.initial();
        List<Integer> order = new ArrayList<>();
        while (!e.getReadyPQ().isEmpty()) order.add(e.getReadyPQ().poll().getId());
        assertEquals(List.of(1, 2, 3, 4, 5), order);
    }
}
