// DiaLife web viewer: read-only. It logs in by a QR code a parent scans in the
// app, then shows the current reading, the chart and the table, live.
'use strict';

const MIN = 60000, HOUR = 60 * MIN, DAY = 24 * HOUR;
const STORE = 'dialife.web'; // {token, expires, by} after a parent allowed this browser
const PREFS = 'dialife.web.prefs'; // {lang, units, meterLine}

// ---------- small helpers ----------

function load(key) { try { return JSON.parse(localStorage.getItem(key) || 'null'); } catch (e) { return null; } }
function save(key, v) { try { v == null ? localStorage.removeItem(key) : localStorage.setItem(key, JSON.stringify(v)); } catch (e) { /* private mode */ } }

const prefs = Object.assign({ lang: null, units: 'mgdl', meterLine: false }, load(PREFS) || {});
function setPref(k, v) { prefs[k] = v; save(PREFS, prefs); }

function lang() {
  if (prefs.lang && DL_TEXTS[prefs.lang]) return prefs.lang;
  const nav = (navigator.languages || [navigator.language || 'en']).map((l) => l.slice(0, 2));
  return nav.find((l) => DL_TEXTS[l]) || 'en';
}
function t(key, ...args) {
  const s = (DL_TEXTS[lang()] || DL_TEXTS.en)[key] || DL_TEXTS.en[key] || key;
  return s.replace(/\{(\d)\}/g, (_, i) => args[+i] == null ? '' : args[+i]);
}
function locale() { return { uk: 'uk-UA', en: 'en-GB', es: 'es-ES' }[lang()]; }
function clock(ms) { return new Date(ms).toLocaleTimeString(locale(), { hour: '2-digit', minute: '2-digit' }); }
function dayTime(ms) { return new Date(ms).toLocaleString(locale(), { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }); }
function dayOnly(ms) { return new Date(ms).toLocaleDateString(locale(), { day: 'numeric', month: 'short' }); }
function sameDay(a, b) { return new Date(a).toDateString() === new Date(b).toDateString(); }
function span(from, to) { return sameDay(from, to) ? dayOnly(from) + ' ' + clock(from) + '–' + clock(to) : dayTime(from) + ' — ' + dayTime(to); }

function g(mg) { return prefs.units === 'mmol' ? (mg / 18).toFixed(1).replace('.', lang() === 'en' ? '.' : ',') : String(Math.round(mg)); }
function unit() { return prefs.units === 'mmol' ? 'mmol/L' : 'mg/dL'; }
function signed(mg) {
  const s = mg > 0 ? '+' : mg < 0 ? '−' : '±';
  return s + (prefs.units === 'mmol' ? (Math.abs(mg) / 18).toFixed(1).replace('.', lang() === 'en' ? '.' : ',') : Math.abs(Math.round(mg)));
}
function amount(v) { return Number.isInteger(v) ? String(v) : v.toFixed(1).replace('.', lang() === 'en' ? '.' : ','); }

// ---------- the API ----------

let session = load(STORE);
if (session && session.expires < Date.now()) { session = null; save(STORE, null); }

async function api(path, opts = {}) {
  const headers = Object.assign({ 'Content-Type': 'application/json' }, opts.headers || {});
  if (session) headers.Authorization = 'Bearer ' + session.token;
  const r = await fetch(path, Object.assign({}, opts, { headers }));
  if (r.status === 401 && session) { logout(false); throw new Error('unauthorized'); }
  if (!r.ok) throw new Error('http ' + r.status);
  return r.status === 204 ? null : r.json();
}

function logout(tellServer) {
  if (tellServer && session) api('/api/web/logout', { method: 'POST' }).catch(() => {});
  session = null;
  save(STORE, null);
  stopStream();
  Login.start();
  m.redraw();
}

// ---------- login by QR ----------

const Login = {
  code: null, // {id, secret, expires_at}
  note: null,
  timer: null,
  async start() {
    clearTimeout(Login.timer);
    Login.code = null;
    try {
      Login.code = await api('/api/web/login', { method: 'POST' });
    } catch (e) {
      Login.note = t('codeError');
      Login.timer = setTimeout(Login.start, 5000);
      m.redraw();
      return;
    }
    m.redraw();
    Login.poll();
  },
  async poll() {
    const c = Login.code;
    if (!c) return;
    let r;
    try {
      r = await api('/api/web/login/' + c.id + '/poll', { method: 'POST', body: JSON.stringify({ secret: c.secret }) });
    } catch (e) {
      r = { state: Date.now() > c.expires_at ? 'expired' : 'pending' };
    }
    if (r.state === 'approved') {
      session = { token: r.token, expires: r.expires_at, by: r.by };
      save(STORE, session);
      Login.code = null;
      Login.note = null;
      Viewer.start();
      m.redraw();
      return;
    }
    if (r.state === 'rejected') { Login.note = t('codeRejected'); return Login.start(); }
    // scanned: a parent has it on the phone; keep this code as long as the server keeps it
    if (r.expires_at) c.expires_at = r.expires_at;
    c.scanned = r.state === 'scanned';
    if (r.state === 'expired' || Date.now() > c.expires_at) { Login.note = null; return Login.start(); }
    Login.timer = setTimeout(Login.poll, 2000);
    m.redraw();
  },
  view() {
    const c = Login.code;
    const left = c ? Math.max(0, Math.round((c.expires_at - Date.now()) / 1000)) : 0;
    return m('.login', [
      m(LangPicker, { cls: 'lang' }),
      m('.card', [
        m('div', { style: 'display:grid;gap:18px' }, [
          m('.brand', 'DiaLife'),
          m('h1', t('loginTitle')),
          m('ol', [m('li', t('loginStep1')), m('li', m.trust(t('loginStep2'))), m('li', t('loginStep3'))]),
          Login.note && m('.err', Login.note)
        ]),
        m('.qrbox', [
          m('.qr', c ? m('img', { src: '/api/web/login/' + c.id + '/qr.svg', alt: 'QR' }) : m('.wait', t('codeLoading'))),
          c && m('span', c.scanned ? t('codeScanned') : t('codeLeft', Math.floor(left / 60) + ':' + String(left % 60).padStart(2, '0')))
        ])
      ])
    ]);
  }
};
setInterval(() => { if (!session) m.redraw(); }, 1000); // the countdown

