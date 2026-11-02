# Adaptive Task Scheduler: Consolidated Research Report

This document consolidates the verified findings of the research on the Adaptive Task Scheduler (Phases 1–3, the incomplete Phase 4, and the final integration). The original phase reports are kept unchanged as the primary records: `research/phase-reports/PHASE1_REPORT.md`, `PHASE2_REPORT.md` and `PHASE3_REPORT.md`. Every number below either comes from those reports, which cite their raw outputs, or from the final-integration run in `research/final-integration/results/`. Raw experimental outputs of all phases were kept in `research/raw-results/` (about 100 MB, not included in the Git repository), and the research source code in `research/archive/` (see §9).

## 1. Summary

- **The shipped scheduler is the original algorithm with one change: the original idle-window lookahead is replaced by the validated rollout lookahead.** All static and dynamic scoring formulas, their weights, the blending, the top-3 candidate selection, worker handling, dependency handling and the runtime events are byte-for-byte the original code (commit `be6d109`). The change is confined to `TaskEngine.getNextTask` (the lookahead block) plus helpers in `TaskEngine` and `DynamicScoreLogic`.
- **Effect of that single change** (fresh, independent seeds; paired with the original on identical instances):

  | Dataset | Instances | GM makespan new/original [95% CI] | Mean improvement | Wins / ties / losses | Worst instance |
  |---|---|---|---|---|---|
  | MAIN (S/M/L, 10–600 tasks) | 3,600 | **0.9893** [0.9886, 0.9900] | **+1.04%** | 1,526 / 2,030 / 44 | +2.5% |
  | CONFIRM (independent replicate) | 2,400 | **0.9891** [0.9882, 0.9900] | **+1.06%** | 1,056 / 1,315 / 29 | +5.9% |
  | XL (601–1,500 tasks) | 200 | 0.9958 [0.9945, 0.9969] | +0.42% | 84 / 113 / 3 | +0.4% |
  | EXACT (2–10 tasks, solved to optimality) | 800 | 0.9910 [0.9885, 0.9931] | +0.85% | 98 / 702 / 0 | ±0 |
  | Curated scenarios (`ScenarioLibrary`) | 10 | sum 563 → 561 | — | 1 / 9 / 0 | ±0 |

  All differences except the 10 curated scenarios have Wilcoxon p < 1e-4. On the exact set the gap to the optimum falls from 0.98% to 0.06%, and the share of optimal schedules rises from 86.8% to 97.8%. The cost is about 1.4× the original's runtime on small graphs and about 2.8× on graphs of 31–1,500 tasks (§6.5).
- **Not shipped:** the learned weights (`QUALITY_OPTIMIZED`, `FAST_OPTIMIZED`), critical-path-only weighting, batched completion handling, reach normalization, id tie-breaking, the configuration system, and every other Phase 1–3 change (§5).

## 2. The original algorithm (verified from commit `be6d109`)

The baseline is the repository's only commit, `be6d109`. The following was read from its source and confirmed by running its 130 tests (130 pass).

**Static score** (`TimeConstraints.scoreSet`), computed once per graph:

score = 0.5 · CP / max CP + 0.25 · blocking power / max blocking power + 0.25 · reachable duration / total work

