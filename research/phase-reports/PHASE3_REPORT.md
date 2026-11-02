# Phase 3 Report — When and why Critical Path First fails, and what it costs to fix

Research and diagnostics only. No production code, default configuration, scoring logic or public interface was changed. All experimental code is in one new class, `benchmark/CriticalPathStudy.java`, which nothing in `backend` calls, plus its test `CriticalPathStudyTest`. Raw outputs are in `target/phase3/run1/`.

**Terms.**
- **CP-first:** the existing `CRITICAL_PATH_FIRST` baseline with batched completions.
- **B\*:** `QUALITY_OPTIMIZED`.
- **A\*:** `FAST_OPTIMIZED`.
- **OPT:** the optimal schedule (idle time allowed).
- **ND:** the best non-delay schedule, which is the best that *any* list-scheduling priority can produce.
- **Pilot:** an experimental rollout. At a contested pick (more ready tasks than free workers), CP-first's choice is compared with the best L−1 tasks it would defer. Each is started and the schedule is continued with CP-first (to the end, or for d events, then estimated by a lower bound). The best one is kept.

## 1. Executive summary

**CP-first is already very strong, and the remaining headroom is small.**
- On 891 exactly solved small graphs (10–18 tasks), CP-first is optimal on 78.6% of instances and averages 0.53% above the optimum (GM).
- Across 2,700 exploration instances, it is ≥ 1% worse than the best observed schedule on 8.9% of instances and ≥ 5% worse on 0.6%.
- The best method found improves it by only **0.27%** GM on independent test data. B\* improves it by 0.23%.

**Where it fails.** Failures concentrate in:
- **small graphs**: 16.7% of S instances have a gap ≥ 1%, vs 2.6% of L;
- **2–3 workers**: 14.3%, vs 1.4% at 21–64;
- **balanced load**, where the critical path ≈ work/workers: 15% in the middle quintile vs 0.2% at low load;
- **non-unit durations**: unit durations have essentially no failures (0.3%).

**Why it fails.**
- The gap is almost entirely a **priority** problem, not a structural one: 94% of the log gap to the optimum is closable by changing priorities alone, and only 0.03% needs inserted idle time.
- The dominant mechanism, seen in the case studies, is **worker starvation**. With one free worker and the others busy, CP-first starts a long critical task. A shorter, slightly less critical task would have released successors in time for the next worker to free up, and that worker instead sits idle.
- CP-first lacks *when* other workers become free and *when* deferred work would be released.
- The fix is nearly always local: 90% of the best single fixes swap out exactly the boundary task (the last one CP-first starts).

**Predictability.**
- *Which* graphs fail is moderately predictable from cheap graph-level features: size and worker count have AUC ≈ 0.70.
- *Which decision* fails is barely predictable from cheap local features: the best AUC is 0.64.
- **The near-tie hypothesis is refuted.** Exact CP ties are almost never the problem: they are improvable 5–7% of the time, against 18% on average. Most of the lost makespan sits at decisions where the CP gap is above 5%.

**Selective lookahead works, but graph-level gating is what makes it cheap.** Rollout gains concentrate on small graphs, where rollouts are also cheap.
- The existing B\*, used only on graphs with ≤ 150 tasks and A\* otherwise (a production-only combination, no new algorithm), keeps **82–90% of B\*'s gain** on every test set. It costs the same as A\* on large graphs: 1.5 ms vs 35 ms (L) and 3.1 ms vs 331 ms (XL), single-threaded.
- An experimental depth-8 pilot used only when workers ≤ 8 matched B\*'s quality exactly (GM 1.0000 vs B\*). In the study code base it costs 25× CP-first on L graphs, against 493× for the full-depth pilot.

**Targeted priority changes do not help.** Six tie-breaking rules and a mechanism-derived "next-completion-aware CP" rule all stayed within ±0.02% of CP-first, and the latter had a worst case of 1.077. A random multi-start of 32 perturbed CP schedules was dominated by the pilot.

**Recommendation (for approval; nothing implemented in production).**
1. If a cheaper Quality mode is wanted: a **size-gated preset selection** (B\* for small graphs, CP-like A\* otherwise). It reuses existing code only.
2. A **graph-level rollout gate** inside the rollout (workers or size), plus engineering work on the rollout's speed.
3. Otherwise, CP-first (or A\*) is sufficient. More algorithmic complexity is justified only where the ≤ 0.5% average gain on small, few-worker graphs matters.

