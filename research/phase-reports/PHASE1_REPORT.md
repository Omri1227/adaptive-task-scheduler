# Phase 1 Report — Algorithm Improvements, Validation and Weight-Optimization Preparation

All 13 approved changes are implemented behind configuration flags. The default configuration (`SchedulerConfig.DEFAULT`) reproduces the original scheduler exactly. No default behaviour has been changed; recommendations below require approval.

Reproduce every number in this report with:

```
mvn -o -q compile
java -Xmx10g -cp target/classes benchmark.ExperimentRunner <main|confirm|xl|exact|shuffle|curated|timing> target/experiments 12
```

Raw per-run CSV files and generated summary tables are written to `target/experiments/` (git-ignored). Sizes: main.csv 313,200 runs, confirm.csv 52,800, xl.csv 4,400, exact.csv 69,600.

Labels used below: **[measured]** = produced by the runs above; **[hypothesis]** = not yet tested.

---

## 1. Executive summary

1. **The best validated configuration improves makespan by about 1.1% on average versus the original scheduler** [measured].
   - The configuration: rollout lookahead (Change 6) activated only on contested decisions (Change 7), depth 8, 5 candidates, plus batched completions, reach/max normalization and id tie-breaks.
   - Independent confirmation set (2,400 instances): GM ratio 0.9887 [95% CI 0.9877–0.9897], 1,055 wins / 1,312 ties / 33 losses, worst instance +4.0%.
   - It is better or equal on every graph family and every size class.
   - On small instances solved exactly it reaches 0.09% above the optimum. The original is 1.01% above, and the rollout is optimal on 97.6% of them versus 85.8%.
2. **The original lookahead never helps** [measured]. Removing it improves the original by 0.29–0.33%, with 556 wins and only 10 losses. Every variant that uses it more often makes results worse:
   - gap condition ignored: +0.64%;
   - activated on all contested decisions: +1.19%.

   Fixing its idle-worker simulation (Change 8) makes it slightly worse still.
3. **With the current weights, the dynamic score also costs quality** [measured]. Plain static priority (K=1) beats the original by 0.61–0.67%. Every larger candidate pool is worse than K=1.
4. **A simple rule is as good on average.** Critical-path-first matches the improved adaptive scheduler's average (0.9895–0.9897), but with 5–7× more losses and worst cases up to +25%.
5. **The static weights have large, size-dependent headroom.** One exploratory probe set the static score to critical path only, under the same rollout:
   - confirmation set: 0.9877 instead of 0.9896;
   - n ≈ 1,000 graphs: 0.9873 instead of 0.9945.

   The default static weights hold the rollout back on large graphs. This is the strongest signal for Phase 2.
6. **Static-analysis cost** (Change 4) [measured]. On an 8,000-task graph the analysis went from 7.1 s to 0.47 s and from 1,018 MB to 14 MB of retained memory, and to 70 ms when the precomputed analysis is reused. Outputs are bit-identical.
7. **Cost of the recommended rollout** [measured]. Depth 8, 5 candidates costs 20 ms per run at 150–600 tasks and 160 ms at about 1,000 tasks, versus 1.4–3.5 ms for the original.
8. **Event semantics.** Batched vs one-at-a-time completion handling changes baseline results by at most 0.03%. The comparisons in this report are not artifacts of event semantics.

---

## 2. Implementation summary (file by file)

### Modified files
6 files, +733 / −128 lines. All original public constructors and methods keep their signatures and behaviour.

| File | Change(s) | What changed |
|---|---|---|
| `backend/Controller.java` | 1, 4, 9 | New overloads `Controller(tasks, workers, print, SchedulerConfig)` and `(…, StaticGraphAnalysis)`. Config is passed to engines rebuilt by `addTask`/`removeTask`. `getConfig()`. BATCHED branch in `workerFinishOnTime` (private `finishAllAtNextCompletionTime`). |
| `backend/TaskEngine.java` | 1, 4, 6, 7, 10, 12, 13 | New overloads `TaskEngine(tasks, config)` and `(tasks, config, analysis)`. `getNextTask` uses a K-candidate pool with λ-blending and dispatches to `legacyLookahead` or `rolloutLookahead`. Activation helpers `gapAllowsLookahead` and `freeWorkersNow`. Optional id tie-break comparator. `dynamicScoreChoose` (private) generalized to K candidates with the original strict-greater insertion order. |
| `backend/DynamicScoreLogic.java` | 1, 5, 6, 8, 10 | Config constructor (the no-arg one is kept). Configurable dynamic weights. `makeDynamicScore` overload with available workers. The simulation copies idle workers instead of mutating them. `unemploymentInDuration` overload with the idle-assignment flag. Nested `RolloutModel` and `rolloutMakespan` simulation with a makespan lower bound. |
| `backend/TimeConstraints.java` | 1, 4, 11 | Config constructor overload. Configurable static weights. BitSet reachability (legacy HashSet path kept when sets were pre-populated). Analysis-reuse path. Reach normalization option. |
| `backend/GraphTaskNode.java` | 4 | Lazy reachable set built from a shared BitSet. All existing methods keep their observable results. |
| `benchmark/BenchmarkEngine.java` | 3, 4 | `benchmark(scenario, config, baselineMode)` and `makespan(…)` overloads. Baselines selectable SEQUENTIAL/BATCHED, with SEQUENTIAL mirroring the Controller's processing order. Analysis reuse. The original `benchmark(scenario)` is unchanged. |

