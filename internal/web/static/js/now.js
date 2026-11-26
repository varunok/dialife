// The header and the "now" card: the newest reading, its trend and range,
// the forecast, the uploader's battery, time in range and an open alarm.

import { logout, session } from './api.js';
import { MIN, clock, dayTime, g, signed, t, unit } from './format.js';
import { alarmName, duration, lagLines, untrustedAt } from './entries.js';
import { V } from './model.js';
import { prefs, setPref } from './prefs.js';
import { LangPicker, arrowIcon } from './widgets.js';

const STALE_MIN = 15; // no newer reading this long: crossed out, "no fresh data"

// minutes since the reading was taken, counted on to now: no new reading must not look fresh
function ageMin(bg) { return Math.floor((Date.now() - bg.mills) / MIN); }

export const Top = {
  view() {
    const st = V.state, stale = st && st.bg && ageMin(st.bg) >= STALE_MIN;
    // the "no sensor" mode: no reading is expected, so none is missing
    const status = V.link === 'offline' ? ['offline', t('offline')] : st && st.sensorless ? ['sensorless', t('noSensor')]
      : stale ? ['offline', t('stale')] : ['live', t('live')];
    return m('header.top', [
      m('span.brand', 'DiaLife'),
      m('span.pill.' + status[0], [m('span.dot'), status[1]]),
      m('span.spacer'),
      m('.seg', ['mgdl', 'mmol'].map((u) => m('button', { class: prefs.units === u ? 'on' : '', 'aria-pressed': String(prefs.units === u), onclick: () => setPref('units', u) }, u === 'mgdl' ? 'mg/dL' : 'mmol/L'))),
      m(LangPicker, {}),
      session && m('span.who', t('allowedBy', session.by || '—', dayTime(session.expires))),
      m('button.ghost', { onclick: () => logout(true) }, t('logout'))
    ]);
  }
};

export const Now = {
  view() {
    const st = V.state;
    if (!st) return m('section.card.now', t('loading'));
    if (st.sensorless) return m('section.card.now', sensorless(st.sensorless, Date.now()));
    const bg = st.bg, th = st.thresholds || {};
    if (!bg) return m('section.card.now', t('noData'));
    const age = ageMin(bg), stale = age >= STALE_MIN;
    const range = bg.sgv < th.bgTargetBottom ? ['lo', t('low', g(th.bgTargetBottom))]
      : bg.sgv > th.bgTargetTop ? ['hi', t('high', g(th.bgTargetTop))]
      : ['in', t('inRange', g(th.bgTargetBottom) + '–' + g(th.bgTargetTop))];
    const f30 = st.ar2 && st.ar2.predicted && st.ar2.predicted[st.ar2.predicted.length - 1];
    const a = (st.alarms || []).find((x) => x.state !== 'cleared');
    // after a shower the sensor is not believed: dimmed, a bright warning, nothing counted from it
    const shower = untrustedAt(st, Date.now());
    return m('section.card.now', [
      m('.hero', [
        m('div', [m('span.v' + (stale ? '.stale' : shower ? '.untrusted' : ''), g(bg.sgv)), shower && m('span.warn', '⚠'), m('span.u', unit())]),
        !stale && m('span.arrow', { 'aria-label': bg.direction || '' }, arrowIcon(bg.direction))
      ]),
      m('.sub', [bg.delta != null && m('b', signed(bg.delta) + ' ' + t('in5')), m('span', ' · ' + (age < 1 ? t('justNow') : t('ago', age)))]),
      shower && m('.untrusted-card', [m('b', t('untrustedTitle', clock(shower.until))), m('span', t('untrustedText'))]),
      !stale && !shower && forecast(st, range, f30),
      m('.tiles', [
        m('.tile', [m('span.k', st.uploader_name || t('uploader')), m('span.n', st.uploader && st.uploader.battery != null ? st.uploader.battery + '%' : '—')]),
        m('.tile', [m('span.k', t('tir')), m('span.n', V.tir != null ? Math.round(V.tir) + '%' : '—')]),
        lagTile(V.meter && lagLines(V.meter.lag))
      ]),
      a && m('.alarm' + (a.level === 'urgent' ? '.urgent' : ''), [
        m('b', t('alarmSince', alarmName(a), clock(a.started_at))),
        m('span', a.handled_by ? t('handledBy', a.handled_by) : t('nobodyYet')),
        shower && a.kind !== 'stale' && m('span.check', t('untrustedAlarm'))
      ])
    ]);
  }
};

// the range and the 30-minute forecast, with how often it came true lately and what it cannot see
function forecast(st, range, f30) {
  const fq = st.forecast_quality;
  const hits = (q) => (q && q.n >= 50 ? q.hit_pct + '%' : t('forecastFew'));
  const blind = fq && fq.ignores && fq.ignores.slice().sort((x, y) => y.t - x.t)[0];
  return [
    m('.chips', [m('span.tag.' + range[0], range[1]), f30 && m('span.tag', fq && fq.n >= 50 ? t('forecastQ', g(f30.sgv), g(fq.err80)) : t('forecast', g(f30.sgv)))]),
    // apart for the quiet hours and after food: one number hides the second
    f30 && fq && (fq.quiet || fq.after_food) && m('.hint', t('forecastHits', hits(fq.quiet), hits(fq.after_food))),
    blind && m('.hint', t(blind.kind === 'food' ? 'blindFood' : 'blindInsulin', clock(blind.t)))
  ];
}

// In place of the reading while the "no sensor" mode is on: calm, no number (an old
// check is not the sugar now); a check out of range and the reminder stand out.
// The viewer only shows them: closing a card and logging a check are the phones'.
function sensorless(s, now) {
  const last = s.last_check;
  return [
    m('.sensorless-card', [
      m('b', t('sensorlessTitle')),
      m('span.last', last ? t('sensorlessLast', g(last.mg), unit(), duration(now - last.t)) : t('sensorlessLastNone')),
      m('span', t('sensorlessSince', clock(s.since), s.by || '—')),
      s.remind_at > 0 && !s.remind_due && m('span', t('sensorlessNext', clock(s.remind_at)))
    ]),
    s.card && m('.check-card.' + (s.card.kind === 'low' ? 'lo' : 'hi'), [
      m('b', t(s.card.kind === 'low' ? 'sensorlessCardLow' : 'sensorlessCardHigh', g(s.card.mg), unit())),
      m('span', t('sensorlessCardText', clock(s.card.t)))
    ]),
    s.remind_due && m('.untrusted-card', [
      m('b', t('sensorlessRemindTitle')),
      m('span', last ? t('sensorlessRemindText', duration(now - last.t)) : t('sensorlessRemindNone'))
    ])
  ];
}

// how far the sensor runs behind the glucometer, across the whole row
function lagTile(l) {
  return l && m('.tile.wide', [m('span.k', t('lag')), m('span.n', l.value), m('span.s', l.sub)]);
}
