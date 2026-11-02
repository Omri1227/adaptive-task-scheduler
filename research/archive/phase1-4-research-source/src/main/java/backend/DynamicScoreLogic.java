package backend;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;

public class DynamicScoreLogic { // חייב ליצור מקס דיוריישן ומקס סטטיק סקור שנוכל לתת לפונקציה  (בבנייה)

    private final SchedulerConfig config;

    public DynamicScoreLogic() {
        this(SchedulerConfig.DEFAULT);
    }

    public DynamicScoreLogic(SchedulerConfig config) {
        this.config = config;
    }

    public double makeDynamicScore(
            GraphTaskNode task,
            int PQSize,
            int workerNum,
            double maxStaticScore,
            int maxDuration,
            int maxOpenChild){
        return makeDynamicScore(task, PQSize, workerNum, maxStaticScore, maxDuration, maxOpenChild, workerNum);
    }

    /**
     * @param availableWorkers workers able to start a task now; replaces workerNum
     *                         in the workload pressure alpha = min(1, ready / workers)
     *                         when SchedulerConfig.isWorkloadUsesAvailableWorkers()
     */
    public double makeDynamicScore(
            GraphTaskNode task,
            int PQSize,
            int workerNum,
            double maxStaticScore,
            int maxDuration,
            int maxOpenChild,
            int availableWorkers){

        int loadWorkers = config.isWorkloadUsesAvailableWorkers() ? Math.max(1, availableWorkers) : workerNum;
        int openChildren = openChildrenCount(task);

        double childrenToPQ =
                openChildToRPQSize(
                        task,
                        openChildren,
                        PQSize,
                        loadWorkers,
                        maxOpenChild);

        double openToBlock = openToBlockRatio(task);
        double openChildrenScore = (double) openChildren/workerNum;
        if (openChildrenScore>1){
            openChildrenScore=1;
        }
        double childImportance = 0;
        if (openChildren>0) {
            childImportance = openChildrenImportance(task) / (openChildren * maxStaticScore);
        }

        double durationToReady = durationToRPQSize(task, maxDuration, PQSize,loadWorkers);
        return config.getOpenToBlockWeight()*openToBlock + config.getOpenChildrenWeight()*openChildrenScore +
                config.getChildImportanceWeight()*childImportance + config.getDurationToReadyWeight()*durationToReady
                + config.getChildrenToReadyQueueWeight()*childrenToPQ;
    }

    public double makeLookAhead(
            GraphTaskNode task,
            PriorityQueue<GraphTaskNode> PQ,
            PriorityQueue<Worker> employed,
            Queue<Worker> unemployed,
            int workerNum,
            int globalTime
    ) {

        double maxUnemployment =
                (workerNum - 1) * task.getDuration();

        if (maxUnemployment <= 0) {
            return 1.0;
        }

        double unemploymentInDurationScore =
                1 - (unemploymentInDuration(
                        task,
                        PQ,
                        employed,
                        unemployed,
                        globalTime,
                        config.isSimulationAssignsIdleWorkers())
                        / maxUnemployment);

        return unemploymentInDurationScore;
    }

    public double openToBlockRatio(GraphTaskNode task) {
        double total = task.getChildren().size();
        if (total == 0) {
            return 0;
        }
        double open = 0;
        for (GraphTaskNode child : task.getChildren()) {
            if (child.getParentsRemained().size() == 1) {
                open++;
            }
        }
        return open / total;
    }

    public int openChildrenCount(GraphTaskNode task) {
        int counter = 0;
        for (GraphTaskNode child : task.getChildren()) {
            if (child.getParentsRemained().size() == 1) {
                counter++;
            }
        }
        return counter;
    }

    public double openChildrenImportance(GraphTaskNode task) {
        double total = 0;
        for (GraphTaskNode child : task.getChildren()) {
            if (child.getParentsRemained().size() == 1) {
                total = total + child.getScore();
            }
        }
        return total;
    }

    public double durationToRPQSize(GraphTaskNode task, int maxDuration, double PQSize, double workersNum) {
        double load = PQSize / workersNum;
        double durationNorm = (double) task.getDuration() / maxDuration;
        double alpha = Math.min(1.0, load);
        return alpha * durationNorm + (1 - alpha) * (1 - durationNorm);
    }