### New files
Minimal supporting classes in the existing packages.

| File | Purpose |
|---|---|
| `backend/SchedulerConfig.java` | Immutable config and builder. All weights, λ, μ, τ, gap condition, lookahead mode/candidates/depth, activation flags, simulation fix, completion mode, workload mode, reach normalization, tie-break, K. `DEFAULT` equals the original. |
| `backend/StaticGraphAnalysis.java` | Weight-independent analysis computed once and reused across configs. It rejects use with a different graph or task order. |
| `benchmark/InstanceGenerator.java` | Deterministic generator: 8 families × 5 duration distributions × 5 size buckets. Workers are set from average parallelism and a pressure factor, with at least 2 workers. |
| `benchmark/ExactSolver.java` | Branch-and-bound for the true optimum (all active schedules) and for the best non-delay (list) schedule, on small instances. |
| `benchmark/ExperimentRunner.java` | Parallel deterministic evaluation, paired statistics (GM, bootstrap CI, Wilcoxon), breakdowns, exact, shuffle, curated and timing experiments. |
| 16 test classes in `src/test/java/backend/` | See §4. |

No directories, files, classes, public methods or fields were moved, renamed or removed. The UI package is untouched.

---

## 3. Architecture preservation confirmation

Verified with `git diff`:
- No removed or changed line declares a `public` or `protected` member.
- No files were deleted or renamed.
- The directory layout is unchanged.

Private changes:
- `BenchmarkEngine.RunningTask` (a private record) gained an `assignment` field.
- `TaskEngine.dynamicScoreChoose` (private) takes the candidate pool as an array.

The legacy lookahead is still the default, and is still implemented by `DynamicScoreLogic.makeLookAhead` and `unemploymentInDuration`, whose original signatures delegate unchanged. The rollout is a selectable mode inside the same `TaskEngine`/`DynamicScoreLogic` code; there is no separate scheduler. Line endings (CRLF) are preserved.

---

## 4. Test report

`mvn -o -q test`: **180 tests run, 0 failures, 0 errors, 0 skipped.** That is 130 pre-existing tests (all pass, unmodified) plus 50 new ones.

| New test class | Covers |
|---|---|
| GoldenTraceTest (3) | Legacy equivalence: 360 generated instances, 60 runtime-event scripts, 10 curated scenarios |
| SchedulerConfigTest (7) | Defaults, round trip, weights take effect, μ = 0, inverted gap, validation |
| BenchmarkCompletionModeTest (3) | Original overload unchanged; modes differ; SEQUENTIAL baseline equals Controller static-only |
| StaticGraphAnalysisTest (5) | BitSet reachability vs an independent DFS oracle; reuse gives identical schedules; mismatch rejection |
| LookaheadSideEffectTest (1) | Real workers are no longer mutated |
| RolloutLookaheadTest (4) | Full rollout equals the static list schedule exactly; truncated bound ≤ full; fixes the counterexample; schedules valid (precedence, capacity) |
| LookaheadActivationTest (5) | Contested gate, NONE mode, legacy gate off, bounded score (hand-computed cases) |
| SimulationIdleAssignmentTest (2) | Idle-time 10 → 5 on a hand-computed case |
| BatchedCompletionTest (3) | Valid schedules; equals BATCHED static baseline; simultaneous completions in one event |
| WorkloadPressureTest (4) | α with total vs available workers, zero-safe |
| ReachNormalizationTest (3) | Both normalizations, with and without analysis reuse |
| DeterministicTieBreakTest (2) | Static-only + id ties is invariant to input order |
| CandidatePoolSizeTest (3) | K = 1 equals the static baseline; valid schedules for K ∈ {1, 2, 3, 5, 10, all}; K = 5 can pick a task outside the static top 3 |
| ExactSolverTest (2) | Both solvers equal brute-force oracles on 250 instances; bounds hold |
| ExperimentInfrastructureTest (3) | Generator determinism, validity, seed separation |

Unresolved issues: none. One failure was found during development and fixed: a hand-designed fixture in `CandidatePoolSizeTest` had a wrong expected value. The scheduler code was correct.

---

## 5. Golden-trace equivalence report

- **Recorded before any change.** `GoldenTraceData` was generated from commit be6d109 and checked for deterministic regeneration. Each entry hashes every static score component (bit-exact doubles) and the full schedule (task, worker, start time), plus the outcome of every runtime event (`addWorker`, `removeWorker`, `addTask`, `removeTask`, `workerFinishEarly`, `workerIsLate`, including exceptions).
- **Differential check.** It ran after every change (1–13) and after the infrastructure work: 20,000 generated instances (n up to 300, half with shuffled input order, 1–16 workers). The original commit, compiled separately, was compared with the working tree on full schedule plus static-score hashes, and on all six baseline makespans for 5,000 of them. **Result after every change: 0 / 20,000 differences.**
- **Default reproduces original.** In all experiments `ORIGINAL` (= `SchedulerConfig.DEFAULT`) matches the pre-change makespans: for example, curated total 563, identical to the original benchmark.

---

## 6. Benchmark results

### 6.1 Datasets
All seeded and deterministic.

