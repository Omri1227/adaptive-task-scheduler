package backend;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.util.stream.Collectors;

public class TaskEngineIntegrationTest {

    // ============================================================
    // Helpers
    // ============================================================

    private Task t(int id, int dur, Integer... deps) {
        return new Task("T" + id, id, dur,
                new LinkedList<>(Arrays.asList(deps)));
    }

    private Task find(List<Task> L, int id) {
        for (Task t : L) if (t.getId() == id) return t;
        return null;
    }

    private GraphTaskNode n(TaskEngine e, int id) {
        return e.getTask(id);
    }

    @Test
    public void taskEngine_20Nodes() {
        LinkedList<Task> tasks = new LinkedList<>();

        Task a1 = new Task("a1", 1, 2, new LinkedList<>());
        Task a2 = new Task("a2", 2, 3, new LinkedList<>());
        Task a3 = new Task("a3", 3, 4, new LinkedList<>());
        Task a4 = new Task("a4", 4, 10, new LinkedList<>());

        Task b5 = new Task("b5", 5, 1, new LinkedList<>());
        b5.addDependsOn(1);
        b5.addDependsOn(2);
        Task b6 = new Task("b6", 6, 4, new LinkedList<>());
        b6.addDependsOn(3);
        Task b7 = new Task("b7", 7, 3, new LinkedList<>());
        b7.addDependsOn(3);
        Task b8 = new Task("b8", 8, 5, new LinkedList<>());
        b8.addDependsOn(3);
        Task b9 = new Task("b9", 9, 5, new LinkedList<>());
        b9.addDependsOn(3);

        Task c10 = new Task("c10", 10, 2, new LinkedList<>());
        c10.addDependsOn(5);
        Task c11 = new Task("c11", 11, 5, new LinkedList<>());
        c11.addDependsOn(5);
        c11.addDependsOn(6);
        Task c12 = new Task("c12", 12, 3, new LinkedList<>());
        c12.addDependsOn(7);
        Task c13 = new Task("c13", 13, 1, new LinkedList<>());
        c13.addDependsOn(7);

        Task d14 = new Task("d14", 14, 3, new LinkedList<>());
        d14.addDependsOn(10);
        Task d15 = new Task("d15", 15, 6, new LinkedList<>());
        d15.addDependsOn(10);
        Task d16 = new Task("d16", 16, 8, new LinkedList<>());
        d16.addDependsOn(11);
        d16.addDependsOn(13);
        Task d17 = new Task("d17", 17, 9, new LinkedList<>());
        d17.addDependsOn(13);
        Task d18 = new Task("d18", 18, 2, new LinkedList<>());
        d18.addDependsOn(13);

        Task e19 = new Task("e19", 19, 6, new LinkedList<>());
        e19.addDependsOn(11);
        e19.addDependsOn(15);

        Task f20 = new Task("f20", 20, 3, new LinkedList<>());
        f20.addDependsOn(14);
        f20.addDependsOn(18);
        f20.addDependsOn(19);

        tasks.add(a1);
        tasks.add(a2);
        tasks.add(a3);
        tasks.add(a4);
        tasks.add(b5);
        tasks.add(b6);
        tasks.add(b7);
        tasks.add(b8);
        tasks.add(b9);
        tasks.add(c10);
        tasks.add(c11);
        tasks.add(c12);
        tasks.add(c13);
        tasks.add(d14);
        tasks.add(d15);
        tasks.add(d16);
        tasks.add(d17);
        tasks.add(d18);
        tasks.add(e19);
        tasks.add(f20);

        TaskEngine taskEngine = new TaskEngine(tasks);
        taskEngine.initial();
        for (GraphTaskNode graphTaskNode : taskEngine.getTopologicalSort()) {
            System.out.println(graphTaskNode.getId());
            System.out.println(graphTaskNode.getScore());
        }
        System.out.println("ready pq size: " + taskEngine.getReadyPQ().size());
        taskEngine.taskCompleted(taskEngine.getReadyPQ().poll());
        System.out.println("ready pq size: " + taskEngine.getReadyPQ().size());
        System.out.println("current pq tasks: ");
        for (GraphTaskNode node : taskEngine.getReadyPQ()){
            System.out.println(node.getId());
        }
        taskEngine.taskCompleted(taskEngine.getReadyPQ().poll());
        System.out.println("ready pq size: " + taskEngine.getReadyPQ().size());
        System.out.println("current pq tasks: ");
        for (GraphTaskNode node : taskEngine.getReadyPQ()){
            System.out.println(node.getId());
        }
        taskEngine.taskCompleted(taskEngine.getReadyPQ().poll());
        System.out.println("ready pq size: " + taskEngine.getReadyPQ().size());
        System.out.println("current pq tasks: ");
        for (GraphTaskNode node : taskEngine.getReadyPQ()){
            System.out.println(node.getId());
        }

        Task f21 = new Task("f21", 21, 3, new LinkedList<>());
        f21.addDependsOn(11);
        LinkedList<Integer> f21child = new LinkedList<>();
        f21child.add(20);
        LinkedList<Task> newTasks = taskEngine.prepareAdd(f21, f21child);
        TaskEngine newTaskEngine = new TaskEngine(newTasks);
        newTaskEngine.initial();


        for (GraphTaskNode graphTaskNode : newTaskEngine.getTopologicalSort()) {
            System.out.println(graphTaskNode.getId());
            System.out.println(graphTaskNode.getScore());
        }

        taskEngine.taskCompleted(taskEngine.getReadyPQ().poll());
        System.out.println("ready pq size: " + taskEngine.getReadyPQ().size());
        System.out.println("current pq tasks: ");
        for (GraphTaskNode node : taskEngine.getReadyPQ()){
            System.out.println(node.getId());
        }
        taskEngine.taskCompleted(taskEngine.getReadyPQ().poll());
        System.out.println("ready pq size: " + taskEngine.getReadyPQ().size());
        System.out.println("current pq tasks: ");
        for (GraphTaskNode node : taskEngine.getReadyPQ()){
            System.out.println(node.getId());
        }


        LinkedList<Task> newTasks2 = newTaskEngine.prepareRemove(e19);
        TaskEngine newTaskEngine2 = new TaskEngine(newTasks2);
        newTaskEngine2.initial();

        for (GraphTaskNode graphTaskNode : newTaskEngine2.getTopologicalSort()) {
            System.out.println(graphTaskNode.getId());
            System.out.println(graphTaskNode.getScore());
        }

        taskEngine.taskCompleted(taskEngine.getReadyPQ().poll());
        System.out.println("ready pq size: " + taskEngine.getReadyPQ().size());
        System.out.println("current pq tasks: ");
        for (GraphTaskNode node : taskEngine.getReadyPQ()){
            System.out.println(node.getId());
        }

        Comparator<Worker> workerComparator = (w1, w2) -> {
            int cmp = Integer.compare(w1.getTaskEstimatedEndTime(), w2.getTaskEstimatedEndTime());
            if (cmp != 0) return cmp;
            return Long.compare(w1.getInsertionOrder(), w2.getInsertionOrder());
        };
        PriorityQueue<Worker> employed = new PriorityQueue<>(workerComparator);
        Queue<Worker> unEmployed = new ArrayDeque<>();
        int globalTime = 0;
        int workersNum = 3;

        Worker w1 = new Worker(1, "w1");
        Worker w2 = new Worker(2, "w2");
        Worker w3 = new Worker(3, "w3");

        w1.giveTask(newTaskEngine2.getReadyPQ().poll());
        w2.giveTask(newTaskEngine2.getReadyPQ().poll());
        employed.add(w1);
        employed.add(w2);

        System.out.println("ready pq size: " + newTaskEngine2.getReadyPQ().size());
        System.out.println("current pq tasks: ");
        for (GraphTaskNode node : newTaskEngine2.getReadyPQ()){
            System.out.println(node.getId());
        }

        GraphTaskNode next =newTaskEngine2.getNextTask(workersNum, employed, unEmployed, globalTime);
        System.out.println("chose: " + next.getId());
    }
}















