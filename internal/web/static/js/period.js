// The chart card's header: the period button and its popup, the meter line
// switch and the quick spans.

import { DAY, HOUR, span, t } from './format.js';
import { V, setPeriod, timeWindow } from './model.js';
import { prefs, setPref } from './prefs.js';
import { info } from './info.js';
import { calIcon } from './widgets.js';

export const ChartHead = {
  view() {
    const w = timeWindow();
    const ready = V.meter && V.meter.ready;
    return m('.chart-head', [
      m('button.period', { 'aria-haspopup': 'dialog', 'aria-expanded': String(V.popOpen), onclick: () => { V.popOpen = !V.popOpen; } },
        [calIcon(), V.period.last ? (V.period.last === 7 * DAY ? t('last7d') : t('lastH', V.period.last / HOUR)) : span(w.from, w.to), ' ▾']),
      V.popOpen && m(PeriodPop),
      m('span.spacer'),
      m('button.chip' + (prefs.meterLine && ready ? '.on' : ''), {
        title: ready ? '' : t('meterWait', V.meter ? V.meter.used : 0, V.meter ? V.meter.need : 8),
        disabled: !ready, style: ready ? '' : 'opacity:.55;cursor:default',
        onclick: () => setPref('meterLine', !prefs.meterLine)
      }, t('meter')),
      info('meter-estimate'),
      m('.seg.sunk', [3, 6, 12, 24].map((h) => m('button', { class: V.period.last === h * HOUR ? 'on' : '', onclick: () => setPeriod({ last: h * HOUR }) }, t('h', h)))
        .concat([m('button', { class: V.period.last === 7 * DAY ? 'on' : '', onclick: () => setPeriod({ last: 7 * DAY }) }, t('d7'))]))
    ]);
  }
};

function toLocalInput(ms) {
  const d = new Date(ms), p = (n) => String(n).padStart(2, '0');
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + 'T' + p(d.getHours()) + ':' + p(d.getMinutes());
}

function startOfDay(ms) { const d = new Date(ms); d.setHours(0, 0, 0, 0); return d.getTime(); }

const PeriodPop = {
  oninit(v) { const w = timeWindow(); v.state.from = toLocalInput(w.from); v.state.to = toLocalInput(w.to); v.state.err = null; },
  view(v) {
    const s = v.state, now = Date.now(), today = startOfDay(now);
    const preset = (label, from, to) => m('button.chip', { onclick: () => setPeriod({ from, to }) }, label);
    return m('.pop', { role: 'dialog', 'aria-label': t('anyPeriod') }, [
      m('.two', [
        m('label', [t('from'), m('input', { type: 'datetime-local', value: s.from, oninput: (e) => { s.from = e.target.value; } })]),
        m('label', [t('to'), m('input', { type: 'datetime-local', value: s.to, oninput: (e) => { s.to = e.target.value; } })])
      ]),
      m('.row', [
        preset(t('today'), today, now),
        preset(t('yesterday'), today - DAY, today),
        preset(t('night'), today - 2 * HOUR, today + 7 * HOUR),
        preset(t('days3'), now - 3 * DAY, now),
        preset(t('days14'), now - 14 * DAY, now)
      ]),
      s.err && m('.err', s.err),
      m('.go', [m('span', t('anyPeriod')), m('button.primary', {
        onclick: () => {
          const from = new Date(s.from).getTime(), to = new Date(s.to).getTime();
          if (!(from < to)) { s.err = t('badRange'); return; }
          setPeriod({ from, to: Math.min(to, now) });
        }
      }, t('show'))])
    ]);
  }
};