## 2. Failure rates and severity of CP-first

**Exploration set:** seed 81001, 2,700 instances, 6 core families × S/M/L × 150, four duration distributions. "Best observed" is the minimum over 18 schedulers. It is not an optimum.

| Comparison | Worse | Worse ≥ 1% | Worse ≥ 5% | GM ratio | p99 ratio | Max ratio |
|---|---|---|---|---|---|---|
| CP-first vs best observed | 31.6% | 8.9% | 0.6% | 1.0028 | 1.038 | 1.117 |
| CP-first vs best non-rollout heuristic | 20.8% | 4.1% | 0.4% | 1.0015 | 1.027 | 1.100 |
| CP-first vs B\* | 27.1% | 7.0% | 0.5% | 1.0022 | 1.033 | 1.117 |
| CP-first vs lower bound | 48.9% | 37.4% | 22.3% | 1.0358 | 1.269 | 1.500 |
| B\* vs CP-first (B\* worse) | 2.3% | 0.2% | 0.0% | 0.9978 | 1.002 | 1.051 |

**The lower-bound gap is not a measure of suboptimality.** On the exact set:

| Ratio (891 solved instances) | GM |
|---|---|
| CP-first / lower bound | 1.0334 |
| optimum / lower bound | 1.0280 |
| CP-first / optimum | 1.0053 |

The optimum itself exceeds the bound on 26% of instances. So 84% of the apparent "gap" is slack in the bound, not scheduler loss.

**Exact set:** seed 81002, 900 instances with 10–18 tasks, 891 solved with a node limit of 2·10⁷.

| | GM ms/OPT | % optimal | GM ms/ND | Worst ms/OPT |
|---|---|---|---|---|
| CP-first | 1.0053 | 78.6% | 1.0049 | 1.126 |
| ORIGINAL | 1.0173 | 64.2% | 1.0170 | 1.537 |
| A\* | 1.0054 | 78.1% | 1.0050 | 1.126 |
| B\* | 1.0015 | 89.7% | 1.0012 | 1.062 |
| pilot L8, full depth | 1.0013 | 90.9% | 1.0009 | 1.062 |
| ND (best list schedule) | 1.0003 | 98.7% | 1.0000 | 1.062 |

## 3. Characteristics of poorly scheduled graphs

The table shows the share of instances with CP-first ≥ 1% worse than the best observed (exploration set):

| Factor | Low-failure end | High-failure end |
|---|---|---|
| Size | L (151–600): 2.6% | S (10–30): 16.7% |
| Workers | 21–64: 1.4% | 2–3: 14.3% |
| Load W/(m·CP), by quintile | Q1 (≤ 0.52, CP-bound): 0.2% | Q3 (≈ 1, balanced): 15.4%; Q5 (≥ 1.5, work-bound): 5.9% |
| CP/LB, by quintile | Q5 (CP is the bound): 2.0% | Q3 (0.86–1.0): 15.4% |
| Durations | UNIT: 0.3% | UNIFORM 1–100: 17.0%; UNIFORM 1–10: 12.5% |
| Duration CV, by quintile | Q1 (CV 0, unit): 0.2% | Q4 (0.57–0.94): 15.7% |
| Pressure | 0.5 (many workers): 0.4% | 1.0: 13.3% |
| Family | Layered 5.6%, Tree 6.2% | Fork-join 15.6%, Stencil 10.7% |

**Interpretation.**
- When the critical path dominates (load ≪ 1), following it is nearly optimal.
- When work dominates heavily (load ≫ 1), almost any non-delay schedule packs to about W/m.
- CP-first fails in between: path length and idle time both matter, and with few workers each idle worker is a large share of capacity.
- Unit durations remove the long-vs-short trade-off. This is consistent with the classical results that CP-type rules are optimal or near-optimal for unit-time tasks (Hu; Coffman–Graham for 2 workers).

## 4. Concrete examples of suboptimal decisions

