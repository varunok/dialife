// DiaLife web viewer: read-only. It logs in by a QR code a parent scans in the
// app, then shows the current reading, the chart and the table, live.

import { session, whenSignedOut } from './api.js';
import { ChartCard } from './chart.js';
import { lang } from './format.js';
import { InfoDialog } from './info.js';
import { Login } from './login.js';
import { startViewer } from './model.js';
import { Now, Top } from './now.js';
import { TableCard } from './table.js';

const Viewer = {
  view() {
    return m('.page', [m(Top), m('.grid', [m(Now), m(ChartCard)]), m(TableCard), m(InfoDialog)]);
  }
};

Login.onSignedIn = startViewer;
whenSignedOut(() => { Login.start(); m.redraw(); });
setInterval(() => { if (!session) m.redraw(); }, 1000); // the login code's countdown

document.documentElement.lang = lang();
m.mount(document.getElementById('app'), { view: () => session ? m(Viewer) : m(Login) });
if (session) startViewer(); else Login.start();
