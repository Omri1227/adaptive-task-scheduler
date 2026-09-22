package backend;

import java.util.*;

/**
 * Curated benchmark scenarios for the scheduling engine.
 *
 * Every scenario is a realistic project workflow and is intentionally designed
 * to contain scheduling traps for one or more greedy policies. Durations and
 * dependencies were calibrated against the project's BenchmarkEngine so the
 * examples demonstrate meaningful differences rather than cosmetic DAGs.
 */
public final class ScenarioLibrary {
    private static final List<ProjectScenario> SCENARIOS = buildScenarios();

    private ScenarioLibrary() {}

    public static List<ProjectScenario> getScenarios() { return List.copyOf(SCENARIOS); }

    public static ProjectScenario getScenario(int number) {
        return SCENARIOS.stream()
                .filter(scenario -> scenario.getNumber() == number)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown scenario number: " + number));
    }

    private static List<ProjectScenario> buildScenarios() {
        List<ProjectScenario> scenarios = new ArrayList<>();
        scenarios.add(scenario1());
        scenarios.add(scenario2());
        scenarios.add(scenario3());
        scenarios.add(scenario4());
        scenarios.add(scenario5());
        scenarios.add(scenario6());
        scenarios.add(scenario7());
        scenarios.add(scenario8());
        scenarios.add(scenario9());
        scenarios.add(scenario10());

        int[] expectedTasks = {6, 8, 10, 14, 18, 22, 28, 34, 42, 50};
        int[] expectedWorkers = {2, 2, 3, 3, 4, 5, 6, 7, 8, 10};
        for (int i = 0; i < scenarios.size(); i++) {
            ProjectScenario scenario = scenarios.get(i);
            if (scenario.getTaskCount() != expectedTasks[i])
                throw new IllegalStateException(scenario.getName() + " must contain " + expectedTasks[i] + " tasks");
            if (scenario.getWorkerCount() != expectedWorkers[i])
                throw new IllegalStateException(scenario.getName() + " must contain " + expectedWorkers[i] + " workers");
            validateScenario(scenario);
        }
        return scenarios;
    }

    // 01 - Product Demo Video Release | 6 tasks | 2 workers
    private static ProjectScenario scenario1() {
        LinkedList<Task> tasks = tasks(
                task(1, "Lock Storyboard & Shot Plan", 7),
                task(2, "Record Voiceover", 4),
                task(3, "Secure Music License", 11),
                task(4, "Film Product Footage", 10, 1),
                task(5, "Edit Main Cut", 9, 1, 2),
                task(6, "Final Review & Publish", 3, 3, 4, 5)
        );

        return scenario(
                1,
                "Product Demo Video Release",
                "A compact media-production workflow where a longer planning task unlocks the two branches that determine the publish date.",
                "Shortest-first is deliberately tempting here, but delaying the unlocker postpones parallel filming and editing work.",
                2,
                tasks
        );
    }

    // 02 - Mobile Checkout Feature Release | 8 tasks | 2 workers
    private static ProjectScenario scenario2() {
        LinkedList<Task> tasks = tasks(
                task(1, "Finalize Checkout UX Specification", 2),
                task(2, "Complete PCI Compliance Review", 6),
                task(3, "Prepare Store Release Assets", 10),
                task(4, "Implement Checkout API", 10, 1),
                task(5, "Implement Checkout UI", 4, 1),
                task(6, "Add Analytics & Error Tracking", 6, 5),
                task(7, "Run End-to-End Payment QA", 7, 4, 5, 6),
                task(8, "Release Checkout Feature", 4, 2, 3, 7)
        );

        return scenario(
                2,
                "Mobile Checkout Feature Release",
                "A two-person release with a short specification task that unlocks the engineering chain while longer compliance and release-prep work is already available.",
                "Shows why both shortest-first and longest-first can miss the task that actually controls downstream progress.",
                2,
                tasks
        );
    }