Ten worst CP-first/OPT instances, at most two per family: `target/phase3/run1/cases.md`. Each entry contains the full DAG with durations, critical paths and slack, the CP-first Gantt chart, the ready set at the first improving decision, the swap, and the resulting Gantt chart. The method:
- CP-first is replayed.
- At each contested batch, every swap of one started task (the 8 lowest-ranked) for one deferred task (the 4 best) is tried, continuing with CP-first.
- The first batch where a swap lowers the final makespan is "the first improving decision".

**This is causal with respect to the CP-first continuation:** changing only that decision improves the outcome. It does not imply that the optimum changes that decision.

**Example 1: worker starvation** (LAYERED-SMALL-81002-231; 13 tasks, 3 workers, CP-first 276, ND 258, OPT 246)
- At t = 7 one worker is free and two tasks are ready:
  - T4: cp 222, duration 77, slack 0;
  - T5: cp 190, duration 29.
- CP-first starts T4. T5 then waits until t = 37, so T7 (83 units, 3 descendants) starts only at 66, and worker W2 idles from 49 to 84.
- Starting T5 first gives 258, which equals the best list schedule.
- Missing information: T4 can absorb a 29-unit delay with little cost, because the schedule is idle-limited, not path-limited. Meanwhile the other workers will run out of work unless T5's successors are released.
- The optimum (246) is lower still and needs a deliberate idle period, which no list rule can produce.

**Example 2: the same mechanism with 2 workers** (STENCIL-SMALL-81002-191; 10 tasks, CP-first 31, OPT 29)
- At t = 8, CP-first prefers T9 (cp 17, duration 10) over T6 (cp 14, duration 2, unlocks T7).
- Starting T6 first lets the other worker take T7 at t = 13 instead of t = 15. The swap reaches the optimum (29).

**Example 3: same mechanism** (LAYERED-SMALL-81002-225; 15 tasks, 2 workers, CP-first 147, OPT 138)
- At t = 57, T9 (cp 55, duration 40) is chosen over T8 (cp 45, duration 15).
- T8's successor T10 would otherwise reach the second worker only at 112. One swap gives 139.

**Example 4: packing at the end of a work-bound schedule** (CHAINS-SMALL-81002-314; 2 workers, LB = W/m = 299, CP-first 319, OPT 299)
- At t = 0, CP-first starts T7 (cp 222) and T5 (cp 114). Swapping T5 for T2 (cp 83) gives 304.
- The loss comes from how the remaining chains pack onto 2 workers at the end. CP-first has no notion of packing.

**Example 5: one swap reaches the optimum** (CHAINS-SMALL-81002-122; 5 workers, CP-first 13, OPT 12)
- At t = 0 the leaf T7 (cp 9) is started instead of T5 (cp 6, but it heads a two-task chain).

**How far one swap gets.**
- Of the 10 worst cases, a single swap reaches the optimum in 3 and the best list schedule in 1 more.
- The rest need several changed decisions, which is why per-decision rules are weak and multi-decision rollouts work.
- Across all 192 exact-set instances with CP-first > ND, one swap reaches ND in 62.5%.

## 5. Statistical relationships between graph features and failures

The instance-level association is with the label "CP-first ≥ 1% worse than best observed" (8.9% positive). The full table, including Spearman correlations with the gap and within-family medians, is in `explore.md`.

| Feature | Spearman (gap) | Within-family median | AUC |
|---|---|---|---|
| workers | −0.27 | −0.25 | 0.30 (i.e. 0.70 for "fewer workers") |
| n | +0.02 | −0.05 | 0.29 (0.71 for "smaller") |
| cp_bound (CP ≥ W/m) | −0.29 | −0.35 | 0.39 |
| duration CV | +0.27 | +0.25 | 0.55 |
| pressure | +0.22 | +0.23 | 0.60 |
| load | +0.22 | +0.27 | 0.58 (non-monotone: peaks at ≈ 1) |
| log10 # critical paths | −0.21 | −0.28 | 0.40 |
| near-critical fraction, edge density, in/out degree, joins/forks | \|ρ\| ≤ 0.15 | — | 0.47–0.56 |

- The best single-feature rules are weak classifiers. "n ≤ 30" has precision 16.7% and recall 62.5%; "workers ≤ 2" has 15.4% and 66.7%.
- These are correlations. Size and worker count are confounded: small graphs get few workers through the pressure rule.
- The decision-level counterfactuals (§4, §6) are the causal evidence.

