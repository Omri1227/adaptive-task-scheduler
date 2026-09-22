package backend;

import java.util.*;//השדה סייז עדיין לא מתעדכן כנדרש

public class WorkerManager {
    private PriorityQueue<Worker> employmentsPQ;
    private Queue<Worker> UnemploymentsQ;
    private HashMap<Integer, Worker> IDTable;
    private int globalOrder;
    private int size;

    public WorkerManager(LinkedList<String> workersName) {
        LinkedList<Worker> workers = new LinkedList<>();
        for (int i = 0; i < workersName.size(); i++) {
            Worker worker = new Worker(i, workersName.get(i));
            workers.add(worker);
        }
        Comparator<Worker> workerComparator = (w1, w2) -> {
            int cmp = Integer.compare(w1.getTaskEstimatedEndTime(), w2.getTaskEstimatedEndTime());
            if (cmp != 0) return cmp;
            return Long.compare(w1.getInsertionOrder(), w2.getInsertionOrder());
        };
        int order = 0;
        for (Worker worker : workers) {
            worker.setInsertionOrder(order++);
        }
        this.employmentsPQ = new PriorityQueue<>(workerComparator);
        this.UnemploymentsQ = new ArrayDeque<>();
        this.UnemploymentsQ.addAll(workers);
        IDTable = new HashMap<>(workers.size());
        for (Worker worker : workers) {
            IDTable.put(worker.getId(), worker);
        }
        size = IDTable.size();
        globalOrder = order;
    }


    public Worker getTopUnemployed() {
        if (UnemploymentsQ.isEmpty()) {
            throw new RuntimeException("no unemployed workers found");
        }
        return UnemploymentsQ.poll();
    }

    public Worker getTopEmployed() {
        if (employmentsPQ.isEmpty()) {
            throw new RuntimeException("NO WORKERS");
        }
        return employmentsPQ.poll();
    }

    public Worker getEmployedFromPQbyID(int id) {
        Worker workerFinish = IDTable.get(id);
        if (workerFinish == null) {
            throw new RuntimeException("ID not found");
        }
        if (!employmentsPQ.remove(workerFinish)) {
            throw new RuntimeException("ID unemployed now");
        }
        return workerFinish;
    }


    public void giveNewTask(Worker worker, GraphTaskNode task) {
        if (task == null) {
            worker.setDesk(null);
            this.UnemploymentsQ.add(worker);
        } else {
            worker.giveTask(task);
            worker.setInsertionOrder(globalOrder++);
            employmentsPQ.add(worker);
        }

    }


    public int getSize() {
        return IDTable.size();
    }

    public Queue<Worker> getUnemploymentsQ() {
        return this.UnemploymentsQ;
    }

    public int getNextEndTime() {
        if (employmentsPQ.peek()==null){
            throw new RuntimeException("wokersPQ is empty");
        }
        return employmentsPQ.peek().getTaskEstimatedEndTime();
    }

    public void addEmployed(Worker worker) {
        employmentsPQ.add(worker);
    }

    public boolean remove(Integer id){
        Worker toRemove = IDTable.get(id);
        if (toRemove==null){
            throw new RuntimeException("worker not found");
        }
        if (toRemove.getEmploymentStatus() == Worker.Status.UNEMPLOYMENT){
            UnemploymentsQ.remove(IDTable.get(id));
            delete(toRemove);
            return true;
        }
        else {
            if (!toRemove.getToRemove()) {
                toRemove.setToRemove();
            }

            return false;
        }
    }

    public boolean delete(Worker worker) {
        IDTable.remove(worker.getId());
        size--;
        return true;
    }

    public void addUnemployed(Worker newWorker) {
        if (IDTable.containsKey(newWorker.getId())) {
            throw new RuntimeException("worker id already exists");
        }

        this.UnemploymentsQ.add(newWorker);
        IDTable.put(newWorker.getId(), newWorker);
    }

    public boolean isTaskInProgress(GraphTaskNode task) {
        for(Worker worker: employmentsPQ){
            if (worker.getDesk()==task){
                return true;
            }
        }
        return false;
    }

    public LinkedList<Worker> getEmploymentsAsList() {
        return new LinkedList<>(employmentsPQ);
    }
    public PriorityQueue<Worker> getEmploymentsQ(){
        return this.employmentsPQ;
    }

    public void reBuildPQ(LinkedList<Worker> workers) {
        while(!employmentsPQ.isEmpty()){
            employmentsPQ.poll();
        }
        employmentsPQ.addAll(workers);
    }

    public Comparator<Worker> getEmployedPQComparator() {
        return (Comparator<Worker>) employmentsPQ.comparator();
    }

    public Worker getWorkerByID(int id) {
        Worker worker = IDTable.get(id);

        if (worker == null) {
            throw new RuntimeException("ID not found");
        }

        return worker;
    }

    public Integer getNextEndTimeExcluding(int id) {
        return employmentsPQ.stream()
                .filter(worker -> worker.getId() != id)
                .map(Worker::getTaskEstimatedEndTime)
                .min(Integer::compareTo)
                .orElse(null);
    }
}