    // 03 - Executive Analytics Dashboard Rollout | 10 tasks | 3 workers
    private static ProjectScenario scenario3() {
        LinkedList<Task> tasks = tasks(
                task(1, "Define KPI Contract", 6),
                task(2, "Provision Analytics Database", 5),
                task(3, "Audit Source Feeds", 6),
                task(4, "Configure BI Workspace", 11),
                task(5, "Build Ingestion Layer", 2, 2, 3),
                task(6, "Build Semantic Model", 1, 1, 5),
                task(7, "Create Data Quality Alerts", 6, 5),
                task(8, "Build Executive Dashboard", 10, 4, 6),
                task(9, "Reconcile KPI Results", 6, 6, 7),
                task(10, "Publish Dashboard", 7, 8, 9)
        );

        return scenario(
                3,
                "Executive Analytics Dashboard Rollout",
                "A dashboard program with independent setup work feeding a central ingestion/modeling bottleneck and two final validation branches.",
                "Exposes the cost of selecting work purely by duration or queue order around synchronization points.",
                3,
                tasks
        );
    }

    // 04 - Boutique Hotel Opening | 14 tasks | 3 workers
    private static ProjectScenario scenario4() {
        LinkedList<Task> tasks = tasks(
                task(1, "Finalize Guest Experience Concept", 4),
                task(2, "Secure Occupancy & Safety Permit", 14),
                task(3, "Complete Fire-Safety Inspection Prep", 6, 2),
                task(4, "Hire Front-of-House Team", 9),
                task(5, "Contract Linen & Amenities Suppliers", 10),
                task(6, "Install Breakfast & Kitchen Equipment", 9, 2),
                task(7, "Train Hotel Operations Team", 10, 2, 4, 5),
                task(8, "Design Brand & Room Experience", 6, 1),
                task(9, "Produce Signage & Guest Materials", 9, 8),
                task(10, "Configure PMS & Booking System", 11),
                task(11, "Launch Pre-Opening Marketing", 12),
                task(12, "Complete Rooms & Lobby Fit-Out", 7, 1, 2),
                task(13, "Run Soft-Opening Rehearsal", 10, 4, 6, 7, 10, 12),
                task(14, "Final Opening Readiness Check", 2, 3, 9, 11, 13)
        );

        return scenario(
                4,
                "Boutique Hotel Opening",
                "A hotel-opening plan spanning permits, fit-out, staffing, suppliers, systems, branding, rehearsal, and a final readiness gate.",
                "Several attractive long and short jobs compete with tasks that unlock the branches needed for the final opening date.",
                3,
                tasks
        );
    }

    // 05 - Seller Marketplace Launch | 18 tasks | 4 workers
    private static ProjectScenario scenario5() {
        LinkedList<Task> tasks = tasks(
                task(1, "Finalize Seller & Product Data Model", 1),
                task(2, "Draft Seller Onboarding Content", 9),
                task(3, "Prepare Product Media Standards", 3),
                task(4, "Configure Payments & Payouts", 5),
                task(5, "Build Catalog API", 10, 1),
                task(6, "Build Search Service", 11, 1),
                task(7, "Build Inventory & Availability Service", 9, 1),
                task(8, "Design Marketplace UI", 5),
                task(9, "Build Seller Onboarding Portal", 6, 2, 8),
                task(10, "Build Buyer Storefront", 1, 5, 6, 8),
                task(11, "Integrate Checkout & Payouts", 9, 4, 10),
                task(12, "Integrate Inventory", 7, 7, 10),
                task(13, "Add Marketplace Analytics", 13, 10),
                task(14, "Load Pilot Seller Catalog", 11, 5, 9),
                task(15, "Run End-to-End Marketplace QA", 6, 11, 12, 13, 14),
                task(16, "Run Search & Performance Tests", 6, 6, 10, 14),
                task(17, "Prepare Seller Launch Campaign", 12, 2, 9),
                task(18, "Launch Marketplace", 12, 15, 16, 17)
        );

        return scenario(
                5,
                "Seller Marketplace Launch",
                "A four-person marketplace launch where data architecture unlocks catalog, search and inventory streams while content, payouts and design compete for attention.",
                "Designed to punish FIFO and static choices that fail to create enough useful parallel work at the right time.",
                4,
                tasks
        );
    }

