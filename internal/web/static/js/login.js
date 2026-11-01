// Logging in by QR: the browser asks for a code, a parent scans it in the app
// and allows it, the browser polls until it gets its session.

import { api, signIn } from './api.js';
import { t } from './format.js';
import { LangPicker } from './widgets.js';

export const Login = {
  code: null, // {id, secret, expires_at, scanned}
  note: null,
  timer: null,
  onSignedIn: () => {},

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
      // 404: the server no longer knows this code (it restarted): a new one at once
      r = { state: e.status === 404 || Date.now() > c.expires_at ? 'expired' : 'pending' };
    }
    if (r.state === 'approved') {
      signIn({ token: r.token, expires: r.expires_at, by: r.by });
      Login.code = null;
      Login.note = null;
      Login.onSignedIn();
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
