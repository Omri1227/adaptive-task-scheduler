package ui;

import backend.Controller;
import backend.ProjectScenario;
import benchmark.BenchmarkEngine;
import benchmark.BenchmarkReport;

import javax.swing.*;

public class Main {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(Main::showProjectMenu);
    }

    private static void showProjectMenu() {
        ProjectScenario scenario = ScenarioSelectionDialog.showDialog(null);

        if (scenario == null) {
            return;
        }

        BenchmarkReport benchmarkReport =
                BenchmarkEngine.benchmark(scenario);

        Controller controller = new Controller(
                scenario.getTasks(),
                scenario.getWorkers()
        );
        controller.initial();

        DagVisualizer visualizer = new DagVisualizer(
                controller,
                scenario.getName(),
                benchmarkReport,
                Main::showProjectMenu
        );

        visualizer.setLocationRelativeTo(null);
        visualizer.setVisible(true);
    }
}