    // 06 - Fintech Payments Platform Release | 22 tasks | 5 workers
    private static ProjectScenario scenario6() {
        LinkedList<Task> tasks = tasks(
                task(1, "Freeze Release Scope", 2),
                task(2, "Provision Staging Environment", 1),
                task(3, "Prepare Database Migration Plan", 2),
                task(4, "Draft Merchant Release Notes", 7),
                task(5, "Update Security Threat Model", 5),
                task(6, "Prepare Support Escalation Guide", 14),
                task(7, "Implement Authentication Changes", 4, 1, 5),
                task(8, "Implement Payment Routing Changes", 6, 1),
                task(9, "Implement Reconciliation Reports", 7, 1),
                task(10, "Build Database Migration Script", 2, 3),
                task(11, "Update Infrastructure Controls", 10, 2, 5),
                task(12, "Integrate Merchant Dashboard", 2, 7, 8, 9),
                task(13, "Run Backend Integration Tests", 4, 7, 8, 9, 10),
                task(14, "Run Security & PCI Tests", 5, 7, 11),
                task(15, "Run Migration Rehearsal", 2, 10, 11),
                task(16, "Run Regression Test Suite", 5, 12, 13),
                task(17, "Run Load & Failover Tests", 5, 11, 13),
                task(18, "Finalize Merchant Release Notes", 1, 4, 16),
                task(19, "Train Support Team", 7, 6, 16),
                task(20, "Approve Release Candidate", 5, 14, 15, 16, 17),
                task(21, "Deploy Production Release", 4, 20),
                task(22, "Post-Deployment Verification", 2, 18, 19, 21)
        );

        return scenario(
                6,
                "Fintech Payments Platform Release",
                "A payments-platform release combining authentication, routing, migration, infrastructure, security, QA, merchant communication and deployment.",
                "Creates competing deep branches where dynamic worker state matters more than a single fixed priority rule.",
                5,
                tasks
        );
    }

    // 07 - Music Festival Production | 28 tasks | 6 workers
    private static ProjectScenario scenario7() {
        LinkedList<Task> tasks = tasks(
                task(1, "Lock Festival Theme & Dates", 4),
                task(2, "Secure Venue & Site", 9),
                task(3, "Confirm Production Budget", 11),
                task(4, "Build Sponsor Prospect List", 8),
                task(5, "Set Up Ticketing Platform", 5),
                task(6, "Draft Marketing Plan", 3),
                task(7, "Build Artist Prospect List", 7),
                task(8, "Design Stage Program", 8, 1),
                task(9, "Confirm Headliner", 1, 1, 7),
                task(10, "Book Supporting Artists", 4, 8, 9),
                task(11, "Design Festival Brand", 14, 1, 6),
                task(12, "Launch Festival Website", 6, 5, 11),
                task(13, "Open Ticket Sales", 4, 2, 12),
                task(14, "Contract Production Vendors", 2, 2, 3),
                task(15, "Finalize Food & Beverage Vendors", 10, 2, 3),
                task(16, "Close Sponsorship Deals", 3, 4, 11),
                task(17, "Publish Artist Lineup", 3, 10, 12),
                task(18, "Build Detailed Run of Show", 8, 10),
                task(19, "Produce Stage Visuals", 4, 11, 18),
                task(20, "Print Signage & Credentials", 10, 11, 13),
                task(21, "Configure Audio & Lighting Plan", 7, 14, 18),
                task(22, "Confirm Staff & Security", 5, 3, 18),
                task(23, "Send Attendee Logistics", 3, 13, 15, 18),
                task(24, "Run Artist Technical Checks", 6, 10, 21),
                task(25, "Build Festival Site", 14, 14, 19, 20, 21, 22),
                task(26, "Set Up Entry & Vendor Areas", 10, 13, 15, 20, 25),
                task(27, "Run Full Production Rehearsal", 9, 24, 25),
                task(28, "Open Festival Gates", 7, 16, 17, 23, 26, 27)
        );

        return scenario(
                7,
                "Music Festival Production",
                "A full festival-production workflow spanning programming, artists, ticketing, sponsors, site build, AV, food vendors, staffing and rehearsal.",
                "Many ready tasks look interchangeable, but poor early choices create avoidable idle periods near the site-build and rehearsal gates.",
                6,
                tasks
        );
    }

