const pptxgen = require("pptxgenjs");
const path = require("path");
const { applyTheme } = require("./apply_theme.js");
const { COL, HEAD, BODY, T, dag, gantt, lanesOf, letterOf } = require("./lib.js");
const EX = require("./examples.json");

const OUT = process.argv[2] || "Adaptive_Task_Scheduler.pptx";
const THEME = {
  name: "Scheduler Research", headFontFace: HEAD, bodyFontFace: BODY,
  colors: { dk1: COL.ink, lt1: "FFFFFF", dk2: COL.ink2, lt2: COL.panel, accent1: COL.qual, accent2: COL.cp, accent3: COL.fast,
    accent4: COL.b0, accent5: COL.orig, accent6: COL.bad, hlink: COL.fast, folHlink: COL.muted },
};
const pres = new pptxgen();
pres.layout = "LAYOUT_WIDE"; // 13.333 x 7.5
pres.title = "Adaptive Task Scheduler — Heuristic Scheduling, Lookahead and Experimental Weight Optimization";
pres.author = "Omri Schein";
pres.theme = { headFontFace: HEAD, bodyFontFace: BODY };

const W = 13.333;
// ---------- layouts ----------
pres.defineSlideMaster({ title: "TITLE_DARK", background: { color: COL.ink }, objects: [
  { placeholder: { options: { name: "title", type: "title", x: 0.7, y: 2.0, w: 6.6, h: 1.9, fontFace: HEAD, fontSize: 44, bold: true, color: "FFFFFF", valign: "bottom", align: "left", margin: 0 }, text: "" } },
  { placeholder: { options: { name: "body", type: "body", x: 0.7, y: 4.05, w: 6.4, h: 1.0, fontFace: BODY, fontSize: 18, color: "C9CFDA", valign: "top", align: "left", margin: 0 }, text: "" } },
] });
pres.defineSlideMaster({ title: "SECTION", background: { color: COL.ink }, objects: [
  { placeholder: { options: { name: "title", type: "title", x: 0.7, y: 2.6, w: 8.5, h: 1.2, fontFace: HEAD, fontSize: 40, bold: true, color: "FFFFFF", valign: "bottom", align: "left", margin: 0 }, text: "" } },
  { placeholder: { options: { name: "body", type: "body", x: 0.7, y: 3.95, w: 8.0, h: 1.2, fontFace: BODY, fontSize: 18, color: "C9CFDA", valign: "top", align: "left", margin: 0 }, text: "" } },
] });
const footer = (label) => [
  { text: { text: label, options: { x: 0.6, y: 7.08, w: 6, h: 0.25, fontFace: BODY, fontSize: 10, color: COL.faint, margin: 0 } } },
];
pres.defineSlideMaster({ title: "CONTENT", background: { color: "FFFFFF" },
  slideNumber: { x: 12.25, y: 7.05, w: 0.5, h: 0.28, fontFace: BODY, fontSize: 10, color: COL.faint, align: "right" },
  objects: [
    ...footer("Adaptive Task Scheduler  ·  heuristic scheduling, lookahead and weight optimization"),
    { placeholder: { options: { name: "title", type: "title", x: 0.6, y: 0.32, w: 12.1, h: 0.72, fontFace: HEAD, fontSize: 32, bold: true, color: COL.ink, valign: "middle", align: "left", margin: 0 }, text: "" } },
  ] });
pres.defineSlideMaster({ title: "APPENDIX", background: { color: "FFFFFF" },
  slideNumber: { x: 12.25, y: 7.05, w: 0.5, h: 0.28, fontFace: BODY, fontSize: 10, color: COL.faint, align: "right" },
  objects: [
    ...footer("Appendix  ·  technical detail"),
    { placeholder: { options: { name: "title", type: "title", x: 0.6, y: 0.32, w: 12.1, h: 0.72, fontFace: HEAD, fontSize: 28, bold: true, color: COL.ink, valign: "middle", align: "left", margin: 0 }, text: "" } },
  ] });

let section = "";
function sec(title) { section = title; pres.addSection({ title }); }
function content(title, kicker, layout = "CONTENT") {
  const s = pres.addSlide({ masterName: layout, sectionTitle: section });
  s.addText(title, { placeholder: "title" });
  if (kicker) T(s, kicker, { x: 0.6, y: 1.02, w: 12.1, h: 0.4, fontSize: 16, color: COL.muted, italic: true });
  return s;
}
function divider(num, title, sub) {
  const s = pres.addSlide({ masterName: "SECTION", sectionTitle: section });
  T(s, num, { x: 0.7, y: 1.7, w: 3, h: 0.8, fontFace: HEAD, fontSize: 54, bold: true, color: COL.qual });
  s.addText(title, { placeholder: "title" });
  s.addText(sub, { placeholder: "body" });
  return s;
}
function panel(s, x, y, w, h, fill = COL.panel) {
  s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x, y, w, h, rectRadius: 0.08, fill: { color: fill }, line: { color: fill, width: 0 } });
}
function chip(s, x, y, text, color, w = 1.0) {
  s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x, y, w, h: 0.28, rectRadius: 0.14, fill: { color }, line: { color, width: 0 } });
  T(s, text, { x, y, w, h: 0.28, fontSize: 11, bold: true, color: "FFFFFF", align: "center", valign: "middle" });
}
function bullets(s, items, o) {
  const runs = items.map((it, i) => {
    const t = typeof it === "string" ? [{ text: it }] : it;
    return t.map((r, j) => ({ text: r.text, options: Object.assign({ bullet: j === 0 ? (o.bullet === false ? false : { indent: 14 }) : undefined, breakLine: j === t.length - 1 && i < items.length - 1, paraSpaceAfter: o.gap || 8 }, r.o || {}) }));
  }).flat();
  s.addText(runs, Object.assign({ isTextBox: true, fontFace: BODY, fontSize: 15, color: COL.ink, valign: "top", margin: 0 }, o));
}
const F = (text, extra = {}) => ({ text, o: Object.assign({ fontFace: HEAD, italic: true }, extra) });
const B = (text, extra = {}) => ({ text, o: Object.assign({ bold: true }, extra) });

// ---------- chart defaults ----------
const AX = { catAxisLabelColor: COL.muted, valAxisLabelColor: COL.muted, catAxisLabelFontFace: "+mn-lt", valAxisLabelFontFace: "+mn-lt",
  catAxisLabelFontSize: 11, valAxisLabelFontSize: 10, dataLabelFontFace: "+mn-lt", dataLabelFontSize: 11, dataLabelColor: COL.ink,
  valGridLine: { color: "E3E7ED", size: 0.75 }, catGridLine: { style: "none" }, catAxisLineShow: false, valAxisLineShow: false,
  legendFontFace: "+mn-lt", legendFontSize: 11, legendColor: COL.muted, titleFontFace: "+mn-lt", titleColor: COL.ink, titleFontSize: 13 };
const chartOpts = (o) => Object.assign({}, AX, o);

// ---------- example helpers ----------
const ex = (k) => EX[k];
function ganttBlock(s, x, y, w, k, policy, caption, color, style = {}, opt = {}) {
  const e = ex(k); const g = lanesOf(e.gantt[policy]);
  const T_ = opt.T || Math.max(e.ms.OPT + 2, ...Object.values(e.gantt).map((gg) => Math.max(...gg.flat().map((q) => q[2]))));
  const ms = policy === "SPT" ? e.ms.SHORTEST_TASK_FIRST : e.ms[policy];
  s.addShape(pres.shapes.OVAL, { x, y: y + 0.06, w: 0.14, h: 0.14, fill: { color }, line: { color, width: 0 } });
  T(s, [{ text: caption + "   " }, { text: "Cmax = " + ms, options: { bold: true, color: ms > e.ms.OPT ? COL.bad : COL.good } }],
    { x: x + 0.22, y, w: w - 0.22, h: 0.26, fontSize: 12, color: COL.ink });
  const h = opt.h || (0.36 * e.workers + 0.3);
  gantt(pres, s, x, y + 0.3, w, h, g, { T: T_, style, cmax: ms, letters: opt.letters, step: opt.step });
  return y + 0.3 + h;
}
const styleFor = (ids, fill, text = "FFFFFF") => Object.fromEntries(ids.map((i) => [i, { fill, text }]));
const nodeStyle = (ids, fill, line, text = "FFFFFF", sub = "F3F5F8") => Object.fromEntries(ids.map((i) => [i, { fill, line: line || fill, text, sub, lw: 1.5 }]));

// A "helps / fails" card: DAG + values on the left, two Gantts on the right, note at the bottom.
function card(s, x, y, w, h, c) {
  panel(s, x, y, w, h, COL.panel);
  chip(s, x + 0.2, y + 0.15, c.good ? "WHERE IT HELPS" : "WHERE IT FAILS", c.good ? COL.good : COL.bad, 1.55);
  T(s, c.head, { x: x + 1.9, y: y + 0.12, w: w - 2.1, h: 0.34, fontSize: 12, color: COL.muted, valign: "middle" });
  const e = ex(c.k);
  const nSrc = Object.values(e.tasks).filter((t) => !t.parents.length).length;
  const big = nSrc >= 5, nh = big ? 0.36 : 0.42;
  const dagW = 2.0, dagH = big ? 2.15 : Math.min(2.0, 0.5 * Math.max(nSrc, 2) + 0.25);
  dag(pres, s, x + 0.25, y + 0.58, dagW, dagH, e.tasks, { style: c.nodeStyle, nodeW: 0.5, nodeH: nh, fs: big ? 11 : 12 });
  if (c.values) {
    const all = Object.entries(c.values).filter(([, v]) => v !== "" && v !== undefined);
    const nz = all.filter(([, v]) => String(v) !== "0");
    const parts = (nz.length < all.length && all.length > 3 ? nz : all).map(([i, v]) => letterOf(i) + " " + v);
    if (nz.length < all.length && all.length > 3) parts.push("others 0");
    T(s, [{ text: (c.valName || "") + ":  ", options: { bold: true } }, { text: parts.join("  ·  ") }],
      { x: x + 0.2, y: y + 0.62 + dagH + 0.06, w: 2.12, h: 0.45, fontSize: 10.5, color: COL.ink2 });
  }
  const gx = x + 2.5, gw = w - 2.7;
  const gh = e.workers >= 3 ? 0.95 : 0.85;
  let yy = y + 0.55;
  c.gantts.forEach((gg) => { yy = ganttBlock(s, gx, yy, gw, c.k, gg.p, gg.cap, gg.color, gg.style || {}, { h: gh }) + 0.06; });
  if (c.note) T(s, c.note, { x: x + 0.2, y: y + h - 0.6, w: w - 0.4, h: 0.52, fontSize: 12, color: COL.ink2, valign: "middle" });
}

function componentSlide(o) {
  const s = content(o.title, o.kicker);
  panel(s, 0.6, 1.5, 4.75, 1.25, COL.panel2);
  T(s, "DEFINITION (AS IMPLEMENTED)", { x: 0.8, y: 1.58, w: 4.4, h: 0.25, fontSize: 10, bold: true, color: COL.muted, charSpacing: 1 });
  T(s, o.formula, { x: 0.8, y: 1.82, w: 4.4, h: 0.9, fontFace: HEAD, fontSize: o.formulaSize || 15, color: COL.ink, valign: "middle" });
  T(s, "Measures", { x: 5.6, y: 1.52, w: 3.4, h: 0.28, fontSize: 13, bold: true });
  T(s, o.measures, { x: 5.6, y: 1.82, w: 3.4, h: 0.95, fontSize: 12.5, color: COL.ink2 });
  T(s, "Why we expected it to help", { x: 9.25, y: 1.52, w: 3.48, h: 0.28, fontSize: 13, bold: true });
  T(s, o.why, { x: 9.25, y: 1.82, w: 3.48, h: 0.95, fontSize: 12.5, color: COL.ink2 });
  if (o.src) T(s, o.src, { x: 0.6, y: 2.8, w: 12.13, h: 0.25, fontSize: 10, color: COL.faint });
  const cy = 3.1, ch = 3.84, cw = (12.13 - 0.25) / 2;
  card(s, 0.6, cy, cw, ch, Object.assign({ good: true }, o.help));
  card(s, 0.6 + cw + 0.25, cy, cw, ch, Object.assign({ good: false }, o.fail));
  if (o.notes) s.addNotes(o.notes);
  return s;
}
const vals = (k, f) => Object.fromEntries(Object.entries(ex(k).tasks).map(([i, t]) => [i, f(t, Number(i))]));