    public double openChildToRPQSize(
            GraphTaskNode task,
            int openChild,
            int PQSize,
            int workersNum,
            int maxOpenChild
    ) {
        if (maxOpenChild == 0) return 0;

        double openChildNorm = (double) openChild / maxOpenChild;
        double load = (double) PQSize / workersNum;
        double alpha = Math.min(1.0, load);
        return (1 - alpha) * openChildNorm;
    }

    public static double unemploymentInDuration(
            GraphTaskNode task,
            PriorityQueue<GraphTaskNode> readyPQ,
            PriorityQueue<Worker> workersPQ,
            Queue<Worker> unEmployed,
            int globalTime
    ) {
        return unemploymentInDuration(task, readyPQ, workersPQ, unEmployed, globalTime, false);
    }

    /**
     * @param assignIdleAtStart if true, idle workers take available ready tasks at
     *                          globalTime, as Controller.addUnemployed does; the
     *                          original simulation (false) first waits for the
     *                          next completion of a busy worker
     */
    public static double unemploymentInDuration(
            GraphTaskNode task,
            PriorityQueue<GraphTaskNode> readyPQ,
            PriorityQueue<Worker> workersPQ,
            Queue<Worker> unEmployed,
            int globalTime,
            boolean assignIdleAtStart
    ) {
        int totalUnemployment = 0;

        PriorityQueue<GraphTaskNode> copyTasksPQ = new PriorityQueue<>(readyPQ);
        PriorityQueue<Worker> copyWorkerPQ = new PriorityQueue<>(workersPQ);
        // Copies, so that the simulation never writes into the real idle workers.
        Queue<Worker> copyUnemployedQ = new ArrayDeque<>();
        for (Worker worker : unEmployed) {
            copyUnemployedQ.add(new Worker(worker));
        }
        int nextWorkerTime = globalTime;
        int endOfTaskTime = nextWorkerTime + task.getDuration();
        HashMap<Integer, GraphTaskNode> simulateIDTable = new HashMap<>();

        for (Worker worker: copyUnemployedQ){
            worker.setUnemploymentTime(globalTime);
        }

        if (assignIdleAtStart) {
            while (!copyTasksPQ.isEmpty() && !copyUnemployedQ.isEmpty()) {
                Worker toGiveTask = new Worker(copyUnemployedQ.poll());
                toGiveTask.setDesk(new GraphTaskNode(copyTasksPQ.poll()));
                toGiveTask.setTaskEstimatedEndTime(toGiveTask.getDesk().getDuration() + globalTime);
                copyWorkerPQ.add(toGiveTask);
            }
        }

        while (nextWorkerTime < endOfTaskTime) {
            if (!copyWorkerPQ.isEmpty()) {
                Worker currentWorker = new Worker(copyWorkerPQ.poll());
                nextWorkerTime = currentWorker.getTaskEstimatedEndTime();
                if (nextWorkerTime>endOfTaskTime){
                    break;
                }
                if (currentWorker.getDesk()!=null && currentWorker.getDesk().getChildren()!=null) {
                    for (GraphTaskNode child : currentWorker.getDesk().getChildren()) {
                        GraphTaskNode simChild = simulateIDTable.get(child.getId());
                        if (simChild != null) {
                            simChild.setSimulateParentsRemainedNum(simChild.getSimulateParentsRemainedNum() - 1);
                            if (simChild.getSimulateParentsRemainedNum() == 0) {
                                copyTasksPQ.add(simChild);
                            }
                        }

                        else{
                            GraphTaskNode newNode = new GraphTaskNode(child);
                            simulateIDTable.put(newNode.getId(), newNode);

                            newNode.setSimulateParentsRemainedNum(
                                    child.getParentsRemained().size() - 1
                            );

                            if (newNode.getSimulateParentsRemainedNum() == 0) {
                                copyTasksPQ.add(newNode);
                            }
                        }
                    }
                }
                currentWorker.setDesk(null);
                if (!copyTasksPQ.isEmpty()) {
                    GraphTaskNode nextTask = copyTasksPQ.poll();
                    GraphTaskNode taskForWorker;
                    GraphTaskNode simNode = simulateIDTable.get(nextTask.getId());
                    if (simNode != null) {
                        taskForWorker = simNode;
                    } else {
                        taskForWorker = new GraphTaskNode(nextTask);
                    }
                    currentWorker.setDesk(taskForWorker);
                    currentWorker.setTaskEstimatedEndTime(currentWorker.getDesk().getDuration()+nextWorkerTime);
                    copyWorkerPQ.add(currentWorker);
                } else {
                    currentWorker.setUnemploymentTime(nextWorkerTime);
                    copyUnemployedQ.add(currentWorker);
                }
                while (!copyTasksPQ.isEmpty() && !copyUnemployedQ.isEmpty()) {
                    Worker toGiveTask = new Worker(copyUnemployedQ.poll());
                    totalUnemployment = totalUnemployment + toGiveTask.getUnemploymentTime(nextWorkerTime);
                    toGiveTask.setDesk(new GraphTaskNode(copyTasksPQ.poll()));
                    toGiveTask.setTaskEstimatedEndTime(toGiveTask.getDesk().getDuration()+nextWorkerTime);
                    copyWorkerPQ.add(toGiveTask);
                }
            } else {
                nextWorkerTime=endOfTaskTime;

            }
        }
        for (GraphTaskNode child : task.getChildren()) {
            GraphTaskNode simChild = simulateIDTable.get(child.getId());

            if (simChild != null) {
                simChild.setSimulateParentsRemainedNum(
                        simChild.getSimulateParentsRemainedNum() - 1
                );
                if (simChild.getSimulateParentsRemainedNum() == 0) {
                    copyTasksPQ.add(simChild);
                }
            } else {
                GraphTaskNode newNode = new GraphTaskNode(child);
                simulateIDTable.put(newNode.getId(), newNode);

                newNode.setSimulateParentsRemainedNum(
                        child.getParentsRemained().size() - 1
                );

                if (newNode.getSimulateParentsRemainedNum() == 0) {
                    copyTasksPQ.add(newNode);
                }
            }
        }
        int minLeftDuration = task.getDuration();
        for (GraphTaskNode tasksLeft : copyTasksPQ){
            minLeftDuration =Math.min(minLeftDuration,tasksLeft.getDuration());

        }
        int tasksReady = copyTasksPQ.size(); //
        while (!copyUnemployedQ.isEmpty() && tasksReady>1){
            totalUnemployment = totalUnemployment + copyUnemployedQ.poll().getUnemploymentTime(endOfTaskTime);
            tasksReady--;
        }
        int numOfUnemployedAfter = 0;
        while (!copyUnemployedQ.isEmpty() ){
            totalUnemployment = totalUnemployment + copyUnemployedQ.poll().getUnemploymentTime(endOfTaskTime);
            numOfUnemployedAfter++;
        }

        totalUnemployment = totalUnemployment + (minLeftDuration*numOfUnemployedAfter);
        return totalUnemployment;
    }

