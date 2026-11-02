package backend;

import java.util.*;

public class TaskEngine {
    private TimeConstraints constraints;
    private HashMap<Integer, GraphTaskNode> IDTable;
    private PriorityQueue<GraphTaskNode> readyPQ;
    private LinkedList<Task> tasks;
    private HashMap<Integer, Task> TaskIdTable;
    private int leftTaskNum;
    private double maxStaticScore;
    private int maxDuration;
    private int maxOpenChild;

    // Rollout lookahead settings (validated in research/phase-reports/PHASE1_REPORT.md section 7 and PHASE2_REPORT.md section 6)
    private static final int LOOKAHEAD_CANDIDATES = 5;
    private static final int ROLLOUT_DEPTH = 8;
    private DynamicScoreLogic.RolloutModel rolloutModel;

    public TaskEngine(LinkedList<Task> tasks){
        this.tasks = tasks;
        maxDuration=0;
        for (Task task : tasks) {
            if (task.getDuration() <= 0) {
                throw new RuntimeException(
                        "task duration must be positive: " + task.getId()
                );
            }

            if (task.getDuration() > maxDuration) {
                maxDuration = task.getDuration();
            }
        }
        this.TaskIdTable = new HashMap<>(tasks.size());
        this.maxStaticScore = 0;

        for (Task task : tasks) {
            if (TaskIdTable.containsKey(task.getId())) {
                throw new RuntimeException(
                        "task id already exists: " + task.getId()
                );
            }

            TaskIdTable.put(task.getId(), task);
        }
        leftTaskNum = tasks.size();
        IDTable = new HashMap<>(tasks.size());
        LinkedList<GraphTaskNode> nodesList= new LinkedList<>();
        for (Task task: tasks){
            GraphTaskNode node = new GraphTaskNode(task);
            nodesList.add(node);
            IDTable.put(node.getId(), node);
        }
        for (Task task : tasks) {
            GraphTaskNode child = IDTable.get(task.getId());

            HashSet<Integer> seenParents = new HashSet<>();

            for (Integer parentId : task.getDependsOnIDS()) {

                if (!seenParents.add(parentId)) {
                    throw new RuntimeException(
                            "duplicate dependency "
                                    + parentId
                                    + " for task "
                                    + task.getId()
                    );
                }

                GraphTaskNode parent = IDTable.get(parentId);

                if (parent == null) {
                    throw new RuntimeException(
                            "parent task does not exist: " + parentId
                    );
                }

                parent.addChildren(child);
                child.addParent(parent);
                child.addParentRemain(parent);
            }
        }
        this.maxOpenChild = 0;

        for (GraphTaskNode node : nodesList) {
            this.maxOpenChild = Math.max(
                    this.maxOpenChild,
                    node.getChildren().size()
            );
        }

        this.constraints = new TimeConstraints(nodesList, IDTable);
        readyPQ = new PriorityQueue<>((t1,t2)->Double.compare(t2.getScore(),t1.getScore()));
    }

    public void initial(){
        constraints.initial(IDTable);
        for (GraphTaskNode task: constraints.getHeads()){
            readyPQ.add(task);
        }
        for (GraphTaskNode node: getTopologicalSort()){
            if (node.getScore()>maxStaticScore){
                maxStaticScore=node.getScore();
            }
        }
    }


    public GraphTaskNode getNextTask(
            int workersNum,
            PriorityQueue<Worker> employed,
            Queue<Worker> unemployed,
            int globalTime) {

        if (leftTaskNum == 0 || readyPQ.isEmpty()) {
            return null;
        }

        if (readyPQ.size() == 1) {
            return readyPQ.poll();
        }

        int originalPQSize = readyPQ.size();

        GraphTaskNode op1 = readyPQ.poll();
        GraphTaskNode op2 = readyPQ.poll();
        GraphTaskNode op3 = readyPQ.poll();

        GraphTaskNode[] tasksAr = new GraphTaskNode[3];
        double[] scores = new double[3];

        dynamicScoreChoose(
                op1,
                op2,
                op3,
                workersNum,
                scores,
                tasksAr,
                originalPQSize
        );

        GraphTaskNode selected = tasksAr[0];

        // Lookahead: a makespan rollout, used only on contested decisions
        // (more ready tasks than workers able to start one right now).
        if (tasksAr[1] != null
                && originalPQSize > freeWorkersNow(employed, unemployed, globalTime)) {

            selected = rolloutLookahead(tasksAr, employed, unemployed, globalTime);

            if (selected != tasksAr[0] && selected != tasksAr[1] && selected != tasksAr[2]) {
                // chosen from beyond the three polled candidates: take it out of the queue
                readyPQ.remove(selected);
            }
        }

        for (GraphTaskNode candidate : tasksAr) {
            if (candidate != null && candidate != selected) {
                readyPQ.add(candidate);
            }
        }

        return selected;
    }


