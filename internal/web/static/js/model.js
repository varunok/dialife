// What the viewer shows and where it comes from: the state, the history for
// the chosen period, the meter model and time in range, kept fresh by the stream.

import { api, logout, runStream } from './api.js';
import { HOUR, MIN } from './format.js';

export const V = {
  state: null, history: null, meter: null, tir: null,
  link: 'connecting', // live | offline
  period: { last: 6 * HOUR }, // or {from, to}
  loading: false, error: null,
  popOpen: false,
  filter: 'all', step: 5, shown: 300
};

/** The period on screen; live when it reaches now, and moves on with it. */
export function timeWindow() {
  if (V.period.last) { const to = Date.now(); return { from: to - V.period.last, to, live: true }; }
  return { from: V.period.from, to: V.period.to, live: V.period.to >= Date.now() - 2 * MIN };
}

let fetchSeq = 0;
export async function loadHistory() {
  const w = timeWindow();
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

async function loadMeter() {
  try { V.meter = await api('/api/meter'); } catch (e) { V.meter = null; }
  m.redraw();
}

async function loadTir() {
  try { const s = await api('/api/stats?days=1'); V.tir = s.zones ? s.zones.in_range : null; } catch (e) { V.tir = null; }
  m.redraw();
}

export function setPeriod(p) {
  V.period = p;
  V.popOpen = false;
  V.shown = 300;
  loadHistory();
}

const ALARM_EVENTS = ['alarm', 'handled', 'checkin', 'checkin_ok', 'help', 'coming', 'clear'];

function onEvent(type, data) {
  let d;
  try { d = JSON.parse(data); } catch (e) { return; }
  if (type === 'logout') { logout(false); return; } // closed from a parent's phone, or expired
  if (type === 'state' || type === 'bg') {
    V.state = Object.assign({}, d, { display_units: (V.state || {}).display_units });
    if (timeWindow().live) loadHistory();
  } else if (type === 'history' || type === 'treatment') {
    loadHistory(); loadMeter();
  } else if (ALARM_EVENTS.includes(type)) {
    loadState(); loadHistory(); // the episode shows on the chart and in the table
  } else if (type === 'uploader' && V.state) {
    V.state = Object.assign({}, V.state, { uploader: d });
  }
  m.redraw();
}

let tick = null, tirTick = null;

/** Everything at once, then the stream. */
export function startViewer() {
  loadState(); loadHistory(); loadMeter(); loadTir();
  runStream(onEvent, (link) => { V.link = link; m.redraw(); });
  clearInterval(tick);
  // minutes-ago and the "last N hours" window move on without new readings
  tick = setInterval(() => { if (V.period.last) m.redraw(); }, 30000);
  clearInterval(tirTick);
  tirTick = setInterval(loadTir, 15 * MIN);
}
