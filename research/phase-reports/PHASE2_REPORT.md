# Phase 2 Report — Weight Optimization (Fast and Quality tracks)

Scope: the approved Phase 2 plan in `PHASE1_REPORT.md` §11, Option B. `SchedulerConfig.DEFAULT` (the original scheduler) is unchanged and remains the default. Both optimized configurations are opt-in presets. No commits, pushes or paid compute were used. All raw outputs are in `target/phase2/run1/`.

## Summary table (independent test set, in-distribution, 4,500 instances; run once)

| | ORIGINAL (`DEFAULT`) | Phase 1 rollout B0 (`PHASE1_ROLLOUT`) | Fast Optimized A* (`FAST_OPTIMIZED`) | Quality Optimized B* (`QUALITY_OPTIMIZED`) | Best relevant baseline: CRITICAL_PATH_FIRST / batched |
|---|---|---|---|---|---|
| GM makespan vs ORIGINAL [95% CI] | 1.0000 | 0.9899 [0.9893, 0.9906] | 0.9907 [0.9901, 0.9914] | **0.9885 [0.9878, 0.9892]** | 0.9908 [0.9901, 0.9915] |
| GM makespan / lower bound | 1.0443 | 1.0337 | 1.0346 | **1.0323** | 1.0347 |
| Mean / median improvement vs ORIGINAL | 0 / 0 | +0.98% / 0 | +0.90% / 0 | **+1.12%** / 0 | +0.89% / 0 |
| Wins / ties / losses vs ORIGINAL | — | 1915 / 2542 / 43 | 1564 / 2620 / 316 | 1945 / 2495 / 60 | 1569 / 2604 / 327 |
| Worst single-instance ratio vs ORIGINAL | 1.000 | 1.028 | 1.084 | 1.028 | 1.084 |
| GM vs B0 [95% CI] | 1.0102 | 1.0000 | 1.0008 [1.0005, 1.0011] | **0.9986 [0.9984, 0.9988]** | — |
| OOD GM vs ORIGINAL: held-out families (1,500) / BIMODAL (1,200) / XL (200) | 1 / 1 / 1 | 0.9875 / 0.9845 / 0.9934 | 0.9892 / 0.9852 / 0.9875 | **0.9859 / 0.9828 / 0.9869** | 0.9892 / 0.9853 / 0.9874 |
| Exact set (800): GM ms/OPT, % optimal | 1.0091, 88.0% | 1.0011, 97.9% | 1.0030, 93.5% | **1.0010**, 97.8% | 1.0029, 93.6% |
| Curated sum (10 scenarios) | 563 | **561** | 572 | 562 | 576 |
| Single-thread ms per run, S / M / L / XL | 0.13 / 0.53 / 2.75 / 4.90 | 0.63 / 5.13 / 15.78 / 149.5 | 0.05 / 0.26 / 1.30 / 3.01 | 0.11 / 2.11 / 18.20 / 141.9 | 0.04 / 0.18 / 1.31 / 8.23 |

Sizes: S = 10–30 tasks, M = 31–150, L = 151–600, XL = 601–1,500.

## 1. Executive summary

- **Quality Optimized (B\*)** gives a small, statistically solid improvement over the Phase 1 rollout.
  - In-distribution test: GM 0.9986 vs B0, CI [0.9984, 0.9988], Wilcoxon p < 1e-4, 708 wins vs 361 losses. That is 0.9885 vs ORIGINAL (+1.12% mean improvement).
  - The gain holds out of distribution: 0.9985 / 0.9982 / 0.9934 vs B0 on held-out families / BIMODAL / XL.
  - Runtime is about equal to B0 overall (0.95×), but 1.15× on L graphs.
  - It meets every **minimum** criterion and several **target** criteria. It misses the main targets (GM vs B0 ≤ 0.9975, vs ORIGINAL ≤ 0.9865, mean improvement ≥ 1.35%).
- **Fast Optimized (A\*)** is 0.9907 vs ORIGINAL at about half of ORIGINAL's runtime. This meets the Track A minimum (≤ 0.9926) but misses the target (≤ 0.9895).
  - **Honest negative result:** A\*'s quality equals the existing CRITICAL_PATH_FIRST baseline with batched completions. The post-hoc paired GM is 0.9999 (467 wins / 480 losses on test; 1.0000 on OOD).
  - The optimizer rediscovered "critical path dominates" and found nothing beyond it.
  - A\* fails three generic criteria: 7.0% losses vs ORIGINAL (minimum ≤ 3%), a worst-case ratio of 1.084 (minimum ≤ 1.06), and a curated sum of 572 (minimum ≤ 563).
