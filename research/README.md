# Research materials

The scheduler's current look-ahead was chosen through a series of controlled experiments.
Start with **[RESEARCH_REPORT.md](RESEARCH_REPORT.md)**: it consolidates every verified finding.

| Path | Content |
|---|---|
| [`RESEARCH_REPORT.md`](RESEARCH_REPORT.md) | Consolidated findings of Phases 1–3 and the final integration |
| [`phase-reports/`](phase-reports/) | The original, unedited reports of Phase 1 (fixing and validating the algorithm), Phase 2 (weight optimization) and Phase 3 (when critical-path-first fails) |
| [`final-integration/`](final-integration/) | Harness, driver script and per-instance results of the controlled comparison between the original scheduler (commit `be6d109`) and the shipped version (original + rollout look-ahead) |
| [`archive/phase1-4-research-source/`](archive/phase1-4-research-source/) | Exact copies of the research code (configurable scheduler, presets, instance generator, exact solver, experiment runners), plus their diff against `be6d109`. Not compiled. To rerun a phase, check out `be6d109` in a separate worktree, copy this `src/` over it, and follow the phase report. |

The raw outputs of Phases 1–4 (about 100 MB of CSV files) are not stored in this repository. The final
comparison that backs the numbers in the main README is fully included in `final-integration/results/`, and can
be regenerated with:

```bash
bash research/final-integration/run-comparison.sh 12            # several minutes on 12 threads
REPORTS_ONLY=1 bash research/final-integration/run-comparison.sh # rebuild the .md tables from the CSVs
```