    /** Index-based, read-only view of one task graph, used by the rollout simulation. */
    static final class RolloutModel {
        private final GraphTaskNode[] nodes;
        private final HashMap<Integer, Integer> indexById;
        private final int[] duration;
        private final int[] bottomLevel;
        private final int[][] children;
        private final int[] rank;

        RolloutModel(List<GraphTaskNode> topologicalSort) {
            int n = topologicalSort.size();
            nodes = topologicalSort.toArray(new GraphTaskNode[0]);
            indexById = new HashMap<>(n * 2);
            duration = new int[n];
            bottomLevel = new int[n];
            children = new int[n][];
            for (int i = 0; i < n; i++) {
                indexById.put(nodes[i].getId(), i);
                duration[i] = nodes[i].getDuration();
                bottomLevel[i] = nodes[i].getCriticalPaths();
            }
            for (int i = 0; i < n; i++) {
                List<GraphTaskNode> ch = nodes[i].getChildren();
                children[i] = new int[ch.size()];
                for (int k = 0; k < ch.size(); k++) {
                    children[i][k] = indexById.get(ch.get(k).getId());
                }
            }
            // continuation priority: highest static score first, ties by task id
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) order[i] = i;
            Arrays.sort(order, (a, b) -> {
                int c = Double.compare(nodes[b].getScore(), nodes[a].getScore());
                return c != 0 ? c : Integer.compare(nodes[a].getId(), nodes[b].getId());
            });
            rank = new int[n];
            for (int r = 0; r < n; r++) rank[order[r]] = r;
        }

