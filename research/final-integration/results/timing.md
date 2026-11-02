# Single-threaded wall time per run (ms)

Timing set: seed 95005, 5 instances per family and size (160). Each value is the median over the
instances of the per-instance median of 3 measurements; each measurement repeats the run for >= 50 ms.

| Size | Original | Original + new lookahead | Ratio |
|---|---|---|---|
| SMALL | 0.124 | 0.172 | 1.4x |
| MEDIUM | 0.990 | 2.865 | 2.9x |
| LARGE | 9.656 | 27.011 | 2.8x |
| XLARGE | 69.893 | 190.232 | 2.7x |
