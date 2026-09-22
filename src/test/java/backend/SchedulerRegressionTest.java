package backend;

import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

public class SchedulerRegressionTest {

    private Task task(int id, int duration, Integer... dependencies) {
        return new Task(
                "T" + id,
                id,
                duration,
                new LinkedList<>(Arrays.asList(dependencies))
        );
    }

    @Test
    public void initial_doesNotAssignSameTaskToMultipleWorkers() {
        LinkedList<Task> tasks = new LinkedList<>();

        tasks.add(task(1, 5));
        tasks.add(task(2, 4));
        tasks.add(task(3, 3));

        LinkedList<String> workers =
                new LinkedList<>(Arrays.asList("W1", "W2", "W3"));

        Controller controller =
                new Controller(tasks, workers);

        controller.initial();

        List<Worker> activeWorkers =
                controller.getActiveWorkers();

        assertEquals(3, activeWorkers.size());

        Set<Integer> assignedTasks = new HashSet<>();

        for (Worker worker : activeWorkers) {
            assertNotNull(worker.getDesk());

            assertTrue(
                    "Task was assigned to more than one worker",
                    assignedTasks.add(worker.getDesk().getId())
            );
        }

        assertTrue(controller.getTaskEngine().getReadyPQ().isEmpty());
    }


    @Test
    public void dependency_isNotAssignedBeforeParentCompletes() {
        LinkedList<Task> tasks = new LinkedList<>();

        Task parent = task(1, 5);
        Task child = task(2, 5, 1);

        tasks.add(parent);
        tasks.add(child);

        LinkedList<String> workers =
                new LinkedList<>(Arrays.asList("W1", "W2"));

        Controller controller =
                new Controller(tasks, workers);

        controller.initial();

        assertEquals(1, controller.getActiveWorkers().size());
        assertEquals(1, controller.getIdleWorkers().size());

        assertEquals(
                1,
                controller.getActiveWorkers().get(0).getDesk().getId()
        );

        controller.workerFinishOnTime();

        assertEquals(1, controller.getActiveWorkers().size());

        assertEquals(
                2,
                controller.getActiveWorkers().get(0).getDesk().getId()
        );
    }


    @Test
    public void invalidEarlyFinish_doesNotRemoveWorker() {
        LinkedList<Task> tasks = new LinkedList<>();
        tasks.add(task(1, 10));

        LinkedList<String> workers =
                new LinkedList<>(Collections.singletonList("W1"));

        Controller controller =
                new Controller(tasks, workers);

        controller.initial();

        assertEquals(1, controller.getActiveWorkers().size());

        Worker worker =
                controller.getActiveWorkers().get(0);

        int workerId = worker.getId();

        try {
            controller.workerFinishEarly(workerId, 11);
            fail("Expected invalid finish time to throw");
        } catch (RuntimeException expected) {
            // expected
        }

        assertEquals(1, controller.getActiveWorkers().size());
        assertEquals(1, controller.getTasksLeft());

        Worker stillActive =
                controller.getActiveWorkers().get(0);

        assertEquals(workerId, stillActive.getId());
        assertNotNull(stillActive.getDesk());
        assertEquals(1, stillActive.getDesk().getId());
    }


    @Test
    public void cycle_isRejected() {
        LinkedList<Task> tasks = new LinkedList<>();

        Task a = task(1, 5, 2);
        Task b = task(2, 5, 1);

        tasks.add(a);
        tasks.add(b);

        try {
            TaskEngine engine = new TaskEngine(tasks);
            engine.initial();

            fail("Expected cyclic graph to be rejected");

        } catch (RuntimeException expected) {
            // expected
        }
    }


    @Test
    public void independentTasks_haveFiniteScores() {
        LinkedList<Task> tasks = new LinkedList<>();

        tasks.add(task(1, 5));
        tasks.add(task(2, 10));

        TaskEngine engine =
                new TaskEngine(tasks);

        engine.initial();

        for (GraphTaskNode node : engine.getTopologicalSort()) {
            assertFalse(Double.isNaN(node.getScore()));
            assertFalse(Double.isInfinite(node.getScore()));
        }
    }


    @Test
    public void duplicateWorkerId_isRejected() {
        LinkedList<Task> tasks = new LinkedList<>();

        LinkedList<String> workers =
                new LinkedList<>(Collections.singletonList("W1"));

        Controller controller =
                new Controller(tasks, workers);

        int workersBefore =
                controller.getActiveWorkers().size()
                        + controller.getIdleWorkers().size();

        try {
            // העובד שנוצר ב-constructor הוא ID 0
            controller.addWorker(0, "Duplicate");

            fail("Expected duplicate worker ID to be rejected");

        } catch (RuntimeException expected) {
            // expected
        }

        int workersAfter =
                controller.getActiveWorkers().size()
                        + controller.getIdleWorkers().size();

        assertEquals(workersBefore, workersAfter);
    }


    @Test
    public void addingWorker_dispatchesReadyTaskImmediately() {
        LinkedList<Task> tasks = new LinkedList<>();
        tasks.add(task(1, 5));

        Controller controller =
                new Controller(tasks, new LinkedList<>());

        controller.initial();

        assertEquals(1, controller.getTaskEngine().getReadyPQ().size());
        assertEquals(0, controller.getActiveWorkers().size());

        controller.addWorker(100, "New Worker");

        assertEquals(1, controller.getActiveWorkers().size());
        assertTrue(controller.getTaskEngine().getReadyPQ().isEmpty());

        assertEquals(
                1,
                controller.getActiveWorkers().get(0).getDesk().getId()
        );
    }
}