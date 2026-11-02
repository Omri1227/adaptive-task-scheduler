package ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only Gantt chart of the schedule observed so far: one lane per worker,
 * finished tasks as solid bars, running tasks up to their estimated finish time,
 * and a marker at the current global time. It only draws what it is given.
 */
final class GanttChartPanel extends JPanel implements Scrollable {

    /** One task execution on one worker. */
    static final class Bar {
        final int workerId;
        final int taskId;
        final String taskName;
        final int start;
        int end;
        boolean running;

        Bar(int workerId, int taskId, String taskName, int start, int end, boolean running) {
            this.workerId = workerId;
            this.taskId = taskId;
            this.taskName = taskName;
            this.start = start;
            this.end = end;
            this.running = running;
        }
    }

    private static final int LANE_HEIGHT = 26;
    private static final int LABEL_WIDTH = 150;
    private static final int AXIS_HEIGHT = 24;
    private static final int RIGHT_MARGIN = 24;

    private static final Color DONE = new Color(92, 132, 196);
    private static final Color RUNNING = new Color(66, 170, 230);
    private static final Color GRID = new Color(228, 228, 228);
    private static final Color NOW = new Color(214, 72, 60);

    private final Map<Integer, String> lanes = new LinkedHashMap<>();
    private final List<Bar> bars = new ArrayList<>();
    private int now;
    private double unitWidth = 18;

    GanttChartPanel() {
        setBackground(Color.WHITE);
        setToolTipText("");
    }

    void update(Map<Integer, String> workerLanes, List<Bar> allBars, int globalTime) {
        lanes.clear();
        lanes.putAll(workerLanes);
        bars.clear();
        bars.addAll(allBars);
        now = globalTime;
        revalidate();
        repaint();
    }

    void zoom(double factor) {
        unitWidth = Math.max(1.5, Math.min(80, unitWidth * factor));
        revalidate();
        repaint();
    }

    private int horizon() {
        int h = Math.max(now, 1);
        for (Bar b : bars) h = Math.max(h, b.end);
        return h;
    }

    @Override
    public Dimension getPreferredSize() {
        int width = LABEL_WIDTH + (int) Math.ceil(horizon() * unitWidth) + RIGHT_MARGIN;
        int height = AXIS_HEIGHT + Math.max(1, lanes.size()) * LANE_HEIGHT + 6;
        return new Dimension(width, height);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        if (lanes.isEmpty()) {
            g.setColor(Color.GRAY);
            g.drawString("No task has started yet.", 12, 20);
            g.dispose();
            return;
        }

        int horizon = horizon();
        int laneCount = lanes.size();
        int chartBottom = AXIS_HEIGHT + laneCount * LANE_HEIGHT;

        // time axis and grid
        int step = niceStep(horizon);
        g.setFont(getFont().deriveFont(10.5f));
        FontMetrics fm = g.getFontMetrics();
        for (int t = 0; t <= horizon; t += step) {
            int x = x(t);
            g.setColor(GRID);
            g.drawLine(x, AXIS_HEIGHT, x, chartBottom);
            g.setColor(Color.DARK_GRAY);
            String label = String.valueOf(t);
            g.drawString(label, x - fm.stringWidth(label) / 2, AXIS_HEIGHT - 8);
        }

        // lanes
        Map<Integer, Integer> laneIndex = new LinkedHashMap<>();
        int row = 0;
        for (Map.Entry<Integer, String> lane : lanes.entrySet()) {
            int y = AXIS_HEIGHT + row * LANE_HEIGHT;
            if (row % 2 == 1) {
                g.setColor(new Color(248, 249, 252));
                g.fillRect(0, y, getWidth(), LANE_HEIGHT);
            }
            g.setColor(Color.BLACK);
            g.setFont(getFont().deriveFont(Font.BOLD, 12f));
            g.drawString(clip(lane.getValue(), g.getFontMetrics(), LABEL_WIDTH - 12), 8, y + LANE_HEIGHT / 2 + 5);
            laneIndex.put(lane.getKey(), row++);
        }

        // bars
        g.setFont(getFont().deriveFont(11f));
        fm = g.getFontMetrics();
        for (Bar b : bars) {
            Integer lane = laneIndex.get(b.workerId);
            if (lane == null) continue;
            Rectangle2D r = barBounds(b, lane);
            g.setColor(b.running ? RUNNING : DONE);
            g.fill(r);
            if (b.running) {
                g.setColor(new Color(20, 90, 150));
                g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{4, 3}, 0));
                g.draw(r);
                g.setStroke(new BasicStroke(1f));
            }
            String label = "#" + b.taskId;
            if (fm.stringWidth(label) + 6 < r.getWidth()) {
                g.setColor(Color.WHITE);
                g.drawString(label, (int) r.getX() + 4, (int) (r.getY() + r.getHeight() / 2 + 4));
            }
        }

        // current time
        g.setColor(NOW);
        g.setStroke(new BasicStroke(2f));
        int xNow = x(now);
        g.drawLine(xNow, AXIS_HEIGHT - 4, xNow, chartBottom);
        g.dispose();
    }

    @Override
    public String getToolTipText(java.awt.event.MouseEvent event) {
        Map<Integer, Integer> laneIndex = new LinkedHashMap<>();
        int row = 0;
        for (Integer id : lanes.keySet()) laneIndex.put(id, row++);
        for (Bar b : bars) {
            Integer lane = laneIndex.get(b.workerId);
            if (lane != null && barBounds(b, lane).contains(event.getPoint())) {
                return "<html><b>" + escape(b.taskName) + "</b> (#" + b.taskId + ")<br/>"
                        + escape(lanes.get(b.workerId)) + "<br/>"
                        + (b.running ? "running: " + b.start + " &rarr; est. " + b.end
                                     : "finished: " + b.start + " &rarr; " + b.end)
                        + " (" + (b.end - b.start) + ")</html>";
            }
        }
        return null;
    }

    private Rectangle2D barBounds(Bar b, int lane) {
        double x0 = x(b.start);
        double width = Math.max(2, (b.end - b.start) * unitWidth - 1);
        return new Rectangle2D.Double(x0, AXIS_HEIGHT + lane * LANE_HEIGHT + 5, width, LANE_HEIGHT - 10);
    }

    private int x(int t) {
        return LABEL_WIDTH + (int) Math.round(t * unitWidth);
    }

    private int niceStep(int horizon) {
        int minPixels = 46;
        int[] candidates = {1, 2, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000, 2000, 5000};
        for (int c : candidates) {
            if (c * unitWidth >= minPixels) return c;
        }
        return Math.max(1, horizon / 10);
    }

    private static String clip(String s, FontMetrics fm, int width) {
        if (fm.stringWidth(s) <= width) return s;
        String t = s;
        while (t.length() > 1 && fm.stringWidth(t + "…") > width) t = t.substring(0, t.length() - 1);
        return t + "…";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---- Scrollable: fill the viewport height, scroll horizontally
    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return new Dimension(800, 220);
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 24;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return orientation == SwingConstants.HORIZONTAL ? visibleRect.width - 40 : visibleRect.height - 20;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return getParent() instanceof JViewport v && v.getWidth() > getPreferredSize().width;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return getParent() instanceof JViewport v && v.getHeight() > getPreferredSize().height;
    }
}
