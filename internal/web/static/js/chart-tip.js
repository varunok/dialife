// What a chart mark says on hover, and where its box goes.

import { MIN, clock, g, t, unit } from './format.js';
import { alarmLines, entryText, foodText, origin, suspectText } from './entries.js';

/** The box: under a point in the top half, above it in the bottom half, inside the plot. */
export function tipPos(b, plotW, plotH) {
  const left = Math.max(0, Math.min(plotW - 320, b.x - 160));
  const top = b.y > plotH / 2 ? Math.max(0, b.y - 96) : Math.min(plotH - 70, b.y + 18);
  return 'left:' + left + 'px;top:' + top + 'px';
}

/** The lines for a mark's data-tip id: "m:n" a meter check, "s:n" a suspect, "al:n" an alarm, "tr:id" an entry. */
export function describeMark(id, c) {
  const [kind, key] = id.split(':');
  const { h, meters, idx, ts, vs, win } = c;
  if (kind === 'm') {
    const p = meters[+key];
    const near = idx.filter((i) => Math.abs(ts[i] - p.t) <= 6 * MIN).sort((a, b) => Math.abs(ts[a] - p.t) - Math.abs(ts[b] - p.t))[0];
    return { lines: [t('meterTip', g(p.v) + ' ' + unit()), [clock(p.t), near != null ? t('sensorAt', g(vs[near])) : null, origin(p.tr, p.src)].filter(Boolean).join(' · ')] };
  }
  if (kind === 's') {
    const q = (h.suspects || []).filter((x) => x.from >= win.from && x.from <= win.to)[+key];
    return { lines: [q ? suspectText(q) : '?'] };
  }
  if (kind === 'al') {
    const al = (h.alarms || [])[+key];
    return al ? { lines: alarmLines(al) } : { lines: ['—'] };
  }
  const tr = (h.treatments || []).find((x) => x.id === key);
  if (!tr) return { lines: ['—'] };
  return { lines: [entryText(tr)].concat(foodText(tr) ? [foodText(tr)] : [], [[clock(tr.t), origin(tr)].join(' · ')], tr.notes && (tr.insulin || tr.carbs) ? [tr.notes] : []) };
}