    // 08 - Personalized Search Launch | 34 tasks | 7 workers
    private static ProjectScenario scenario8() {
        LinkedList<Task> tasks = tasks(
                task(1, "Define Search Objective", 5),
                task(2, "Define Relevance Metrics", 8),
                task(3, "Audit Query & Clickstream Data", 1),
                task(4, "Provision ML Training Environment", 9),
                task(5, "Define Privacy & Safety Requirements", 5),
                task(6, "Design Online Serving Architecture", 11),
                task(7, "Set Up Experiment Tracking", 13),
                task(8, "Create Relevance Labeling Guide", 7),
                task(9, "Extract Training Dataset", 13, 3),
                task(10, "Label Evaluation Sample", 8, 8, 9),
                task(11, "Clean Training Dataset", 3, 9),
                task(12, "Build Feature Pipeline", 7, 11),
                task(13, "Build Baseline Ranker", 3, 1, 2, 10, 12),
                task(14, "Train Candidate Ranker A", 3, 7, 12),
                task(15, "Train Candidate Ranker B", 2, 7, 12),
                task(16, "Run Offline Relevance Evaluation", 10, 2, 13, 14, 15),
                task(17, "Run Safety & Bias Evaluation", 2, 5, 16),
                task(18, "Review Feature Privacy", 8, 5, 12),
                task(19, "Tune Ranking Model", 10, 16),
                task(20, "Select Production Ranker", 5, 17, 18, 19),
                task(21, "Implement Feature Store Sync", 2, 6, 12),
                task(22, "Build Online Ranking API", 6, 20, 21),
                task(23, "Build Batch Backfill Job", 3, 20, 21),
                task(24, "Build Search Quality Monitoring", 5, 2, 20),
                task(25, "Build Data Drift Monitoring", 7, 2, 12),
                task(26, "Containerize Ranking Service", 7, 22),
                task(27, "Run Security Review", 12, 5, 22, 26),
                task(28, "Load Test Ranking Service", 6, 22, 26),
                task(29, "Deploy Ranker to Staging", 11, 26, 27),
                task(30, "Run Shadow Query Test", 1, 24, 25, 29),
                task(31, "Analyze Shadow Results", 1, 30),
                task(32, "Run Product Acceptance Test", 2, 23, 29, 31),
                task(33, "Deploy Production Ranker", 8, 28, 32),
                task(34, "Verify Post-Launch Metrics", 1, 24, 25, 33)
        );

        return scenario(
                8,
                "Personalized Search Launch",
                "An end-to-end search-ranking launch covering data, relevance labeling, model training, privacy, serving, monitoring, shadow traffic and production rollout.",
                "Large gaps versus simple duration-based rules demonstrate why critical downstream structure must be considered, while the dynamic scheduler still uses live worker state.",
                7,
                tasks
        );
    }

    // 09 - Enterprise Data Center Exit | 42 tasks | 8 workers
    private static ProjectScenario scenario9() {
        LinkedList<Task> tasks = tasks(
                task(1, "Inventory Applications", 10),
                task(2, "Inventory Databases", 4),
                task(3, "Map Network Dependencies", 5),
                task(4, "Define Security Baseline", 11),
                task(5, "Define Capacity & SLO Targets", 10),
                task(6, "Establish Cloud Landing Zone", 11),
                task(7, "Set Up IAM Federation", 10),
                task(8, "Set Up Central Logging", 10),
                task(9, "Set Up Cost Controls", 6),
                task(10, "Build Migration Runbook Template", 13),
                task(11, "Classify Application Waves", 5, 1, 3),
                task(12, "Classify Database Waves", 8, 2),
                task(13, "Build Shared Cloud Network", 2, 3, 6),
                task(14, "Configure Security Policies", 1, 4, 6),
                task(15, "Configure Observability Stack", 2, 5, 8),
                task(16, "Configure CI/CD Foundations", 8, 6, 7),
                task(17, "Configure Secrets Management", 6, 4, 7),
                task(18, "Containerize Pilot Application", 3, 11, 16),
                task(19, "Configure Pilot DB Replication", 10, 12, 13),
                task(20, "Run Pilot Network Cutover", 2, 13),
                task(21, "Run Pilot Security Validation", 3, 14, 17, 18),
                task(22, "Run Pilot Performance Test", 6, 5, 15, 18, 19),
                task(23, "Approve Migration Pattern", 7, 20, 21, 22),
                task(24, "Migrate Wave 1 Applications", 4, 11, 23),
                task(25, "Migrate Wave 1 Databases", 14, 12, 19, 23),
                task(26, "Validate Migration Wave 1", 10, 15, 24, 25),
                task(27, "Migrate Wave 2 Applications", 10, 11, 26),
                task(28, "Migrate Wave 2 Databases", 6, 12, 26),
                task(29, "Validate Migration Wave 2", 1, 15, 27, 28),
                task(30, "Migrate Wave 3 Applications", 3, 11, 29),
                task(31, "Migrate Wave 3 Databases", 5, 12, 29),
                task(32, "Validate Migration Wave 3", 2, 15, 30, 31),
                task(33, "Update DNS & Routing", 6, 20, 32),
                task(34, "Enforce Production Security Policies", 1, 14, 32),
                task(35, "Enable Production Autoscaling", 5, 5, 32),
                task(36, "Finalize Dashboards & Alerts", 2, 15, 32),
                task(37, "Complete Cloud Cost Review", 6, 9, 32),
                task(38, "Run Disaster Recovery Test", 9, 13, 25, 31),
                task(39, "Complete Operational Handover", 1, 10, 36, 38),
                task(40, "Run Final Change Review", 1, 33, 34, 35, 37, 39),
                task(41, "Decommission Legacy Infrastructure", 10, 40),
                task(42, "Close Data Center Exit Program", 2, 41)
        );

        return scenario(
                9,
                "Enterprise Data Center Exit",
                "A staged infrastructure-exit program with cloud foundations, a pilot, three migration waves, security, observability, disaster recovery and shutdown of legacy systems.",
                "Simple shortest/longest/FIFO rules lose time across migration waves; the adaptive policy coordinates the shared foundations and late convergence gates more effectively.",
                8,
                tasks
        );
    }

