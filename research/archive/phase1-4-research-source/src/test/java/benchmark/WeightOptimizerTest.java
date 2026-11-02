package benchmark;

import backend.SchedulerConfig;
import benchmark.InstanceGenerator.Family;
import benchmark.InstanceGenerator.Instance;
import benchmark.InstanceGenerator.SizeBucket;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ForkJoinPool;

import static org.junit.Assert.*;

public class WeightOptimizerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    static List<Instance> small() {
        List<Instance> list = new ArrayList<>();
        for (Family f : WeightOptimizer.TRAIN_FAMILIES) {
            for (SizeBucket b : new SizeBucket[]{SizeBucket.SMALL, SizeBucket.MEDIUM}) {
                for (int i = 0; i < 6; i++) list.add(InstanceGenerator.generate(123, i, f, b, WeightOptimizer.TRAIN_DURATIONS));
            }
        }
        return list;
    }

    @Test
    public void cvar_isTheMeanOfTheWorstTail() {
        double[] x = new double[100];
        for (int i = 0; i < 100; i++) x[i] = i;
        assertEquals((95 + 96 + 97 + 98 + 99) / 5.0, WeightOptimizer.cvar(x, 0.95), 1e-12);
        assertEquals(99, WeightOptimizer.cvar(new double[]{99, 1, 2}, 0.95), 0);
    }

    @Test
    public void objective_isZeroForTheOriginalScheduler() {
        ForkJoinPool pool = new ForkJoinPool(2);
        WeightOptimizer.DataSet d = new WeightOptimizer.DataSet("t", small(), pool);
        WeightOptimizer o = new WeightOptimizer(tmp.getRoot().toPath(), 2);
        double[][] s = o.score(List.of(SchedulerConfig.DEFAULT, WeightOptimizer.trackA(1)), d, d.all());
        assertEquals(0, s[0][0], 0);
        assertNotEquals(0, s[1][0], 0);
        // deterministic
        double[][] again = o.score(List.of(WeightOptimizer.trackA(1)), d, d.all());
        assertEquals(s[1][0], again[0][0], 0);
        pool.shutdown();
        o.pool.shutdown();
    }

    @Test
    public void stratified_isBalancedDeterministicAndWithoutRepeats() {
        ForkJoinPool pool = new ForkJoinPool(2);
        WeightOptimizer.DataSet d = new WeightOptimizer.DataSet("t", small(), pool);
        int[] a = WeightOptimizer.stratified(d, d.all(), 30, new Random(5));
        int[] b = WeightOptimizer.stratified(d, d.all(), 30, new Random(5));
        assertArrayEquals(a, b);
        assertEquals(30, a.length);
        assertEquals(30, Arrays.stream(a).distinct().count());
        Map<String, Integer> perCell = new HashMap<>();
        for (int i : a) perCell.merge(d.instances.get(i).family + "/" + d.instances.get(i).bucket, 1, Integer::sum);
        assertEquals(12, perCell.size());
        for (int c : perCell.values()) assertTrue(c == 2 || c == 3);
        pool.shutdown();
    }

    @Test
    public void datasets_areDisjointAndHoldOutFamiliesAndBimodal() {
        Set<String> train = new HashSet<>();
        for (Instance x : WeightOptimizer.trainInstances()) {
            assertTrue(Arrays.asList(WeightOptimizer.TRAIN_FAMILIES).contains(x.family));
            assertNotEquals(InstanceGenerator.DurationDistribution.BIMODAL, x.durations);
            train.add(x.id);
        }
        assertEquals(7200, train.size());
        List<Instance> val = WeightOptimizer.validationInstances();
        List<Instance> test = WeightOptimizer.testInstances();
        List<Instance> ood = WeightOptimizer.oodInstances();
        assertEquals(2480, val.size());
        assertEquals(4500, test.size());
        assertEquals(2900, ood.size());
        Set<String> all = new HashSet<>(train);
        for (List<Instance> l : List.of(val, test, ood, WeightOptimizer.exactInstances())) {
            for (Instance x : l) assertTrue(x.id, all.add(x.id));
        }
        for (Instance x : test) assertTrue(WeightOptimizer.inDistribution(x));
        for (Instance x : ood) assertFalse(WeightOptimizer.inDistribution(x));
    }

    @Test
    public void interruptedSearch_resumesWithTheIdenticalTrajectory() throws Exception {
        ForkJoinPool pool = new ForkJoinPool(2);
        WeightOptimizer.DataSet d = new WeightOptimizer.DataSet("t", small(), pool);
        ParameterSpace space = ParameterSpace.full(WeightOptimizer.trackA(3));
        double[] start = space.restrict(ParameterSpace.encode(SchedulerConfig.DEFAULT));

        // uninterrupted reference run on a separate directory
        WeightOptimizer ref = new WeightOptimizer(tmp.newFolder("ref").toPath(), 2);
        ref.openEvaluationLog();
        WeightOptimizer.RunResult full = ref.cmaRunOn(d, "run", space, start, 6, 8);
        ref.evaluationLog.close();

        // interrupted after 4 generations, then resumed from the log
        java.nio.file.Path dir = tmp.newFolder("resume").toPath();
        WeightOptimizer first = new WeightOptimizer(dir, 2);
        first.openEvaluationLog();
        first.cmaRunOn(d, "run", space, start, 6, 4);
        first.evaluationLog.close();
        WeightOptimizer second = new WeightOptimizer(dir, 2);
        second.openEvaluationLog();
        long before = second.evaluationsRun;
        WeightOptimizer.RunResult resumed = second.cmaRunOn(d, "run", space, start, 6, 8);
        second.evaluationLog.close();

        assertEquals(4 * 6, second.evaluationsRun - before);   // only the 4 missing generations were evaluated
        assertArrayEquals(full.finalMean(), resumed.finalMean(), 0);
        assertEquals(full.evaluations_().size(), resumed.evaluations_().size());
        for (int k = 0; k < full.evaluations_().size(); k++) {
            assertArrayEquals(full.evaluations_().get(k).u(), resumed.evaluations_().get(k).u(), 0);
            assertEquals(full.evaluations_().get(k).j(), resumed.evaluations_().get(k).j(), 0);
        }
        assertTrue(Files.readAllLines(dir.resolve("evaluations.csv")).size() == 1 + 8 * 6);
        pool.shutdown();
        for (WeightOptimizer o : List.of(ref, first, second)) o.pool.shutdown();
    }

    @Test
    public void sobolPoints_areInTheUnitCubeAndWellSpread() {
        SobolSequence s = new SobolSequence(9, 3);
        int n = 1024;
        int[][] bins = new int[9][8];
        for (int k = 0; k < n; k++) {
            double[] p = s.point(k);
            for (int i = 0; i < 9; i++) {
                assertTrue(p[i] >= 0 && p[i] < 1);
                bins[i][(int) (p[i] * 8)]++;
            }
        }
        // a (0, m, 1)-net in each coordinate: every 1/8 interval holds exactly n/8 points
        for (int[] b : bins) for (int c : b) assertEquals(n / 8, c);
    }
}
