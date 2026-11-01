// The table's rows: the history cut into clock-aligned intervals, newest
// first, each with its sensor values, entries, meter checks, suspects and alarms.

import { MIN } from './format.js';
import { alarmRowClass, meterPoints } from './entries.js';

/**
 * @param h the history from the server
 * @param th the target range {bgTargetBottom, bgTargetTop}
 * @param w the period {from, to}
 * @param minutes the chosen interval; never finer than the history's own step
 * @param filter 'all' | 'entries' (rows with something logged) | 'out' (out of range)
 */
export function tableRows(h, th, w, minutes, filter) {
  const step = Math.max(minutes, h.step || 5) * MIN;
  const ts = h.sgv.t, vs = h.sgv.v, lo = h.sgv.lo, hi = h.sgv.hi;
  const tz = new Date(w.from).getTimezoneOffset() * MIN;
  const bucket = (tt) => step <= 5 * MIN && !h.step ? tt : Math.floor((tt - tz) / step) * step + tz;
  const rows = new Map();
  const row = (b) => {
    if (!rows.has(b)) rows.set(b, { t: b, vals: [], los: [], his: [], entries: [], meters: [], suspects: [], alarms: [] });
    return rows.get(b);
  };
  const inside = (tt) => tt >= w.from && tt <= w.to;

  ts.forEach((tt, i) => {
    if (!inside(tt)) return;
    const r = row(bucket(tt));
    r.vals.push(vs[i]); r.los.push(lo ? lo[i] : vs[i]); r.his.push(hi ? hi[i] : vs[i]);
  });
  (h.treatments || []).filter((tr) => inside(tr.t)).forEach((tr) => row(bucket(tr.t)).entries.push(tr));
  meterPoints(h).filter((p) => inside(p.t) && !p.tr).forEach((p) => row(bucket(p.t)).meters.push(p));
  const qs = (h.suspects || []).filter((q) => inside(q.from));
  qs.forEach((q) => row(bucket(q.from)).suspects.push(q));
  const als = h.alarms || [];
  als.filter((al) => inside(al.started_at)).forEach((al) => row(bucket(al.started_at)).alarms.push(al));

  let list = Array.from(rows.values()).sort((a, b) => b.t - a.t);
  list.forEach((r) => {
    r.avg = r.vals.length ? r.vals.reduce((a, b) => a + b, 0) / r.vals.length : null;
    r.min = r.los.length ? Math.min(...r.los) : null;
    r.max = r.his.length ? Math.max(...r.his) : null;
    r.out = r.min != null && (r.min < th.bgTargetBottom || r.max > th.bgTargetTop);
    r.has = r.entries.length + r.meters.length + r.suspects.length + r.alarms.length > 0;
    // a suspect or an alarm runs on through the rows it covers
    r.span = qs.some((q) => q.from <= r.t + step && q.to >= r.t);
    const al = als.find((a) => a.started_at <= r.t + step && (a.cleared_at || w.to) >= r.t);
    r.alarm = al ? alarmRowClass(al) : '';
  });
  // the change within a row, or else from the newest value of the nearest older row that has one
  // (a meter-only row has no sensor value)
  let older = null;
  for (let i = list.length - 1; i >= 0; i--) {
    const r = list[i];
    r.delta = r.vals.length > 1 ? r.vals[r.vals.length - 1] - r.vals[0] : (older && r.vals.length ? r.vals[0] - older.vals[older.vals.length - 1] : null);
    if (r.vals.length) older = r;
  }
  if (filter === 'entries') list = list.filter((r) => r.has);
  if (filter === 'out') list = list.filter((r) => r.out);
  return { list, step };
}
