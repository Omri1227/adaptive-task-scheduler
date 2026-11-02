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
    private final SchedulerConfig config;
    private final StaticGraphAnalysis analysis;
    private DynamicScoreLogic.RolloutModel rolloutModel;


    public TaskEngine(LinkedList<Task> tasks){
        this(tasks, SchedulerConfig.DEFAULT);
    }

    public TaskEngine(LinkedList<Task> tasks, SchedulerConfig config){
        this(tasks, config, null);
    }

    /**
     * @param analysis precomputed weight-independent analysis of exactly these
     *                 tasks, or null to compute it in initial()
     */
    public TaskEngine(LinkedList<Task> tasks, SchedulerConfig config, StaticGraphAnalysis analysis){
        if (analysis != null && !analysis.matches(tasks)) {
            throw new IllegalArgumentException("static analysis does not match the task list");
        }
        this.config = config;
        this.analysis = analysis;
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

        this.constraints = new TimeConstraints(nodesList, IDTable, config);
        readyPQ = config.isDeterministicTieBreak()
                ? new PriorityQueue<>((t1,t2)->{
                    int c = Double.compare(t2.getScore(),t1.getScore());
                    return c != 0 ? c : Integer.compare(t1.getId(), t2.getId());
                })
                : new PriorityQueue<>((t1,t2)->Double.compare(t2.getScore(),t1.getScore()));
    }

    public void initial(){
        constraints.initial(IDTable, analysis);
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

        // candidate pool: the K best static scores (original K = 3)
        int poolSize = Math.min(config.getCandidatePoolSize(), originalPQSize);
        GraphTaskNode[] pool = new GraphTaskNode[poolSize];
        for (int i = 0; i < poolSize; i++) {
            pool[i] = readyPQ.poll();
        }
        GraphTaskNode op1 = pool[0];

        GraphTaskNode[] tasksAr = new GraphTaskNode[poolSize];
        double[] scores = new double[poolSize];

        int availableWorkers = config.isWorkloadUsesAvailableWorkers()
                ? freeWorkersNow(employed, unemployed, globalTime)
                : workersNum;

        dynamicScoreChoose(
                pool,
                workersNum,
                scores,
                tasksAr,
                originalPQSize,
                availableWorkers
        );

        GraphTaskNode selected = tasksAr[0];

        // The original gate counted the ready tasks left after removing the top 3;
        // it is kept independent of K so that K alone does not change activation.
        int readyBeyondTopThree = Math.max(0, originalPQSize - 3);

        if (config.getLookaheadMode() != SchedulerConfig.LookaheadMode.NONE
                && tasksAr.length > 1
                && (!config.isLegacyLoadGate() || readyBeyondTopThree <= workersNum)
                && (!config.isContestedDecisionsOnly()
                        || originalPQSize > freeWorkersNow(employed, unemployed, globalTime))
                && gapAllowsLookahead(Math.abs(scores[1] - scores[0]))) {

            selected = config.getLookaheadMode() == SchedulerConfig.LookaheadMode.ROLLOUT
                    ? rolloutLookahead(tasksAr, op1, employed, unemployed, globalTime)
                    : legacyLookahead(tasksAr, scores, employed, unemployed, workersNum, globalTime);
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
     * Original lookahead: blended score + mu * idle-window score; the first of the
     * best candidates wins ties (with two candidates: score0 >= score1 keeps the first).
     */
    private GraphTaskNode legacyLookahead(
            GraphTaskNode[] candidates,
            double[] scores,
            PriorityQueue<Worker> employed,
            Queue<Worker> unemployed,
            int workersNum,
            int globalTime) {

        int count = lookaheadCandidateCount(candidates);
        GraphTaskNode best = null;
        double bestScore = 0;
        for (int i = 0; i < count; i++) {
            PriorityQueue<GraphTaskNode> pq = buildSimulationReadyQueue(candidates[i], candidates);
            double lookahead = new DynamicScoreLogic(config).makeLookAhead(
                    candidates[i],
                    pq,
                    employed,
                    unemployed,
                    workersNum,
                    globalTime
            );
            if (config.isBoundedLookaheadScore()) {
                lookahead = Math.max(0.0, Math.min(1.0, lookahead));
            }
            double score = scores[i] + config.getLookaheadWeight() * lookahead;
            if (best == null || score > bestScore) {
                best = candidates[i];
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * Rollout lookahead: every compared candidate is simulated for the same number
     * of completion events and the smallest estimated makespan wins; ties keep
     * the blended-score order.
     */
    private GraphTaskNode rolloutLookahead(
            GraphTaskNode[] candidates,
            GraphTaskNode staticBest,
            PriorityQueue<Worker> employed,
            Queue<Worker> unemployed,
            int globalTime) {

        if (rolloutModel == null) {
            rolloutModel = new DynamicScoreLogic.RolloutModel(getTopologicalSort());
        }
        List<GraphTaskNode> compared = new ArrayList<>();
        int count = lookaheadCandidateCount(candidates);
        for (int i = 0; i < count; i++) {
            compared.add(candidates[i]);
        }
        if (config.isRolloutIncludesStaticBest() && staticBest != null && !compared.contains(staticBest)) {
            compared.add(staticBest);
        }

        DynamicScoreLogic logic = new DynamicScoreLogic(config);
        GraphTaskNode best = null;
        int bestMakespan = Integer.MAX_VALUE;
        for (GraphTaskNode candidate : compared) {
            int makespan = logic.rolloutMakespan(
                    rolloutModel,
                    candidate,
                    buildSimulationReadyQueue(candidate, candidates),
                    employed,
                    unemployed.size(),
                    globalTime,
                    config.getRolloutDepth());
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
        for (Worker w : employed) {
            if (w.getTaskEstimatedEndTime() == globalTime) {
                free++;
            }
        }
        return free;
    }

    private int lookaheadCandidateCount(GraphTaskNode[] candidates) {
        int count = 0;
        while (count < candidates.length && count < config.getLookaheadCandidates() && candidates[count] != null) {
            count++;
        }
        return count;
    }

    private boolean gapAllowsLookahead(double gap) {
        return switch (config.getGapCondition()) {
            case GAP_ABOVE_THRESHOLD -> gap > config.getLookaheadGapThreshold();
            case GAP_AT_MOST_THRESHOLD -> gap <= config.getLookaheadGapThreshold();
            case ANY_GAP -> true;
        };
    }

    /** lambda * static + (1 - lambda) * dynamic; equals the original (dynamic + static) / 2 for lambda = 0.5. */
    private double blendScore(double dynamicScore, double staticScore) {
        double lambda = config.getStaticShare();
        return lambda * staticScore + (1 - lambda) * dynamicScore;
    }

    /**
     * Orders the pool by blended score, best first. A candidate moves ahead of an
     * earlier one only with a strictly greater score, exactly as the original
     * three-candidate insertion did (ties keep static-score order).
     */
    private void dynamicScoreChoose
            (GraphTaskNode[] pool,
             int workersNum, double[]scores, GraphTaskNode[]tasks,
             int originalPQSize, int availableWorkers)
    {
        int placed = 0;
        for (GraphTaskNode candidate : pool) {
            double dynamicScore =
                    new DynamicScoreLogic(config).makeDynamicScore(
                            candidate,
                            originalPQSize,
                            workersNum,
                            maxStaticScore,
                            maxDuration,
                            maxOpenChild,
                            availableWorkers);
            double total = blendScore(dynamicScore, candidate.getScore());
            int position = placed;
            for (int i = 0; i < placed; i++) {
                if (total > scores[i]) {
                    position = i;
                    break;
                }
            }
            for (int i = placed; i > position; i--) {
                scores[i] = scores[i - 1];
                tasks[i] = tasks[i - 1];
            }
            scores[position] = total;
            tasks[position] = candidate;
            placed++;
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