| Set | Seed | Composition | Use |
|---|---|---|---|
| MAIN | 1001 | 8 families × {SMALL 10–30, MEDIUM 31–150, LARGE 151–600} × 150 = 3,600 instances; n 4–600 (mean 156); workers 2–64; 5 duration distributions (~720 each); 4 workload pressures | All E1–E8 comparisons; selection of combinations |
| CONFIRM | 3003 | Same design, 100 per cell = 2,400 | Independent confirmation of selected variants |
| XL | 4004 | 8 families × 25, n 448–1,486 (mean 1,006) | Size generalization and cost |
| EXACT | 2002 | 8 families × 100 tiny (n 2–10, 2–4 workers) = 800 | Comparison against true optimum and best list schedule |
| CURATED | — | The 10 `ScenarioLibrary` scenarios | Hold-out only, never used for selection |

Families: Erdős–Rényi, layered, fork-join, in/out-tree, chains (+singles), stencil (wavefront), FFT butterfly, Gaussian elimination. FFT and Gaussian sizes are quantized by their structure.

**Metrics.** All paired comparisons are against ORIGINAL on identical instances:
- GM ratio: the geometric mean of makespan_variant / makespan_original. Below 1 is better.
- 95% percentile-bootstrap CI (2,000 resamples).
- Mean and median relative improvement.
- Win/tie/loss counts and worst ratio.
- Two-sided Wilcoxon signed-rank p-value.
- GM makespan / lower bound (LB = max(critical path, ⌈work/workers⌉)).

Median improvement is +0.00 for every variant, because most instances tie (the scheduler often already reaches the bound). Completion semantics are SEQUENTIAL unless stated.

### 6.2 Per-change results (MAIN, 3,600 instances)

| Change / experiment | Variant | GM ms/LB | GM vs ORIGINAL [95% CI] | mean impr % | W/T/L | worst | p |
|---|---|---|---|---|---|---|---|
| reference | ORIGINAL | 1.0423 | 1.0000 | 0 | — | — | — |
| reference | NO_LOOKAHEAD (mode NONE) | 1.0392 | 0.9971 [0.9966, 0.9975] | +0.28 | 556/3034/10 | 1.036 | <1e-4 |
| reference | STATIC_ONLY (K=1) | 1.0359 | 0.9939 [0.9933, 0.9946] | +0.59 | 1125/2117/358 | 1.081 | <1e-4 |
| 1 (E1) μ | μ = 0.25 / 0.5 | 1.0408 / 1.0416 | 0.9986 / 0.9994 | +0.14 / +0.06 | 371/…/11, 201/…/8 | 1.041 / 1.077 | <1e-4 |
| 7 (E1) gap | gap ≤ τ (inverted) | 1.0444 | 1.0020 [1.0013, 1.0027] | −0.23 | 466/2255/879 | 1.273 | <1e-4 |
| 7 (E1) gap | gap ignored | 1.0490 | 1.0064 [1.0057, 1.0071] | −0.66 | 63/2539/998 | 1.368 | <1e-4 |
| 7 (E1) bound | bounded score, μ = 1 | 1.0423 | 1.0000 | 0.00 | 9/3591/0 | 1.000 | 0.008 |
| 7 (E1) bound | bounded, μ = 0.5 | 1.0416 | 0.9993 | +0.07 | 212/3380/8 | 1.068 | <1e-4 |
| 7 (E1) contested | legacy lookahead, contested-only | 1.0547 | 1.0119 [1.0108, 1.0130] | −1.25 | 73/2355/1172 | 1.523 | <1e-4 |
| 8 (E8) | legacy, idle-fix | 1.0429 | 1.0006 [1.0004, 1.0007] | −0.06 | 5/3492/103 | 1.138 | <1e-4 |
| 8 (E8) | legacy, idle-fix, contested | 1.0563 | 1.0135 | −1.42 | 64/2282/1254 | 1.523 | <1e-4 |
| 6+7 (E2) | rollout d=8 L=3 contested | 1.0319 | 0.9900 [0.9893, 0.9907] | +0.98 | 1522/2022/56 | 1.041 | <1e-4 |
| 6+7 (E2) | rollout d=16 L=5 contested | 1.0310 | 0.9892 [0.9885, 0.9899] | +1.06 | 1606/1950/44 | 1.041 | <1e-4 |
| 6 (E2) | rollout d=full L=3, legacy gates | 1.0377 | 0.9956 | +0.42 | 928/2535/137 | 1.085 | <1e-4 |
| 13 (E3) | K=2 / 5 / 10 / all, no lookahead | 1.0374 / 1.0406 / 1.0417 / 1.0426 | 0.9953 / 0.9984 / 0.9994 / 1.0003 | +0.45 / +0.15 / +0.04 / −0.06 | — | 1.07–1.17 | — |
| 13 (E3) | K=2 / 5 / 10 / all, legacy lookahead | 1.0405 / 1.0437 / 1.0448 / 1.0458 | 0.9983 / 1.0014 / 1.0024 / 1.0034 | — | — | 1.14–1.21 | — |
| 9 (E4) | ORIGINAL batched | 1.0417 | 0.9995 [0.9992, 0.9997] | +0.05 | 212/3298/90 | 1.167 | <1e-4 |
| 9 (E4) | NO_LOOKAHEAD batched | 1.0389 | 0.9968 (vs 0.9971 sequential) | +0.31 | 642/2879/79 | 1.074 | <1e-4 |
| 9 (E4) | STATIC_ONLY batched | 1.0357 | 0.9937 (vs 0.9939) | +0.61 | 1132/2113/355 | 1.081 | <1e-4 |
| 10 (E5) | ORIGINAL, available-worker α | 1.0429 | 1.0005 [1.0003, 1.0008] | −0.06 | 152/3262/186 | 1.227 | 0.005 |
| 10 (E5) | NO_LOOKAHEAD, available-worker α | 1.0392 | 0.9970 (vs 0.9971) | +0.29 | 660/2858/82 | 1.047 | <1e-4 |
| 11 (E6) | ORIGINAL reach/max | 1.0417 | 0.9994 [0.9990, 0.9997] | +0.06 | 219/3253/128 | 1.207 | <1e-4 |
| 11 (E6) | NO_LOOKAHEAD reach/max | 1.0385 | 0.9964 (vs 0.9971) | +0.35 | 722/2763/115 | 1.082 | <1e-4 |
| 11 (E6) | STATIC_ONLY reach/max | 1.0353 | 0.9933 (vs 0.9939) | +0.65 | 1159/2083/358 | 1.081 | <1e-4 |
| 12 (E7) | ORIGINAL id-ties | 1.0423 | 1.0000 [1.0000, 1.0000] | 0.00 | 33/3539/28 | 1.024 | 0.28 |
| baseline | CP-first (SEQUENTIAL / BATCHED) | 1.0313 / 1.0311 | 0.9895 / 0.9893 | +1.02 / +1.04 | 1358/2005/237 | 1.250 / 1.200 | <1e-4 |
| baseline | largest downstream work (SEQUENTIAL) | 1.0350 | 0.9930 | +0.67 | 1210/1961/429 | 1.164 | <1e-4 |
| baseline | FIFO / LPT / SPT (SEQUENTIAL) | 1.107 / 1.110 / 1.135 | 1.062 / 1.065 / 1.089 | −6.6 / −6.9 / −9.4 | — | 1.50–1.67 | <1e-4 |

