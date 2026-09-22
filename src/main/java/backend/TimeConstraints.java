package backend;

import java.util.*;

public class TimeConstraints {
    private LinkedList<GraphTaskNode> heads;
    private int taskTotalNum;
    private ArrayList<GraphTaskNode> topologicalSort;

    public TimeConstraints(LinkedList<GraphTaskNode> tasks, HashMap<Integer, GraphTaskNode> IDTable) {
        taskTotalNum = tasks.size();
        heads = new LinkedList<>();
        for (GraphTaskNode task : tasks) {
            if (task.getParents().isEmpty()) {
                heads.add(task);
            }
        }

        topologicalSort = new ArrayList<>();
        HashSet<Integer> table = new HashSet<>();
        HashMap<Integer, Integer> inDegree = new HashMap<>();
        for (GraphTaskNode task : tasks) {
            inDegree.put(task.getId(), task.getParents().size());
        }
        Queue<GraphTaskNode> q = new ArrayDeque<>();
        for (GraphTaskNode task : heads) {
            q.add(task);

        }
        while (!q.isEmpty()) {
            GraphTaskNode current = q.poll();

            topologicalSort.add(current);

            for (GraphTaskNode child : current.getChildren()) {
                int oldVal = inDegree.get(child.getId());
                int newVal = oldVal - 1;

                inDegree.put(child.getId(), newVal);

                if (newVal == 0) {
                    q.add(child);
                }
            }
        }

        if (topologicalSort.size() != taskTotalNum) {
            throw new RuntimeException(
                    "Task graph contains a cycle");
        }
    }

    public LinkedList<GraphTaskNode> getHeads() {
        return this.heads;
    }

    public void initial(HashMap<Integer, GraphTaskNode> IDTable) {
        criticalPaths();
        int maxCritical = 0;
        for (GraphTaskNode task : heads) {
            maxCritical = Math.max(task.getCriticalPaths(), maxCritical);
        }

        int totalTime = 0;
        for (int i = 0; i < topologicalSort.size(); i++) {
            totalTime = totalTime + topologicalSort.get(i).getDuration();
        }

        reachableDuration();

        HashSet<Integer> visited1 = new HashSet<>(topologicalSort.size());
        double maxBlockPower = blockPower(visited1);

        HashSet<Integer> visited2 = new HashSet<>(topologicalSort.size());
        scoreSet(totalTime, maxCritical, maxBlockPower, visited2);
    }

    public void criticalPaths() {
        for (int i = topologicalSort.size() - 1; i >= 0; i--) {
            GraphTaskNode current = topologicalSort.get(i);
            if (current.getChildren().isEmpty()) {
                current.setMaxDescendantEndTime(current.getDuration());
            } else {
                int maxChild = 0;
                for (GraphTaskNode child : current.getChildren()) {
                    maxChild = Math.max(maxChild, child.getCriticalPaths());
                }
                current.setMaxDescendantEndTime(maxChild + current.getDuration());
            }
        }
    }


    public void reachableDuration() {
        for (int i = topologicalSort.size() - 1; i >= 0; i--) {
            GraphTaskNode current = topologicalSort.get(i);

            for (GraphTaskNode child : current.getChildren()) {
                current.addAllToReachableSet(child);

            }
            int sum = 0;
            for (GraphTaskNode task : current.getReachableSet()) {
                sum = sum + task.getDuration();
            }
            current.setReachableDuration(sum);
        }
    }

    public double blockPower(HashSet<Integer>visited){
        double maxNode = 0;
        Queue<GraphTaskNode> q = new ArrayDeque<>();
        for (GraphTaskNode task : heads) {
            q.add(task);
            visited.add(task.getId());
        }
        while (!q.isEmpty()) {
            GraphTaskNode current = q.poll();
            double courrentScore = 0;
            for (GraphTaskNode child : current.getChildren()) {
                courrentScore = courrentScore + 1.0 / child.getParents().size();
                if (!visited.contains(child.getId())){
                    q.add(child);
                    visited.add(child.getId());
                }
            }
            maxNode = Math.max(maxNode, courrentScore);
            current.setBlockingPower(courrentScore);
        }
        return maxNode;
    }



    public void scoreSet(int totalTime, int maxCritical,double maxBlock, HashSet<Integer> visited) {
        Queue<GraphTaskNode> q = new ArrayDeque<>();
        for (GraphTaskNode task : heads) {
            q.add(task);
            visited.add(task.getId());
        }
        while (!q.isEmpty()) {
            GraphTaskNode current = q.poll();
            double criticalPart =
                    maxCritical == 0
                            ? 0.0
                            : 0.5 * current.getCriticalPaths() / maxCritical;

            double blockPart =
                    maxBlock == 0
                            ? 0.0
                            : 0.25 * current.getBlockingPower() / maxBlock;

            double reachablePart =
                    totalTime == 0
                            ? 0.0
                            : 0.25 * current.getReachableDuration() / totalTime;

            current.setScore(
                    criticalPart
                            + blockPart
                            + reachablePart);

            for (GraphTaskNode child : current.getChildren()) {
                if (!visited.contains(child.getId())) {
                    visited.add(child.getId());
                    q.add(child);
                }
            }
        }
    }

    public LinkedList<GraphTaskNode> taskCompleted(GraphTaskNode task) {
        LinkedList<GraphTaskNode> newReady = new LinkedList<>();
        for (GraphTaskNode child : task.getChildren()) {
            child.removeRemain(task);
            if (child.getParentsRemained().isEmpty()) {
                newReady.add(child);
            }
        }
        task.setCompleted();
        return newReady;
    }

    public ArrayList<GraphTaskNode> getTopologicalSort() {
        return topologicalSort;
    }
}
