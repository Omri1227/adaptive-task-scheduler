package backend;

import org.junit.Test;

import java.util.LinkedList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class WorkloadPressureTest {

    private static final SchedulerConfig DURATION_ONLY =
            SchedulerConfig.builder().dynamicWeights(0, 0, 0, 1, 0).build();

    private static GraphTaskNode longest() {
        TaskEngine e = new TaskEngine(new LinkedList<>(List.of(new Task("L", 1, 10, new LinkedList<>()))));
        e.initial();
        return e.getTask(1);
    }

    @Test
    public void defaultFormula_usesTotalWorkers() {
        // m = 10, 3 ready -> alpha = 0.3 -> 0.3 * 1 + 0.7 * 0 = 0.3 for the longest task
        DynamicScoreLogic logic = new DynamicScoreLogic(DURATION_ONLY);
        assertEquals(0.3, logic.makeDynamicScore(longest(), 3, 10, 1.0, 10, 1), 1e-12);
        assertEquals(0.3, logic.makeDynamicScore(longest(), 3, 10, 1.0, 10, 1, 1), 1e-12);
    }

    @Test
    public void availableWorkersFormula_seesThreeTasksPerFreeWorker() {
        // 1 free worker, 3 ready -> alpha = 1 -> prefers the longest task fully
        DynamicScoreLogic logic = new DynamicScoreLogic(
                DURATION_ONLY.toBuilder().workloadUsesAvailableWorkers(true).build());
        assertEquals(1.0, logic.makeDynamicScore(longest(), 3, 10, 1.0, 10, 1, 1), 1e-12);
        // 6 free workers, 3 ready -> alpha = 0.5
        assertEquals(0.5, logic.makeDynamicScore(longest(), 3, 10, 1.0, 10, 1, 6), 1e-12);
    }

    @Test
    public void zeroAvailableWorkers_isHandledSafely() {
        DynamicScoreLogic logic = new DynamicScoreLogic(
                DURATION_ONLY.toBuilder().workloadUsesAvailableWorkers(true).build());
        assertEquals(1.0, logic.makeDynamicScore(longest(), 3, 10, 1.0, 10, 1, 0), 1e-12);
    }

    @Test
    public void flagProducesValidSchedules() {
        java.util.Random r = new java.util.Random(13);
        SchedulerConfig cfg = SchedulerConfig.builder().workloadUsesAvailableWorkers(true).build();
        for (int it = 0; it < 150; it++) {
            LinkedList<Task> tasks = new LinkedList<>();
            int n = 1 + r.nextInt(40);
            for (int j = 0; j < n; j++) {
                LinkedList<Integer> d = new LinkedList<>();
                for (int i = 0; i < j; i++) if (r.nextDouble() < 0.15) d.add(i + 1);
                tasks.add(new Task("T" + (j + 1), j + 1, 1 + r.nextInt(12), d));
            }
            RolloutLookaheadTest.assertValid(tasks, 1 + r.nextInt(8), cfg);
        }
    }
}
