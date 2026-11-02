# Presentation

**[Adaptive_Task_Scheduler.pdf](Adaptive_Task_Scheduler.pdf)** (view in the browser) ·
[Adaptive_Task_Scheduler.pptx](Adaptive_Task_Scheduler.pptx) (original slides, 48 slides)

Contents: the scheduling problem and why it is NP-hard, the static and dynamic scoring components, the
rollout look-ahead, the experimental methodology, results against baselines and exact optima, and appendices
with exact definitions and reproducibility notes.

Some slides compare research configurations (for example `QUALITY_OPTIMIZED`, with learned weights) that
were evaluated but **not** shipped. The shipped scheduler is the original algorithm with only the rollout
look-ahead added; see [`../research/RESEARCH_REPORT.md`](../research/RESEARCH_REPORT.md).

`source/` holds the script that generates the deck ([PptxGenJS](https://gitbrent.github.io/PptxGenJS/)) and
the verified examples it draws. `Probe.java` was used to compute those examples against the archived research
code and does not compile against the shipped source.
