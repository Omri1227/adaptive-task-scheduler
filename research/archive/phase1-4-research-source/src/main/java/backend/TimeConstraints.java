package backend;

import java.util.*;

public class TimeConstraints {
    private LinkedList<GraphTaskNode> heads;
    private int taskTotalNum;
    private ArrayList<GraphTaskNode> topologicalSort;
    private final SchedulerConfig config;

    public TimeConstraints(LinkedList<GraphTaskNode> tasks, HashMap<Integer, GraphTaskNode> IDTable) {
        this(tasks, IDTable, SchedulerConfig.DEFAULT);
    }

    public TimeConstraints(LinkedList<GraphTaskNode> tasks, HashMap<Integer, GraphTaskNode> IDTable,
                           SchedulerConfig config) {
        this.config = config;
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
        initial(IDTable, null);
    }

    /**
     * With a precomputed analysis only the weight-dependent scoreSet runs; the
     * critical paths, reachable durations and blocking power are copied.
     */
    void initial(HashMap<Integer, GraphTaskNode> IDTable, StaticGraphAnalysis analysis) {
        if (analysis != null) {
            analysis.applyTo(topologicalSort);
            HashSet<Integer> visited = new HashSet<>(topologicalSort.size());
            int reachDenominator = config.isReachNormalizedByMaximum()
                    ? analysis.getMaxReachableDuration()
                    : analysis.getTotalTime();
            scoreSet(reachDenominator, analysis.getMaxCritical(), analysis.getMaxBlockingPower(), visited);
            return;
        }
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

        if (config.isReachNormalizedByMaximum()) {
            int maxReach = 0;
            for (GraphTaskNode node : topologicalSort) {
                maxReach = Math.max(maxReach, node.getReachableDuration());
            }
            totalTime = maxReach;
        }

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
        boolean trivial = true;
        for (GraphTaskNode node : topologicalSort) {
            if (!node.hasTrivialReachableSet()) {
                trivial = false;
                break;
            }
        }
        if (trivial) {
            reachableDurationBits();
            return;
        }
        // Reachable sets were already populated by the caller: keep the original
        // cumulative HashSet semantics exactly.
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

    /**
     * Same result as the HashSet computation, using one bit set per task over the
     * topological order (n^2/8 bytes instead of n^2 hash entries). Each node's
     * reachable set is materialized from its bits only if someone asks for it.
     */
    private void reachableDurationBits() {
        int n = topologicalSort.size();
        HashMap<GraphTaskNode, Integer> index = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            index.put(topologicalSort.get(i), i);
        }
        BitSet[] bits = new BitSet[n];
        for (int i = n - 1; i >= 0; i--) {
            GraphTaskNode current = topologicalSort.get(i);
            BitSet reach = new BitSet(n);
            reach.set(i);
            for (GraphTaskNode child : current.getChildren()) {
                reach.or(bits[index.get(child)]);
            }
            bits[i] = reach;
            int sum = 0;
            for (int k = reach.nextSetBit(0); k >= 0; k = reach.nextSetBit(k + 1)) {
                sum = sum + topologicalSort.get(k).getDuration();
            }
            current.setReachableDuration(sum);
            current.setReachableBits(reach, topologicalSort);
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
                            : config.getCriticalPathWeight() * current.getCriticalPaths() / maxCritical;

            double blockPart =
                    maxBlock == 0
                            ? 0.0
                            : config.getBlockingPowerWeight() * current.getBlockingPower() / maxBlock;

            double reachablePart =
                    totalTime == 0
                            ? 0.0
                            : config.getReachableDurationWeight() * current.getReachableDuration() / totalTime;

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