// =====================================================================
// PART I
// =====================================================================
sec("I · The scheduling problem");
{
  const s = pres.addSlide({ masterName: "TITLE_DARK", sectionTitle: section });
  s.addText("Adaptive Task Scheduler", { placeholder: "title" });
  s.addText("Heuristic Scheduling, Lookahead and Experimental Weight Optimization", { placeholder: "body" });
  T(s, "Research presentation  ·  P | prec | Cmax  ·  synthetic DAG benchmarks", { x: 0.7, y: 5.4, w: 6.4, h: 0.3, fontSize: 13, color: COL.faint });
  // DAG + Gantt (stencil instance) on the right, drawn in light ink
  const e = ex("STENCIL191");
  const darkNode = Object.fromEntries(Object.keys(e.tasks).map((i) => [i, { fill: COL.ink2, line: "8A93A3", text: "FFFFFF", sub: "C9CFDA" }]));
  [1, 2, 3, 7, 8].forEach((i) => (darkNode[i] = { fill: COL.cp, line: COL.cp, text: "FFFFFF", sub: "FFF4DD", lw: 1.5 }));
  dag(pres, s, 7.75, 0.9, 4.9, 2.9, e.tasks, { letters: "T", style: darkNode, nodeW: 0.62, nodeH: 0.46, fs: 12, hiEdges: [[1, 2], [2, 3], [3, 7], [7, 8]] });
  const g = lanesOf(e.gantt.QUALITY_OPT);
  const st = styleFor([6], COL.qual); [1, 2, 3, 7, 8].forEach((i) => (st[i] = { fill: COL.cp, text: "FFFFFF" }));
  Object.keys(e.tasks).forEach((i) => { if (!st[i]) st[i] = { fill: "3A4256", text: "FFFFFF" }; });
  gantt(pres, s, 7.6, 4.55, 5.05, 1.25, g, { T: 31, style: st, letters: "T", fs: 10, cmax: 29, cmaxColor: "FFFFFF" });
  T(s, "A real benchmark DAG (Phase 3, STENCIL-SMALL-81002-191) and the schedule our final scheduler builds for it: Cmax = 29, the proven optimum.", { x: 7.6, y: 6.0, w: 5.05, h: 0.6, fontSize: 11, color: "AEB5C2" });
}
{
  const s = content("The problem: who works on what, and when?", "Identical workers, tasks with known durations, and dependencies between them. Finish everything as early as possible.");
  const e = ex("REACHHELP");
  panel(s, 0.6, 1.6, 4.6, 4.6);
  T(s, "TASKS AND DEPENDENCIES", { x: 0.85, y: 1.75, w: 4, h: 0.25, fontSize: 10, bold: true, color: COL.muted, charSpacing: 1 });
  dag(pres, s, 1.0, 2.25, 3.8, 2.7, e.tasks, { nodeW: 0.7, nodeH: 0.56, fs: 15 });
  T(s, "Each box is a task; its number is its duration. An arrow X → Y means Y cannot start until X has finished.", { x: 0.85, y: 5.25, w: 4.1, h: 0.8, fontSize: 13, color: COL.ink2 });
  T(s, "TWO IDENTICAL WORKERS, ONE TIMELINE", { x: 5.6, y: 1.75, w: 6, h: 0.25, fontSize: 10, bold: true, color: COL.muted, charSpacing: 1 });
  ganttBlock(s, 5.6, 2.15, 7.1, "REACHHELP", "REACH_ONLY", "A good schedule", COL.good, {}, { h: 1.15 });
  ganttBlock(s, 5.6, 3.85, 7.1, "REACHHELP", "CP_ONLY", "A plausible but worse schedule", COL.bad, {}, { h: 1.15 });
  bullets(s, [
    [B("Online decision: "), { text: "whenever a worker becomes free, pick one of the ready tasks." }],
    [B("Ready: "), { text: "all of a task's prerequisites are finished." }],
    [B("Goal: "), { text: "minimise the time the last task finishes (the makespan, Cmax)." }],
  ], { x: 5.6, y: 5.55, w: 7.1, h: 1.3, fontSize: 14, gap: 4 });
  s.addNotes("Same five tasks, same two workers. The two schedules differ only in which ready task each free worker took. Optimum for this instance is 11 (verified with the repository's exact branch-and-bound solver).");
}
{
  const s = content("Mathematical formulation", "The classical problem P | prec | Cmax, which the scheduler solves online with deterministic durations.");
  panel(s, 0.6, 1.6, 6.1, 5.2);
  const rows = [
    ["Tasks and DAG", "G = (V, E) is a directed acyclic graph; (u, v) ∈ E means u must finish before v starts."],
    ["Durations", "Each task v has an integer duration d(v) > 0, known in advance."],
    ["Workers", "m identical workers (P); each runs one task at a time."],
    ["Precedence", "S(v) ≥ S(u) + d(u) for every edge (u, v) ∈ E."],
    ["Non-preemptive", "Once started, v runs without interruption: C(v) = S(v) + d(v)."],
    ["Objective", "minimise Cmax = max over v ∈ V of C(v)."],
  ];
  rows.forEach(([h, t], i) => {
    T(s, h, { x: 0.85, y: 1.8 + i * 0.8, w: 1.75, h: 0.7, fontSize: 14, bold: true, color: COL.ink });
    T(s, t, { x: 2.65, y: 1.8 + i * 0.8, w: 3.85, h: 0.7, fontSize: 14, color: COL.ink2, fontFace: i >= 3 ? HEAD : BODY, italic: i >= 3 });
  });
  T(s, "Three-field notation", { x: 7.1, y: 1.65, w: 5.5, h: 0.3, fontSize: 13, bold: true, color: COL.muted });
  const fields = [["P", "identical parallel machines", COL.fast], ["prec", "precedence constraints (a DAG)", COL.cp], ["Cmax", "makespan objective", COL.qual]];
  fields.forEach(([f, d, c], i) => {
    s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: 7.1 + i * 1.85, y: 2.05, w: 1.7, h: 0.85, rectRadius: 0.08, fill: { color: c }, line: { color: c, width: 0 } });
    T(s, f, { x: 7.1 + i * 1.85, y: 2.05, w: 1.7, h: 0.85, fontFace: HEAD, fontSize: 26, bold: true, color: "FFFFFF", align: "center", valign: "middle" });
    T(s, d, { x: 7.1 + i * 1.85, y: 2.98, w: 1.7, h: 0.55, fontSize: 12, color: COL.ink2, align: "center" });
  });
  T(s, "A simple lower bound used throughout", { x: 7.1, y: 3.85, w: 5.5, h: 0.3, fontSize: 13, bold: true, color: COL.muted });
  panel(s, 7.1, 4.2, 5.6, 1.0, COL.panel2);
  T(s, "LB = max( CP ,  ⌈ Σ d(v) / m ⌉ )", { x: 7.3, y: 4.2, w: 5.2, h: 1.0, fontFace: HEAD, fontSize: 20, italic: true, color: COL.ink, valign: "middle", align: "center" });
  T(s, "CP is the longest dependency chain (no schedule can beat it); Σd/m is the total work spread perfectly over m workers. Every schedule satisfies Cmax ≥ LB, but the optimum can sit strictly above LB.", { x: 7.1, y: 5.35, w: 5.6, h: 1.2, fontSize: 13, color: COL.ink2 });
}
{
  const s = content("Why is the problem NP-hard?  A reduction from PARTITION", "Even with no dependencies and only two workers, deciding whether a schedule meets a deadline is as hard as PARTITION.");
  const steps = [
    ["1", "PARTITION", "Given positive integers a₁ … aₙ with sum S, can they be split into two sets of equal sum S/2? (NP-complete; Karp 1972)"],
    ["2", "Construction", "For each aᵢ create a task of duration aᵢ. No precedence edges. m = 2 identical workers. Deadline T = S/2."],
    ["3", "⇒", "If a partition exists, run one set on each worker: both finish at S/2, so Cmax ≤ T."],
    ["4", "⇐", "If Cmax ≤ S/2, the two workers' loads sum to S and each is ≤ S/2, so each is exactly S/2: their task sets form a partition."],
    ["5", "Polynomial", "The construction copies n numbers: O(n) time. Hence the decision version of P2 || Cmax (and so of P | prec | Cmax) is NP-hard."],
  ];
  steps.forEach(([n, h, t], i) => {
    const y = 1.6 + i * 1.02;
    s.addShape(pres.shapes.OVAL, { x: 0.6, y: y + 0.05, w: 0.42, h: 0.42, fill: { color: COL.ink }, line: { color: COL.ink, width: 0 } });
    T(s, n, { x: 0.6, y: y + 0.05, w: 0.42, h: 0.42, fontSize: 14, bold: true, color: "FFFFFF", align: "center", valign: "middle" });
    T(s, h, { x: 1.2, y, w: 1.55, h: 0.5, fontSize: 15, bold: true, color: COL.ink, valign: "middle" });
    T(s, t, { x: 2.8, y, w: 4.6, h: 0.95, fontSize: 13, color: COL.ink2 });
  });
  // example
  panel(s, 7.75, 1.6, 4.98, 5.25);
  T(s, "YES-instance  {4, 3, 2, 2, 1}  ·  S = 12, T = 6", { x: 7.95, y: 1.75, w: 4.6, h: 0.3, fontSize: 13, bold: true, color: COL.ink });
  const py = ex("PARTYES");
  const gy = [[[1, 0, 4], [3, 4, 6]], [[2, 0, 3], [4, 3, 5], [5, 5, 6]]];
  const lab = { 1: "4", 2: "3", 3: "2", 4: "2", 5: "1" };
  gantt(pres, s, 7.95, 2.15, 4.6, 1.1, gy, { T: 8, cmax: 6, label: lab, style: Object.fromEntries([1, 2, 3, 4, 5].map((i) => [i, { fill: i === 1 || i === 3 ? COL.fast : COL.b0, text: "FFFFFF" }])) });
  T(s, "{4, 2} | {3, 2, 1}: both workers finish at 6 = T. Exact solver: optimum 6.", { x: 7.95, y: 3.3, w: 4.6, h: 0.5, fontSize: 12, color: COL.ink2 });
  T(s, "NO-instance  {5, 4, 3}  ·  S = 12, T = 6", { x: 7.95, y: 4.05, w: 4.6, h: 0.3, fontSize: 13, bold: true, color: COL.ink });
  gantt(pres, s, 7.95, 4.45, 4.6, 1.1, [[[1, 0, 5]], [[2, 0, 4], [3, 4, 7]]], { T: 8, cmax: 7, label: { 1: "5", 2: "4", 3: "3" }, style: { 1: { fill: COL.orig, text: "FFFFFF" }, 2: { fill: COL.orig, text: "FFFFFF" }, 3: { fill: COL.bad, text: "FFFFFF" } } });
  T(s, "No subset sums to 6, so every schedule has Cmax ≥ 7 > T. Exact solver: optimum 7.", { x: 7.95, y: 5.6, w: 4.6, h: 0.5, fontSize: 12, color: COL.ink2 });
  T(s, "Each bar is one number / task, labelled by its duration.", { x: 7.95, y: 6.25, w: 4.6, h: 0.4, fontSize: 10, color: COL.faint });
  s.addNotes("Decision version: 'is there a schedule with Cmax <= T?' is NP-complete (it is in NP: a schedule is a polynomial certificate). The optimisation version is therefore NP-hard. PARTITION is only weakly NP-complete (pseudo-polynomial dynamic programming exists), so this reduction proves weak NP-hardness only. Stronger results exist in the literature (e.g. 3-PARTITION for an arbitrary number of machines; Ullman 1975 for unit-time tasks with precedence), but they are not proven on this slide.");
}
{
  const s = content("What the reduction does and does not say", "Precise claims, because NP-hardness is often over-interpreted.");
  const cards = [
    ["Decision version", "“Is there a schedule with Cmax ≤ T?” is NP-complete: a schedule is a short certificate checked in polynomial time.", COL.fast],
    ["Optimisation version", "Minimising Cmax is NP-hard: an optimal algorithm would also answer the decision question.", COL.b0],
    ["Weak, not strong", "PARTITION is weakly NP-complete (pseudo-polynomial algorithms exist). This reduction proves weak NP-hardness only; strong NP-hardness needs a different reduction (e.g. from 3-PARTITION).", COL.cp],
  ];
  cards.forEach(([h, t, c], i) => {
    const x = 0.6 + i * 4.1;
    panel(s, x, 1.65, 3.85, 2.0);
    s.addShape(pres.shapes.OVAL, { x: x + 0.25, y: 1.88, w: 0.3, h: 0.3, fill: { color: c }, line: { color: c, width: 0 } });
    T(s, h, { x: x + 0.7, y: 1.83, w: 3.0, h: 0.4, fontSize: 16, bold: true, color: COL.ink, valign: "middle" });
    T(s, t, { x: x + 0.25, y: 2.35, w: 3.4, h: 1.75, fontSize: 13, color: COL.ink2 });
  });
  T(s, "What NP-hardness does not mean", { x: 0.6, y: 4.0, w: 12, h: 0.35, fontSize: 18, bold: true, color: COL.ink, fontFace: HEAD });
  bullets(s, [
    [B("Not “every instance is hard”. "), { text: "In our exact experiments, Critical Path First is already optimal on 93.6% of 800 tiny instances (2–10 tasks)." }],
    [B("Not “optimum cannot be computed”. "), { text: "Our branch-and-bound solver proves optima for 2–18-task graphs; a single 19–22-task instance took up to 2.5 h (Phase 3)." }],
    [B("It does mean: "), { text: "no known algorithm guarantees an optimum in polynomial time for every instance, so practical schedulers rely on fast, well-tested priority rules." }],
  ], { x: 0.6, y: 4.5, w: 12.1, h: 2.3, fontSize: 15, gap: 8 });
}
{
  const s = content("Why we need a heuristic", "Our requirements point to a priority-based list scheduler.");
  const reqs = [["Online", "Decide at every worker-free event; durations and the DAG are known but decisions are made as time advances."], ["Fast", "Thousands of tasks; decisions in milliseconds, not hours of exhaustive search."], ["Robust", "Graphs of very different shapes, sizes, duration mixes and worker counts."], ["Explainable", "Each decision should come from interpretable signals about the DAG and the current state."]];
  reqs.forEach(([h, t], i) => {
    const y = 1.65 + i * 1.2;
    panel(s, 0.6, y, 5.6, 1.05);
    T(s, h, { x: 0.85, y: y + 0.12, w: 1.6, h: 0.8, fontSize: 17, bold: true, color: COL.ink, fontFace: HEAD, valign: "middle" });
    T(s, t, { x: 2.45, y: y + 0.12, w: 3.6, h: 0.8, fontSize: 13, color: COL.ink2, valign: "middle" });
  });
  T(s, "List scheduling, the template we use", { x: 6.7, y: 1.65, w: 6, h: 0.35, fontSize: 16, bold: true, color: COL.ink });
  const steps = ["A worker becomes free", "Collect the ready tasks", "Score each ready task", "Start the best-scoring task", "Advance to the next completion"];
  steps.forEach((t, i) => {
    const y = 2.15 + i * 0.78;
    s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: 6.7, y, w: 3.8, h: 0.58, rectRadius: 0.08, fill: { color: i === 2 ? COL.qual : "FFFFFF" }, line: { color: i === 2 ? COL.qual : COL.rule, width: 1.25 } });
    T(s, t, { x: 6.9, y, w: 3.5, h: 0.58, fontSize: 14, bold: i === 2, color: i === 2 ? "FFFFFF" : COL.ink, valign: "middle" });
    if (i < steps.length - 1) s.addShape(pres.shapes.LINE, { x: 8.6, y: y + 0.58, w: 0, h: 0.2, line: { color: COL.faint, width: 1.25, endArrowType: "triangle" } });
  });
  T(s, "The whole research question is step 3: which score?", { x: 6.7, y: 6.15, w: 6, h: 0.6, fontSize: 15, italic: true, color: COL.qual, bold: true });
  T(s, "A list scheduler never idles a worker while a task is ready. On small graphs the best such schedule is within 0.02–0.03% of the true optimum (exact experiments, Phases 1 and 3).", { x: 10.85, y: 2.15, w: 1.88, h: 3.9, fontSize: 12, color: COL.muted });
}

// =====================================================================
// PART II
// =====================================================================
sec("II · Our scheduling algorithm");
divider("II", "Our scheduling algorithm", "Static graph analysis, dynamic state-aware scoring, and how they were combined in the original design.");
{
  const s = content("Overall architecture", "What happens every time a worker becomes available.");
  const boxes = [
    ["DAG", "tasks, durations, dependencies; cycle check", COL.ink2],
    ["Static analysis", "once: critical path, blocking power, reachable work → static score", COL.cp],
    ["Ready tasks", "priority queue ordered by static score", COL.ink2],
    ["Dynamic scoring", "top-K candidates rescored with the current state", COL.fast],
    ["Lookahead", "on contested decisions: simulate each candidate forward", COL.qual],
    ["Assignment", "start the chosen task on the free worker", COL.ink2],
    ["State update", "on completion: release children whose parents are all done", COL.ink2],
  ];
  const bw = 1.62, gap = 0.12, y = 2.0;
  boxes.forEach(([h, t, c], i) => {
    const x = 0.6 + i * (bw + gap);
    s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x, y, w: bw, h: 0.75, rectRadius: 0.08, fill: { color: c }, line: { color: c, width: 0 } });
    T(s, h, { x, y, w: bw, h: 0.75, fontSize: 14, bold: true, color: "FFFFFF", align: "center", valign: "middle" });
    T(s, t, { x, y: y + 0.85, w: bw, h: 1.1, fontSize: 11, color: COL.ink2, align: "center" });
    if (i < boxes.length - 1) s.addShape(pres.shapes.LINE, { x: x + bw, y: y + 0.375, w: gap, h: 0, line: { color: COL.faint, width: 1.25, endArrowType: "triangle" } });
  });
  // loop arrow back
  s.addShape(pres.shapes.LINE, { x: 0.6 + 6 * (bw + gap) + bw / 2, y: 4.0, w: 0, h: 0.35, line: { color: COL.faint, width: 1.25 } });
  s.addShape(pres.shapes.LINE, { x: 0.6 + 2 * (bw + gap) + bw / 2, y: 4.35, w: 4 * (bw + gap), h: 0, line: { color: COL.faint, width: 1.25 } });
  s.addShape(pres.shapes.LINE, { x: 0.6 + 2 * (bw + gap) + bw / 2, y: 4.0, w: 0, h: 0.35, flipV: true, line: { color: COL.faint, width: 1.25, endArrowType: "triangle" } });
  T(s, "repeat until all tasks are done", { x: 4.6, y: 4.42, w: 4, h: 0.3, fontSize: 11, color: COL.muted, align: "center", italic: true });
  panel(s, 0.6, 5.0, 5.9, 1.85);
  T(s, "Original design (Original scheduler)", { x: 0.85, y: 5.12, w: 5.5, h: 0.3, fontSize: 14, bold: true, color: COL.ink });
  T(s, "Top K = 3 tasks by static score → blended score ½·static + ½·dynamic → an idle-window lookahead compared the best two when their scores differed by more than 0.1.", { x: 0.85, y: 5.45, w: 5.4, h: 1.3, fontSize: 13, color: COL.ink2 });
  panel(s, 6.85, 5.0, 5.88, 1.85);
  T(s, "Final design (QUALITY_OPTIMIZED)", { x: 7.1, y: 5.12, w: 5.5, h: 0.3, fontSize: 14, bold: true, color: COL.qual });
  T(s, "Top K = 5 by static score → learned blend (λ ≈ 0.999) → rollout lookahead on every contested decision (more ready tasks than free workers), depth 8, 5 candidates.", { x: 7.1, y: 5.45, w: 5.4, h: 1.3, fontSize: 13, color: COL.ink2 });
}

