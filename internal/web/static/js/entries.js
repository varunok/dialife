// How entries, glucometer checks, suspects and alarms read: shared by the
// chart's tooltips and the table.

import { amount, clock, g, hasText, t } from './format.js';

export function isBasal(tr) {
  if (tr.insulin_type) return tr.insulin_type === 'basal';
  return tr.event_type === 'Basal Injection';
}

/** Glucometer checks: entries with a glucose value and the ones xDrip+ sent as mbg. */
export function meterPoints(h) {
  const out = [];
  (h.treatments || []).forEach((tr) => { if (tr.glucose != null) out.push({ t: tr.t, v: tr.glucose, tr }); });
  const mb = h.mbg || { t: [], v: [] };
  mb.t.forEach((tt, i) => out.push({ t: tt, v: mb.v[i], src: (mb.d || [])[i] }));
  return out;
}

/** Who entered it, or which device sent it. */
export function origin(tr, src) {
  if (tr) {
    const who = tr.entered_by && tr.entered_by.toLowerCase() !== 'xdrip' ? tr.entered_by : null;
    return who ? t('typed', who) : t('typed', 'xDrip');
  }
  if (!src || src === 'manual') return t('typed', 'xDrip');
  return t('auto', src.split(' ')[0]);
}

// a meal logged as a list: "борщ 18 ≈ · хліб 22", "≈" on numbers from a suggestion
export function foodText(tr) {
  return (tr.food_items || []).map((f) => [f.name, f.carbs == null ? '' : Math.round(f.carbs) + (f.source === 'mine' || f.source === 'table' || f.source === 'estimate' ? ' ≈' : '')].filter(Boolean).join(' ')).join(' · ');
}

// a suspect carries its span; a meal without insulin, its time
export function suspectText(q) {
  if (q.kind === 'bolus') return t('suspectBolus', clock(q.from));
  if (q.kind === 'double') return t('suspectDouble', clock(q.from), clock(q.to));
  return t(q.kind === 'rise' ? 'suspectRise' : 'suspectFall', clock(q.from), clock(q.to), g(q.sgv_from), g(q.sgv_to));
}

export function alarmInk(al) {
  if (al.kind === 'low' || al.kind === 'predicted_low') return 'var(--low)';
  if (al.kind === 'stale') return 'var(--muted)';
  return 'var(--high)';
}

/** The row class an alarm gives the table rows it covers. */
export function alarmRowClass(al) {
  return al.kind === 'low' || al.kind === 'predicted_low' ? 'alarm-low' : al.kind === 'stale' ? 'alarm-stale' : 'alarm-high';
}

function alarmName(al) { return hasText('kind_' + al.kind) ? t('kind_' + al.kind) : t('kind_other'); }

function alarmText(al) {
  return al.cleared_at ? t('alarmSpan', alarmName(al), clock(al.started_at), clock(al.cleared_at)) : t('alarmOn', alarmName(al), clock(al.started_at));
}

export function alarmLines(al) {
  const more = [al.handled_by && t('alarmBy', al.handled_by), al.manual && t('alarmManual')].filter(Boolean).join(' · ');
  return more ? [alarmText(al), more] : [alarmText(al)];
}

export function entryText(tr) {
  if (tr.duration) return t('activity') + ' · ' + t('intensity_' + (tr.intensity || 'moderate')) + ' · ' + tr.duration + ' ' + t('minutes');
  const parts = [];
  if ((tr.insulin || 0) > 0) parts.push((tr.insulin_name || t(isBasal(tr) ? 'basal' : 'rapid')) + ' ' + amount(tr.insulin) + ' ' + t('insulinU'));
  if ((tr.carbs || 0) > 0) parts.push(amount(tr.carbs) + ' ' + t('carbsG'));
  if (!parts.length) return tr.notes || t('note');
  return parts.join(' · ');
}
