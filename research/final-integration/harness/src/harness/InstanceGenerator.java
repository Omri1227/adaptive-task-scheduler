package harness;

import backend.Task;

import java.util.*;

/**
 * Deterministic generator of benchmark scheduling instances.
 *
 * An instance is fully determined by (masterSeed, index): the same pair always
 * yields the same tasks, durations and worker count, independent of thread
 * scheduling or of which other instances are generated.
 */
public final class InstanceGenerator {

    public enum Family {
        /** Random order-based DAG (edge i->j with probability p for i < j). */
        ERDOS_RENYI,
        /** Layers of random width; each task depends on 1-3 tasks of the previous one or two layers. */
        LAYERED,
        /** Nested fork-join stages (series-parallel). */
        FORK_JOIN,
        /** Random out-trees or in-trees. */
        TREE,
        /** Independent chains plus independent single tasks (low dependency). */
        CHAINS,
        /** 2-D wavefront grid: (i,j) depends on (i-1,j) and (i,j-1). */
        STENCIL,
        /** FFT butterfly network. */
        FFT,
        /** Gaussian elimination task graph. */
        GAUSSIAN
    }

    public enum DurationDistribution {
        UNIFORM_1_10, UNIFORM_1_100, LOGNORMAL, BIMODAL, UNIT
    }

    public enum SizeBucket {
        SMALL(10, 30), MEDIUM(31, 150), LARGE(151, 600), XLARGE(601, 1500), TINY(4, 10);

        final int min;
        final int max;

        SizeBucket(int min, int max) {
            this.min = min;
            this.max = max;
        }
    }

    /** One generated instance with its descriptive features. */
    public static final class Instance {
        public final String id;
        public final Family family;
        public final SizeBucket bucket;
        public final DurationDistribution durations;
        public final double pressure;
        public final LinkedList<Task> tasks;
        public final int workers;
        public final int totalWork;
        public final int criticalPath;
        public final int lowerBound;
        public final int edges;
        public final int depth;

        Instance(String id, Family family, SizeBucket bucket, DurationDistribution durations, double pressure,
                 LinkedList<Task> tasks, int workers) {
            this.id = id;
            this.family = family;
            this.bucket = bucket;
            this.durations = durations;
            this.pressure = pressure;
            this.tasks = tasks;
            this.workers = workers;
            int[] stats = stats(tasks);
            this.totalWork = stats[0];
            this.criticalPath = stats[1];
            this.edges = stats[2];
            this.depth = stats[3];
            this.lowerBound = Math.max(criticalPath, (totalWork + workers - 1) / workers);
        }

        public int size() {
            return tasks.size();
        }

        /** Average parallelism W / CP. */
        public double parallelism() {
            return (double) totalWork / criticalPath;
        }

        /** Fresh copy of the tasks (scheduler runs mutate nothing in Task, but copies keep runs independent). */
        public LinkedList<Task> copyTasks() {
            return copy(tasks);
        }

        public LinkedList<String> workerNames() {
            LinkedList<String> w = new LinkedList<>();
            for (int i = 0; i < workers; i++) w.add("W" + i);
            return w;
        }
    }

    /** Workload pressures: target workers = W / (CP * pressure); higher pressure = fewer workers. */
    static final double[] PRESSURES = {0.5, 1.0, 1.5, 3.0};

    private InstanceGenerator() {
    }

    public static Instance generate(long masterSeed, int index, Family family, SizeBucket bucket) {
        return generate(masterSeed, index, family, bucket, DurationDistribution.values());
    }

