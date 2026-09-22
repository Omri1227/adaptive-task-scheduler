package backend;

import java.util.ArrayList;  //1.ליצור סוציאליות בין העובדים 2.לתת אופציות למשימה מתוך בנק 3.שיהיה מקום שהעובד יכול להיכנס רק למשימות שלו
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

public class Controller {
    private TaskEngine taskEngine;
    private WorkerManager workerManager;
    private int globalTime;

    private int workersNum;
    private int tasksLeft;
    private int tasksCompleted;
    private final boolean printShutdownSummary;



    public Controller(LinkedList<Task> tasks, LinkedList<String> workersName) {
        this(tasks, workersName, true);
    }

    public Controller(
            LinkedList<Task> tasks,
            LinkedList<String> workersName,
            boolean printShutdownSummary) {

        this.taskEngine = new TaskEngine(tasks);
        this.workerManager = new WorkerManager(workersName);
        this.globalTime = 0;
        this.workersNum = workerManager.getSize();
        this.tasksLeft = 0;
        this.tasksCompleted = 0;
        this.printShutdownSummary = printShutdownSummary;
    }

    public void initial() {
        taskEngine.initial();
        tasksLeft = taskEngine.getTopologicalSortSize();

        addUnemployed();
    }

    public void addTask(Task taskToAdd, LinkedList<Integer> IDsOfChildren) {
        for (Integer child : IDsOfChildren) {
            GraphTaskNode node = taskEngine.getTask(child);

            if (node == null) {
                throw new RuntimeException(
                        "child task with id " + child + " not exist"
                );
            }

            if (node.isCompleted()) {
                throw new RuntimeException(
                        "can't add dependency to completed task " + child
                );
            }

            if (workerManager.isTaskInProgress(node)) {
                throw new RuntimeException(
                        "can't add this task, " + child + " is in progress"
                );
            }
        }
        LinkedList<Task> newList = taskEngine.prepareAdd(taskToAdd, IDsOfChildren);
        TaskEngine newGraph = new TaskEngine(newList);
        newGraph.initial();
        if (!(newGraph.getTopologicalSortSize() == newList.size())) {
            throw new RuntimeException("cycle created, illegal task to add");
        }
        this.taskEngine = newGraph;
        LinkedList<Worker> workers = workerManager.getEmploymentsAsList();
        for (Worker worker : workers) {
            Integer currentID = worker.getDesk().getId();
            worker.setDesk(taskEngine.getGraphTaskNode(currentID));
            taskEngine.getReadyPQ().remove(taskEngine.getIDTable().get(currentID));
        }
        workerManager.reBuildPQ(workers);

        System.out.println("task added");
        tasksLeft++;
        addUnemployed();
    }

    public void removeTask(Task taskToRemove) {
        GraphTaskNode toRemove = taskEngine.getTask(taskToRemove.getId());
        if (toRemove == null) {
            throw new RuntimeException("this task not exist");
        }
        if (toRemove.isCompleted()) {
            throw new RuntimeException("this task already completed");
        }
        if (workerManager.isTaskInProgress(toRemove)) {
            throw new RuntimeException("this task is in progress");
        }
        LinkedList<Task> newTasks = taskEngine.prepareRemove(taskToRemove);
        TaskEngine newGraph = new TaskEngine(newTasks);
        this.taskEngine = newGraph;
        taskEngine.initial();
        LinkedList<Worker> workers = workerManager.getEmploymentsAsList();
        for (Worker worker : workers) {
            Integer currentID = worker.getDesk().getId();
            worker.setDesk(taskEngine.getGraphTaskNode(currentID));
            worker.setDesk(taskEngine.getGraphTaskNode(currentID));
            taskEngine.getReadyPQ().remove(taskEngine.getIDTable().get(currentID));
        }
        workerManager.reBuildPQ(workers);
        System.out.println("task removed");
        tasksLeft--;
    }

    public void addWorker(int id, String name) {
        Worker newWorker = new Worker(id, name);
        workerManager.addUnemployed(newWorker);
        System.out.println("worker added");
        workersNum++;

        addUnemployed();
    }

    public void removeWorker(Integer id) {
        if (workerManager.remove(id)) {
            System.out.println("worker removed");
            workersNum--;
        } else {
            System.out.println("worker will delete while finish his current task");
        }
    }

