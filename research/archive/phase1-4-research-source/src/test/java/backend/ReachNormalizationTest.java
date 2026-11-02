package backend;

import org.junit.Test;

import java.util.LinkedList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ReachNormalizationTest {

    /** Four independent chains of three unit tasks: total work 12, largest reach 3. */
    private static LinkedList<Task> chains() {
        LinkedList<Task> tasks = new LinkedList<>();
        int id = 1;
        for (int c = 0; c < 4; c++) {
            for (int k = 0; k < 3; k++) {
                LinkedList<Integer> dep = k == 0 ? new LinkedList<>() : new LinkedList<>(List.of(id - 1));
                tasks.add(new Task("T" + id, id, 1, dep));
                id++;
            }
        }
        return tasks;
    }

    private static double headScore(SchedulerConfig cfg, boolean reuseAnalysis) {
        LinkedList<Task> tasks = chains();
        StaticGraphAnalysis analysis = reuseAnalysis ? StaticGraphAnalysis.of(tasks) : null;
        TaskEngine e = new TaskEngine(tasks, cfg, analysis);
        e.initial();
        return e.getTask(1).getScore();
    }

    @Test
    public void defaultDividesReachByTotalWork() {
        SchedulerConfig reachOnly = SchedulerConfig.builder().staticWeights(0, 0, 1).build();
        assertEquals(3.0 / 12, headScore(reachOnly, false), 1e-12);
        assertEquals(3.0 / 12, headScore(reachOnly, true), 1e-12);
    }

    @Test
    public void flagDividesReachByLargestReach() {
        SchedulerConfig reachOnly = SchedulerConfig.builder().staticWeights(0, 0, 1)
                .reachNormalizedByMaximum(true).build();
        assertEquals(1.0, headScore(reachOnly, false), 1e-12);
        assertEquals(1.0, headScore(reachOnly, true), 1e-12);
    }

    @Test
    public void defaultWeights_headScoreGainsTheFullReachTerm() {
        // default: 0.5*3/3 + 0.25*1/1 + 0.25*3/12 = 0.8125 ; normalized: 0.5 + 0.25 + 0.25 = 1.0
        assertEquals(0.8125, headScore(SchedulerConfig.DEFAULT, false), 1e-12);
        assertEquals(1.0, headScore(SchedulerConfig.builder().reachNormalizedByMaximum(true).build(), false), 1e-12);
    }
}
