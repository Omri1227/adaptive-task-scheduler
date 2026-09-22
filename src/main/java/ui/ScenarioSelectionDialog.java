package ui;

import backend.ProjectScenario;
import backend.ScenarioLibrary;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * Modal startup dialog used to choose one of the curated demo projects.
 */
public final class ScenarioSelectionDialog extends JDialog {
    private final JList<ProjectScenario> scenarioList;
    private final JLabel projectName = new JLabel();
    private final JLabel projectSize = new JLabel();
    private final JTextArea description = createReadOnlyTextArea();
    private final JTextArea schedulingFocus = createReadOnlyTextArea();

    private ProjectScenario selection;

    private ScenarioSelectionDialog(Frame owner, List<ProjectScenario> scenarios) {
        super(owner, "Choose a Project Scenario", true);

        if (scenarios == null || scenarios.isEmpty()) {
            throw new IllegalArgumentException("at least one scenario is required");
        }

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(860, 560));
        setSize(920, 600);
        setLayout(new BorderLayout(16, 16));

        JPanel content = new JPanel(new BorderLayout(16, 16));
        content.setBorder(new EmptyBorder(18, 18, 18, 18));
        add(content, BorderLayout.CENTER);

        JLabel heading = new JLabel("Choose a project to simulate");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 24f));

        JLabel subtitle = new JLabel(
                "Projects grow from 6 tasks / 2 workers to 50 tasks / 10 workers.");
        subtitle.setForeground(new Color(90, 90, 90));

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.add(heading);
        header.add(Box.createVerticalStrut(4));
        header.add(subtitle);
        content.add(header, BorderLayout.NORTH);

        scenarioList = new JList<>(scenarios.toArray(new ProjectScenario[0]));
        scenarioList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        scenarioList.setFixedCellHeight(58);
        scenarioList.setCellRenderer(new ScenarioCellRenderer());
        scenarioList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                refreshDetails(scenarioList.getSelectedValue());
            }
        });
        scenarioList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && scenarioList.getSelectedValue() != null) {
                    startSelectedScenario();
                }
            }
        });

        JScrollPane listScroll = new JScrollPane(scenarioList);
        listScroll.setPreferredSize(new Dimension(390, 420));

        JPanel details = buildDetailsPanel();

        JSplitPane splitPane = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                listScroll,
                details
        );
        splitPane.setResizeWeight(0.43);
        splitPane.setDividerLocation(390);
        splitPane.setBorder(null);
        content.add(splitPane, BorderLayout.CENTER);

        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(event -> dispose());

        JButton startButton = new JButton("Start Project");
        startButton.setFont(startButton.getFont().deriveFont(Font.BOLD));
        startButton.addActionListener(event -> startSelectedScenario());
        getRootPane().setDefaultButton(startButton);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttons.add(cancelButton);
        buttons.add(startButton);
        content.add(buttons, BorderLayout.SOUTH);

        scenarioList.setSelectedIndex(0);
        setLocationRelativeTo(owner);
    }

    public static ProjectScenario showDialog(Frame owner) {
        ScenarioSelectionDialog dialog = new ScenarioSelectionDialog(
                owner,
                ScenarioLibrary.getScenarios()
        );
        dialog.setVisible(true);
        return dialog.selection;
    }

    private JPanel buildDetailsPanel() {
        JPanel panel = new JPanel();
        panel.setBorder(new EmptyBorder(6, 14, 6, 6));
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        projectName.setFont(projectName.getFont().deriveFont(Font.BOLD, 22f));
        projectName.setAlignmentX(Component.LEFT_ALIGNMENT);

        projectSize.setForeground(new Color(70, 70, 70));
        projectSize.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel overviewLabel = sectionLabel("Project flow");
        JLabel focusLabel = sectionLabel("Scheduling focus");

        description.setAlignmentX(Component.LEFT_ALIGNMENT);
        schedulingFocus.setAlignmentX(Component.LEFT_ALIGNMENT);

        panel.add(projectName);
        panel.add(Box.createVerticalStrut(6));
        panel.add(projectSize);
        panel.add(Box.createVerticalStrut(22));
        panel.add(overviewLabel);
        panel.add(Box.createVerticalStrut(6));
        panel.add(description);
        panel.add(Box.createVerticalStrut(22));
        panel.add(focusLabel);
        panel.add(Box.createVerticalStrut(6));
        panel.add(schedulingFocus);
        panel.add(Box.createVerticalGlue());

        return panel;
    }

    private void refreshDetails(ProjectScenario scenario) {
        if (scenario == null) {
            return;
        }

        projectName.setText(scenario.getName());
        projectSize.setText(
                scenario.getTaskCount() + " tasks  ·  "
                        + scenario.getWorkerCount() + " workers");
        description.setText(scenario.getDescription());
        schedulingFocus.setText(scenario.getSchedulingFocus());
    }

    private void startSelectedScenario() {
        ProjectScenario selected = scenarioList.getSelectedValue();
        if (selected == null) {
            JOptionPane.showMessageDialog(
                    this,
                    "Please choose a project first.",
                    "No project selected",
                    JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        selection = selected;
        dispose();
    }

    private static JTextArea createReadOnlyTextArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFocusable(false);
        area.setFont(UIManager.getFont("Label.font").deriveFont(14f));
        area.setRows(5);
        area.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
        return area;
    }

    private static JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 15f));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static final class ScenarioCellRenderer extends JPanel
            implements ListCellRenderer<ProjectScenario> {

        private final JLabel nameLabel = new JLabel();
        private final JLabel metaLabel = new JLabel();

        private ScenarioCellRenderer() {
            setLayout(new BorderLayout(8, 2));
            setBorder(new EmptyBorder(7, 10, 7, 10));

            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 14f));
            metaLabel.setFont(metaLabel.getFont().deriveFont(12f));

            add(nameLabel, BorderLayout.CENTER);
            add(metaLabel, BorderLayout.SOUTH);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends ProjectScenario> list,
                ProjectScenario value,
                int index,
                boolean isSelected,
                boolean cellHasFocus) {

            nameLabel.setText(String.format("%02d  %s", value.getNumber(), value.getName()));
            metaLabel.setText(value.getTaskCount() + " tasks  ·  "
                    + value.getWorkerCount() + " workers");

            Color background = isSelected
                    ? list.getSelectionBackground()
                    : list.getBackground();
            Color foreground = isSelected
                    ? list.getSelectionForeground()
                    : list.getForeground();

            setBackground(background);
            nameLabel.setForeground(foreground);
            metaLabel.setForeground(isSelected ? foreground : new Color(95, 95, 95));

            return this;
        }
    }
}