## 6. Evidence on whether failures can be predicted cheaply

**Decision level** (exploration: 2,040 instances, 87,383 contested batches; exact set: 4,570 batches).
- 17.7% of contested batches have an improving swap.
- The best cheap features, by AUC:

| Feature | AUC |
|---|---|
| deferred task's CP relative to the estimated remaining time ("competing near-critical path") | 0.64 |
| exact CP tie (*negative*: ties are rarely harmful) | 0.41 |
| a worker frees soon (next completion relative to task duration) | 0.43 |
| single free worker | 0.42 |

- Static attributes of the competing tasks (shorter, more children, unlocks sole-parent children, more downstream work) have AUC 0.49–0.53, i.e. no signal. The starvation mechanism depends on the interaction between future release times and worker availability, which only simulation captures.

**Near-tie (gap) triggers, exploration set:**

| Trigger | Batches fired | Share of total lost makespan caught |
|---|---|---|
| CP gap ≤ 0 (exact ties) | 25.5% | 11.8% |
| CP gap ≤ 0.05 | 65.1% | 45.5% |
| CP gap ≤ 0.2 | 83.0% | 78.0% |

A trigger that catches most of the lost makespan has to fire on most decisions.

**Graph level.** Size and worker count predict *where* the gain is (AUC ≈ 0.70). That suffices for an efficient policy, because cost grows with size: gating by size removes most of the cost and little of the gain (§7).

**Conclusion.** Cheap signals cannot reliably pick the individual bad decisions. They can pick the graphs where lookahead pays.

## 7. Selective lookahead opportunities

**Development set:** seed 81003, 1,860 instances (S/M/L × 100 and XL × 10 per core family), 27 variants (`dev.md`).

**Pre-registered selection rules** (fixed in code before the stage ran):
- **S1:** the fewest simulated starts with GM ≤ 1.0005 × the full pilot.
- **S2:** the best GM with ≤ 20% of the full pilot's simulated starts.
- **S3:** a zero-simulation rule with GM < 1, p < 0.01 and worst ≤ 1.05.
- **S4:** the cheapest production configuration with GM ≤ 1.0005 × B\*.

Main dev findings (GM vs CP-first; cost in list passes, i.e. task starts / n):

| Variant | GM | vs B\* | Passes | Note |
|---|---|---|---|---|
| B\* (production) | 0.9977 | 1.0000 | — | 12× production CP-first CPU |
| pilot L4, full depth, every contested pick | 0.9975 | 0.9997 | 175.8 | never worse than CP-first (proved and tested) |
| pilot L4 d8, every contested pick | 0.9978 | 1.0000 | 20.8 | S2 |
| pilot L4 d8 if workers ≤ 8, else CP | 0.9978 | 1.0001 | 16.1 | S1 |
| pilot full if n ≤ 150, else CP | 0.9978 | 1.0001 | 36.4 | 2.1× study CP-first CPU |
| pilot full, deferred near-critical (ρ 0.5) | 0.9976 | 0.9999 | 74.1 | best decision-level trigger: 2.4× fewer passes |
| pilot full, CP gap ≤ 0.05 | 0.9982 | 1.0005 | 129.0 | near-tie trigger: loses quality, saves little |
| prod: B\* only if blended gap ≤ 0.01 / 0.05 | 0.9986 / 0.9980 | 1.0009 / 1.0003 | — | near-tie trigger with existing config options; saves only 12–19% CPU |
| prod: B\* if n ≤ 150, else A\* | 0.9980 | 1.0003 | — | S4: 1.2× production CP-first CPU (B\*: 12×) |
| prod: B\* if workers ≤ 8, else A\* | 0.9979 | 1.0001 | — | 3.2× |

Selected for the final run: S1, S2 and S4. S3 selected nothing.

**Final evaluation**, run once, with the frozen schedulers in `final-frozen.txt`. GM vs CP-first:

