package backend;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.LinkedList;

public class WorkerTest {

    private GraphTaskNode node(int id){
        Task t = new Task("T"+id, id, id*10, new LinkedList<>());
        return new GraphTaskNode(t);
    }

    // ===== CONSTRUCTOR BASIC =====
    @Test
    public void constructor_initialStateCorrect() {
        Worker w = new Worker(1, "A");

        assertEquals(1, w.getId());
        assertEquals("A", w.getName());
        assertEquals(Worker.Status.UNEMPLOYMENT, w.getEmploymentStatus());
        assertNull(w.getDesk());
        assertEquals(0, w.getTaskEstimatedEndTime());
        assertEquals(0, w.getInsertionOrder());
        assertFalse(w.getToRemove());
        assertEquals(0, w.getUnemploymentTime(0));
    }

    // ===== GIVE TASK =====
    @Test
    public void giveTask_assignsDeskCorrectly() {
        Worker w = new Worker(1, "A");
        GraphTaskNode t = node(3); // duration = 30

        assertTrue(w.giveTask(t));

        assertEquals(t, w.getDesk());
        assertEquals(Worker.Status.EMPLOYMENT, w.getEmploymentStatus());
        assertEquals(30, w.getTaskEstimatedEndTime()); // corrected
    }

    @Test(expected = RuntimeException.class)
    public void giveTask_whenWorkerBusy_throws() {
        Worker w = new Worker(1, "A");
        w.giveTask(node(1));
        w.giveTask(node(2)); // should throw
    }

    // ===== ESTIMATED END =====
    @Test
    public void giveTask_setsEstimatedEndCorrectly() {
        Worker w = new Worker(1, "A");
        GraphTaskNode t = node(3);

        w.giveTask(t);

        assertEquals(30, w.getTaskEstimatedEndTime());
    }

    // ===== FINISH TASK =====
    @Test
    public void finishTask_basicFlow() {
        Worker w = new Worker(1, "A");
        GraphTaskNode t = node(3);

        w.giveTask(t);
        assertTrue(w.finishTask(50)); // finish execution

        assertNull(w.getDesk());
        assertEquals(Worker.Status.UNEMPLOYMENT, w.getEmploymentStatus());
        assertEquals(30, w.getTaskEstimatedEndTime()); // unchanged by finish
    }

    @Test
    public void finishTask_setsActualTime() {
        Worker w = new Worker(1, "A");
        GraphTaskNode t = node(3);

        w.giveTask(t);
        w.finishTask(100);

        assertEquals(100, t.getActualFinishTime());
    }

    @Test(expected = RuntimeException.class)
    public void finishTask_withoutWork_throws() {
        Worker w = new Worker(1, "A");
        w.finishTask(20);
    }

    // ===== UNEMPLOYMENT TRACKING =====
    @Test
    public void unemploymentTracksCorrectly() {
        Worker w = new Worker(1, "A");
        w.setUnemploymentTime(10);

        assertEquals(0, w.getUnemploymentTime(10));
        assertEquals(10, w.getUnemploymentTime(20));
        assertEquals(20, w.getUnemploymentTime(30));
    }

    // ===== INSERTION ORDER =====
    @Test
    public void insertionOrderSetGet() {
        Worker w = new Worker(1, "A");
        w.setInsertionOrder(77);

        assertEquals(77, w.getInsertionOrder());
    }

    // ===== TO REMOVE FLAG =====
    @Test
    public void toRemoveFlagWorks() {
        Worker w = new Worker(1, "A");
        assertFalse(w.getToRemove());

        w.setToRemove();
        assertTrue(w.getToRemove());
    }

    // ===== COPY CONSTRUCTOR =====
    @Test
    public void copyConstructor_copiesIdNameState() {
        Worker w = new Worker(1, "A");
        w.setInsertionOrder(99);
        w.setUnemploymentTime(500);

        Worker copy = new Worker(w);

        assertEquals(w.getId(), copy.getId());
        assertEquals(w.getName(), copy.getName());
        assertEquals(w.getInsertionOrder(), copy.getInsertionOrder());
        assertEquals(w.getUnemploymentTime(600), copy.getUnemploymentTime(600));
    }

    @Test
    public void copyConstructor_deepCopiesDesk() {
        Worker w = new Worker(1, "A");
        GraphTaskNode original = node(4);
        w.setDesk(original);

        Worker copy = new Worker(w);

        assertNotSame(original, copy.getDesk());
        assertEquals(original.getId(), copy.getDesk().getId());
    }

    @Test
    public void copyConstructor_handlesNullDesk() {
        Worker w = new Worker(1, "A");
        Worker copy = new Worker(w);

        assertNull(copy.getDesk());
    }

    @Test
    public void copyConstructor_doesNotCopyDrawerHistory() {
        Worker w = new Worker(1, "A");

        w.giveTask(node(3));
        w.finishTask(10);

        Worker copy = new Worker(w);

        assertNull(copy.getDesk());
    }

    // ===== EQUALS / HASHCODE =====
    @Test
    public void equals_sameId_true() {
        Worker w1 = new Worker(7, "A");
        Worker w2 = new Worker(7, "B");

        assertEquals(w1, w2);
        assertEquals(w1.hashCode(), w2.hashCode());
    }

    @Test
    public void equals_differentId_false() {
        Worker w1 = new Worker(7, "A");
        Worker w2 = new Worker(8, "A");

        assertNotEquals(w1, w2);
    }

    @Test
    public void equals_withNull_false() {
        Worker w1 = new Worker(7, "A");
        assertNotEquals(w1, null);
    }

    @Test
    public void equals_withDifferentClass_false() {
        Worker w1 = new Worker(7, "A");
        assertNotEquals(w1, "string");
    }

    // ===== STATUS CHECKS =====
    @Test
    public void giveTask_setsEmploymentStatus() {
        Worker w = new Worker(1, "A");

        w.giveTask(node(1));

        assertEquals(Worker.Status.EMPLOYMENT, w.getEmploymentStatus());
    }

    @Test
    public void finishTask_setsUnemploymentStatus() {
        Worker w = new Worker(1, "A");

        w.giveTask(node(2));
        w.finishTask(100);

        assertEquals(Worker.Status.UNEMPLOYMENT, w.getEmploymentStatus());
    }
}