**Changes 2, 3, 4 and 5 do not change behaviour**; they are verified by equivalence (§5). For Change 3, each baseline's batched vs sequential GM differs by ≤ 0.0003 (for example CP-first 0.9893 vs 0.9895). The completion-semantics asymmetry noted in the review does not bias the comparisons.

### 6.3 Breakdown by family and size (MAIN, GM vs ORIGINAL, wins/losses)

| variant | ER | LAYERED | FORK_JOIN | TREE | CHAINS | STENCIL | FFT | GAUSSIAN | SMALL | MEDIUM | LARGE |
|---|---|---|---|---|---|---|---|---|---|---|---|
| NO_LOOKAHEAD | 0.9978 | 0.9984 | 0.9958 | 0.9969 | 0.9970 | 0.9978 | 0.9954 | 0.9975 | 0.9936 | 0.9985 | 0.9991 |
| STATIC_ONLY | 0.9934 | 0.9962 | 0.9946 | 0.9898 | 0.9967 | 0.9992 (117/140) | 0.9958 | 0.9857 | 0.9907 | 0.9939 | 0.9971 |
| rollout d=8 L=5 contested | 0.9912 | 0.9932 | 0.9908 | 0.9871 | 0.9936 | 0.9862 | 0.9901 | 0.9836 | 0.9856 | 0.9903 | 0.9926 |
| rollout d=full L=5 contested | 0.9907 (154/1) | 0.9927 (137/1) | 0.9896 (278/1) | 0.9866 (239/1) | 0.9934 (137/0) | 0.9890 (226/32) | 0.9896 (172/2) | 0.9825 (269/7) | 0.9852 | 0.9894 | 0.9932 |
| CP-first (SEQUENTIAL) | 0.9927 | 0.9952 | 0.9939 (208/56) | **0.9792** | 0.9928 | 0.9859 | 0.9905 | 0.9856 | 0.9894 | 0.9905 | **0.9886** |

The rollout improves every family, with at most 7 losses per family except stencil (13–32). Its gain shrinks with size (SMALL 1.4% → LARGE 0.7%). CP-first is better on trees and on large graphs; the rollout is better on the other families and has a far smaller tail.

### 6.4 Confirmation on an independent seed (CONFIRM, 2,400 instances) and size generalization (XL, 200 instances, n ≈ 1,000)

| variant | CONFIRM GM vs ORIGINAL [CI] | W/T/L | worst | XL GM vs ORIGINAL [CI] | XL W/T/L | XL worst |
|---|---|---|---|---|---|---|
| NO_LOOKAHEAD | 0.9966 [0.9959, 0.9972] | 349/2048/3 | 1.002 | 0.9995 | 35/164/1 | 1.001 |
| STATIC_ONLY | 0.9933 [0.9924, 0.9942] | 755/1417/228 | 1.111 | 0.9978 | 64/111/25 | 1.032 |
| rollout d=8 L=3 contested | 0.9900 [0.9890, 0.9909] | 999/1362/39 | 1.027 | 0.9961 | 80/115/5 | 1.006 |
| rollout d=8 L=5 contested | 0.9896 [0.9886, 0.9906] | 1024/1325/51 | 1.038 | 0.9945 | 95/103/2 | 1.005 |
| rollout d=full L=5 contested | 0.9892 [0.9882, 0.9901] | 1060/1302/38 | 1.038 | 0.9953 | 94/101/5 | 1.007 |
| d=8 L=5 + batched | 0.9891 | 1046/1319/35 | 1.027 | 0.9943 | 96/101/3 | 1.011 |
| d=8 L=5 + reach/max | 0.9892 | 1036/1315/49 | 1.040 | 0.9938 | 95/101/4 | 1.005 |
| d=8 L=5 + id-ties | 0.9896 | 1027/1321/52 | 1.038 | 0.9945 | 96/101/3 | 1.005 |
| **d=8 L=5 + batched + reach/max + id-ties** | **0.9887 [0.9877, 0.9897]** | 1055/1312/33 | 1.040 | **0.9936** | 99/97/4 | 1.011 |
| STATIC_ONLY + reach/max + batched | 0.9926 | 779/1407/214 | 1.100 | 0.9972 | 66/110/24 | 1.032 |
| PROBE: d=8 L=5 rollout, static = CP only | **0.9877** [0.9865, 0.9887] | 1045/1304/51 | 1.046 | **0.9873** | 106/92/2 | 1.006 |
| PROBE: STATIC_ONLY, static = CP only | 0.9896 | 905/1330/165 | 1.100 | 0.9879 | 97/99/4 | 1.015 |
| CP-first (SEQUENTIAL) | 0.9897 | 885/1340/175 | 1.100 | 0.9879 | 99/97/4 | 1.002 |