// ---- Critical path ----
componentSlide({
  title: "Static component 1 · Critical path", kicker: "The length of the longest dependency chain that starts at the task.",
  formula: "cp(v) = d(v) + max { cp(c) : c ∈ children(v) }\ncp(v) = d(v) for tasks with no children",
  measures: "The remaining “bottom-level” time: how long the work hanging below v takes even with unlimited workers.",
  why: "Delaying a task on a long chain delays everything after it, so the whole project finishes later. Starting long chains early protects the critical path.",
  src: "TimeConstraints.criticalPaths(); normalised by the overall critical-path length.",
  help: { k: "CPHELP", head: "2 workers · C → D is the long chain",
    values: vals("CPHELP", (t) => t.cp), valName: "cp", nodeStyle: nodeStyle([3, 4], COL.cp),
    gantts: [{ p: "CP_ONLY", cap: "Critical Path First", color: COL.cp, style: styleFor([3, 4], COL.cp) }, { p: "SPT", cap: "Shortest task first", color: COL.orig }],
    note: "CP starts C (cp 12) at once; shortest-first delays it and D waits until 10. 12 is optimal." },
  fail: { k: "CPFAIL", head: "2 workers · the short task B unlocks D",
    values: vals("CPFAIL", (t) => t.cp), valName: "cp", nodeStyle: nodeStyle([2], COL.qual),
    gantts: [{ p: "CP_ONLY", cap: "Critical Path First", color: COL.cp, style: styleFor([2, 4], COL.cp) }, { p: "BP_ONLY", cap: "Optimal (start B first)", color: COL.good, style: styleFor([2, 4], COL.qual) }],
    note: "Misses when workers free up: B (cp 3) loses to C (cp 4), so D is released late and a worker idles at t = 5. Optimum 6." },
  notes: "Both instances verified with the real scheduler code (CP-only configuration: static weights (1,0,0), lambda=1, K=1, no lookahead) and with the repository ExactSolver.",
});
componentSlide({
  title: "Static component 2 · Blocking power", kicker: "How much of the remaining graph waits directly on this task.",
  formula: "bp(v) = Σ  1 / |parents(c)|\n           c ∈ children(v)",
  measures: "Each child contributes its share of “unblocking”: a sole-parent child counts 1, a child with k parents counts 1/k.",
  why: "Finishing tasks that unblock others keeps the ready queue full, so workers are less likely to sit idle.",
  src: "TimeConstraints.blockPower(); normalised by the maximum bp in the DAG. Ties are broken by task id.",
  help: { k: "CPFAIL", head: "same instance where CP failed",
    values: vals("CPFAIL", (t) => t.bp), valName: "bp", nodeStyle: nodeStyle([2], COL.qual),
    gantts: [{ p: "BP_ONLY", cap: "Blocking power only", color: COL.qual, style: styleFor([2, 4], COL.qual) }, { p: "CP_ONLY", cap: "Critical Path First", color: COL.cp }],
    note: "bp(B) = 1, everything else 0: B goes first, D is released at t = 2 and the schedule reaches the optimum 6." },
  fail: { k: "BPFAIL", head: "2 workers · B is long but unblocks nothing",
    values: vals("BPFAIL", (t) => t.bp), valName: "bp", nodeStyle: nodeStyle([2], COL.cp),
    gantts: [{ p: "BP_ONLY", cap: "Blocking power only", color: COL.qual, style: styleFor([2], COL.bad) }, { p: "CP_ONLY", cap: "Optimal (Critical Path First)", color: COL.good, style: styleFor([2], COL.cp) }],
    note: "Misses durations: A and C (bp ½ each) beat B (bp 0), so the 5-unit task B starts late. Optimum 5." },
});
componentSlide({
  title: "Static component 3 · Reachable workload", kicker: "The total work that depends, directly or indirectly, on this task.",
  formula: "reach(v) = Σ  d(u)\n  u ∈ {v} ∪ descendants(v)",
  measures: "The volume of work downstream of v, including v itself. Overlapping descendants are counted once.",
  why: "A short task that feeds a large amount of downstream work should run early, even if no single chain below it is long.",
  src: "TimeConstraints.reachableDuration(); normalised by total work (original) or by max reach (optimised presets).",
  help: { k: "REACHHELP", head: "2 workers · A feeds both C and E",
    values: vals("REACHHELP", (t) => t.reach), valName: "reach", nodeStyle: nodeStyle([1], COL.qual),
    gantts: [{ p: "REACH_ONLY", cap: "Reachable workload only", color: COL.qual, style: styleFor([1], COL.qual) }, { p: "CP_ONLY", cap: "Critical Path First", color: COL.cp, style: styleFor([1], COL.bad) }],
    note: "reach(A) = 12 is the largest, although cp(A) = 8 is the smallest. Starting A first gives the optimum 11; CP gets 13." },
  fail: { k: "REACHFAIL", head: "2 workers · E is long and independent",
    values: vals("REACHFAIL", (t) => t.reach), valName: "reach", nodeStyle: nodeStyle([5], COL.cp),
    gantts: [{ p: "REACH_ONLY", cap: "Reachable workload only", color: COL.qual, style: styleFor([5], COL.bad) }, { p: "CP_ONLY", cap: "Optimal (Critical Path First)", color: COL.good, style: styleFor([5], COL.cp) }],
    note: "Misses chain length and timing: A (8) and B (7) outrank E (6), so the 6-unit E starts late. Optimum 9." },
});
{
  const s = content("Combining the static components", "One normalised score per task, computed once before scheduling starts.");
  panel(s, 0.6, 1.6, 12.13, 1.45, COL.panel2);
  T(s, [
    { text: "static(v) = ", options: {} },
    { text: "0.50", options: { color: COL.cp, bold: true } }, { text: " · cp(v) / cp_max  +  " },
    { text: "0.25", options: { color: COL.fast, bold: true } }, { text: " · bp(v) / bp_max  +  " },
    { text: "0.25", options: { color: COL.b0, bold: true } }, { text: " · reach(v) / W" },
  ], { x: 0.8, y: 1.6, w: 11.7, h: 1.0, fontFace: HEAD, fontSize: 24, italic: true, color: COL.ink, align: "center", valign: "middle" });
  T(s, "cp_max = overall critical-path length · bp_max = largest blocking power · W = total work Σ d(v). Optimised presets divide reach by max reach instead of W (Phase 1, Change 11).", { x: 0.8, y: 2.55, w: 11.7, h: 0.4, fontSize: 12, color: COL.muted, align: "center" });
  T(s, "Why combine instead of choosing one?", { x: 0.6, y: 3.3, w: 6, h: 0.35, fontSize: 17, bold: true, fontFace: HEAD });
  bullets(s, [
    "Each component sees one kind of information and has its own blind spot (previous three slides).",
    "Normalising to [0, 1] makes them comparable across graphs of any size.",
    "Weights express a prior: chain length matters most, unblocking and downstream volume second.",
    [B("The weights 0.5 / 0.25 / 0.25 were a design choice, not derived or tuned. "), { text: "Testing that choice is Part IV." }],
  ], { x: 0.6, y: 3.75, w: 6.0, h: 3.0, fontSize: 14, gap: 8 });
  // static scores on CPFAIL & REACHFAIL
  const sc = (k) => { const t = ex(k).tasks; const ids = Object.keys(t); const cpm = Math.max(...ids.map((i) => t[i].cp)); const bpm = Math.max(...ids.map((i) => t[i].bp)); const Wt = ids.reduce((a, i) => a + t[i].dur, 0);
    return Object.fromEntries(ids.map((i) => [i, 0.5 * t[i].cp / cpm + (bpm ? 0.25 * t[i].bp / bpm : 0) + 0.25 * t[i].reach / Wt])); };
  const two = [["CPFAIL", "Fixes the CP failure", "STATIC_ORIG", "Combined static score: Cmax 6 (optimal; CP-only gives 7)"], ["REACHFAIL", "But inherits the reach failure", "STATIC_ORIG", "Combined static score: Cmax 10 (optimum 9)"]];
  two.forEach(([k, h, p, cap], i) => {
    const x = 6.95 + i * 2.95;
    panel(s, x, 3.3, 2.78, 3.55);
    T(s, h, { x: x + 0.15, y: 3.4, w: 2.5, h: 0.3, fontSize: 13, bold: true, color: i === 0 ? COL.good : COL.bad });
    const v = sc(k);
    dag(pres, s, x + 0.25, 3.85, 2.25, 1.35, ex(k).tasks, { nodeW: 0.48, nodeH: 0.42, fs: 12, values: Object.fromEntries(Object.entries(v).map(([a, b]) => [a, b.toFixed(2)])) });
    T(s, cap, { x: x + 0.15, y: 5.65, w: 2.5, h: 0.6, fontSize: 11, color: COL.ink2 });
    T(s, "numbers under tasks = static score", { x: x + 0.15, y: 6.35, w: 2.5, h: 0.3, fontSize: 10, color: COL.faint });
  });
}
// ---- dynamic components ----
{
  const s = content("Dynamic components: scoring the current state", "Five signals recomputed at every decision for the top-K ready tasks. They depend on what is running and what is ready now.");
  const rows = [
    ["openToBlock", "0.20", "share of v’s children that v alone still blocks", "open(v) / |children(v)|"],
    ["openChildren", "0.10", "children released by v, relative to the worker count", "min(1, open(v) / m)"],
    ["childImportance", "0.15", "average static score of the children v would release", "Σ static(c) / (open(v) · static_max)"],
    ["durationToReady", "0.30", "long tasks when many are ready, short tasks when few are", "α·d̂(v) + (1−α)(1−d̂(v))"],
    ["childrenToReadyQueue", "0.25", "children released, but only when the ready queue is short", "(1−α) · open(v) / maxChildren"],
  ];
  T(s, "component", { x: 0.6, y: 1.65, w: 2.6, h: 0.3, fontSize: 11, bold: true, color: COL.muted });
  T(s, "weight", { x: 3.2, y: 1.65, w: 0.9, h: 0.3, fontSize: 11, bold: true, color: COL.muted });
  T(s, "intent", { x: 4.1, y: 1.65, w: 4.3, h: 0.3, fontSize: 11, bold: true, color: COL.muted });
  T(s, "formula (DynamicScoreLogic.makeDynamicScore)", { x: 8.5, y: 1.65, w: 4.3, h: 0.3, fontSize: 11, bold: true, color: COL.muted });
  rows.forEach(([n, w_, i, f], k) => {
    const y = 2.0 + k * 0.72;
    panel(s, 0.6, y, 12.13, 0.62, k % 2 ? "FFFFFF" : COL.panel);
    T(s, n, { x: 0.75, y, w: 2.5, h: 0.62, fontSize: 14, bold: true, valign: "middle" });
    T(s, w_, { x: 3.2, y, w: 0.9, h: 0.62, fontSize: 14, valign: "middle", color: COL.fast, bold: true });
    T(s, i, { x: 4.1, y, w: 4.3, h: 0.62, fontSize: 13, valign: "middle", color: COL.ink2 });
    T(s, f, { x: 8.5, y, w: 4.2, h: 0.62, fontSize: 14, valign: "middle", fontFace: HEAD, italic: true });
  });
  panel(s, 0.6, 5.75, 12.13, 1.1, COL.panel2);
  T(s, [
    { text: "Shared notation.  ", options: { bold: true } },
    { text: "open(v) = number of children c whose only unfinished parent is v (|parentsRemaining(c)| = 1).  m = total workers.  α = min(1, |ready| / m) is the workload pressure.  d̂(v) = d(v) / d_max.  maxChildren = the largest out-degree in the DAG.  dynamic(v) = 0.2·openToBlock + 0.1·openChildren + 0.15·childImportance + 0.3·durationToReady + 0.25·childrenToReadyQueue." },
  ], { x: 0.8, y: 5.82, w: 11.75, h: 1.0, fontSize: 12, color: COL.ink2, valign: "middle" });
}
const dynNote = "Illustration policy: rank all ready tasks by this component alone (λ = 0, K = all, no lookahead, batched completions); ties fall back to static order, as in the implementation.";
componentSlide({
  title: "Dynamic 1 · Open-to-block ratio", kicker: "What fraction of my children am I the last thing blocking?",
  formula: "openToBlock(v) = open(v) / |children(v)|\n(0 if v has no children)",
  measures: "Whether finishing v would actually release work now, rather than just reduce a child’s parent count.",
  why: "A task that is the final blocker of its children turns into new ready work the moment it ends, which helps avoid idle workers.",
  src: dynNote,
  help: { k: "UNLOCKHELP", head: "2 workers · A is the last blocker of C",
    valName: "openToBlock at t = 0", values: { 1: "1.0", 2: "0", 3: "0", 4: "0" }, nodeStyle: nodeStyle([1], COL.qual),
    gantts: [{ p: "D_OPEN2BLOCK", cap: "Open-to-block only", color: COL.qual, style: styleFor([1], COL.qual) }, { p: "CP_ONLY", cap: "Critical Path First", color: COL.cp, style: styleFor([1], COL.bad) }],
    note: "A scores 1.0, B and D score 0, so A starts at t = 0 and C is ready early. Optimum 10; CP (B, D first) gets 11." },
  fail: { k: "UNLOCKFAIL", head: "2 workers · B unlocks only a 1-unit task",
    valName: "openToBlock at t = 0", values: { 1: "0", 2: "1.0", 3: "0", 4: "0", 5: "0" }, nodeStyle: nodeStyle([1], COL.cp),
    gantts: [{ p: "D_OPEN2BLOCK", cap: "Open-to-block only", color: COL.qual, style: styleFor([1], COL.bad) }, { p: "CP_ONLY", cap: "Optimal (Critical Path First)", color: COL.good, style: styleFor([1], COL.cp) }],
    note: "Misses how much the released work matters: B (1.0) takes a worker at t = 0, so D and E (4 units each) queue behind it on one worker. Optimum 8." },
});
componentSlide({
  title: "Dynamic 2 · Released children vs. workers", kicker: "Will finishing me release enough work to keep the other workers busy?",
  formula: "openChildren(v) = min(1, open(v) / m)",
  measures: "The number of children v would release, as a share of the worker count m and capped at 1.",
  why: "If m workers are about to free up, a task that releases several children can feed them, which reduces starvation.",
  src: dynNote,
  help: { k: "PQHELP", head: "3 workers · A releases D",
    valName: "openChildren at t = 0", values: { 1: "⅓", 2: "0", 3: "0", 4: "0", 5: "0" }, nodeStyle: nodeStyle([1], COL.qual), gh: 0.95, dagH: 1.0,
    gantts: [{ p: "D_OPENCHILD", cap: "Released children only", color: COL.qual, style: styleFor([1, 4], COL.qual) }, { p: "CP_ONLY", cap: "Critical Path First", color: COL.cp, style: styleFor([1, 4], COL.bad) }],
    note: "A (⅓) starts at t = 0 with E and B, so D is ready at t = 1. Optimum 6; CP starts E, B, C, delays A to t = 5, and gets 7." },
  fail: { k: "PQFAIL", head: "3 workers · A releases only a 1-unit task",
    valName: "openChildren at t = 0", values: { 1: "⅓", 2: "0", 3: "0", 4: "0", 5: "0", 6: "0" }, nodeStyle: nodeStyle([5, 6], COL.cp), gh: 0.95, dagH: 1.0,
    gantts: [{ p: "D_OPENCHILD", cap: "Released children only", color: COL.qual, style: styleFor([5, 6], COL.bad) }, { p: "CP_ONLY", cap: "Optimal (Critical Path First)", color: COL.good, style: styleFor([5], COL.cp) }],
    note: "Misses durations: A (⅓) takes a worker at t = 0 ahead of F (5) and E (3), pushing E to t = 6. Cmax 9 vs optimum 8." },
});
componentSlide({
  title: "Dynamic 3 · Child importance", kicker: "Are the children I release themselves important?",
  formula: "childImportance(v) = mean of static(c) over\nopen children c, ÷ static_max   (0 if none)",
  formulaSize: 14,
  measures: "The average static score of the children v would release, normalised by the highest static score in the DAG.",
  why: "Releasing a child that sits on the critical path should matter more than releasing a leaf.",
  src: dynNote,
  help: { k: "UNLOCKHELP", head: "2 workers · same instance as Dynamic 1",
    valName: "childImportance at t = 0", values: { 1: "0.13", 2: "0", 3: "0", 4: "0" }, nodeStyle: nodeStyle([1], COL.qual),
    gantts: [{ p: "D_CHILDIMP", cap: "Child importance only", color: COL.qual, style: styleFor([1], COL.qual) }, { p: "CP_ONLY", cap: "Critical Path First", color: COL.cp, style: styleFor([1], COL.bad) }],
    note: "Even a modest child score (0.13) beats the 0 of tasks that release nothing. Same schedule as open-to-block. Optimum 10." },
  fail: { k: "UNLOCKFAIL", head: "2 workers · same instance as Dynamic 1",
    valName: "childImportance at t = 0", values: { 1: "0", 2: "0.17", 3: "0", 4: "0", 5: "0" }, nodeStyle: nodeStyle([1], COL.cp),
    gantts: [{ p: "D_CHILDIMP", cap: "Child importance only", color: COL.qual, style: styleFor([1], COL.bad) }, { p: "CP_ONLY", cap: "Optimal (Critical Path First)", color: COL.good, style: styleFor([1], COL.cp) }],
    note: "Misses the releasing task’s own urgency: any open child (0.17) beats 0, so the 1-unit B takes a worker ahead of D and E. Cmax 9 vs optimum 8." },
});
componentSlide({
  title: "Dynamic 4 · Duration-aware scoring", kicker: "Prefer long tasks under heavy load, short tasks under light load.",
  formula: "durationToReady(v) = α·d̂(v) + (1−α)·(1−d̂(v))\nα = min(1, |ready| / m),  d̂ = d / d_max",
  formulaSize: 14,
  measures: "When at least m tasks are ready (α = 1) it ranks by duration, like LPT. With few ready tasks it ranks short tasks first.",
  why: "Under load, starting long work early avoids a long tail. When work is scarce, short tasks finish quickly and may release more.",
  src: dynNote,
  help: { k: "DURHELP", head: "2 workers · E is long and independent",
    valName: "durationToReady at t = 0", values: { 1: "0.33", 2: "0.50", 3: "", 4: "", 5: "1.0" }, nodeStyle: nodeStyle([5], COL.qual),
    gantts: [{ p: "D_DUR2READY", cap: "Duration-aware only", color: COL.qual, style: styleFor([5], COL.qual) }, { p: "CP_ONLY", cap: "Critical Path First", color: COL.cp, style: styleFor([5], COL.bad) }],
    note: "Three ready tasks for 2 workers, so α = 1: E (6 units) starts at once. Optimum 10; CP starts E at t = 2 and gets 12." },
  fail: { k: "DURFAIL", head: "2 workers · A heads a chain A → B",
    valName: "durationToReady at t = 0", values: { 1: "0.40", 2: "", 3: "1.0", 4: "0.80" }, nodeStyle: nodeStyle([1], COL.cp),
    gantts: [{ p: "D_DUR2READY", cap: "Duration-aware only", color: COL.qual, style: styleFor([1, 2], COL.bad) }, { p: "CP_ONLY", cap: "Optimal (Critical Path First)", color: COL.good, style: styleFor([1, 2], COL.cp) }],
    note: "Misses dependencies: as pure LPT it starts C and D, delaying the chain head A. Cmax 8 vs optimum 7." },
});
componentSlide({
  title: "Dynamic 5 · Children to ready queue", kicker: "Release new work when the ready queue is running dry.",
  formula: "childrenToReadyQueue(v) = (1−α) · open(v) / maxChildren",
  measures: "Released children scaled by maxChildren (largest out-degree). Exactly 0 whenever |ready| ≥ m, i.e. in most heavily contested decisions.",
  why: "When fewer tasks are ready than workers exist, the priority is to refill the queue.",
  src: dynNote,
  help: { k: "PQHELP", head: "3 workers · 2 ready at the third pick → α = ⅔",
    valName: "childrenToReadyQueue, last pick at t = 0", values: { 1: "0.33", 2: "", 3: "0", 4: "", 5: "" }, nodeStyle: nodeStyle([1], COL.qual), gh: 0.95, dagH: 1.0,
    gantts: [{ p: "D_CHILD2PQ", cap: "Children-to-queue only", color: COL.qual, style: styleFor([1, 4], COL.qual) }, { p: "CP_ONLY", cap: "Critical Path First", color: COL.cp, style: styleFor([1, 4], COL.bad) }],
    note: "With 2 tasks ready for the last free worker, A scores ⅓ and C scores 0, so A goes first. Optimum 6; CP gets 7." },
  fail: { k: "PQFAIL", head: "3 workers · decision at t = 5, 2 ready",
    valName: "childrenToReadyQueue at t = 5", values: { 1: "0.33", 2: "", 3: "", 4: "", 5: "0", 6: "" }, nodeStyle: nodeStyle([5], COL.cp), gh: 0.95, dagH: 1.0,
    gantts: [{ p: "D_CHILD2PQ", cap: "Children-to-queue only", color: COL.qual, style: styleFor([5], COL.bad) }, { p: "CP_ONLY", cap: "Optimal (Critical Path First)", color: COL.good, style: styleFor([5], COL.cp) }],
    note: "Misses durations: A (⅓) beats E (0) although E is 3 units long. Cmax 9 vs optimum 8." },
});
{
  const s = content("The components overlap", "Most dynamic signals are different scalings of one quantity, open(v): the work v would release now.");
  const cols = ["open(v)", "d(v)", "children’s static score", "α (load)", "graph structure"];
  const rows = [
    ["Blocking power (static)", [1, 0, 0, 0, 1]], ["openToBlock", [1, 0, 0, 0, 0]], ["openChildren", [1, 0, 0, 0, 0]], ["childImportance", [1, 0, 1, 0, 0]],
    ["childrenToReadyQueue", [1, 0, 0, 1, 0]], ["durationToReady", [0, 1, 0, 1, 0]], ["Critical path (static)", [0, 1, 0, 0, 1]], ["Reachable work (static)", [0, 1, 0, 0, 1]],
  ];
  const x0 = 3.6, cw = 1.25;
  cols.forEach((c, j) => T(s, c, { x: x0 + j * cw, y: 1.62, w: cw, h: 0.55, fontSize: 11, bold: true, color: COL.muted, align: "center", valign: "bottom" }));
  rows.forEach(([n, v], i) => {
    const y = 2.25 + i * 0.53;
    T(s, n, { x: 0.6, y, w: 2.95, h: 0.45, fontSize: 13, bold: i >= 1 && i <= 5 ? false : true, valign: "middle" });
    v.forEach((on, j) => {
      const c = j === 0 ? COL.qual : j === 1 ? COL.cp : j === 3 ? COL.fast : COL.ink2;
      s.addShape(pres.shapes.OVAL, { x: x0 + j * cw + cw / 2 - 0.13, y: y + 0.09, w: 0.26, h: 0.26, fill: { color: on ? c : "FFFFFF" }, line: { color: on ? c : COL.rule, width: 1 } });
    });
  });
  panel(s, 10.1, 1.65, 2.63, 3.6);
  T(s, "Consequences", { x: 10.3, y: 1.8, w: 2.3, h: 0.3, fontSize: 14, bold: true });
  bullets(s, [
    "Four dynamic signals rise and fall together with open(v).",
    "On our verified examples the three unlocking components produced identical schedules.",
    "Correlated features make individual weights hard to identify. Optimisation later finds a flat plateau (Part IV).",
  ], { x: 10.3, y: 2.2, w: 2.3, h: 4.5, fontSize: 12, gap: 8 });
}
{
  const s = content("Combining static and dynamic scores", "The original blend, and how many candidates it considered.");
  panel(s, 0.6, 1.6, 7.2, 1.4, COL.panel2);
  T(s, [{ text: "score(v) = " }, { text: "λ", options: { bold: true, color: COL.qual } }, { text: " · static(v) + (1 − " }, { text: "λ", options: { bold: true, color: COL.qual } }, { text: ") · dynamic(v)" }],
    { x: 0.8, y: 1.6, w: 6.8, h: 0.95, fontFace: HEAD, italic: true, fontSize: 24, align: "center", valign: "middle" });
  T(s, "Original λ = 0.5, i.e. (static + dynamic) / 2", { x: 0.8, y: 2.5, w: 6.8, h: 0.35, fontSize: 13, color: COL.muted, align: "center" });
  bullets(s, [
    [B("Candidate pool. "), { text: "Only the K = 3 best ready tasks by static score are rescored dynamically (K is configurable in the research code)." }],
    [B("Blending factor λ "), { text: "sets how much the current state may override the graph structure: λ = 1 is pure static priority, λ = 0 is pure dynamic." }],
    [B("Original lookahead. "), { text: "If the top two blended scores differed by more than 0.1 under the load gate, an idle-window estimate was added. This is replaced by the rollout in the final design (Part III)." }],
    [B("All initial weights were heuristic choices: "), { text: "static 0.5 / 0.25 / 0.25, dynamic 0.2 / 0.1 / 0.15 / 0.3 / 0.25, λ = 0.5. None were proven or tuned." }],
  ], { x: 0.6, y: 3.25, w: 7.2, h: 3.6, fontSize: 14, gap: 9 });
  // weights chart
  s.addChart(pres.charts.BAR, [{ name: "Original weight", labels: ["critical path", "blocking power", "reach", "openToBlock", "openChildren", "childImportance", "durationToReady", "childrenToReadyQ"], values: [0.5, 0.25, 0.25, 0.2, 0.1, 0.15, 0.3, 0.25] }],
    chartOpts({ x: 8.1, y: 1.55, w: 4.63, h: 5.3, barDir: "bar", chartColors: [COL.orig], showValue: true, dataLabelPosition: "outEnd", dataLabelFormatCode: "0.00", valAxisMaxVal: 0.6, valAxisMinVal: 0, valAxisHidden: true, valGridLine: { style: "none" }, catAxisOrientation: "maxMin",
      showTitle: true, title: "Original weights (static above, dynamic below)", showLegend: false, barGapWidthPct: 60 }));
}