    /**
     * As above, drawing the duration distribution uniformly from {@code allowed}
     * only. With every distribution allowed (in declaration order) this is
     * exactly {@link #generate(long, int, Family, SizeBucket)}.
     */
    public static Instance generate(long masterSeed, int index, Family family, SizeBucket bucket,
                                    DurationDistribution... allowed) {
        if (allowed.length == 0) throw new IllegalArgumentException("no duration distribution allowed");
        Random r = new Random(mix(masterSeed, index, family.ordinal(), bucket.ordinal()));
        DurationDistribution dist = allowed[r.nextInt(allowed.length)];
        double pressure = PRESSURES[r.nextInt(PRESSURES.length)];
        int targetN = bucket.min + r.nextInt(bucket.max - bucket.min + 1);
        List<List<Integer>> deps = structure(family, targetN, r);
        LinkedList<Task> tasks = new LinkedList<>();
        for (int j = 0; j < deps.size(); j++) {
            LinkedList<Integer> d = new LinkedList<>();
            for (int p : deps.get(j)) d.add(p + 1);
            tasks.add(new Task("T" + (j + 1), j + 1, duration(dist, r), d));
        }
        Collections.shuffle(tasks, r);
        int[] stats = stats(tasks);
        double parallelism = (double) stats[0] / stats[1];
        int workers = (int) Math.round(parallelism / pressure);
        // at least 2 workers: with one worker every non-delay schedule has makespan W
        workers = Math.max(2, Math.min(64, workers));
        if (bucket == SizeBucket.TINY) workers = Math.min(workers, 4);
        String id = family + "-" + bucket + "-" + masterSeed + "-" + index;
        return new Instance(id, family, bucket, dist, pressure, tasks, workers);
    }

    /** {@code perCell} instances for every family x bucket combination, in a fixed order. */
    public static List<Instance> dataset(long masterSeed, int perCell, SizeBucket... buckets) {
        List<Instance> list = new ArrayList<>();
        for (SizeBucket b : buckets) {
            for (Family f : Family.values()) {
                for (int i = 0; i < perCell; i++) {
                    list.add(generate(masterSeed, i, f, b));
                }
            }
        }
        return list;
    }

    // ------------------------------------------------------------------ structures

    private static List<List<Integer>> structure(Family family, int n, Random r) {
        return switch (family) {
            case ERDOS_RENYI -> erdosRenyi(n, r);
            case LAYERED -> layered(n, r);
            case FORK_JOIN -> forkJoin(n, r);
            case TREE -> tree(n, r);
            case CHAINS -> chains(n, r);
            case STENCIL -> stencil(n, r);
            case FFT -> fft(n, r);
            case GAUSSIAN -> gaussian(n, r);
        };
    }

    private static List<List<Integer>> erdosRenyi(int n, Random r) {
        double avgParents = 0.5 + r.nextDouble() * 3.0;
        double p = Math.min(1.0, 2.0 * avgParents / Math.max(1, n - 1));
        List<List<Integer>> deps = empty(n);
        for (int j = 1; j < n; j++) {
            for (int i = 0; i < j; i++) {
                if (r.nextDouble() < p) deps.get(j).add(i);
            }
        }
        return deps;
    }

    private static List<List<Integer>> layered(int n, Random r) {
        int layers = Math.max(2, (int) Math.round(Math.sqrt(n) * (0.4 + r.nextDouble() * 1.6)));
        layers = Math.min(layers, n);
        List<List<Integer>> layerNodes = new ArrayList<>();
        for (int l = 0; l < layers; l++) layerNodes.add(new ArrayList<>());
        for (int i = 0; i < n; i++) layerNodes.get(i < layers ? i : r.nextInt(layers)).add(i);
        // renumber so that ids follow layer order (dependencies point to smaller ids)
        int[] newId = new int[n];
        int next = 0;
        for (List<Integer> layer : layerNodes) for (int x : layer) newId[x] = next++;
        List<List<Integer>> deps = empty(n);
        for (int l = 1; l < layers; l++) {
            for (int x : layerNodes.get(l)) {
                int k = 1 + r.nextInt(3);
                Set<Integer> parents = new TreeSet<>();
                for (int q = 0; q < k; q++) {
                    int back = (l >= 2 && r.nextInt(4) == 0) ? 2 : 1;
                    List<Integer> src = layerNodes.get(l - back);
                    parents.add(newId[src.get(r.nextInt(src.size()))]);
                }
                deps.get(newId[x]).addAll(parents);
            }
        }
        return deps;
    }