    public void workerFinishOnTime() {
        if (this.tasksLeft == 0) {
            throw new RuntimeException("All tasks completed");
        }

        Worker worker = workerManager.getTopEmployed();
        GraphTaskNode currentFinish = worker.getDesk();

        globalTime = worker.getTaskEstimatedEndTime();

        worker.finishTask(globalTime);
        taskEngine.taskCompleted(currentFinish);

        tasksLeft--;
        tasksCompleted++;

        if (worker.getToRemove()) {
            workerManager.delete(worker);
            System.out.println("worker removed");
            workersNum--;

            if (tasksLeft == 0) {
                SHUTDOWN();
                return;
            }

            addUnemployed();
            return;
        }

        if (tasksLeft == 0) {
            workerManager.giveNewTask(worker, null);
            SHUTDOWN();
            return;
        }

        GraphTaskNode task = taskEngine.getNextTask(
                workersNum,
                workerManager.getEmploymentsQ(),
                workerManager.getUnemploymentsQ(),
                globalTime
        );

        workerManager.giveNewTask(worker, task);

        addUnemployed();
    }

    private void SHUTDOWN() {
        if (!printShutdownSummary) {
            return;
        }

        System.out.println("ALL TASKS FINISHED");
        for (Worker worker : workerManager.getUnemploymentsQ()){
            System.out.println("Worker " + worker.getId() + " drawer: ");
            for (GraphTaskNode task : worker.getDrawer()){
                System.out.println(task.getId());
            }
        }
        System.out.println("total work time: " + globalTime);
    }

    public void workerFinishEarly(int id, int time) {
        if (tasksLeft == 0) {
            throw new RuntimeException("All tasks completed");
        }

        Worker worker = workerManager.getWorkerByID(id);

        if (worker.getEmploymentStatus() == Worker.Status.UNEMPLOYMENT) {
            throw new RuntimeException("Unemployed worker");
        }

        if (time > worker.getTaskEstimatedEndTime()) {
            throw new RuntimeException("Cannot finish later than estimated");
        }

        if (time < globalTime) {
            throw new RuntimeException("cannot finish earlier then current");
        }

        Integer nextOtherEnd =
                workerManager.getNextEndTimeExcluding(id);

        if (nextOtherEnd != null && time > nextOtherEnd) {
            throw new RuntimeException(
                    "cannot finish after the next scheduled completion");
        }

        workerManager.getEmployedFromPQbyID(id);

        globalTime = time;

        GraphTaskNode currentFinish = worker.getDesk();

        worker.finishTask(globalTime);
        taskEngine.taskCompleted(currentFinish);

        tasksCompleted++;
        tasksLeft--;

        if (worker.getToRemove()) {
            workerManager.delete(worker);
            workersNum--;

            System.out.println("worker removed");

            if (tasksLeft == 0) {
                SHUTDOWN();
                return;
            }

            addUnemployed();
            return;
        }

        if (tasksLeft == 0) {
            workerManager.giveNewTask(worker, null);
            SHUTDOWN();
            return;
        }

        GraphTaskNode task = taskEngine.getNextTask(
                workersNum,
                workerManager.getEmploymentsQ(),
                workerManager.getUnemploymentsQ(),
                globalTime);

        workerManager.giveNewTask(worker, task);

        addUnemployed();
    }

    public void workerIsLate(int id , int time) {
        if (time<globalTime){
            throw new RuntimeException("new time must be bigger then current");
        }
        Worker late = workerManager.getEmployedFromPQbyID(id);
        late.setTaskEstimatedEndTime(time);
        workerManager.addEmployed(late);
    }

    public void addUnemployed() {
        while (!workerManager.getUnemploymentsQ().isEmpty() && !taskEngine.getReadyPQ().isEmpty()) {
            Worker unemployed = workerManager.getUnemploymentsQ().poll();
            GraphTaskNode nextTask = taskEngine.getNextTask(
                    workersNum, workerManager.getEmploymentsQ(),
                    workerManager.getUnemploymentsQ(), globalTime);
            if (nextTask != null) {
                unemployed.setLastFinishTime(globalTime);
                workerManager.giveNewTask(unemployed, nextTask);
            } else {
                break;
            }
        }
    }

    public TaskEngine getTaskEngine() {
        return this.taskEngine;
    }

    public WorkerManager getWorkerManager() {
        return this.workerManager;
    }

    public int getGlobalTime(){
        return globalTime;
    }
    public int getTasksLeft(){
        return tasksLeft;
    }
    public int getTasksCompleted(){
        return tasksCompleted;
    }

    // === Tasks for UI ===
    public Collection<GraphTaskNode> getAllTasks() {
        return taskEngine.getTopologicalSort();
    }

    // === Workers for UI ===
    public List<Worker> getActiveWorkers() {
        return new ArrayList<>(workerManager.getEmploymentsQ());
    }

    public List<Worker> getIdleWorkers() {
        return new ArrayList<>(workerManager.getUnemploymentsQ());
    }


}