The MAIN-set conclusions replicate on the independent seed to within 0.001. The PROBE rows test one fixed weight vector, not training. They show the default static weights (0.5/0.25/0.25) are a large part of the remaining gap on big graphs.

### 6.5 Exact comparison (EXACT, 800 tiny instances, all solved)

The optimum is on average 1.04% above the simple lower bound. The best list (non-delay) schedule is only 0.02% above the optimum, and only 3 of 800 instances need deliberate idling. So on small instances the non-delay limitation is negligible.

| variant | GM ms/OPT | % optimal | GM ms/best-list | worst ms/OPT |
|---|---|---|---|---|
| ORIGINAL | 1.0101 | 85.8 | 1.0099 | 1.529 |
| NO_LOOKAHEAD | 1.0033 | 92.6 | 1.0031 | 1.200 |
| STATIC_ONLY | 1.0036 | 93.1 | 1.0034 | 1.250 |
| rollout d=1 L=3 contested | 1.0030 | 93.0 | 1.0029 | 1.176 |
| rollout d≥4 L=3/5 contested | 1.0009 | 97.3–97.6 | 1.0007–1.0008 | 1.083 |
| CP-first | 1.0035 | 93.5 | 1.0033 | 1.250 |
| SPT | 1.0383 | 70.3 | 1.0381 | 1.559 |

### 6.6 Curated scenarios (hold-out, makespans; sum over the 10)

| variant | #1 | #2 | #3 | #4 | #5 | #6 | #7 | #8 | #9 | #10 | sum |
|---|---|---|---|---|---|---|---|---|---|---|---|
| ORIGINAL | 24 | 27 | 29 | 42 | 47 | 31 | 69 | 101 | 99 | 94 | 563 |
| rollout d≥8, L=3 or 5, contested | 24 | 27 | 29 | 42 | **45** | 31 | 69 | 101 | 99 | 94 | **561** |
| NO_LOOKAHEAD | 24 | 27 | 29 | 42 | 50 | 32 | 69 | 101 | 99 | 94 | 567 |
| STATIC_ONLY | 27 | 27 | 29 | 44 | 51 | 32 | 69 | 101 | 99 | 96 | 575 |
| CP-first | 27 | 31 | 29 | 44 | 50 | 32 | 69 | 101 | 99 | 94 | 576 |

The rollout is never worse than the original on any curated scenario. The greedy and static rules, which these scenarios were built to trap, are clearly worse.

### 6.7 E7: sensitivity to input order (1,200 MAIN instances × 11 orderings)

| variant | % instances whose makespan depends on order | mean range % | max range % |
|---|---|---|---|
| ORIGINAL | 2.1 | 0.007 | 1.82 |
| STATIC_ONLY | 4.2 | 0.010 | 1.82 |
| rollout d=full L=3 contested | 0.8 | 0.004 | 1.47 |
| any of the above + id tie-break | **0.0** | 0 | 0 |
| CP-first baseline (ties by list order) | 23.3 | 0.146 | 9.09 |

Order sensitivity is a small noise source for the original (about 0.007% on average). It disappears completely with Change 12, which does not affect quality (E7 row in §6.2: 33 wins / 28 losses, p = 0.28).

---

## 7. Lookahead analysis

| Question | Measured answer |
|---|---|
| Legacy idle-window lookahead | Harmful whenever it decides. Best setting is off (μ = 0): GM 0.9971. More activation is worse (any gap 1.0064; contested 1.0119). The simulation fix (Change 8) does not rescue it (1.0006). Bounded scoring only neutralizes it. |
| Activation policy | Decisive for the rollout. Under the original gates (load gate + gap > 0.1) the rollout reaches only 0.9956. Activated on every contested decision (ready tasks > free workers), it reaches 0.9892–0.9900. Uncontested decisions are skipped by construction. |
| Depth (completion events) | Steep gain up to 4–8, then flat. MAIN, L=5 contested: d=0 0.9984, d=1 0.9932, d=2 0.9918, d=4 0.9905, d=8 0.9895, d=16 0.9892, full 0.9893. CONFIRM: d=8 0.9896 vs full 0.9892. **XL: d=8 (0.9945) is better than full (0.9953).** Deeper is not better there: the truncated bound beats a long static-policy continuation. |
| Candidates compared (L) | L=5 ≥ L=3 at depth ≥ 8 (MAIN 0.9895 vs 0.9900; XL 0.9945 vs 0.9961). L=5 at depth ≤ 2 is worse than L=3, because it compares more candidates with a weak estimate. L=2 ≈ L=3. |
| Evaluation criterion | A makespan estimate over a common horizon (exact makespan if the schedule finishes, otherwise a critical-path/work lower bound). It replaces candidate-specific idle windows and fixes the documented counterexample (12 → 11). Depth 0 with this bound already equals no-lookahead (0.9971), so the gains come from simulating forward. |
| Continuation policy | Static-priority list scheduling. A full rollout from t=0 reproduces the static baseline exactly (tested). The CP-only probe shows the quality of this continuation's priority limits the rollout on large graphs. |
| Cost (single thread, ms per run; S / M / L / XL) | d=8 L=5: 0.08 / 1.5 / 20 / 159. d=16 L=5: 0.09 / 2.0 / 28 / 229. Full L=5: 0.10 / 4.1 / 88 / 801. Original: 0.11 / 1.1 / 1.4 / 3.5. |
| **Recommendation** | Depth 8, L=5, contested-only. Depth 16 is within noise on quality at +40% cost. Full depth costs 4–5× more without a measured gain at XL. |

