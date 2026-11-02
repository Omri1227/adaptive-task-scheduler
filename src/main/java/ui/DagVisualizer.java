package ui;

import backend.Controller;
import backend.GraphTaskNode;
import backend.Task;
import backend.Worker;
import benchmark.BenchmarkReport;
import benchmark.BenchmarkStrategy;
import com.mxgraph.layout.hierarchical.mxHierarchicalLayout;
import com.mxgraph.swing.mxGraphComponent;
import com.mxgraph.util.mxConstants;
import com.mxgraph.view.mxGraph;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.List;

/**
 * Live visualization of one project run. Every scheduling action goes through the
 * public Controller API exactly as before; this class only observes the resulting
 * state (task graph colors, workers, ready queue, Gantt chart and event log).
 */
public class DagVisualizer extends JFrame {

    // ===== Core =====
    private final Controller controller;
    private final String projectName;
    private final BenchmarkReport benchmarkReport;
    private final Runnable onBackToMenu;

    // ===== Graph =====
    private mxGraph graph;
    private mxGraphComponent graphComponent;
    private JPanel graphContainer;
    private final Map<Integer, Object> nodeCells = new HashMap<>();

    // ===== Observed schedule (for the Gantt chart and the event log) =====
    private final Map<String, GanttChartPanel.Bar> barsByKey = new LinkedHashMap<>();
    private final Map<Integer, String> laneNames = new LinkedHashMap<>();
    private final GanttChartPanel gantt = new GanttChartPanel();
    private final JTextArea eventLog = new JTextArea();

    // ===== UI =====
    private final DefaultTableModel workersModel = readOnlyModel("Worker", "ID", "Status", "Task", "Started", "Est. finish");
    private final DefaultTableModel readyModel = readOnlyModel("Rank", "Task", "ID", "Duration", "Static score", "Critical path");
    private JLabel globalTimeLabel;
    private JProgressBar progress;
    private JLabel statusLabel;
    private JButton nextButton;
    private JButton playButton;
    private JButton runToEndButton;
    private JSlider speedSlider;
    private javax.swing.Timer playTimer;
    private JPanel rightSidePanel;
    private JPanel bottomPanel;
    private JSplitPane mainSplitPane;
    private JSplitPane verticalSplitPane;
    private JButton expandGraphButton;
    private boolean graphExpanded = false;
    private int normalDividerSize = 8;
    private boolean finishAnnounced = false;

    private static final String COLOR_DONE = "#D5D5D5";
    private static final String COLOR_RUNNING = "#87CEFA";
    private static final String COLOR_READY = "#C9EFC4";
    private static final String COLOR_WAITING = "#FFFFFF";

    public DagVisualizer(Controller controller) {
        this(controller, null, null, null);
    }

    public DagVisualizer(Controller controller, String projectName) {
        this(controller, projectName, null, null);
    }

    public DagVisualizer(
            Controller controller,
            String projectName,
            BenchmarkReport benchmarkReport) {

        this(controller, projectName, benchmarkReport, null);
    }

    public DagVisualizer(
            Controller controller,
            String projectName,
            BenchmarkReport benchmarkReport,
            Runnable onBackToMenu) {

        this.controller = controller;
        this.projectName = projectName;
        this.benchmarkReport = benchmarkReport;
        this.onBackToMenu = onBackToMenu;

        buildUI();
        rebuildGraph();
        observe("Project started");
        refreshAll();
    }

    // =====================================================
    // ================== UI STRUCTURE =====================
    // =====================================================

