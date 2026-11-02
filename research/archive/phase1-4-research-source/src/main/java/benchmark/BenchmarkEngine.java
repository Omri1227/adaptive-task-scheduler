package benchmark;

import backend.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Runs the same immutable ProjectScenario under several scheduling policies.
 *
 * Every benchmark assumes deterministic task durations and no runtime changes
 * (no workers/tasks added or removed, and no early/late completion events).
 * This makes the displayed makespans directly comparable.
 *
 * Completion semantics: the adaptive scheduler always uses the Controller's
 * semantics (SchedulerConfig.getCompletionMode(), SEQUENTIAL by default). The
 * greedy baselines use BATCHED semantics in benchmark(scenario) and the mode
 * passed explicitly in the other overloads.
 */
public final class BenchmarkEngine {

    private BenchmarkEngine() {
    }

    public static BenchmarkReport benchmark(ProjectScenario scenario) {
        if (scenario == null) {
            throw new IllegalArgumentException("scenario cannot be null");
        }

        EnumMap<BenchmarkStrategy, Integer> results =
                new EnumMap<>(BenchmarkStrategy.class);

        results.put(
                BenchmarkStrategy.ADAPTIVE,
                runAdaptiveScheduler(scenario)
        );

        for (BenchmarkStrategy strategy : BenchmarkStrategy.values()) {
            if (strategy == BenchmarkStrategy.ADAPTIVE) {
                continue;
            }

            results.put(
                    strategy,
                    runGreedyScheduler(scenario, strategy)
            );
        }

        return new BenchmarkReport(scenario.getName(), results);
    }

    /**
     * Same as {@link #benchmark(ProjectScenario)}, but the adaptive scheduler uses
     * {@code adaptiveConfig} and the greedy baselines process simultaneous
     * completions according to {@code baselineMode}. The original overload uses
     * {@link SchedulerConfig#DEFAULT} and BATCHED baselines.
     */
    public static BenchmarkReport benchmark(
            ProjectScenario scenario,
            SchedulerConfig adaptiveConfig,
            SchedulerConfig.CompletionMode baselineMode) {

        if (scenario == null) {
            throw new IllegalArgumentException("scenario cannot be null");
        }

        EnumMap<BenchmarkStrategy, Integer> results =
                new EnumMap<>(BenchmarkStrategy.class);

        for (BenchmarkStrategy strategy : BenchmarkStrategy.values()) {
            results.put(strategy, makespan(scenario, strategy, adaptiveConfig, baselineMode));
        }

        return new BenchmarkReport(scenario.getName(), results);
    }

    /**
     * Makespan of one strategy. ADAPTIVE runs the real Controller with
     * {@code adaptiveConfig}; every other strategy is a greedy list scheduler
     * whose simultaneous completions follow {@code baselineMode}.
     */
    public static int makespan(
            ProjectScenario scenario,
            BenchmarkStrategy strategy,
            SchedulerConfig adaptiveConfig,
            SchedulerConfig.CompletionMode baselineMode) {

        return makespan(scenario, strategy, adaptiveConfig, baselineMode, null);
    }

    /**
     * As above, reusing a precomputed static analysis of the scenario's tasks
     * (see StaticGraphAnalysis); null computes it per run.
     */
    public static int makespan(
            ProjectScenario scenario,
            BenchmarkStrategy strategy,
            SchedulerConfig adaptiveConfig,
            SchedulerConfig.CompletionMode baselineMode,
            StaticGraphAnalysis analysis) {

        if (strategy == BenchmarkStrategy.ADAPTIVE) {
            return runAdaptiveScheduler(scenario, adaptiveConfig, analysis);
        }
        return runGreedyScheduler(scenario, strategy, baselineMode, analysis);
    }

    /**
     * Uses the real Controller so the benchmarked Adaptive value is exactly the
     * algorithm used by the live visualization.
     */
    private static int runAdaptiveScheduler(ProjectScenario scenario) {
        return runAdaptiveScheduler(scenario, SchedulerConfig.DEFAULT);
    }

    private static int runAdaptiveScheduler(ProjectScenario scenario, SchedulerConfig config) {
        return runAdaptiveScheduler(scenario, config, null);
    }