---

## 8. Performance analysis

- **Static analysis (Change 4).** Init time and retained memory, original → BitSet → BitSet + reused analysis, deep graphs (width 4):

  | n | init time | retained memory |
  |---|---|---|
  | 1,000 | 53 → 21 → 9 ms | 16 → 0.9 → 0.8 MB |
  | 2,000 | 239 → 41 → 27 ms | 64 → 2.1 → 1.5 MB |
  | 4,000 | 2,061 → 129 → 36 ms | 258 → 5.2 → 3.1 MB |
  | 8,000 | 7,102 → 472 → 70 ms | 1,018 → 14 → 6.3 MB |

  Wide graphs at n=8,000: 2.5 s → 0.15 s and 361 → 14 MB. Outputs are bit-identical (golden + differential tests).
- **Scheduling cost per run** (single thread, analysis reused, 40 instances per size bucket, median of 3):

  | | MEDIUM | LARGE | XL |
  |---|---|---|---|
  | ORIGINAL | 1.1 ms | 1.4 ms | 3.5 ms |
  | NO_LOOKAHEAD / STATIC_ONLY | 0.1 ms | 0.6 ms | 1.8–2.1 ms |
  | Recommended rollout (d=8, L=5) | 1.5 ms | 20 ms | 159 ms |

  The recommended rollout is 14–45× slower than the original on large graphs but well below a second. Timings below 1 ms are noisy (JIT).
- **Rollout memory.** Transient O(n) arrays per simulated candidate, no retained growth.
- **Campaign cost.** MAIN (3,600 instances × 87 variants = 313k runs): 211 s on 12 threads. CONFIRM 184 s, XL 201 s, E7 184 s, EXACT 3 s.

---

## 9. Recommended final configuration (measured; not applied — needs your approval)

**Quality configuration.** CONFIRM 0.9887 (−1.13% mean makespan), XL 0.9936, tiny instances 0.09% above the optimum, curated 561 vs 563, worst instance +4%:

```java
SchedulerConfig.builder()
    .lookaheadMode(SchedulerConfig.LookaheadMode.ROLLOUT)
    .rolloutDepth(8).lookaheadCandidates(5).candidatePoolSize(5)
    .legacyLoadGate(false).gapCondition(SchedulerConfig.GapCondition.ANY_GAP)
    .contestedDecisionsOnly(true)
    .completionMode(SchedulerConfig.CompletionMode.BATCHED)     // Change 9: small consistent gain
    .reachNormalizedByMaximum(true)                             // Change 11: small consistent gain
    .deterministicTieBreak(true)                                // Change 12: quality-neutral, removes order dependence
    .build();
```

**Low-cost alternative.** `candidatePoolSize(1)` + reach/max + batched gives CONFIRM 0.9926 at lower runtime than the original.

| Change | Verdict |
|---|---|
| 1, 2, 4, 5 | Keep (no behaviour change; infrastructure) |
| 3 | Keep; comparisons validated |
| 6 + 7 (rollout, contested activation) | **Beneficial, recommend for default** |
| 7 (inverted or ignored gap, bounded score for the legacy lookahead) | No benefit; keep behind flags, off |
| 8 | Slightly harmful; keep off |
| 9 | Small consistent benefit; recommend with rollout |
| 10 | No measurable benefit (0.9970 vs 0.9971); keep off |
| 11 | Small consistent benefit (0.06–0.09%); recommend |
| 12 | Quality-neutral, improves reproducibility; recommend |
| 13 | K > 3 harmful with current weights; K = 1 best without rollout; K = 5 used only by the rollout; revisit in Phase 2 |

Retiring the legacy idle-window lookahead from the default is recommended. It stays available as `LEGACY_IDLE_WINDOW`, and `DEFAULT` is unchanged until you approve.

---

## 10. Remaining limitations and risks

- **The lower bound is loose on large instances.** On tiny instances the optimum is 1.04% above the bound; on large ones the true optimality gap is unknown. Exact optima are only available for n ≤ 10, where the rollout is already near-optimal. Absolute optimality claims for large graphs are not supported.
- **Synthetic distribution.** 8 generator families with deterministic durations, no runtime events, identical workers. Generalization beyond these families is untested except on the 10 curated scenarios. FFT and Gaussian sizes are coarse.
- **Phase 1 used MAIN both to measure and to choose combinations.** CONFIRM and XL are the unbiased estimates for the chosen variants; Phase 2 must use fresh seeds.
- **The rollout continuation is not an exact model of execution.** It uses static priority and batched semantics, while real execution reconsiders with the rollout itself. The improvement guarantee of classical rollout therefore does not formally hold; the measured loss rate is 1.4–2.1%.
- **Cost.** The rollout is 14–45× slower than the original on large graphs (≈ 160 ms at n ≈ 1,000). This is acceptable offline, possibly not for interactive use at n ≫ 1,000.
- **Timing.** Runtimes in the campaign tables were measured under 12-thread contention; the single-thread table (§8) is authoritative. Sub-millisecond values are noisy.
- **The legacy lookahead stays the default** until approved, so the shipped behaviour still carries the issues documented in the review.

