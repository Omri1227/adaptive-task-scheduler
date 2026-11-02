package backend;

import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

public class StaticGraphAnalysisTest {

    private static LinkedList<Task> randomDag(Random r, int n, double p) {
        LinkedList<Task> tasks = new LinkedList<>();
        for (int j = 0; j < n; j++) {
            LinkedList<Integer> d = new LinkedList<>();
            for (int i = 0; i < j; i++) if (r.nextDouble() < p) d.add(i + 1);
            tasks.add(new Task("T" + (j + 1), j + 1, 1 + r.nextInt(30), d));
        }
        Collections.shuffle(tasks, r);
        return tasks;
    }

    private static LinkedList<Task> copy(LinkedList<Task> tasks) {
        LinkedList<Task> c = new LinkedList<>();
        for (Task t : tasks) c.add(new Task(t.getName(), t.getId(), t.getDuration(), new LinkedList<>(t.getDependsOnIDS())));
        return c;
    }

    /** Independent oracle: descendants (including self) by DFS over the task list. */
    private static Map<Integer, Set<Integer>> descendants(LinkedList<Task> tasks) {
        Map<Integer, List<Integer>> children = new HashMap<>();
        for (Task t : tasks) children.putIfAbsent(t.getId(), new ArrayList<>());
        for (Task t : tasks) for (int p : t.getDependsOnIDS()) children.get(p).add(t.getId());
        Map<Integer, Set<Integer>> result = new HashMap<>();
        for (Task t : tasks) {
            Set<Integer> seen = new HashSet<>();
            Deque<Integer> stack = new ArrayDeque<>(List.of(t.getId()));
            while (!stack.isEmpty()) {
                int x = stack.pop();
                if (seen.add(x)) stack.addAll(children.get(x));
            }
            result.put(t.getId(), seen);
        }
        return result;
    }

    @Test
    public void bitSetReachability_matchesIndependentOracle() {
        Random r = new Random(11);
        for (int it = 0; it < 200; it++) {
            LinkedList<Task> tasks = randomDag(r, 1 + r.nextInt(60), r.nextDouble() * 0.4);
            Map<Integer, Integer> duration = new HashMap<>();
            for (Task t : tasks) duration.put(t.getId(), t.getDuration());
            Map<Integer, Set<Integer>> oracle = descendants(tasks);

            TaskEngine engine = new TaskEngine(copy(tasks));
            engine.initial();
            for (GraphTaskNode node : engine.getTopologicalSort()) {
                Set<Integer> expected = oracle.get(node.getId());
                int sum = 0;
                for (int id : expected) sum += duration.get(id);
                assertEquals(sum, node.getReachableDuration());
                Set<Integer> actual = new HashSet<>();
                for (GraphTaskNode g : node.getReachableSet()) actual.add(g.getId());
                assertEquals(expected, actual);
            }
        }
    }

    @Test
    public void reusedAnalysis_givesIdenticalScoresAndSchedulesForAnyConfig() {
        Random r = new Random(3);
        SchedulerConfig[] configs = {
                SchedulerConfig.DEFAULT,
                SchedulerConfig.builder().staticWeights(0.2, 0.6, 0.2).build(),
                SchedulerConfig.builder().staticWeights(1, 0, 0).staticShare(0.9).lookaheadWeight(0.3).build()
        };
        for (int it = 0; it < 60; it++) {
            LinkedList<Task> tasks = randomDag(r, 2 + r.nextInt(50), r.nextDouble() * 0.3);
            StaticGraphAnalysis analysis = StaticGraphAnalysis.of(tasks);
            int m = 1 + r.nextInt(6);
            for (SchedulerConfig config : configs) {
                assertEquals(run(copy(tasks), m, config, null), run(copy(tasks), m, config, analysis));
            }
        }
    }

    @Test
    public void analysis_isUnaffectedByLaterMutationOfTheSourceList() {
        LinkedList<Task> tasks = randomDag(new Random(9), 20, 0.2);
        StaticGraphAnalysis analysis = StaticGraphAnalysis.of(tasks);
        LinkedList<Task> fresh = copy(tasks);
        tasks.get(0).getDependsOnIDS().clear();
        assertTrue(analysis.matches(fresh));
    }

    @Test(expected = IllegalArgumentException.class)
    public void analysis_rejectsDifferentDurations() {
        LinkedList<Task> tasks = randomDag(new Random(4), 10, 0.3);
        StaticGraphAnalysis analysis = StaticGraphAnalysis.of(tasks);
        LinkedList<Task> other = copy(tasks);
        Task t = other.removeFirst();
        other.addFirst(new Task(t.getName(), t.getId(), t.getDuration() + 1, t.getDependsOnIDS()));
        new TaskEngine(other, SchedulerConfig.DEFAULT, analysis);
    }

    @Test(expected = IllegalArgumentException.class)
    public void analysis_rejectsDifferentListOrder() {
        LinkedList<Task> tasks = new LinkedList<>();
        for (int i = 1; i <= 6; i++) tasks.add(new Task("T" + i, i, i, new LinkedList<>()));
        StaticGraphAnalysis analysis = StaticGraphAnalysis.of(tasks);
        LinkedList<Task> reversed = copy(tasks);
        Collections.reverse(reversed);
        new TaskEngine(reversed, SchedulerConfig.DEFAULT, analysis).initial();
    }

    private static String run(LinkedList<Task> tasks, int m, SchedulerConfig config, StaticGraphAnalysis analysis) {
        LinkedList<String> w = new LinkedList<>();
        for (int i = 0; i < m; i++) w.add("W" + i);
        Controller c = new Controller(tasks, w, false, config, analysis);
        c.initial();
        StringBuilder sb = new StringBuilder();
        for (GraphTaskNode g : c.getAllTasks()) {
            sb.append(g.getId()).append(':').append(g.getScore()).append(':').append(g.getReachableSet().size()).append(';');
        }
        while (c.getTasksLeft() > 0) {
            for (Worker wk : c.getActiveWorkers()) sb.append(wk.getDesk().getId()).append('@').append(wk.getStartTime()).append(',');
            c.workerFinishOnTime();
        }
        return sb.append(c.getGlobalTime()).toString();
    }
}