    private static int runAdaptiveScheduler(
            ProjectScenario scenario, SchedulerConfig config, StaticGraphAnalysis analysis) {
        Controller controller = new Controller(
                scenario.getTasks(),
                scenario.getWorkers(),
                false,
                config,
                analysis
        );

        controller.initial();

        while (controller.getTasksLeft() > 0) {
            if (controller.getActiveWorkers().isEmpty()) {
                throw new IllegalStateException(
                        "adaptive scheduler deadlocked in scenario: "
                                + scenario.getName());
            }

            controller.workerFinishOnTime();
        }

        return controller.getGlobalTime();
    }

    private static int runGreedyScheduler(
            ProjectScenario scenario,
            BenchmarkStrategy strategy) {
        return runGreedyScheduler(scenario, strategy, SchedulerConfig.CompletionMode.BATCHED);
    }

    /**
     * BATCHED: every completion at the current time is applied before free
     * workers choose (original behaviour). SEQUENTIAL: one completion is applied
     * at a time and free workers choose immediately, mirroring Controller.
     */
    private static int runGreedyScheduler(
            ProjectScenario scenario,
            BenchmarkStrategy strategy,
            SchedulerConfig.CompletionMode mode) {
        return runGreedyScheduler(scenario, strategy, mode, null);
    }

    private static int runGreedyScheduler(
            ProjectScenario scenario,
            BenchmarkStrategy strategy,
            SchedulerConfig.CompletionMode mode,
            StaticGraphAnalysis analysis) {

        if (strategy == BenchmarkStrategy.ADAPTIVE) {
            throw new IllegalArgumentException(
                    "adaptive strategy must use runAdaptiveScheduler");
        }

        LinkedList<Task> taskCopies = scenario.getTasks();
        TaskEngine engine = new TaskEngine(taskCopies, SchedulerConfig.DEFAULT, analysis);
        engine.initial();

        Map<Integer, GraphTaskNode> nodes = new HashMap<>();
        Map<Integer, Integer> originalOrder = new HashMap<>();

        for (int i = 0; i < taskCopies.size(); i++) {
            originalOrder.put(taskCopies.get(i).getId(), i);
        }

        for (GraphTaskNode node : engine.getTopologicalSort()) {
            nodes.put(node.getId(), node);
        }

        Map<Integer, Integer> remainingParents = new HashMap<>();
        for (GraphTaskNode node : nodes.values()) {
            remainingParents.put(node.getId(), node.getParents().size());
        }

        List<ReadyTask> ready = new ArrayList<>();
        long readySequence = 0;

        List<GraphTaskNode> initialReady = nodes.values().stream()
                .filter(node -> remainingParents.get(node.getId()) == 0)
                .sorted(Comparator.comparingInt(
                        node -> originalOrder.get(node.getId())))
                .toList();

        for (GraphTaskNode node : initialReady) {
            ready.add(new ReadyTask(node, readySequence++));
        }

        // SEQUENTIAL processes equal finish times in assignment order, exactly like
        // the Controller's worker queue (end time, then insertion order).
        PriorityQueue<RunningTask> running = new PriorityQueue<>(
                mode == SchedulerConfig.CompletionMode.SEQUENTIAL
                        ? Comparator.comparingInt(RunningTask::finishTime)
                                .thenComparingLong(RunningTask::assignment)
                        : Comparator.comparingInt(RunningTask::finishTime)
                                .thenComparingInt(RunningTask::workerId)
                                .thenComparingInt(RunningTask::taskId)
        );
        long assignmentCounter = 0;

        int workerCount = scenario.getWorkerCount();
        int freeWorkers = workerCount;
        int currentTime = 0;
        int completedCount = 0;
        Set<Integer> scheduled = new HashSet<>();

        while (completedCount < nodes.size()) {
            while (freeWorkers > 0 && !ready.isEmpty()) {
                ReadyTask selected = selectTask(
                        ready,
                        strategy,
                        originalOrder
                );

                ready.remove(selected);

                if (!scheduled.add(selected.node().getId())) {
                    throw new IllegalStateException(
                            "task scheduled twice: " + selected.node().getId());
                }

                int workerId = firstFreeWorkerId(running, workerCount);
                running.add(new RunningTask(
                        selected.node().getId(),
                        workerId,
                        currentTime + selected.node().getDuration(),
                        assignmentCounter++
                ));
                freeWorkers--;
            }

            if (running.isEmpty()) {
                throw new IllegalStateException(
                        "greedy scheduler deadlocked in scenario "
                                + scenario.getName()
                                + " using " + strategy.getDisplayName());
            }

            currentTime = running.peek().finishTime();

            List<RunningTask> finishingNow = new ArrayList<>();
            if (mode == SchedulerConfig.CompletionMode.SEQUENTIAL) {
                finishingNow.add(running.poll());
            } else {
                while (!running.isEmpty()
                        && running.peek().finishTime() == currentTime) {
                    finishingNow.add(running.poll());
                }
            }

            freeWorkers += finishingNow.size();
            completedCount += finishingNow.size();

            // Process all simultaneous completions before releasing newly-ready
            // work, so the result does not depend on event ordering.
            Set<Integer> newlyReadyIds = new HashSet<>();

            for (RunningTask finished : finishingNow) {
                GraphTaskNode completed = nodes.get(finished.taskId());

                for (GraphTaskNode child : completed.getChildren()) {
                    int childId = child.getId();
                    int left = remainingParents.get(childId) - 1;
                    remainingParents.put(childId, left);

                    if (left == 0) {
                        newlyReadyIds.add(childId);
                    } else if (left < 0) {
                        throw new IllegalStateException(
                                "negative remaining dependency count for task "
                                        + childId);
                    }
                }
            }

            List<Integer> orderedNewReady = newlyReadyIds.stream()
                    .sorted(Comparator.comparingInt(originalOrder::get))
                    .toList();

            for (Integer taskId : orderedNewReady) {
                ready.add(new ReadyTask(nodes.get(taskId), readySequence++));
            }
        }

        return currentTime;
    }