// =====================================================================
// PART III
// =====================================================================
sec("III · Final lookahead");
divider("III", "Final lookahead", "Local scores cannot see when workers become free. A bounded rollout can.");
{
  const s = content("Why local scoring is not enough: worker starvation", "A real benchmark instance (Phase 3, exact set): 10 tasks, 2 workers. Critical Path First: 31. Optimum: 29.");
  const e = ex("STENCIL191");
  panel(s, 0.6, 1.6, 4.3, 5.25);
  dag(pres, s, 0.85, 1.85, 3.8, 2.85, e.tasks, { letters: "T", nodeW: 0.56, nodeH: 0.44, fs: 11, style: Object.assign(nodeStyle([9], COL.cp), nodeStyle([6], COL.qual)) });
  T(s, "At t = 8 one worker is free and two tasks are ready:", { x: 0.85, y: 4.9, w: 3.9, h: 0.3, fontSize: 12, bold: true });
  T(s, [{ text: "T9", options: { bold: true, color: COL.cp } }, { text: "  cp 17 · duration 10 · releases nothing alone", options: { breakLine: true } },
        { text: "T6", options: { bold: true, color: COL.qual } }, { text: "  cp 14 · duration 2 · co-parent of T7 and T10" }],
    { x: 0.85, y: 5.25, w: 3.9, h: 0.7, fontSize: 12, color: COL.ink2 });
  T(s, "Critical-path order says T9. But T7 needs T6, and T7 is what the other worker should run when T3 ends at t = 13.", { x: 0.85, y: 6.0, w: 3.9, h: 0.8, fontSize: 12, color: COL.ink2 });
  const st1 = Object.assign(styleFor([9], COL.cp), styleFor([6], COL.qual));
  ganttBlock(s, 5.2, 1.65, 7.5, "STENCIL191", "CP_ONLY", "Critical Path First: start T9 at t = 8", COL.cp, st1, { letters: "T", h: 1.2, T: 31 });
  ganttBlock(s, 5.2, 3.45, 7.5, "STENCIL191", "QUALITY_OPT", "Start T6 at t = 8 (what the lookahead chooses)", COL.qual, st1, { letters: "T", h: 1.2, T: 31 });
  panel(s, 5.2, 5.3, 7.53, 1.55, COL.panel2);
  T(s, [{ text: "What went wrong.  ", options: { bold: true } }, { text: "With T9 first, T6 waits until t = 13, T7 starts at 15 and T10 at 24, and one worker sits idle from 27 to 31. Starting the short T6 first costs T9 two units of slack but lets T7 start at 13. The difference comes from the timing of future completions, which no static or one-step score sees." }],
    { x: 5.4, y: 5.38, w: 7.15, h: 1.4, fontSize: 13, color: COL.ink2, valign: "middle" });
  s.addNotes("Both schedules produced by the real scheduler code (research archive): CP-only configuration gives 31, QUALITY_OPTIMIZED gives 29. Optimum 29 and best list schedule 29 from ExactSolver; matches PHASE3_REPORT.md Example 2.");
}
{
  const s = content("Our final lookahead: bounded rollout", "Used in PHASE1_ROLLOUT and QUALITY_OPTIMIZED; validated in Phases 1–3.");
  const steps = [
    ["Activate", "Only on contested decisions: more ready tasks than workers able to start one now (idle workers, the deciding one, and those finishing at this instant)."],
    ["Select candidates", "Take the K = 5 best ready tasks by static score, order them by blended score, and keep the first L = 5. The static best is always included."],
    ["Simulate", "For each candidate: start it now, then continue with deterministic static-priority list scheduling (ties by id). Simultaneous completions are applied together; idle workers take ready tasks at once."],
    ["Bound the horizon", "Stop after 8 completion events (depth 8). Running tasks keep their true remaining times."],
    ["Evaluate", "If the simulation finished, use its exact makespan. Otherwise use a lower bound on the final makespan of the reached state (right)."],
    ["Choose", "Start the candidate with the smallest estimate. Ties keep the blended-score order. The real ready queue and workers are never modified."],
  ];
  steps.forEach(([h, t], i) => {
    const y = 1.6 + i * 0.86;
    s.addShape(pres.shapes.OVAL, { x: 0.6, y: y + 0.08, w: 0.42, h: 0.42, fill: { color: COL.qual }, line: { color: COL.qual, width: 0 } });
    T(s, String(i + 1), { x: 0.6, y: y + 0.08, w: 0.42, h: 0.42, fontSize: 14, bold: true, color: "FFFFFF", align: "center", valign: "middle" });
    T(s, h, { x: 1.2, y: y + 0.03, w: 1.9, h: 0.55, fontSize: 15, bold: true, valign: "middle" });
    T(s, t, { x: 3.1, y: y, w: 5.2, h: 0.82, fontSize: 12.5, color: COL.ink2, valign: "middle" });
  });
  panel(s, 8.6, 1.6, 4.13, 5.25, COL.panel2);
  T(s, "STATE EVALUATION AFTER d EVENTS", { x: 8.8, y: 1.75, w: 3.8, h: 0.25, fontSize: 10, bold: true, color: COL.muted, charSpacing: 1 });
  T(s, "Ĉ = max(\n   max over running j of  end(j) + cp(j) − d(j),\n   max over ready j of  t + cp(j),\n   t + ⌈ remaining work / m ⌉ )", { x: 8.8, y: 2.1, w: 3.8, h: 1.9, fontFace: HEAD, italic: true, fontSize: 14, color: COL.ink });
  T(s, "A valid lower bound on the makespan reachable from the simulated state: chain bound plus work bound.", { x: 8.8, y: 3.75, w: 3.8, h: 0.8, fontSize: 12, color: COL.ink2 });
  T(s, "Why this replaced the original lookahead", { x: 8.8, y: 4.75, w: 3.8, h: 0.3, fontSize: 12, bold: true });
  T(s, "Every candidate is compared on one common quantity, the estimated final makespan, rather than candidate-specific idle windows.", { x: 8.8, y: 5.05, w: 3.8, h: 1.4, fontSize: 12, color: COL.ink2 });
  s.addNotes("Source: research/archive/phase1-4-research-source TaskEngine.rolloutLookahead and DynamicScoreLogic.rolloutMakespan / makespanLowerBound. Preset values: lookaheadCandidates 5, rolloutDepth 8, candidatePoolSize 5, contestedDecisionsOnly, ANY_GAP, BATCHED, reach/max, deterministic tie-break. The previous idle-window lookahead was measured to be harmful (removing it improved the original by 0.29-0.33%); its results are not attributed to this mechanism.");
}
{
  const s = content("Lookahead in action", "QUALITY_OPTIMIZED on the instance from the previous slide, t = 8. Numbers are the real rollout outputs (instrumented run).");
  const e = ex("STENCIL191");
  // current state
  panel(s, 0.6, 1.6, 3.6, 5.25);
  T(s, "CURRENT STATE  ·  t = 8", { x: 0.8, y: 1.72, w: 3.3, h: 0.25, fontSize: 10, bold: true, color: COL.muted, charSpacing: 1 });
  const done = { 1: 1, 2: 1, 5: 1 };
  const st = {}; Object.keys(e.tasks).forEach((i) => (st[i] = done[i] ? { fill: COL.panel2, line: COL.rule, text: COL.faint, sub: COL.faint } : {}));
  st[3] = { fill: COL.ink2, line: COL.ink2, text: "FFFFFF", sub: "C9CFDA" };
  Object.assign(st, nodeStyle([9], COL.cp), nodeStyle([6], COL.qual));
  dag(pres, s, 0.8, 2.1, 3.2, 2.35, e.tasks, { letters: "T", nodeW: 0.5, nodeH: 0.4, fs: 11, style: st });
  bullets(s, [
    [B("Done: "), { text: "T1, T2, T5" }], [B("Running: "), { text: "T3 on W1 until t = 13" }], [B("Free: "), { text: "W2" }],
    [B("Ready: "), { text: "T9, T6 → contested (2 ready, 1 free)" }],
    [B("Blended score: "), { text: "T9 0.680 > T6 0.560" }],
  ], { x: 0.8, y: 4.65, w: 3.3, h: 2.1, fontSize: 12, gap: 3 });
  // two simulated futures
  const fade = {}; [1, 2, 5, 3].forEach((i) => (fade[i] = { fill: COL.panel2, text: COL.faint }));
  T(s, "SIMULATED FUTURES (8-event rollout, continuation by static priority)", { x: 4.5, y: 1.72, w: 8.2, h: 0.25, fontSize: 10, bold: true, color: COL.muted, charSpacing: 1 });
  ganttBlock(s, 4.5, 2.05, 6.3, "STENCIL191", "CP_ONLY", "Candidate T9", COL.cp, Object.assign({}, fade, styleFor([9], COL.cp)), { letters: "T", h: 1.05, T: 31 });
  ganttBlock(s, 4.5, 3.6, 6.3, "STENCIL191", "QUALITY_OPT", "Candidate T6", COL.qual, Object.assign({}, fade, styleFor([6], COL.qual)), { letters: "T", h: 1.05, T: 31 });
  [[2.35, "31", COL.bad, "estimate"], [3.9, "29", COL.good, "estimate"]].forEach(([y, v, c, l]) => {
    T(s, v, { x: 11.05, y: y - 0.05, w: 1.6, h: 0.75, fontSize: 40, bold: true, color: c, fontFace: HEAD, align: "center" });
    T(s, "exact makespan (finishes within 8 events)", { x: 10.95, y: y + 0.7, w: 1.8, h: 0.5, fontSize: 10, color: COL.muted, align: "center" });
  });
  panel(s, 4.5, 5.25, 8.23, 1.6, COL.panel2);
  T(s, [{ text: "Decision: start T6 ", options: { bold: true, color: COL.qual } }, { text: "(29 < 31), overriding the blended-score ranking. Two later contested decisions are also resolved by rollout: t = 13 T7 (29) vs T4 (31), and t = 20 T4 (29) vs T10 (31). Final schedule: 29, the optimum proven by the exact solver; Critical Path First and the Original scheduler both give 31.", options: {} }],
    { x: 4.7, y: 5.32, w: 7.85, h: 1.46, fontSize: 13, color: COL.ink2, valign: "middle" });
  s.addNotes("Reproduced by running the research scheduler with a trace hook added to a scratch copy (no repository code changed). Faded bars = already fixed before t = 8. Both simulated futures end within 6 completion events, so the rollout returns exact makespans rather than the lower bound.");
}
{
  const s = content("Depth, candidates and cost", "Deeper search helps up to about 8 events, then quality flattens while cost keeps growing (Phase 1, MAIN set, 3,600 instances).");
  s.addChart(pres.charts.LINE, [{ name: "GM makespan vs Original", labels: ["0", "1", "2", "4", "8", "16", "full"], values: [0.9984, 0.9932, 0.9918, 0.9905, 0.9895, 0.9892, 0.9893] }],
    chartOpts({ x: 0.6, y: 1.55, w: 6.2, h: 4.2, chartColors: [COL.qual], lineSize: 2, lineDataSymbol: "circle", lineDataSymbolSize: 8, showValue: true, dataLabelPosition: "t", dataLabelFormatCode: "0.0000",
      valAxisMinVal: 0.988, valAxisMaxVal: 1.0, valAxisLabelFormatCode: "0.000", showTitle: true, title: "Rollout depth (completion events), L = 5, contested only", showLegend: false, catAxisTitle: "depth", showCatAxisTitle: true, catAxisTitleColor: COL.muted, catAxisTitleFontSize: 11 }));
  T(s, "Lower is better. Geometric-mean (GM) ratio of makespan to the Original scheduler on the same instances.", { x: 0.6, y: 5.8, w: 6.2, h: 0.5, fontSize: 11, color: COL.muted });
  // cost table
  const hdr = ["single thread, ms per run", "S", "M", "L", "XL"];
  const rows = [["Original", "0.11", "1.1", "1.4", "3.5"], ["depth 8, L = 5", "0.08", "1.5", "20", "159"], ["depth 16, L = 5", "0.09", "2.0", "28", "229"], ["full depth, L = 5", "0.10", "4.1", "88", "801"]];
  s.addTable([hdr.map((h) => ({ text: h, options: { bold: true, color: COL.muted, fontSize: 11 } })), ...rows.map((r, i) => r.map((c, j) => ({ text: c, options: { fontSize: 12, bold: i === 1, color: i === 1 ? COL.qual : COL.ink, align: j ? "right" : "left" } })))],
    { x: 7.1, y: 1.6, w: 5.63, colW: [2.23, 0.85, 0.85, 0.85, 0.85], fontFace: BODY, border: { type: "solid", color: COL.rule, pt: 0.75 }, rowH: 0.36, margin: 0.06 });
  T(s, "S 10–30, M 31–150, L 151–600, XL ≈ 450–1,500 tasks (Phase 1 §7–8).", { x: 7.1, y: 3.5, w: 5.6, h: 0.3, fontSize: 10, color: COL.faint });
  panel(s, 7.1, 3.95, 2.72, 2.45, COL.panel);
  T(s, "Validated", { x: 7.3, y: 4.05, w: 2.4, h: 0.3, fontSize: 14, bold: true, color: COL.good });
  T(s, "Depth 8, L = 5, contested-only. On XL graphs depth 8 (0.9945) beat full depth (0.9953). L = 5 helps only at depth ≥ 8. Activation is decisive: under the original gates the rollout reached only 0.9956.", { x: 7.3, y: 4.4, w: 2.4, h: 2.4, fontSize: 11.5, color: COL.ink2 });
  panel(s, 10.01, 3.95, 2.72, 2.45, COL.panel);
  T(s, "Not validated", { x: 10.2, y: 4.05, w: 2.4, h: 0.3, fontSize: 14, bold: true, color: COL.bad });
  T(s, "Adaptive depth or a graph-level gate inside the rollout. Phase 3 tested such gates only in a study simulator, or as preset switching by graph size; neither is implemented in production.", { x: 10.2, y: 4.4, w: 2.4, h: 2.4, fontSize: 11.5, color: COL.ink2 });
}

