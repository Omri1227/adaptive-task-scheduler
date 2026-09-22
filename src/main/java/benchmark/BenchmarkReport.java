package benchmark;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Immutable makespan comparison for one ProjectScenario.
 */
public final class BenchmarkReport {
    private final String scenarioName;
    private final EnumMap<BenchmarkStrategy, Integer> makespans;

    public BenchmarkReport(
            String scenarioName,
            Map<BenchmarkStrategy, Integer> makespans) {

        if (scenarioName == null || scenarioName.isBlank()) {
            throw new IllegalArgumentException("scenario name cannot be empty");
        }
        if (makespans == null || makespans.isEmpty()) {
            throw new IllegalArgumentException("benchmark results cannot be empty");
        }

        this.scenarioName = scenarioName;
        this.makespans = new EnumMap<>(BenchmarkStrategy.class);
        this.makespans.putAll(makespans);

        for (BenchmarkStrategy strategy : BenchmarkStrategy.values()) {
            if (!this.makespans.containsKey(strategy)) {
                throw new IllegalArgumentException(
                        "missing benchmark result for " + strategy);
            }
        }
    }

    public String getScenarioName() {
        return scenarioName;
    }

    public int getMakespan(BenchmarkStrategy strategy) {
        Integer value = makespans.get(strategy);
        if (value == null) {
            throw new IllegalArgumentException(
                    "no benchmark result for " + strategy);
        }
        return value;
    }

    public int getBestMakespan() {
        return makespans.values().stream()
                .min(Integer::compareTo)
                .orElseThrow();
    }

    public boolean isBest(BenchmarkStrategy strategy) {
        return getMakespan(strategy) == getBestMakespan();
    }

    public Map<BenchmarkStrategy, Integer> asMap() {
        return Collections.unmodifiableMap(makespans);
    }
}