    // 10 - Global Subscription Platform Launch | 50 tasks | 10 workers
    private static ProjectScenario scenario10() {
        LinkedList<Task> tasks = tasks(
                task(1, "Define Subscription Product Scope", 3),
                task(2, "Define Growth & Reliability Metrics", 14),
                task(3, "Run Subscriber Research", 2),
                task(4, "Run Architecture Spike", 1),
                task(5, "Define Security & Privacy Requirements", 8),
                task(6, "Provision Development Environment", 10),
                task(7, "Set Up CI/CD", 12),
                task(8, "Define Analytics Taxonomy", 8),
                task(9, "Define Brand & Visual Direction", 6),
                task(10, "Plan Customer Support", 14),
                task(11, "Review Subscription Legal Terms", 9),
                task(12, "Plan Launch Marketing", 7),
                task(13, "Design Subscription UX Architecture", 9, 1, 3),
                task(14, "Design Service API Contracts", 13, 1, 4),
                task(15, "Design Subscription Data Model", 6, 1, 4),
                task(16, "Build Design System Components", 10, 9, 13),
                task(17, "Create High-Fidelity Subscriber Screens", 1, 13, 16),
                task(18, "Create Security Threat Model", 2, 5, 14, 15),
                task(19, "Create Analytics Implementation Plan", 7, 2, 8, 14),
                task(20, "Create Test Strategy", 4, 1, 5),
                task(21, "Implement Identity Service", 8, 14, 18),
                task(22, "Implement Subscription Service", 13, 14, 15),
                task(23, "Implement Billing Ledger", 5, 14, 15),
                task(24, "Implement Notification Service", 4, 14),
                task(25, "Implement Web App Shell", 12, 16, 17),
                task(26, "Implement Signup & Onboarding", 13, 17, 21, 25),
                task(27, "Implement Account Management", 6, 17, 21, 22, 25),
                task(28, "Implement Analytics Events", 7, 19, 21, 22),
                task(29, "Implement Search & Discovery", 13, 14, 25),
                task(30, "Implement Billing & Entitlements", 3, 14, 22, 23),
                task(31, "Complete Accessibility Pass", 7, 17, 25),
                task(32, "Build Integration Test Environment", 11, 21, 22, 23, 24),
                task(33, "Run Backend Integration Tests", 2, 21, 22, 23, 28, 30, 32),
                task(34, "Run Frontend Integration Tests", 5, 25, 26, 27, 29, 31, 32),
                task(35, "Run Security Testing", 10, 18, 21, 22, 30, 32),
                task(36, "Build Performance Test Harness", 10, 2, 22, 32),
                task(37, "Run Load & Performance Tests", 10, 29, 30, 33, 36),
                task(38, "Prepare Production Seed Data", 1, 23),
                task(39, "Provision Production Infrastructure", 3, 4, 5, 7),
                task(40, "Configure Monitoring & Alerting", 5, 2, 39),
                task(41, "Run End-to-End Regression", 10, 33, 34, 35, 37),
                task(42, "Run Product Acceptance Review", 6, 17, 41),
                task(43, "Finalize Help Center", 9, 10, 25, 26),
                task(44, "Train Customer Support", 2, 10, 43),
                task(45, "Finalize Legal & Privacy Copy", 10, 11, 18),
                task(46, "Prepare Launch Campaign Assets", 9, 12, 17),
                task(47, "Approve Release Candidate", 6, 40, 41, 42, 45),
                task(48, "Deploy Production Release", 1, 38, 39, 47),
                task(49, "Launch Campaign & Customer Comms", 2, 44, 46, 48),
                task(50, "Run Post-Launch Verification", 8, 27, 40, 48, 49)
        );

        return scenario(
                10,
                "Global Subscription Platform Launch",
                "A fifty-task cross-functional launch spanning discovery, UX, backend, frontend, billing, infrastructure, security, QA, legal, support, marketing and production rollout.",
                "The largest scenario includes enough competing work that duration-only, FIFO and even static graph scoring can all make measurably worse assignments.",
                10,
                tasks
        );
    }
    private static ProjectScenario scenario(int number, String name, String description, String schedulingFocus, int workerCount, LinkedList<Task> tasks) {
        return new ProjectScenario(number, name, description, schedulingFocus, tasks, workers(workerCount));
    }

