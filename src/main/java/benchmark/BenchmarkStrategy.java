package benchmark;

public enum BenchmarkStrategy {
    ADAPTIVE(
            "Adaptive Scheduler",
            "The project's full scheduler: static + dynamic scoring with a rollout look-ahead on contested decisions."
    ),
    SHORTEST_TASK_FIRST(
            "Shortest Task First",
            "Greedy baseline: always chooses the ready task with the shortest duration."
    ),
    LONGEST_TASK_FIRST(
            "Longest Task First",
            "Greedy baseline: always chooses the ready task with the longest duration."
    ),
    FIFO(
            "FIFO / Ready Order",
            "Greedy baseline: chooses the task that has been waiting in the ready queue the longest."
    ),
    CRITICAL_PATH_FIRST(
            "Critical Path First",
            "Greedy baseline: chooses the ready task with the longest remaining critical path."
    ),
    HIGHEST_STATIC_SCORE(
            "Highest Static Score",
            "Greedy baseline: chooses the highest static graph score, without dynamic scoring or look-ahead."
    ),
    LARGEST_DOWNSTREAM_WORK(
            "Largest Downstream Work",
            "Greedy baseline: chooses the ready task with the greatest reachable downstream workload."
    );

    private final String displayName;
    private final String description;

    BenchmarkStrategy(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