    private static ReadyTask selectTask(
            List<ReadyTask> ready,
            BenchmarkStrategy strategy,
            Map<Integer, Integer> originalOrder) {

        Comparator<ReadyTask> tieByOriginalOrder =
                Comparator.comparingInt(
                        readyTask -> originalOrder.get(readyTask.node().getId()));

        Comparator<ReadyTask> comparator = switch (strategy) {
            case SHORTEST_TASK_FIRST ->
                    Comparator.comparingInt(
                                    (ReadyTask value) -> value.node().getDuration())
                            .thenComparing(tieByOriginalOrder);

            case LONGEST_TASK_FIRST ->
                    Comparator.<ReadyTask>comparingInt(
                                    value -> value.node().getDuration())
                            .reversed()
                            .thenComparing(tieByOriginalOrder);

            case FIFO ->
                    Comparator.comparingLong(ReadyTask::readySequence)
                            .thenComparing(tieByOriginalOrder);

            case CRITICAL_PATH_FIRST ->
                    Comparator.<ReadyTask>comparingInt(
                                    value -> value.node().getCriticalPaths())
                            .reversed()
                            .thenComparing(tieByOriginalOrder);

            case HIGHEST_STATIC_SCORE ->
                    Comparator.<ReadyTask>comparingDouble(
                                    value -> value.node().getScore())
                            .reversed()
                            .thenComparing(tieByOriginalOrder);

            case LARGEST_DOWNSTREAM_WORK ->
                    Comparator.<ReadyTask>comparingInt(
                                    value -> value.node().getReachableDuration())
                            .reversed()
                            .thenComparing(tieByOriginalOrder);

            case ADAPTIVE -> throw new IllegalArgumentException(
                    "adaptive strategy does not use greedy selection");
        };

        return ready.stream()
                .min(comparator)
                .orElseThrow();
    }

    /**
     * Worker identity has no effect on greedy choices, but keeping stable worker
     * IDs makes simultaneous finish events deterministic.
     */
    private static int firstFreeWorkerId(
            PriorityQueue<RunningTask> running,
            int workerCount) {

        boolean[] busy = new boolean[workerCount];
        for (RunningTask task : running) {
            busy[task.workerId()] = true;
        }

        for (int i = 0; i < busy.length; i++) {
            if (!busy[i]) {
                return i;
            }
        }

        throw new IllegalStateException("no free worker found");
    }

    private record ReadyTask(GraphTaskNode node, long readySequence) {
    }

    private record RunningTask(int taskId, int workerId, int finishTime, long assignment) {
    }
}
