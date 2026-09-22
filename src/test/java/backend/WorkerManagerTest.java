package backend;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.LinkedList;
import java.util.Queue;
import java.util.PriorityQueue;
import java.util.Comparator;

public class WorkerManagerTest {

    private GraphTaskNode node(int id) {
        Task t = new Task("T" + id, id, id * 10, new LinkedList<>());
        return new GraphTaskNode(t);
    }

    private WorkerManager buildManager(int n) {
        LinkedList<String> names = new LinkedList<>();
        for (int i = 0; i < n; i++) {
            names.add("W" + i);
        }
        return new WorkerManager(names);
    }

    // ===== CONSTRUCTOR TESTS =====

    @Test
    public void constructor_initializesCorrectly() {
        WorkerManager manager = buildManager(3);

        assertEquals(3, manager.getSize());
        assertEquals(3, manager.getUnemploymentsQ().size());
        assertTrue(manager.getEmploymentsQ().isEmpty());
    }

    // ===== GET TOP UNEMPLOYED =====

    @Test
    public void getTopUnemployed_returnsFIFO() {
        WorkerManager manager = buildManager(3);

        Worker w0 = manager.getTopUnemployed();
        Worker w1 = manager.getTopUnemployed();
        Worker w2 = manager.getTopUnemployed();

        assertEquals(0, w0.getId());
        assertEquals(1, w1.getId());
        assertEquals(2, w2.getId());
    }

    @Test(expected = RuntimeException.class)
    public void getTopUnemployed_empty_throws() {
        WorkerManager manager = buildManager(1);
        manager.getTopUnemployed();
        manager.getTopUnemployed(); // throws
    }

    // ===== GIVE NEW TASK =====

    @Test
    public void giveNewTask_workerMovesToEmployedQueue() {
        WorkerManager manager = buildManager(2);

        Worker w = manager.getTopUnemployed();
        manager.giveNewTask(w, node(1));

        assertEquals(1, manager.getEmploymentsQ().size());
        assertEquals(1, manager.getUnemploymentsQ().size());
        assertEquals(2, manager.getSize()); // unchanged size
    }

    // ===== GET TOP EMPLOYED =====

    @Test(expected = RuntimeException.class)
    public void getTopEmployed_empty_throws() {
        WorkerManager manager = buildManager(2);
        manager.getTopEmployed();
    }

    @Test
    public void getTopEmployed_returnsByEndTimeThenOrder() {
        WorkerManager manager = buildManager(2);

        Worker w1 = manager.getTopUnemployed();
        Worker w2 = manager.getTopUnemployed();

        GraphTaskNode fast = node(1);  // duration = 10
        GraphTaskNode slow = node(3);  // duration = 30

        manager.giveNewTask(w1, slow);
        manager.giveNewTask(w2, fast);

        Worker first = manager.getTopEmployed();
        assertEquals(w2, first);

        Worker second = manager.getTopEmployed();
        assertEquals(w1, second);
    }

    // ===== getNextEndTime =====

    @Test
    public void getNextEndTime_returnsEarliestEndTime() {
        WorkerManager manager = buildManager(2);

        Worker w1 = manager.getTopUnemployed();
        Worker w2 = manager.getTopUnemployed();

        manager.giveNewTask(w1, node(3));
        manager.giveNewTask(w2, node(1));

        assertEquals(10, manager.getNextEndTime());
    }

    @Test(expected = RuntimeException.class)
    public void getNextEndTime_empty_throws() {
        WorkerManager manager = buildManager(1);
        manager.getNextEndTime();
    }

    // ===== getEmployedFromPQbyID =====

    @Test
    public void getEmployedFromPQbyID_valid() {
        WorkerManager manager = buildManager(2);
        Worker w = manager.getTopUnemployed();

        manager.giveNewTask(w, node(2));

        Worker fetched = manager.getEmployedFromPQbyID(w.getId());
        assertEquals(w, fetched);

        try {
            manager.getEmployedFromPQbyID(w.getId());
            fail("Expected unemployed exception");
        } catch (RuntimeException e) {
            assertEquals("ID unemployed now", e.getMessage());
        }
    }

    @Test(expected = RuntimeException.class)
    public void getEmployedFromPQbyID_invalid_throws() {
        WorkerManager manager = buildManager(1);
        manager.getEmployedFromPQbyID(999);
    }

    // ===== SIZE TESTS =====

    @Test
    public void addUnemployed_increasesSize() {
        WorkerManager manager = buildManager(0);

        assertEquals(0, manager.getSize());

        Worker newW = new Worker(7, "AAA");
        manager.addUnemployed(newW);

        assertEquals(1, manager.getSize());
        assertEquals(newW, manager.getUnemploymentsQ().peek());
    }

    @Test
    public void removeUnemployed_removesFromAll() {
        WorkerManager manager = buildManager(3);

        assertEquals(3, manager.getSize());

        boolean result = manager.remove(0);
        assertTrue(result);

        assertEquals(2, manager.getSize());
        assertEquals(2, manager.getUnemploymentsQ().size());
    }

    @Test
    public void removeEmployed_marksFirstCallKeepsSize() {
        WorkerManager manager = buildManager(2);

        Worker w = manager.getTopUnemployed();
        manager.giveNewTask(w, node(2));

        int sizeBefore = manager.getSize();
        boolean result = manager.remove(w.getId());

        assertFalse(result);
        assertEquals(sizeBefore, manager.getSize());
        assertTrue(w.getToRemove());
    }

    @Test
    public void delete_reducesSize() {
        WorkerManager manager = buildManager(1);

        Worker w = manager.getTopUnemployed();
        manager.remove(w.getId()); // direct delete path

        assertEquals(0, manager.getSize());
    }

    // ===== MISC LOGIC =====

    @Test
    public void isTaskInProgress_detectsCorrectly() {
        WorkerManager manager = buildManager(1);

        Worker w = manager.getTopUnemployed();
        GraphTaskNode t = node(1);

        manager.giveNewTask(w, t);

        assertTrue(manager.isTaskInProgress(t));
        assertFalse(manager.isTaskInProgress(node(5)));
    }

    @Test
    public void getEmploymentsAsList_returnsSnapshot() {
        WorkerManager manager = buildManager(2);

        Worker w1 = manager.getTopUnemployed();
        Worker w2 = manager.getTopUnemployed();

        manager.giveNewTask(w1, node(3));
        manager.giveNewTask(w2, node(1));

        assertEquals(2, manager.getEmploymentsAsList().size());
    }

    @Test
    public void reBuildPQ_rebuildsCorrectly() {
        WorkerManager manager = buildManager(2);

        Worker w1 = new Worker(6, "A");
        Worker w2 = new Worker(7, "B");

        w1.giveTask(node(3));
        w2.giveTask(node(1));

        LinkedList<Worker> list = new LinkedList<>();
        list.add(w1);
        list.add(w2);

        manager.reBuildPQ(list);

        assertEquals(2, manager.getEmploymentsQ().size());
        assertEquals(w2, manager.getTopEmployed());
    }
}