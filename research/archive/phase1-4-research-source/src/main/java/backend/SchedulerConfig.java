package backend;

import java.util.Objects;

/**
 * Immutable set of tunable scheduler parameters and feature flags.
 *
 * {@link #DEFAULT} reproduces the original scheduler exactly (verified by
 * GoldenTraceTest). Every other value is opt-in and exists so that weights and
 * individual algorithmic changes can be evaluated in isolation.
 */
public final class SchedulerConfig {

    /** When the legacy score-gap test lets the lookahead run. */
    public enum GapCondition {
        /** Original rule: lookahead only when the top two scores differ by more than tau. */
        GAP_ABOVE_THRESHOLD,
        /** Inverted rule: lookahead only when the top two scores are within tau (near ties). */
        GAP_AT_MOST_THRESHOLD,
        /** Ignore the score gap. */
        ANY_GAP
    }

    /** Which lookahead runs when the activation conditions hold. */
    public enum LookaheadMode {
        /** Original: idle time inside each candidate's own duration window (DynamicScoreLogic.makeLookAhead). */
        LEGACY_IDLE_WINDOW,
        /**
         * Rollout: each candidate is followed by a deterministic static-priority
         * continuation for the same number of completion events and judged by the
         * resulting (estimated or exact) final makespan.
         */
        ROLLOUT,
        /** No lookahead: the best blended score is chosen. */
        NONE
    }

    /** Candidate pool size meaning "every ready task". */
    public static final int ALL_READY_TASKS = Integer.MAX_VALUE;

    /** Rollout depth meaning "simulate until every task is finished". */
    public static final int FULL_ROLLOUT = Integer.MAX_VALUE;

    /** How task completions that occur at the same simulated time are processed. */
    public enum CompletionMode {
        /** Original Controller behaviour: one completion, then an immediate decision for that worker. */
        SEQUENTIAL,
        /** All completions at the same time are applied before any new task is assigned. */
        BATCHED
    }

    public static final SchedulerConfig DEFAULT = builder().build();

    /**
     * Phase 1 recommended rollout (B0): contested-only rollout, 5 candidates, depth 8, original weights.
     * Opt-in; DEFAULT is unchanged.
     */
    public static final SchedulerConfig PHASE1_ROLLOUT = builder()
            .lookaheadMode(LookaheadMode.ROLLOUT)
            .rolloutDepth(8)
            .lookaheadCandidates(5)
            .candidatePoolSize(5)
            .legacyLoadGate(false)
            .gapCondition(GapCondition.ANY_GAP)
            .contestedDecisionsOnly(true)
            .completionMode(CompletionMode.BATCHED)
            .reachNormalizedByMaximum(true)
            .deterministicTieBreak(true)
            .build();

    /**
     * Phase 2 Track A winner (A*, see PHASE2_REPORT.md): no lookahead, K = 2, learned weights.
     * Opt-in; DEFAULT is unchanged.
     */
    public static final SchedulerConfig FAST_OPTIMIZED = builder()
            .staticWeights(0.9419505011700513, 0.014377117009955828, 0.04367238181999288)
            .dynamicWeights(0.018118364036328133, 0.14181848692297505, 0.3210363092104712,
                    0.09321126939904587, 0.4258155704311797)
            .staticShare(0.9893181762524133)
            .lookaheadMode(LookaheadMode.NONE)
            .candidatePoolSize(2)
            .completionMode(CompletionMode.BATCHED)
            .reachNormalizedByMaximum(true)
            .deterministicTieBreak(true)
            .build();

    /**
     * Phase 2 Track B winner (B*, see PHASE2_REPORT.md): PHASE1_ROLLOUT with learned weights.
     * Opt-in; DEFAULT is unchanged.
     */
    public static final SchedulerConfig QUALITY_OPTIMIZED = PHASE1_ROLLOUT.toBuilder()
            .staticWeights(0.9999999970515216, 2.6291118137197E-9, 3.193664533303767E-10)
            .dynamicWeights(1.1784398533034709E-10, 2.403394208989869E-9, 0.12653823877995585,
                    0.873461757138842, 1.5599640085701002E-9)
            .staticShare(0.9989270574400966)
            .build();

    // static score weights (TimeConstraints.scoreSet)
    private final double criticalPathWeight;
    private final double blockingPowerWeight;
    private final double reachableDurationWeight;

    // dynamic score weights (DynamicScoreLogic.makeDynamicScore)
    private final double openToBlockWeight;
    private final double openChildrenWeight;
    private final double childImportanceWeight;
    private final double durationToReadyWeight;
    private final double childrenToReadyQueueWeight;

    // blending and lookahead (TaskEngine.getNextTask)
    private final double staticShare;
    private final double lookaheadWeight;
    private final double lookaheadGapThreshold;
    private final GapCondition gapCondition;