    private static List<List<Integer>> forkJoin(int n, Random r) {
        List<List<Integer>> deps = new ArrayList<>();
        int join = -1;
        while (deps.size() < n) {
            int fork = deps.size();
            deps.add(join < 0 ? new ArrayList<>() : new ArrayList<>(List.of(join)));
            int width = 2 + r.nextInt(8);
            List<Integer> ends = new ArrayList<>();
            for (int b = 0; b < width && deps.size() < n - 1; b++) {
                int len = 1 + r.nextInt(3);
                int prev = fork;
                for (int k = 0; k < len && deps.size() < n - 1; k++) {
                    deps.add(new ArrayList<>(List.of(prev)));
                    prev = deps.size() - 1;
                }
                ends.add(prev);
            }
            if (deps.size() >= n) break;
            join = deps.size();
            deps.add(new ArrayList<>(ends));
        }
        return deps;
    }

    private static List<List<Integer>> tree(int n, Random r) {
        boolean inTree = r.nextBoolean();
        List<List<Integer>> deps = empty(n);
        for (int j = 1; j < n; j++) {
            int parent = Math.max(0, j - 1 - r.nextInt(Math.min(j, 1 + r.nextInt(8))));
            if (inTree) {
                // reverse orientation: node (n-1-j) feeds node (n-1-parent)
                deps.get(n - 1 - parent).add(n - 1 - j);
            } else {
                deps.get(j).add(parent);
            }
        }
        return deps;
    }

    private static List<List<Integer>> chains(int n, Random r) {
        List<List<Integer>> deps = new ArrayList<>();
        int singles = r.nextInt(Math.max(1, n / 3));
        while (deps.size() < n - singles) {
            int len = 1 + r.nextInt(Math.max(1, Math.min(12, n / 3)));
            for (int k = 0; k < len && deps.size() < n - singles; k++) {
                deps.add(k == 0 ? new ArrayList<>() : new ArrayList<>(List.of(deps.size() - 1)));
            }
        }
        while (deps.size() < n) deps.add(new ArrayList<>());
        return deps;
    }

    private static List<List<Integer>> stencil(int n, Random r) {
        int rows = Math.max(1, (int) Math.round(Math.sqrt(n) * (0.5 + r.nextDouble())));
        int cols = Math.max(1, (int) Math.ceil((double) n / rows));
        List<List<Integer>> deps = new ArrayList<>();
        for (int i = 0; i < rows && deps.size() < n; i++) {
            for (int j = 0; j < cols && deps.size() < n; j++) {
                List<Integer> d = new ArrayList<>();
                if (i > 0) d.add((i - 1) * cols + j);
                if (j > 0) d.add(i * cols + j - 1);
                deps.add(d);
            }
        }
        return deps;
    }

    private static List<List<Integer>> fft(int n, Random r) {
        // 2^k points and k butterfly stages: 2^k * (k + 1) tasks, the largest size not above n
        int k = 1;
        while ((1 << (k + 1)) * (k + 2) <= n) k++;
        int width = 1 << k;
        List<List<Integer>> deps = new ArrayList<>();
        for (int x = 0; x < width; x++) deps.add(new ArrayList<>());
        for (int s = 0; s < k; s++) {
            int base = s * width;
            for (int x = 0; x < width; x++) {
                int partner = x ^ (1 << s);
                deps.add(new ArrayList<>(List.of(base + x, base + partner)));
            }
        }
        return deps;
    }

    private static List<List<Integer>> gaussian(int n, Random r) {
        // matrix size s gives s(s+1)/2 - 1 tasks: pivot k, then updates (k, j) for j > k
        int s = 2;
        while ((s + 1) * (s + 2) / 2 - 1 <= n) s++;
        List<List<Integer>> deps = new ArrayList<>();
        Map<Long, Integer> idOf = new HashMap<>();
        for (int k = 0; k < s - 1; k++) {
            int pivot = deps.size();
            List<Integer> pd = new ArrayList<>();
            if (k > 0) pd.add(idOf.get(key(k - 1, k)));
            deps.add(pd);
            idOf.put(key(k, k), pivot);
            for (int j = k + 1; j < s; j++) {
                List<Integer> d = new ArrayList<>(List.of(pivot));
                if (k > 0) d.add(idOf.get(key(k - 1, j)));
                idOf.put(key(k, j), deps.size());
                deps.add(d);
            }
        }
        return deps;
    }

