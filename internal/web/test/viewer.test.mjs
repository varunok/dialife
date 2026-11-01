// Tests of the web viewer's pure parts: run with `node --test internal/web/test/`.
// The page itself needs a browser; these cover what it computes.
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';

import { prefs } from '../static/js/prefs.js';
import { MIN, amount, g, signed, t, unit } from '../static/js/format.js';
import { alarmLines, alarmName, entryText, foodText, isBasal, meterPoints, origin } from '../static/js/entries.js';
import { tableRows } from '../static/js/rows.js';
import { parseEvent } from '../static/js/api.js';

process.env.TZ = 'UTC';
const TH = { bgTargetBottom: 80, bgTargetTop: 250 };
const T0 = Date.UTC(2026, 9, 31, 12, 0); // a whole hour

beforeEach(() => { prefs.lang = 'en'; prefs.units = 'mgdl'; });

test('glucose in mg/dL and mmol/L, with the language decimal separator', () => {
  assert.equal(g(126.4), '126');
  assert.equal(unit(), 'mg/dL');
  prefs.units = 'mmol';
  assert.equal(g(126), '7.0');
  assert.equal(unit(), 'mmol/L');
  prefs.lang = 'uk';
  assert.equal(g(126), '7,0');
});

test('a change carries its sign', () => {
  assert.equal(signed(4), '+4');
  assert.equal(signed(-2.4), '−2');
  assert.equal(signed(0), '±0');
  prefs.units = 'mmol';
  assert.equal(signed(-18), '−1.0');
});

test('amounts are whole or one decimal', () => {
  assert.equal(amount(3), '3');
  assert.equal(amount(2.25), '2.3'); // toFixed rounds half-even on the binary value
  prefs.lang = 'es';
  assert.equal(amount(0.5), '0,5');
});

test('texts fill their places; an unknown key shows itself', () => {
  assert.equal(t('ago', 7), '7 min ago');
  prefs.lang = 'uk';
  assert.equal(t('ago', 7), '7 хв тому');
  assert.equal(t('no_such_key'), 'no_such_key');
  // a language there are no texts for: as if none was chosen (the browser's)
  prefs.lang = null;
  const browsers = t('justNow');
  prefs.lang = 'xx';
  assert.equal(t('justNow'), browsers);
});

test('an alarm of an unknown kind reads as an alarm, not as its key', () => {
  assert.equal(alarmName({ kind: 'low' }), 'Low');
  assert.equal(alarmName({ kind: 'something_new' }), 'Alarm');
});

test('alarm lines: open or closed, who handled it, ended by hand', () => {
  const at = Date.UTC(2026, 9, 31, 10, 5);
  assert.deepEqual(alarmLines({ kind: 'high', started_at: at }), ['Alarm · High · since 10:05, still on']);
  assert.deepEqual(alarmLines({ kind: 'low', started_at: at, cleared_at: at + 20 * MIN, handled_by: 'Mum', manual: true }),
    ['Alarm · Low · 10:05–10:25', 'handled by Mum · ended by hand']);
});

test('long-acting insulin by its type, else by the Nightscout event', () => {
  assert.equal(isBasal({ insulin_type: 'basal' }), true);
  assert.equal(isBasal({ insulin_type: 'rapid', event_type: 'Basal Injection' }), false);
  assert.equal(isBasal({ event_type: 'Basal Injection' }), true);
  assert.equal(isBasal({ event_type: 'Meal Bolus' }), false);
});

test('an entry reads as insulin and carbs, activity, or its note', () => {
  assert.equal(entryText({ insulin: 2, insulin_name: 'Humalog', carbs: 45 }), 'Humalog 2 u · 45 g');
  assert.equal(entryText({ insulin: 1.5, insulin_type: 'basal' }), 'Long-acting 1.5 u');
  assert.equal(entryText({ notes: 'sick' }), 'sick');
  assert.match(entryText({ duration: 30, intensity: 'vigorous' }), /30 min$/);
});

test('dishes: suggested numbers marked ≈, typed ones not', () => {
  const tr = { food_items: [{ name: 'borscht', carbs: 18.4, source: 'table' }, { name: 'bread', carbs: 22, source: 'manual' }, { name: 'tea' }] };
  assert.equal(foodText(tr), 'borscht 18 ≈ · bread 22 · tea');
  assert.equal(foodText({}), '');
});

test('who logged it: a person, xDrip+ by hand, or a device on its own', () => {
  assert.equal(origin({ entered_by: 'Dad' }), 'Dad · typed');
  assert.equal(origin({ entered_by: 'xdrip' }), 'xDrip · typed');
  assert.equal(origin(null, 'manual'), 'xDrip · typed');
  assert.equal(origin(null, 'Contour Next One'), 'Contour · automatic');
});

