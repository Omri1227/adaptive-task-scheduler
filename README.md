Adaptive Task Scheduling Engine

A Java-based scheduling system for dynamically assigning interdependent tasks to multiple workers.

Instead of relying on a simple greedy rule such as shortest task first, the scheduler models tasks as
a Directed Acyclic Graph (DAG) and combines graph analysis, dynamic priority scoring, and short look-ahead
simulation to decide which ready task should be executed next.

Overview

Scheduling becomes more difficult when tasks depend on one another.
Choosing the shortest available task may appear efficient locally, but a longer task might unlock several
downstream tasks and allow more workers to operate in parallel. Similarly, delaying a critical task can block
a large part of the graph and increase the total completion time.

This project explores this problem by building a scheduler that continuously evaluates both:

the current value of executing a task;
the downstream effect that decision may have on the rest of the schedule.

The goal is to reduce worker idle time and complete the full task graph efficiently while respecting all task dependencies.

How It Works

Each task contains:

a unique ID;
an estimated duration;
zero or more dependencies.

The tasks are converted into a DAG, where an edge represents a dependency between two tasks.

A task becomes eligible for execution only when all of its required parent tasks have completed.

When a worker becomes available, the scheduler:

identifies all currently ready tasks;
evaluates their structural importance in the DAG;
calculates a dynamic score based on the current system state;
performs a short look-ahead simulation on the strongest candidates;
assigns the selected task to the available worker;
updates the graph when the task completes.

The process repeats until all tasks have been completed.

Scheduling Strategy

The scheduling decision combines several signals.

Static Graph Analysis

Before execution begins, the system analyzes the dependency graph and calculates characteristics such as:

critical-path involvement;
downstream blocking power;
reachable workload;
task duration;
dependency structure.

These values represent the structural importance of a task within the entire DAG.

Dynamic Scoring

Static importance alone is not enough.

The scheduler also considers the current state of the system, including:

number of available workers;
number of ready tasks;
task duration relative to other tasks;
how many new tasks could become available after completion;
current worker utilization.

This allows task priorities to change as execution progresses.

Look-Ahead Simulation

For the strongest candidate tasks, the scheduler simulates their near-term effect before making the final decision.

The simulation estimates how executing a candidate may affect:

future task availability;
worker idle time;
downstream dependencies.

This allows the scheduler to distinguish between tasks with similar immediate scores but different consequences for the
rest of the schedule.

Why Not Just Use a Greedy Strategy?

Simple scheduling strategies can make locally reasonable decisions while producing inefficient global schedules.

For example:

        A
       / \
      B   C
     / \   \
    D   E   F

If several tasks are currently available, choosing only according to duration ignores how much work each task unlocks.

A slightly longer task may release several independent tasks and keep multiple workers busy, while completing a shorter task
may unlock nothing.

The project is therefore designed to compare the adaptive scheduler against simpler baselines such as:

Shortest Processing Time;
Longest Processing Time;
FIFO / ready-queue order;
highest static graph score;
critical-path-oriented scheduling;
random ready-task selection.

These strategies can be evaluated on the same task graphs using the total schedule completion time and worker idle time.

Dynamic Runtime Events

The scheduler is not limited to a fixed schedule created before execution.

The system supports runtime changes including:

adding a worker;
removing a worker;
adding a task;
removing a task;
a worker finishing earlier than expected;
updating a worker's estimated completion time.

After relevant changes, the scheduler updates its internal state and continues scheduling the remaining work.

Architecture
Component	Responsibility
Controller	Coordinates execution and runtime events
TaskEngine	Manages ready tasks and selects the next task
TimeConstraints	Builds and analyzes the dependency DAG
DynamicScoreLogic	Calculates dynamic scores and performs look-ahead simulation
WorkerManager	Tracks workers, availability, and active assignments
GraphTaskNode	Represents a task inside the dependency graph
Task	Stores the external task definition
Worker	Represents a worker and execution history
DagVisualizer	Visualizes the current task graph using Swing
Correctness and Validation

The system validates several important scheduling invariants:

task IDs must be unique;
worker IDs must be unique;
task durations must be positive;
dependencies must reference valid tasks;
duplicate dependencies are rejected;
cyclic dependency graphs are rejected;
a task cannot execute before its dependencies are completed;
the same task cannot be assigned to multiple workers simultaneously.

The project includes unit, integration, and regression tests covering graph construction, task completion, worker management,
dynamic scheduling, runtime updates, and edge cases.

Visualization

The project includes a Swing-based DAG visualization that displays the current structure of the task graph and helps demonstrate
how dependencies change as tasks are completed.

This makes it possible to follow the scheduler's decisions visually rather than treating the scheduling engine as a black box.

Technologies
Java 21
Java Swing
JUnit
JGraphX
Data structures including priority queues, hash maps, sets, queues, and DAG representations


Running the Project

Run the Main class to start the example simulation and visualization.

The project can also be exercised directly through the Controller API or through the included test suite.

Future Work

Possible extensions include:

benchmarking the heuristic against additional scheduling algorithms;
automatically learning scoring weights from historical schedules;
supporting workers with different skills or task-specific execution speeds;
evaluating the scheduler on larger randomly generated DAGs;
extending the current look-ahead into deeper search strategies.
Project Goal

The project is primarily an exploration of a classic scheduling question:

When several tasks are ready, which one should be executed now in order to improve the schedule as a whole?

Rather than using a fixed greedy rule, the system combines dependency-graph structure with the current execution state to make that decision dynamically.