// =====================================================================
// PART IV
// =====================================================================
sec("IV · Optimizing the weights");
divider("IV", "Optimizing the weights", "How should weight be distributed among the components to minimise makespan across diverse problems?");
{
  const s = content("The research question", "Nine continuous knobs and three discrete ones; no theory says how to set them.");
  const groups = [["Static weights", ["w_cp", "w_bp", "w_reach"], COL.cp, "simplex: ≥ 0, sum 1"], ["Dynamic weights", ["openToBlock", "openChildren", "childImp", "dur→ready", "children→queue"], COL.fast, "simplex: ≥ 0, sum 1"], ["Blend", ["λ"], COL.qual, "[0, 1]"], ["Structure", ["K pool", "L candidates", "depth"], COL.ink2, "discrete grid"]];
  let y = 1.65;
  groups.forEach(([h, ks, c, d]) => {
    T(s, h, { x: 0.6, y, w: 2.1, h: 0.45, fontSize: 14, bold: true, valign: "middle" });
    ks.forEach((k, j) => {
      s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: 2.75 + j * 1.38, y: y + 0.04, w: 1.3, h: 0.38, rectRadius: 0.19, fill: { color: c }, line: { color: c, width: 0 } });
      T(s, k, { x: 2.75 + j * 1.38, y: y + 0.04, w: 1.3, h: 0.38, fontSize: 11, bold: true, color: "FFFFFF", align: "center", valign: "middle" });
    });
    T(s, d, { x: 9.8, y, w: 2.9, h: 0.45, fontSize: 12, color: COL.muted, valign: "middle", italic: true });
    y += 0.62;
  });
  T(s, "Why manual tuning is hard", { x: 0.6, y: 4.3, w: 6, h: 0.35, fontSize: 17, bold: true, fontFace: HEAD });
  bullets(s, [
    "Effects interact: a dynamic weight matters only if λ lets it override the static order.",
    "The objective is piecewise constant: small weight changes often change no decision at all.",
    "Each component helps on some graphs and hurts on others (Part II), so judging by examples misleads.",
  ], { x: 0.6, y: 4.75, w: 6.2, h: 2.1, fontSize: 14, gap: 8 });
  panel(s, 7.1, 4.3, 5.63, 2.0, COL.panel2);
  T(s, "Our approach: large-scale empirical optimisation", { x: 7.3, y: 4.42, w: 5.3, h: 0.3, fontSize: 14, bold: true, color: COL.qual });
  T(s, "Generate thousands of diverse DAGs. Search the weight space on training graphs, select on separate validation graphs, and report once on untouched test graphs, with paired statistics against the Original scheduler.", { x: 7.3, y: 4.8, w: 5.3, h: 1.9, fontSize: 13, color: COL.ink2 });
}
{
  const s = content("Experimental methodology", "Phase 2 datasets. All seeded, deterministic, disjoint across roles and from the Phase 1 seeds.");
  panel(s, 0.6, 1.6, 3.4, 5.25, COL.panel2);
  T(s, "INSTANCE GENERATOR", { x: 0.8, y: 1.72, w: 3.0, h: 0.25, fontSize: 10, bold: true, color: COL.muted, charSpacing: 1 });
  bullets(s, [
    [B("8 DAG families: "), { text: "Erdős–Rényi, layered, fork-join, tree, chains, stencil, FFT, Gaussian elimination" }],
    [B("5 duration distributions: "), { text: "uniform 1–10, uniform 1–100, log-normal, unit, bimodal" }],
    [B("Sizes: "), { text: "S 10–30, M 31–150, L 151–600, XL 601–1,500 tasks" }],
    [B("Workers: "), { text: "2–64, set from average parallelism × pressure ∈ {0.5, 1, 1.5, 3}" }],
  ], { x: 0.8, y: 2.05, w: 3.05, h: 4.7, fontSize: 12.5, gap: 8 });
  const sets = [
    ["Train", "7,200", "seed 10001", "6 families × S/M/L, no BIMODAL; minibatches of 1,500", COL.fast],
    ["Validation", "2,480", "seed 20001", "selection part 1,466 (racing) · monitoring part 1,014 (never used to choose)", COL.b0],
    ["Test", "4,500", "seed 30001", "in-distribution; run once after freezing (guarded by test.done)", COL.qual],
    ["Out-of-distribution test", "2,900", "seed 30002", "held-out FFT + Gaussian 1,500 · BIMODAL 1,200 · XL 200", COL.qual],
    ["Exact", "800", "seed 40001", "2–10 tasks, solved to proven optimality", COL.cp],
  ];
  sets.forEach(([n, c, sd, d, col], i) => {
    const y = 1.6 + i * 1.05;
    s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: 4.35, y, w: 8.38, h: 0.9, rectRadius: 0.08, fill: { color: "FFFFFF" }, line: { color: COL.rule, width: 1 } });
    s.addShape(pres.shapes.OVAL, { x: 4.55, y: y + 0.31, w: 0.28, h: 0.28, fill: { color: col }, line: { color: col, width: 0 } });
    T(s, n, { x: 5.0, y: y + 0.08, w: 2.6, h: 0.4, fontSize: 15, bold: true, valign: "middle" });
    T(s, sd, { x: 5.0, y: y + 0.48, w: 2.6, h: 0.35, fontSize: 11, color: COL.muted });
    T(s, c, { x: 7.5, y: y + 0.05, w: 1.4, h: 0.8, fontSize: 24, bold: true, fontFace: HEAD, color: col, align: "right", valign: "middle" });
    T(s, d, { x: 9.15, y: y + 0.05, w: 3.45, h: 0.8, fontSize: 12, color: COL.ink2, valign: "middle" });
  });
}
{
  const s = content("Weight-optimisation process", "Search on train, race on validation, test once. CMA-ES with random search at equal budget as a control.");
  const st = [["Stage 0", "Sobol sensitivity", "1,024 Sobol points + Saltelli N = 256 (3,840 evaluations) on 1,000 training graphs"], ["Track A", "fast, no lookahead", "CMA-ES per K ∈ {1, 2, 3, 5}: population 20, σ₀ = 0.3, one IPOP restart at 40; plus random search"], ["Pre-screen", "full training set", "top 15 per run + CMA mean + default weights, rescored on all 7,200"], ["Race", "validation", "30 finalists; drop if worse than leader (paired Wilcoxon p < 0.01); 25 / 50 / 100% stages"], ["Track B", "quality, rollout", "grid L ∈ {3, 5} × depth ∈ {4, 8}; CMA-ES warm-started from A*; race again"], ["Freeze → test", "once", "frozen-configs.txt written before the single test run"]];
  st.forEach(([a, b, c], i) => {
    const x = 0.6 + (i % 3) * 4.1, y = 1.6 + Math.floor(i / 3) * 1.6;
    panel(s, x, y, 3.9, 1.42);
    T(s, a, { x: x + 0.2, y: y + 0.1, w: 1.6, h: 0.35, fontSize: 15, bold: true, color: i === 4 ? COL.qual : i === 1 ? COL.fast : COL.ink });
    T(s, b, { x: x + 1.75, y: y + 0.1, w: 2.0, h: 0.35, fontSize: 12, color: COL.muted, italic: true, valign: "middle" });
    T(s, c, { x: x + 0.2, y: y + 0.5, w: 3.5, h: 0.88, fontSize: 12, color: COL.ink2 });
  });
  panel(s, 0.6, 4.9, 6.0, 1.95, COL.panel2);
  T(s, "Objective (minimise), paired against the Original on each instance", { x: 0.8, y: 5.0, w: 5.6, h: 0.3, fontSize: 12, bold: true, color: COL.muted });
  T(s, "J = mean log(Cᵢ / Cᵢᴼᴿᴵᴳ) + 0.5 · CVaR₀.₉₅ log(Cᵢ / Cᵢᴼᴿᴵᴳ)", { x: 0.8, y: 5.35, w: 5.6, h: 0.6, fontFace: HEAD, italic: true, fontSize: 17, valign: "middle" });
  T(s, "Average gain plus a penalty on the worst 5% of instances. 9 unconstrained logits map to two softmax simplices and a logistic λ.", { x: 0.8, y: 5.95, w: 5.6, h: 0.8, fontSize: 12, color: COL.ink2 });
  panel(s, 6.85, 4.9, 5.88, 1.95);
  T(s, "Role of Claude Code", { x: 7.05, y: 5.0, w: 5.5, h: 0.3, fontSize: 13, bold: true, color: COL.qual });
  T(s, "Claude Code (an AI coding assistant) wrote and ran the harness: generator, CMA-ES, Sobol, racing, statistics, reports. It was an engineering tool, not a source of truth. Correctness rests on 201 passing tests, golden traces of the original scheduler, the exact solver and paired statistics. Compute: 29,440 evaluations, ≈ 50 M scheduler runs, ≈ 4.1 h on 12 local threads.", { x: 7.05, y: 5.32, w: 5.5, h: 1.5, fontSize: 11.5, color: COL.ink2 });
}

