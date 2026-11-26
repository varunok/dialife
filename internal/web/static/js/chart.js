// The chart: the sensor, the target band, entries in three rows, glucometer
// diamonds, alarms and activity along the top, the forecast cone and the
// "≈ meter" line. Hover shows a value, the wheel zooms, dragging moves in time.

import { describeMark, tipPos } from './chart-tip.js';
import { HOUR, MIN, DAY, clock, dayOnly, dayTime, g, signed, t, unit } from './format.js';
import { alarmInk, isBasal, isShower, meterPoints, sensorlessIn, untrusted } from './entries.js';
import { V, loadHistory, timeWindow } from './model.js';
import { ChartHead } from './period.js';
import { prefs } from './prefs.js';

const H = 420, LABEL_W = 44;
const ROW_BASAL = 50, ROW_RAPID = 31, ROW_CARBS = 12; // marker rows above the plot's bottom
const DEFAULT_TH = { bgTargetBottom: 80, bgTargetTop: 250 };

export const ChartCard = {
  view() {
    return m('section.card', [
      m(ChartHead),
      m(Chart),
      m('.hint', V.history && V.history.step ? t('aggregated', V.history.step) : t('chartHint'))
    ]);
  }
};

const Chart = {
  oncreate(v) {
    const el = v.dom;
    v.state.ro = new ResizeObserver(() => { v.state.w = el.clientWidth; m.redraw(); });
    v.state.ro.observe(el);
    v.state.w = el.clientWidth;
    m.redraw();
  },
  onremove(v) { v.state.ro && v.state.ro.disconnect(); },
  view(v) {
    const s = v.state, h = V.history;
    if (!h) return m('.chart', m('div.hint', t('loading')));
    const c = geometry(s.w || 900, h, V.state, timeWindow());
    const out = [].concat(noSensor(c), shade(c), grid(c), activity(c), alarms(c), cone(c), meterBand(c), sensor(c), marks(c), diamonds(c));
    const tipBox = hover(s.tip, c, out);
    return m('.chart', {
      onmousemove: (e) => pointAt(s, e, c),
      onmouseleave: () => { s.tip = null; },
      onwheel: (e) => zoom(e, c),
      onmousedown: (e) => drag(e, c)
    }, [
      m('svg', { viewBox: '0 0 ' + c.W + ' ' + H, preserveAspectRatio: 'none', role: 'img', 'aria-label': t('lastH', Math.round(c.spanH)) }, out),
      tipBox && m('.tip', { style: tipPos(tipBox, c.plotW, c.plotH) }, [m('b', tipBox.lines[0])].concat(tipBox.lines.slice(1).map((l) => m('span', l))).concat(tipBox.extra ? [m('i', tipBox.extra)] : []))
    ]);
  }
};

// the scales and the data in view: everything the layers below draw from
function geometry(W, h, st, win) {
  const plotW = W - LABEL_W, plotH = H - 26;
  const agg = !!h.step;
  // live, with readings one by one: room for the next 30 minutes
  const fcSpan = win.live && !agg ? 30 * MIN : 0;
  const from = win.from, to = win.to + fcSpan;
  const ts = h.sgv.t, vs = h.sgv.v;
  const idx = ts.map((_, i) => i).filter((i) => ts[i] >= from && ts[i] <= win.to);
  const meters = meterPoints(h).filter((p) => p.t >= from && p.t <= win.to);
  const maxV = Math.max(300, ...idx.map((i) => agg ? h.sgv.hi[i] : vs[i]), ...meters.map((p) => p.v));
  const lo = 40, hi = Math.min(420, maxV + 20);
  const th = (st && st.thresholds) || DEFAULT_TH;
  const model = prefs.meterLine && V.meter && V.meter.ready && V.meter.model;
  return {
    W, plotW, plotH, h, st, win, from, to, ts, vs, idx, meters, agg, lo, hi, th, model,
    spanH: (to - from) / HOUR,
    x: (tt) => (tt - from) / (to - from) * plotW,
    y: (val) => plotH - (val - lo) / (hi - lo) * plotH,
    col: (val) => val < th.bgTargetBottom ? 'var(--low)' : val > th.bgTargetTop ? 'var(--high)' : 'var(--in)',
    // "≈ meter" from the sensor value: the estimate and its spread
    est: (val) => { const sc = Math.max(val, 100); return [val * (1 + model.b) + model.a + model.offset * sc, model.sd * sc]; },
    // the blood a reading shows was there the sensor's delay earlier
    lagMs: ((V.meter && V.meter.lag && V.meter.lag.used) || 0) * MIN
  };
}

