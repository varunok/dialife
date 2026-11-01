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

/** The trend arrow, turned by the 5-minute change in mg/dL. */
export function arrowIcon(d) {
  const deg = d == null ? 0 : d >= 10 ? -90 : d >= 5 ? -45 : d <= -10 ? 90 : d <= -5 ? 45 : 0;
  return m('svg', { viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': 2.4, 'stroke-linecap': 'round', 'stroke-linejoin': 'round', style: 'transform: rotate(' + deg + 'deg)' },
    [m('path', { d: 'M5 12h14' }), m('path', { d: 'M13 6l6 6-6 6' })]);
}

export function calIcon() {
  return m('svg', { width: 16, height: 16, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': 2, 'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': 'true' },
    [m('rect', { x: 3, y: 5, width: 18, height: 16, rx: 2 }), m('path', { d: 'M3 10h18M8 3v4M16 3v4' })]);
}
