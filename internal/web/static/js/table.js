// The table under the chart: the same period in rows, a filter and the interval.

import { MIN, amount, clock, g, signed, span, t } from './format.js';
import { alarmInk, alarmLines, foodText, isBasal, origin, suspectText } from './entries.js';
import { V, timeWindow } from './model.js';
import { tableRows } from './rows.js';

const PAGE = 300; // rows shown at once; "show more" adds as many
const DEFAULT_TH = { bgTargetBottom: 80, bgTargetTop: 250 };

export const TableCard = {
  view() {
    const w = timeWindow();
    const th = (V.state && V.state.thresholds) || DEFAULT_TH;
    const data = V.history ? tableRows(V.history, th, w, V.step, V.filter) : null;
    const minStep = V.history && V.history.step ? V.history.step : 5;
    const seg = (key, opts) => m('.seg.sunk', opts.map(([v, label, disabled]) => m('button', {
      class: V[key] === v || (key === 'step' && Math.max(V.step, minStep) === v) ? 'on' : '',
      disabled, style: disabled ? 'opacity:.4;cursor:default' : '',
      onclick: () => { if (!disabled) { V[key] = v; V.shown = PAGE; } }
    }, label)));
    return m('section.card', [
      m('.table-head', [
        m('h2', t('data', span(w.from, w.to))),
        seg('filter', [['all', t('all')], ['entries', t('entriesOnly')], ['out', t('outOfRange')]]),
        seg('step', [[5, t('min5'), minStep > 5], [15, t('min15'), minStep > 15], [60, t('hour1')]])
      ]),
      !data ? m('.hint', t('loading')) : !data.list.length ? m('.hint', t('noData')) : m(Table, { list: data.list, step: data.step, th })
    ]);
  }
};

const rapid = (tr) => (tr.insulin || 0) > 0 && !isBasal(tr);
const basal = (tr) => (tr.insulin || 0) > 0 && isBasal(tr);

const Table = {
  view(v) {
    const { list, step, th } = v.attrs;
    const intervals = step > 5 * MIN || (V.history && V.history.step);
    const shown = list.slice(0, V.shown);
    return m('div', [
      m('.scroll', m('table', [
        m('thead', m('tr', [
          m('th', { style: 'padding-left:8px' }, intervals ? t('colInterval') : t('colTime')),
          m('th', intervals ? t('colSensorAgg') : t('colSensor')), m('th', t('colDelta')),
          m('th', { style: 'color:var(--text)' }, t('colMeter')), m('th.c-ins', t('colRapid')), m('th.c-bas', t('colBasal')),
          m('th.c-carb', t('colCarbs')), m('th.c-act', t('colActivity')), m('th', t('colNote'))
        ])),
        m('tbody', shown.map((r) => row(r, step, th, intervals))),
        m('tfoot', totals(list))
      ])),
      list.length > V.shown && m('button.ghost.more', { onclick: () => { V.shown += PAGE; } }, t('showMore', Math.min(PAGE, list.length - V.shown)))
    ]);
  }
};

function row(r, step, th, intervals) {
  const col = (val) => val == null ? '' : val < th.bgTargetBottom ? 'lo' : val > th.bgTargetTop ? 'hi' : 'ok';
  const at = (tt) => intervals ? ' · ' + clock(tt) : '';
  const ins = r.entries.filter(rapid), bas = r.entries.filter(basal);
  const carbs = r.entries.filter((tr) => (tr.carbs || 0) > 0), act = r.entries.filter((tr) => tr.duration);
  const meters = r.entries.filter((tr) => tr.glucose != null).map((tr) => ({ v: tr.glucose, t: tr.t, tr })).concat(r.meters);
  const notes = r.alarms.map((al) => m('span', { style: 'color:' + alarmInk(al) }, alarmLines(al).join(' · ')))
    .concat(r.suspects.map((q) => m('span.c-sus', suspectText(q))))
    .concat(r.entries.filter((tr) => foodText(tr)).map((tr) => foodText(tr)))
    .concat(r.entries.filter((tr) => tr.notes).map((tr) => tr.notes))
    .concat([...new Set(r.entries.map((tr) => origin(tr)).concat(r.meters.map((p) => origin(null, p.src))))]);
  const cell = (items) => items.length ? items.join(', ') : '·';
  return m('tr', { key: r.t, class: [r.has ? 'has' : '', r.span ? 'span' : '', r.alarm].join(' ') }, [
    m('td.t', intervals ? clock(r.t) + '–' + clock(r.t + step) : clock(r.t)),
    m('td.v', r.avg == null ? '' : [m('b.' + col(r.avg), g(r.avg)), intervals && m('small', [m('span.' + (r.min < th.bgTargetBottom ? 'lo' : ''), g(r.min)), '–', m('span.' + (r.max > th.bgTargetTop ? 'hi' : ''), g(r.max))])]),
    m('td.d', r.delta == null ? '' : signed(r.delta)),
    m('td.m', cell(meters.map((p) => g(p.v) + at(p.t)))),
    m('td.m.c-ins', cell(ins.map((tr) => amount(tr.insulin) + ' ' + (tr.insulin_name || '') + at(tr.t)))),
    m('td.m.c-bas', cell(bas.map((tr) => amount(tr.insulin) + ' ' + (tr.insulin_name || '') + at(tr.t)))),
    m('td.m.c-carb', cell(carbs.map((tr) => amount(tr.carbs) + ' ' + t('carbsG') + at(tr.t)))),
    m('td.m.c-act', cell(act.map((tr) => tr.duration + ' ' + t('minutes') + at(tr.t)))),
    m('td', notes.length ? notes.reduce((acc, n, i) => acc.concat(i ? [' · ', n] : [n]), []) : '')
  ]);
}

// the period's totals: mean and range, meter checks, insulin, carbs, activity
function totals(list) {
  const sum = (f) => list.reduce((a, r) => a + r.entries.reduce((b, tr) => b + (f(tr) || 0), 0), 0);
  const all = list.flatMap((r) => r.vals);
  return m('tr', [
    m('td', { style: 'padding-left:8px;color:var(--muted)' }, t('total')),
    m('td', all.length ? t('avg', g(all.reduce((a, b) => a + b, 0) / all.length)) + ' · ' + g(Math.min(...list.filter((r) => r.min != null).map((r) => r.min))) + '–' + g(Math.max(...list.filter((r) => r.max != null).map((r) => r.max))) : ''),
    m('td', ''),
    m('td', String(list.reduce((a, r) => a + r.meters.length + r.entries.filter((tr) => tr.glucose != null).length, 0))),
    m('td.c-ins', amount(sum((tr) => rapid(tr) ? tr.insulin : 0)) + ' ' + t('insulinU')),
    m('td.c-bas', amount(sum((tr) => basal(tr) ? tr.insulin : 0)) + ' ' + t('insulinU')),
    m('td.c-carb', amount(sum((tr) => tr.carbs)) + ' ' + t('carbsG')),
    m('td.c-act', sum((tr) => tr.duration) + ' ' + t('minutes')),
    m('td', '')
  ]);
}
