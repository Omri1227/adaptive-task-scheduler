package ui;

import backend.Controller;
import backend.GraphTaskNode;
import backend.Task;
import backend.Worker;
import benchmark.BenchmarkReport;
import com.mxgraph.layout.hierarchical.mxHierarchicalLayout;
import com.mxgraph.swing.mxGraphComponent;
import com.mxgraph.util.mxConstants;
import com.mxgraph.view.mxGraph;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

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

    // ===== UI Panels =====
    private JPanel activePanel;
    private JPanel idlePanel;
    private JLabel globalTimeLabel;
    private JPanel rightSidePanel;
    private JPanel actionsPanel;
    private JSplitPane mainSplitPane;
    private JButton expandGraphButton;
    private boolean graphExpanded = false;
    private int normalDividerSize = 8;

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
        rebuildGraph();   // build once
        refreshAll();     // sync state
    }

    // =====================================================
    // ================== UI STRUCTURE =====================
    // =====================================================

    private void buildUI() {
        setTitle(projectName == null || projectName.isBlank()
                ? "Task System Visualizer"
                : projectName + " — Task System Visualizer");
        setSize(1800, 1000);
        setLayout(new BorderLayout());
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        // ---------- TOP : Navigation + Global Time + Graph controls ----------
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBorder(new EmptyBorder(6, 8, 6, 8));

        JPanel navigationPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        if (onBackToMenu != null) {
            JButton backButton = new JButton("\u2190 Back to Projects");
            backButton.addActionListener(e -> returnToProjectMenu());
            navigationPanel.add(backButton);
        }
        topPanel.add(navigationPanel, BorderLayout.WEST);

        JPanel timePanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        JLabel title = new JLabel("Global Time:");
        title.setFont(new Font("Arial", Font.BOLD, 20));

        globalTimeLabel = new JLabel();
        globalTimeLabel.setFont(new Font("Arial", Font.BOLD, 20));

        timePanel.add(title);
        timePanel.add(globalTimeLabel);
        topPanel.add(timePanel, BorderLayout.CENTER);

        JPanel graphControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        expandGraphButton = new JButton("Expand Graph");
        expandGraphButton.setToolTipText(
                "Show the task graph using almost the entire window"
        );
        expandGraphButton.addActionListener(e -> toggleExpandedGraph());
        graphControls.add(expandGraphButton);
        topPanel.add(graphControls, BorderLayout.EAST);

        add(topPanel, BorderLayout.NORTH);

        // ---------- CENTER : Resizable Graph / Right-side split ----------
        graphContainer = new JPanel(new BorderLayout());

        // ---------- RIGHT : Workers + Benchmark ----------
        rightSidePanel = new JPanel(new BorderLayout(0, 12));
        rightSidePanel.setBorder(new EmptyBorder(0, 8, 0, 8));
        rightSidePanel.setPreferredSize(new Dimension(590, 900));

        activePanel = new JPanel();
        activePanel.setLayout(new BoxLayout(activePanel, BoxLayout.Y_AXIS));

        idlePanel = new JPanel();
        idlePanel.setLayout(new BoxLayout(idlePanel, BoxLayout.Y_AXIS));

        JPanel workersPanel = new JPanel(new GridLayout(1, 2, 8, 0));
        workersPanel.add(wrapWithTitle("Active Workers", activePanel));
        workersPanel.add(wrapWithTitle("Idle Workers", idlePanel));
        rightSidePanel.add(workersPanel, BorderLayout.CENTER);

        if (benchmarkReport != null) {
            BenchmarkPanel benchmarkPanel = new BenchmarkPanel(benchmarkReport);
            benchmarkPanel.setPreferredSize(new Dimension(570, 285));
            rightSidePanel.add(benchmarkPanel, BorderLayout.SOUTH);
        }

        mainSplitPane = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                graphContainer,
                rightSidePanel
        );
        mainSplitPane.setContinuousLayout(true);
        mainSplitPane.setOneTouchExpandable(true);
        mainSplitPane.setResizeWeight(0.72);
        normalDividerSize = mainSplitPane.getDividerSize();
        add(mainSplitPane, BorderLayout.CENTER);

        SwingUtilities.invokeLater(() ->
                mainSplitPane.setDividerLocation(0.72)
        );

        // ---------- BOTTOM : Actions ----------
        actionsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        actionsPanel.setPreferredSize(new Dimension(1800, 90));

        JButton finishOnTime = new JButton("Worker finished on time");
        JButton finishEarly = new JButton("Worker finished early");
        JButton workerLate = new JButton("Worker is late");

        JButton addTask = new JButton("Add Task");
        JButton removeTask = new JButton("Remove Task");

        JButton addWorker = new JButton("Add Worker");
        JButton removeWorker = new JButton("Remove Worker");

        finishOnTime.addActionListener(e -> {
            try {
                controller.workerFinishOnTime();
                refreshAll();
            } catch (Exception ex) {
                showError(ex.getMessage());
            }
        });



        finishEarly.addActionListener(e -> {
            try {
                String workerIdStr = JOptionPane.showInputDialog(
                        this, "Enter worker id:");
                if (workerIdStr == null) return;

                String timeStr = JOptionPane.showInputDialog(
                        this, "Enter finish time");
                if (timeStr == null) return;

                int workerId = Integer.parseInt(workerIdStr);
                int time = Integer.parseInt(timeStr);

                controller.workerFinishEarly(workerId, time);
                refreshAll();

            } catch (Exception ex) {
                showError(ex.getMessage());
            }
        });
        workerLate.addActionListener(e -> {
            try {
                String idStr = JOptionPane.showInputDialog(
                        this, "Enter new estimated finish time:");
                if (idStr == null) return;

                String timeStr = JOptionPane.showInputDialog(
                        this, "Enter new estimated finish time:");
                if (timeStr == null) return;


                int time = Integer.parseInt(timeStr);
                int id = Integer.parseInt(idStr);

                controller.workerIsLate(id, time);
                refreshAll();

            } catch (Exception ex) {
                showError(ex.getMessage());
            }
        });

        addTask.addActionListener(e -> showAddTaskDialog());


        removeTask.addActionListener(e -> showRemoveTaskDialog());


        addWorker.addActionListener(e -> {
            try {
                String idStr = JOptionPane.showInputDialog(this, "Worker id:");
                if (idStr == null) return;

                String name = JOptionPane.showInputDialog(this, "Worker name:");
                if (name == null) return;

                controller.addWorker(Integer.parseInt(idStr), name);
                refreshAll();

            } catch (Exception ex) {
                showError(ex.getMessage());
            }
        });

        removeWorker.addActionListener(e -> {
            try {
                String idStr = JOptionPane.showInputDialog(this, "Worker id to remove:");
                if (idStr == null) return;

                controller.removeWorker(Integer.parseInt(idStr));
                refreshAll();

            } catch (Exception ex) {
                showError(ex.getMessage());
            }
        });

        actionsPanel.add(finishOnTime);
        actionsPanel.add(finishEarly);
        actionsPanel.add(workerLate);

        actionsPanel.add(Box.createHorizontalStrut(20));

        actionsPanel.add(addTask);
        actionsPanel.add(removeTask);

        actionsPanel.add(Box.createHorizontalStrut(20));

        actionsPanel.add(addWorker);
        actionsPanel.add(removeWorker);

        add(actionsPanel, BorderLayout.SOUTH);
    }
    private void returnToProjectMenu() {
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
            // Keep the same graph component; only give it almost the entire frame.
            mainSplitPane.setRightComponent(null);
            mainSplitPane.setDividerSize(0);
            actionsPanel.setVisible(false);
            expandGraphButton.setText("Restore Layout");
            setExtendedState(getExtendedState() | JFrame.MAXIMIZED_BOTH);
        } else {
            mainSplitPane.setRightComponent(rightSidePanel);
            mainSplitPane.setDividerSize(normalDividerSize);
            actionsPanel.setVisible(true);
            expandGraphButton.setText("Expand Graph");

            SwingUtilities.invokeLater(() ->
                    mainSplitPane.setDividerLocation(0.72)
            );
        }

        revalidate();
        repaint();
    }

    private void showAddTaskDialog() {
        try {
            JTextField idField = new JTextField();
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

            int result = JOptionPane.showConfirmDialog(
                    this,
                    panel,
                    "Add New Task",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE
            );

            if (result != JOptionPane.OK_OPTION) return;

            int id = Integer.parseInt(idField.getText().trim());
            String name = nameField.getText().trim();
            int duration = Integer.parseInt(durationField.getText().trim());

            LinkedList<Integer> parents = new LinkedList<>();
            if (!parentsField.getText().trim().isEmpty()) {
                for (String s : parentsField.getText().split(",")) {
                    parents.add(Integer.parseInt(s.trim()));
                }
            }

            LinkedList<Integer> children = new LinkedList<>();
            if (!childrenField.getText().trim().isEmpty()) {
                for (String s : childrenField.getText().split(",")) {
                    children.add(Integer.parseInt(s.trim()));
                }
            }

            Task newTask = new Task(name, id, duration, parents);
            controller.addTask(newTask, children);

            rebuildGraph();
            refreshAll();

        } catch (Exception ex) {
            showError(ex.getMessage());
        }
    }
    private void showRemoveTaskDialog() {
        try {
            String input = JOptionPane.showInputDialog(
                    this,
                    "Enter Task ID to remove:"
            );

            if (input == null || input.trim().isEmpty()) return;

            int id = Integer.parseInt(input.trim());

            Task task = new Task("", id, 0, new LinkedList<>());

            controller.removeTask(task);

            rebuildGraph();
            refreshAll();

        } catch (Exception ex) {
            showError(ex.getMessage());
        }
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(
                this,
                message,
                "Error",
                JOptionPane.ERROR_MESSAGE
        );
    }

    private JPanel wrapWithTitle(String title, JPanel panel) {
        JPanel wrapper = new JPanel();
        wrapper.setLayout(new BorderLayout());

        JLabel label = new JLabel(title, SwingConstants.CENTER);
        label.setFont(new Font("Arial", Font.BOLD, 16));

        wrapper.add(label, BorderLayout.NORTH);
        wrapper.add(new JScrollPane(panel), BorderLayout.CENTER);
        return wrapper;
    }

    // =====================================================
    // ================= GRAPH REBUILD =====================
    // =====================================================

    private void rebuildGraph() {
        nodeCells.clear();
        graphContainer.removeAll();

        graph = new mxGraph();
        graph.setHtmlLabels(true);
        Object parent = graph.getDefaultParent();

        graph.getModel().beginUpdate();
        try {
            for (GraphTaskNode n : controller.getAllTasks()) {
                Object v = graph.insertVertex(
                        parent, null,
                        "<html><div style='text-align:center'><b>"
                                + escapeHtml(n.getName())
                                + "</b><br/>#" + n.getId()
                                + " &middot; duration " + n.getDuration()
                                + "</div></html>",
                        0, 0, 250, 72,
                        "rounded=true;fontColor=black;html=1;whiteSpace=wrap;spacing=8;align=center;verticalAlign=middle;"
                );
                nodeCells.put(n.getId(), v);
            }

            for (GraphTaskNode n : controller.getAllTasks()) {
                for (GraphTaskNode p : n.getParents()) {
                    if (nodeCells.containsKey(p.getId())) {
                        graph.insertEdge(
                                parent, null, "",
                                nodeCells.get(p.getId()),
                                nodeCells.get(n.getId())
                        );
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
        graphComponent.setHorizontalScrollBarPolicy(
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        );
        graphComponent.setVerticalScrollBarPolicy(
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        );
        graphComponent.getHorizontalScrollBar().setUnitIncrement(24);
        graphComponent.getVerticalScrollBar().setUnitIncrement(24);

        graphComponent.getGraphControl().addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                Object cell = graphComponent.getCellAt(e.getX(), e.getY());
                if (cell == null) return;

                for (Map.Entry<Integer, Object> entry : nodeCells.entrySet()) {
                    if (entry.getValue() == cell) {
                        GraphTaskNode node =
                                controller.getTaskEngine().getGraphTaskNode(entry.getKey());

                        showTaskScore(node);
                        break;
                    }
                }
            }
        });
        graphContainer.add(graphComponent, BorderLayout.CENTER);
        graphContainer.revalidate();
        graphContainer.repaint();
    }

    private void showTaskScore(GraphTaskNode node) {
        JOptionPane.showMessageDialog(this,
                "Task: " + node.getName()
                        + "\nTask id: " + node.getId()
                        + "\nDuration: " + node.getDuration()
                        + "\nTotal static score: " + node.getScore()
                        + "\n1. critical path: " + node.getCriticalPaths()
                        + "\n2. reachable duration : " + node.getReachableDuration()
                        + "\n3. blocking power : " + node.getBlockingPower(),
                "Task Details",
                JOptionPane.INFORMATION_MESSAGE);
    }

    // =====================================================
    // ===================== REFRESH =======================
    // =====================================================

    private void refreshAll() {
        rebuildGraph();
        refreshTime();
        refreshGraphColors();
        refreshWorkers();
    }

    private void refreshTime() {
        globalTimeLabel.setText(String.valueOf(controller.getGlobalTime()));
    }

    private void refreshGraphColors() {
        graph.getModel().beginUpdate();
        try {
            for (GraphTaskNode n : controller.getAllTasks()) {
                Object cell = nodeCells.get(n.getId());
                if (cell == null) continue;

                String color;
                if (n.isCompleted()) {
                    color = "#A0A0A0";           // completed
                } else if (controller.getWorkerManager().isTaskInProgress(n)) {
                    color = "#87CEFA";           // in progress
                } else {
                    color = "#FFFFFF";           // waiting
                }

                graph.setCellStyles(
                        mxConstants.STYLE_FILLCOLOR,
                        color,
                        new Object[]{cell}
                );
            }
        } finally {
            graph.getModel().endUpdate();
        }
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

    private void refreshWorkers() {
        activePanel.removeAll();
        idlePanel.removeAll();

        // ---- Active ----
        for (Worker w : controller.getActiveWorkers()) {

            JLabel label = new JLabel(
                    "<html><div dir='ltr'>"
                            + w.getName() + " [id " + w.getId() + "]"
                            + " &rarr; " + w.getDesk().getName()
                            + " (#" + w.getDesk().getId() + ")"
                            + ", estimate finish time: "
                            + w.getTaskEstimatedEndTime()
                            + "</div></html>"
            );

            activePanel.add(label);
        }

        // ---- Idle ----
        for (Worker w : controller.getIdleWorkers()) {
            idlePanel.add(new JLabel(w.getName() + " [id " + w.getId() + "]"));
        }

        activePanel.revalidate();
        activePanel.repaint();
        idlePanel.revalidate();
        idlePanel.repaint();
    }
}