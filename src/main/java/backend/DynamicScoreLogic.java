package backend;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.Queue;

public class DynamicScoreLogic { // חייב ליצור מקס דיוריישן ומקס סטטיק סקור שנוכל לתת לפונקציה  (בבנייה)

    public double makeDynamicScore(
            GraphTaskNode task,
            int PQSize,
            int workerNum,
            double maxStaticScore,
            int maxDuration,
            int maxOpenChild){

        int openChildren = openChildrenCount(task);

        double childrenToPQ =
                openChildToRPQSize(
                        task,
                        openChildren,
                        PQSize,
                        workerNum,
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

        double durationToReady = durationToRPQSize(task, maxDuration, PQSize,workerNum);
        return 0.2*openToBlock + 0.1*openChildrenScore +
                0.15*childImportance + 0.3*durationToReady + 0.25*childrenToPQ;
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
                        globalTime)
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
        int totalUnemployment = 0;

        PriorityQueue<GraphTaskNode> copyTasksPQ = new PriorityQueue<>(readyPQ);
        PriorityQueue<Worker> copyWorkerPQ = new PriorityQueue<>(workersPQ);
        Queue<Worker> copyUnemployedQ = new ArrayDeque<>(unEmployed);
        int nextWorkerTime = globalTime;
        int endOfTaskTime = nextWorkerTime + task.getDuration();
        HashMap<Integer, GraphTaskNode> simulateIDTable = new HashMap<>();

        for (Worker worker: copyUnemployedQ){
            worker.setUnemploymentTime(globalTime);
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
}