---

## 11. Phase 2 — weight optimization plan (ready for approval; not started)

### A. Optimization targets

Two tracks, optimized separately and in sequence.

| Parameter | Track A ("fast": no lookahead) | Track B ("quality": rollout) | Range / constraint |
|---|---|---|---|
| Static weights (w_cp, w_block, w_reach) | optimize | optimize (it is also the continuation priority) | simplex, w ≥ 0, Σ = 1 (2 dof, softmax-parameterized) |
| Dynamic weights (5) | optimize | optimize | simplex (4 dof) |
| λ (static share) | optimize | optimize | [0, 1] |
| K (candidate pool) | {1, 2, 3, 5} | fixed 5 | integer |
| L (rollout candidates), depth | — | outer grid {3, 5} × {4, 8} | integer |
| Batched / reach-max / id-ties | fixed on (measured benefit or neutral) | fixed on | — |
| μ, τ, gap condition, legacy-lookahead flags, Change 8, Change 10 | fixed off | fixed off | evidence: no benefit (§6) |

Lookahead parameters are optimized as an outer discrete grid with weights optimized inside each cell (not jointly in one continuous search). Depth and L change the objective's cost 2–4× and interact with the weights only through the candidate order. Track B is warm-started from Track A's best static weights. The CP-only probe (§6.4) shows static weights are the highest-value target.

### B. Method

0. **Sensitivity (Stage 0).** 1,024 scrambled-Sobol configurations (Track A space) on 1,000 training instances. Compute per-parameter first-order and total-effect estimates (Sobol/Saltelli estimator or fANOVA on the sample) to find flat directions and possibly fix them.
1. **CMA-ES (Stage 1).** Own deterministic Java implementation in `benchmark`; no new dependency.
   - Population 20, σ₀ = 0.3 in the unconstrained parameterization, IPOP restarts ×3, 150 generations per restart (Track A), 60 per grid cell (Track B).
   - Each generation draws a fresh minibatch of 1,500 training instances (stratified by family × size), shared by all members (common random numbers).
   - Integer K is handled by running CMA-ES per K value; the pool sizes are few.
2. **Control.** Random search with the same evaluation budget, to verify CMA-ES adds value.
3. **Selection (Stage 2).** The 30 best distinct configurations from all runs race on the validation set: successive halving, eliminating by paired Wilcoxon (p < 0.01) against the current leader.
4. **Test (Stage 3).** Final evaluation once.

Why not Bayesian optimization: an evaluation is cheap (Track A ≈ 2 s of wall time per generation), and the objective is piecewise constant with plateaus, which suits GP surrogates poorly. BO's advantage is for expensive evaluations; TPE can serve as a secondary cross-check if desired.

**Objective (minimize):**

J = mean_i log(ms_i / ms_i^ORIG) + 0.5 · CVaR₀.₉₅(log(ms_i / ms_i^ORIG)) + penalty

The penalty is 1e3 · max(0, runtime/budget − 1), where the runtime budget is ≤ 2× ORIGINAL for Track A and ≤ 1.2× the Phase 1 rollout for Track B. Original makespans are precomputed once per instance.

### C. Dataset design

All instances are new seeds, disjoint from Phase 1 (1001, 2002, 3003, 4004).

| Set | Seeds | Composition | Size |
|---|---|---|---|
| Train | 10001 | 6 families (ER, layered, fork-join, tree, chains, stencil) × S/M/L × 400; durations: all except BIMODAL | 7,200 (minibatches of 1,500) |
| Validation | 20001 | All 8 families × S/M/L × 100 + XL 80 | 2,480 |
| Test (in-distribution) | 30001 | 6 training families × S/M/L × 250, training duration distributions | 4,500 |
| Test (out-of-distribution) | 30002 | Held-out families FFT and Gaussian (S/M/L × 250); BIMODAL durations on all families (1,200); XL 200 | 2,900 |
| Exact | 40001 | Tiny, 8 families × 100 | 800 |
| Curated | — | 10 scenarios | 10 |

**Leakage controls:**
- Seeds are disjoint across sets.
- Held-out families and the BIMODAL distribution never appear in training.
- Test is run once, after the configuration is frozen.
- Validation is used only for racing.

### D. Quantitative success criteria (test set, paired vs references; bootstrap CI and Wilcoxon p < 0.01 required)

References: ORIGINAL; **B0** = recommended Phase 1 configuration (CONFIRM GM 0.9887 vs ORIGINAL); **A0** = STATIC_ONLY + reach/max + batched (0.9926).

