// The ⓘ next to a feature: what it is based on and what is our own, from our
// observations. The texts are docs/METHODS.md in short, in methods.json, the
// same file the phones read; the full text with its sources is linked.

import { lang, t } from './format.js';

let methods = null, loading = null, open = null;

function load() {
  if (methods || loading) return;
  loading = fetch('methods.json').then((r) => r.json())
    .then((d) => { methods = d; m.redraw(); })
    .catch(() => { loading = null; m.redraw(); });
}

/** A feature's section in the viewer's language (English when missing); null when unknown. */
export function infoSection(d, id, lg) {
  const s = d && d.sections && d.sections[id];
  return s ? (s[lg] || s.en || null) : null;
}

/** The ⓘ button for the section id. */
export function info(id) {
  return m('button.info', {
    type: 'button', 'aria-label': t('infoAbout'),
    onclick: (e) => { e.stopPropagation(); open = id; load(); }
  }, 'ⓘ');
}

const list = (items) => items.length ? m('ul', items.map((x) => m('li', x))) : m('p.none', t('infoNone'));

/** The open ⓘ, over the page; a click outside or Escape closes it. */
export const InfoDialog = {
  oncreate() { document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && open) { open = null; m.redraw(); } }); },
  view() {
    if (!open) return null;
    const s = infoSection(methods, open, lang());
    const close = () => { open = null; };
    return m('.info-back', { onclick: close }, m('.info-dialog', { role: 'dialog', 'aria-modal': 'true', onclick: (e) => e.stopPropagation() }, s ? [
      m('h2', s.title),
      m('p.short', s.short),
      m('h3', t('infoBased')), list(s.based),
      m('h3', t('infoOurs')), list(s.ours),
      m('a', { href: methods.doc + '#' + open, target: '_blank', rel: 'noopener' }, t('infoMore')),
      m('button.primary', { type: 'button', onclick: close }, t('infoClose'))
    ] : [m('p', loading ? t('loading') : t('infoUnavailable')), m('button.primary', { type: 'button', onclick: close }, t('infoClose'))]));
  }
};