- CP is the longest path to a sink, including the task.
- Blocking power is the sum over the children of 1 / (the child's number of parents).
- Reachable duration is the total duration of all descendants plus the task itself.

**Dynamic score** (`DynamicScoreLogic.makeDynamicScore`), recomputed at each decision:

dynamic = 0.2 · openToBlock + 0.1 · openChildren + 0.15 · childImportance + 0.3 · durationToReady + 0.25 · childrenToReadyQueue

**Selection** (`TaskEngine.getNextTask`):
1. If only one task is ready, it is taken.
2. Otherwise, the top 3 ready tasks by static score are polled from the ready queue. Each gets the blended score (static + dynamic) / 2, and they are ordered by it, with ties keeping static order.
3. **Original lookahead:** when fewer than `workersNum` other ready tasks remain *and* the two best blended scores differ by more than 0.1, an idle-window simulation (`makeLookAhead` / `unemploymentInDuration`) is added to the scores of the top two. The higher total wins, ties going to the first.
4. The unselected candidates are returned to the queue.

**Controller:** processes completions one at a time, in end-time order (`workerFinishOnTime`). It supports adding and removing workers and tasks, early finishes and late finishes.

## 3. Research timeline and data provenance

| Phase | Question | Code (now archived) | Raw results | Status |
|---|---|---|---|---|
| 1 | Fix and improve the algorithm behind flags; validate | `SchedulerConfig`, `StaticGraphAnalysis`, `ExperimentRunner`, `InstanceGenerator`, `ExactSolver` | `research/raw-results/phase1-experiments/` | Complete, report `PHASE1_REPORT.md` |
| 2 | Optimize the weights (fast and quality tracks) | `ParameterSpace`, `CmaEs`, `SobolSequence`, `WeightOptimizer` | `research/raw-results/phase2/` | Complete, report `PHASE2_REPORT.md` |
| 3 | When and why critical-path-first fails | `CriticalPathStudy` | `research/raw-results/phase3/` | Complete, report `PHASE3_REPORT.md` |
| 4 | Lookahead-triggered weight switching | `DynamicWeightStudy` | `research/raw-results/phase4/` | **Incomplete:** no report, development stage not run (empty `dev.log`). No findings are claimed from it. |
| Final | Original + rollout lookahead only | `research/final-integration/harness/` | `research/final-integration/results/` | Complete, this document §6 |

Seeds are disjoint across phases: Phase 1 used 1001–4004, Phase 2 10001–50001, Phase 3 81001–81008, Phase 4 91001+, and the final integration 95001–95006 (plus 3003 for a consistency check, §6.4). All instances come from the same deterministic generator: 8 DAG families (Erdős–Rényi, layered, fork-join, tree, chains, stencil, FFT, Gaussian elimination), 5 duration distributions, 4 workload pressures, and 2–64 workers.

**Statistics used throughout:**
- The geometric mean (GM) of the paired makespan ratio, where below 1 is better.
- 95% percentile-bootstrap CI.
- The two-sided Wilcoxon signed-rank test.
- Wins/ties/losses and the worst instance.

Most instances tie, so the median improvement is usually 0.

## 4. Verified findings, by topic

### 4.1 The original lookahead does not help (Phase 1)

- Removing it improves the original by 0.29–0.33% GM (556 wins, 10 losses on MAIN).
- Every variant that uses it more often is worse:
  - gap condition ignored: +0.64%;
  - inverted gap: +0.20%;
  - activated on all contested decisions: +1.19%.
- Fixing its simulation side effects (it mutated the real idle workers' state) made it slightly worse still (+0.06%). Bounding its score only neutralized it.
- Cause: it scores candidates by worker idle time within the candidate's own duration window. That is a different horizon for each candidate, not a makespan criterion. A documented counterexample (two workers, P→A, P→B, X) gives 12 instead of the optimal 11.

### 4.2 Rollout lookahead: the validated replacement (Phases 1 and 2)

**Mechanism.** At a decision, each compared candidate is started now in a simulation that continues with a static-priority list schedule. In the simulation, idle workers take ready tasks at once and simultaneous completions are applied together. The simulation stops after *d* completion events.
- The estimate is the exact makespan if the schedule finishes. Otherwise it is a lower bound on the final makespan: max(critical-path bound of the running and ready tasks, now + remaining work / workers).
- The smallest estimate wins, and ties keep the blended-score order.
- The simulation works on index arrays and copies only, so real nodes, workers and queues are never modified.

**Findings:**

| Design question | Finding | Evidence |
|---|---|---|
| Activation | Decisive. Use only on contested decisions (more ready tasks than free workers). Under the original gates the rollout reached only 0.9956; on all contested decisions it reached 0.9892–0.9900. | Phase 1 §7 |
| Depth | Steep gain up to 4–8 events, then flat. MAIN: d=0 0.9984, d=1 0.9932, d=4 0.9905, **d=8 0.9895**, d=16 0.9892, full 0.9893. On XL, d=8 (0.9945) beats full depth (0.9953). Phase 2 grid: depth 8 better than 4 in every cell. | Phase 1 §7, Phase 2 §6 |
| Candidates | **L=5** ≥ L=3 at depth ≥ 8 (MAIN 0.9895 vs 0.9900; XL 0.9945 vs 0.9961). L=5 is worse at depth ≤ 2. | Phase 1 §7; Phase 2: L3 ≈ L5 |
| Criterion | A makespan estimate over a common horizon. With depth 0 it equals no lookahead, so the gain comes from simulating forward. It fixes the 12 → 11 counterexample. | Phase 1 §7, unit tests |
| Continuation policy | Static priority. A full rollout from t=0 reproduces the static list schedule exactly. Its quality limits the rollout on large graphs (§4.4). | Phase 1 §7, unit test |
| Guarantee | None formally: the continuation differs from real execution. Measured loss rate vs the original: 1–2% of instances, worst +4%. | Phase 1 §10 |

### 4.3 Candidate pool and dynamic score with the original weights (Phase 1)

- With the original weights, plain static priority (K = 1, no dynamic score) beats the original by 0.61–0.67%. Every pool larger than 1 without lookahead is worse than K = 1.
- Phase 1's recommended rollout configuration used K = 5, which also changes the non-lookahead selection. It is *not* used in the shipped version (§5).

### 4.4 Weights (Phase 2)

- **Critical path dominates.**
  - Every search run, in both tracks, converged to static weights ≈ (1, 0, 0) with λ → 1, and the dynamic weights became tie-breakers on a flat plateau.
  - CMA-ES did not beat random search at equal budget.
  - The learned no-lookahead preset A\* equals plain critical-path-first in quality (paired GM 0.9999).
- **B\* (`QUALITY_OPTIMIZED`):** the rollout with learned weights. 0.9885 vs the original on the Phase 2 test set, 0.9986 vs the Phase 1 rollout B0.
- These are *different algorithms* from the original, because the weights differ. They are reported for completeness only. They are not shipped and are not evidence for the shipped configuration.

### 4.5 When greedy critical-path order fails (Phase 3)

- CP-first is optimal on 78.6% of small exact instances and 0.53% above the optimum on average.
- Its failures concentrate on small graphs, 2–3 workers, balanced load and non-unit durations.
- **Mechanism:** worker starvation. A long critical task is started, while a shorter one would have released successors in time for the next free worker.
- Only simulation captured this. Six tie-break rules and a one-step "next-completion-aware" rule stayed within ±0.02%. Cheap per-decision triggers could not pick the bad decisions (best AUC 0.64), but graph size and worker count predict where lookahead pays (AUC ≈ 0.70).
- Phase 3 rollouts ("pilots") ran in a separate array-based study simulator, with CP-first as the base and continuation policy. They were not implemented in production, so they are not part of the shipped lookahead. Its proposed graph-level gate (rollout only when workers ≤ 8) was never validated in the production scheduler.

### 4.6 Smaller Phase 1 results

| Change | Result |
|---|---|
| Batched completion handling | Small gain (≤ 0.05%) |
| Reach normalized by maximum | Small gain (0.06–0.09%) |
| Id tie-breaks | Quality-neutral; removes input-order dependence (2.1% of instances were order-sensitive) |
| Workload α from available workers | No measurable effect |
| BitSet reachability | Bit-identical outputs; 7.1 s → 0.47 s and 1 GB → 14 MB at 8,000 tasks |
| Exact set: non-delay limit | The best list schedule is 0.02–0.03% above the optimum, so idle-insertion methods are not worth pursuing for this problem class |

## 5. Final integration: what was integrated and what was not

**Integrated** (in `TaskEngine.getNextTask` and helpers):
1. The rollout lookahead replaces the idle-window lookahead.
2. Contested-decision activation replaces the original gate.
3. Depth 8.
4. Five compared candidates.
5. The makespan-oriented estimate (exact or lower bound).
6. The corrected, side-effect-free simulation.

**Conflicts between the validated configuration and "preserve everything else", resolved in favor of the original behavior:**

| Element of the validated configuration | Why it is outside the lookahead | Resolution |
|---|---|---|
| Candidate pool K = 5 (`candidatePoolSize(5)`) | It changes which task is chosen on *every* decision, including those where the lookahead does not run (the best blended score among the top 5 instead of the top 3) | The original top-3 blended selection is kept. Only the rollout compares the top 3 plus the next 2 ready tasks by static score. When it runs, that is the same set of 5 candidates the validated configuration compared; only the order used to break exact ties among them differs. |
| Batched completions (`CompletionMode.BATCHED`) | Changes the Controller's event processing | Not integrated: the Controller still processes completions one at a time. Inside the simulation, simultaneous completions are batched, as in the validated rollout. |
| Reach normalization by maximum | Changes the static score formula | Not integrated |
| Id tie-breaks in the ready queue | Changes queue ordering on all decisions | Not integrated (the original comparator is kept). The *simulation's* own priority breaks ties by id, as validated. |
| Learned weights (`QUALITY_OPTIMIZED`, `FAST_OPTIMIZED`), CP-only weights | Different scoring weights | Not integrated (explicitly excluded) |
| `SchedulerConfig`, presets, `StaticGraphAnalysis` | Configuration architecture and multiple implementations | Not integrated: there is a single scheduler with fixed constants. The research code is archived (§9). |

The legacy public methods `DynamicScoreLogic.makeLookAhead` and `unemploymentInDuration` are kept unchanged for interface compatibility, but the scheduler no longer calls them. Their only side effect, setting idle workers' `unemploymentTime`, is read nowhere else, so dropping the call cannot change other behavior.

**Bug found and fixed during integration.** When the rollout picks one of the two extra candidates, that task is still in the ready queue and must be removed, or it would later be assigned a second time. The original 130 tests did not detect this. The new `RolloutLookaheadTest` checks after every event that every task runs exactly once and that the ready queue holds exactly the waiting tasks whose dependencies are complete. It fails without the fix.

## 6. Final integration results

Harness: `research/final-integration/run-comparison.sh`.
- It rebuilds the original from `git archive be6d109` and the updated version from the working tree.
- It runs both through the public `Controller` API (on-time completions, as in `BenchmarkEngine`) in separate JVMs.
- Full tables, including breakdowns by family, size, workers, durations and pressure, are in `results/comparison.md`.

### 6.1 Non-lookahead behavior is identical

**Differential test.** Both builds were rebuilt with the lookahead block switched off (`if (false && …)`, the only edit) and run on 4,610 static instances plus 3,000 random runtime-event scripts (`results/differential.md`):
- Static scores (score, critical path, reachable duration, blocking power) are bit-identical on 4,610 / 4,610 instances.
- Full schedules (task, worker, start time) are identical on 4,610 / 4,610.
- The event scripts mix on-time, early and late finishes, adding and removing workers and tasks, and invalid requests. Their traces (outcome or exception of each step, plus the full observable state) are byte-identical in 3,000 / 3,000 scripts.
- With the lookahead on, static scores remain bit-identical on all 9,410 benchmark instances. The full builds raised no exception types other than the original's plain `RuntimeException` validation errors in any event script.

**Source diff against `be6d109`.** Only `TaskEngine.java` and `DynamicScoreLogic.java` changed in `backend`:
- the lookahead block was replaced;
- the helpers `rolloutLookahead` and `freeWorkersNow`, the nested `RolloutModel`, `rolloutMakespan`, `makespanLowerBound`, two constants and a cached-model field were added.

`benchmark` changed only in one description string (`BenchmarkStrategy.ADAPTIVE`).

**Tests.** All 130 original tests pass unmodified, plus 5 new tests (135 / 135).

### 6.2 Makespan: in-distribution (MAIN 3,600 and CONFIRM 2,400 instances)

| Breakdown (MAIN) | GM new/original | Wins / ties / losses |
|---|---|---|
| **All** | **0.9893** [0.9886, 0.9900] | 1,526 / 2,030 / 44 |
| Small 10–30 / medium 31–150 / large 151–600 tasks | 0.9852 / 0.9899 / 0.9930 | 435/754/11, 520/661/19, 571/615/14 |
| 2–3 / 4–8 / 9–20 / 21–64 workers | 0.9854 / 0.9912 / 0.9964 / 0.9973 | 984/859/34, 369/599/4, 128/329/6, 45/243/0 |
| Gaussian / Tree / FFT / Stencil / Fork-join | 0.9801 / 0.9871 / 0.9890 / 0.9890 / 0.9901 | — |
| Layered / Erdős–Rényi / Chains | 0.9926 / 0.9924 / 0.9946 | — |
| Unit durations / all others | 0.9992 / 0.9839–0.9879 | 23/739/0 for unit |

- Every family, size, worker range, duration distribution and pressure is better than the original, all with p < 1e-4.
- GM makespan / lower bound goes from 1.0422 to 1.0311, which closes 26% of the gap to the bound.
- CONFIRM (independent seed) replicates the result to within 0.0002: GM 0.9891 [0.9882, 0.9900], +1.06% mean, 1,056 / 1,315 / 29.
- **Losses** are rare: 1.2% of instances in each set.
  - MAIN worst: +2.5% (FFT, 32 tasks, 4 workers: 118 → 121).
  - CONFIRM worst: +5.9% (stencil, 21 tasks, 2 workers: 68 → 72), the only loss above 3%.
  - The largest wins are −21% (MAIN) and −18% (CONFIRM).

### 6.3 Large graphs, optimality and curated scenarios

- **XL (601–1,500 tasks, 200 instances):** GM 0.9958 [0.9945, 0.9969], 84 / 113 / 3, worst +0.4%. The gain shrinks with size, as in Phase 1 (0.9945 there for K = 5).
- **Exact set (800 instances with 2–10 tasks, all solved to optimality):**

  | Scheduler | GM makespan / optimum | % optimal | Worst / optimum |
  |---|---|---|---|
  | Original | 1.0098 | 86.8% | 1.404 |
  | Original + new lookahead | **1.0006** | **97.8%** | 1.097 |

  There are no losses (98 wins / 702 ties).
- **Curated scenarios:** 563 → 561. Scenario 5 goes from 47 to 45, and the other nine are unchanged. This equals the Phase 1 rollout's curated result.

### 6.4 Consistency with the Phase 1 measurement

On the Phase 1 CONFIRM instances (seed 3003, regenerated by the same generator), the shipped version gives GM 0.9895 [0.9885, 0.9904] vs the original. Phase 1 measured 0.9896 for "rollout d=8 L=5 contested" with K = 5 on the same set. Keeping the original top-3 selection (§5) therefore costs nothing measurable.

The shipped version (0.9893–0.9895) is also within about 0.0006 of the full Phase 1 recommendation B0 (0.9887 on CONFIRM). B0 additionally used batched completions, reach normalization and id tie-breaks, which were not integrated.

### 6.5 Runtime

Single-threaded wall time per run (seed 95005, 160 instances; `results/timing.md`):

| Size | Original | Original + new lookahead | Ratio |
|---|---|---|---|
| Small (10–30 tasks) | 0.12 ms | 0.17 ms | 1.4× |
| Medium (31–150) | 0.99 ms | 2.87 ms | 2.9× |
| Large (151–600) | 9.7 ms | 27.0 ms | 2.8× |
| XL (601–1,500) | 70 ms | 190 ms | 2.7× |

These runs include the original static analysis (HashSet reachability), which is unchanged. Phase 1's faster BitSet analysis was not integrated, which is why absolute times are higher than in the Phase 1–2 reports.

## 7. Limitations

- **Synthetic data:** all instances come from one generator with deterministic durations and identical workers. The runtime-event tests check correctness and equivalence, not quality under uncertainty.
- **No guarantee:** the rollout continuation (static priority, batched simulation) is not the real policy, so the rollout can lose. It lost on 1.2% of instances, worst +5.9%.
- **Lower bounds:** the lower bound is loose on large graphs, so the true optimality gap there is unknown. Exact optima exist only for ≤ 10 tasks in this evaluation (≤ 18 in Phase 3).
- **Runtime:** about 2.8× the original on medium and large graphs (≈ 0.2 s at 1,000+ tasks per full run). Phase 3's evidence suggests a size- or worker-gated rollout could cut this, but that was never validated in the production scheduler and is not included.
- **Phase 4:** incomplete. Its partial outputs are preserved but unverified.

## 8. Reproduction

```
mvn -o test                                                   # 135 tests
bash research/final-integration/run-comparison.sh 12          # several minutes on 12 threads; writes research/final-integration/results/
REPORTS_ONLY=1 bash research/final-integration/run-comparison.sh   # regenerate the .md reports from the CSVs
```

Phases 1–3 cannot be rerun from the current source tree, because their configurable code is archived. To reproduce them:
1. Check out `be6d109` into a separate worktree.
2. Copy `research/archive/phase1-4-research-source/src` over it.
3. Follow the commands in the respective phase report.

## 9. File index

| Path | Content |
|---|---|
| `research/phase-reports/PHASE1_REPORT.md`, `PHASE2_REPORT.md`, `PHASE3_REPORT.md` | Original phase reports (unchanged) |
| `research/RESEARCH_REPORT.md` | This document |
| `research/raw-results/phase1-experiments/` | Phase 1 raw CSVs and tables (copied from `target/experiments/`) |
| `research/raw-results/phase2/`, `phase3/`, `phase4/` | Phase 2–4 run directories (copied from `target/phase*/`) |
| `research/archive/phase1-4-research-source/` | Exact copies of all research source and test files, as they were before the integration, plus `tracked-files-vs-be6d109.diff` |
| `research/final-integration/harness/` | Comparison harness (`Run`, `Compare`, plus copies of the research generator and exact solver) |
| `research/final-integration/run-comparison.sh` | Builds the original, updated and lookahead-disabled variants and runs every experiment |
| `research/final-integration/results/` | Raw per-instance CSVs, `comparison.md`, `differential.md`, `timing.md` |