    private PriorityQueue<GraphTaskNode> buildSimulationReadyQueue(
            GraphTaskNode excluded,
            GraphTaskNode[] candidates) {

        PriorityQueue<GraphTaskNode> simulationPQ =
                new PriorityQueue<>(readyPQ);

        for (GraphTaskNode candidate : candidates) {
            if (candidate != null && candidate != excluded) {
                simulationPQ.add(candidate);
            }
        }

        return simulationPQ;
    }

    /**
     * Rollout lookahead. Compares the three blended-score candidates and the next
     * best ready tasks by static score (LOOKAHEAD_CANDIDATES in total). Each one is
     * started now in a simulation that continues for ROLLOUT_DEPTH completion
     * events; the smallest estimated makespan wins, and ties keep the order above
     * (so the blended-score choice wins every tie). The ready queue is not changed.
     */
    private GraphTaskNode rolloutLookahead(
            GraphTaskNode[] candidates,
            PriorityQueue<Worker> employed,
            Queue<Worker> unemployed,
            int globalTime) {

        if (rolloutModel == null) {
            rolloutModel = new DynamicScoreLogic.RolloutModel(getTopologicalSort());
        }

        List<GraphTaskNode> compared = new ArrayList<>();
        for (GraphTaskNode candidate : candidates) {
            if (candidate != null) {
                compared.add(candidate);
            }
        }
        // further candidates come from a copy, so readyPQ itself stays untouched
        PriorityQueue<GraphTaskNode> rest = new PriorityQueue<>(readyPQ);
        while (compared.size() < LOOKAHEAD_CANDIDATES && !rest.isEmpty()) {
            compared.add(rest.poll());
        }

        DynamicScoreLogic logic = new DynamicScoreLogic();
        GraphTaskNode best = null;
        int bestMakespan = Integer.MAX_VALUE;
        for (GraphTaskNode candidate : compared) {
            PriorityQueue<GraphTaskNode> otherReady =
                    buildSimulationReadyQueue(candidate, candidates);
            otherReady.remove(candidate);

            int makespan = logic.rolloutMakespan(
                    rolloutModel,
                    candidate,
                    otherReady,
                    employed,
                    unemployed.size(),
                    globalTime,
                    ROLLOUT_DEPTH);
            if (makespan < bestMakespan) {
                best = candidate;
                bestMakespan = makespan;
            }
        }
        return best;
    }

    /** Workers able to start a task at globalTime: the deciding one, idle ones, and ones finishing now. */
    private int freeWorkersNow(PriorityQueue<Worker> employed, Queue<Worker> unemployed, int globalTime) {
        int free = 1 + unemployed.size();
        for (Worker worker : employed) {
            if (worker.getTaskEstimatedEndTime() == globalTime) {
                free++;
            }
        }
        return free;
    }

    private void dynamicScoreChoose
            (GraphTaskNode op1, GraphTaskNode op2, GraphTaskNode op3,
             int workersNum, double[]scores, GraphTaskNode[]tasks,
             int originalPQSize)
    {

        double op1DS =
                new DynamicScoreLogic().makeDynamicScore(
                        op1,
                        originalPQSize,
                        workersNum,
                        maxStaticScore,
                        maxDuration,
                        maxOpenChild);
        double op2DS =
                new DynamicScoreLogic().makeDynamicScore(
                        op2,
                        originalPQSize,
                        workersNum,
                        maxStaticScore,
                        maxDuration,
                        maxOpenChild);
        double op3DS = (op3 != null)
                ? new DynamicScoreLogic().makeDynamicScore(
                op3,
                originalPQSize,
                workersNum,
                maxStaticScore,
                maxDuration,
                maxOpenChild)
                : Double.NEGATIVE_INFINITY;

        double op1TotalScore = (op1DS + op1.getScore()) / 2;
        tasks[0] = op1;
        scores[0] = op1TotalScore;
        double op2TotalScore = (op2DS + op2.getScore()) / 2;
        if (op2TotalScore>scores[0]) {
            scores[1] = scores[0];
            scores[0] = op2TotalScore;
            tasks[1] = tasks[0];
            tasks[0] = op2;
        }
        else{
            scores[1]=op2TotalScore;
            tasks[1]=op2;
        }
        double op3TotalScore = (op3 != null) ? (op3DS + op3.getScore()) / 2 : Double.NEGATIVE_INFINITY;
        if (op3TotalScore!=Double.NEGATIVE_INFINITY) {
            if (op3TotalScore > scores[0]) {
                scores[2] = scores[1];
                scores[1] = scores[0];
                scores[0] = op3TotalScore;
                tasks[2] = tasks[1];
                tasks[1] = tasks[0];
                tasks[0] = op3;
            } else {
                if (op3TotalScore > scores[1]) {
                    scores[2] = scores[1];
                    scores[1] = op3TotalScore;
                    tasks[2] = tasks[1];
                    tasks[1] = op3;
                } else {
                    scores[2] = op3TotalScore;
                    tasks[2]=op3;
                }
            }
        }
    }