| Set | n | B\* | Full pilot | S1: pilot d8, workers ≤ 8 | S2: pilot d8 | S4: prod B\* if n ≤ 150, else A\* | Share of B\*'s gain kept by S4 |
|---|---|---|---|---|---|---|---|
| In-distribution | 2,700 | 0.9977 | 0.9973 | 0.9977 | 0.9977 | 0.9980 | 87% |
| XL (601–1,500 tasks) | 120 | 0.9993 | 0.9991 | 0.9994 | 0.9994 | 1.0001 | 0% (by design) |
| OOD: FFT + Gaussian | 900 | 0.9969 | 0.9965 | 0.9972 | 0.9970 | 0.9974 | 84% |
| OOD: BIMODAL | 600 | 0.9972 | 0.9966 | 0.9971 | 0.9971 | 0.9977 | 82% |
| OOD: Pareto durations (new) | 630 | 0.9970 | 0.9962 | 0.9969 | 0.9969 | 0.9973 | 90% |
| Exact set (595 solved), GM ms/OPT | 600 | 1.0014 | 1.0010 | 1.0014 | 1.0014 | 1.0014 | 100% |

(CP-first's own GM ms/OPT on the final exact set is 1.0054.)

- All differences from CP-first have p < 1e-4, except S4 on XL (p = 0.135; there S4 equals A\*).
- In-distribution 95% CIs:

| Variant | GM vs CP-first [95% CI] |
|---|---|
| B\* | 0.9977 [0.9974, 0.9980] |
| S1 | 0.9977 [0.9974, 0.9980] |
| S4 | 0.9980 [0.9977, 0.9983] |
| Full pilot | 0.9973 [0.9970, 0.9976] |

## 8. Targeted heuristic improvements (tested; none recommended)

Each rule was derived from a documented mechanism and tested as a hypothesis.

| Hypothesis | Rule | Dev GM vs CP-first | Verdict |
|---|---|---|---|
| Arbitrary tie-breaking causes failures | CP with ties by children / descendants / downstream work / longer / shorter / sole-parent children | 0.9998–1.0001 (exploration), 0.9999 (dev) | Refuted. Ties are rarely harmful (§6). |
| Starvation can be detected from the next completion time | next-completion-aware CP: compare max(t + cp(a), t_next + cp(b)) with max(t + cp(b), min(t + dur(b), t_next) + cp(a)) | 0.9998, p = 0.002–0.011, worst 1.077 | Real but negligible effect, with a worse tail. Rejected by S3. |
| Random diversity is enough | best of 32 CP schedules with ±10% priority noise | 0.9985 (dev), 0.9983 (final), never worse | Dominated by the pilot at similar cost. Offline only. |

The failure mechanism is real, but correcting it needs information about future release times. That is exactly what a rollout computes. No static or one-step rule captured it. This matches §6 and the Phase 2 finding that weights beyond critical-path dominance add nothing.

## 9. Expected quality-versus-runtime trade-offs

**Single-threaded wall time, ms per run** (`timing.md`; 120 instances; median of 3 measurements; each measurement repeats the run for ≥ 20 ms):

| Scheduler | S | M | L | XL | Alloc L / XL (MB) |
|---|---|---|---|---|---|
| CP-first (production BenchmarkEngine) | 0.027 | 0.296 | 3.73 | 13.7 | 1.09 / 3.32 |
| A\* (production Controller) | 0.020 | 0.180 | 1.55 | 3.30 | 0.66 / 1.70 |
| B\* (production) | 0.064 | 2.16 | 35.2 | 331 | 9.43 / 72.9 |
| S4: prod B\* if n ≤ 150, else A\* | 0.064 | 2.42 | 1.48 | 3.06 | 0.66 / 1.70 |
| CP-first (study simulator) | 0.001 | 0.012 | 0.108 | 0.311 | 0.01 / 0.03 |
| full pilot (study) | 0.019 | 1.72 | 53.3 | 264 | 7.52 / 35.9 |
| S2: pilot d8 (study) | 0.015 | 0.52 | 5.32 | 14.3 | 7.58 / 36.0 |
| S1: pilot d8 if workers ≤ 8 (study) | 0.016 | 0.43 | 2.68 | 5.69 | 4.53 / 23.0 |
| best of 32 perturbed (study, offline) | 0.061 | 0.74 | 5.91 | 13.1 | 0.29 / 0.71 |

**Compare within one code base.** The study's array-based simulator is 25–45× faster than the production list scheduler for the same CP-first schedule. So "B\* vs study pilot" timings mostly compare implementations, not algorithms.
- **Within production:** S4 keeps 82–90% of B\*'s gain on S–L graphs. On L graphs it costs 1.0× A\* instead of 23×, and on XL 0.9× instead of 100×.
- **Within the study code:** the depth-8 pilot gated to ≤ 8 workers equals B\*'s quality at 25× CP-first on L graphs, against 493× for the full pilot (850× on XL).
- **Implementation-independent cost** (list passes per run): B\*-level quality needs about 16–21 passes, the full pilot about 150 (in-distribution).

**Available headroom.**
- The full pilot (never worse than CP-first) reaches 0.9973 in-distribution.
- On small graphs, the best list schedule is only 0.51% below CP-first, and the pilot closes 86% of that: GM ms/ND 1.0007 vs CP-first's 1.0051 on the final exact set.
- The non-delay restriction costs only 0.03% (ND/OPT 1.0003). Methods that insert idle time are not worth exploring for this problem class.

## 10. Experimental findings and statistical uncertainty

**Data.** All Phase 3 sets use fresh seeds (81001–81008), and the Phase 2 test sets were not touched. The data was separated as follows:

| Role | Data |
|---|---|
| Exploration (rule and trigger design) | 81001 exploration set, 81002 exact set |
| Selection | 81003 dev set |
| Final test (run once, guarded by `final.done`) | 81004 in-distribution + XL; 81005 FFT/Gaussian and BIMODAL; 81007 Pareto; 81006 exact |

FFT, Gaussian, BIMODAL, Pareto and XL were never used for exploration or selection. Pareto durations were not used in any phase.

**Statistics.**
- Paired comparisons, with 95% bootstrap CIs on the GM ratio and two-sided Wilcoxon signed-rank tests.
- The dev stage compared 27 variants, so dev differences below about 0.0003 GM are within selection noise. The final set reproduced the dev ordering: S1 and S2 within 0.0001 of B\*, S4 within 0.0003.
- **Simulator correctness.** The study simulator reproduces production CP-first and LDW exactly on 1,120 instances, from TINY up to 1,500 tasks (`verify.md`).
- **Monotonicity.** The full-depth pilot is provably never worse than CP-first, since each comparison includes CP-first's own continuation. It had 0 losses on all 4,950 final-stage instances, and this is unit-tested. The depth-8 pilots are not monotone and showed 1.5–2.1% losses, with a worst case of 1.030.
- **Selection bias in §4.** The case studies are the worst instances, chosen to illustrate mechanisms, not their frequency. Frequencies come from §6.
- **Definition of "regret".** Regret is defined with respect to CP-first continuations; the true optimal policy could differ.
- **CPU timing.** Thread CPU time on Windows is about 15.6 ms granular, so per-run CPU means in `dev.md` and `final.md` are averages of quantized samples. `timing.md` uses wall-clock repetition instead. Cross-implementation runtime ratios are not algorithmic ratios (§9).
- **Exact set.** It is restricted to ≤ 18 tasks: in a probe, 19–22 tasks took up to 2.5 h for a single instance. 9 of 900 and 5 of 600 instances hit the node limit and were excluded.

## 11. Ranked recommendations

| Rank | Recommendation | Expected gain vs CP-first | Overhead | Confidence | Complexity | Architecture fit |
|---|---|---|---|---|---|---|
| 1 | **Size-gated Quality selection:** `QUALITY_OPTIMIZED` for graphs with ≤ 150 tasks, the no-lookahead path otherwise | 0.20% in-distribution (87% of B\*'s), 82–90% of B\*'s OOD gain | = B\* on S/M; = A\* on L/XL (24–108× cheaper than B\* there) | High (dev + final + 3 OOD sets) | Very low (choose a preset from n) | Full: existing presets only |
| 2 | **Graph-level rollout gate inside the rollout** (e.g. only when workers ≤ 8, or n ≤ N), as a new opt-in `SchedulerConfig` option | = B\* quality (S1: 1.0000–1.0003 × B\* on all sets) | Rollout skipped on many-worker graphs | Medium-high (study implementation; the production rollout differs in continuation details) | Low (one config field and a gate check) | Additive option; DEFAULT untouched |
| 3 | **Engineering work on the production rollout's speed** (array-based state and incremental copies, as in the study simulator) | 0 (same decisions) | The study code is 25–45× faster for the same schedules | High for direction, uncertain magnitude | Medium-high | Internal to TaskEngine/DynamicScoreLogic |
| 4 | Keep CP-first (or A\*) where the ≤ 0.5% average gain on small, few-worker graphs does not matter | — | — | High | None | — |
| — | Not recommended: near-tie triggers, tie-break rules, next-completion rule, more weight tuning, multi-start, idle-insertion methods | ≤ 0.02% or dominated | — | High (refuted or dominated) | — | — |

A cheaper but equally safe option, supported by dev evidence only: the full-depth pilot on graphs with ≤ 150 tasks (dev 0.9978, never worse than CP-first, 2.1× study CP-first CPU). It was not carried to the final run, so it is unconfirmed on test data.

## 12. Implementation and validation plan (for approval)

**R1. Size-gated Quality selection** (no scheduler change).
- Add a public static selector, e.g. `SchedulerConfig.qualityFor(int taskCount)`, returning `QUALITY_OPTIMIZED` when taskCount ≤ 150 and `FAST_OPTIMIZED` otherwise. It is additive, and DEFAULT and the existing presets stay unchanged.
- Optionally provide a `CRITICAL_PATH` preset as the large-graph fallback: K = 1, λ = 1, static weights (1, 0, 0), batched. A\* is slightly worse than CP-first on 10% of instances (worst 1.042), which this fallback would avoid. It needs its own validation.
- Tests: the selector boundaries; golden traces unchanged; schedules identical to the underlying presets.
- Validation on fresh seeds (82xxx), set by set. Acceptance:
  - GM vs B\* ≤ 1.0005 on S/M;
  - GM vs CP-first ≤ 0.9985 in-distribution;
  - single-thread runtime on L/XL ≤ 1.2× A\*;
  - worst case vs CP-first ≤ B\*'s;
  - every family ≤ 1.000 vs CP-first.

**R2. Graph-level rollout gate inside the rollout.**
1. Add an opt-in `SchedulerConfig` field, e.g. `rolloutMaxWorkers` (default unlimited, so behavior is unchanged), checked once per decision in `TaskEngine.getNextTask` before the rollout. No interface changes.
2. Pre-register the threshold grid {3, 8, 16} and the size alternative {150, 300}, and select on a fresh dev set with the S1 rule.
3. Validate once on fresh in-distribution, XL and OOD sets (FFT/Gaussian, BIMODAL, Pareto) plus a fresh exact set.
4. Acceptance: GM vs B\* ≤ 1.0005 everywhere, and a runtime reduction ≥ 50% on L/XL.
5. Golden traces, all existing tests, and a test that the gate disabled equals B\* exactly.

**R3. Rollout performance work.**
- Profile `DynamicScoreLogic.rolloutMakespan`. Replace map- and object-based state copies with flat arrays, as in `CriticalPathStudy.Sim`.
- Acceptance: bit-identical schedules on 20k instances (differential test), and runtime at or below the current value at every size.

Each step is independent, additive and reversible, and needs your approval before implementation.

## Files

- `src/main/java/benchmark/CriticalPathStudy.java` (new, experimental): stages `verify`, `explore`, `exact`, `decisions`, `cases`, `dev`, `final` (once), `timing`.
- `src/test/java/benchmark/CriticalPathStudyTest.java` (new): 7 tests.
- Outputs in `target/phase3/run1/`: `verify.md`, `explore.md/.csv`, `exact.md/.csv`, `decisions.md/.csv`, `cases.md`, `dev.md`, `dev-selection.txt`, `final-frozen.txt`, `final.md/.csv`, `timing.md`, `stage-times.csv`.
- Total compute: about 8 minutes of stage time on 12 threads, plus an exact-solver size probe (about 2.6 h single-threaded, stopped once the size limit was clear).
- Reproduce: `mvn -o compile`, then `java -Xmx6g -cp target/classes benchmark.CriticalPathStudy <stage> target/phase3/<dir> 12`, running the stages in the order above.