const points = (pts) => pts.map((p) => p.join(',')).join(' ');

// the target band, the threshold lines and the time ticks
function grid(c) {
  const { x, y, th, plotW, plotH, W, win, from, to } = c;
  const out = [m('rect', { x: 0, y: y(th.bgTargetTop), width: Math.max(0, Math.min(plotW, x(win.to))), height: y(th.bgTargetBottom) - y(th.bgTargetTop), fill: 'var(--in)', 'fill-opacity': 0.07 })];
  [[th.bgTargetTop, 'var(--high)'], [th.bgTargetBottom, 'var(--low)']].forEach(([val, ink]) => {
    out.push(m('line', { x1: 0, x2: plotW, y1: y(val), y2: y(val), stroke: ink, 'stroke-opacity': 0.55, 'stroke-dasharray': '3 5' }));
    out.push(m('text', { x: W - 2, y: y(val) + 4, 'text-anchor': 'end', fill: 'var(--muted)' }, g(val)));
  });
  const step = [[2, HOUR / 4], [5, HOUR / 2], [10, HOUR], [20, 2 * HOUR], [40, 4 * HOUR], [100, 12 * HOUR], [Infinity, DAY]].find(([lim]) => c.spanH <= lim)[1];
  const tz = new Date(from).getTimezoneOffset() * MIN;
  let lastEnd = -Infinity;
  for (let tick = Math.ceil((from - tz) / step) * step + tz; tick <= Math.min(to, win.to); tick += step) {
    const tx = x(tick);
    out.push(m('line', { x1: tx, x2: tx, y1: 0, y2: plotH, stroke: 'var(--line)' }));
    const label = step >= DAY ? dayOnly(tick) : (new Date(tick).getHours() === 0 && step >= HOUR ? dayOnly(tick) : clock(tick));
    const lw = label.length * 7;
    const lx = Math.max(lw / 2, Math.min(plotW - lw / 2, tx));
    // labels that would overlap are left out
    if (lx - lw / 2 > lastEnd + 6) { out.push(m('text', { x: lx, y: H - 6, 'text-anchor': 'middle', fill: 'var(--faint)' }, label)); lastEnd = lx + lw / 2; }
  }
  return out;
}

// when the "no sensor" mode was on: a calm band with its name, no hatching and no amber,
// as nothing was wrong: there was simply no sensor; the glucometer checks sit on it as dots
function noSensor(c) {
  const { x, plotW, plotH, from, win } = c;
  return sensorlessIn(c.h.sensorless, from, win.to, Date.now()).flatMap((p) => {
    const x0 = Math.max(0, x(p.from)), x1 = Math.min(plotW, x(Math.min(p.until, Date.now())));
    if (x1 <= x0) return [];
    return [
      m('rect', { x: x0, y: 0, width: x1 - x0, height: plotH, fill: 'var(--sensorless)', 'fill-opacity': 0.10 }),
      m('line', { x1: x0, x2: x0, y1: 0, y2: plotH, stroke: 'var(--sensorless)', 'stroke-opacity': 0.55 }),
      !p.open && m('line', { x1: x1, x2: x1, y1: 0, y2: plotH, stroke: 'var(--sensorless)', 'stroke-opacity': 0.55 }),
      x1 - x0 > 70 && m('text', { x: x0 + 4, y: 32, fill: 'var(--sensorless)', style: 'font-weight:600' }, t('sensorlessChart'))
    ];
  });
}

// where the sensor is not believed (a shower and half an hour after): hatched, labelled at the top
function shade(c) {
  const { x, plotW, plotH } = c;
  const out = [m('defs', m('pattern', { id: 'untrusted', width: 8, height: 8, patternUnits: 'userSpaceOnUse', patternTransform: 'rotate(45)' },
    m('line', { x1: 0, y1: 0, x2: 0, y2: 8, stroke: 'var(--high)', 'stroke-opacity': 0.25, 'stroke-width': 1.5 })))];
  (c.h.untrusted || []).forEach((w) => {
    const x0 = Math.max(0, x(w.from)), x1 = Math.min(plotW, x(w.until));
    if (x1 <= x0) return;
    out.push(m('rect', { x: x0, y: 0, width: x1 - x0, height: plotH, fill: 'var(--high)', 'fill-opacity': 0.08 }),
      m('rect', { x: x0, y: 0, width: x1 - x0, height: plotH, fill: 'url(#untrusted)' }),
      m('line', { x1: x0, x2: x0, y1: 0, y2: plotH, stroke: 'var(--high)', 'stroke-opacity': 0.6, 'stroke-dasharray': '3 3' }),
      x1 - x0 > 50 && m('text', { x: x0 + 4, y: 32, fill: 'var(--high)', style: 'font-weight:600' }, '⚠ ' + t('untrustedChart')));
  });
  return out;
}

