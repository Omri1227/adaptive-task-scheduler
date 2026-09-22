package backend;

import benchmark.BenchmarkEngine;
import benchmark.BenchmarkReport;
import benchmark.BenchmarkStrategy;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BenchmarkEngineTest {

    @Test
    public void everyScenarioProducesAllBenchmarkResults() {
        for (ProjectScenario scenario : ScenarioLibrary.getScenarios()) {
            BenchmarkReport report = BenchmarkEngine.benchmark(scenario);

            for (BenchmarkStrategy strategy : BenchmarkStrategy.values()) {
                assertTrue(
                        scenario.getName() + " / " + strategy,
                        report.getMakespan(strategy) > 0
                );
            }
        }
    }

    @Test
    public void benchmarkingIsDeterministic() {
        ProjectScenario scenario = ScenarioLibrary.getScenario(10);

        BenchmarkReport first = BenchmarkEngine.benchmark(scenario);
        BenchmarkReport second = BenchmarkEngine.benchmark(scenario);

        for (BenchmarkStrategy strategy : BenchmarkStrategy.values()) {
            assertEquals(
                    first.getMakespan(strategy),
                    second.getMakespan(strategy)
            );
        }
    }

    @Test
    public void singleChainHasSameMakespanForEveryPolicy() {
        ProjectScenario chain = new ProjectScenario(
                99,
                "Single Chain",
                "test",
                "test",
                new java.util.LinkedList<>(java.util.List.of(
                        new Task("A", 1, 2, new java.util.LinkedList<>()),
                        new Task("B", 2, 3, new java.util.LinkedList<>(java.util.List.of(1))),
                        new Task("C", 3, 4, new java.util.LinkedList<>(java.util.List.of(2)))
                )),
                new java.util.LinkedList<>(java.util.List.of("Worker 0", "Worker 1"))
        );

        BenchmarkReport report = BenchmarkEngine.benchmark(chain);

        for (BenchmarkStrategy strategy : BenchmarkStrategy.values()) {
            assertEquals(9, report.getMakespan(strategy));
        }
    }
}
