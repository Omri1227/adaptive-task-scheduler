package backend;

import benchmark.BenchmarkEngine;
import benchmark.BenchmarkStrategy;
import benchmark.ExactSolver;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

public class ExactSolverTest {

    private static LinkedList<Task> randomDag(Random r, int n) {
        double p = r.nextDouble() * 0.5;
        LinkedList<Task> tasks = new LinkedList<>();
        for (int j = 0; j < n; j++) {
            LinkedList<Integer> d = new LinkedList<>();
            for (int i = 0; i < j; i++) if (r.nextDouble() < p) d.add(i + 1);
            tasks.add(new Task("T" + (j + 1), j + 1, 1 + r.nextInt(6), d));
        }
        return tasks;
    }

    /** Independent oracle: best list schedule over every priority permutation. */
    private static int bruteForceList(LinkedList<Task> tasks, int m) {
        List<Task> list = new ArrayList<>(tasks);
        int[] best = {Integer.MAX_VALUE};
        permute(list, 0, perm -> best[0] = Math.min(best[0], listSchedule(perm, m)));
        return best[0];
    }

    /** Independent oracle: serial schedule generation over every precedence-feasible order (active schedules). */
    private static int bruteForceOptimal(LinkedList<Task> tasks, int m) {
        List<Task> list = new ArrayList<>(tasks);
        int[] best = {Integer.MAX_VALUE};
        permute(list, 0, perm -> {
            Map<Integer, Integer> finish = new HashMap<>();
            int horizon = 0;
            for (Task t : perm) horizon += t.getDuration();
            int[] usage = new int[horizon + 1];
            int makespan = 0;
            for (Task t : perm) {
                int es = 0;
                for (int p : t.getDependsOnIDS()) {
                    Integer f = finish.get(p);
                    if (f == null) return; // not precedence-feasible
                    es = Math.max(es, f);
                }
                int s = es;
                while (true) {
                    boolean fits = true;
                    for (int x = s; x < s + t.getDuration(); x++) if (usage[x] >= m) { fits = false; break; }
                    if (fits) break;
                    s++;
                }
                for (int x = s; x < s + t.getDuration(); x++) usage[x]++;
                finish.put(t.getId(), s + t.getDuration());
                makespan = Math.max(makespan, s + t.getDuration());
            }
            best[0] = Math.min(best[0], makespan);
        });
        return best[0];
    }

    private static int listSchedule(List<Task> priority, int m) {
        Map<Integer, Integer> rank = new HashMap<>();
        for (int i = 0; i < priority.size(); i++) rank.put(priority.get(i).getId(), i);
        Map<Integer, Integer> finish = new HashMap<>();
        Set<Integer> started = new HashSet<>();
        PriorityQueue<int[]> running = new PriorityQueue<>(Comparator.comparingInt(a -> a[0]));
        int t = 0;
        while (finish.size() < priority.size()) {
            List<Task> ready = new ArrayList<>();
            for (Task task : priority) {
                if (started.contains(task.getId())) continue;
                boolean ok = true;
                for (int p : task.getDependsOnIDS()) if (!finish.containsKey(p) || finish.get(p) > t) { ok = false; break; }
                if (ok) ready.add(task);
            }
            ready.sort(Comparator.comparingInt(x -> rank.get(x.getId())));
            for (Task task : ready) {
                if (running.size() >= m) break;
                started.add(task.getId());
                running.add(new int[]{t + task.getDuration(), task.getId()});
            }
            int next = running.peek()[0];
            while (!running.isEmpty() && running.peek()[0] == next) {
                int[] done = running.poll();
                finish.put(done[1], done[0]);
            }
            t = next;
        }
        return t;
    }

    private static void permute(List<Task> list, int k, java.util.function.Consumer<List<Task>> f) {
        if (k == list.size()) {
            f.accept(list);
            return;
        }
        for (int i = k; i < list.size(); i++) {
            Collections.swap(list, k, i);
            permute(list, k + 1, f);
            Collections.swap(list, k, i);
        }
    }

    @Test
    public void solvers_matchBruteForceOracles() {
        Random r = new Random(99);
        int strictGap = 0;
        for (int it = 0; it < 250; it++) {
            int n = 2 + r.nextInt(6);
            LinkedList<Task> tasks = randomDag(r, n);
            int m = 2 + r.nextInt(2);
            int ub = 1000;
            int list = ExactSolver.bestNonDelay(tasks, m, ub, 50_000_000);
            int opt = ExactSolver.optimal(tasks, m, ub, 50_000_000);
            assertEquals("non-delay " + it, bruteForceList(tasks, m), list);
            assertEquals("optimal " + it, bruteForceOptimal(tasks, m), opt);
            assertTrue(opt <= list);
            if (opt < list) strictGap++;
        }
        // sanity: the two notions differ on some instances
        assertTrue("expected at least one instance where idling beats every list schedule", strictGap >= 1);
    }

    @Test
    public void solvers_boundTheAdaptiveScheduler() {
        Random r = new Random(5);
        for (int it = 0; it < 150; it++) {
            LinkedList<Task> tasks = randomDag(r, 3 + r.nextInt(8));
            int m = 2 + r.nextInt(3);
            LinkedList<String> w = new LinkedList<>();
            for (int i = 0; i < m; i++) w.add("W" + i);
            int adaptive = BenchmarkEngine.makespan(new ProjectScenario(1, "x", "", "", tasks, w),
                    BenchmarkStrategy.ADAPTIVE, SchedulerConfig.DEFAULT, SchedulerConfig.CompletionMode.BATCHED);
            int list = ExactSolver.bestNonDelay(tasks, m, adaptive, 50_000_000);
            int opt = ExactSolver.optimal(tasks, m, list, 50_000_000);
            assertTrue(list <= adaptive);
            assertTrue(opt <= list);
        }
    }
}