    // lookahead variant (Change 6)
    private final LookaheadMode lookaheadMode;
    private final int lookaheadCandidates;
    private final int rolloutDepth;
    private final boolean rolloutIncludesStaticBest;

    // lookahead activation and scoring controls (Change 7)
    private final boolean legacyLoadGate;
    private final boolean contestedDecisionsOnly;
    private final boolean boundedLookaheadScore;

    // legacy lookahead simulation (Change 8)
    private final boolean simulationAssignsIdleWorkers;

    // Controller event processing (Change 9)
    private final CompletionMode completionMode;

    // dynamic score workload pressure (Change 10)
    private final boolean workloadUsesAvailableWorkers;

    // static score normalization (Change 11)
    private final boolean reachNormalizedByMaximum;

    // ready queue ordering (Change 12)
    private final boolean deterministicTieBreak;

    // candidate pool (Change 13)
    private final int candidatePoolSize;

    private SchedulerConfig(Builder b) {
        this.criticalPathWeight = b.criticalPathWeight;
        this.blockingPowerWeight = b.blockingPowerWeight;
        this.reachableDurationWeight = b.reachableDurationWeight;
        this.openToBlockWeight = b.openToBlockWeight;
        this.openChildrenWeight = b.openChildrenWeight;
        this.childImportanceWeight = b.childImportanceWeight;
        this.durationToReadyWeight = b.durationToReadyWeight;
        this.childrenToReadyQueueWeight = b.childrenToReadyQueueWeight;
        this.staticShare = b.staticShare;
        this.lookaheadWeight = b.lookaheadWeight;
        this.lookaheadGapThreshold = b.lookaheadGapThreshold;
        this.gapCondition = Objects.requireNonNull(b.gapCondition, "gapCondition");
        this.lookaheadMode = Objects.requireNonNull(b.lookaheadMode, "lookaheadMode");
        this.lookaheadCandidates = b.lookaheadCandidates;
        this.rolloutDepth = b.rolloutDepth;
        this.rolloutIncludesStaticBest = b.rolloutIncludesStaticBest;
        this.legacyLoadGate = b.legacyLoadGate;
        this.contestedDecisionsOnly = b.contestedDecisionsOnly;
        this.boundedLookaheadScore = b.boundedLookaheadScore;
        this.simulationAssignsIdleWorkers = b.simulationAssignsIdleWorkers;
        this.completionMode = Objects.requireNonNull(b.completionMode, "completionMode");
        this.workloadUsesAvailableWorkers = b.workloadUsesAvailableWorkers;
        this.reachNormalizedByMaximum = b.reachNormalizedByMaximum;
        this.deterministicTieBreak = b.deterministicTieBreak;
        this.candidatePoolSize = b.candidatePoolSize;

        if (candidatePoolSize < 1) {
            throw new IllegalArgumentException("candidatePoolSize must be at least 1");
        }
        if (staticShare < 0 || staticShare > 1) {
            throw new IllegalArgumentException("staticShare must be in [0, 1]");
        }
        if (lookaheadCandidates < 2) {
            throw new IllegalArgumentException("lookaheadCandidates must be at least 2");
        }
        if (rolloutDepth < 0) {
            throw new IllegalArgumentException("rolloutDepth must be non-negative");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.criticalPathWeight = criticalPathWeight;
        b.blockingPowerWeight = blockingPowerWeight;
        b.reachableDurationWeight = reachableDurationWeight;
        b.openToBlockWeight = openToBlockWeight;
        b.openChildrenWeight = openChildrenWeight;
        b.childImportanceWeight = childImportanceWeight;
        b.durationToReadyWeight = durationToReadyWeight;
        b.childrenToReadyQueueWeight = childrenToReadyQueueWeight;
        b.staticShare = staticShare;
        b.lookaheadWeight = lookaheadWeight;
        b.lookaheadGapThreshold = lookaheadGapThreshold;
        b.gapCondition = gapCondition;
        b.lookaheadMode = lookaheadMode;
        b.lookaheadCandidates = lookaheadCandidates;
        b.rolloutDepth = rolloutDepth;
        b.rolloutIncludesStaticBest = rolloutIncludesStaticBest;
        b.legacyLoadGate = legacyLoadGate;
        b.contestedDecisionsOnly = contestedDecisionsOnly;
        b.boundedLookaheadScore = boundedLookaheadScore;
        b.simulationAssignsIdleWorkers = simulationAssignsIdleWorkers;
        b.completionMode = completionMode;
        b.workloadUsesAvailableWorkers = workloadUsesAvailableWorkers;
        b.reachNormalizedByMaximum = reachNormalizedByMaximum;
        b.deterministicTieBreak = deterministicTieBreak;
        b.candidatePoolSize = candidatePoolSize;
        return b;
    }

    public double getCriticalPathWeight() { return criticalPathWeight; }
    public double getBlockingPowerWeight() { return blockingPowerWeight; }
    public double getReachableDurationWeight() { return reachableDurationWeight; }
    public double getOpenToBlockWeight() { return openToBlockWeight; }
    public double getOpenChildrenWeight() { return openChildrenWeight; }
    public double getChildImportanceWeight() { return childImportanceWeight; }
    public double getDurationToReadyWeight() { return durationToReadyWeight; }
    public double getChildrenToReadyQueueWeight() { return childrenToReadyQueueWeight; }
    public double getStaticShare() { return staticShare; }
    public double getLookaheadWeight() { return lookaheadWeight; }
    public double getLookaheadGapThreshold() { return lookaheadGapThreshold; }
    public GapCondition getGapCondition() { return gapCondition; }
    public LookaheadMode getLookaheadMode() { return lookaheadMode; }
    public int getLookaheadCandidates() { return lookaheadCandidates; }
    public int getRolloutDepth() { return rolloutDepth; }
    public boolean isRolloutIncludesStaticBest() { return rolloutIncludesStaticBest; }
    public boolean isLegacyLoadGate() { return legacyLoadGate; }
    public boolean isContestedDecisionsOnly() { return contestedDecisionsOnly; }
    public boolean isBoundedLookaheadScore() { return boundedLookaheadScore; }
    public boolean isSimulationAssignsIdleWorkers() { return simulationAssignsIdleWorkers; }
    public CompletionMode getCompletionMode() { return completionMode; }
    public boolean isWorkloadUsesAvailableWorkers() { return workloadUsesAvailableWorkers; }
    public boolean isReachNormalizedByMaximum() { return reachNormalizedByMaximum; }
    public boolean isDeterministicTieBreak() { return deterministicTieBreak; }
    public int getCandidatePoolSize() { return candidatePoolSize; }

    @Override
    public String toString() {
        return "SchedulerConfig{"
                + "static=[" + criticalPathWeight + ", " + blockingPowerWeight + ", " + reachableDurationWeight + "]"
                + ", dynamic=[" + openToBlockWeight + ", " + openChildrenWeight + ", " + childImportanceWeight
                + ", " + durationToReadyWeight + ", " + childrenToReadyQueueWeight + "]"
                + ", staticShare=" + staticShare
                + ", lookaheadWeight=" + lookaheadWeight
                + ", gapThreshold=" + lookaheadGapThreshold
                + ", gapCondition=" + gapCondition
                + ", lookaheadMode=" + lookaheadMode
                + ", lookaheadCandidates=" + lookaheadCandidates
                + ", rolloutDepth=" + (rolloutDepth == FULL_ROLLOUT ? "full" : String.valueOf(rolloutDepth))
                + ", rolloutIncludesStaticBest=" + rolloutIncludesStaticBest
                + ", legacyLoadGate=" + legacyLoadGate
                + ", contestedDecisionsOnly=" + contestedDecisionsOnly
                + ", boundedLookaheadScore=" + boundedLookaheadScore
                + ", simulationAssignsIdleWorkers=" + simulationAssignsIdleWorkers
                + ", completionMode=" + completionMode
                + ", workloadUsesAvailableWorkers=" + workloadUsesAvailableWorkers
                + ", reachNormalizedByMaximum=" + reachNormalizedByMaximum
                + ", deterministicTieBreak=" + deterministicTieBreak
                + ", candidatePoolSize=" + (candidatePoolSize == ALL_READY_TASKS ? "all" : String.valueOf(candidatePoolSize))
                + '}';
    }

    public static final class Builder {
        private double criticalPathWeight = 0.5;
        private double blockingPowerWeight = 0.25;
        private double reachableDurationWeight = 0.25;
        private double openToBlockWeight = 0.2;
        private double openChildrenWeight = 0.1;
        private double childImportanceWeight = 0.15;
        private double durationToReadyWeight = 0.3;
        private double childrenToReadyQueueWeight = 0.25;
        private double staticShare = 0.5;
        private double lookaheadWeight = 1.0;
        private double lookaheadGapThreshold = 0.1;
        private GapCondition gapCondition = GapCondition.GAP_ABOVE_THRESHOLD;
        private LookaheadMode lookaheadMode = LookaheadMode.LEGACY_IDLE_WINDOW;
        private int lookaheadCandidates = 2;
        private int rolloutDepth = FULL_ROLLOUT;
        private boolean rolloutIncludesStaticBest = true;
        private boolean legacyLoadGate = true;
        private boolean contestedDecisionsOnly = false;
        private boolean boundedLookaheadScore = false;
        private boolean simulationAssignsIdleWorkers = false;
        private CompletionMode completionMode = CompletionMode.SEQUENTIAL;
        private boolean workloadUsesAvailableWorkers = false;
        private boolean reachNormalizedByMaximum = false;
        private boolean deterministicTieBreak = false;
        private int candidatePoolSize = 3;

        private Builder() {
        }

        /** K: number of best static-score ready tasks the dynamic score ranks (original 3; ALL_READY_TASKS for all). */
        public Builder candidatePoolSize(int k) {
            this.candidatePoolSize = k;
            return this;
        }

        /** Ready queue orders equal static scores by task id (original: heap insertion history). */
        public Builder deterministicTieBreak(boolean enabled) {
            this.deterministicTieBreak = enabled;
            return this;
        }

        /** Static reach term divides by the largest reachable duration instead of the total work. */
        public Builder reachNormalizedByMaximum(boolean enabled) {
            this.reachNormalizedByMaximum = enabled;
            return this;
        }

        /**
         * Workload pressure alpha = min(1, ready / W) uses W = workers able to start
         * a task now (at least 1) instead of the total worker count.
         */
        public Builder workloadUsesAvailableWorkers(boolean enabled) {
            this.workloadUsesAvailableWorkers = enabled;
            return this;
        }

        /** How Controller.workerFinishOnTime processes completions at the same time (original: SEQUENTIAL). */
        public Builder completionMode(CompletionMode mode) {
            this.completionMode = mode;
            return this;
        }

        /** Legacy lookahead simulation gives idle workers ready tasks at the decision time (original: after the next completion). */
        public Builder simulationAssignsIdleWorkers(boolean enabled) {
            this.simulationAssignsIdleWorkers = enabled;
            return this;
        }

        /** Original gate: lookahead only if the ready tasks left after the candidate pool are at most the worker count. */
        public Builder legacyLoadGate(boolean enabled) {
            this.legacyLoadGate = enabled;
            return this;
        }

        /**
         * Lookahead only when the decision is contested: more ready tasks than
         * workers free right now (the deciding worker, idle workers and workers
         * finishing at this same instant). Otherwise every ready task starts now
         * anyway and the choice cannot change the schedule's task set.
         */
        public Builder contestedDecisionsOnly(boolean enabled) {
            this.contestedDecisionsOnly = enabled;
            return this;
        }

        /** Clamp the legacy idle-window score to [0, 1] before applying mu. */
        public Builder boundedLookaheadScore(boolean enabled) {
            this.boundedLookaheadScore = enabled;
            return this;
        }

        public Builder lookaheadMode(LookaheadMode mode) {
            this.lookaheadMode = mode;
            return this;
        }

        /** Number of best blended-score candidates the lookahead compares (original: 2). */
        public Builder lookaheadCandidates(int count) {
            this.lookaheadCandidates = count;
            return this;
        }

        /** Completion instants simulated after the decision; FULL_ROLLOUT runs to the end. */
        public Builder rolloutDepth(int events) {
            this.rolloutDepth = events;
            return this;
        }

        /** Also roll out the highest-static-score task when it is not among the candidates. */
        public Builder rolloutIncludesStaticBest(boolean include) {
            this.rolloutIncludesStaticBest = include;
            return this;
        }

        public Builder staticWeights(double criticalPath, double blockingPower, double reachableDuration) {
            this.criticalPathWeight = criticalPath;
            this.blockingPowerWeight = blockingPower;
            this.reachableDurationWeight = reachableDuration;
            return this;
        }

        public Builder dynamicWeights(double openToBlock, double openChildren, double childImportance,
                                      double durationToReady, double childrenToReadyQueue) {
            this.openToBlockWeight = openToBlock;
            this.openChildrenWeight = openChildren;
            this.childImportanceWeight = childImportance;
            this.durationToReadyWeight = durationToReady;
            this.childrenToReadyQueueWeight = childrenToReadyQueue;
            return this;
        }

        /** lambda: final score = lambda * static + (1 - lambda) * dynamic. */
        public Builder staticShare(double lambda) {
            this.staticShare = lambda;
            return this;
        }

        /** mu: multiplier of the lookahead term added to the blended score. */
        public Builder lookaheadWeight(double mu) {
            this.lookaheadWeight = mu;
            return this;
        }

        /** tau: score-gap threshold used by the gap condition. */
        public Builder lookaheadGapThreshold(double tau) {
            this.lookaheadGapThreshold = tau;
            return this;
        }

        public Builder gapCondition(GapCondition condition) {
            this.gapCondition = condition;
            return this;
        }

        public SchedulerConfig build() {
            return new SchedulerConfig(this);
        }
    }
}
