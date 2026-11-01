// The language, the texts and how times and numbers read in it.

import { prefs } from './prefs.js';
import { TEXTS } from './texts.js';

export const MIN = 60000, HOUR = 60 * MIN, DAY = 24 * HOUR;

export function lang() {
  if (prefs.lang && TEXTS[prefs.lang]) return prefs.lang;
  const nav = (navigator.languages || [navigator.language || 'en']).map((l) => l.slice(0, 2));
  return nav.find((l) => TEXTS[l]) || 'en';
}

export function languages() { return Object.keys(TEXTS).map((k) => [k, TEXTS[k].langName]); }

export function hasText(key) { return (TEXTS[lang()] || TEXTS.en)[key] != null; }

export function t(key, ...args) {
  const s = (TEXTS[lang()] || TEXTS.en)[key] || TEXTS.en[key] || key;
  return s.replace(/\{(\d)\}/g, (_, i) => args[+i] == null ? '' : args[+i]);
}

function locale() { return { uk: 'uk-UA', en: 'en-GB', es: 'es-ES' }[lang()]; }

export function clock(ms) { return new Date(ms).toLocaleTimeString(locale(), { hour: '2-digit', minute: '2-digit' }); }
export function dayTime(ms) { return new Date(ms).toLocaleString(locale(), { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }); }
export function dayOnly(ms) { return new Date(ms).toLocaleDateString(locale(), { day: 'numeric', month: 'short' }); }
function sameDay(a, b) { return new Date(a).toDateString() === new Date(b).toDateString(); }
export function span(from, to) { return sameDay(from, to) ? dayOnly(from) + ' ' + clock(from) + '–' + clock(to) : dayTime(from) + ' — ' + dayTime(to); }

// one decimal with the language's separator
function decimal(v) { return v.toFixed(1).replace('.', lang() === 'en' ? '.' : ','); }

/** A glucose value in mg/dL, in the viewer's units. */
export function g(mg) { return prefs.units === 'mmol' ? decimal(mg / 18) : String(Math.round(mg)); }
export function unit() { return prefs.units === 'mmol' ? 'mmol/L' : 'mg/dL'; }
/** A change in mg/dL with its sign: +4, −2, ±0. */
export function signed(mg) {
  const s = mg > 0 ? '+' : mg < 0 ? '−' : '±';
  return s + (prefs.units === 'mmol' ? decimal(Math.abs(mg) / 18) : Math.abs(Math.round(mg)));
}
/** Units of insulin or grams: whole, or one decimal. */
export function amount(v) { return Number.isInteger(v) ? String(v) : decimal(v); }