// activity bars along the top
function activity(c) {
  const { x, plotW, win, from } = c;
  return (c.h.treatments || []).filter((tr) => tr.duration && tr.t <= win.to && tr.t + tr.duration * MIN >= from).map((tr) => {
    const x0 = Math.max(0, x(tr.t)), x1 = Math.max(x0 + 6, Math.min(plotW, x(tr.t + tr.duration * MIN)));
    const a = isShower(tr) ? 0.55 : { vigorous: 0.85, moderate: 0.55 }[tr.intensity] || 0.3;
    return m('rect', { x: x0, y: 4, width: x1 - x0, height: 12, rx: 6, fill: isShower(tr) ? 'var(--high)' : 'var(--in)', 'fill-opacity': a, 'data-tip': 'act:' + tr.id });
  });
}

// alarm episodes: a coloured strip along the top, as long as they lasted
function alarms(c) {
  const { x, plotW, win } = c;
  const out = [];
  (c.h.alarms || []).forEach((al, n) => {
    const end = al.cleared_at || win.to;
    const x0 = Math.max(0, x(al.started_at)), x1 = Math.max(x0 + 4, Math.min(plotW, x(end)));
    if (x1 <= 0 || x0 >= plotW) return;
    out.push(m('rect', { x: x0, y: 20, width: x1 - x0, height: 6, rx: 3, fill: alarmInk(al), 'fill-opacity': al.level === 'urgent' ? 0.9 : 0.55, 'data-tip': 'al:' + n }));
  });
  return out;
}

// the forecast cone from the newest reading
function cone(c) {
  const { x, y, st, idx, ts, vs, plotW, hi, lo } = c;
  if (!(c.win.live && !c.agg && st && st.ar2 && st.ar2.cone && st.ar2.cone.length && idx.length)) return [];
  const li = idx[idx.length - 1];
  if (untrusted(c.h.untrusted, ts[li])) return []; // from a reading not believed it would carry the error on
  const pts = [[x(ts[li]), y(vs[li])]].concat(st.ar2.cone.map((p) => [Math.min(plotW, x(p.t)), y(Math.min(hi, p.hi))]))
    .concat(st.ar2.cone.slice().reverse().map((p) => [Math.min(plotW, x(p.t)), y(Math.max(lo, p.lo))]));
  return [m('polygon', { points: points(pts), fill: 'var(--in)', 'fill-opacity': 0.12 })];
}

// "≈ meter": a faint band and dashed line, from the model; only since the stretch it was
// learned on (the sensor's start or its last calibration), each point the delay earlier
function meterBand(c) {
  const { x, y, idx, ts, vs, hi, lo, est } = c;
  if (!(c.model && idx.length > 1)) return [];
  // nothing to estimate from where the sensor is not believed: the line breaks there
  const runs = [];
  idx.forEach((i) => {
    if (untrusted(c.h.untrusted, ts[i]) || ts[i] < (c.model.since || 0)) { runs.push([]); return; }
    if (!runs.length) runs.push([]);
    runs[runs.length - 1].push(i);
  });
  return runs.filter((r) => r.length > 1).flatMap((r) => meterRun(c, r));
}

function meterRun(c, idx) {
  const { y, ts, vs, hi, lo, est, lagMs } = c;
  const x = (t) => c.x(t - lagMs);
  const up = idx.map((i) => [x(ts[i]), y(Math.min(hi, est(vs[i])[0] + est(vs[i])[1]))]);
  const dn = idx.slice().reverse().map((i) => [x(ts[i]), y(Math.max(lo, est(vs[i])[0] - est(vs[i])[1]))]);
  return [
    m('polygon', { points: points(up.concat(dn)), fill: 'var(--est)', 'fill-opacity': 0.1 }),
    m('polyline', { points: idx.map((i) => x(ts[i]) + ',' + y(Math.min(hi, est(vs[i])[0]))).join(' '), fill: 'none', stroke: 'var(--est)', 'stroke-opacity': 0.75, 'stroke-width': 1.4, 'stroke-dasharray': '5 4' })
  ];
}

