package backend;

import java.util.*;

/**
 * Weight-independent results of the static DAG analysis (critical paths,
 * reachable durations, blocking power and reachability), computed once per task
 * graph and reused by every scheduler run on that graph, whatever its
 * SchedulerConfig. Only TimeConstraints.scoreSet depends on the weights.
 *
 * Instances are immutable. Applying an analysis to a different task list (other
 * ids, durations, dependencies or list order) is rejected.
 */
public final class StaticGraphAnalysis {

    private final int[] topologicalIds;
    private final Map<Integer, Integer> indexById;
    private final int[] durations;
    private final int[][] sortedDependencies;
    private final int[] criticalPaths;
    private final int[] reachableDurations;
    private final double[] blockingPowers;
    private final BitSet[] reachable;
    private final int maxCritical;
    private final int totalTime;
    private final double maxBlockingPower;
    private final int maxReachableDuration;

    private StaticGraphAnalysis(TaskEngine engine, LinkedList<Task> tasks) {
        ArrayList<GraphTaskNode> topo = engine.getTopologicalSort();
        int n = topo.size();
        topologicalIds = new int[n];
        indexById = new HashMap<>(n * 2);
        durations = new int[n];
        sortedDependencies = new int[n][];
        criticalPaths = new int[n];
        reachableDurations = new int[n];
        blockingPowers = new double[n];
        reachable = new BitSet[n];

        Map<Integer, Task> taskById = new HashMap<>(n * 2);
        for (Task t : tasks) taskById.put(t.getId(), t);

        int maxCp = 0;
        int total = 0;
        double maxBlock = 0;
        int maxReach = 0;
        for (int i = 0; i < n; i++) {
            GraphTaskNode node = topo.get(i);
            topologicalIds[i] = node.getId();
            indexById.put(node.getId(), i);
            durations[i] = node.getDuration();
            sortedDependencies[i] = sortedDependencies(taskById.get(node.getId()));
            criticalPaths[i] = node.getCriticalPaths();
            reachableDurations[i] = node.getReachableDuration();
            blockingPowers[i] = node.getBlockingPower();
            reachable[i] = node.reachableBitsOver(topo);
            total += node.getDuration();
            maxBlock = Math.max(maxBlock, node.getBlockingPower());
            maxReach = Math.max(maxReach, node.getReachableDuration());
        }
        for (GraphTaskNode head : engine.getConstraintsHeads()) {
            maxCp = Math.max(maxCp, head.getCriticalPaths());
        }
        maxCritical = maxCp;
        totalTime = total;
        maxBlockingPower = maxBlock;
        maxReachableDuration = maxReach;
    }

    /** Runs the original static analysis once on a private copy of {@code tasks}. */
    public static StaticGraphAnalysis of(LinkedList<Task> tasks) {
        LinkedList<Task> copy = new LinkedList<>();
        for (Task t : tasks) {
            copy.add(new Task(t.getName(), t.getId(), t.getDuration(), new LinkedList<>(t.getDependsOnIDS())));
        }
        TaskEngine engine = new TaskEngine(copy);
        engine.initial();
        return new StaticGraphAnalysis(engine, copy);
    }

    /** True if {@code tasks} has exactly the ids, durations and dependencies this analysis was built from. */
    public boolean matches(LinkedList<Task> tasks) {
        if (tasks.size() != topologicalIds.length) {
            return false;
        }
        for (Task t : tasks) {
            Integer i = indexById.get(t.getId());
            if (i == null || durations[i] != t.getDuration()
                    || !Arrays.equals(sortedDependencies[i], sortedDependencies(t))) {
                return false;
            }
        }
        return true;
    }

    public int getTaskCount() {
        return topologicalIds.length;
    }

    public int getMaxCritical() {
        return maxCritical;
    }

    public int getTotalTime() {
        return totalTime;
    }

    public double getMaxBlockingPower() {
        return maxBlockingPower;
    }

    public int getMaxReachableDuration() {
        return maxReachableDuration;
    }

    /**
     * Copies the stored values onto {@code topologicalSort}, which must list the
     * same ids in the same topological order the analysis was computed with.
     */
    void applyTo(ArrayList<GraphTaskNode> topologicalSort) {
        if (topologicalSort.size() != topologicalIds.length) {
            throw new IllegalArgumentException("static analysis was computed for a different task graph");
        }
        for (int i = 0; i < topologicalIds.length; i++) {
            if (topologicalSort.get(i).getId() != topologicalIds[i]) {
                throw new IllegalArgumentException("static analysis was computed for a different task order");
            }
        }
        for (int i = 0; i < topologicalIds.length; i++) {
            GraphTaskNode node = topologicalSort.get(i);
            node.setMaxDescendantEndTime(criticalPaths[i]);
            node.setReachableDuration(reachableDurations[i]);
            node.setBlockingPower(blockingPowers[i]);
            node.setReachableBits(reachable[i], topologicalSort);
        }
    }

    private static int[] sortedDependencies(Task t) {
        int[] deps = new int[t.getDependsOnIDS().size()];
        int k = 0;
        for (Integer d : t.getDependsOnIDS()) deps[k++] = d;
        Arrays.sort(deps);
        return deps;
    }
}