test('meter points: typed checks and xDrip+ mbg together', () => {
  const pts = meterPoints({ treatments: [{ t: 1, glucose: 140 }, { t: 2, carbs: 10 }], mbg: { t: [3], v: [150], d: ['Contour'] } });
  assert.deepEqual(pts.map((p) => [p.t, p.v, p.src]), [[1, 140, undefined], [3, 150, 'Contour']]);
});

// ---------- the table's rows ----------

function history(values, extra = {}) {
  return Object.assign({ sgv: { t: values.map((_, i) => T0 + i * 5 * MIN), v: values } }, extra);
}
const period = (n) => ({ from: T0, to: T0 + n * 5 * MIN });

test('5-minute rows: one per reading, newest first, delta from the one before', () => {
  const { list } = tableRows(history([100, 104, 101]), TH, period(3), 5, 'all');
  assert.deepEqual(list.map((r) => [r.t - T0, r.avg, r.delta]), [[10 * MIN, 101, -3], [5 * MIN, 104, 4], [0, 100, null]]);
});

test('hour rows: mean, lowest and highest, the change within the hour', () => {
  const vals = Array.from({ length: 24 }, (_, i) => 100 + i * 10); // 100 … 330 over two hours
  const { list, step } = tableRows(history(vals), TH, period(24), 60, 'all');
  assert.equal(step, 60 * MIN);
  assert.equal(list.length, 2);
  const [late, early] = list;
  assert.equal(early.t, T0);
  assert.equal(early.min, 100);
  assert.equal(early.max, 210);
  assert.equal(early.delta, 110);
  assert.equal(early.out, false);
  assert.equal(late.out, true);
});

test('the server\'s coarser step wins over a finer choice', () => {
  const h = history([100, 110], { step: 15 });
  h.sgv.lo = [90, 100]; h.sgv.hi = [120, 130];
  const { list, step } = tableRows(h, TH, period(2), 5, 'all');
  assert.equal(step, 15 * MIN);
  assert.equal(list[0].min, 90);
  assert.equal(list[0].max, 130);
});

test('a meter-only row has no delta; the next row skips it for its delta', () => {
  const h = history([100, 110]);
  h.sgv.t = [T0, T0 + 10 * MIN];
  h.mbg = { t: [T0 + 5 * MIN], v: [150] };
  const { list } = tableRows(h, TH, period(2), 5, 'all');
  assert.deepEqual(list.map((r) => [r.t - T0, r.delta]), [[10 * MIN, 10], [5 * MIN, null], [0, null]]);
  assert.equal(list[1].meters.length, 1);
  assert.equal(list[1].has, true);
});

test('filters: rows with entries, rows out of range', () => {
  const h = history([60, 120, 300], { treatments: [{ t: T0 + 5 * MIN, carbs: 30 }] });
  assert.deepEqual(tableRows(h, TH, period(3), 5, 'entries').list.map((r) => r.avg), [120]);
  assert.deepEqual(tableRows(h, TH, period(3), 5, 'out').list.map((r) => r.avg), [300, 60]);
});

test('an alarm marks every row it covers, coloured by its kind', () => {
  const h = history([60, 60, 90, 100], { alarms: [{ kind: 'low', started_at: T0, cleared_at: T0 + 7 * MIN }] });
  const { list } = tableRows(h, TH, period(4), 5, 'all');
  assert.deepEqual(list.map((r) => r.alarm), ['', '', 'alarm-low', 'alarm-low']);
  assert.equal(list[3].alarms.length, 1); // listed once, in the row it began
});

test('nothing outside the period', () => {
  const h = history([100, 110, 120], { treatments: [{ t: T0 - MIN, carbs: 5 }] });
  const { list } = tableRows(h, TH, { from: T0 + 5 * MIN, to: T0 + 10 * MIN }, 5, 'all');
  assert.deepEqual(list.map((r) => r.avg), [120, 110]);
});

// ---------- the stream ----------

test('an SSE block: its type and data; a plain message without a type', () => {
  assert.deepEqual(parseEvent('event: bg\ndata: {"sgv":120}'), { type: 'bg', data: '{"sgv":120}' });
  assert.deepEqual(parseEvent('data: {"a":1}'), { type: 'message', data: '{"a":1}' });
  assert.deepEqual(parseEvent(': keep-alive'), { type: 'message', data: '' });
});

// ---------- the modules fit together ----------

test('every module loads and finds what it imports', async () => {
  const { readdirSync } = await import('node:fs');
  const dir = new URL('../static/js/', import.meta.url);
  // main.js mounts the page at once: it needs a browser
  for (const f of readdirSync(dir).filter((f) => f.endsWith('.js') && f !== 'main.js')) {
    await import(new URL(f, dir));
  }
});
