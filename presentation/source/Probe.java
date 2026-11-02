import backend.*;
import benchmark.*;

import java.nio.file.*;
import java.util.*;

/** Reads instances (NAME workers / id dur parents|- ... / END) and prints makespans per policy. */
public class Probe {
    static SchedulerConfig.Builder listBase() {
        return SchedulerConfig.builder()
                .lookaheadMode(SchedulerConfig.LookaheadMode.NONE)
                .completionMode(SchedulerConfig.CompletionMode.BATCHED)
                .deterministicTieBreak(true);
    }

    public static void main(String[] args) throws Exception {
        List<String> lines = Files.readAllLines(Path.of(args[0]));
        Map<String, SchedulerConfig> cfg = new LinkedHashMap<>();
        cfg.put("ORIGINAL", SchedulerConfig.DEFAULT);
        cfg.put("CP_ONLY", listBase().staticWeights(1, 0, 0).staticShare(1).candidatePoolSize(1).build());
        cfg.put("BP_ONLY", listBase().staticWeights(0, 1, 0).staticShare(1).candidatePoolSize(1).build());
        cfg.put("REACH_ONLY", listBase().staticWeights(0, 0, 1).staticShare(1).candidatePoolSize(1).build());
        cfg.put("STATIC_ORIG", listBase().staticShare(1).candidatePoolSize(1).build());
        String[] dn = {"D_OPEN2BLOCK", "D_OPENCHILD", "D_CHILDIMP", "D_DUR2READY", "D_CHILD2PQ"};
        for (int k = 0; k < 5; k++) {
            double[] w = new double[5];
            w[k] = 1;
            cfg.put(dn[k], listBase().staticShare(0).dynamicWeights(w[0], w[1], w[2], w[3], w[4])
                    .candidatePoolSize(SchedulerConfig.ALL_READY_TASKS).build());
        }
        cfg.put("PHASE1_ROLLOUT", SchedulerConfig.PHASE1_ROLLOUT);
        cfg.put("FAST_OPT", SchedulerConfig.FAST_OPTIMIZED);
        cfg.put("QUALITY_OPT", SchedulerConfig.QUALITY_OPTIMIZED);

        int i = 0;
        while (i < lines.size()) {
            String h = lines.get(i++).trim();
            if (h.isEmpty()) continue;
            String[] hp = h.split("\\s+");
            String name = hp[0];
            int workers = Integer.parseInt(hp[1]);
            LinkedList<Task> tasks = new LinkedList<>();
            while (!lines.get(i).trim().equals("END")) {
                String[] p = lines.get(i++).trim().split("\\s+");
                LinkedList<Integer> deps = new LinkedList<>();
                if (!p[2].equals("-")) for (String s : p[2].split(",")) deps.add(Integer.parseInt(s));
                tasks.add(new Task("T" + p[0], Integer.parseInt(p[0]), Integer.parseInt(p[1]), deps));
            }
            i++;
            LinkedList<String> wn = new LinkedList<>();
            for (int w = 0; w < workers; w++) wn.add("W" + w);
            ProjectScenario sc = new ProjectScenario(1, name, "d", "f", tasks, wn);
            StringBuilder sb = new StringBuilder(name);
            for (var e : cfg.entrySet()) {
                int ms = BenchmarkEngine.makespan(sc, BenchmarkStrategy.ADAPTIVE, e.getValue(),
                        SchedulerConfig.CompletionMode.BATCHED);
                sb.append(' ').append(e.getKey()).append('=').append(ms);
            }
            for (BenchmarkStrategy s : new BenchmarkStrategy[]{BenchmarkStrategy.CRITICAL_PATH_FIRST,
                    BenchmarkStrategy.LARGEST_DOWNSTREAM_WORK, BenchmarkStrategy.SHORTEST_TASK_FIRST}) {
                sb.append(' ').append(s.name()).append('=')
                        .append(BenchmarkEngine.makespan(sc, s, SchedulerConfig.DEFAULT,
                                SchedulerConfig.CompletionMode.BATCHED));
            }
            if (tasks.size() <= 20) {
                sb.append(" OPT=").append(ExactSolver.optimal(tasks, workers, 100000, 50_000_000L));
                sb.append(" BEST_LIST=").append(ExactSolver.bestNonDelay(tasks, workers, 100000, 50_000_000L));
            }
            System.out.println(sb);
            for (int a = 1; a < args.length; a++) {
                System.out.println(" " + args[a] + ":");
                System.out.print(ScheduleDump.run(tasks, wn, cfg.get(args[a])));
            }
        }
    }
}
