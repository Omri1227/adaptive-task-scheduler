package backend;

import org.junit.Test;

import java.util.*;

public class ControllerAdditionalTest {

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

        LinkedList<String> workers = new LinkedList<>();

        workers.addLast("w1");
        workers.addLast("w2");
        workers.addLast("w3");

        Controller controller = new Controller(tasks, workers);
        controller.initial();
        TaskEngine taskEngine = controller.getTaskEngine();
        WorkerManager workerManager = controller.getWorkerManager();


        for (GraphTaskNode task : taskEngine.getTopologicalSort()) {
            System.out.println(task.getId());
            System.out.println(task.getScore());
        }
        for (Worker worker : workerManager.getUnemploymentsQ()) {
            System.out.println("unemployed : " + worker.getId());
        }
        for (Worker worker : workerManager.getEmploymentsQ()) {
            System.out.println("employed : " + worker.getId());
            System.out.println(worker.getDesk().getId());
        }

        int counter = 1;

        while (controller.getTasksLeft() > 15) {
            System.out.println(counter + " task completed!");
            controller.workerFinishOnTime();
            for (Worker worker : workerManager.getUnemploymentsQ()) {
                System.out.println("unemployed : " + worker.getId());
            }
            for (Worker worker : workerManager.getEmploymentsQ()) {
                System.out.println("employed : " + worker.getId());
                System.out.println(worker.getDesk().getId());
            }
            System.out.println(controller.getTasksLeft() + " tasks left");
            System.out.println(controller.getTasksCompleted() + " tasks left");
            System.out.println(controller.getGlobalTime() + " global time");
            counter++;
        }
        controller.workerFinishEarly(2, 7);
        System.out.println("worker 2 finished early!!!!!" + controller.getGlobalTime() + "time");
        for (Worker worker : workerManager.getEmploymentsQ()) {
            System.out.println("employed : " + worker.getId());
            System.out.println(worker.getDesk().getId());
        }


        while (controller.getTasksLeft() > 10) {
            System.out.println(counter + " task completed!");
            controller.workerFinishOnTime();
            for (Worker worker : workerManager.getUnemploymentsQ()) {
                System.out.println("unemployed : " + worker.getId());
            }
            for (Worker worker : workerManager.getEmploymentsQ()) {
                System.out.println("employed : " + worker.getId());
                System.out.println(worker.getDesk().getId());
            }
            System.out.println(controller.getTasksLeft() + " tasks left");
            System.out.println(controller.getTasksCompleted() + " tasks left");
            System.out.println(controller.getGlobalTime() + " global time");
            counter++;
        }

        System.out.println("worker 2 finished early!!!!!" + controller.getGlobalTime() + "time");
        for (Worker worker : workerManager.getEmploymentsQ()) {
            System.out.println("employed : " + worker.getId());
            System.out.println(worker.getDesk().getId());
        }

    }
}