// the sensor: dots, or for intervals a band from lowest to highest with the mean; and the "now" line
function sensor(c) {
  const { x, y, col, idx, ts, vs, h, win, spanH, plotH } = c;
  const out = [];
  if (c.agg) {
    if (idx.length) {
      const up = idx.map((i) => [x(ts[i]), y(h.sgv.hi[i])]), dn = idx.slice().reverse().map((i) => [x(ts[i]), y(h.sgv.lo[i])]);
      out.push(m('polygon', { points: points(up.concat(dn)), fill: 'var(--in)', 'fill-opacity': 0.18 }));
      out.push(m('polyline', { points: idx.map((i) => x(ts[i]) + ',' + y(vs[i])).join(' '), fill: 'none', stroke: 'var(--in)', 'stroke-width': 1.8 }));
    }
  } else {
    const r = spanH <= 4 ? 3.2 : spanH <= 8 ? 2.4 : spanH <= 14 ? 1.8 : 1.4;
    // not believed (a shower): a faint hollow dot, so no one reads it as the sugar
    const dot = (i, rr) => untrusted(h.untrusted, ts[i])
      ? m('circle', { cx: x(ts[i]), cy: y(vs[i]), r: rr, fill: 'none', stroke: col(vs[i]), 'stroke-opacity': 0.6 })
      : m('circle', { cx: x(ts[i]), cy: y(vs[i]), r: rr, fill: col(vs[i]) });
    idx.forEach((i) => out.push(dot(i, r)));
    if (win.live && idx.length) {
      const li = idx[idx.length - 1];
      out.push(m('circle', { cx: x(ts[li]), cy: y(vs[li]), r: r + 4, fill: 'var(--surface)' }), dot(li, r + 2));
    }
  }
  if (win.live) out.push(m('line', { x1: x(win.to), x2: x(win.to), y1: 0, y2: plotH, stroke: 'var(--faint)', 'stroke-dasharray': '2 3' }));
  return out;
}

// treatment markers in three rows: long-acting, rapid, carbs and notes; suspects dashed
function marks(c) {
  const { x, h, from, win, plotH } = c;
  const list = [];
  (h.treatments || []).filter((tr) => tr.t >= from && tr.t <= win.to && tr.glucose == null && !tr.duration).forEach((tr) => {
    const ins = (tr.insulin || 0) > 0, bas = ins && isBasal(tr);
    const row = bas ? ROW_BASAL : ins ? ROW_RAPID : ROW_CARBS;
    const [ink, bg, glyph] = bas ? ['var(--basal)', 'var(--basal-bg)', t('glyphBasal')]
      : ins ? ['var(--insulin)', 'var(--insulin-bg)', t('glyphRapid')]
      : (tr.carbs || 0) > 0 ? ['var(--carbs)', 'var(--carbs-bg)', t('glyphCarbs')]
      : ['var(--muted)', 'var(--raised)', t('glyphNote')];
    list.push({ x: x(tr.t), y: plotH - row, ink, bg, glyph, tip: 'tr:' + tr.id });
  });
  (h.suspects || []).filter((q) => q.from >= from && q.from <= win.to).forEach((q, n) => {
    list.push({ x: x(q.from), y: plotH - (q.kind === 'rise' ? ROW_CARBS : ROW_RAPID), ink: 'var(--high)', bg: 'var(--high-bg)', glyph: '?', tip: 's:' + n, dashed: true });
  });
  const out = [];
  list.forEach((k) => {
    out.push(m('circle', { cx: k.x, cy: k.y, r: 10, fill: k.bg, stroke: k.dashed ? k.ink : 'none', 'stroke-dasharray': k.dashed ? '3 3' : null, 'data-tip': k.tip }));
    out.push(m('text', { x: k.x, y: k.y + 4, 'text-anchor': 'middle', fill: k.ink, style: 'font-family:var(--ui);font-weight:700;pointer-events:none' }, k.glyph));
  });
  return out;
}

