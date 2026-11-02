package benchmark;

import backend.SchedulerConfig;
import benchmark.InstanceGenerator.Family;
import benchmark.InstanceGenerator.Instance;
import benchmark.InstanceGenerator.SizeBucket;
import backend.StaticGraphAnalysis;
import org.junit.Test;

import static org.junit.Assert.*;

public class PresetTest {

    // u vectors of the Phase 2 selections (target/phase2/run1/selected-{A,B}.properties)
    static final double[] A_STAR_U = {0.9234300106446999, 0.5051985225877871, 0.6163063287122565,
            0.04566253311815882, 0.25142472984363917, 0.33312535491519923, 0.20945679502940762,
            0.3613705638880109, 0.9528472413165832};
    static final double[] B_STAR_U = {1.1969760461110912, -0.7786859297149928, -0.989492145187865,
            -0.5659122769679766, -0.26438470901387895, 1.5135326021175564, 1.706724582327594,
            -0.30760663325576587, 1.1836276830434582};

    @Test
    public void defaultIsStillTheOriginalScheduler() {
        assertEquals(SchedulerConfig.builder().build().toString(), SchedulerConfig.DEFAULT.toString());
        assertEquals(SchedulerConfig.LookaheadMode.LEGACY_IDLE_WINDOW, SchedulerConfig.DEFAULT.getLookaheadMode());
        assertEquals(0.5, SchedulerConfig.DEFAULT.getCriticalPathWeight(), 0);
        assertEquals(3, SchedulerConfig.DEFAULT.getCandidatePoolSize());
    }

    @Test
    public void presetsAreExactlyTheSelectedConfigurations() {
        assertEquals(WeightOptimizer.B0.toString(), SchedulerConfig.PHASE1_ROLLOUT.toString());
        assertEquals(ParameterSpace.decode(WeightOptimizer.trackA(2), A_STAR_U).toString(),
                SchedulerConfig.FAST_OPTIMIZED.toString());
        assertEquals(ParameterSpace.decode(WeightOptimizer.trackB(5, 8), B_STAR_U).toString(),
                SchedulerConfig.QUALITY_OPTIMIZED.toString());
    }

    @Test
    public void presetsProduceTheSameSchedulesAsTheDecodedSelections() {
        SchedulerConfig[][] pairs = {
                {SchedulerConfig.FAST_OPTIMIZED, ParameterSpace.decode(WeightOptimizer.trackA(2), A_STAR_U)},
                {SchedulerConfig.QUALITY_OPTIMIZED, ParameterSpace.decode(WeightOptimizer.trackB(5, 8), B_STAR_U)}};
        for (SchedulerConfig[] p : pairs) {
            ExperimentRunner.Variant a = ExperimentRunner.Variant.adaptive("preset", "t", p[0]);
            ExperimentRunner.Variant b = ExperimentRunner.Variant.adaptive("decoded", "t", p[1]);
            for (Family f : Family.values()) {
                for (int i = 0; i < 4; i++) {
                    Instance x = InstanceGenerator.generate(4242, i, f, SizeBucket.SMALL);
                    StaticGraphAnalysis an = StaticGraphAnalysis.of(x.tasks);
                    assertEquals(x.id, b.run(x, an), a.run(x, an));
                }
            }
        }
    }
}
