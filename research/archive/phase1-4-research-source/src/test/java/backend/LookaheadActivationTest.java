package backend;

import org.junit.Test;

import java.util.*;

import static org.junit.Assert.assertEquals;

public class LookaheadActivationTest {

    // P(1) -> A(1), P -> B(10), X(6) independent
    private static LinkedList<Task> tasks() {
        return new LinkedList<>(Arrays.asList(
                new Task("P", 1, 1, new LinkedList<>()),
                new Task("X", 2, 6, new LinkedList<>()),
                new Task("A", 3, 1, new LinkedList<>(List.of(1))),
                new Task("B", 4, 10, new LinkedList<>(List.of(1)))));
    }

    /**
     * State at t=1: P finished, X runs until 6 on another worker, one more worker
     * is idle, ready = {A, B}. Two ready tasks and two free workers: the decision is
     * not contested. The legacy lookahead still runs and flips the choice to A.
     */
    private static int decide(SchedulerConfig config) {
        TaskEngine engine = new TaskEngine(tasks(), config);
        engine.initial();
        GraphTaskNode p = engine.getReadyPQ().poll();
        GraphTaskNode x = engine.getReadyPQ().poll();
        assertEquals(1, p.getId());
        engine.taskCompleted(p);

        Worker running = new Worker(1, "WX");
        running.setDesk(x);
        running.setTaskEstimatedEndTime(6);
        PriorityQueue<Worker> employed = new PriorityQueue<>(Comparator.comparingInt(Worker::getTaskEstimatedEndTime));
        employed.add(running);
        Queue<Worker> idle = new ArrayDeque<>(List.of(new Worker(2, "W2")));
        return engine.getNextTask(3, employed, idle, 1).getId();
    }

    @Test
    public void legacyLookahead_runsOnUncontestedDecision() {
        assertEquals(3, decide(SchedulerConfig.DEFAULT));
    }

    @Test
    public void contestedOnly_skipsLookaheadWhenEveryReadyTaskCanStart() {
        assertEquals(4, decide(SchedulerConfig.builder().contestedDecisionsOnly(true).build()));
    }

    @Test
    public void noLookaheadMode_keepsBlendedOrder() {
        assertEquals(4, decide(SchedulerConfig.builder().lookaheadMode(SchedulerConfig.LookaheadMode.NONE).build()));
    }

    @Test
    public void disablingLegacyLoadGate_keepsLookaheadActiveHere() {
        assertEquals(3, decide(SchedulerConfig.builder().legacyLoadGate(false).build()));
    }

    @Test
    public void boundedScore_changesOutcomeOnKnownCounterexample() {
        // Two workers, t=1: LA(A) = 1.0, LA(B) = -0.4, blended A = 0.0447, B = 0.4467.
        // mu = 0.3 unbounded: A 0.3447 > B 0.3267 -> A (makespan 12).
        // mu = 0.3 bounded:   B's score is clamped to 0 -> B 0.4467 > A 0.3447 -> B (makespan 11).
        SchedulerConfig mu = SchedulerConfig.builder().lookaheadWeight(0.3).build();
        assertEquals(12, makespan(mu));
        assertEquals(11, makespan(mu.toBuilder().boundedLookaheadScore(true).build()));
    }

    private static int makespan(SchedulerConfig config) {
        Controller c = new Controller(tasks(), new LinkedList<>(List.of("W0", "W1")), false, config);
        c.initial();
        while (c.getTasksLeft() > 0) c.workerFinishOnTime();
        return c.getGlobalTime();
    }
}
