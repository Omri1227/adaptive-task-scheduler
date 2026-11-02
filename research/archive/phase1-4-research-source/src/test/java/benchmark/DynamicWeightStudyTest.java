package benchmark;

import backend.SchedulerConfig;
import backend.StaticGraphAnalysis;
import benchmark.CriticalPathStudy.Graph;
import benchmark.CriticalPathStudy.Rule;
import benchmark.CriticalPathStudy.Sim;
import benchmark.DynamicWeightStudy.Profile;
import benchmark.InstanceGenerator.Family;
import benchmark.InstanceGenerator.Instance;
import benchmark.InstanceGenerator.SizeBucket;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

public class DynamicWeightStudyTest {

    static List<Instance> sample() {
        List<Instance> list = new ArrayList<>();
        for (Family f : Family.values()) {
            for (SizeBucket b : new SizeBucket[]{SizeBucket.TINY, SizeBucket.SMALL, SizeBucket.MEDIUM}) {
                for (int i = 0; i < 5; i++) list.add(InstanceGenerator.generate(8086, i, f, b));
            }
        }
        return list;
    }

    static SchedulerConfig production(Profile p) {
        return SchedulerConfig.DEFAULT.toBuilder()
                .staticWeights(p.ws()[0], p.ws()[1], p.ws()[2])
                .dynamicWeights(p.wd()[0], p.wd()[1], p.wd()[2], p.wd()[3], p.wd()[4])
                .staticShare(p.lambda())
                .candidatePoolSize(p.pool() == DynamicWeightStudy.ALL ? SchedulerConfig.ALL_READY_TASKS : p.pool())
                .lookaheadMode(SchedulerConfig.LookaheadMode.NONE)
                .completionMode(SchedulerConfig.CompletionMode.BATCHED)
                .deterministicTieBreak(true)
                .build();
    }

    @Test
    public void profilesReproduceTheProductionScoringExactly() {
        // the study's scoring terms equal TimeConstraints.scoreSet + DynamicScoreLogic.makeDynamicScore:
        // every library profile gives the same makespan as the production Controller with those weights
        for (Profile p : DynamicWeightStudy.LIBRARY) {
            if (p == DynamicWeightStudy.CP) continue;
            ExperimentRunner.Variant prod = ExperimentRunner.Variant.adaptive(p.name(), "t", production(p));
            for (Instance x : sample()) {
                Graph g = new Graph(x);
                int study = new Sim(g, Rule.CP.keys(g)).run(new DynamicWeightStudy.ProfilePolicy(p), CriticalPathStudy.FULL);
                assertEquals(p.name() + " " + x.id, prod.run(x, StaticGraphAnalysis.of(x.tasks)), study);
            }
        }
    }

    @Test
    public void cpProfileIsCriticalPathFirst() {
        for (Instance x : sample()) {
            Graph g = new Graph(x);
            assertEquals(x.id, CriticalPathStudy.listSchedule(g, Rule.CP.keys(g)),
                    new Sim(g, Rule.CP.keys(g)).run(new DynamicWeightStudy.ProfilePolicy(DynamicWeightStudy.CP), CriticalPathStudy.FULL));
        }
    }

    @Test
    public void probeIsAFractionAndFullDepthLookaheadsNeverLoseToCp() {
        for (Instance x : sample()) {
            Graph g = new Graph(x);
            long[] key = Rule.CP.keys(g);
            int cp = CriticalPathStudy.listSchedule(g, key);
            double probe = DynamicWeightStudy.starvationProbe(new Sim(g, key), 8);
            assertTrue(probe >= 0 && probe <= 1);
            assertTrue(x.id, new Sim(g, key).run(new DynamicWeightStudy.PolicyRollout(0), CriticalPathStudy.FULL) <= cp);
            assertTrue(x.id, new Sim(g, key).run(new DynamicWeightStudy.PolicyRollout(0.15), CriticalPathStudy.FULL) <= cp);
            assertTrue(x.id, new Sim(g, key).run(new DynamicWeightStudy.ProfileCandidatePilot(0, CriticalPathStudy.FULL), CriticalPathStudy.FULL) <= cp);
        }
    }

    @Test
    public void treeSerializationRoundTrips() {
        Random r = new Random(1);
        int n = 2000, d = CriticalPathStudy.DECISION_FEATURES.length;
        double[][] x = new double[n][d];
        boolean[] y = new boolean[n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < d; j++) x[i][j] = r.nextDouble();
            y[i] = x[i][5] < 0.3 && x[i][7] > 0.4;
        }
        DynamicWeightStudy.Tree t = DynamicWeightStudy.Tree.fit(x, y, 2, 50);
        DynamicWeightStudy.Tree back = DynamicWeightStudy.Tree.parse(t.serialize());
        assertEquals(t.serialize(), back.serialize());
        for (int i = 0; i < n; i++) assertEquals(t.predict(x[i]), back.predict(x[i]));
    }
}
