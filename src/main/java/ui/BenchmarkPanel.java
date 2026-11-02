package ui;

import benchmark.BenchmarkReport;
import benchmark.BenchmarkStrategy;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Compact read-only comparison shown beside the live visualizer: one row per
 * strategy with its makespan, a proportional bar, and the difference from the
 * adaptive scheduler.
 */
public final class BenchmarkPanel extends JPanel {

    private static final Color ADAPTIVE = new Color(30, 75, 135);
    private static final Color BASELINE = new Color(150, 165, 185);
    private static final Color BEST = new Color(46, 140, 87);

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
                "Makespan without runtime changes. Shorter is better; ★ = best."
        );
        subtitle.setForeground(new Color(85, 85, 85));
        subtitle.setFont(subtitle.getFont().deriveFont(11.5f));
        add(subtitle, BorderLayout.NORTH);

        JPanel rows = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridy = 0;
        constraints.insets = new Insets(3, 2, 3, 2);
        constraints.fill = GridBagConstraints.HORIZONTAL;

        int worst = 1;
        for (BenchmarkStrategy strategy : BenchmarkStrategy.values()) {
            worst = Math.max(worst, report.getMakespan(strategy));
        }

        for (BenchmarkStrategy strategy : BenchmarkStrategy.values()) {
            addStrategyRow(rows, constraints, report, strategy, worst);
            constraints.gridy++;
        }

        add(rows, BorderLayout.CENTER);

        JLabel note = new JLabel(
                "<html><i>% = difference from the adaptive scheduler.</i></html>"
        );
        note.setForeground(new Color(105, 105, 105));
        note.setFont(note.getFont().deriveFont(10.5f));
        add(note, BorderLayout.SOUTH);
    }

    private static void addStrategyRow(
            JPanel rows,
            GridBagConstraints constraints,
            BenchmarkReport report,
            BenchmarkStrategy strategy,
            int worst) {

        boolean adaptive = strategy == BenchmarkStrategy.ADAPTIVE;
        boolean best = report.isBest(strategy);
        int makespan = report.getMakespan(strategy);
        int adaptiveMakespan = report.getMakespan(BenchmarkStrategy.ADAPTIVE);

        JLabel name = new JLabel(strategy.getDisplayName());
        name.setToolTipText(strategy.getDescription());

        Bar bar = new Bar((double) makespan / worst, adaptive ? ADAPTIVE : best ? BEST : BASELINE);
        bar.setToolTipText(strategy.getDescription());

        JLabel value = new JLabel(makespan + (best ? "  ★" : ""), SwingConstants.RIGHT);
        value.setToolTipText("Total project completion time");

        String deltaText = "";
        if (!adaptive && adaptiveMakespan > 0) {
            double pct = 100.0 * (makespan - adaptiveMakespan) / adaptiveMakespan;
            deltaText = pct == 0 ? "±0%" : String.format("%+.1f%%", pct);
        }
        JLabel delta = new JLabel(deltaText, SwingConstants.RIGHT);
        delta.setForeground(new Color(110, 110, 110));
        delta.setPreferredSize(new Dimension(56, delta.getPreferredSize().height));

        if (adaptive) {
            name.setFont(name.getFont().deriveFont(Font.BOLD));
            value.setFont(value.getFont().deriveFont(Font.BOLD));
            name.setForeground(ADAPTIVE);
            value.setForeground(ADAPTIVE);
        }

        constraints.gridx = 0;
        constraints.weightx = 0.0;
        constraints.anchor = GridBagConstraints.WEST;
        rows.add(name, constraints);

        constraints.gridx = 1;
        constraints.weightx = 1.0;
        rows.add(bar, constraints);

        constraints.gridx = 2;
        constraints.weightx = 0.0;
        constraints.anchor = GridBagConstraints.EAST;
        rows.add(value, constraints);

        constraints.gridx = 3;
        rows.add(delta, constraints);
    }

    /** Horizontal bar whose length is a fraction of the available width. */
    private static final class Bar extends JComponent {
        private final double fraction;
        private final Color color;

        Bar(double fraction, Color color) {
            this.fraction = Math.max(0, Math.min(1, fraction));
            this.color = color;
            setPreferredSize(new Dimension(120, 14));
            setMinimumSize(new Dimension(40, 14));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int h = Math.min(12, getHeight() - 2);
            int y = (getHeight() - h) / 2;
            g2.setColor(new Color(236, 238, 242));
            g2.fillRoundRect(0, y, getWidth(), h, 6, 6);
            g2.setColor(color);
            g2.fillRoundRect(0, y, (int) Math.round(getWidth() * fraction), h, 6, 6);
            g2.dispose();
        }
    }
}
