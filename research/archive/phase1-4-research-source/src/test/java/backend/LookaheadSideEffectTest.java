package backend;

import org.junit.Test;

import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class LookaheadSideEffectTest {

    @Test
    public void lookahead_doesNotModifyRealIdleWorkers() {
        LinkedList<Task> tasks = new LinkedList<>(Arrays.asList(
                new Task("A", 1, 3, new LinkedList<>()),
                new Task("B", 2, 5, new LinkedList<>()),
                new Task("C", 3, 2, new LinkedList<>(List.of(1)))));
        TaskEngine engine = new TaskEngine(tasks);
        engine.initial();

        Worker idle = new Worker(7, "idle");
        idle.setUnemploymentTime(4);
        Queue<Worker> unemployed = new ArrayDeque<>(List.of(idle));
        PriorityQueue<Worker> employed = new PriorityQueue<>(Comparator.comparingInt(Worker::getTaskEstimatedEndTime));
        PriorityQueue<GraphTaskNode> ready = new PriorityQueue<>(engine.getReadyPQ());
        GraphTaskNode a = engine.getTask(1);
        ready.remove(a);

        new DynamicScoreLogic().makeLookAhead(a, ready, employed, unemployed, 2, 10);

        assertEquals(4, idle.copyUnemployedTime());
        assertNull(idle.getDesk());
        assertEquals(1, unemployed.size());
        assertEquals(2, engine.getReadyPQ().size());
    }
}
