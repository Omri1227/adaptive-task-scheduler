package backend;

import org.junit.Test;

import java.util.*;

import static org.junit.Assert.assertEquals;

public class SimulationIdleAssignmentTest {

    private static GraphTaskNode node(int id, int duration) {
        return new GraphTaskNode(new Task("T" + id, id, duration, new LinkedList<>()));
    }

    /**
     * t=0, candidate A(5), other ready task B(5), one idle worker, nobody busy.
     * Original: no busy worker ever completes, so the idle worker is counted idle
     * for the whole window (5) plus the end-of-window penalty (5) = 10.
     * Fixed: the idle worker starts B at t=0, finishes at 5, so only the
     * end-of-window penalty (5) remains.
     */
    @Test
    public void idleWorkerStartsAvailableTaskAtDecisionTime() {
        GraphTaskNode a = node(1, 5);
        GraphTaskNode b = node(2, 5);
        PriorityQueue<GraphTaskNode> ready = new PriorityQueue<>((x, y) -> Double.compare(y.getScore(), x.getScore()));
        ready.add(b);
        PriorityQueue<Worker> employed = new PriorityQueue<>(Comparator.comparingInt(Worker::getTaskEstimatedEndTime));
        Queue<Worker> idle = new ArrayDeque<>(List.of(new Worker(1, "W1")));

        assertEquals(10.0, DynamicScoreLogic.unemploymentInDuration(a, ready, employed, idle, 0), 0);
        assertEquals(10.0, DynamicScoreLogic.unemploymentInDuration(a, ready, employed, idle, 0, false), 0);
        assertEquals(5.0, DynamicScoreLogic.unemploymentInDuration(a, ready, employed, idle, 0, true), 0);

        // inputs untouched
        assertEquals(1, ready.size());
        assertEquals(1, idle.size());
        assertEquals(0, employed.size());
    }

    @Test
    public void flagReachesLookaheadScore() {
        GraphTaskNode a = node(1, 5);
        GraphTaskNode b = node(2, 5);
        PriorityQueue<GraphTaskNode> ready = new PriorityQueue<>((x, y) -> Double.compare(y.getScore(), x.getScore()));
        ready.add(b);
        PriorityQueue<Worker> employed = new PriorityQueue<>(Comparator.comparingInt(Worker::getTaskEstimatedEndTime));
        Queue<Worker> idle = new ArrayDeque<>(List.of(new Worker(1, "W1")));
        SchedulerConfig fixed = SchedulerConfig.builder().simulationAssignsIdleWorkers(true).build();

        // 1 - idle / ((workers - 1) * duration) with 2 workers and duration 5
        assertEquals(1 - 10.0 / 5, new DynamicScoreLogic().makeLookAhead(a, ready, employed, idle, 2, 0), 1e-12);
        assertEquals(1 - 5.0 / 5, new DynamicScoreLogic(fixed).makeLookAhead(a, ready, employed, idle, 2, 0), 1e-12);
    }
}
