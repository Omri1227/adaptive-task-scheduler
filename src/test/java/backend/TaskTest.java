package backend;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.LinkedList;

public class TaskTest {

    @Test
    public void constructor_setsAllFieldsCorrectly() {
        LinkedList<Integer> deps = new LinkedList<>();
        deps.add(1);
        deps.add(2);

        Task task = new Task("TaskA", 10, 5, deps);

        assertEquals("TaskA", task.getName());
        assertEquals(10, task.getId());
        assertEquals(5, task.getDuration());
        assertEquals(deps, task.getDependsOnIDS());
    }

    @Test
    public void addDependsOn_addsDependency() {
        Task task = new Task("TaskA", 1, 3, new LinkedList<>());

        task.addDependsOn(5);

        assertTrue(task.getDependsOnIDS().contains(5));
        assertEquals(1, task.getDependsOnIDS().size());
    }

    @Test
    public void removeDependsOn_removesExistingDependency() {
        LinkedList<Integer> deps = new LinkedList<>();
        deps.add(3);

        Task task = new Task("TaskA", 1, 3, deps);
        task.removeDependsOn(3);

        assertFalse(task.getDependsOnIDS().contains(3));
        assertTrue(task.getDependsOnIDS().isEmpty());
    }

    @Test
    public void removeDependsOn_nonExistingDependency_doesNothing() {
        Task task = new Task("TaskA", 1, 3, new LinkedList<>());

        task.removeDependsOn(99);

        assertTrue(task.getDependsOnIDS().isEmpty());
    }
}