package benchmark;

import backend.StaticGraphAnalysis;
import benchmark.CriticalPathStudy.Graph;
import benchmark.CriticalPathStudy.Pilot;
import benchmark.CriticalPathStudy.Rule;
import benchmark.CriticalPathStudy.Sim;
import benchmark.InstanceGenerator.Family;
import benchmark.InstanceGenerator.Instance;
import benchmark.InstanceGenerator.SizeBucket;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

public class CriticalPathStudyTest {

    static List<Instance> sample() {
        List<Instance> list = new ArrayList<>();
        for (Family f : Family.values()) {
            for (SizeBucket b : new SizeBucket[]{SizeBucket.TINY, SizeBucket.SMALL, SizeBucket.MEDIUM}) {
                for (int i = 0; i < 6; i++) list.add(InstanceGenerator.generate(4711, i, f, b));
            }
        }
        return list;
    }

    @Test
    public void studySimulatorReproducesProductionCriticalPathFirst() {
        for (Instance x : sample()) {
            Graph g = new Graph(x);
            assertEquals(x.id, CriticalPathStudy.CP_PROD.run(x, StaticGraphAnalysis.of(x.tasks)),
                    CriticalPathStudy.listSchedule(g, Rule.CP.keys(g)));
        }
    }

    @Test
    public void pilotThatNeverFiresIsCriticalPathFirst() {
        for (Instance x : sample()) {
            Graph g = new Graph(x);
            long[] key = Rule.CP.keys(g);
            Pilot p = new Pilot(4, CriticalPathStudy.FULL, (s, r, f) -> false);
            assertEquals(x.id, CriticalPathStudy.listSchedule(g, key), CriticalPathStudy.pilotSchedule(g, key, p));
            assertEquals(0, p.simulations);
        }
    }

    @Test
    public void fullDepthPilotNeverLosesToItsOwnRule() {
        // every comparison includes the rule's own continuation, whose value is the value predicted at the
        // previous decision, so the predicted makespan never increases (any trigger)
        for (Instance x : sample()) {
            Graph g = new Graph(x);
            long[] key = Rule.CP.keys(g);
            int cp = CriticalPathStudy.listSchedule(g, key);
            for (CriticalPathStudy.Trigger t : List.of(CriticalPathStudy.ALWAYS, CriticalPathStudy.gapAtMost(0.02))) {
                int pilot = CriticalPathStudy.pilotSchedule(g, key, new Pilot(4, CriticalPathStudy.FULL, t));
                assertTrue(x.id, pilot <= cp);
            }
        }
    }

    @Test
    public void readyHeapTopMatchesSortedOrder() {
        Random r = new Random(3);
        long[] key = new long[200];
        for (int i = 0; i < key.length; i++) key[i] = r.nextInt(20);
        CriticalPathStudy.ReadyHeap h = new CriticalPathStudy.ReadyHeap(key, key.length);
        List<Integer> members = new ArrayList<>();
        for (int i = 0; i < key.length; i++) {
            if (r.nextBoolean()) {
                h.add(i);
                members.add(i);
            }
        }
        for (int k = 0; k < 10; k++) {
            int v = members.remove(r.nextInt(members.size()));
            h.remove(v);
        }
        members.sort((a, b) -> key[a] != key[b] ? Long.compare(key[b], key[a]) : Integer.compare(a, b));
        int[] top = h.top(members.size());
        for (int k = 0; k < top.length; k++) assertEquals(members.get(k).intValue(), top[k]);
        assertEquals(members.get(0).intValue(), h.peek());
    }

    @Test
    public void copiesAreIndependentAndEstimatesAreLowerBounds() {
        for (Instance x : sample()) {
            Graph g = new Graph(x);
            Sim s = new Sim(g, Rule.CP.keys(g));
            int finalMakespan = s.copy().run(null, CriticalPathStudy.FULL);
            while (s.done < g.n) {
                while (s.free > 0 && s.ready.size > 0) s.startTask(s.ready.peek());
                Sim c = s.copy();
                assertTrue(x.id, c.estimate() <= finalMakespan);
                assertEquals(x.id, finalMakespan, c.run(null, CriticalPathStudy.FULL));
                s.advance();
            }
            assertEquals(finalMakespan, s.t);
        }
    }

    @Test
    public void swapRegretIsNonNegativeAndBoundaryAtMostBest() {
        for (Instance x : sample()) {
            for (CriticalPathStudy.Decision d : CriticalPathStudy.decisions(0, new Graph(x))) {
                assertTrue(d.regret() >= 0);
                assertTrue(d.bestSwap() <= d.boundarySwap());
            }
        }
    }

    @Test
    public void paretoInstancesKeepTheGraph() {
        Instance base = InstanceGenerator.generate(1, 0, Family.LAYERED, SizeBucket.SMALL);
        Instance p = CriticalPathStudy.pareto(base, 9, 0);
        assertEquals(base.tasks.size(), p.tasks.size());
        for (int k = 0; k < base.tasks.size(); k++) {
            assertEquals(base.tasks.get(k).getDependsOnIDS(), p.tasks.get(k).getDependsOnIDS());
            assertTrue(p.tasks.get(k).getDuration() >= 5 && p.tasks.get(k).getDuration() <= 1000);
        }
        assertEquals("PARETO", CriticalPathStudy.durationLabel(p));
    }
}
