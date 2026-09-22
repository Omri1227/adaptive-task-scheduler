package backend;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.util.stream.Collectors;

public class ControllerTest {
    @Test
    public void controller_5Nodes() {
        //task engine:
        LinkedList<Task> tasks = new LinkedList<>();
        Task a1 = new Task("a1", 1, 2, new LinkedList<>());
        Task a2 = new Task("a2", 2, 3, new LinkedList<>());
        Task a3 = new Task("a3", 3, 4, new LinkedList<>());
        Task b4 = new Task("b4", 4, 8, new LinkedList<>());
        Task c5 = new Task("c5", 5, 1, new LinkedList<>());
        b4.addDependsOn(a1.getId());
        b4.addDependsOn(a2.getId());
        c5.addDependsOn(b4.getId());
        c5.addDependsOn(a3.getId());
        tasks.add(a1);
        tasks.add(a2);
        tasks.add(a3);
        tasks.add(b4);
        tasks.add(c5);

        //worker manager :
        LinkedList<String> workers = new LinkedList<>();

        workers.addLast("w1");
        workers.addLast("w2");
        workers.addLast("w3");

        Controller controller = new Controller(tasks, workers);
        controller.initial();

        TaskEngine taskEngine = controller.getTaskEngine();
        WorkerManager workerManager = controller.getWorkerManager();


        for (GraphTaskNode task : taskEngine.getTopologicalSort()){
            System.out.println(task.getId());
            System.out.println(task.getScore());
        }
        for (Worker worker : workerManager.getUnemploymentsQ()){
            System.out.println("unemployed : " + worker.getId());
        }
        for (Worker worker : workerManager.getEmploymentsQ()){
            System.out.println("employed : " + worker.getId());
            System.out.println(worker.getDesk().getId());
        }

        System.out.println(taskEngine.getReadyPQ().size());
        System.out.println("first task completed!");

        controller.workerFinishOnTime();
        for (Worker worker : workerManager.getUnemploymentsQ()){
            System.out.println("unemployed : " + worker.getId());
        }
        for (Worker worker : workerManager.getEmploymentsQ()){
            System.out.println("employed : " + worker.getId());
            System.out.println(worker.getDesk().getId());
        }
        System.out.println(controller.getTasksLeft() + " tasks left");
        System.out.println(controller.getTasksCompleted() + " tasks left");
        System.out.println(controller.getGlobalTime() + " global time");

        System.out.println("second task completed!");

        controller.workerFinishOnTime();
        for (Worker worker : workerManager.getUnemploymentsQ()){
            System.out.println("unemployed : " + worker.getId());
        }
        for (Worker worker : workerManager.getEmploymentsQ()){
            System.out.println("employed : " + worker.getId());
            System.out.println(worker.getDesk().getId());
        }
        System.out.println(controller.getTasksLeft() + " tasks left");
        System.out.println(controller.getTasksCompleted() + " tasks left");
        System.out.println(controller.getGlobalTime() + " global time");

        System.out.println("third task completed!");

        controller.workerFinishOnTime();
        for (Worker worker : workerManager.getUnemploymentsQ()){
            System.out.println("unemployed : " + worker.getId());
        }
        for (Worker worker : workerManager.getEmploymentsQ()){
            System.out.println("employed : " + worker.getId());
            System.out.println(worker.getDesk().getId());
        }
        System.out.println(controller.getTasksLeft() + " tasks left");
        System.out.println(controller.getTasksCompleted() + " tasks left");
        System.out.println(controller.getGlobalTime() + " global time");

        System.out.println("fourth task completed!");

        controller.workerFinishOnTime();
        for (Worker worker : workerManager.getUnemploymentsQ()){
            System.out.println("unemployed : " + worker.getId());
        }
        for (Worker worker : workerManager.getEmploymentsQ()){
            System.out.println("employed : " + worker.getId());
            System.out.println(worker.getDesk().getId());
        }
        System.out.println(controller.getTasksLeft() + " tasks left");
        System.out.println(controller.getTasksCompleted() + " tasks left");
        System.out.println(controller.getGlobalTime() + " global time");










    }
}