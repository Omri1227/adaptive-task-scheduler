package backend;

import java.util.LinkedList;

/**
 * Immutable description of a demo project used by the visualizer.
 *
 * The scenario owns task templates and returns fresh copies so every run starts
 * from a clean state even if the same scenario is launched more than once.
 */
public final class ProjectScenario {

    private final int number;
    private final String name;
    private final String description;
    private final String schedulingFocus;
    private final LinkedList<Task> taskTemplates;
    private final LinkedList<String> workerNames;

    public ProjectScenario(
            int number,
            String name,
            String description,
            String schedulingFocus,
            LinkedList<Task> tasks,
            LinkedList<String> workers) {

        if (number <= 0) {
            throw new IllegalArgumentException(
                    "scenario number must be positive"
            );
        }

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "scenario name cannot be empty"
            );
        }

        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException(
                    "scenario must contain at least one task"
            );
        }

        if (workers == null || workers.isEmpty()) {
            throw new IllegalArgumentException(
                    "scenario must contain at least one worker"
            );
        }

        this.number = number;
        this.name = name;
        this.description =
                description == null ? "" : description;
        this.schedulingFocus =
                schedulingFocus == null ? "" : schedulingFocus;

        this.taskTemplates =
                copyTasks(tasks);

        this.workerNames =
                new LinkedList<>(workers);
    }

    public int getNumber() {
        return number;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getSchedulingFocus() {
        return schedulingFocus;
    }

    public int getTaskCount() {
        return taskTemplates.size();
    }

    public int getWorkerCount() {
        return workerNames.size();
    }

    /**
     * Returns a fresh copy of the scenario tasks.
     *
     * This is important because Controller/TaskEngine mutate task-related state
     * during a simulation. Every benchmark or UI run must therefore receive an
     * independent copy of the original project definition.
     */
    public LinkedList<Task> getTasks() {
        return copyTasks(taskTemplates);
    }

    /**
     * Returns a fresh copy of the worker-name list.
     */
    public LinkedList<String> getWorkers() {
        return new LinkedList<>(workerNames);
    }

    @Override
    public String toString() {
        return String.format(
                "%02d. %s",
                number,
                name
        );
    }

    private static LinkedList<Task> copyTasks(
            LinkedList<Task> source) {

        LinkedList<Task> result =
                new LinkedList<>();

        for (Task task : source) {
            result.add(
                    new Task(
                            task.getName(),
                            task.getId(),
                            task.getDuration(),
                            new LinkedList<>(
                                    task.getDependsOnIDS()
                            )
                    )
            );
        }

        return result;
    }
}