const LangPicker = {
  view(v) {
    return m('select.ghost', {
      class: v.attrs.cls, 'aria-label': 'Language',
      value: lang(), onchange: (e) => { setPref('lang', e.target.value); document.documentElement.lang = e.target.value; }
    }, Object.keys(DL_TEXTS).map((k) => m('option', { value: k }, DL_TEXTS[k].langName)));
  }
};

// ---------- the viewer's data ----------

const V = {
  state: null, history: null, meter: null, tir: null,
  link: 'connecting', // live | offline
  period: { last: 6 * HOUR }, // or {from, to}
  loading: false, error: null,
  popOpen: false,
  filter: 'all', step: 5, shown: 300
};

function window_() {
  if (V.period.last) { const to = Date.now(); return { from: to - V.period.last, to, live: true }; }
  return { from: V.period.from, to: V.period.to, live: V.period.to >= Date.now() - 2 * MIN };
}

let fetchSeq = 0;
async function loadHistory() {
  const w = window_();
  const seq = ++fetchSeq;
  V.loading = true;
  try {
    // the table may ask for coarser intervals; the server never goes finer than the span allows
    const h = await api('/api/history?from=' + Math.round(w.from) + '&to=' + Math.round(w.to));
    if (seq !== fetchSeq) return;
    V.history = h;
    V.error = null;
  } catch (e) {
    if (seq === fetchSeq) V.error = String(e);
  }
  V.loading = false;
  m.redraw();
}

async function loadState() {
  try { V.state = await api('/api/state'); } catch (e) { /* the stream retries */ }
  m.redraw();
}
async function loadMeter() { try { V.meter = await api('/api/meter'); } catch (e) { V.meter = null; } m.redraw(); }
async function loadTir() { try { const s = await api('/api/stats?days=1'); V.tir = s.zones ? s.zones.in_range : null; } catch (e) { V.tir = null; } m.redraw(); }

// SSE over fetch, so the token goes in a header and never in a URL
let streamAbort = null;
function stopStream() { if (streamAbort) streamAbort.abort(); streamAbort = null; }
async function runStream() {
  stopStream();
  const ctl = new AbortController();
  streamAbort = ctl;
  let backoff = 2000;
  while (!ctl.signal.aborted && session) {
    try {
      const r = await fetch('/api/stream', { headers: { Authorization: 'Bearer ' + session.token, Accept: 'text/event-stream' }, signal: ctl.signal });
      if (r.status === 401) { logout(false); return; }
      if (!r.ok || !r.body) throw new Error('stream ' + r.status);
      V.link = 'live'; backoff = 2000; m.redraw();
      const reader = r.body.getReader();
      const dec = new TextDecoder();
      let buf = '';
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buf += dec.decode(value, { stream: true });
        let i;
        while ((i = buf.indexOf('\n\n')) >= 0) {
          const block = buf.slice(0, i); buf = buf.slice(i + 2);
          let type = 'message', data = '';
          for (const line of block.split('\n')) {
            if (line.startsWith('event:')) type = line.slice(6).trim();
            else if (line.startsWith('data:')) data += line.slice(5).trim();
          }
          if (data) onEvent(type, data);
        }
      }
    } catch (e) {
      if (ctl.signal.aborted) return;
    }
    V.link = 'offline'; m.redraw();
    await new Promise((res) => setTimeout(res, backoff));
    backoff = Math.min(backoff * 2, 60000);
  }
}

function onEvent(type, data) {
  let d;
  try { d = JSON.parse(data); } catch (e) { return; }
  if (type === 'logout') { logout(false); return; } // closed from a parent's phone, or expired
  if (type === 'state' || type === 'bg') {
    V.state = Object.assign({}, d, { display_units: (V.state || {}).display_units });
    if (window_().live) loadHistory();
  } else if (type === 'history' || type === 'treatment') {
    loadHistory(); loadMeter();
  } else if (['alarm', 'handled', 'checkin', 'checkin_ok', 'help', 'coming', 'clear'].includes(type)) {
    loadState(); loadHistory(); // the episode shows on the chart and in the table
  } else if (type === 'uploader' && V.state) {
    V.state = Object.assign({}, V.state, { uploader: d });
  }
  m.redraw();
}

const Viewer = {
  start() {
    loadState(); loadHistory(); loadMeter(); loadTir(); runStream();
    clearInterval(Viewer.tick);
    // minutes-ago and the "last N hours" window move on without new readings
    Viewer.tick = setInterval(() => { if (V.period.last) m.redraw(); }, 30000);
    clearInterval(Viewer.tirTick);
    Viewer.tirTick = setInterval(loadTir, 15 * MIN);
  },
  view() {
    return m('.page', [m(Top), m('.grid', [m(Now), m(ChartCard)]), m(TableCard)]);
  }
};

function setPeriod(p) {
  V.period = p; V.popOpen = false; V.shown = 300;
  loadHistory();
}

// ---------- header ----------

