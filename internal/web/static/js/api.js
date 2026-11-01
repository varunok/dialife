// The server: the session token, requests and the live stream.

import { SESSION_KEY, load, save } from './prefs.js';

/** {token, expires, by} once a parent allowed this browser; null before. */
export let session = load(SESSION_KEY);
if (session && session.expires < Date.now()) { session = null; save(SESSION_KEY, null); }

let onSignedOut = () => {};
/** What to show once the session is gone: the login, set by main. */
export function whenSignedOut(fn) { onSignedOut = fn; }

export function signIn(s) {
  session = s;
  save(SESSION_KEY, s);
}

export async function api(path, opts = {}) {
  const headers = Object.assign({ 'Content-Type': 'application/json' }, opts.headers || {});
  if (session) headers.Authorization = 'Bearer ' + session.token;
  const r = await fetch(path, Object.assign({}, opts, { headers }));
  if (r.status === 401 && session) { logout(false); throw new Error('unauthorized'); }
  if (!r.ok) throw Object.assign(new Error('http ' + r.status), { status: r.status });
  return r.status === 204 ? null : r.json();
}

export function logout(tellServer) {
  if (tellServer && session) api('/api/web/logout', { method: 'POST' }).catch(() => {});
  session = null;
  save(SESSION_KEY, null);
  stopStream();
  onSignedOut();
}

// ---------- the live stream ----------

let streamAbort = null;

export function stopStream() {
  if (streamAbort) streamAbort.abort();
  streamAbort = null;
}

/**
 * SSE over fetch, so the token goes in a header and never in a URL. Reconnects
 * with a growing pause; onLink tells 'live' or 'offline', onEvent each event.
 */
export async function runStream(onEvent, onLink) {
  stopStream();
  const ctl = new AbortController();
  streamAbort = ctl;
  let backoff = 2000;
  while (!ctl.signal.aborted && session) {
    try {
      const r = await fetch('/api/stream', { headers: { Authorization: 'Bearer ' + session.token, Accept: 'text/event-stream' }, signal: ctl.signal });
      if (r.status === 401) { logout(false); return; }
      if (!r.ok || !r.body) throw new Error('stream ' + r.status);
      onLink('live');
      backoff = 2000;
      await readEvents(r.body, onEvent);
    } catch (e) {
      if (ctl.signal.aborted) return;
    }
    onLink('offline');
    await new Promise((res) => setTimeout(res, backoff));
    backoff = Math.min(backoff * 2, 60000);
  }
}

async function readEvents(body, onEvent) {
  const reader = body.getReader();
  const dec = new TextDecoder();
  let buf = '';
  for (;;) {
    const { value, done } = await reader.read();
    if (done) return;
    buf += dec.decode(value, { stream: true });
    let i;
    while ((i = buf.indexOf('\n\n')) >= 0) {
      const ev = parseEvent(buf.slice(0, i));
      buf = buf.slice(i + 2);
      if (ev.data) onEvent(ev.type, ev.data);
    }
  }
}

/** One SSE block: its event type and its data lines joined. */
export function parseEvent(block) {
  let type = 'message', data = '';
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) type = line.slice(6).trim();
    else if (line.startsWith('data:')) data += line.slice(5).trim();
  }
  return { type, data };
}