- **Main scientific finding.**
  - In both tracks, every search run converged to static weights ≈ (1, 0, 0), i.e. critical path only, with λ → 1.
  - The dynamic weights then act only as tie-breakers, and their landscape is flat: 30 race finalists with very different dynamic weights all scored 0.9886–0.9887 on validation.
  - CMA-ES did not beat random search at equal budget in either track.
- **Recommendation:** keep DEFAULT. Use `QUALITY_OPTIMIZED` where rollout cost is acceptable, since it strictly dominates `PHASE1_ROLLOUT`. Use `FAST_OPTIMIZED` (or CP-first) only where speed matters and occasional small-instance regressions are acceptable. See §15.

## 2. Exact implementation changes

All changes are additive. No file, class or method was moved, renamed or removed, and no existing signature changed.

| File | Change |
|---|---|
| `backend/SchedulerConfig.java` (Phase 1 file) | Added 3 `public static final` presets: `PHASE1_ROLLOUT`, `FAST_OPTIMIZED`, `QUALITY_OPTIMIZED`. They hold literal doubles, and `DEFAULT` is untouched. |
| `benchmark/InstanceGenerator.java` (Phase 1 file) | Added an overload `generate(seed, index, family, bucket, DurationDistribution... allowed)`. The original method delegates to it with all distributions, giving an identical random stream (tested). |
| `benchmark/ParameterSpace.java` (new) | 9 unconstrained logits u → `SchedulerConfig`: z = −5 + 10u, softmax over the 3 static weights, softmax over the 5 dynamic weights, logistic λ. Also `encode`/`decode`, freezing and restrict/expand. |
| `benchmark/CmaEs.java` (new) | Own deterministic CMA-ES: Hansen defaults, CSA, rank-1 and rank-μ updates, Jacobi eigendecomposition. No new dependency. |
| `benchmark/SobolSequence.java` (new) | Joe–Kuo Sobol sequence (up to 13 dimensions), Gray code, seeded digital shift. |
| `benchmark/WeightOptimizer.java` (new) | Stages: `calibrate`, `datasets`, `sobol`, `trackA`, `raceA`, `trackB`, `raceB`, `validation`, `test`, `timing`, `reproduce`. Also the objective, minibatching, replay-based resume, racing and reports. |
| `src/test/java/benchmark/` (new) | `ParameterSpaceTest` (7), `CmaEsTest` (5), `WeightOptimizerTest` (6), `PresetTest` (3). This test package mirrors `benchmark` so package-private helpers are testable without widening any API. |
| `README.md` | New section "Optimized Presets (Phase 2)" with reproduction commands. |

## 3. Optimized weights for both tracks

Static weights are (critical path, blocking power, reach). Dynamic weights are (open→block, open children, child importance, duration→ready, children→ready queue). λ is the static share.

