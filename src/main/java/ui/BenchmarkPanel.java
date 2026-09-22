package ui;

import benchmark.BenchmarkReport;
import benchmark.BenchmarkStrategy;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Compact read-only comparison shown beside the live visualizer.
 */
public final class BenchmarkPanel extends JPanel {

    public BenchmarkPanel(BenchmarkReport report) {
        if (report == null) {
            throw new IllegalArgumentException("benchmark report cannot be null");
        }

        setLayout(new BorderLayout(0, 8));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Initial Schedule Benchmark"),
                new EmptyBorder(4, 8, 8, 8)
        ));

        JLabel subtitle = new JLabel(
                "<html>Total completion time using the original DAG and estimated durations.</html>"
        );
        subtitle.setForeground(new Color(85, 85, 85));
        subtitle.setFont(subtitle.getFont().deriveFont(11.5f));
        add(subtitle, BorderLayout.NORTH);

        JPanel rows = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridy = 0;
        constraints.insets = new Insets(3, 2, 3, 2);
        constraints.fill = GridBagConstraints.HORIZONTAL;

        for (BenchmarkStrategy strategy : BenchmarkStrategy.values()) {
            addStrategyRow(rows, constraints, report, strategy);
            constraints.gridy++;
        }

        add(rows, BorderLayout.CENTER);

        JLabel note = new JLabel(
                "<html><i>Runtime edits and early/late finishes are not included.</i></html>"
        );
        note.setForeground(new Color(105, 105, 105));
        note.setFont(note.getFont().deriveFont(10.5f));
        add(note, BorderLayout.SOUTH);
    }

    private static void addStrategyRow(
            JPanel rows,
            GridBagConstraints constraints,
            BenchmarkReport report,
            BenchmarkStrategy strategy) {

        boolean adaptive = strategy == BenchmarkStrategy.ADAPTIVE;
        boolean best = report.isBest(strategy);

        JLabel name = new JLabel(strategy.getDisplayName());
        name.setToolTipText(strategy.getDescription());

        JLabel value = new JLabel(
                report.getMakespan(strategy) + (best ? "  ★" : ""),
                SwingConstants.RIGHT
        );
        value.setToolTipText("Total project completion time");

        if (adaptive) {
            name.setFont(name.getFont().deriveFont(Font.BOLD));
            value.setFont(value.getFont().deriveFont(Font.BOLD));
            name.setForeground(new Color(30, 75, 135));
            value.setForeground(new Color(30, 75, 135));
        }

        constraints.gridx = 0;
        constraints.weightx = 1.0;
        constraints.anchor = GridBagConstraints.WEST;
        rows.add(name, constraints);

        constraints.gridx = 1;
        constraints.weightx = 0.0;
        constraints.anchor = GridBagConstraints.EAST;
        rows.add(value, constraints);
    }
}