        private int index(GraphTaskNode node) {
            return indexById.get(node.getId());
        }
    }

    /**
     * Rollout lookahead: starts {@code candidate} now, then continues with a
     * deterministic static-priority list schedule (idle workers take ready tasks
     * at once, simultaneous completions are applied together) for {@code depth}
     * completion instants. Returns the exact makespan if the schedule finishes
     * within the horizon, otherwise a lower bound on the final makespan of the
     * reached state: max(critical-path bound of running and ready tasks,
     * now + remaining work / workers).
     *
     * @param otherReady ready tasks other than the candidate
     * @param employed   workers currently running a task (their desk and end time)
     * @param idleWorkers workers free at {@code globalTime} besides the deciding one
     */
    int rolloutMakespan(
            RolloutModel model,
            GraphTaskNode candidate,
            Collection<GraphTaskNode> otherReady,
            Collection<Worker> employed,
            int idleWorkers,
            int globalTime,
            int depth) {

        int n = model.nodes.length;
        int[] remaining = new int[n];
        long unstartedWork = 0;
        for (int i = 0; i < n; i++) {
            GraphTaskNode g = model.nodes[i];
            if (!g.isCompleted()) {
                remaining[i] = g.getParentsRemained().size();
                unstartedWork += model.duration[i];
            }
        }

        // running tasks encoded as (end << 32 | index); ready tasks ordered by rank
        PriorityQueue<Long> running = new PriorityQueue<>();
        PriorityQueue<Integer> ready = new PriorityQueue<>((a, b) -> Integer.compare(model.rank[a], model.rank[b]));

        for (Worker w : employed) {
            if (w.getDesk() == null) continue;
            int i = model.index(w.getDesk());
            unstartedWork -= model.duration[i];
            running.add(((long) w.getTaskEstimatedEndTime() << 32) | i);
        }
        int c = model.index(candidate);
        unstartedWork -= model.duration[c];
        running.add(((long) (globalTime + model.duration[c]) << 32) | c);
        for (GraphTaskNode g : otherReady) {
            ready.add(model.index(g));
        }

        int workers = running.size() + idleWorkers;
        int free = idleWorkers;
        int t = globalTime;
        int events = 0;
        while (true) {
            while (free > 0 && !ready.isEmpty()) {
                int j = ready.poll();
                unstartedWork -= model.duration[j];
                running.add(((long) (t + model.duration[j]) << 32) | j);
                free--;
            }
            if (running.isEmpty()) {
                return t;
            }
            if (events >= depth) {
                return makespanLowerBound(model, running, ready, unstartedWork, workers, t);
            }
            t = (int) (running.peek() >>> 32);
            while (!running.isEmpty() && (int) (running.peek() >>> 32) == t) {
                int j = (int) (running.poll() & 0xffffffffL);
                free++;
                for (int ch : model.children[j]) {
                    if (--remaining[ch] == 0) {
                        ready.add(ch);
                    }
                }
            }
            events++;
        }
    }

    private static int makespanLowerBound(
            RolloutModel model,
            PriorityQueue<Long> running,
            PriorityQueue<Integer> ready,
            long unstartedWork,
            int workers,
            int t) {

        long work = unstartedWork;
        long bound = t;
        for (long r : running) {
            int end = (int) (r >>> 32);
            int j = (int) (r & 0xffffffffL);
            work += end - t;
            bound = Math.max(bound, (long) end + model.bottomLevel[j] - model.duration[j]);
        }
        for (int j : ready) {
            bound = Math.max(bound, (long) t + model.bottomLevel[j]);
        }
        long workBound = t + (work + workers - 1) / workers;
        return (int) Math.max(bound, workBound);
    }
}