    private static long key(int a, int b) {
        return ((long) a << 32) | b;
    }

    private static List<List<Integer>> empty(int n) {
        List<List<Integer>> deps = new ArrayList<>();
        for (int i = 0; i < n; i++) deps.add(new ArrayList<>());
        return deps;
    }

    // ------------------------------------------------------------------ helpers

    private static int duration(DurationDistribution dist, Random r) {
        return switch (dist) {
            case UNIFORM_1_10 -> 1 + r.nextInt(10);
            case UNIFORM_1_100 -> 1 + r.nextInt(100);
            case LOGNORMAL -> (int) Math.max(1, Math.min(200, Math.round(Math.exp(Math.log(10) + r.nextGaussian()))));
            case BIMODAL -> r.nextInt(5) == 0 ? 40 + r.nextInt(21) : 1 + r.nextInt(5);
            case UNIT -> 1;
        };
    }

    /** [total work, critical path, edges, depth in tasks]. */
    static int[] stats(LinkedList<Task> tasks) {
        Map<Integer, Task> byId = new HashMap<>();
        for (Task t : tasks) byId.put(t.getId(), t);
        Map<Integer, Integer> finish = new HashMap<>();
        Map<Integer, Integer> level = new HashMap<>();
        int work = 0;
        int edges = 0;
        for (Task t : tasks) {
            work += t.getDuration();
            edges += t.getDependsOnIDS().size();
        }
        int cp = 0;
        int depth = 0;
        for (Task t : tasks) {
            cp = Math.max(cp, finishTime(t, byId, finish));
            depth = Math.max(depth, level(t, byId, level));
        }
        return new int[]{work, cp, edges, depth};
    }

    private static int finishTime(Task t, Map<Integer, Task> byId, Map<Integer, Integer> memo) {
        Integer known = memo.get(t.getId());
        if (known != null) return known;
        // iterative DFS to avoid deep recursion on long chains
        Deque<Task> stack = new ArrayDeque<>(List.of(t));
        while (!stack.isEmpty()) {
            Task cur = stack.peek();
            boolean ready = true;
            for (int p : cur.getDependsOnIDS()) {
                if (!memo.containsKey(p)) {
                    stack.push(byId.get(p));
                    ready = false;
                }
            }
            if (ready) {
                stack.pop();
                int start = 0;
                for (int p : cur.getDependsOnIDS()) start = Math.max(start, memo.get(p));
                memo.put(cur.getId(), start + cur.getDuration());
            }
        }
        return memo.get(t.getId());
    }

    private static int level(Task t, Map<Integer, Task> byId, Map<Integer, Integer> memo) {
        Integer known = memo.get(t.getId());
        if (known != null) return known;
        Deque<Task> stack = new ArrayDeque<>(List.of(t));
        while (!stack.isEmpty()) {
            Task cur = stack.peek();
            boolean ready = true;
            for (int p : cur.getDependsOnIDS()) {
                if (!memo.containsKey(p)) {
                    stack.push(byId.get(p));
                    ready = false;
                }
            }
            if (ready) {
                stack.pop();
                int l = 0;
                for (int p : cur.getDependsOnIDS()) l = Math.max(l, memo.get(p));
                memo.put(cur.getId(), l + 1);
            }
        }
        return memo.get(t.getId());
    }

    static LinkedList<Task> copy(LinkedList<Task> tasks) {
        LinkedList<Task> c = new LinkedList<>();
        for (Task t : tasks) c.add(new Task(t.getName(), t.getId(), t.getDuration(), new LinkedList<>(t.getDependsOnIDS())));
        return c;
    }

    private static long mix(long seed, long a, long b, long c) {
        long h = seed * 0x9E3779B97F4A7C15L;
        h ^= a + 0x632BE59BD9B4E019L + (h << 6) + (h >>> 2);
        h ^= b * 0xD6E8FEB86659FD93L + (h << 6) + (h >>> 2);
        h ^= c * 0xA0761D6478BD642FL + (h << 6) + (h >>> 2);
        return h;
    }
}
