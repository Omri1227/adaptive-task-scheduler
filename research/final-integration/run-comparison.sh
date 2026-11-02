#!/usr/bin/env bash
# Controlled comparison: original scheduler (commit be6d109) vs. the updated scheduler
# in the working tree (original + rollout lookahead), plus a differential check that
# everything outside the lookahead is unchanged.
#
# Usage (Git Bash or any POSIX shell, from anywhere inside the repository):
#   bash research/final-integration/run-comparison.sh [threads]
# Results: research/final-integration/results/   Build scratch: target/final-integration-build/
set -euo pipefail

THREADS=${1:-12}
ROOT=$(git rev-parse --show-toplevel)
BASELINE=be6d109
W=$ROOT/target/final-integration-build
OUT=$ROOT/research/final-integration/results
HARNESS=$ROOT/research/final-integration/harness/src

case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=';'; win() { cygpath -m "$1"; } ;; *) SEP=':'; win() { echo "$1"; } ;; esac

if [ "${REPORTS_ONLY:-0}" != 1 ]; then
rm -rf "$W"; mkdir -p "$W" "$OUT"

# ---- sources: ORIG = baseline commit, NEW = working tree; *-NOLA = same with the lookahead switched off
mkdir -p "$W/src-orig" "$W/src-new"
git -C "$ROOT" archive "$BASELINE" src/main/java/backend src/main/java/benchmark | tar -x -C "$W/src-orig"
mkdir -p "$W/src-new/src/main/java"
cp -r "$ROOT/src/main/java/backend" "$ROOT/src/main/java/benchmark" "$W/src-new/src/main/java/"
for v in orig new; do
  cp -r "$W/src-$v" "$W/src-$v-nola"
  f="$W/src-$v-nola/src/main/java/backend/TaskEngine.java"
  [ "$(grep -c 'if (tasksAr\[1\] != null' "$f")" = 1 ] || { echo "lookahead gate not found exactly once in $v"; exit 1; }
  perl -pi -e 's/if \(tasksAr\[1\] != null/if (false \&\& tasksAr[1] != null/' "$f"
done

# ---- compile the four scheduler builds and the harness
for v in orig new orig-nola new-nola; do
  mkdir -p "$W/cls-$v"
  javac -nowarn -encoding UTF-8 --release 21 -d "$(win "$W/cls-$v")" $(find "$W/src-$v" -name '*.java' | while read -r f; do win "$f"; done)
done
mkdir -p "$W/cls-harness"
javac -encoding UTF-8 --release 21 -cp "$(win "$W/cls-new")" -d "$(win "$W/cls-harness")" \
  $(find "$HARNESS" -name '*.java' | while read -r f; do win "$f"; done)

run() {  # build set
  java -Xmx6g -cp "$(win "$W/cls-$1")$SEP$(win "$W/cls-harness")" harness.Run "$2" "$(win "$OUT/$2.$1.csv")" "$THREADS"
}

# ---- 1. differential: lookahead off in both builds -> everything else must be identical
for v in orig-nola new-nola; do for s in main xl exact curated events; do run $v $s; done; done

# ---- 2. comparison of the full builds
for v in orig new; do for s in main confirm xl exact phase1-confirm curated events; do run $v $s; done; done
run new exact-opt

# ---- 3. single-threaded timing (nothing else running)
for v in orig new; do run $v timing; done
else
  # regenerate reports from existing CSVs: recompile the harness only
  mkdir -p "$W/cls-harness"
  javac -encoding UTF-8 --release 21 -cp "$(win "$W/cls-new")" -d "$(win "$W/cls-harness")"  $(find "$HARNESS" -name '*.java' | while read -r f; do win "$f"; done)
fi

# ---- reports
CMP="java -cp $(win "$W/cls-harness") harness.Compare"
{
  echo "# Differential check: lookahead disabled in both builds"
  echo
  for s in main xl exact curated; do
    $CMP "$(win "$OUT/$s.orig-nola.csv")" "$(win "$OUT/$s.new-nola.csv")" "$s (original without lookahead vs. updated without lookahead)"
  done
  echo "## Runtime-event scripts"
  echo
  if cmp -s "$OUT/events.orig-nola.csv" "$OUT/events.new-nola.csv"; then
    echo "All $(($(wc -l < "$OUT/events.new-nola.csv") - 1)) event-script traces identical (byte-identical CSV)."
  else
    echo "EVENT TRACES DIFFER"; diff "$OUT/events.orig-nola.csv" "$OUT/events.new-nola.csv" | head -20
  fi
} > "$OUT/differential.md"

{
  echo "# Original scheduler vs. original + new rollout lookahead"
  echo
  $CMP "$(win "$OUT/main.orig.csv")" "$(win "$OUT/main.new.csv")" "MAIN (seed 95001, 3,600 instances, S/M/L)"
  $CMP "$(win "$OUT/confirm.orig.csv")" "$(win "$OUT/confirm.new.csv")" "CONFIRM (seed 95002, 2,400 instances, S/M/L)"
  $CMP "$(win "$OUT/xl.orig.csv")" "$(win "$OUT/xl.new.csv")" "XL (seed 95003, 200 instances, 601-1,500 tasks)"
  $CMP "$(win "$OUT/exact.orig.csv")" "$(win "$OUT/exact.new.csv")" "EXACT (seed 95004, 800 tiny instances)" "$(win "$OUT/exact-opt.new.csv")"
  $CMP "$(win "$OUT/curated.orig.csv")" "$(win "$OUT/curated.new.csv")" "CURATED (the 10 ScenarioLibrary scenarios)"
  $CMP "$(win "$OUT/phase1-confirm.orig.csv")" "$(win "$OUT/phase1-confirm.new.csv")" "Consistency check: Phase 1 CONFIRM set (seed 3003, 2,400 instances)"
} > "$OUT/comparison.md"

echo "done: $OUT"

# ---- timing summary (median over instances of the per-instance median ms per run)
{
  echo "# Single-threaded wall time per run (ms)"
  echo
  echo "Timing set: seed 95005, 5 instances per family and size (160). Each value is the median over the"
  echo "instances of the per-instance median of 3 measurements; each measurement repeats the run for >= 50 ms."
  echo
  echo "| Size | Original | Original + new lookahead | Ratio |"
  echo "|---|---|---|---|"
  for b in SMALL MEDIUM LARGE XLARGE; do
    o=$(awk -F, -v b=$b 'NR>1 && $2==b {print $5}' "$OUT/timing.orig.csv" | sort -g | awk '{a[NR]=$1} END {print (NR%2? a[(NR+1)/2] : (a[NR/2]+a[NR/2+1])/2)}')
    n=$(awk -F, -v b=$b 'NR>1 && $2==b {print $5}' "$OUT/timing.new.csv" | sort -g | awk '{a[NR]=$1} END {print (NR%2? a[(NR+1)/2] : (a[NR/2]+a[NR/2+1])/2)}')
    awk -v b=$b -v o=$o -v n=$n 'BEGIN {printf "| %s | %.3f | %.3f | %.1fx |\n", b, o, n, n/o}'
  done
} > "$OUT/timing.md"
echo "reports: $OUT"
