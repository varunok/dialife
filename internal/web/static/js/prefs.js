// What this browser remembers: the session a parent allowed and the viewer's
// own choices. Private mode or blocked storage: nothing is kept, nothing breaks.

export const SESSION_KEY = 'dialife.web'; // {token, expires, by} after a parent allowed this browser
const PREFS_KEY = 'dialife.web.prefs'; // {lang, units, meterLine}

export function load(key) {
  try { return JSON.parse(localStorage.getItem(key) || 'null'); } catch (e) { return null; }
}

export function save(key, v) {
  try { v == null ? localStorage.removeItem(key) : localStorage.setItem(key, JSON.stringify(v)); } catch (e) { /* private mode */ }
}

export const prefs = Object.assign({ lang: null, units: 'mgdl', meterLine: false }, load(PREFS_KEY) || {});

export function setPref(k, v) {
  prefs[k] = v;
  save(PREFS_KEY, prefs);
}
