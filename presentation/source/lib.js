// Shared drawing helpers: palette, text, DAGs, Gantt charts.
const COL = {
  ink: "1C2230", ink2: "2B3242", muted: "5B6475", faint: "8A93A3", rule: "D5DAE1",
  bg: "FFFFFF", panel: "F3F5F8", panel2: "E9EDF2", white: "FFFFFF",
  cp: "C98A12",      // critical path / CP-first (ochre)
  fast: "1E5AA8",    // FAST_OPTIMIZED (blue)
  b0: "3AA36F",      // PHASE1_ROLLOUT (green)
  qual: "C0398F",    // QUALITY_OPTIMIZED / rollout lookahead (magenta)
  orig: "8A93A3",    // ORIGINAL (neutral)
  bad: "B83A3A", good: "2F7D4F",
  bar: "DCE3EC", barLine: "9AA5B5", idle: "F7E3E3",
};
const HEAD = "Cambria", BODY = "Calibri";

function T(slide, text, o) {
  if (!(o.w > 0) || !(o.h > 0)) throw new Error("bad text box size " + JSON.stringify([o.x, o.y, o.w, o.h, String(text).slice(0, 40)]));
  slide.addText(text, Object.assign({ isTextBox: true, fontFace: BODY, color: COL.ink, fontSize: 14, margin: 0, valign: "top" }, o));
}

function letterOf(id, style) { return style === "T" ? "T" + id : String.fromCharCode(64 + Number(id)); }

// DAG drawn left-to-right by longest-path level.
function dag(pres, slide, x, y, w, h, tasks, o = {}) {
  const ids = Object.keys(tasks).map(Number).sort((a, b) => a - b);
  const lvl = {};
  const L = (i) => { if (lvl[i] !== undefined) return lvl[i]; const ps = tasks[i].parents; lvl[i] = ps.length ? 1 + Math.max(...ps.map(L)) : 0; return lvl[i]; };
  ids.forEach(L);
  const nLev = Math.max(...ids.map((i) => lvl[i])) + 1;
  const byLev = Array.from({ length: nLev }, () => []);
  ids.forEach((i) => byLev[lvl[i]].push(i));
  if (o.order) byLev.forEach((arr) => arr.sort((a, b) => (o.order.indexOf(a) - o.order.indexOf(b))));
  const nw = o.nodeW || 0.62, nh = o.nodeH || 0.5;
  const colGap = nLev > 1 ? (w - nw) / (nLev - 1) : 0;
  const pos = {};
  byLev.forEach((arr, l) => {
    const n = arr.length; const gap = n > 1 ? (h - nh) / (n - 1) : 0;
    arr.forEach((i, k) => { pos[i] = { x: x + (nLev > 1 ? l * colGap : (w - nw) / 2), y: y + (n > 1 ? k * gap : (h - nh) / 2) }; });
  });
  const hiEdges = new Set((o.hiEdges || []).map((e) => e.join("-")));
  ids.forEach((c) => tasks[c].parents.forEach((p) => {
    const x1 = pos[p].x + nw, y1 = pos[p].y + nh / 2, x2 = pos[c].x, y2 = pos[c].y + nh / 2;
    const hi = hiEdges.has(p + "-" + c);
    slide.addShape(pres.shapes.LINE, { x: x1, y: Math.min(y1, y2), w: Math.max(x2 - x1, 0.01), h: Math.abs(y2 - y1), flipV: y2 < y1,
      line: { color: hi ? COL.cp : COL.faint, width: hi ? 2.25 : 1.25, endArrowType: "triangle" } });
  }));
  ids.forEach((i) => {
    const st = (o.style && o.style[i]) || {};
    const fill = st.fill || COL.white, line = st.line || COL.ink2, tc = st.text || COL.ink;
    slide.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: pos[i].x, y: pos[i].y, w: nw, h: nh, rectRadius: 0.08, fill: { color: fill }, line: { color: line, width: st.lw || 1.25 } });
    slide.addText([
      { text: letterOf(i, o.letters), options: { bold: true, fontSize: o.fs || 13, color: tc, breakLine: true } },
      { text: String(tasks[i].dur), options: { fontSize: 10, color: st.sub || COL.muted } },
    ], { isTextBox: true, x: pos[i].x, y: pos[i].y, w: nw, h: nh, align: "center", valign: "middle", margin: 0, fontFace: BODY, paraSpaceAfter: 0, lineSpacingMultiple: 0.85 });
    if (o.values && o.values[i] !== undefined) {
      T(slide, o.values[i], { x: pos[i].x - 0.25, y: pos[i].y + nh + 0.02, w: nw + 0.5, h: 0.24, fontSize: 10, color: (o.valColor && o.valColor[i]) || COL.muted, align: "center" });
    }
  });
  return pos;
}

// Gantt: lanes = [[ [id,s,e], ... ], ...]
function gantt(pres, slide, x, y, w, h, lanes, o = {}) {
  const Tmax = o.T || Math.max(...lanes.flat().map((s) => s[2]));
  const labW = 0.42, axisH = o.axis === false ? 0 : 0.26;
  const pw = w - labW, lh = (h - axisH) / lanes.length;
  const sx = (t) => x + labW + (t / Tmax) * pw;
  lanes.forEach((lane, k) => {
    const ly = y + k * lh;
    slide.addShape(pres.shapes.RECTANGLE, { x: x + labW, y: ly + lh * 0.12, w: pw, h: lh * 0.76, fill: { color: COL.panel }, line: { color: COL.panel, width: 0 } });
    T(slide, "W" + (k + 1), { x, y: ly, w: labW - 0.05, h: lh, fontSize: 10, color: COL.muted, valign: "middle" });
    lane.forEach(([id, s, e]) => {
      const st = (o.style && o.style[id]) || {};
      slide.addShape(pres.shapes.RECTANGLE, { x: sx(s), y: ly + lh * 0.12, w: sx(e) - sx(s), h: lh * 0.76,
        fill: { color: st.fill || COL.bar }, line: { color: COL.white, width: 1.5 } });
      if (sx(e) - sx(s) > 0.2) T(slide, o.label ? o.label[id] : letterOf(id, o.letters), { x: sx(s), y: ly + lh * 0.12, w: sx(e) - sx(s), h: lh * 0.76, fontSize: o.fs || 11, bold: true, color: st.text || COL.ink, align: "center", valign: "middle" });
    });
  });
  if (axisH) {
    const step = o.step || (Tmax <= 12 ? 1 : Tmax <= 24 ? 2 : Tmax <= 40 ? 5 : 10);
    for (let t = 0; t <= Tmax; t += step) {
      T(slide, String(t), { x: sx(t) - 0.15, y: y + h - axisH + 0.03, w: 0.3, h: 0.2, fontSize: 9, color: COL.faint, align: "center" });
    }
  }
  if (o.cmax !== undefined) {
    slide.addShape(pres.shapes.LINE, { x: sx(o.cmax), y: y - 0.04, w: 0, h: h - axisH + 0.04, line: { color: o.cmaxColor || COL.ink, width: 1.5, dashType: "dash" } });
  }
  return { sx, lh };
}

// Convert JSON gantt (lanes of [id,s,e]) to a sorted structure.
function lanesOf(g) { return g.map((lane) => lane.map((s) => [s[0], s[1], s[2]]).sort((a, b) => a[1] - b[1])); }

module.exports = { COL, HEAD, BODY, T, dag, gantt, lanesOf, letterOf };
