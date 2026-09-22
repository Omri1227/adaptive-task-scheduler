package backend;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class ScenarioLibraryTest {

    @Test
    public void library_containsTenProgressivelyLargerScenarios() {
        List<ProjectScenario> scenarios = ScenarioLibrary.getScenarios();

        assertEquals(10, scenarios.size());

        int[] expectedTasks = {6, 8, 10, 14, 18, 22, 28, 34, 42, 50};
        int[] expectedWorkers = {2, 2, 3, 3, 4, 5, 6, 7, 8, 10};

        for (int i = 0; i < scenarios.size(); i++) {
            assertEquals(expectedTasks[i], scenarios.get(i).getTaskCount());
            assertEquals(expectedWorkers[i], scenarios.get(i).getWorkerCount());
        }
    }

    @Test
    public void everyScenario_hasNamedUniqueTasks() {
        for (ProjectScenario scenario : ScenarioLibrary.getScenarios()) {
            Set<Integer> ids = new HashSet<>();

            for (Task task : scenario.getTasks()) {
                assertTrue("Task name cannot be blank", task.getName() != null && !task.getName().isBlank());
                assertTrue("Task duration must be positive", task.getDuration() > 0);
                assertTrue("Task IDs must be unique", ids.add(task.getId()));
            }
        }
    }

    @Test
    public void everyScenario_runsToCompletion() {
        for (ProjectScenario scenario : ScenarioLibrary.getScenarios()) {
            Controller controller = new Controller(
                    scenario.getTasks(),
                    scenario.getWorkers()
            );
            controller.initial();

            int guard = scenario.getTaskCount() * 3;

            while (controller.getTasksLeft() > 0 && guard-- > 0) {
                controller.workerFinishOnTime();
            }

            assertEquals(
                    "Scenario did not finish: " + scenario.getName(),
                    0,
                    controller.getTasksLeft()
            );
            assertEquals(
                    scenario.getTaskCount(),
                    controller.getTasksCompleted()
            );
        }
    }
}