// =====================================================================
// PART V
// =====================================================================
sec("V · Experimental results");
divider("V", "Experimental results", "Independent test sets, exact optima, robustness and cost.");
const STRAT = [["Original", COL.orig], ["Critical Path First", COL.cp], ["FAST_OPTIMIZED", COL.fast], ["Phase 1 Rollout", COL.b0], ["QUALITY_OPTIMIZED", COL.qual]];
{
  const s = content("Comparing scheduling strategies", "Phase 2 in-distribution test set: 4,500 instances (seed 30001), run once. Paired against the Original on the same instances.");
  s.addChart(pres.charts.BAR, [{ name: "GM makespan reduction vs Original (%)", labels: ["Critical Path First", "FAST_OPTIMIZED", "Phase 1 Rollout", "QUALITY_OPTIMIZED"], values: [0.92, 0.93, 1.01, 1.15] }],
    chartOpts({ x: 0.6, y: 1.55, w: 5.4, h: 4.3, barDir: "bar", chartColors: [COL.cp, COL.fast, COL.b0, COL.qual], showValue: true, dataLabelPosition: "outEnd", dataLabelFormatCode: "0.00\"%\"",
      valAxisMinVal: 0, valAxisMaxVal: 1.4, valAxisHidden: true, valGridLine: { style: "none" }, catAxisOrientation: "maxMin", showTitle: true, title: "Makespan reduction vs Original, GM (%)", showLegend: false, barGapWidthPct: 45 }));
  T(s, "Reduction = 1 − GM(Cmax / Cmax_Original). Higher is better.", { x: 0.6, y: 5.9, w: 5.4, h: 0.3, fontSize: 11, color: COL.muted });
  const hdr = ["", "GM vs Original [95% CI]", "mean impr.", "W / T / L", "worst ratio", "GM / LB"];
  const rows = [
    ["Original (DEFAULT)", "1.0000", "0", "—", "1.000", "1.0443"],
    ["Critical Path First (batched)", "0.9908 [0.9901, 0.9915]", "+0.89%", "1569 / 2604 / 327", "1.084", "1.0347"],
    ["FAST_OPTIMIZED (A*)", "0.9907 [0.9901, 0.9914]", "+0.90%", "1564 / 2620 / 316", "1.084", "1.0346"],
    ["Phase 1 Rollout (B0)", "0.9899 [0.9893, 0.9906]", "+0.98%", "1915 / 2542 / 43", "1.028", "1.0337"],
    ["QUALITY_OPTIMIZED (B*)", "0.9885 [0.9878, 0.9892]", "+1.12%", "1945 / 2495 / 60", "1.028", "1.0323"],
  ];
  s.addTable([hdr.map((h) => ({ text: h, options: { bold: true, color: COL.muted, fontSize: 10.5 } })), ...rows.map((r, i) => r.map((c, j) => ({ text: c, options: { fontSize: 11, bold: i === 4, color: j === 0 ? STRAT[i][1] === COL.orig ? COL.ink : STRAT[i][1] : COL.ink, align: j ? "center" : "left" } })))],
    { x: 6.25, y: 1.6, w: 6.48, colW: [1.75, 1.6, 0.75, 1.13, 0.6, 0.65], fontFace: BODY, border: { type: "solid", color: COL.rule, pt: 0.75 }, rowH: 0.42, margin: 0.04 });
  panel(s, 6.25, 4.35, 6.48, 2.0, COL.panel2);
  T(s, "The configurations differ by more than weights", { x: 6.45, y: 4.45, w: 6.1, h: 0.3, fontSize: 13, bold: true });
  bullets(s, [
    "Original: legacy idle-window lookahead, K = 3, sequential completions.",
    "FAST: no lookahead, K = 2, batched, reach/max, id tie-break, learned weights.",
    "Phase 1 Rollout: rollout (depth 8, L = 5, K = 5, contested), batched, original weights.",
    "QUALITY: the same rollout structure with learned weights.",
  ], { x: 6.45, y: 4.8, w: 6.1, h: 2.0, fontSize: 12, gap: 4 });
  s.addNotes("Medians are 0 for every variant because most instances tie. All headline comparisons p < 1e-4 (two-sided paired Wilcoxon). CP-first vs FAST post hoc: GM 0.9999 (467 wins / 480 losses).");
}
{
  const s = content("What weights did optimisation discover?", "Every search run converged to critical path only and λ → 1. Dynamic scoring is left as a tie-breaker.");
  const labS = ["critical path", "blocking power", "reach"];
  s.addChart(pres.charts.BAR, [
    { name: "Original", labels: labS, values: [0.5, 0.25, 0.25] }, { name: "FAST_OPTIMIZED", labels: labS, values: [0.94195, 0.01438, 0.04367] }, { name: "QUALITY_OPTIMIZED", labels: labS, values: [1.0, 2.6e-9, 3.2e-10] }],
    chartOpts({ x: 0.6, y: 1.55, w: 5.2, h: 3.4, barDir: "col", barGrouping: "clustered", chartColors: [COL.orig, COL.fast, COL.qual], showValue: true, dataLabelPosition: "outEnd", dataLabelFormatCode: "0.00", dataLabelFontSize: 10,
      valAxisMinVal: 0, valAxisMaxVal: 1.1, valAxisHidden: true, valGridLine: { style: "none" }, showTitle: true, title: "Static weights", showLegend: true, legendPos: "b", barGapWidthPct: 40 }));
  const labD = ["openToBlock", "openChildren", "childImp", "dur→ready", "ch→queue"];
  s.addChart(pres.charts.BAR, [
    { name: "Original", labels: labD, values: [0.2, 0.1, 0.15, 0.3, 0.25] }, { name: "FAST_OPTIMIZED", labels: labD, values: [0.01812, 0.14182, 0.32104, 0.09321, 0.42582] }, { name: "QUALITY_OPTIMIZED", labels: labD, values: [1.2e-10, 2.4e-9, 0.12654, 0.87346, 1.6e-9] }],
    chartOpts({ x: 6.0, y: 1.55, w: 6.73, h: 3.4, barDir: "col", barGrouping: "clustered", chartColors: [COL.orig, COL.fast, COL.qual], showValue: true, dataLabelPosition: "outEnd", dataLabelFormatCode: "0.00", dataLabelFontSize: 10,
      valAxisMinVal: 0, valAxisMaxVal: 1.0, valAxisHidden: true, valGridLine: { style: "none" }, showTitle: true, title: "Dynamic weights (matter only through 1 − λ)", showLegend: true, legendPos: "b", barGapWidthPct: 40 }));
  const hdr = ["", "static (cp, bp, reach)", "λ (static share)", "dynamic share 1 − λ"];
  const rows = [["Original", "0.5, 0.25, 0.25", "0.5", "0.5"], ["FAST_OPTIMIZED", "0.9419505, 0.0143771, 0.0436724", "0.9893182", "0.0106818"], ["QUALITY_OPTIMIZED", "0.9999999971, 2.63e-9, 3.19e-10", "0.9989271", "0.0010729"]];
  s.addTable([hdr.map((h) => ({ text: h, options: { bold: true, color: COL.muted, fontSize: 10.5 } })), ...rows.map((r, i) => r.map((c, j) => ({ text: c, options: { fontSize: 11, color: j === 0 ? [COL.ink, COL.fast, COL.qual][i] : COL.ink, bold: j === 0 } })))],
    { x: 0.6, y: 5.1, w: 7.4, colW: [1.8, 2.9, 1.3, 1.4], fontFace: BODY, border: { type: "solid", color: COL.rule, pt: 0.75 }, rowH: 0.34, margin: 0.04 });
  panel(s, 8.25, 5.1, 4.48, 1.75, COL.panel2);
  T(s, "Near zero is not the same as irrelevant", { x: 8.45, y: 5.18, w: 4.1, h: 0.3, fontSize: 13, bold: true, color: COL.qual });
  T(s, "With λ ≈ 0.999 the dynamic term still reorders tasks whose static scores nearly tie. But 30 race finalists with very different dynamic vectors all scored 0.9886–0.9887 on validation, so individual dynamic weights are not identifiable.", { x: 8.45, y: 5.5, w: 4.1, h: 1.3, fontSize: 11.5, color: COL.ink2 });
  s.addNotes("Full-precision values from SchedulerConfig.java presets (research archive). QUALITY dynamic weights: 1.1784e-10, 2.4034e-9, 0.12653824, 0.87346176, 1.56e-9.");
}
{
  const s = content("How close are we to optimal?", "Exact optima on small graphs; lower bounds on large ones. A lower bound is not an optimum.");
  s.addChart(pres.charts.BAR, [{ name: "% optimal", labels: ["Original", "Critical Path First", "FAST_OPTIMIZED", "Phase 1 Rollout", "QUALITY_OPTIMIZED"], values: [88.0, 93.6, 93.5, 97.9, 97.8] }],
    chartOpts({ x: 0.6, y: 1.55, w: 4.1, h: 3.9, barDir: "col", chartColors: [COL.orig, COL.cp, COL.fast, COL.b0, COL.qual], showValue: true, dataLabelPosition: "outEnd", dataLabelFormatCode: "0.0\"%\"", valAxisMinVal: 0, valAxisMaxVal: 110, valAxisHidden: true, valGridLine: { style: "none" },
      showTitle: true, title: "Phase 2 exact set · 800 graphs, 2–10 tasks", showLegend: false, catAxisLabelFontSize: 9, barGapWidthPct: 35 }));
  s.addChart(pres.charts.BAR, [{ name: "% optimal", labels: ["Original", "Critical Path First", "FAST_OPTIMIZED", "QUALITY_OPTIMIZED", "best list schedule"], values: [64.2, 78.6, 78.1, 89.7, 98.7] }],
    chartOpts({ x: 4.85, y: 1.55, w: 4.1, h: 3.9, barDir: "col", chartColors: [COL.orig, COL.cp, COL.fast, COL.qual, COL.ink2], showValue: true, dataLabelPosition: "outEnd", dataLabelFormatCode: "0.0\"%\"", valAxisMinVal: 0, valAxisMaxVal: 110, valAxisHidden: true, valGridLine: { style: "none" },
      showTitle: true, title: "Phase 3 exact set · 891 solved, 10–18 tasks", showLegend: false, catAxisLabelFontSize: 9, barGapWidthPct: 35 }));
  const hdr = ["GM Cmax / OPT", "Ph 2 (800)", "Ph 3 (891)"];
  const rows = [["Original", "1.0091", "1.0173"], ["Critical Path First", "1.0029", "1.0053"], ["FAST_OPTIMIZED", "1.0030", "1.0054"], ["Phase 1 Rollout", "1.0011", "—"], ["QUALITY_OPTIMIZED", "1.0010", "1.0015"], ["best list schedule", "—", "1.0003"]];
  s.addTable([hdr.map((h) => ({ text: h, options: { bold: true, color: COL.muted, fontSize: 10.5 } })), ...rows.map((r) => r.map((c, j) => ({ text: c, options: { fontSize: 11, align: j ? "center" : "left" } })))],
    { x: 9.15, y: 1.6, w: 3.58, colW: [1.7, 0.94, 0.94], fontFace: BODY, border: { type: "solid", color: COL.rule, pt: 0.75 }, rowH: 0.33, margin: 0.04 });
  T(s, "Exact optima allow inserted idle time; “best list schedule” is the best any priority rule can reach.", { x: 9.15, y: 4.05, w: 3.58, h: 0.75, fontSize: 10.5, color: COL.muted });
  panel(s, 0.6, 5.6, 12.13, 1.25, COL.panel2);
  T(s, [{ text: "Large graphs, lower bound only.  ", options: { bold: true } }, { text: "On the 4,500-instance test set GM Cmax / LB is 1.0443 (Original), 1.0347 (CP-first), 1.0346 (FAST), 1.0337 (Phase 1 Rollout), 1.0323 (QUALITY). On exactly solved graphs the optimum itself averages 1.0280 × LB (Phase 3), so most of the remaining LB gap is slack in the bound, not proven scheduler loss. The true gap on large graphs is unknown." }],
    { x: 0.8, y: 5.65, w: 11.75, h: 1.15, fontSize: 12.5, color: COL.ink2, valign: "middle" });
}
{
  const s = content("Robustness and generalisation", "The learned configurations were never selected on these sets. QUALITY_OPTIMIZED stays best on every subset.");
  const labels = ["In-distribution (4,500)", "Held-out families FFT+Gaussian (1,500)", "Held-out BIMODAL durations (1,200)", "Larger graphs XL (200)"];
  const red = (a) => a.map((g) => +(100 * (1 - g)).toFixed(2));
  s.addChart(pres.charts.BAR, [
    { name: "Critical Path First", labels, values: red([0.9908, 0.9892, 0.9853, 0.9874]) },
    { name: "FAST_OPTIMIZED", labels, values: red([0.9907, 0.9892, 0.9852, 0.9875]) },
    { name: "Phase 1 Rollout", labels, values: red([0.9899, 0.9875, 0.9845, 0.9934]) },
    { name: "QUALITY_OPTIMIZED", labels, values: red([0.9885, 0.9859, 0.9828, 0.9869]) }],
    chartOpts({ x: 0.6, y: 1.55, w: 7.6, h: 4.2, barDir: "col", barGrouping: "clustered", chartColors: [COL.cp, COL.fast, COL.b0, COL.qual], showValue: true, dataLabelPosition: "outEnd", dataLabelFormatCode: "0.00", dataLabelFontSize: 9,
      valAxisMinVal: 0, valAxisMaxVal: 2.0, valAxisLabelFormatCode: "0.0", showTitle: true, title: "GM makespan reduction vs Original (%)", showLegend: true, legendPos: "b", catAxisLabelFontSize: 10, barGapWidthPct: 50 }));
  s.addChart(pres.charts.LINE, [{ name: "QUALITY_OPTIMIZED", labels: ["2–3", "4–8", "9–20", "21–64"], values: [1.42, 1.06, 0.71, 0.30] }],
    chartOpts({ x: 8.45, y: 1.55, w: 4.28, h: 2.75, chartColors: [COL.qual], lineSize: 2, lineDataSymbol: "circle", lineDataSymbolSize: 8, showValue: true, dataLabelPosition: "t", dataLabelFormatCode: "0.00",
      valAxisMinVal: 0, valAxisMaxVal: 1.8, valAxisHidden: true, valGridLine: { style: "none" }, showTitle: true, title: "QUALITY reduction (%) by worker count", showLegend: false }));
  panel(s, 8.45, 4.45, 4.28, 2.4, COL.panel2);
  T(s, "Uncertainty and worst cases", { x: 8.65, y: 4.53, w: 3.9, h: 0.3, fontSize: 13, bold: true });
  bullets(s, [
    "QUALITY vs Phase 1 Rollout: 0.9986 [0.9984, 0.9988]; OOD 0.9985 / 0.9982 / 0.9934.",
    "Worst single instance vs Original: QUALITY 1.028 (OOD 1.043); FAST 1.084 (OOD 1.120).",
    "Losses vs Original: QUALITY 1.33%, FAST 7.0%.",
  ], { x: 8.65, y: 4.88, w: 3.9, h: 1.95, fontSize: 11, gap: 4 });
  T(s, "Gain shrinks with size vs Phase 1 Rollout (S 0.9999, M 0.9987, L 0.9972 for QUALITY). On XL graphs the original weights in Phase 1 Rollout are weakest.", { x: 0.6, y: 5.85, w: 7.6, h: 0.75, fontSize: 11.5, color: COL.muted });
}
{
  const s = content("Quality versus computational cost", "One timing harness (Phase 2: single thread, 160 instances, median of 3). L-size graphs, 151–600 tasks.");
  const xs = [2.75, 1.31, 1.30, 15.78, 18.20], ys = [0, 0.92, 0.93, 1.01, 1.15];
  const data = [{ name: "X", values: xs }];
  STRAT.forEach(([n], i) => data.push({ name: n, values: xs.map((_, j) => (j === i ? ys[i] : null)) }));
  s.addChart(pres.charts.SCATTER, data, chartOpts({ x: 0.6, y: 1.55, w: 7.3, h: 4.9, chartColors: STRAT.map((q) => q[1]), lineSize: 0, lineDataSymbol: "circle", lineDataSymbolSize: 14,
    valAxisMinVal: -0.2, valAxisMaxVal: 1.4, valAxisMajorUnit: 0.2, valAxisLabelFormatCode: "0.0", catAxisMinVal: 0, catAxisMaxVal: 20, showTitle: true, title: "Makespan reduction vs Original (%) against ms per run (L graphs)",
    showLegend: true, legendPos: "b", showValAxisTitle: true, valAxisTitle: "GM reduction vs Original (%)", valAxisTitleColor: COL.muted, valAxisTitleFontSize: 11, showCatAxisTitle: true, catAxisTitle: "single-thread ms per run, L graphs", catAxisTitleColor: COL.muted, catAxisTitleFontSize: 11 }));
  T(s, "Critical Path First (1.31 ms, 0.92%) and FAST (1.30 ms, 0.93%) overlap", { x: 1.6, y: 2.55, w: 3.6, h: 0.45, fontSize: 10.5, color: COL.muted, italic: true });
  const hdr = ["ms per run", "S", "M", "L", "XL"];
  const rows = [["Original", "0.13", "0.53", "2.75", "4.90"], ["Critical Path First", "0.04", "0.18", "1.31", "8.23"], ["FAST_OPTIMIZED", "0.05", "0.26", "1.30", "3.01"], ["Phase 1 Rollout", "0.63", "5.13", "15.78", "149.5"], ["QUALITY_OPTIMIZED", "0.11", "2.11", "18.20", "141.9"]];
  s.addTable([hdr.map((h) => ({ text: h, options: { bold: true, color: COL.muted, fontSize: 10.5 } })), ...rows.map((r, i) => r.map((c, j) => ({ text: c, options: { fontSize: 11, align: j ? "right" : "left", color: j === 0 && i ? STRAT[i][1] : COL.ink, bold: j === 0 } })))],
    { x: 8.15, y: 1.6, w: 4.58, colW: [1.78, 0.7, 0.7, 0.7, 0.7], fontFace: BODY, border: { type: "solid", color: COL.rule, pt: 0.75 }, rowH: 0.34, margin: 0.04 });
  panel(s, 8.15, 3.85, 4.58, 3.0, COL.panel2);
  T(s, "Reading the trade-off", { x: 8.35, y: 3.95, w: 4.2, h: 0.3, fontSize: 13, bold: true });
  bullets(s, [
    "FAST and CP-first: ≈ 0.9% gain at half the Original’s time or less.",
    "QUALITY adds ≈ 0.22 percentage points for 14× FAST’s time on L graphs and 47× on XL.",
    "Phase 3, measured in a separate run of the production code: QUALITY for n ≤ 150, FAST above, keeps 82–90% of QUALITY’s gain at FAST’s cost on large graphs.",
  ], { x: 8.35, y: 4.3, w: 4.2, h: 2.5, fontSize: 11.5, gap: 5 });
}
{
  const s = content("Where do the remaining errors live?", "Phase 3: share of instances where Critical Path First is ≥ 1% worse than the best observed schedule (2,700 exploration graphs).");
  const labels = ["Size: L (151–600)", "Size: S (10–30)", "Workers: 21–64", "Workers: 2–3", "Load: CP-bound (Q1)", "Load: balanced (Q3)", "Durations: unit", "Durations: uniform 1–100"];
  const vals_ = [2.6, 16.7, 1.4, 14.3, 0.2, 15.4, 0.3, 17.0];
  s.addChart(pres.charts.BAR, [{ name: "% of instances ≥ 1% worse", labels, values: vals_ }],
    chartOpts({ x: 0.6, y: 1.55, w: 6.8, h: 5.3, barDir: "bar", chartColors: vals_.map((v, i) => (i % 2 ? COL.cp : "C9CFDA")), invertedColors: undefined, showValue: true, dataLabelPosition: "outEnd", dataLabelFormatCode: "0.0\"%\"",
      valAxisMinVal: 0, valAxisMaxVal: 20, valAxisHidden: true, valGridLine: { style: "none" }, catAxisOrientation: "maxMin", showTitle: true, title: "Low-failure end (grey) vs high-failure end (ochre)", showLegend: false, barGapWidthPct: 35 }));
  const facts = [["94%", "of the log gap to the optimum can be closed by changing priorities alone; only 0.03% needs inserted idle time"], ["90%", "of the best single fixes swap exactly the boundary task, the last one CP-first starts"], ["5–7%", "of exact CP ties are improvable, vs 18% of decisions on average: the near-tie hypothesis is refuted"], ["0.64", "best AUC of any cheap decision-level feature for predicting a bad decision"]];
  facts.forEach(([n, t], i) => {
    const y = 1.6 + i * 1.32;
    T(s, n, { x: 7.7, y, w: 1.6, h: 1.0, fontSize: 32, bold: true, fontFace: HEAD, color: COL.qual, valign: "middle" });
    T(s, t, { x: 9.35, y, w: 3.38, h: 1.1, fontSize: 12.5, color: COL.ink2, valign: "middle" });
  });
}