| | Static | Dynamic | λ | Structure |
|---|---|---|---|---|
| ORIGINAL | 0.5, 0.25, 0.25 | 0.2, 0.1, 0.15, 0.3, 0.25 | 0.5 | legacy lookahead, K = 3, sequential |
| **A\* FAST_OPTIMIZED** (A-K2-rnd-r0#14) | 0.94195, 0.01438, 0.04367 | 0.01812, 0.14182, 0.32104, 0.09321, 0.42582 | 0.98932 | no lookahead, K = 2, batched, reach/max, id tie-break |
| **B\* QUALITY_OPTIMIZED** (B-L5-d8-cma-r1#5) | 1.00000, 2.6e-9, 3.2e-10 | ≈0, ≈0, 0.12654, 0.87346, ≈0 | 0.99893 | contested-only rollout, L = 5, depth 8, K = 5, any-gap, batched, reach/max, id tie-break |
| S\* (best K = 1, reported only) | 0.98527, 0.01472, 3.1e-6 | (unused at K = 1) | (unused) | K = 1, batched, reach/max |

- The full-precision values are in `SchedulerConfig.java` and in `target/phase2/run1/selected-{A,B,S}.properties`, together with the u vectors.
- `PresetTest` checks that each preset equals `ParameterSpace.decode(base, u)` exactly and produces identical schedules.
- Interpretation: at λ ≈ 0.99 the blended score is almost purely the static critical-path score. The dynamic term (≤ 1.1% of the score) only reorders tasks of near-equal critical path. In B\* it mostly favors tasks whose duration is short relative to the ready work ("duration→ready").

## 4. Search methods and computational budgets

**Objective (minimize).** J = mean log(ms/ms_ORIG) + 0.5 · CVaR₀.₉₅(log(ms/ms_ORIG)).
- ORIGINAL makespans are precomputed per instance.
- The planned runtime penalty was implemented as a constraint check but never applied, because it never binds: the Track A configurations run at or below ORIGINAL's cost, and the selected B\* runs at ≤ 1.15× B0 on every size, below the 1.2× budget. Rollout cost depends on L and depth, which were the grid axes, not on the weights; the weights affect it only through the number of contested decisions.

**Pipeline.**
1. **Stage 0, sensitivity** (§14). Pre-registered freeze rule: freeze a coordinate if its total effect is < 2% of the maximum.
2. **Track A.** CMA-ES per K ∈ {1, 2, 3, 5}.
   - Population 20, σ₀ = 0.3. Run 0, then one IPOP restart at population 40.
   - Each generation draws a fresh stratified minibatch of 1,500 training instances, shared by all members (common random numbers).
   - Control: random search over the same space with the same number of evaluations and the same minibatches as run 0.
3. **Pre-screen.** Per run, the top 15 distinct points by minibatch J, plus the final CMA mean, plus the default weights, are scored on the full training set (7,200). Candidates with identical training makespans are deduplicated.
4. **Racing.** The 30 best candidates race on the selection part of validation (1,466 instances).
   - Stages use 25% / 50% / 100% prefixes of a seeded permutation.
   - At each stage, drop candidates worse than the leader by paired Wilcoxon p < 0.01, then keep the better half.
   - The winner has the minimum J on the full subset.
5. **Track B.** Outer grid L ∈ {3, 5} × depth ∈ {4, 8}, with CMA-ES per cell warm-started from A\*'s weights.
   - Cell (5, 8) also has a second run from the B0 weights and a random-search control.
   - Pre-screen and racing as in Track A. The pool also contains the default and A\* weights in every cell (103 distinct candidates).
6. **Freeze.** The configurations were written to `frozen-configs.txt`, then the test ran once (guarded by `test.done`).

**Budgets: planned vs used.** A calibration run measured 1.9–3.8 s per Track A generation and 32 / 37 / 46 / 57 s per Track B generation (cells L3d4 / L3d8 / L5d4 / L5d8). At those rates the planned budget would have taken about 16 h, against the approved ceiling of about 5–6 h, so budgets were scaled before any search ran:

| | Planned | Used |
|---|---|---|
| Track A | 3 IPOP restarts × 150 generations per K | run 0 100 gen + restart 50 gen (K = 1: 60 + 40); 20 + 2 × 1,000/2,000 evals per K |
| Track B | 2 restarts × 60 generations per cell | 1 run × 30 generations per cell; cell (5, 8): + 1 run from B0 + random search |
| Sobol | 1,024 points | 1,024 points + Saltelli N = 256 (3,840 evaluations) |

**Compute used.** About 4.1 h of wall time on 12 local threads (from `stage-times.csv`):

| Stage | Time | Evaluations |
|---|---|---|
| Sobol | 293 s (+281 s for the discarded first design) | 3,840 |
| Track A | 2,307 s | 22,000 |
| raceA | 123 s (+118 s rerun) | — |
| Track B | ≈ 3,200 s before the kill + 4,383 s after | 3,600 |
| raceB | 3,778 s (planned: about 20 min) | — |
| validation + test | 186 s | — |

- That is 29,440 configuration evaluations on minibatches and about 50 M scheduler runs in total.
- The calibration run itself was not logged in `stage-times.csv`.

**Search results.**

| Run | Best minibatch J | Converged weights (mean) |
|---|---|---|
| A-K1 cma r0 / r1 | −0.00666 / −0.00716 | static ≈ (0.99, 0.01, 0) |
| A-K2 cma r0 / r1 | −0.00673 / −0.00706 | static (1, 0, 0), λ → 1 |
| A-K3 cma r0 / r1 | −0.00683 / −0.00705 | static (1, 0, 0), λ → 1 |
| A-K5 cma r0 / r1 | −0.00657 / −0.00752 | static (1, 0, 0), λ → 1 |
| B-L3-d4 / L3-d8 / L5-d4 / L5-d8 (cma r0) | −0.01049 / −0.01246 / −0.01003 / −0.01237 | static (1, 0, 0) in every cell |
| B-L5-d8 cma r1 (from B0) / random search | −0.01219 / −0.01228 | static (1, 0, 0) |

**CMA-ES vs random search (equal budget, same minibatches): no advantage.**

| Group | CMA-ES: best full-train J; validation GM | Random search |
|---|---|---|
| A-K1 | −0.00480; 0.9908 | −0.00481; 0.9908 |
| A-K2 | −0.00461; 0.9909 | −0.00479; 0.9909 |
| A-K3 | −0.00479; 0.9909 | −0.00505; 0.9912 |
| A-K5 | −0.00461; 0.9909 | −0.00491; 0.9914 |
| B-L5-d8 | −0.01132; 0.9886 | −0.01129; 0.9887 |

Once the critical-path weight dominates, the objective is a nearly flat plateau, so neither method can separate points within the minibatch noise. A\* itself came from a random-search run.

## 5. Dataset generation methodology and exact dataset sizes

- Generator: `InstanceGenerator`, unchanged from Phase 1 apart from the duration-restricting overload.
- 8 DAG families: ER, layered, fork-join, tree, chains, stencil, FFT, Gaussian elimination.
- 5 duration distributions: uniform 1–10, uniform 1–100, log-normal, unit, bimodal.
- Pressure (tasks per worker) ∈ {0.5, 1, 1.5, 3}; 2–64 workers.
- All seeds are new and disjoint from Phase 1. Instance ids embed the seed, and the `datasets` stage and `WeightOptimizerTest` verify that no id appears in two sets.

| Set | Seed | Composition | Instances | Checksum |
|---|---|---|---|---|
| Train | 10001 | 6 training families × S/M/L × 400; durations except BIMODAL | 7,200 | 47103e6bcf782814 |
| Validation | 20001 | 8 families × S/M/L × 100 (all durations) + 80 XL | 2,480 | 4926146860438778 |
| ↳ selection part | | training families, non-BIMODAL, S/M/L | 1,466 | |
| ↳ monitoring part | | FFT/Gaussian, BIMODAL, XL; never used for selection | 1,014 | |
| Test (in-distribution) | 30001 | 6 training families × S/M/L × 250, training durations | 4,500 | 7d40851bdedfa075 |
| Test (OOD) | 30002 | FFT + Gaussian × S/M/L × 250 (training durations) = 1,500; BIMODAL on all 8 families = 1,200; XL = 200 | 2,900 | 6b7caef6608e6838 |
| Exact | 40001 | tiny (2–10 tasks), 8 families × 100, solved to optimality | 800 | 5d68a5d212221bb9 |
| Timing | 50001 | 5 per family × size, S/M/L/XL | 160 | 3bb2761fec872854 |
| Curated | — | the 10 `ScenarioLibrary` scenarios | 10 | — |

**Deviation (conservative).** The plan said "validation is used only for racing". Racing used only the in-distribution part of validation. The held-out families, BIMODAL and XL instances in validation were reported but never used to choose anything, so the OOD test sets have no selection pressure from their distributions.

## 6. Training and validation results

Training-set GM vs ORIGINAL (7,200 instances): A\* 0.9904, B\* 0.9883. The best full-train J per Track B cell:

| Cell | L3-d4 | L3-d8 | L5-d4 | L5-d8 |
|---|---|---|---|---|
| Best train J | −0.00931 | −0.01139 | −0.00873 | −0.01132 |
| Train GM | 0.9889 | 0.9882 | 0.9892 | 0.9883 |

Depth 8 is clearly better than depth 4, while L = 3 and L = 5 are tied.

Racing:
- A: 30 → 12 (7 eliminated by Wilcoxon) → 6 → 6.
- B: 30 → 10 (10 eliminated) → 5 → 5.

Validation results (`validation.md`), as GM vs ORIGINAL [95% CI]:

| Config | Selection part (1,466) | Monitoring part (1,014) |
|---|---|---|
| A0 | 0.9942 | 0.9901 |
| B0 | 0.9899 [0.9887, 0.9911] | 0.9859 |
| S\* | 0.9908 | 0.9865 |
| A\* | 0.9907 [0.9895, 0.9920] | 0.9867 |
| B\* | 0.9887 [0.9874, 0.9899] | 0.9839 |
| CP-first / batched | 0.9909 | 0.9866 |

On the selection part, B\* vs B0 is 0.9988 [0.9984, 0.9991]. On validation, both winners met their minimum criteria before the test ran: A\* ≤ 0.9926 and B\* ≤ 0.9887.

## 7. Independent test-set results (in-distribution, 4,500 instances, run once)

See the summary table. Additional results:

- **vs B0:** A\* 1.0008 [1.0005, 1.0011] (429 wins / 1,104 losses); B\* 0.9986 [0.9984, 0.9988] (708 / 361), worst 1.081.
- **vs A0:** A\* 0.9961 [0.9957, 0.9965].

Per family, GM vs ORIGINAL:

| Family | B0 | A\* | B\* |
|---|---|---|---|
| ER | 0.9920 | 0.9941 | 0.9919 |
| Layered | 0.9919 | 0.9938 | 0.9916 |
| Fork-join | 0.9919 | 0.9947 | 0.9918 |
| Tree | 0.9840 | 0.9807 | 0.9787 |
| Chains | 0.9944 | 0.9954 | 0.9941 |
| Stencil | 0.9853 | 0.9857 | 0.9832 |

- B\* vs B0 is ≤ 1.000 in every family; the worst is ER at 0.9999 and the best is Tree at 0.9946.
- **By size**, B\* vs B0: S 0.9999, M 0.9987, L 0.9972. The gain comes from larger graphs. A\* is worse than B0 on S (1.0039) and better on L (0.9980).
- **By workers**, B\* vs ORIGINAL: 2–3 → 0.9858, 4–8 → 0.9894, 9–20 → 0.9929, 21–64 → 0.9970.
- **By duration distribution**, B\* vs ORIGINAL: U1–10 0.9870, U1–100 0.9859, LN 0.9868, unit 0.9943.

Full breakdowns, including pressure, are in `target/phase2/run1/test.md`.

**Success criteria** (`PHASE1_REPORT.md` §11.D; ✔ met, ✘ missed). Rows not marked "Track B" are applied to both tracks.

| Criterion | A\* measured | A\* level | B\* measured | B\* level |
|---|---|---|---|---|
| Track A GM vs ORIGINAL ≤ 0.9926 / 0.9895 / 0.9870 | 0.9907 | minimum ✔, target ✘ | — | — |
| Track B GM vs B0 ≤ 1.000 (CI < 1.002) / 0.9975 / 0.9950 | — | — | 0.9986 [.., 0.9988] | minimum ✔, target ✘ |
| Track B GM vs ORIGINAL ≤ 0.9887 / 0.9865 / 0.9840 | — | — | 0.9885 | minimum ✔, target ✘ |
| Mean improvement (Track B) ≥ 1.1 / 1.35 / 1.6% | — | — | 1.12% | minimum ✔, target ✘ |
| Median improvement ≥ 0 / ≥ 0 / > 0 | 0 | target ✔, stretch ✘ | 0 | target ✔, stretch ✘ |
| Losses vs ORIGINAL ≤ 3 / 2 / 1% | 7.02% | **minimum ✘** | 1.33% | target ✔, stretch ✘ |
| Worst ratio ≤ 1.06 / 1.04 / 1.03 | 1.084 | **minimum ✘** | 1.028 | stretch ✔ |
| Every family GM vs ORIGINAL ≤ 1.000 / 0.998 | max 0.9954 | target ✔ | max 0.9941 | target ✔ |
| Every family GM vs B0 ≤ 1.003 / 1.000 (Track B) | — | — | max 0.9999 | target ✔ |
| Exact GM ms/OPT ≤ 1.0012 / 1.0009 / 1.0005 (Track B) | — | — | 1.0010 | minimum ✔, target ✘ |
| Exact % optimal ≥ 97 / 97.6 / 98.5 (Track B) | — | — | 97.8% | target ✔, stretch ✘ |
| OOD: share of in-distribution gain ≥ 50 / 75 / 100%, no family > 0.3% worse than B0 | 116–160% | stretch ✔ | 114–150%; worst family vs B0 0.9999 | stretch ✔ |
| Runtime (single thread): A ≤ 2× / 1× ORIGINAL; B ≤ 1.2× / 1× B0 | 0.38–0.61× | target ✔ | 0.95× overall (S/M/L mean); L 1.15×, XL 0.95× | target ✔ overall; L only minimum |
| Curated sum ≤ 563 / 561 / < 561 | 572 | **minimum ✘** | 562 | minimum ✔, target ✘ |

## 8. Out-of-distribution performance (2,900 instances)

| Subset | n | B0 vs ORIG | A\* vs ORIG | B\* vs ORIG | B\* vs B0 [95% CI] | A\* vs B0 |
|---|---|---|---|---|---|---|
| Held-out families FFT + Gaussian | 1,500 | 0.9875 | 0.9892 | 0.9859 | 0.9985 [0.9976, 0.9992] | 1.0018 |
| BIMODAL durations (all families) | 1,200 | 0.9845 | 0.9852 | 0.9828 | 0.9982 [0.9976, 0.9987] | 1.0007 |
| XL (601–1,500 tasks) | 200 | 0.9934 | 0.9875 | 0.9869 | 0.9934 [0.9899, 0.9963] | 0.9941 |

- Log-scale share of the in-distribution gain retained:
  - B\*: 123% (held-out families), 150% (BIMODAL), 114% (XL);
  - A\*: 116%, 160%, 135%.
- So the learned weights do not overfit the training families. Critical-path dominance transfers well.
- On XL, both optimized configurations clearly beat B0. B0's original weights are weakest on large graphs.
- B\* is ≤ 1.000 vs B0 on every OOD family (FFT 0.9965, Gaussian 0.9999).
- B\*'s worst OOD ratio vs ORIGINAL is 1.043 (BIMODAL chains, 24 tasks). A\*'s is 1.120 (Gaussian, 27 tasks, 2 workers).

## 9. Comparisons against all relevant baselines (in-distribution test, GM vs ORIGINAL)

| Baseline | GM | Note |
|---|---|---|
| B\* QUALITY_OPTIMIZED | **0.9885** | best overall |
| B0 PHASE1_ROLLOUT | 0.9899 | |
| A\* FAST_OPTIMIZED / S\* | 0.9907 / 0.9907 | |
| CRITICAL_PATH_FIRST (batched / sequential) | 0.9908 / 0.9912 | best simple rule; A\* vs it: 0.9999 (467/3553/480), OOD 1.0000 |
| A0 (Phase 1 low-cost) | 0.9946 | |
| HIGHEST_STATIC_SCORE / STATIC_ONLY | 0.9953 | |
| LARGEST_DOWNSTREAM_WORK | 0.9943 | |
| NO_LOOKAHEAD | 0.9973 | |
| FIFO / LPT / SPT | 1.0630 / 1.0701 / 1.0906 | |

The A\* vs CP-first comparison was computed post hoc from `test.csv` after the test, and was not used for any decision.

## 10. Optimality-gap analysis

**Exact set (800 tiny instances, all solved to optimality):**

| Config | GM ms/OPT | % optimal | Worst ms/OPT |
|---|---|---|---|
| ORIGINAL | 1.0091 | 88.0% | 1.286 |
| A\* | 1.0030 | 93.5% | 1.226 |
| B0 | 1.0011 | 97.9% | 1.202 |
| B\* | **1.0010** | 97.8% | 1.202 |

B\* vs B0 on the tiny set is a wash: there is only 0.1% headroom left, as Phase 1 predicted.

**Lower-bound gap on the test set**, GM ms/LB with LB = max(critical path, work/workers):

| Config | ORIGINAL | B0 | A\* | B\* |
|---|---|---|---|---|
| GM ms/LB | 1.0443 | 1.0337 | 1.0346 | 1.0323 |
| LB gap closed vs ORIGINAL | — | 24% | 22% | 27% |

Most of the remaining gap is probably LB slack, not scheduler loss: the exact set shows ORIGINAL within 0.9% of the optimum while its gap to the LB is about 4%.

## 11. Statistical confidence and uncertainty

- All GM ratios are paired, with 95% bootstrap CIs over instances, and use two-sided paired Wilcoxon signed-rank tests.
- Every headline comparison (A\*, B\* vs ORIGINAL, B0 and A0, in- and out-of-distribution) has p < 1e-4, with three exceptions: B\* vs B0 on held-out families (p = 0.0001), A\* vs B0 on XL (p = 0.0005) and A\* vs B0 on BIMODAL (p = 0.0026, CI [0.9999, 1.0014], so not significant). With about 20 headline tests, a Bonferroni threshold of 0.01/20 = 5e-4 holds for all except the last.
- **Selection noise.**
  - The top 30 race entrants differ by < 0.0002 in train J and by 0.9886–0.9887 in validation GM.
  - The specific B\* weights are one point on a plateau, not a unique optimum. Any finalist would likely perform within ±0.0002 GM.
  - Validation → test shift was small: B\* 0.9887 → 0.9885, A\* 0.9907 → 0.9907.
- **Ties.** Most instances are ties: 55% for B\* vs ORIGINAL and 76% vs B0. Mean and median improvements reflect this, with a median of 0 everywhere.
- **Generalization uncertainty.** All instances are synthetic, from one generator. The OOD sets vary family, duration distribution and size, but not the generator itself (§17).

## 12. Runtime and memory comparisons

Single thread, median of 3 repetitions after warm-up, on the 160 timing instances. Static analysis is reused; its one-off cost is listed separately.

| Config | S ms | M ms | L ms | XL ms | Alloc per run L / XL (MB) |
|---|---|---|---|---|---|
| ORIGINAL | 0.13 | 0.53 | 2.75 | 4.90 | 1.33 / 4.64 |
| A\* FAST | 0.05 | 0.26 | 1.30 | 3.01 | 0.59 / 1.78 |
| CP-first / batched | 0.04 | 0.18 | 1.31 | 8.23 | 0.94 / 3.21 |
| B0 | 0.63 | 5.13 | 15.78 | 149.5 | 8.09 / 65.5 |
| B\* QUALITY | 0.11 | 2.11 | 18.20 | 141.9 | 7.94 / 63.6 |
| (StaticGraphAnalysis.of, once) | 0.24 | 0.63 | 1.24 | 5.52 | |

- **A\*** runs at 0.38–0.61× ORIGINAL's time and allocates less than half as much. It is also 2.7× faster than CP-first on XL.
- **B\*** is cheaper than B0 on S and M: with λ ≈ 1 fewer decisions are "contested", so fewer rollouts run. It is 1.15× B0 on L and 0.95× on XL.
- **B\* vs ORIGINAL:** about 1× on S, 4× on M, 6.6× on L and 29× on XL. It is unsuitable where per-decision latency matters on graphs of 1,000+ tasks.
- **Peak heap during the XL pass:** 94 MB (A\*), 103 MB (ORIGINAL), 153 MB (B0), 191 MB (B\*). This metric depends on GC timing and is indicative only. Allocation per run is the reliable memory metric, and on it B\* ≈ B0.
- **Optimization cost:** see §4 (about 4.1 h, about 50 M scheduler runs, all local).

## 13. Worst-case regressions and failure examples

- **A\*.** The worst cases are small, few-worker instances where pure critical-path order starves a worker. The two worst in-distribution cases:
  - TREE-SMALL-30001-136: 14 tasks, 2 workers, makespan 107 → 116 (1.084);
  - LAYERED-SMALL-30001-205: 26 tasks, 2 workers, 620 → 661 (1.066).

  The worst OOD case is GAUSSIAN-SMALL-30002-56 (332 → 372, 1.120). Losses vs ORIGINAL are concentrated at 2–3 workers (235 of 316) and in S/M graphs. These cases cause A\*'s three criterion failures.
- **B\*.** The worst in-distribution case is the same TREE-SMALL-30001-136, at 107 → 110 (1.028), which equals B0's worst case. The worst OOD case is CHAINS-SMALL-30002-1022 under BIMODAL (161 → 168, 1.043). Against B0, B\*'s worst is 1.081 (in-distribution) and 1.049 (OOD). Losses vs B0 are 361 in-distribution vs 708 wins.
- **Curated scenarios.** B\* loses 1 time unit on scenario #4 vs B0 (43 vs 42). A\* loses on #1, #4 and #5 vs ORIGINAL (sum 572 vs 563), the same pattern as every no-lookahead rule (CP-first 576, STATIC_ONLY 575).

Instance lists are in `target/phase2/run1/test.md`.

## 14. Sensitivity analysis of the learned weights

**Stage 0** (`sensitivity.md`): Track A space, K = 3, 1,000 stratified training instances, 1,024 Sobol points, and a Saltelli/Jansen design with N = 256.

| Coordinate | Total effect S_T [95% CI] | Share of max | First order (binned) |
|---|---|---|---|
| s.blockingPower | 0.496 [0.400, 0.611] | 1.00 | 0.345 |
| s.criticalPath | 0.329 [0.249, 0.425] | 0.66 | 0.150 |
| s.reach | 0.222 [0.166, 0.282] | 0.45 | 0.058 |
| λ | 0.137 [0.092, 0.196] | 0.28 | 0.055 |
| d.durationToReady | 0.091 [0.062, 0.126] | 0.18 | 0.052 |
| d.openToBlock | 0.053 [0.028, 0.084] | 0.11 | 0.007 |
| d.childImportance | 0.049 [0.025, 0.076] | 0.10 | 0.007 |
| d.openChildren | 0.017 [0.005, 0.032] | 0.035 | 0.004 |
| d.childrenToReadyQueue | 0.008 [0.002, 0.016] | 0.016 | 0.003 → **frozen** (borderline) |

- **Static weights carry the variance.** Blocking power has the largest effect because giving it weight is harmful: the best region has critical path ≈ 1. The dynamic weights matter little, and only when λ is small.
- **At the optimum the landscape is flat in the dynamic weights.**
  - The Track B race entrants include dynamic vectors as different as (0, 0, 0.13, 0.87, 0), (0, 0.49, 0.27, 0.20, 0.04) and (0.38, 0.08, 0.15, 0, 0.39), all with validation GM 0.9886–0.9887.
  - Across entrants with λ from 0.74 to 0.9997, validation J spans only −0.01083 to −0.01115.
  - So the learned dynamic weights should not be interpreted as meaningful individually. The only robust findings are critical path ≫ blocking power and reach, and λ close to 1.
- **The frozen coordinate** (childrenToReadyQueue) was frozen only in Track A. Track B searched all 9 coordinates.
- **Design flaw found and fixed.** The first Stage 0 drew matrix B from consecutive Sobol points, which are a fixed digital shift of A. That made the critical-path total effect come out as 0. The design was rebuilt with an independent pseudo-random B and rerun. The flawed output is preserved in `target/phase2/run1/invalid-sobol-design-1/`, and no decision used it.

## 15. Final recommendation: Fast Optimized versus Quality Optimized

1. **Default:** keep `SchedulerConfig.DEFAULT`, as required. Neither preset is promoted automatically.
2. **If quality matters and rollout cost is acceptable: use `QUALITY_OPTIMIZED`.**
   - It replaces `PHASE1_ROLLOUT`: GM ≤ B0 in every family, size bucket and OOD subset, at equal overall runtime.
   - The in-distribution worst case vs ORIGINAL is the same (1.028). Out of distribution, B\*'s worst case is slightly higher than B0's (BIMODAL 1.043 vs 1.030; held-out families 1.028 vs 1.026).
   - The gain over B0 is small (0.14%) but consistent and significant.
   - Its gain over ORIGINAL is 1.15% GM, with 1.3% losses and a worst ratio of 1.028.
3. **If speed matters: `FAST_OPTIMIZED` is acceptable, but it is effectively CRITICAL_PATH_FIRST with batched completions.**
   - It gives +0.9% at half the runtime of ORIGINAL.
   - It does not meet the loss-rate, worst-case or curated criteria: 7% of instances get worse than ORIGINAL, by up to 8–12% on small 2-worker instances.
   - If a no-lookahead mode is shipped, documenting it as "critical-path priority" is more honest than "learned weights". The learning added no measurable quality over that rule.
4. **Between the two:** B\* is 0.22% better than A\* in GM on the test set, with 5× fewer losses vs ORIGINAL and a far better worst case, at 2× (S), 8× (M), 14× (L) and 47× (XL) A\*'s single-thread runtime. A\* is better than B0 only on XL graphs.

## 16. Reproduction instructions

Requirements: Java 21 and Maven (offline repository populated). From the repository root:

```
mvn -o test                                   # 201 tests incl. golden traces and preset checks
mvn -o compile
CP=target/classes                             # on Git Bash/Windows: CP="$(cygpath -m $PWD/target/classes)"
for s in datasets sobol trackA raceA trackB raceB validation test timing reproduce; do
  java -Xmx4g -cp "$CP" benchmark.WeightOptimizer $s target/phase2/<new-run-dir> 12
done
```

- Every stage is deterministic given the seeds in `manifest.json`.
- Search stages append each evaluation to `evaluations.csv`. A re-run replays logged generations and reproduces the identical trajectory: this was tested, and it was used for real after the kill described below.
- `test` refuses to run twice in the same directory.
- `reproduce` reruns every 10th test instance with the shipped presets in a fresh JVM. Result: 450 of 450 identical for each of ORIGINAL, PHASE1_ROLLOUT, FAST_OPTIMIZED and QUALITY_OPTIMIZED (`reproduce.md`).
- Using a preset: `new Controller(tasks, workerNames, printShutdownSummary, SchedulerConfig.QUALITY_OPTIMIZED)`.
- Run log of this execution:
  - The Track B process was killed once by the OS low-memory reaper while running with `-Xmx8g`. It was resumed with `-Xmx4g`. Logged generations replayed with identical values, and only cell (5, 8) was recomputed. The killed log is `trackB.part1-killed.log`.
  - The first `raceA` report mislabelled CMA-ES rows as random search. The label was fixed and raceA rerun; the selection was identical (`selection-A.first-run-mislabelled.md` is kept).

**Verification performed:**
- full suite 201/201 (180 from Phase 1 + 21 new);
- golden traces of DEFAULT unchanged;
- `PresetTest`: presets = selections, DEFAULT unchanged;
- the `test` stage checks that the presets equal the frozen selections before evaluating;
- reproducibility 1,800 of 1,800;
- test-set access only in the `datasets` (checksums and disjointness), `test` and `reproduce` stages. All selections (`selected-A` 01:32, `selected-B` 10:53) predate the test (10:55–10:58).
- `git status`: no tracked file was changed in Phase 2. The Phase 2 edits are to the two untracked Phase 1 files listed in §2, the new files, and README.md. No commits.

## 17. Remaining limitations and possible future improvements

- **Small gains.** The improvement over Phase 1 is 0.14% GM. Most of the Phase 2 value is confirming that critical-path dominance is the right static score and that the remaining weight space is a plateau.
- **Budget reduced** from the plan (§4).
  - Track A: 14,800 CMA-ES evaluations (+ 7,200 random search), against 84,000 planned (3 IPOP restarts × 150 generations at populations 20/40/80, × 4 K values).
  - Track B: 3,600 evaluations, against 9,600–14,400 planned (2 × 60 generations × 4 cells, with or without population doubling). Given the flat landscape and the random-search parity, more budget is unlikely to help much. That is an inference, not a measurement.
- **Track A misses** the loss-rate, worst-case and curated criteria. These come from no-lookahead scheduling itself, not from the weights: every no-lookahead variant has them.
- **Synthetic data only.** One generator, P|prec|Cmax with known durations. No real traces and no duration uncertainty.
- **B\* runtime on huge graphs** (29× ORIGINAL on XL). A size-adaptive policy (rollout only below about 600 tasks, A\* above) would likely dominate both presets on the measured data. This was not tested and is outside the approved scope.
- **Peak-heap numbers** are GC-dependent. Allocation per run is the reliable memory metric.
- **Possible future work:**
  - CP-tie-breaking rules designed directly, instead of weights;
  - better list-scheduling priorities (e.g. HEFT-style upward rank with worker-availability lookahead);
  - a learned size gate for the rollout;
  - evaluation under duration noise.
