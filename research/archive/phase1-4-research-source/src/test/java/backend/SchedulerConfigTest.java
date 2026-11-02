package backend;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedList;

import static org.junit.Assert.*;

public class SchedulerConfigTest {

    private Task task(int id, int duration, Integer... deps) {
        return new Task("T" + id, id, duration, new LinkedList<>(Arrays.asList(deps)));
    }

    private LinkedList<Task> sample() {
        // P(1) -> A(1), P -> B(10), X(6) independent
        return new LinkedList<>(Arrays.asList(task(1, 1), task(2, 6), task(3, 1, 1), task(4, 10, 1)));
    }

    @Test
    public void defaults_matchOriginalConstants() {
        SchedulerConfig c = SchedulerConfig.DEFAULT;
        assertEquals(0.5, c.getCriticalPathWeight(), 0);
        assertEquals(0.25, c.getBlockingPowerWeight(), 0);
        assertEquals(0.25, c.getReachableDurationWeight(), 0);
        assertEquals(0.2, c.getOpenToBlockWeight(), 0);
        assertEquals(0.1, c.getOpenChildrenWeight(), 0);
        assertEquals(0.15, c.getChildImportanceWeight(), 0);
        assertEquals(0.3, c.getDurationToReadyWeight(), 0);
        assertEquals(0.25, c.getChildrenToReadyQueueWeight(), 0);
        assertEquals(0.5, c.getStaticShare(), 0);
        assertEquals(1.0, c.getLookaheadWeight(), 0);
        assertEquals(0.1, c.getLookaheadGapThreshold(), 0);
        assertEquals(SchedulerConfig.GapCondition.GAP_ABOVE_THRESHOLD, c.getGapCondition());
    }

    @Test
    public void toBuilder_roundTripsEveryValue() {
        SchedulerConfig c = SchedulerConfig.builder()
                .staticWeights(1, 2, 3).dynamicWeights(4, 5, 6, 7, 8)
                .staticShare(0.7).lookaheadWeight(0.3).lookaheadGapThreshold(0.05)
                .gapCondition(SchedulerConfig.GapCondition.ANY_GAP).build();
        assertEquals(c.toString(), c.toBuilder().build().toString());
    }

    @Test
    public void staticWeights_changeStaticScores() {
        SchedulerConfig cpOnly = SchedulerConfig.builder().staticWeights(1, 0, 0).build();
        TaskEngine engine = new TaskEngine(sample(), cpOnly);
        engine.initial();
        // critical paths: P=11 (max), X=6, A=1, B=10
        assertEquals(1.0, engine.getTask(1).getScore(), 1e-12);
        assertEquals(6.0 / 11, engine.getTask(2).getScore(), 1e-12);
        assertEquals(10.0 / 11, engine.getTask(4).getScore(), 1e-12);
    }

    @Test
    public void dynamicWeights_changeDynamicScore() {
        TaskEngine engine = new TaskEngine(sample());
        engine.initial();
        GraphTaskNode b = engine.getTask(4);
        SchedulerConfig durationOnly = SchedulerConfig.builder().dynamicWeights(0, 0, 0, 1, 0).build();
        // ready=2, workers=2 -> alpha=1 -> durationToReady = d/maxD = 1.0
        assertEquals(1.0, new DynamicScoreLogic(durationOnly).makeDynamicScore(b, 2, 2, 1.0, 10, 2), 1e-12);
        assertEquals(0.3, new DynamicScoreLogic().makeDynamicScore(b, 2, 2, 1.0, 10, 2), 1e-12);
    }

    @Test
    public void lookaheadWeightZero_followsBlendedScoreOnKnownCounterexample() {
        // With the default lookahead the scheduler picks A at t=1 (makespan 12);
        // with mu = 0 it keeps the higher blended score B (makespan 11).
        assertEquals(12, makespan(SchedulerConfig.DEFAULT));
        assertEquals(11, makespan(SchedulerConfig.builder().lookaheadWeight(0).build()));
        assertEquals(11, makespan(SchedulerConfig.builder().lookaheadGapThreshold(1.0).build()));
    }

    @Test
    public void invertedGapCondition_skipsLookaheadForLargeGaps() {
        SchedulerConfig inverted = SchedulerConfig.builder()
                .gapCondition(SchedulerConfig.GapCondition.GAP_AT_MOST_THRESHOLD).build();
        assertEquals(11, makespan(inverted));
    }

    @Test(expected = IllegalArgumentException.class)
    public void staticShareOutsideUnitInterval_isRejected() {
        SchedulerConfig.builder().staticShare(1.5).build();
    }

    private int makespan(SchedulerConfig config) {
        Controller c = new Controller(sample(), new LinkedList<>(Arrays.asList("W0", "W1")), false, config);
        c.initial();
        while (c.getTasksLeft() > 0) {
            c.workerFinishOnTime();
        }
        return c.getGlobalTime();
    }
}