// =====================================================================
// PART VI
// =====================================================================
sec("VI · Conclusions");
divider("VI", "Conclusions", "What we learned, and what the final scheduler delivers.");
{
  const s = content("What did we learn?", "Findings supported by the paired tests in the Phase 1–3 reports.");
  const items = [
    ["Critical Path First is an exceptionally strong baseline", "0.9908 GM vs Original on test; optimal on 93.6% of tiny and 78.6% of 10–18-task graphs."],
    ["Optimisation converges to critical-path dominance", "Every CMA-ES and random-search run reached static ≈ (1, 0, 0) with λ → 1."],
    ["Most extra components add little under one global weighting", "FAST ≈ CP-first (paired GM 0.9999); dynamic weights sit on a flat plateau."],
    ["The improved lookahead adds real quality", "QUALITY 0.9885 vs Original, 0.9977 vs CP-first (Phase 3 final); 97.8% optimal on tiny graphs."],
    ["Remaining errors are concentrated", "Small graphs, 2–3 workers, balanced load, non-unit durations; mechanism: worker starvation."],
    ["Complexity must pay for itself", "QUALITY is 14–47× FAST’s time on L / XL graphs for ≈ 0.2 points; size-gating keeps 82–90% of the gain."],
    ["Sophisticated search did not beat simple search", "CMA-ES ≈ random search at equal budget in every group; FAST came from a random-search run."],
  ];
  items.forEach(([h, t], i) => {
    const col = i % 2, row = Math.floor(i / 2);
    const x = 0.6 + col * 6.17, y = 1.6 + row * 1.32;
    const w = i === 6 ? 12.13 : 5.96;
    panel(s, x, y, w, 1.18);
    T(s, String(i + 1), { x: x + 0.15, y: y + 0.1, w: 0.45, h: 0.5, fontSize: 22, bold: true, fontFace: HEAD, color: COL.qual });
    T(s, h, { x: x + 0.65, y: y + 0.1, w: w - 0.85, h: 0.4, fontSize: 14, bold: true });
    T(s, t, { x: x + 0.65, y: y + 0.52, w: w - 0.85, h: 0.6, fontSize: 12, color: COL.ink2 });
  });
}
{
  const s = pres.addSlide({ masterName: "SECTION", sectionTitle: section });
  T(s, "Final summary", { x: 0.7, y: 0.5, w: 8, h: 0.7, fontFace: HEAD, fontSize: 36, bold: true, color: "FFFFFF" });
  const flow = ["Problem", "NP-hardness", "Heuristic design", "Lookahead", "Large-scale experiments", "Weight optimisation", "Findings"];
  const bw = 1.55;
  flow.forEach((f, i) => {
    const x = 0.7 + i * (bw + 0.12);
    s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x, y: 1.5, w: bw, h: 0.7, rectRadius: 0.08, fill: { color: i === 3 ? COL.qual : COL.ink2 }, line: { color: i === 3 ? COL.qual : "4A5368", width: 1 } });
    T(s, f, { x, y: 1.5, w: bw, h: 0.7, fontSize: 12.5, bold: true, color: "FFFFFF", align: "center", valign: "middle" });
    if (i < flow.length - 1) s.addShape(pres.shapes.LINE, { x: x + bw, y: 1.85, w: 0.12, h: 0, line: { color: "8A93A3", width: 1.25, endArrowType: "triangle" } });
  });
  const stats = [["1.15%", "GM makespan reduction vs the Original scheduler (QUALITY_OPTIMIZED, 4,500 test graphs)"], ["97.8%", "of 800 small graphs scheduled optimally (2–10 tasks)"], ["1.028", "worst single-instance ratio vs Original in-distribution; 1.3% of instances lose"]];
  stats.forEach(([n, t], i) => {
    const x = 0.7 + i * 4.1;
    T(s, n, { x, y: 2.75, w: 3.8, h: 0.9, fontSize: 44, bold: true, fontFace: HEAD, color: i === 0 ? COL.qual : "FFFFFF" });
    T(s, t, { x, y: 3.65, w: 3.6, h: 0.8, fontSize: 13, color: "C9CFDA" });
  });
  T(s, [{ text: "The final scheduler: ", options: { bold: true, color: "FFFFFF" } }, { text: "critical-path priority, with a learned near-zero dynamic tie-breaker, plus a depth-8 rollout lookahead on contested decisions.", options: { color: "C9CFDA" } }],
    { x: 0.7, y: 4.85, w: 11.9, h: 0.7, fontSize: 17 });
  T(s, [{ text: "Principal contribution: ", options: { bold: true, color: "FFFFFF" } }, { text: "a reproducible, statistically controlled evaluation showing that critical path explains almost all of what static and dynamic weighting can achieve. The remaining gains come from simulating the future, concentrated on small, few-worker graphs.", options: { color: "C9CFDA" } }],
    { x: 0.7, y: 5.6, w: 11.9, h: 1.1, fontSize: 17 });
}