    private static Task task(int id, String name, int duration, int... dependencies) {
        LinkedList<Integer> dependsOn = new LinkedList<>();
        for (int dependency : dependencies) dependsOn.add(dependency);
        return new Task(name, id, duration, dependsOn);
    }

    private static LinkedList<Task> tasks(Task... tasks) {
        LinkedList<Task> result = new LinkedList<>();
        for (Task task : tasks) result.add(task);
        return result;
    }

    private static LinkedList<String> workers(int count) {
        LinkedList<String> workers = new LinkedList<>();
        for (int i = 0; i < count; i++) workers.add("Worker " + i);
        return workers;
    }

    private static void validateScenario(ProjectScenario scenario) {
        LinkedList<Task> tasks = scenario.getTasks();
        Map<Integer, Task> byId = new HashMap<>();
        for (Task task : tasks) {
            if (task.getName() == null || task.getName().isBlank())
                throw new IllegalStateException(scenario.getName() + " contains a task without a name");
            if (task.getDuration() <= 0)
                throw new IllegalStateException(scenario.getName() + " contains a non-positive duration");
            if (byId.put(task.getId(), task) != null)
                throw new IllegalStateException(scenario.getName() + " contains duplicate task id " + task.getId());
        }
        Map<Integer, Integer> indegree = new HashMap<>();
        Map<Integer, List<Integer>> children = new HashMap<>();
        for (Task task : tasks) {
            indegree.put(task.getId(), 0);
            children.put(task.getId(), new ArrayList<>());
        }
        for (Task task : tasks) {
            Set<Integer> seen = new HashSet<>();
            for (Integer parentId : task.getDependsOnIDS()) {
                if (!byId.containsKey(parentId))
                    throw new IllegalStateException(scenario.getName() + ": task " + task.getId() + " depends on missing task " + parentId);
                if (!seen.add(parentId))
                    throw new IllegalStateException(scenario.getName() + ": duplicate dependency " + parentId + " for task " + task.getId());
                indegree.put(task.getId(), indegree.get(task.getId()) + 1);
                children.get(parentId).add(task.getId());
            }
        }
        Queue<Integer> queue = new ArrayDeque<>();
        for (Map.Entry<Integer, Integer> entry : indegree.entrySet()) if (entry.getValue() == 0) queue.add(entry.getKey());
        int visited = 0;
        while (!queue.isEmpty()) {
            int current = queue.poll();
            visited++;
            for (Integer child : children.get(current)) {
                int next = indegree.get(child) - 1;
                indegree.put(child, next);
                if (next == 0) queue.add(child);
            }
        }
        if (visited != tasks.size()) throw new IllegalStateException(scenario.getName() + " contains a dependency cycle");
    }
}