| Criterion | Minimum acceptable | Target | Stretch |
|---|---|---|---|
| Track A GM vs ORIGINAL (in-distribution) | ≤ 0.9926 (= A0) | ≤ 0.9895 (≈ CP-first) | ≤ 0.9870 |
| Track B GM vs B0 (in-distribution) | ≤ 1.000, CI upper < 1.002 | ≤ 0.9975 | ≤ 0.9950 |
| Track B GM vs ORIGINAL | ≤ 0.9887 | ≤ 0.9865 | ≤ 0.9840 |
| Mean improvement vs ORIGINAL (Track B) | ≥ 1.1% | ≥ 1.35% | ≥ 1.6% |
| Median improvement | ≥ 0 | ≥ 0 | > 0 |
| Losses vs ORIGINAL | ≤ 3% of instances | ≤ 2% | ≤ 1% |
| Worst single-instance ratio vs ORIGINAL | ≤ 1.06 | ≤ 1.04 | ≤ 1.03 |
| Every family: GM vs ORIGINAL | ≤ 1.000 | ≤ 0.998 | — |
| Every family: GM vs B0 (Track B) | ≤ 1.003 | ≤ 1.000 | — |
| Exact set: GM ms/OPT (Track B) | ≤ 1.0012 | ≤ 1.0009 | ≤ 1.0005 |
| Exact set: % optimal | ≥ 97% | ≥ 97.6% | ≥ 98.5% |
| Out-of-distribution (held-out families, BIMODAL, XL) | ≥ 50% of in-distribution gain, no family worse than B0 by > 0.3% | ≥ 75% | ≥ 100% |
| Runtime per run vs reference (single thread) | Track A ≤ 2× ORIGINAL; Track B ≤ 1.2× B0 | Track A ≤ 1×; Track B ≤ 1× | — |
| Curated sum | ≤ 563 | ≤ 561 | < 561 |

Rationale from measured headroom:
- Static-weight probing alone gave 0.9877 vs B0's 0.9896 on CONFIRM and 0.9873 vs 0.9945 on XL, so −0.25% vs B0 is plausible.
- The tiny-instance optimum leaves only 0.09% there, so gains must come from medium and large graphs.

### E. Implementation architecture

New classes go in `benchmark`; there are no changes to `backend` except new presets after approval.
- `ParameterSpace`: maps an unconstrained vector to `SchedulerConfig` (softmax simplices, logistic λ). Its inverse maps the default exactly.
- `CmaEs`: deterministic, seeded, with checkpoint serialization.
- `WeightOptimizer`: stages 0–3, the objective, minibatching, racing.
- Evaluation and statistics reuse `ExperimentRunner.evaluate`/`compare` and `StaticGraphAnalysis` caching. Analysis and ORIGINAL makespans are computed once per instance and kept in memory.
- **Storage.** `target/phase2/<run-id>/`:
  - `manifest.json`: seeds, git commit, parameter space, budgets;
  - `sobol.csv`;
  - `generations.csv`: generation, member, raw vector, config, J, GM, CVaR, runtime;
  - `checkpoint-<track>-<cell>.txt`: CMA state, RNG state, generation;
  - `validation.csv`, `test.csv`, `report.md`.
- **Tests:**
  - `ParameterSpaceTest`: round trip, simplex, default reproduced bit-exactly;
  - `CmaEsTest`: converges on sphere/Rosenbrock, deterministic per seed, resume from checkpoint gives an identical trajectory;
  - `WeightOptimizerTest`: objective determinism, minibatch stratification;
  - the golden test and the 20k differential check stay green.

### F. Autonomous execution roadmap (after approval)

| Step | Task | Depends on | Tests / acceptance | Estimated compute (12 threads) |
|---|---|---|---|---|
| 1 | `ParameterSpace`, `CmaEs`, `WeightOptimizer` skeleton, storage, checkpoints | — | Unit tests above green; golden + differential unchanged | — |
| 2 | Generate datasets (train/val/test/OOD/exact), precompute ORIGINAL makespans, B0/A0 references, exact optima | 1 | Manifest checksums; references reproduce Phase 1 within CI | ~10 min |
| 3 | Stage 0 Sobol sensitivity, Track A | 2 | Report of effects; decide fixed parameters by a pre-registered rule (total effect < 2% of the largest) | ~2 min |
| 4 | Track A CMA-ES (per K ∈ {1, 2, 3, 5}, 3 restarts × 150 generations) + random-search control | 3 | Checkpoint per generation; resume test | ~1 h |
| 5 | Track A racing on validation → A* | 4 | Racing log; A* meets "minimum" on validation | ~5 min |
| 6 | Track B grid {L 3, 5} × {depth 4, 8}, CMA-ES warm-started from A* (2 restarts × 60 generations per cell) | 5 | Checkpoints; cost penalty respected | ~3–4 h |
| 7 | Track B racing on validation → B* | 6 | B* meets "minimum" on validation | ~20 min |
| 8 | Final test evaluation of ORIGINAL, A0, B0, A*, B*, CP-first, exact set, curated | 7 | All §D metrics with CIs; run once | ~15 min |
| 9 | Report, recommendation and proposed presets (no default change without approval) | 8 | Tables with exact numbers, sample sizes, configs | — |

**Failure recovery:**
- Every stage is deterministic given its seed and checkpoint.
- A crash resumes from the last completed generation and reproduces the identical trajectory.
- Instance runs are guarded by event counters (a bounded loop raises an error, which is logged and counted as a failure, never silently skipped).
- Any stage that misses its acceptance criterion stops the pipeline with a report rather than proceeding.

**Deliverables:** the optimized configurations A* and B* with full provenance; the Phase 2 report; reproducible run directories; tests.

**Total estimated compute:** about 5–6 hours wall time on this 12-core machine. The dominant term is Track B, about 7 ms per run averaged over S/M/L.