// =====================================================================
// APPENDIX
// =====================================================================
sec("Appendix");
divider("A", "Appendix", "Definitions, implementation details, supporting tables and caveats.");
function apx(title, kicker) { return content(title, kicker, "APPENDIX"); }
{
  const s = apx("A1 · Exact component definitions and code locations");
  const hdr = ["component", "definition", "normalisation", "orig. weight", "code"];
  const rows = [
    ["critical path cp(v)", "d(v) + max over children of cp(c)", "÷ overall CP length", "0.50", "TimeConstraints.criticalPaths"],
    ["blocking power bp(v)", "Σ over children c of 1 / |parents(c)|", "÷ max bp", "0.25", "TimeConstraints.blockPower"],
    ["reachable work reach(v)", "Σ d(u) over {v} ∪ descendants(v)", "÷ total work (orig.) or ÷ max reach", "0.25", "TimeConstraints.reachableDuration"],
    ["openToBlock", "open(v) / |children(v)|", "—", "0.20", "DynamicScoreLogic.openToBlockRatio"],
    ["openChildren", "min(1, open(v) / m)", "m = total workers", "0.10", "makeDynamicScore"],
    ["childImportance", "Σ static(c) over open c / (open(v) · static_max)", "—", "0.15", "openChildrenImportance"],
    ["durationToReady", "α d̂ + (1 − α)(1 − d̂), α = min(1, |R| / m)", "d̂ = d / d_max", "0.30", "durationToRPQSize"],
    ["childrenToReadyQueue", "(1 − α) · open(v) / maxChildren", "maxChildren = max out-degree", "0.25", "openChildToRPQSize"],
    ["blend", "λ · static + (1 − λ) · dynamic over top K", "λ = 0.5, K = 3", "—", "TaskEngine.getNextTask"],
  ];
  s.addTable([hdr.map((h) => ({ text: h, options: { bold: true, color: COL.muted, fontSize: 11 } })), ...rows.map((r) => r.map((c, j) => ({ text: c, options: { fontSize: 11.5, fontFace: j === 1 ? HEAD : BODY, italic: j === 1 } })))],
    { x: 0.6, y: 1.25, w: 12.13, colW: [2.1, 3.85, 2.25, 0.95, 2.98], fontFace: BODY, border: { type: "solid", color: COL.rule, pt: 0.75 }, rowH: 0.5, margin: 0.05 });
  T(s, "open(v) counts children whose only remaining unfinished parent is v. |R| is the number of ready tasks at the decision, including the candidates. In the code, maxChildren is held in a variable named maxOpenChild but computed as the maximum number of children of any task.", { x: 0.6, y: 6.3, w: 12.1, h: 0.6, fontSize: 11, color: COL.muted });
}
{
  const s = apx("A2 · Final lookahead: implementation and status");
  T(s, "QUALITY_OPTIMIZED (research/archive/…/SchedulerConfig.java)", { x: 0.6, y: 1.25, w: 6, h: 0.3, fontSize: 13, bold: true });
  T(s, "lookaheadMode = ROLLOUT\nrolloutDepth = 8   lookaheadCandidates = 5\ncandidatePoolSize = 5   rolloutIncludesStaticBest = true\ncontestedDecisionsOnly = true   legacyLoadGate = false\ngapCondition = ANY_GAP   completionMode = BATCHED\nreachNormalizedByMaximum = true   deterministicTieBreak = true\nstaticWeights = (0.99999999705, 2.63e-9, 3.19e-10)\ndynamicWeights = (1.18e-10, 2.40e-9, 0.12653824, 0.87346176, 1.56e-9)\nstaticShare λ = 0.99892706",
    { x: 0.6, y: 1.6, w: 6.2, h: 3.3, fontFace: "Courier New", fontSize: 11.5, color: COL.ink2 });
  T(s, "Rollout per candidate (DynamicScoreLogic.rolloutMakespan)", { x: 7.1, y: 1.25, w: 5.6, h: 0.3, fontSize: 13, bold: true });
  T(s, "start candidate at t; other ready tasks wait\nloop:\n  give free workers ready tasks by static rank\n  if nothing runs → return t        (exact)\n  if events ≥ depth → return LB(state)\n  t ← next completion time\n  complete all tasks ending at t, release children\n  events ← events + 1",
    { x: 7.1, y: 1.6, w: 5.6, h: 3.0, fontFace: "Courier New", fontSize: 11.5, color: COL.ink2 });
  panel(s, 0.6, 4.45, 12.13, 1.75, COL.panel2);
  T(s, [{ text: "Status and honesty notes.  ", options: { bold: true } }, { text: "All lookahead results in this deck come from the research code in research/archive/phase1-4-research-source (presets PHASE1_ROLLOUT and QUALITY_OPTIMIZED). The tracked src/ tree still holds the original scheduler. An uncommitted port of the rollout into src/main/java/backend/TaskEngine.java was in progress when this deck was built; it chooses candidates slightly differently (3 blended + next by static score) and has not been benchmarked. The original idle-window lookahead was measured to be harmful (Phase 1) and none of its results are attributed to the rollout." }],
    { x: 0.8, y: 4.5, w: 11.75, h: 1.65, fontSize: 12.5, color: COL.ink2, valign: "middle" });
}
{
  const s = apx("A3 · Phase 1: the original lookahead and the per-change results", "MAIN set, 3,600 instances, GM vs Original (lower is better). Selected rows from PHASE1_REPORT.md §6.2.");
  const hdr = ["variant", "GM vs Original [95% CI]", "W / T / L", "worst"];
  const rows = [["no lookahead (mode NONE)", "0.9971 [0.9966, 0.9975]", "556 / 3034 / 10", "1.036"], ["static only (K = 1)", "0.9939 [0.9933, 0.9946]", "1125 / 2117 / 358", "1.081"], ["legacy lookahead, gap ignored", "1.0064 [1.0057, 1.0071]", "63 / 2539 / 998", "1.368"], ["legacy lookahead, contested only", "1.0119 [1.0108, 1.0130]", "73 / 2355 / 1172", "1.523"], ["legacy + idle-simulation fix (Change 8)", "1.0006 [1.0004, 1.0007]", "5 / 3492 / 103", "1.138"], ["rollout d = 8, L = 3, contested", "0.9900 [0.9893, 0.9907]", "1522 / 2022 / 56", "1.041"], ["rollout d = 16, L = 5, contested", "0.9892 [0.9885, 0.9899]", "1606 / 1950 / 44", "1.041"], ["rollout full depth, original gates", "0.9956", "928 / 2535 / 137", "1.085"], ["CP-first (sequential)", "0.9895", "1358 / 2005 / 237", "1.250"], ["FIFO / LPT / SPT", "1.062 / 1.065 / 1.089", "—", "1.50–1.67"]];
  s.addTable([hdr.map((h) => ({ text: h, options: { bold: true, color: COL.muted, fontSize: 11 } })), ...rows.map((r) => r.map((c, j) => ({ text: c, options: { fontSize: 11.5, align: j ? "center" : "left" } })))],
    { x: 0.6, y: 1.55, w: 8.4, colW: [3.3, 2.4, 1.8, 0.9], fontFace: BODY, border: { type: "solid", color: COL.rule, pt: 0.75 }, rowH: 0.42, margin: 0.05 });
  panel(s, 9.25, 1.55, 3.48, 5.3, COL.panel2);
  T(s, "Takeaways", { x: 9.45, y: 1.65, w: 3.1, h: 0.3, fontSize: 13, bold: true });
  bullets(s, ["The original lookahead never helped: removing it improves the Original by 0.29–0.33%.", "Activating it more often made results worse.", "The rollout with contested activation is what helps.", "Phase 1 recommended configuration on the independent CONFIRM set (2,400): 0.9887 [0.9877, 0.9897], 1,055 / 1,312 / 33."], { x: 9.45, y: 2.05, w: 3.1, h: 4.7, fontSize: 12, gap: 8 });
}
{
  const s = apx("A4 · Sensitivity analysis and search-method control", "Stage 0 (Track A space, K = 3, 1,000 training graphs): Sobol total effects. CMA-ES vs random search at equal budget.");
  s.addChart(pres.charts.BAR, [{ name: "total effect S_T", labels: ["s.blockingPower", "s.criticalPath", "s.reach", "λ", "d.durationToReady", "d.openToBlock", "d.childImportance", "d.openChildren", "d.childrenToReadyQ"], values: [0.496, 0.329, 0.222, 0.137, 0.091, 0.053, 0.049, 0.017, 0.008] }],
    chartOpts({ x: 0.6, y: 1.55, w: 6.2, h: 5.3, barDir: "bar", chartColors: [COL.ink2], showValue: true, dataLabelPosition: "outEnd", dataLabelFormatCode: "0.000", valAxisMinVal: 0, valAxisMaxVal: 0.6, valAxisHidden: true, valGridLine: { style: "none" }, catAxisOrientation: "maxMin", showTitle: true, title: "Sobol total effect S_T (95% CIs in PHASE2_REPORT §14)", showLegend: false }));
  const hdr = ["group", "CMA-ES: train J; val GM", "random: train J; val GM"];
  const rows = [["A-K1", "−0.00480; 0.9908", "−0.00481; 0.9908"], ["A-K2", "−0.00461; 0.9909", "−0.00479; 0.9909"], ["A-K3", "−0.00479; 0.9909", "−0.00505; 0.9912"], ["A-K5", "−0.00461; 0.9909", "−0.00491; 0.9914"], ["B-L5-d8", "−0.01132; 0.9886", "−0.01129; 0.9887"]];
  s.addTable([hdr.map((h) => ({ text: h, options: { bold: true, color: COL.muted, fontSize: 11 } })), ...rows.map((r) => r.map((c, j) => ({ text: c, options: { fontSize: 11.5, align: j ? "center" : "left" } })))],
    { x: 7.1, y: 1.6, w: 5.63, colW: [1.1, 2.27, 2.26], fontFace: BODY, border: { type: "solid", color: COL.rule, pt: 0.75 }, rowH: 0.4, margin: 0.05 });
  T(s, "Blocking power has the largest effect because giving it weight is harmful: the best region has critical path ≈ 1. A first Stage-0 design was flawed (matrix B was a digital shift of A); it was rebuilt, and the invalid output is kept separately and was not used.", { x: 7.1, y: 4.2, w: 5.6, h: 1.6, fontSize: 12, color: COL.ink2 });
}
{
  const s = apx("A5 · Phase 3: selective lookahead, final evaluation", "GM vs Critical Path First, run once on fresh seeds 81004–81007. Lower is better.");
  const hdr = ["set", "n", "QUALITY (B*)", "full pilot", "S1: pilot d8, workers ≤ 8", "S2: pilot d8", "S4: B* if n ≤ 150, else A*", "S4 share of B* gain"];
  const rows = [["In-distribution", "2,700", "0.9977", "0.9973", "0.9977", "0.9977", "0.9980", "87%"], ["XL (601–1,500)", "120", "0.9993", "0.9991", "0.9994", "0.9994", "1.0001", "0% (by design)"], ["OOD FFT + Gaussian", "900", "0.9969", "0.9965", "0.9972", "0.9970", "0.9974", "84%"], ["OOD BIMODAL", "600", "0.9972", "0.9966", "0.9971", "0.9971", "0.9977", "82%"], ["OOD Pareto (new)", "630", "0.9970", "0.9962", "0.9969", "0.9969", "0.9973", "90%"], ["Exact, GM Cmax/OPT", "595 solved", "1.0014", "1.0010", "1.0014", "1.0014", "1.0014", "100%"]];
  s.addTable([hdr.map((h) => ({ text: h, options: { bold: true, color: COL.muted, fontSize: 10.5 } })), ...rows.map((r) => r.map((c, j) => ({ text: c, options: { fontSize: 11.5, align: j ? "center" : "left" } })))],
    { x: 0.6, y: 1.55, w: 12.13, colW: [2.0, 1.1, 1.3, 1.2, 1.9, 1.2, 2.0, 1.43], fontFace: BODY, border: { type: "solid", color: COL.rule, pt: 0.75 }, rowH: 0.45, margin: 0.05 });
  T(s, "“Pilot” is an experimental rollout in the Phase 3 study simulator (CP-first continuation), not production code. The full-depth pilot is provably never worse than CP-first and had 0 losses on 4,950 final instances. Study-simulator timings are 25–45× faster than production for the same schedule, so they must not be compared with production timings. Six tie-break rules and a next-completion-aware CP rule stayed within ±0.02% of CP-first.", { x: 0.6, y: 5.0, w: 12.1, h: 1.6, fontSize: 12, color: COL.ink2 });
}
{
  const s = apx("A6 · Phase 4 (exploratory): weight profiles at bad decisions", "Seed 91001: 1,320 instances, 57,840 contested CP-first batches; 17.7% are “bad” (a swap improves the makespan).");
  const hdr = ["profile, applied at one batch", "differs from CP", "on bad batches: improves / worse", "net regret, all batches"];
  const rows = [["Original weights (K = 3)", "60.4%", "32.8% / 6.6%", "−8.3%"], ["FAST weights (K = 2)", "28.0%", "9.5% / 1.4%", "+2.0%"], ["CP + blocking power", "53.5%", "26.0% / 4.8%", "−13.2%"], ["CP + reachable workload", "31.7%", "12.1% / 1.5%", "+3.2%"], ["CP + unlocking", "52.6%", "25.5% / 5.0%", "−1.9%"], ["CP + duration-to-ready", "59.2%", "33.0% / 7.4%", "−40.2%"], ["CP + children-to-ready-queue", "19.7%", "3.3% / 0.3%", "+2.8%"]];
  s.addTable([hdr.map((h) => ({ text: h, options: { bold: true, color: COL.muted, fontSize: 11 } })), ...rows.map((r) => r.map((c, j) => ({ text: c, options: { fontSize: 11.5, align: j ? "center" : "left" } })))],
    { x: 0.6, y: 1.55, w: 8.3, colW: [2.9, 1.5, 2.3, 1.6], fontFace: BODY, border: { type: "solid", color: COL.rule, pt: 0.75 }, rowH: 0.42, margin: 0.05 });
  panel(s, 9.15, 1.55, 3.58, 5.3, COL.panel2);
  T(s, "Status", { x: 9.35, y: 1.65, w: 3.2, h: 0.3, fontSize: 13, bold: true, color: COL.bad });
  bullets(s, ["Exploration only: the development stage log is empty and no configuration was selected or tested.", "A starvation probe (8-event idle fraction) detected bad decisions no better than chance (AUC 0.47).", "An oracle choosing the best profile per batch would capture 62.6% of the best-swap regret, but a cheap depth-2 selector learned nothing (precision 0%).", "These numbers are not used for any claim in the main deck."], { x: 9.35, y: 2.05, w: 3.2, h: 4.7, fontSize: 11.5, gap: 6 });
}
{
  const s = apx("A7 · How the examples in this deck were verified");
  bullets(s, [
    [B("Real scheduler code. "), { text: "Every Gantt chart in Part II–III was produced by running the research version of the scheduler (Controller / TaskEngine) on the instance and reading back each worker’s executed tasks." }],
    [B("Single-component policies. "), { text: "Static: weights one-hot, λ = 1, K = 1, no lookahead. Dynamic: λ = 0, dynamic weights one-hot, K = all ready tasks, no lookahead. All use batched completions and id tie-breaks; dynamic ties fall back to the static order, as in the code." }],
    [B("Optimality. "), { text: "Each counterexample’s optimum (idle time allowed) and best list schedule come from the repository’s ExactSolver (branch and bound); they coincide on all examples shown." }],
    [B("Selection. "), { text: "Candidate instances came from 30,000 random 4–7-task DAGs (fixed seed). We kept small instances where the decisive choice is strict, not a tie. An independent Python list scheduler matched the Java makespans on all 90,000 static-policy runs." }],
    [B("Schedule validity. "), { text: "Every displayed schedule was checked for durations, precedence, no worker overlap, and a makespan equal to the reported value." }],
    [B("Lookahead trace. "), { text: "Rollout estimates (31 vs 29 at t = 8) were printed by a trace hook added to a scratch copy of the code; no repository file was modified." }],
  ], { x: 0.6, y: 1.3, w: 12.1, h: 5.5, fontSize: 14, gap: 10 });
}
{
  const s = apx("A8 · Limitations and reproducibility");
  bullets(s, [
    "Synthetic data from one generator: P | prec | Cmax with known, deterministic durations; no real traces, no duration noise, identical workers.",
    "Exact optima exist only for ≤ 18 tasks; on large graphs only lower bounds are available, and they are loose.",
    "QUALITY_OPTIMIZED is 29× the Original’s single-thread time on XL graphs (≈ 140 ms per run at n ≈ 1,000).",
    "Phase 2 search budgets were scaled down from the plan (about 16 h → 4.1 h). Given the plateau and the CMA-ES / random parity, more budget is unlikely to help; that is an inference, not a measurement.",
    "The rollout continuation (static priority) is not an exact model of execution, so the classical rollout improvement guarantee does not formally hold; measured loss rate vs the Original is 1.3%.",
    "Timings from different code bases (production vs the Phase 3 study simulator) are never compared directly in this deck.",
  ], { x: 0.6, y: 1.3, w: 7.4, h: 5.5, fontSize: 13, gap: 9 });
  panel(s, 8.3, 1.3, 4.43, 4.6, COL.panel2);
  T(s, "Reproduce", { x: 8.5, y: 1.4, w: 4.0, h: 0.3, fontSize: 13, bold: true });
  T(s, "mvn -o test        # 201 tests\nmvn -o compile\njava -cp target/classes \\\n  benchmark.ExperimentRunner \\\n  <main|confirm|xl|exact|…> out 12\njava -cp target/classes \\\n  benchmark.WeightOptimizer \\\n  <stage> target/phase2/run1 12\njava -cp target/classes \\\n  benchmark.CriticalPathStudy \\\n  <stage> target/phase3/run1 12",
    { x: 8.5, y: 1.8, w: 4.1, h: 3.6, fontFace: "Courier New", fontSize: 11, color: COL.ink2 });
  T(s, "Run from the research source tree. Raw outputs: research/raw-results/phase1-experiments, phase2/run1, phase3/run1, phase4/run1.", { x: 8.5, y: 4.85, w: 4.1, h: 0.9, fontSize: 11, color: COL.muted });
}

(async () => {
  await pres.writeFile({ fileName: OUT });
  await applyTheme(OUT, THEME);
  console.log("wrote", OUT);
})();
