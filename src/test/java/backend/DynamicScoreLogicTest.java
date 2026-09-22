package backend;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.util.stream.Collectors;

public class DynamicScoreLogicTest {

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

        Worker w1 = new Worker(1, "w1");
        Worker w2 = new Worker(2, "w2");
        Worker w3 = new Worker(3, "w3");
        Worker w4 = new Worker(4, "w4");
        Worker w5 = new Worker(5, "w5");
        Worker w6 = new Worker(6, "w6");

        Comparator<Worker> workerComparator = (W1, W2) -> {
            int cmp = Integer.compare(W1.getTaskEstimatedEndTime(), W2.getTaskEstimatedEndTime());
            if (cmp != 0) return cmp;
            return Long.compare(W1.getInsertionOrder(), W2.getInsertionOrder());
        };
        PriorityQueue<Worker> employed = new PriorityQueue<>(workerComparator);
        Queue<Worker> unemployed = new ArrayDeque<>();


        w1.giveTask(taskEngine.getTask(a1.getId()));
        w2.giveTask(taskEngine.getTask(a2.getId()));
        w3.giveTask(taskEngine.getTask(a3.getId()));
        w4.giveTask(taskEngine.getTask(a4.getId()));
        employed.add(w1);
        employed.add(w2);
        employed.add(w3);
        employed.add(w4);








        unemployed.add(w5);
        unemployed.add(w6);

        int globalTime = 0;
        int maxDuration = 10;

        DynamicScoreLogic score = new DynamicScoreLogic();
        GraphTaskNode current1 = taskEngine.getReadyPQ().poll();
        System.out.println(current1.getId());
        System.out.println("open to block : " + score.openToBlockRatio(current1));
        System.out.println("open child importance : " + score.openChildrenImportance(current1));
        System.out.println("duration to pq size : 1)this duration : " +
                current1.getDuration() + ", 2)pq size : " +taskEngine.getReadyPQ().size() +
                ", 3) 7 workers, score: " +score.durationToRPQSize(current1, maxDuration,
                taskEngine.getReadyPQ().size(), 7));
        System.out.println("child to pq size: 1)this childs : " +score.openChildrenCount(current1)
                + ", 2)pq size : " + taskEngine.getReadyPQ().size() + ", score: " +
                score.openChildToRPQSize(current1, score.openChildrenCount(current1),
                        taskEngine.getReadyPQ().size(), 3, 4));


        GraphTaskNode current2 = taskEngine.getReadyPQ().poll();
        System.out.println(current2.getId());
        System.out.println("open to block : " + score.openToBlockRatio(current2));
        System.out.println("open child importance : " + score.openChildrenImportance(current2));
        System.out.println("duration to pq size : 1)this duration : " +
                current2.getDuration() + ", 2)pq size : " +(taskEngine.getReadyPQ().size()+1) +
                ", 3) 7 workers, score: " +score.durationToRPQSize(current2, maxDuration,
                taskEngine.getReadyPQ().size(), 7));
        System.out.println("child to pq size: 1)this childs : " +score.openChildrenCount(current2)
                + ", 2)pq size : " + taskEngine.getReadyPQ().size() + ", score: " +
                score.openChildToRPQSize(current2, score.openChildrenCount(current2),
                        taskEngine.getReadyPQ().size(), 3, 4));
        taskEngine.taskCompleted(current2);
        taskEngine.taskCompleted(current1);


        GraphTaskNode current3 = taskEngine.getReadyPQ().poll();
        System.out.println(current3.getId());
        System.out.println("open to block : " + score.openToBlockRatio(current3));
        System.out.println("open child importance : " + score.openChildrenImportance(current3));
        System.out.println("duration to pq size : 1)this duration : " +
                current3.getDuration() + ", 2)pq size : " +taskEngine.getReadyPQ().size() +
                ", 3) 7 workers, score: " +score.durationToRPQSize(current3, maxDuration,
                taskEngine.getReadyPQ().size(), 7));
        System.out.println("child to pq size: 1)this childs : " +score.openChildrenCount(current3)
                + ", 2)pq size : " + taskEngine.getReadyPQ().size() + ", score: " +
                score.openChildToRPQSize(current3, score.openChildrenCount(current3),
                        taskEngine.getReadyPQ().size(), 3, 4));

        GraphTaskNode current4 = taskEngine.getReadyPQ().poll();
        System.out.println(current4.getId());
        System.out.println("duration to pq size : 1)this duration : " +
                current4.getDuration() + ", 2)pq size : " +(taskEngine.getReadyPQ().size()+1) +
                ", 3) 7 workers, score: " +score.durationToRPQSize(current4, maxDuration,
                taskEngine.getReadyPQ().size(), 7));
        System.out.println("child to pq size: 1)this childs : " +score.openChildrenCount(current4)
                + ", 2)pq size : " + taskEngine.getReadyPQ().size() + ", score: " +
                score.openChildToRPQSize(current4, score.openChildrenCount(current4),
                        taskEngine.getReadyPQ().size(), 3, 4));
        taskEngine.taskCompleted(current3);
        taskEngine.taskCompleted(current4);



        System.out.println("current pq :");
        for (GraphTaskNode task : taskEngine.getReadyPQ()){
            System.out.println(task.getId());
            System.out.println("7 workers score: " +score.makeDynamicScore(
                            task,
                            taskEngine.getReadyPQ().size(),
                            7,
                            1,
                            10,
                            4
                    ));
            System.out.println("4 workers score: " +score.makeDynamicScore(
                    task,
                    taskEngine.getReadyPQ().size(),
                    4,
                    1,
                    10,
                    4
            ));
        }

        taskEngine.taskCompleted(taskEngine.getReadyPQ().poll());
        taskEngine.taskCompleted(taskEngine.getReadyPQ().poll());
        taskEngine.taskCompleted(taskEngine.getReadyPQ().poll());
        taskEngine.taskCompleted(taskEngine.getReadyPQ().poll());

        System.out.println("current pq :");
        for (GraphTaskNode task : taskEngine.getReadyPQ()){
            System.out.println(task.getId());

            System.out.println("7 workers score: " +score.makeDynamicScore(
                    task,
                    1,
                    7,
                    1,
                    10,
                    4
            ));
        }

        GraphTaskNode next = taskEngine.getNextTask(7, employed, unemployed,globalTime);
        System.out.println(next.getId());
        taskEngine.getReadyPQ().poll();
        GraphTaskNode next2 = taskEngine.getNextTask(7, employed, unemployed,globalTime);
        System.out.println(next2.getId());
        taskEngine.getReadyPQ().poll();
        GraphTaskNode next3 = taskEngine.getNextTask(7, employed, unemployed,globalTime);
        System.out.println(next3.getId());
        taskEngine.getReadyPQ().poll();












    }
}