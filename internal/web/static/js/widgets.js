// Small pieces used on more than one screen: the language picker and icons.

import { setPref } from './prefs.js';
import { lang, languages } from './format.js';

export const LangPicker = {
  view(v) {
    return m('select.ghost', {
      class: v.attrs.cls, 'aria-label': 'Language',
      value: lang(), onchange: (e) => { setPref('lang', e.target.value); document.documentElement.lang = e.target.value; }
    }, languages().map(([k, name]) => m('option', { value: k }, name)));
  }
};

// the server's trend (Dexcom's rate bands, as the app shows them): the turn, and a double arrow when fast
const TRENDS = {
  DoubleUp: { deg: -90, double: true }, SingleUp: { deg: -90 }, FortyFiveUp: { deg: -45 },
  Flat: { deg: 0 },
  FortyFiveDown: { deg: 45 }, SingleDown: { deg: 90 }, DoubleDown: { deg: 90, double: true }
};

/** How the arrow for the server's direction is drawn; null: no arrow (unknown, an error code). */
export function trendOf(direction) {
  return TRENDS[direction] || null;
}

/** The trend arrow for the server's direction; an empty circle when it has none. */
export function arrowIcon(direction) {
  const tr = trendOf(direction);
  if (!tr) return null;
  const arrow = (dx) => [m('path', { d: 'M' + (5 + dx) + ' 12h14' }), m('path', { d: 'M' + (13 + dx) + ' 6l6 6-6 6' })];
  return m('svg', { viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': 2.4, 'stroke-linecap': 'round', 'stroke-linejoin': 'round', style: 'transform: rotate(' + tr.deg + 'deg)' },
    tr.double ? [...arrow(-2.5), ...arrow(2.5)] : arrow(0));
}

export function calIcon() {
  return m('svg', { width: 16, height: 16, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': 2, 'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': 'true' },
    [m('rect', { x: 3, y: 5, width: 18, height: 16, rx: 2 }), m('path', { d: 'M3 10h18M8 3v4M16 3v4' })]);
}