    public void taskCompleted(GraphTaskNode task){
        leftTaskNum--;
        LinkedList<GraphTaskNode> newReady = this.constraints.taskCompleted(task);
        readyPQ.addAll(newReady);
    }

    public LinkedList<Task> prepareAdd(Task toAdd, LinkedList<Integer> children) {
        if(IDTable.containsKey(toAdd.getId())){
            throw new RuntimeException("task id already exist");
        }
        ArrayList<GraphTaskNode> old = this.constraints.getTopologicalSort();
        Set<Integer> remaining = new HashSet<>();
        for (GraphTaskNode current : old) {
            if (!current.isCompleted()) {
                remaining.add(current.getId());
            }
        }
        LinkedList<Task> newList = new LinkedList<>();
        for (Integer current : remaining) {
            Task original = TaskIdTable.get(current);
            Task copy = new Task(original.getName(), original.getId(), original.getDuration(), new LinkedList<>(original.getDependsOnIDS()));
            Iterator<Integer> it = copy.getDependsOnIDS().iterator();
            while(it.hasNext()){
                Integer parent = it.next();
                if(IDTable.get(parent)!=null && IDTable.get(parent).isCompleted()){
                    it.remove();
                }
            }
            if (children.contains(copy.getId())) {
                copy.getDependsOnIDS().add(toAdd.getId());
            }

            newList.add(copy);
        }
        Task copy = new Task(
                toAdd.getName(),
                toAdd.getId(),
                toAdd.getDuration(),
                new LinkedList<>(toAdd.getDependsOnIDS())
        );

        Iterator<Integer> parentIt =
                copy.getDependsOnIDS().iterator();

        while (parentIt.hasNext()) {
            Integer parentId = parentIt.next();
            GraphTaskNode parent = IDTable.get(parentId);

            if (parent != null && parent.isCompleted()) {
                parentIt.remove();
            }
        }
        newList.add(copy);
        TaskIdTable.put(copy.getId(), copy);
        return newList;
    }

    public LinkedList<Task> prepareRemove(Task taskToRemove) {
        ArrayList<GraphTaskNode> old = this.constraints.getTopologicalSort();
        Set<Integer> remaining = new HashSet<>();
        for (GraphTaskNode current : old){
            if (!current.isCompleted()){
                remaining.add(current.getId());
            }
        }
        if (!remaining.remove(taskToRemove.getId())) {
            throw new RuntimeException("task not exist");
        }

        LinkedList<Task> newList = new LinkedList<>();
        for (Integer current : remaining) {
            Task original = TaskIdTable.get(current);
            Task copy = new Task(original.getName(), original.getId(), original.getDuration(), new LinkedList<>(original.getDependsOnIDS()));
            if (copy.getDependsOnIDS().contains(taskToRemove.getId())){
                for (GraphTaskNode grandParent : IDTable.get(taskToRemove.getId()).getParentsRemained()){
                    if (!grandParent.isCompleted()
                            && !copy.getDependsOnIDS().contains(grandParent.getId())) {
                        copy.addDependsOn(grandParent.getId());
                    }
                }
                copy.removeDependsOn(taskToRemove.getId());
            }
            newList.add(copy);
            Iterator<Integer> it = copy.getDependsOnIDS().iterator();
            while(it.hasNext()){
                Integer parent = it.next();
                if(IDTable.get(parent)!=null && IDTable.get(parent).isCompleted()){
                    it.remove();
                }
            }
        }

        return newList;
    }

    public GraphTaskNode getTask(int id) {
        return IDTable.get(id);
    }

    public GraphTaskNode getGraphTaskNode(Integer currentID) {
        return IDTable.get(currentID);
    }

    public int getTopologicalSortSize() {
        return this.constraints.getTopologicalSort().size();
    }
    public Set<Integer> getTaskIdList() {
        return IDTable.keySet();
    }
    public LinkedList<GraphTaskNode> getConstraintsHeads() {
        return constraints.getHeads();
    }

    public int getTaskCount() {
        return IDTable.size();
    }

    public PriorityQueue<GraphTaskNode> getReadyPQCopy() {
        return new PriorityQueue<>(readyPQ);
    }

    public ArrayList<GraphTaskNode> getTopologicalSort() {
        return this.constraints.getTopologicalSort();
    }
    public PriorityQueue<GraphTaskNode> getReadyPQ(){
        return this.readyPQ;
    }

    public HashMap<Integer, GraphTaskNode> getIDTable(){
        return this.IDTable;
    }
}