    private void buildUI() {
        setTitle(projectName == null || projectName.isBlank()
                ? "Task System Visualizer"
                : projectName + " — Task System Visualizer");
        setSize(1680, 980);
        setLayout(new BorderLayout());
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        add(buildToolbar(), BorderLayout.NORTH);

        // ---------- graph (left) ----------
        graphContainer = new JPanel(new BorderLayout());
        JPanel graphPanel = new JPanel(new BorderLayout());
        graphPanel.add(graphContainer, BorderLayout.CENTER);
        graphPanel.add(buildGraphFooter(), BorderLayout.SOUTH);

        // ---------- right: workers / ready queue / log tabs, benchmark below ----------
        rightSidePanel = new JPanel(new BorderLayout(0, 8));
        rightSidePanel.setBorder(new EmptyBorder(0, 6, 0, 6));

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Workers", tablePanel(workersModel,
                "Running tasks and their estimated finish times."));
        tabs.addTab("Ready Queue", tablePanel(readyModel,
                "Tasks whose dependencies are complete, by static score. When a worker becomes free the "
                        + "scheduler blends static and dynamic scores and, if more tasks are ready than free "
                        + "workers, compares the best candidates with a rollout lookahead."));
        eventLog.setEditable(false);
        eventLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        tabs.addTab("Event Log", new JScrollPane(eventLog));
        rightSidePanel.add(tabs, BorderLayout.CENTER);

        if (benchmarkReport != null) {
            BenchmarkPanel benchmarkPanel = new BenchmarkPanel(benchmarkReport);
            rightSidePanel.add(benchmarkPanel, BorderLayout.SOUTH);
        }

        mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, graphPanel, rightSidePanel);
        mainSplitPane.setContinuousLayout(true);
        mainSplitPane.setOneTouchExpandable(true);
        mainSplitPane.setResizeWeight(0.68);
        normalDividerSize = mainSplitPane.getDividerSize();

        // ---------- bottom: Gantt chart ----------
        bottomPanel = new JPanel(new BorderLayout());
        JScrollPane ganttScroll = new JScrollPane(gantt);
        ganttScroll.getHorizontalScrollBar().setUnitIncrement(24);
        JPanel ganttHeader = new JPanel(new BorderLayout());
        ganttHeader.setBorder(new EmptyBorder(4, 8, 2, 8));
        JLabel ganttTitle = new JLabel("Schedule (Gantt chart) — solid: finished, dashed: running until its estimated finish, red line: now");
        ganttTitle.setFont(ganttTitle.getFont().deriveFont(Font.BOLD));
        ganttHeader.add(ganttTitle, BorderLayout.WEST);
        JPanel ganttZoom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        ganttZoom.add(smallButton("−", "Zoom out the time axis", e -> gantt.zoom(1 / 1.4)));
        ganttZoom.add(smallButton("+", "Zoom in the time axis", e -> gantt.zoom(1.4)));
        ganttHeader.add(ganttZoom, BorderLayout.EAST);
        bottomPanel.add(ganttHeader, BorderLayout.NORTH);
        bottomPanel.add(ganttScroll, BorderLayout.CENTER);

        verticalSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, mainSplitPane, bottomPanel);
        verticalSplitPane.setContinuousLayout(true);
        verticalSplitPane.setResizeWeight(0.6);
        add(verticalSplitPane, BorderLayout.CENTER);

        add(buildActionsAndStatus(), BorderLayout.SOUTH);

        SwingUtilities.invokeLater(() -> {
            mainSplitPane.setDividerLocation(0.68);
            verticalSplitPane.setDividerLocation(0.6);
        });

        installKeyBindings();
    }

    private JComponent buildToolbar() {
        JPanel top = new JPanel(new BorderLayout(12, 0));
        top.setBorder(new EmptyBorder(6, 8, 6, 8));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        if (onBackToMenu != null) {
            JButton backButton = new JButton("← Projects");
            backButton.setToolTipText("Return to the project menu");
            backButton.addActionListener(e -> returnToProjectMenu());
            left.add(backButton);
            left.add(Box.createHorizontalStrut(10));
        }

        nextButton = new JButton("Next Event ⏭");
        nextButton.setToolTipText("The worker with the earliest estimated finish completes on time (Space / Right arrow)");
        nextButton.addActionListener(e -> stepOnce());

        playButton = new JButton("▶ Play");
        playButton.setToolTipText("Advance automatically, one on-time completion per tick (P)");
        playButton.addActionListener(e -> togglePlay());

        speedSlider = new JSlider(1, 10, 5);
        speedSlider.setToolTipText("Playback speed");
        speedSlider.setPreferredSize(new Dimension(110, speedSlider.getPreferredSize().height));
        speedSlider.addChangeListener(e -> {
            if (playTimer != null) playTimer.setDelay(playDelay());
        });

        runToEndButton = new JButton("Run to End ⏩");
        runToEndButton.setToolTipText("Complete every remaining task on time");
        runToEndButton.addActionListener(e -> runToEnd());

        left.add(nextButton);
        left.add(playButton);
        left.add(new JLabel("Speed"));
        left.add(speedSlider);
        left.add(runToEndButton);
        top.add(left, BorderLayout.WEST);

        JPanel center = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        JLabel timeTitle = new JLabel("Global time:");
        timeTitle.setFont(timeTitle.getFont().deriveFont(Font.BOLD, 20f));
        globalTimeLabel = new JLabel();
        globalTimeLabel.setFont(globalTimeLabel.getFont().deriveFont(Font.BOLD, 20f));
        progress = new JProgressBar();
        progress.setStringPainted(true);
        progress.setPreferredSize(new Dimension(220, 22));
        center.add(timeTitle);
        center.add(globalTimeLabel);
        center.add(Box.createHorizontalStrut(10));
        center.add(progress);
        top.add(center, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        expandGraphButton = new JButton("Expand Graph");
        expandGraphButton.setToolTipText("Show the task graph using almost the entire window");
        expandGraphButton.addActionListener(e -> toggleExpandedGraph());
        right.add(expandGraphButton);
        top.add(right, BorderLayout.EAST);
        return top;
    }

    private JComponent buildGraphFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(new EmptyBorder(4, 8, 4, 8));

        JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        legend.add(legendItem(COLOR_WAITING, "Waiting for dependencies"));
        legend.add(legendItem(COLOR_READY, "Ready"));
        legend.add(legendItem(COLOR_RUNNING, "Running"));
        legend.add(legendItem(COLOR_DONE, "Completed"));
        JLabel hint = new JLabel("  Click a task for details.");
        hint.setForeground(new Color(110, 110, 110));
        legend.add(hint);
        footer.add(legend, BorderLayout.WEST);

        JPanel zoom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        zoom.add(smallButton("−", "Zoom out", e -> graphComponent.zoomOut()));
        zoom.add(smallButton("+", "Zoom in", e -> graphComponent.zoomIn()));
        zoom.add(smallButton("1:1", "Actual size", e -> graphComponent.zoomActual()));
        zoom.add(smallButton("Fit", "Fit the whole graph", e -> fitGraph()));
        footer.add(zoom, BorderLayout.EAST);
        return footer;
    }

    private JComponent buildActionsAndStatus() {
        JPanel south = new JPanel(new BorderLayout());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 6));
        actions.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(215, 215, 215)));
        JLabel runtime = new JLabel("Runtime changes:");
        runtime.setFont(runtime.getFont().deriveFont(Font.BOLD));
        actions.add(runtime);

        JButton finishEarly = new JButton("Worker Finished Early…");
        finishEarly.addActionListener(e -> showFinishEarlyDialog());
        JButton workerLate = new JButton("Worker Is Late…");
        workerLate.addActionListener(e -> showWorkerLateDialog());
        JButton addTask = new JButton("Add Task…");
        addTask.addActionListener(e -> showAddTaskDialog());
        JButton removeTask = new JButton("Remove Task…");
        removeTask.addActionListener(e -> showRemoveTaskDialog());
        JButton addWorker = new JButton("Add Worker…");
        addWorker.addActionListener(e -> showAddWorkerDialog());
        JButton removeWorker = new JButton("Remove Worker…");
        removeWorker.addActionListener(e -> showRemoveWorkerDialog());

        actions.add(finishEarly);
        actions.add(workerLate);
        actions.add(Box.createHorizontalStrut(14));
        actions.add(addTask);
        actions.add(removeTask);
        actions.add(Box.createHorizontalStrut(14));
        actions.add(addWorker);
        actions.add(removeWorker);
        south.add(actions, BorderLayout.CENTER);

        statusLabel = new JLabel(" ");
        statusLabel.setBorder(new EmptyBorder(3, 10, 4, 10));
        statusLabel.setForeground(new Color(70, 70, 70));
        south.add(statusLabel, BorderLayout.SOUTH);
        return south;
    }

    private void installKeyBindings() {
        JRootPane root = getRootPane();
        InputMap in = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = root.getActionMap();
        in.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "next");
        in.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "next");
        in.put(KeyStroke.getKeyStroke(KeyEvent.VK_P, 0), "play");
        am.put("next", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (playTimer == null || !playTimer.isRunning()) stepOnce();
            }
        });
        am.put("play", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                togglePlay();
            }
        });
    }

    // =====================================================
    // ===================== PLAYBACK ======================
    // =====================================================

    /** One on-time completion: exactly the original "Worker finished on time" action. */
    private void stepOnce() {
        if (controller.getTasksLeft() == 0) {
            stopPlay();
            return;
        }
        try {
            controller.workerFinishOnTime();
            observe("On-time completion");
            refreshAll();
        } catch (Exception ex) {
            stopPlay();
            showError(ex.getMessage());
        }
    }

    private void togglePlay() {
        if (playTimer != null && playTimer.isRunning()) {
            stopPlay();
            return;
        }
        if (controller.getTasksLeft() == 0) return;
        playTimer = new javax.swing.Timer(playDelay(), e -> stepOnce());
        playTimer.start();
        playButton.setText("⏸ Pause");
    }

    private void stopPlay() {
        if (playTimer != null) playTimer.stop();
        playButton.setText("▶ Play");
    }

    private int playDelay() {
        // speed 1 = 1.5 s per event ... speed 10 = 0.06 s per event
        return (int) Math.round(1500 * Math.pow(0.7, speedSlider.getValue() - 1));
    }

    private void runToEnd() {
        stopPlay();
        int guard = 0;
        try {
            while (controller.getTasksLeft() > 0 && guard++ < 1_000_000) {
                if (controller.getActiveWorkers().isEmpty()) {
                    showError("No worker is running a task, so no completion can happen. "
                            + "Add a worker to continue.");
                    break;
                }
                controller.workerFinishOnTime();
                observe("On-time completion");
            }
        } catch (Exception ex) {
            showError(ex.getMessage());
        }
        refreshAll();
    }

    private void returnToProjectMenu() {
        stopPlay();
        int choice = JOptionPane.showConfirmDialog(
                this,
                "Return to the project menu?\nThe current simulation state will be discarded.",
                "Back to Projects",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );

        if (choice != JOptionPane.YES_OPTION) {
            return;
        }

        dispose();
        SwingUtilities.invokeLater(onBackToMenu);
    }

    private void toggleExpandedGraph() {
        graphExpanded = !graphExpanded;

        if (graphExpanded) {
            mainSplitPane.setRightComponent(null);
            mainSplitPane.setDividerSize(0);
            verticalSplitPane.setBottomComponent(null);
            verticalSplitPane.setDividerSize(0);
            expandGraphButton.setText("Restore Layout");
            setExtendedState(getExtendedState() | JFrame.MAXIMIZED_BOTH);
        } else {
            mainSplitPane.setRightComponent(rightSidePanel);
            mainSplitPane.setDividerSize(normalDividerSize);
            verticalSplitPane.setBottomComponent(bottomPanel);
            verticalSplitPane.setDividerSize(normalDividerSize);
            expandGraphButton.setText("Expand Graph");

            SwingUtilities.invokeLater(() -> {
                mainSplitPane.setDividerLocation(0.68);
                verticalSplitPane.setDividerLocation(0.6);
            });
        }

        revalidate();
        repaint();
    }

    // =====================================================
    // ================= RUNTIME DIALOGS ===================
    // =====================================================

    private void showFinishEarlyDialog() {
        Worker worker = chooseWorker("Worker Finished Early", controller.getActiveWorkers(), true);
        if (worker == null) return;
        Integer time = askTime("Worker Finished Early",
                "Actual finish time for " + worker.getName() + " (current time " + controller.getGlobalTime()
                        + ", estimated finish " + worker.getTaskEstimatedEndTime() + "):",
                controller.getGlobalTime());
        if (time == null) return;
        runAction("Finished early: " + worker.getName() + " at " + time,
                () -> controller.workerFinishEarly(worker.getId(), time), false);
    }

    private void showWorkerLateDialog() {
        Worker worker = chooseWorker("Worker Is Late", controller.getActiveWorkers(), true);
        if (worker == null) return;
        Integer time = askTime("Worker Is Late",
                "New estimated finish time for " + worker.getName() + " (currently "
                        + worker.getTaskEstimatedEndTime() + "):",
                worker.getTaskEstimatedEndTime() + 1);
        if (time == null) return;
        runAction("Late: " + worker.getName() + " now estimated to finish at " + time,
                () -> controller.workerIsLate(worker.getId(), time), false);
    }

    private void showAddWorkerDialog() {
        int suggestedId = 1;
        for (Worker w : allWorkers()) suggestedId = Math.max(suggestedId, w.getId() + 1);
        JTextField idField = new JTextField(String.valueOf(suggestedId));
        JTextField nameField = new JTextField("Worker " + suggestedId);
        JPanel panel = new JPanel(new GridLayout(0, 2, 10, 8));
        panel.add(new JLabel("Worker id:"));
        panel.add(idField);
        panel.add(new JLabel("Worker name:"));
        panel.add(nameField);
        if (JOptionPane.showConfirmDialog(this, panel, "Add Worker", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        try {
            int id = Integer.parseInt(idField.getText().trim());
            String name = nameField.getText().trim();
            runAction("Worker added: " + name + " [id " + id + "]", () -> controller.addWorker(id, name), false);
        } catch (NumberFormatException ex) {
            showError("Worker id must be a whole number.");
        }
    }

    private void showRemoveWorkerDialog() {
        Worker worker = chooseWorker("Remove Worker", allWorkers(), false);
        if (worker == null) return;
        runAction("Remove worker: " + worker.getName() + " [id " + worker.getId() + "]",
                () -> controller.removeWorker(worker.getId()), false);
    }

    private void showAddTaskDialog() {
        int suggestedId = 1;
        for (GraphTaskNode n : controller.getAllTasks()) suggestedId = Math.max(suggestedId, n.getId() + 1);
        for (GanttChartPanel.Bar b : barsByKey.values()) suggestedId = Math.max(suggestedId, b.taskId + 1);

        JTextField idField = new JTextField(String.valueOf(suggestedId));
        JTextField nameField = new JTextField();
        JTextField durationField = new JTextField();
        JTextField parentsField = new JTextField();
        JTextField childrenField = new JTextField();

        JPanel panel = new JPanel(new GridLayout(0, 2, 10, 10));
        panel.add(new JLabel("Task ID:"));
        panel.add(idField);
        panel.add(new JLabel("Task Name:"));
        panel.add(nameField);
        panel.add(new JLabel("Duration:"));
        panel.add(durationField);
        panel.add(new JLabel("Depends on (parent IDs, comma separated):"));
        panel.add(parentsField);
        panel.add(new JLabel("Children IDs (comma separated):"));
        panel.add(childrenField);

        if (JOptionPane.showConfirmDialog(this, panel, "Add New Task", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;

        try {
            int id = Integer.parseInt(idField.getText().trim());
            String name = nameField.getText().trim();
            int duration = Integer.parseInt(durationField.getText().trim());
            LinkedList<Integer> parents = parseIds(parentsField.getText());
            LinkedList<Integer> children = parseIds(childrenField.getText());
            Task newTask = new Task(name, id, duration, parents);
            runAction("Task added: " + name + " (#" + id + ")", () -> controller.addTask(newTask, children), true);
        } catch (NumberFormatException ex) {
            showError("Task id, duration and dependency ids must be whole numbers.");
        }
    }

    private void showRemoveTaskDialog() {
        List<GraphTaskNode> removable = new ArrayList<>();
        for (GraphTaskNode n : controller.getAllTasks()) {
            if (!n.isCompleted() && !controller.getWorkerManager().isTaskInProgress(n)) removable.add(n);
        }
        if (removable.isEmpty()) {
            showError("There is no waiting task to remove.");
            return;
        }
        removable.sort(Comparator.comparingInt(GraphTaskNode::getId));
        JComboBox<String> box = new JComboBox<>();
        for (GraphTaskNode n : removable) box.addItem("#" + n.getId() + "  " + n.getName());
        if (JOptionPane.showConfirmDialog(this, box, "Remove Task", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        GraphTaskNode chosen = removable.get(box.getSelectedIndex());
        Task task = new Task("", chosen.getId(), 0, new LinkedList<>());
        runAction("Task removed: " + chosen.getName() + " (#" + chosen.getId() + ")",
                () -> controller.removeTask(task), true);
    }

    private void runAction(String description, Runnable action, boolean graphChanged) {
        stopPlay();
        try {
            action.run();
            if (graphChanged) {
                rebuildGraph();
            }
            observe(description);
            refreshAll();
        } catch (Exception ex) {
            showError(ex.getMessage());
        }
    }

    private Worker chooseWorker(String title, List<Worker> workers, boolean showTask) {
        if (workers.isEmpty()) {
            showError(showTask ? "No worker is running a task right now." : "There are no workers.");
            return null;
        }
        List<Worker> sorted = new ArrayList<>(workers);
        sorted.sort(Comparator.comparingInt(Worker::getId));
        JComboBox<String> box = new JComboBox<>();
        for (Worker w : sorted) {
            String text = w.getName() + " [id " + w.getId() + "]";
            if (w.getDesk() != null) {
                text += "  → " + w.getDesk().getName() + " (#" + w.getDesk().getId()
                        + "), est. finish " + w.getTaskEstimatedEndTime();
            } else {
                text += "  (idle)";
            }
            box.addItem(text);
        }
        if (JOptionPane.showConfirmDialog(this, box, title, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return null;
        return sorted.get(box.getSelectedIndex());
    }

    private Integer askTime(String title, String message, int initial) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(initial, 0, Integer.MAX_VALUE, 1));
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(new JLabel(message), BorderLayout.NORTH);
        panel.add(spinner, BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(this, panel, title, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return null;
        return (Integer) spinner.getValue();
    }

    private List<Worker> allWorkers() {
        List<Worker> all = new ArrayList<>(controller.getActiveWorkers());
        all.addAll(controller.getIdleWorkers());
        return all;
    }

    private static LinkedList<Integer> parseIds(String text) {
        LinkedList<Integer> ids = new LinkedList<>();
        if (text != null && !text.trim().isEmpty()) {
            for (String s : text.split(",")) ids.add(Integer.parseInt(s.trim()));
        }
        return ids;
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(
                this,
                message == null ? "The action could not be completed." : message,
                "Error",
                JOptionPane.ERROR_MESSAGE
        );
    }

    // =====================================================
    // ================= GRAPH REBUILD =====================
    // =====================================================

    private void rebuildGraph() {
        nodeCells.clear();
        graphContainer.removeAll();

        graph = new mxGraph();
        graph.setHtmlLabels(true);
        graph.setCellsEditable(false);
        graph.setCellsDisconnectable(false);
        graph.setConnectableEdges(false);
        graph.setCellsResizable(false);
        graph.setEdgeLabelsMovable(false);
        Object parent = graph.getDefaultParent();

        graph.getModel().beginUpdate();
        try {
            for (GraphTaskNode n : controller.getAllTasks()) {
                Object v = graph.insertVertex(
                        parent, null, nodeLabel(n, null),
                        0, 0, 250, 72,
                        "rounded=true;fontColor=black;html=1;whiteSpace=wrap;spacing=8;align=center;verticalAlign=middle;"
                );
                nodeCells.put(n.getId(), v);
            }

            for (GraphTaskNode n : controller.getAllTasks()) {
                for (GraphTaskNode p : n.getParents()) {
                    if (nodeCells.containsKey(p.getId())) {
                        graph.insertEdge(parent, null, "", nodeCells.get(p.getId()), nodeCells.get(n.getId()));
                    }
                }
            }
        } finally {
            graph.getModel().endUpdate();
        }

        mxHierarchicalLayout layout = new mxHierarchicalLayout(graph);
        layout.setInterRankCellSpacing(75);
        layout.setIntraCellSpacing(45);
        layout.execute(parent);

        graphComponent = new mxGraphComponent(graph);
        graphComponent.setConnectable(false);
        graphComponent.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        graphComponent.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        graphComponent.getHorizontalScrollBar().setUnitIncrement(24);
        graphComponent.getVerticalScrollBar().setUnitIncrement(24);

        graphComponent.getGraphControl().addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                Object cell = graphComponent.getCellAt(e.getX(), e.getY());
                if (cell == null) return;

                for (Map.Entry<Integer, Object> entry : nodeCells.entrySet()) {
                    if (entry.getValue() == cell) {
                        showTaskScore(controller.getTaskEngine().getGraphTaskNode(entry.getKey()));
                        break;
                    }
                }
            }
        });
        graphContainer.add(graphComponent, BorderLayout.CENTER);
        graphContainer.revalidate();
        graphContainer.repaint();
    }

    private void fitGraph() {
        Dimension view = graphComponent.getViewport().getExtentSize();
        com.mxgraph.util.mxRectangle bounds = graph.getGraphBounds();
        double scale = graph.getView().getScale();
        double w = bounds.getWidth() / scale + 40;
        double h = bounds.getHeight() / scale + 40;
        if (w <= 0 || h <= 0 || view.width <= 0 || view.height <= 0) return;
        graphComponent.zoomTo(Math.min(1.5, Math.min(view.width / w, view.height / h)), false);
    }

    private String nodeLabel(GraphTaskNode n, String workerName) {
        return "<html><div style='text-align:center'><b>"
                + escapeHtml(n.getName())
                + "</b><br/>#" + n.getId()
                + " &middot; duration " + n.getDuration()
                + (workerName == null ? "" : "<br/>&#9654; " + escapeHtml(workerName))
                + "</div></html>";
    }

    private void showTaskScore(GraphTaskNode node) {
        if (node == null) return;
        String status = node.isCompleted() ? "completed"
                : controller.getWorkerManager().isTaskInProgress(node) ? "running"
                : node.getParentsRemained().isEmpty() ? "ready" : "waiting for " + node.getParentsRemained().size()
                        + " unfinished dependenc" + (node.getParentsRemained().size() == 1 ? "y" : "ies");
        JOptionPane.showMessageDialog(this,
                "Task: " + node.getName()
                        + "\nTask id: " + node.getId()
                        + "\nDuration: " + node.getDuration()
                        + "\nStatus: " + status
                        + "\n\nTotal static score: " + String.format(Locale.ROOT, "%.4f", node.getScore())
                        + "\n1. critical path: " + node.getCriticalPaths()
                        + "\n2. reachable duration: " + node.getReachableDuration()
                        + "\n3. blocking power: " + node.getBlockingPower(),
                "Task Details",
                JOptionPane.INFORMATION_MESSAGE);
    }

    // =====================================================
    // ===================== OBSERVE =======================
    // =====================================================

    /** Records starts and finishes visible in the controller state after an action. */
    private void observe(String action) {
        int now = controller.getGlobalTime();
        Set<String> activeKeys = new HashSet<>();
        List<String> started = new ArrayList<>();
        List<String> finished = new ArrayList<>();

        for (Worker w : controller.getActiveWorkers()) {
            laneNames.putIfAbsent(w.getId(), w.getName() + " [" + w.getId() + "]");
            GraphTaskNode task = w.getDesk();
            String key = w.getId() + ":" + task.getId() + ":" + w.getStartTime();
            activeKeys.add(key);
            GanttChartPanel.Bar bar = barsByKey.get(key);
            if (bar == null) {
                barsByKey.put(key, new GanttChartPanel.Bar(w.getId(), task.getId(), task.getName(),
                        w.getStartTime(), w.getTaskEstimatedEndTime(), true));
                started.add(task.getName() + " (#" + task.getId() + ") on " + w.getName());
            } else {
                bar.end = w.getTaskEstimatedEndTime();
            }
        }
        for (Worker w : controller.getIdleWorkers()) {
            laneNames.putIfAbsent(w.getId(), w.getName() + " [" + w.getId() + "]");
        }
        for (Map.Entry<String, GanttChartPanel.Bar> entry : barsByKey.entrySet()) {
            GanttChartPanel.Bar bar = entry.getValue();
            if (bar.running && !activeKeys.contains(entry.getKey())) {
                bar.running = false;
                bar.end = now;
                finished.add(bar.taskName + " (#" + bar.taskId + ")");
            }
        }

        StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "t=%-5d %s", now, action));
        if (!finished.isEmpty()) line.append(" | finished: ").append(String.join(", ", finished));
        if (!started.isEmpty()) line.append(" | started: ").append(String.join(", ", started));
        eventLog.append(line.append('\n').toString());
        eventLog.setCaretPosition(eventLog.getDocument().getLength());
    }

    // =====================================================
    // ===================== REFRESH =======================
    // =====================================================

    private void refreshAll() {
        refreshTime();
        refreshGraphColors();
        refreshWorkers();
        refreshReadyQueue();
        gantt.update(new TreeMap<>(laneNames), new ArrayList<>(barsByKey.values()), controller.getGlobalTime());
        refreshStatus();
    }

    private void refreshTime() {
        globalTimeLabel.setText(String.valueOf(controller.getGlobalTime()));
    }

    private void refreshGraphColors() {
        Map<Integer, String> runningOn = new HashMap<>();
        for (Worker w : controller.getActiveWorkers()) runningOn.put(w.getDesk().getId(), w.getName());

        graph.getModel().beginUpdate();
        try {
            for (GraphTaskNode n : controller.getAllTasks()) {
                Object cell = nodeCells.get(n.getId());
                if (cell == null) continue;

                String color;
                String fontColor = "black";
                if (n.isCompleted()) {
                    color = COLOR_DONE;
                    fontColor = "#606060";
                } else if (runningOn.containsKey(n.getId())) {
                    color = COLOR_RUNNING;
                } else if (n.getParentsRemained().isEmpty()) {
                    color = COLOR_READY;
                } else {
                    color = COLOR_WAITING;
                }

                graph.setCellStyles(mxConstants.STYLE_FILLCOLOR, color, new Object[]{cell});
                graph.setCellStyles(mxConstants.STYLE_FONTCOLOR, fontColor, new Object[]{cell});
                graph.getModel().setValue(cell, nodeLabel(n, runningOn.get(n.getId())));
            }
        } finally {
            graph.getModel().endUpdate();
        }
    }

    private void refreshWorkers() {
        workersModel.setRowCount(0);
        List<Worker> active = new ArrayList<>(controller.getActiveWorkers());
        active.sort(Comparator.comparingInt(Worker::getTaskEstimatedEndTime).thenComparingInt(Worker::getId));
        for (Worker w : active) {
            workersModel.addRow(new Object[]{w.getName(), w.getId(), "running",
                    w.getDesk().getName() + " (#" + w.getDesk().getId() + ")",
                    w.getStartTime(), w.getTaskEstimatedEndTime()});
        }
        List<Worker> idle = new ArrayList<>(controller.getIdleWorkers());
        idle.sort(Comparator.comparingInt(Worker::getId));
        for (Worker w : idle) {
            workersModel.addRow(new Object[]{w.getName(), w.getId(), "idle", "—", "", ""});
        }
    }

    private void refreshReadyQueue() {
        readyModel.setRowCount(0);
        PriorityQueue<GraphTaskNode> ready = controller.getTaskEngine().getReadyPQCopy();
        int rank = 1;
        while (!ready.isEmpty()) {
            GraphTaskNode n = ready.poll();
            readyModel.addRow(new Object[]{rank++, n.getName(), n.getId(), n.getDuration(),
                    String.format(Locale.ROOT, "%.4f", n.getScore()), n.getCriticalPaths()});
        }
    }

    private void refreshStatus() {
        int done = controller.getTasksCompleted();
        int total = done + controller.getTasksLeft();
        progress.setMaximum(Math.max(1, total));
        progress.setValue(done);
        progress.setString(done + " / " + total + " tasks completed");

        boolean finished = controller.getTasksLeft() == 0;
        nextButton.setEnabled(!finished);
        playButton.setEnabled(!finished);
        runToEndButton.setEnabled(!finished);

        if (finished) {
            stopPlay();
            String text = "All tasks finished at time " + controller.getGlobalTime() + ".";
            if (benchmarkReport != null) {
                text += " Benchmark (no runtime changes): adaptive scheduler "
                        + benchmarkReport.getMakespan(BenchmarkStrategy.ADAPTIVE)
                        + ", best baseline " + bestBaseline() + ".";
            }
            statusLabel.setText(text);
            if (!finishAnnounced) {
                finishAnnounced = true;
                eventLog.append("All tasks finished at t=" + controller.getGlobalTime() + "\n");
            }
        } else {
            statusLabel.setText("Ready tasks: " + controller.getTaskEngine().getReadyPQ().size()
                    + "   ·   Running: " + controller.getActiveWorkers().size()
                    + "   ·   Idle workers: " + controller.getIdleWorkers().size()
                    + "   ·   Tasks left: " + controller.getTasksLeft()
                    + "      (Space / Right arrow: next event,  P: play / pause)");
        }
    }

    private String bestBaseline() {
        int best = Integer.MAX_VALUE;
        String name = "";
        for (BenchmarkStrategy s : BenchmarkStrategy.values()) {
            if (s == BenchmarkStrategy.ADAPTIVE) continue;
            if (benchmarkReport.getMakespan(s) < best) {
                best = benchmarkReport.getMakespan(s);
                name = s.getDisplayName();
            }
        }
        return best + " (" + name + ")";
    }

    // =====================================================
    // ===================== HELPERS =======================
    // =====================================================

    private static DefaultTableModel readOnlyModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private static JComponent tablePanel(DefaultTableModel model, String caption) {
        JTable table = new JTable(model);
        table.setRowHeight(22);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(true);
        DefaultTableCellRenderer left = new DefaultTableCellRenderer();
        left.setHorizontalAlignment(SwingConstants.LEFT);
        table.setDefaultRenderer(Object.class, left);
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        JLabel label = new JLabel("<html>" + caption + "</html>");
        label.setForeground(new Color(90, 90, 90));
        label.setBorder(new EmptyBorder(4, 4, 2, 4));
        panel.add(label, BorderLayout.NORTH);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        return panel;
    }

    private static JComponent legendItem(String hexColor, String text) {
        JLabel label = new JLabel(text);
        label.setIcon(new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                g.setColor(Color.decode(hexColor));
                g.fillRoundRect(x, y, 14, 14, 4, 4);
                g.setColor(Color.GRAY);
                g.drawRoundRect(x, y, 14, 14, 4, 4);
            }

            @Override
            public int getIconWidth() {
                return 15;
            }

            @Override
            public int getIconHeight() {
                return 15;
            }
        });
        return label;
    }

    private static JButton smallButton(String text, String tooltip, java.awt.event.ActionListener action) {
        JButton b = new JButton(text);
        b.setToolTipText(tooltip);
        b.setMargin(new Insets(2, 8, 2, 8));
        b.addActionListener(action);
        return b;
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }

        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