// glucometer diamonds, over everything
function diamonds(c) {
  const out = [];
  c.meters.forEach((p, n) => {
    const cx = c.x(p.t), cy = c.y(p.v), d = (r) => 'M' + cx + ' ' + (cy - r) + 'L' + (cx + r) + ' ' + cy + 'L' + cx + ' ' + (cy + r) + 'L' + (cx - r) + ' ' + cy + 'Z';
    out.push(m('path', { d: d(9), fill: 'var(--surface)', 'data-tip': 'm:' + n }), m('path', { d: d(7), fill: 'var(--text)', style: 'pointer-events:none' }), m('path', { d: d(3.5), fill: c.col(p.v), style: 'pointer-events:none' }));
  });
  return out;
}

// the hovered reading gets a line and a ring (drawn into out); the box to show, if any
function hover(tip, c, out) {
  const { x, y, col, idx, ts, vs, h, agg, model, est, plotH } = c;
  if (tip && tip.kind === 'reading' && idx.includes(tip.i)) {
    const i = tip.i, px = x(ts[i]), py = y(vs[i]);
    out.push(m('line', { x1: px, x2: px, y1: 0, y2: plotH, stroke: 'var(--muted)' }), m('circle', { cx: px, cy: py, r: 6, fill: col(vs[i]), stroke: 'var(--surface)', 'stroke-width': 3 }));
    const lines = agg
      ? [g(vs[i]) + ' ' + unit(), g(h.sgv.lo[i]) + '–' + g(h.sgv.hi[i]) + ' · ' + clock(ts[i]) + '–' + clock(ts[i] + h.step * MIN)]
      : [g(vs[i]) + ' ' + unit(), (i > 0 && ts[i] - ts[i - 1] <= 10 * MIN ? signed(vs[i] - vs[i - 1]) + ' · ' : '') + dayTime(ts[i])];
    // not believed (a shower): said instead of an estimate made from it
    const extra = untrusted(h.untrusted, ts[i]) ? '⚠ ' + t('untrustedText') : model && ts[i] >= (model.since || 0) ? t('estimate', g(est(vs[i])[0]), g(est(vs[i])[1])) : null;
    return { x: px, y: py, lines, extra };
  }
  if (tip && tip.kind === 'mark') return Object.assign({ x: tip.x, y: tip.y }, describeMark(tip.id, c));
  return null;
}

// the mark under the pointer, or else the nearest reading
function pointAt(s, e, c) {
  const rect = e.currentTarget.getBoundingClientRect();
  const mx = e.clientX - rect.left, my = e.clientY - rect.top;
  const target = e.target.getAttribute && e.target.getAttribute('data-tip');
  if (target) { s.tip = { kind: 'mark', id: target, x: mx, y: my }; return; }
  if (!c.idx.length || mx > c.plotW) { s.tip = null; return; }
  let best = c.idx[0];
  c.idx.forEach((i) => { if (Math.abs(c.x(c.ts[i]) - mx) < Math.abs(c.x(c.ts[best]) - mx)) best = i; });
  s.tip = { kind: 'reading', i: best };
}

let zoomTimer = null;

// the wheel zooms around the pointer, never past now nor under an hour
function zoom(e, c) {
  e.preventDefault();
  const to0 = c.win.to;
  const rect = e.currentTarget.getBoundingClientRect();
  const at = c.from + (e.clientX - rect.left) / c.plotW * (to0 - c.from);
  const f = e.deltaY > 0 ? 1.25 : 0.8, len = Math.max(HOUR, (to0 - c.from) * f);
  const r = (at - c.from) / (to0 - c.from);
  let from = at - r * len, to = from + len;
  const now = Date.now();
  if (to > now) { to = now; from = now - len; }
  clearTimeout(zoomTimer);
  V.period = { from, to };
  zoomTimer = setTimeout(loadHistory, 250);
}

// dragging moves the period in time, never past now
function drag(e, c) {
  if (e.button !== 0) return;
  const x0 = e.clientX, from0 = c.from, to0 = c.win.to, w = c.plotW;
  let moved = false;
  const move = (ev) => {
    const dt = (x0 - ev.clientX) / w * (to0 - from0);
    if (Math.abs(ev.clientX - x0) < 4 && !moved) return;
    moved = true;
    let from = from0 + dt, to = to0 + dt;
    const now = Date.now();
    if (to > now) { from -= to - now; to = now; }
    V.period = { from, to };
    m.redraw();
  };
  const up = () => { window.removeEventListener('mousemove', move); window.removeEventListener('mouseup', up); if (moved) loadHistory(); };
  window.addEventListener('mousemove', move);
  window.addEventListener('mouseup', up);
}