const Top = {
  view() {
    const st = V.state, stale = st && st.bg && st.bg.age_sec > 15 * 60;
    const status = V.link === 'offline' ? ['offline', t('offline')] : stale ? ['offline', t('stale')] : ['live', t('live')];
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

// ---------- now ----------

const Now = {
  view() {
    const st = V.state;
    if (!st) return m('section.card.now', t('loading'));
    const bg = st.bg, th = st.thresholds || {};
    if (!bg) return m('section.card.now', t('noData'));
    const ageMin = Math.floor(bg.age_sec / 60), stale = ageMin >= 15;
    const range = bg.sgv < th.bgTargetBottom ? ['lo', t('low', g(th.bgTargetBottom))]
      : bg.sgv > th.bgTargetTop ? ['hi', t('high', g(th.bgTargetTop))]
      : ['in', t('inRange', g(th.bgTargetBottom) + '–' + g(th.bgTargetTop))];
    const f30 = st.ar2 && st.ar2.predicted && st.ar2.predicted[st.ar2.predicted.length - 1];
    const a = (st.alarms || []).find((x) => x.state !== 'cleared');
    const fq = st.forecast_quality; // how often "in 30 min" came true lately, and what it cannot see
    const hits = (q) => (q && q.n >= 50 ? q.hit_pct + '%' : t('forecastFew'));
    const blind = fq && fq.ignores && fq.ignores.slice().sort((x, y) => y.t - x.t)[0];
    return m('section.card.now', [
      m('.hero', [
        m('div', [m('span.v' + (stale ? '.stale' : ''), g(bg.sgv)), m('span.u', unit())]),
        !stale && m('span.arrow', { 'aria-label': bg.direction || '' }, arrowIcon(bg.delta_exact != null ? bg.delta_exact : bg.delta))
      ]),
      m('.sub', [bg.delta != null && m('b', signed(bg.delta) + ' ' + t('in5')), m('span', ' · ' + (ageMin < 1 ? t('justNow') : t('ago', ageMin)))]),
      !stale && m('.chips', [m('span.tag.' + range[0], range[1]), f30 && m('span.tag', fq && fq.n >= 50 ? t('forecastQ', g(f30.sgv), g(fq.err80)) : t('forecast', g(f30.sgv)))]),
      // how often it came true, apart for the quiet hours and after food: one number hides the second
      !stale && f30 && fq && (fq.quiet || fq.after_food) && m('.hint', t('forecastHits', hits(fq.quiet), hits(fq.after_food))),
      !stale && blind && m('.hint', t(blind.kind === 'food' ? 'blindFood' : 'blindInsulin', clock(blind.t))),
      m('.tiles', [
        m('.tile', [m('span.k', st.uploader_name || t('uploader')), m('span.n', st.uploader && st.uploader.battery != null ? st.uploader.battery + '%' : '—')]),
        m('.tile', [m('span.k', t('tir')), m('span.n', V.tir != null ? Math.round(V.tir) + '%' : '—')])
      ]),
      a && m('.alarm' + (a.level === 'urgent' ? '.urgent' : ''), [
        m('b', t('alarmSince', t('kind_' + a.kind) || t('kind_other'), clock(a.started_at))),
        m('span', a.handled_by ? t('handledBy', a.handled_by) : t('nobodyYet'))
      ])
    ]);
  }
};

function arrowIcon(d) {
  const deg = d == null ? 0 : d >= 10 ? -90 : d >= 5 ? -45 : d <= -10 ? 90 : d <= -5 ? 45 : 0;
  return m('svg', { viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': 2.4, 'stroke-linecap': 'round', 'stroke-linejoin': 'round', style: 'transform: rotate(' + deg + 'deg)' },
    [m('path', { d: 'M5 12h14' }), m('path', { d: 'M13 6l6 6-6 6' })]);
}

// ---------- chart ----------

const ChartCard = {
  view() {
    const w = window_();
    const ready = V.meter && V.meter.ready;
    return m('section.card', [
      m('.chart-head', [
        m('button.period', { 'aria-haspopup': 'dialog', 'aria-expanded': String(V.popOpen), onclick: () => { V.popOpen = !V.popOpen; } },
          [calIcon(), V.period.last ? (V.period.last === 7 * DAY ? t('last7d') : t('lastH', V.period.last / HOUR)) : span(w.from, w.to), ' ▾']),
        V.popOpen && m(PeriodPop),
        m('span.spacer'),
        m('button.chip' + (prefs.meterLine && ready ? '.on' : ''), {
          title: ready ? '' : t('meterWait', V.meter ? V.meter.used : 0, V.meter ? V.meter.need : 8),
          disabled: !ready, style: ready ? '' : 'opacity:.55;cursor:default',
          onclick: () => setPref('meterLine', !prefs.meterLine)
        }, t('meter')),
        m('.seg.sunk', [3, 6, 12, 24].map((h) => m('button', { class: V.period.last === h * HOUR ? 'on' : '', onclick: () => setPeriod({ last: h * HOUR }) }, t('h', h)))
          .concat([m('button', { class: V.period.last === 7 * DAY ? 'on' : '', onclick: () => setPeriod({ last: 7 * DAY }) }, t('d7'))]))
      ]),
      m(Chart),
      m('.hint', V.history && V.history.step ? t('aggregated', V.history.step) : t('chartHint'))
    ]);
  }
};

function calIcon() {
  return m('svg', { width: 16, height: 16, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': 2, 'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': 'true' },
    [m('rect', { x: 3, y: 5, width: 18, height: 16, rx: 2 }), m('path', { d: 'M3 10h18M8 3v4M16 3v4' })]);
}

function toLocalInput(ms) {
  const d = new Date(ms), p = (n) => String(n).padStart(2, '0');
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + 'T' + p(d.getHours()) + ':' + p(d.getMinutes());
}
function startOfDay(ms) { const d = new Date(ms); d.setHours(0, 0, 0, 0); return d.getTime(); }

const PeriodPop = {
  oninit(v) { const w = window_(); v.state.from = toLocalInput(w.from); v.state.to = toLocalInput(w.to); v.state.err = null; },
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

const ROW_BASAL = 50, ROW_RAPID = 31, ROW_CARBS = 12; // marker rows above the plot's bottom

function isBasal(tr) {
  if (tr.insulin_type) return tr.insulin_type === 'basal';
  return tr.event_type === 'Basal Injection';
}

function meterPoints(h) {
  const out = [];
  (h.treatments || []).forEach((tr) => { if (tr.glucose != null) out.push({ t: tr.t, v: tr.glucose, tr }); });
  const mb = h.mbg || { t: [], v: [] };
  mb.t.forEach((tt, i) => out.push({ t: tt, v: mb.v[i], src: (mb.d || [])[i] }));
  return out;
}

function origin(tr, src) {
  if (tr) {
    const who = tr.entered_by && tr.entered_by.toLowerCase() !== 'xdrip' ? tr.entered_by : null;
    return who ? t('typed', who) : t('typed', 'xDrip');
  }
  if (!src || src === 'manual') return t('typed', 'xDrip');
  return t('auto', src.split(' ')[0]);
}

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
    const s = v.state, W = s.w || 900, H = 420, labelW = 44, plotW = W - labelW, plotH = H - 26;
    const h = V.history, st = V.state;
    const win = window_();
    const fcSpan = win.live && !(h && h.step) ? 30 * MIN : 0;
    const from = win.from, to = win.to + fcSpan;
    const x = (tt) => (tt - from) / (to - from) * plotW;
    const th = (st && st.thresholds) || { bgTargetBottom: 80, bgTargetTop: 250 };
    if (!h) return m('.chart', m('div.hint', t('loading')));
    const ts = h.sgv.t, vs = h.sgv.v, agg = !!h.step;
    const idx = ts.map((_, i) => i).filter((i) => ts[i] >= from && ts[i] <= win.to);
    const meters = meterPoints(h).filter((p) => p.t >= from && p.t <= win.to);
    const maxV = Math.max(300, ...idx.map((i) => agg ? h.sgv.hi[i] : vs[i]), ...meters.map((p) => p.v));
    const lo = 40, hi = Math.min(420, maxV + 20);
    const y = (val) => plotH - (val - lo) / (hi - lo) * plotH;
    const col = (val) => val < th.bgTargetBottom ? 'var(--low)' : val > th.bgTargetTop ? 'var(--high)' : 'var(--in)';
    const out = [];

    // band, thresholds and hour ticks
    out.push(m('rect', { x: 0, y: y(th.bgTargetTop), width: Math.max(0, Math.min(plotW, x(win.to))), height: y(th.bgTargetBottom) - y(th.bgTargetTop), fill: 'var(--in)', 'fill-opacity': 0.07 }));
    [[th.bgTargetTop, 'var(--high)'], [th.bgTargetBottom, 'var(--low)']].forEach(([val, c]) => {
      out.push(m('line', { x1: 0, x2: plotW, y1: y(val), y2: y(val), stroke: c, 'stroke-opacity': 0.55, 'stroke-dasharray': '3 5' }));
      out.push(m('text', { x: W - 2, y: y(val) + 4, 'text-anchor': 'end', fill: 'var(--muted)' }, g(val)));
    });
    const spanH = (to - from) / HOUR;
    const step = [[2, HOUR / 4], [5, HOUR / 2], [10, HOUR], [20, 2 * HOUR], [40, 4 * HOUR], [100, 12 * HOUR], [Infinity, DAY]].find(([lim]) => spanH <= lim)[1];
    const tz = new Date(from).getTimezoneOffset() * MIN;
    let lastEnd = -Infinity;
    for (let tick = Math.ceil((from - tz) / step) * step + tz; tick <= Math.min(to, win.to); tick += step) {
      const tx = x(tick);
      out.push(m('line', { x1: tx, x2: tx, y1: 0, y2: plotH, stroke: 'var(--line)' }));
      const label = step >= DAY ? dayOnly(tick) : (new Date(tick).getHours() === 0 && step >= HOUR ? dayOnly(tick) : clock(tick));
      const lw = label.length * 7;
      const lx = Math.max(lw / 2, Math.min(plotW - lw / 2, tx));
      if (lx - lw / 2 > lastEnd + 6) { out.push(m('text', { x: lx, y: H - 6, 'text-anchor': 'middle', fill: 'var(--faint)' }, label)); lastEnd = lx + lw / 2; }
    }

    // activity bars along the top
    (h.treatments || []).filter((tr) => tr.duration && tr.t <= win.to && tr.t + tr.duration * MIN >= from).forEach((tr) => {
      const x0 = Math.max(0, x(tr.t)), x1 = Math.max(x0 + 6, Math.min(plotW, x(tr.t + tr.duration * MIN)));
      const a = { vigorous: 0.85, moderate: 0.55 }[tr.intensity] || 0.3;
      out.push(m('rect', { x: x0, y: 4, width: x1 - x0, height: 12, rx: 6, fill: 'var(--in)', 'fill-opacity': a, 'data-tip': 'act:' + tr.id }));
    });

    // alarm episodes: a coloured strip along the top, as long as they lasted
    (h.alarms || []).forEach((al, n) => {
      const end = al.cleared_at || win.to;
      const x0 = Math.max(0, x(al.started_at)), x1 = Math.max(x0 + 4, Math.min(plotW, x(end)));
      if (x1 <= 0 || x0 >= plotW) return;
      out.push(m('rect', { x: x0, y: 20, width: x1 - x0, height: 6, rx: 3, fill: alarmInk(al), 'fill-opacity': al.level === 'urgent' ? 0.9 : 0.55, 'data-tip': 'al:' + n }));
    });

    // the forecast cone from the newest reading
    if (win.live && !agg && st && st.ar2 && st.ar2.cone && st.ar2.cone.length && idx.length) {
      const li = idx[idx.length - 1];
      const pts = [[x(ts[li]), y(vs[li])]].concat(st.ar2.cone.map((c) => [Math.min(plotW, x(c.t)), y(Math.min(hi, c.hi))]))
        .concat(st.ar2.cone.slice().reverse().map((c) => [Math.min(plotW, x(c.t)), y(Math.max(lo, c.lo))]));
      out.push(m('polygon', { points: pts.map((p) => p.join(',')).join(' '), fill: 'var(--in)', 'fill-opacity': 0.12 }));
    }

    // "≈ meter": a faint band and dashed line, from the model
    const model = prefs.meterLine && V.meter && V.meter.ready && V.meter.model;
    const est = (val) => { const sc = Math.max(val, 100); return [val * (1 + model.b) + model.a + model.offset * sc, model.sd * sc]; };
    if (model && idx.length > 1) {
      const up = idx.map((i) => [x(ts[i]), y(Math.min(hi, est(vs[i])[0] + est(vs[i])[1]))]);
      const dn = idx.slice().reverse().map((i) => [x(ts[i]), y(Math.max(lo, est(vs[i])[0] - est(vs[i])[1]))]);
      out.push(m('polygon', { points: up.concat(dn).map((p) => p.join(',')).join(' '), fill: 'var(--est)', 'fill-opacity': 0.1 }));
      out.push(m('polyline', { points: idx.map((i) => x(ts[i]) + ',' + y(Math.min(hi, est(vs[i])[0]))).join(' '), fill: 'none', stroke: 'var(--est)', 'stroke-opacity': 0.75, 'stroke-width': 1.4, 'stroke-dasharray': '5 4' }));
    }

    // the sensor: dots, or for intervals a band from lowest to highest with the mean
    if (agg) {
      if (idx.length) {
        const up = idx.map((i) => [x(ts[i]), y(h.sgv.hi[i])]), dn = idx.slice().reverse().map((i) => [x(ts[i]), y(h.sgv.lo[i])]);
        out.push(m('polygon', { points: up.concat(dn).map((p) => p.join(',')).join(' '), fill: 'var(--in)', 'fill-opacity': 0.18 }));
        out.push(m('polyline', { points: idx.map((i) => x(ts[i]) + ',' + y(vs[i])).join(' '), fill: 'none', stroke: 'var(--in)', 'stroke-width': 1.8 }));
      }
    } else {
      const r = spanH <= 4 ? 3.2 : spanH <= 8 ? 2.4 : spanH <= 14 ? 1.8 : 1.4;
      idx.forEach((i) => out.push(m('circle', { cx: x(ts[i]), cy: y(vs[i]), r, fill: col(vs[i]) })));
      if (win.live && idx.length) {
        const li = idx[idx.length - 1];
        out.push(m('circle', { cx: x(ts[li]), cy: y(vs[li]), r: r + 4, fill: 'var(--surface)' }), m('circle', { cx: x(ts[li]), cy: y(vs[li]), r: r + 2, fill: col(vs[li]) }));
      }
    }
    if (win.live) out.push(m('line', { x1: x(win.to), x2: x(win.to), y1: 0, y2: plotH, stroke: 'var(--faint)', 'stroke-dasharray': '2 3' }));

    // treatment markers in three rows: long-acting, rapid, carbs and notes
    const marks = [];
    (h.treatments || []).filter((tr) => tr.t >= from && tr.t <= win.to && tr.glucose == null && !tr.duration).forEach((tr) => {
      const ins = (tr.insulin || 0) > 0, bas = ins && isBasal(tr);
      const row = bas ? ROW_BASAL : ins ? ROW_RAPID : ROW_CARBS;
      const [ink, bg, glyph] = bas ? ['var(--basal)', 'var(--basal-bg)', t('glyphBasal')] : ins ? ['var(--insulin)', 'var(--insulin-bg)', t('glyphRapid')] : (tr.carbs || 0) > 0 ? ['var(--carbs)', 'var(--carbs-bg)', t('glyphCarbs')] : ['var(--muted)', 'var(--raised)', t('glyphNote')];
      marks.push({ x: x(tr.t), y: plotH - row, ink, bg, glyph, tip: 'tr:' + tr.id });
    });
    (h.suspects || []).filter((q) => q.from >= from && q.from <= win.to).forEach((q, n) => {
      marks.push({ x: x(q.from), y: plotH - (q.kind === 'rise' ? ROW_CARBS : ROW_RAPID), ink: 'var(--high)', bg: 'var(--high-bg)', glyph: '?', tip: 's:' + n, dashed: true });
    });
    marks.forEach((k) => {
      out.push(m('circle', { cx: k.x, cy: k.y, r: 10, fill: k.bg, stroke: k.dashed ? k.ink : 'none', 'stroke-dasharray': k.dashed ? '3 3' : null, 'data-tip': k.tip }));
      out.push(m('text', { x: k.x, y: k.y + 4, 'text-anchor': 'middle', fill: k.ink, style: 'font-family:var(--ui);font-weight:700;pointer-events:none' }, k.glyph));
    });
    // glucometer diamonds, over everything
    meters.forEach((p, n) => {
      const cx = x(p.t), cy = y(p.v), d = (r) => 'M' + cx + ' ' + (cy - r) + 'L' + (cx + r) + ' ' + cy + 'L' + cx + ' ' + (cy + r) + 'L' + (cx - r) + ' ' + cy + 'Z';
      out.push(m('path', { d: d(9), fill: 'var(--surface)', 'data-tip': 'm:' + n }), m('path', { d: d(7), fill: 'var(--text)', style: 'pointer-events:none' }), m('path', { d: d(3.5), fill: col(p.v), style: 'pointer-events:none' }));
    });

    // hover: the nearest reading, or the mark under the pointer
    const tip = s.tip;
    let tipBox = null;
    if (tip && tip.kind === 'reading' && idx.includes(tip.i)) {
      const i = tip.i, px = x(ts[i]), py = y(vs[i]);
      out.push(m('line', { x1: px, x2: px, y1: 0, y2: plotH, stroke: 'var(--muted)' }), m('circle', { cx: px, cy: py, r: 6, fill: col(vs[i]), stroke: 'var(--surface)', 'stroke-width': 3 }));
      const lines = agg
        ? [g(vs[i]) + ' ' + unit(), g(h.sgv.lo[i]) + '–' + g(h.sgv.hi[i]) + ' · ' + clock(ts[i]) + '–' + clock(ts[i] + h.step * MIN)]
        : [g(vs[i]) + ' ' + unit(), (i > 0 && ts[i] - ts[i - 1] <= 10 * MIN ? signed(vs[i] - vs[i - 1]) + ' · ' : '') + dayTime(ts[i])];
      const extra = model ? t('estimate', g(est(vs[i])[0]), g(est(vs[i])[1])) : null;
      tipBox = { x: px, y: py, lines, extra };
    } else if (tip && tip.kind === 'mark') {
      tipBox = Object.assign({ x: tip.x, y: tip.y }, describeMark(tip.id, h, st, meters, idx, ts, vs));
    }

    return m('.chart', {
      onmousemove: (e) => Chart.hover(s, e, { x, plotW, plotH, idx, ts, vs, meters }),
      onmouseleave: () => { s.tip = null; },
      onwheel: (e) => Chart.zoom(e, { from, to: win.to, plotW }),
      onmousedown: (e) => Chart.drag(e, { from, to: win.to, plotW })
    }, [
      m('svg', { viewBox: '0 0 ' + W + ' ' + H, preserveAspectRatio: 'none', role: 'img', 'aria-label': t('lastH', Math.round(spanH)) }, out),
      tipBox && m('.tip', { style: tipPos(tipBox, plotW, plotH) }, [m('b', tipBox.lines[0])].concat(tipBox.lines.slice(1).map((l) => m('span', l))).concat(tipBox.extra ? [m('i', tipBox.extra)] : []))
    ]);
  },
  hover(s, e, c) {
    const rect = e.currentTarget.getBoundingClientRect();
    const mx = e.clientX - rect.left, my = e.clientY - rect.top;
    const target = e.target.getAttribute && e.target.getAttribute('data-tip');
    if (target) { s.tip = { kind: 'mark', id: target, x: mx, y: my }; return; }
    if (!c.idx.length || mx > c.plotW) { s.tip = null; return; }
    let best = c.idx[0];
    c.idx.forEach((i) => { if (Math.abs(c.x(c.ts[i]) - mx) < Math.abs(c.x(c.ts[best]) - mx)) best = i; });
    s.tip = { kind: 'reading', i: best };
  },
  zoom(e, c) {
    e.preventDefault();
    const rect = e.currentTarget.getBoundingClientRect();
    const at = c.from + (e.clientX - rect.left) / c.plotW * (c.to - c.from);
    const f = e.deltaY > 0 ? 1.25 : 0.8, span = Math.max(HOUR, (c.to - c.from) * f);
    const r = (at - c.from) / (c.to - c.from);
    let from = at - r * span, to = from + span;
    const now = Date.now();
    if (to > now) { to = now; from = now - span; }
    clearTimeout(Chart.zt);
    V.period = { from, to };
    Chart.zt = setTimeout(loadHistory, 250);
  },
  drag(e, c) {
    if (e.button !== 0) return;
    const x0 = e.clientX, from0 = c.from, to0 = c.to, w = c.plotW;
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
};

function tipPos(b, plotW, plotH) {
  const left = Math.max(0, Math.min(plotW - 320, b.x - 160));
  const top = b.y > plotH / 2 ? Math.max(0, b.y - 96) : Math.min(plotH - 70, b.y + 18);
  return 'left:' + left + 'px;top:' + top + 'px';
}

function describeMark(id, h, st, meters, idx, ts, vs) {
  const [kind, key] = id.split(':');
  if (kind === 'm') {
    const p = meters[+key];
    const near = idx.filter((i) => Math.abs(ts[i] - p.t) <= 6 * MIN).sort((a, b) => Math.abs(ts[a] - p.t) - Math.abs(ts[b] - p.t))[0];
    return { lines: [t('meterTip', g(p.v) + ' ' + unit()), [clock(p.t), near != null ? t('sensorAt', g(vs[near])) : null, origin(p.tr, p.src)].filter(Boolean).join(' · ')] };
  }
  if (kind === 's') {
    const w = window_(), q = (h.suspects || []).filter((x) => x.from >= w.from && x.from <= w.to)[+key];
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

// a meal logged as a list: "борщ 18 ≈ · хліб 22", "≈" on numbers from a suggestion
function foodText(tr) {
  return (tr.food_items || []).map((f) => [f.name, f.carbs == null ? '' : Math.round(f.carbs) + (f.source === 'mine' || f.source === 'table' || f.source === 'estimate' ? ' ≈' : '')].filter(Boolean).join(' ')).join(' · ');
}

// a suspect carries its span; a meal without insulin, its time
function suspectText(q) {
  if (q.kind === 'bolus') return t('suspectBolus', clock(q.from));
  if (q.kind === 'double') return t('suspectDouble', clock(q.from), clock(q.to));
  return t(q.kind === 'rise' ? 'suspectRise' : 'suspectFall', clock(q.from), clock(q.to), g(q.sgv_from), g(q.sgv_to));
}

function alarmInk(al) {
  if (al.kind === 'low' || al.kind === 'predicted_low') return 'var(--low)';
  if (al.kind === 'stale') return 'var(--muted)';
  return 'var(--high)';
}
function alarmName(al) { return (DL_TEXTS[lang()]['kind_' + al.kind] ? t('kind_' + al.kind) : t('kind_other')); }
function alarmText(al) {
  return al.cleared_at ? t('alarmSpan', alarmName(al), clock(al.started_at), clock(al.cleared_at)) : t('alarmOn', alarmName(al), clock(al.started_at));
}
function alarmLines(al) {
  const more = [al.handled_by && t('alarmBy', al.handled_by), al.manual && t('alarmManual')].filter(Boolean).join(' · ');
  return more ? [alarmText(al), more] : [alarmText(al)];
}

function entryText(tr) {
  if (tr.duration) return t('activity') + ' · ' + t('intensity_' + (tr.intensity || 'moderate')) + ' · ' + tr.duration + ' ' + t('minutes');
  const parts = [];
  if ((tr.insulin || 0) > 0) parts.push((tr.insulin_name || t(isBasal(tr) ? 'basal' : 'rapid')) + ' ' + amount(tr.insulin) + ' ' + t('insulinU'));
  if ((tr.carbs || 0) > 0) parts.push(amount(tr.carbs) + ' ' + t('carbsG'));
  if (!parts.length) return tr.notes || t('note');
  return parts.join(' · ');
}

// ---------- table: clock-aligned intervals ----------

function tableRows() {
  const h = V.history, w = window_();
  if (!h) return null;
  const step = Math.max(V.step, h.step || 5) * MIN;
  const ts = h.sgv.t, vs = h.sgv.v, lo = h.sgv.lo, hi = h.sgv.hi;
  const tz = new Date(w.from).getTimezoneOffset() * MIN;
  const bucket = (tt) => step <= 5 * MIN && !h.step ? tt : Math.floor((tt - tz) / step) * step + tz;
  const rows = new Map();
  const row = (b) => { if (!rows.has(b)) rows.set(b, { t: b, vals: [], los: [], his: [], entries: [], meters: [], suspects: [], alarms: [] }); return rows.get(b); };
  ts.forEach((tt, i) => {
    if (tt < w.from || tt > w.to) return;
    const r = row(bucket(tt));
    r.vals.push(vs[i]); r.los.push(lo ? lo[i] : vs[i]); r.his.push(hi ? hi[i] : vs[i]);
  });
  (h.treatments || []).filter((tr) => tr.t >= w.from && tr.t <= w.to).forEach((tr) => row(bucket(tr.t)).entries.push(tr));
  meterPoints(h).filter((p) => p.t >= w.from && p.t <= w.to && !p.tr).forEach((p) => row(bucket(p.t)).meters.push(p));
  const qs = (h.suspects || []).filter((q) => q.from >= w.from && q.from <= w.to);
  qs.forEach((q) => row(bucket(q.from)).suspects.push(q));
  const als = h.alarms || [];
  als.filter((al) => al.started_at >= w.from && al.started_at <= w.to).forEach((al) => row(bucket(al.started_at)).alarms.push(al));
  const th = (V.state && V.state.thresholds) || { bgTargetBottom: 80, bgTargetTop: 250 };
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
    r.alarm = al ? (al.kind === 'low' || al.kind === 'predicted_low' ? 'alarm-low' : al.kind === 'stale' ? 'alarm-stale' : 'alarm-high') : '';
  });
  for (let i = 0; i < list.length; i++) {
    const r = list[i], older = list.slice(i + 1).find((o) => o.vals.length); // a meter-only row has no sensor value
    r.delta = r.vals.length > 1 ? r.vals[r.vals.length - 1] - r.vals[0] : (older && older.vals.length && r.vals.length ? r.vals[0] - older.vals[older.vals.length - 1] : null);
  }
  if (V.filter === 'entries') list = list.filter((r) => r.has);
  if (V.filter === 'out') list = list.filter((r) => r.out);
  return { list, step, th };
}

const TableCard = {
  view() {
    const w = window_();
    const data = tableRows();
    const minStep = V.history && V.history.step ? V.history.step : 5;
    const seg = (key, opts) => m('.seg.sunk', opts.map(([v, label, disabled]) => m('button', {
      class: V[key] === v || (key === 'step' && Math.max(V.step, minStep) === v) ? 'on' : '',
      disabled, style: disabled ? 'opacity:.4;cursor:default' : '',
      onclick: () => { if (!disabled) { V[key] = v; V.shown = 300; } }
    }, label)));
    return m('section.card', [
      m('.table-head', [
        m('h2', t('data', span(w.from, w.to))),
        seg('filter', [['all', t('all')], ['entries', t('entriesOnly')], ['out', t('outOfRange')]]),
        seg('step', [[5, t('min5'), minStep > 5], [15, t('min15'), minStep > 15], [60, t('hour1')]])
      ]),
      !data ? m('.hint', t('loading')) : !data.list.length ? m('.hint', t('noData')) : m(Table, data)
    ]);
  }
};

const Table = {
  view(v) {
    const { list, step, th } = v.attrs;
    const intervals = step > 5 * MIN || (V.history && V.history.step);
    const col = (val) => val == null ? '' : val < th.bgTargetBottom ? 'lo' : val > th.bgTargetTop ? 'hi' : 'ok';
    const at = (tt) => intervals ? ' · ' + clock(tt) : '';
    const shown = list.slice(0, V.shown);
    const sum = (f) => list.reduce((a, r) => a + r.entries.reduce((b, tr) => b + (f(tr) || 0), 0), 0);
    const all = list.flatMap((r) => r.vals);
    return m('div', [
      m('.scroll', m('table', [
        m('thead', m('tr', [
          m('th', { style: 'padding-left:8px' }, intervals ? t('colInterval') : t('colTime')),
          m('th', intervals ? t('colSensorAgg') : t('colSensor')), m('th', t('colDelta')),
          m('th', { style: 'color:var(--text)' }, t('colMeter')), m('th.c-ins', t('colRapid')), m('th.c-bas', t('colBasal')),
          m('th.c-carb', t('colCarbs')), m('th.c-act', t('colActivity')), m('th', t('colNote'))
        ])),
        m('tbody', shown.map((r) => {
          const ins = r.entries.filter((tr) => (tr.insulin || 0) > 0 && !isBasal(tr)), bas = r.entries.filter((tr) => (tr.insulin || 0) > 0 && isBasal(tr));
          const carbs = r.entries.filter((tr) => (tr.carbs || 0) > 0), act = r.entries.filter((tr) => tr.duration);
          const meters = r.entries.filter((tr) => tr.glucose != null).map((tr) => ({ v: tr.glucose, t: tr.t, tr })).concat(r.meters);
          const notes = r.alarms.map((al) => m('span', { style: 'color:' + alarmInk(al) }, alarmLines(al).join(' · ')))
            .concat(r.suspects.map((q) => m('span.c-sus', suspectText(q))))
            .concat(r.entries.filter((tr) => foodText(tr)).map((tr) => foodText(tr)))
            .concat(r.entries.filter((tr) => tr.notes).map((tr) => tr.notes))
            .concat([...new Set(r.entries.map((tr) => origin(tr)).concat(r.meters.map((p) => origin(null, p.src))))]);
          const list_ = (items) => items.length ? items.join(', ') : '·';
          return m('tr', { key: r.t, class: [r.has ? 'has' : '', r.span ? 'span' : '', r.alarm].join(' ') }, [
            m('td.t', intervals ? clock(r.t) + '–' + clock(r.t + step) : clock(r.t)),
            m('td.v', r.avg == null ? '' : [m('b.' + col(r.avg), g(r.avg)), intervals && m('small', [m('span.' + (r.min < th.bgTargetBottom ? 'lo' : ''), g(r.min)), '–', m('span.' + (r.max > th.bgTargetTop ? 'hi' : ''), g(r.max))])]),
            m('td.d', r.delta == null ? '' : signed(r.delta)),
            m('td.m', list_(meters.map((p) => g(p.v) + at(p.t)))),
            m('td.m.c-ins', list_(ins.map((tr) => amount(tr.insulin) + ' ' + (tr.insulin_name || '') + at(tr.t)))),
            m('td.m.c-bas', list_(bas.map((tr) => amount(tr.insulin) + ' ' + (tr.insulin_name || '') + at(tr.t)))),
            m('td.m.c-carb', list_(carbs.map((tr) => amount(tr.carbs) + ' ' + t('carbsG') + at(tr.t)))),
            m('td.m.c-act', list_(act.map((tr) => tr.duration + ' ' + t('minutes') + at(tr.t)))),
            m('td', notes.length ? notes.reduce((acc, n, i) => acc.concat(i ? [' · ', n] : [n]), []) : '')
          ]);
        })),
        m('tfoot', m('tr', [
          m('td', { style: 'padding-left:8px;color:var(--muted)' }, t('total')),
          m('td', all.length ? t('avg', g(all.reduce((a, b) => a + b, 0) / all.length)) + ' · ' + g(Math.min(...list.filter((r) => r.min != null).map((r) => r.min))) + '–' + g(Math.max(...list.filter((r) => r.max != null).map((r) => r.max))) : ''),
          m('td', ''),
          m('td', String(list.reduce((a, r) => a + r.meters.length + r.entries.filter((tr) => tr.glucose != null).length, 0))),
          m('td.c-ins', amount(sum((tr) => (tr.insulin || 0) > 0 && !isBasal(tr) ? tr.insulin : 0)) + ' ' + t('insulinU')),
          m('td.c-bas', amount(sum((tr) => (tr.insulin || 0) > 0 && isBasal(tr) ? tr.insulin : 0)) + ' ' + t('insulinU')),
          m('td.c-carb', amount(sum((tr) => tr.carbs)) + ' ' + t('carbsG')),
          m('td.c-act', sum((tr) => tr.duration) + ' ' + t('minutes')),
          m('td', '')
        ]))
      ])),
      list.length > V.shown && m('button.ghost.more', { onclick: () => { V.shown += 300; } }, t('showMore', Math.min(300, list.length - V.shown)))
    ]);
  }
};

// ---------- start ----------

document.documentElement.lang = lang();
m.mount(document.getElementById('app'), { view: () => session ? m(Viewer) : m(Login) });
if (session) Viewer.start(); else Login.start();
