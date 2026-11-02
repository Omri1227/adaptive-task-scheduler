package backend;

import benchmark.InstanceGenerator;
import benchmark.InstanceGenerator.Family;
import benchmark.InstanceGenerator.Instance;
import benchmark.InstanceGenerator.SizeBucket;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class ExperimentInfrastructureTest {

    @Test
    public void generator_isDeterministic() {
        for (Family f : Family.values()) {
            for (SizeBucket b : SizeBucket.values()) {
                Instance a = InstanceGenerator.generate(42, 7, f, b);
                Instance c = InstanceGenerator.generate(42, 7, f, b);
                assertEquals(a.workers, c.workers);
                assertEquals(a.tasks.size(), c.tasks.size());
                for (int i = 0; i < a.tasks.size(); i++) {
                    assertEquals(a.tasks.get(i).getId(), c.tasks.get(i).getId());
                    assertEquals(a.tasks.get(i).getDuration(), c.tasks.get(i).getDuration());
                    assertEquals(a.tasks.get(i).getDependsOnIDS(), c.tasks.get(i).getDependsOnIDS());
                }
            }
        }
    }

    @Test
    public void generator_producesValidAcyclicInstancesForEveryFamily() {
        for (Family f : Family.values()) {
            for (SizeBucket b : new SizeBucket[]{SizeBucket.TINY, SizeBucket.SMALL, SizeBucket.MEDIUM, SizeBucket.LARGE}) {
                for (int i = 0; i < 10; i++) {
                    Instance x = InstanceGenerator.generate(5, i, f, b);
                    assertTrue(x.workers >= 2);
                    Set<Integer> ids = new HashSet<>();
                    for (Task t : x.tasks) {
                        assertTrue(ids.add(t.getId()));
                        assertTrue(t.getDuration() > 0);
                        assertEquals(new HashSet<>(t.getDependsOnIDS()).size(), t.getDependsOnIDS().size());
                    }
                    // TaskEngine rejects cycles, unknown parents and duplicate ids
                    TaskEngine e = new TaskEngine(x.copyTasks());
                    e.initial();
                    assertEquals(x.tasks.size(), e.getTopologicalSortSize());
                    assertTrue(x.lowerBound >= x.criticalPath);
                    assertTrue((long) x.lowerBound * x.workers >= x.totalWork);
                }
            }
        }
    }

    @Test
    public void dataset_differsBetweenSeeds() {
        List<Instance> a = InstanceGenerator.dataset(1, 3, SizeBucket.SMALL);
        List<Instance> b = InstanceGenerator.dataset(2, 3, SizeBucket.SMALL);
        assertEquals(a.size(), b.size());
        int same = 0;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).totalWork == b.get(i).totalWork && a.get(i).tasks.size() == b.get(i).tasks.size()) same++;
        }
        assertTrue(same < a.size());
    }
}
