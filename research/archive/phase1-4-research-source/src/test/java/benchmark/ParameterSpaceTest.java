package benchmark;

import backend.SchedulerConfig;
import benchmark.InstanceGenerator.Family;
import benchmark.InstanceGenerator.Instance;
import benchmark.InstanceGenerator.SizeBucket;
import backend.StaticGraphAnalysis;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.*;

public class ParameterSpaceTest {

    @Test
    public void decode_producesSimplexWeightsAndLambdaInUnitInterval() {
        Random r = new Random(1);
        for (int t = 0; t < 1000; t++) {
            double[] u = new double[ParameterSpace.FULL_DIMENSION];
            for (int i = 0; i < u.length; i++) u[i] = -1 + 3 * r.nextDouble();
            SchedulerConfig c = ParameterSpace.decode(SchedulerConfig.DEFAULT, u);
            double s = c.getCriticalPathWeight() + c.getBlockingPowerWeight() + c.getReachableDurationWeight();
            double d = c.getOpenToBlockWeight() + c.getOpenChildrenWeight() + c.getChildImportanceWeight()
                    + c.getDurationToReadyWeight() + c.getChildrenToReadyQueueWeight();
            assertEquals(1.0, s, 1e-12);
            assertEquals(1.0, d, 1e-12);
            assertTrue(c.getCriticalPathWeight() >= 0 && c.getBlockingPowerWeight() >= 0 && c.getReachableDurationWeight() >= 0);
            assertTrue(c.getStaticShare() >= 0 && c.getStaticShare() <= 1);
        }
    }

    @Test
    public void encodeDecode_roundTripsTheDefaultWeights() {
        SchedulerConfig c = ParameterSpace.decode(SchedulerConfig.DEFAULT, ParameterSpace.encode(SchedulerConfig.DEFAULT));
        assertEquals(0.5, c.getCriticalPathWeight(), 1e-15);
        assertEquals(0.25, c.getBlockingPowerWeight(), 1e-15);
        assertEquals(0.25, c.getReachableDurationWeight(), 1e-15);
        assertEquals(0.2, c.getOpenToBlockWeight(), 1e-15);
        assertEquals(0.1, c.getOpenChildrenWeight(), 1e-15);
        assertEquals(0.15, c.getChildImportanceWeight(), 1e-15);
        assertEquals(0.3, c.getDurationToReadyWeight(), 1e-15);
        assertEquals(0.25, c.getChildrenToReadyQueueWeight(), 1e-15);
        assertEquals(0.5, c.getStaticShare(), 1e-15);
        // non-weight settings come from the base unchanged
        assertEquals(SchedulerConfig.DEFAULT.toString().replaceAll("static=.*staticShare=[^,]*,", ""),
                c.toString().replaceAll("static=.*staticShare=[^,]*,", ""));
    }

    @Test
    public void encodeDecode_roundTripsArbitraryWeights() {
        SchedulerConfig in = SchedulerConfig.DEFAULT.toBuilder().staticWeights(0.7, 0.1, 0.2)
                .dynamicWeights(0.05, 0.4, 0.15, 0.3, 0.1).staticShare(0.8).build();
        SchedulerConfig out = ParameterSpace.decode(SchedulerConfig.DEFAULT, ParameterSpace.encode(in));
        assertEquals(0.7, out.getCriticalPathWeight(), 1e-12);
        assertEquals(0.4, out.getOpenChildrenWeight(), 1e-12);
        assertEquals(0.8, out.getStaticShare(), 1e-12);
    }

    @Test
    public void defaultEncoding_reproducesOriginalSchedules() {
        // the decoded default weights may differ from the literals in the last bit; schedules must not
        SchedulerConfig decoded = ParameterSpace.decode(SchedulerConfig.DEFAULT, ParameterSpace.encode(SchedulerConfig.DEFAULT));
        ExperimentRunner.Variant a = ExperimentRunner.Variant.adaptive("a", "t", SchedulerConfig.DEFAULT);
        ExperimentRunner.Variant b = ExperimentRunner.Variant.adaptive("b", "t", decoded);
        for (Family f : Family.values()) {
            for (SizeBucket bucket : new SizeBucket[]{SizeBucket.SMALL, SizeBucket.MEDIUM}) {
                for (int i = 0; i < 15; i++) {
                    Instance x = InstanceGenerator.generate(777, i, f, bucket);
                    StaticGraphAnalysis an = StaticGraphAnalysis.of(x.tasks);
                    assertEquals(x.id, a.run(x, an), b.run(x, an));
                }
            }
        }
    }

    @Test
    public void frozenCoordinates_keepTheirValuesAndFreeOnesAreWritten() {
        boolean[] free = {true, true, true, false, false, false, false, false, false};
        double[] fixed = ParameterSpace.encode(SchedulerConfig.DEFAULT);
        ParameterSpace space = ParameterSpace.full(SchedulerConfig.DEFAULT).freeze(free, fixed);
        assertEquals(3, space.dimension());
        double[] u = space.expand(new double[]{0.9, 0.1, 0.2});
        assertEquals(0.9, u[0], 0);
        assertEquals(fixed[5], u[5], 0);
        assertArrayEquals(new double[]{0.9, 0.1, 0.2}, space.restrict(u), 0);
        SchedulerConfig c = space.toConfig(new double[]{0.9, 0.1, 0.2});
        assertEquals(0.3, c.getDurationToReadyWeight(), 1e-15);
        assertTrue(c.getCriticalPathWeight() > 0.99);
    }

    @Test
    public void generatorOverload_withAllDistributionsEqualsOriginalGenerator() {
        for (Family f : Family.values()) {
            for (int i = 0; i < 5; i++) {
                Instance a = InstanceGenerator.generate(99, i, f, SizeBucket.SMALL);
                Instance b = InstanceGenerator.generate(99, i, f, SizeBucket.SMALL, InstanceGenerator.DurationDistribution.values());
                assertEquals(a.workers, b.workers);
                assertEquals(a.durations, b.durations);
                assertEquals(a.totalWork, b.totalWork);
                assertEquals(a.tasks.size(), b.tasks.size());
                for (int k = 0; k < a.tasks.size(); k++) {
                    assertEquals(a.tasks.get(k).getId(), b.tasks.get(k).getId());
                    assertEquals(a.tasks.get(k).getDependsOnIDS(), b.tasks.get(k).getDependsOnIDS());
                }
            }
        }
    }

    @Test
    public void generatorOverload_restrictsTheDurationDistribution() {
        for (int i = 0; i < 50; i++) {
            Instance x = InstanceGenerator.generate(5, i, Family.LAYERED, SizeBucket.SMALL,
                    InstanceGenerator.DurationDistribution.BIMODAL);
            assertEquals(InstanceGenerator.DurationDistribution.BIMODAL, x.durations);
            Instance y = InstanceGenerator.generate(5, i, Family.LAYERED, SizeBucket.SMALL, WeightOptimizer.TRAIN_DURATIONS);
            assertNotEquals(InstanceGenerator.DurationDistribution.BIMODAL, y.durations);
        }
    }
}
