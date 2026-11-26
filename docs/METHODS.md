# How DiaLife computes what it shows

> **Not a medical device.** See the [disclaimer](../DISCLAIMER.md). Nothing here is
> treatment advice.

One of DiaLife's main aims is that what it shows and how it alarms follow the
recommendations of paediatric diabetes organisations and published research; those
recommendations are gathered in [docs/GUIDELINES.md](GUIDELINES.md).

Every number, arrow, card and alarm DiaLife works out is described here, in three parts:

- **Based on** — what comes from a published source: a guideline, a study, a clinical
  consensus, a manufacturer's documentation, or an open-source project's code. Each source is
  linked in [References](#references) or in [GUIDELINES.md](GUIDELINES.md).
- **Our own: from our observations** — what no source covers, so we chose it from what we
  saw in real use: thresholds, windows, rules. Each says why. Not validated by any study;
  open to change.
- **Limits** — what the feature does not see or cannot know.

All values are in mg/dL; the apps convert for mmol/L.

## Contents

- [Change in 5 min](#change-5min)
- [Trend arrow](#trend-arrow)
- [In 30 min](#forecast-30)
- [Low and high alarms](#alarms)
- [Predicted low and high](#predicted-alarms)
- [An alarm being handled](#alarm-episodes)
- [Recheck after a low](#recheck)
- [≈ meter](#meter-estimate)
- [Sensor delay](#sensor-delay)
- [Calibration advice](#calibration)
- [After a shower](#shower)
- [No-sensor mode](#no-sensor)
- [Statistics](#stats)
- [Insulin and carbs on board](#on-board)
- [Double insulin guard](#double-insulin)
- [Meal dose](#meal-dose)
- [One meal, several entries](#meal-group)
- [When to eat](#wait)
- [Carb suggestions](#carb-suggestions)
- [Did you forget to log?](#questions)

---

<a id="change-5min"></a>
## Change in 5 min

**In short.** How much the sensor value changed over the last 5 minutes, worked out the way
Nightscout does it.

**Based on**
- Nightscout's `bgnow` plugin [1], ported line by line: readings are grouped into 5-minute
  buckets (offset so the newest reading sits mid-bucket), the change is the difference of
  the two newest bucket means; a gap longer than 9 minutes is scaled back to 5 minutes and
  marked as interpolated.

**Our own: from our observations**
- Nothing.

**Limits**
- One reading's noise shows directly in this number.

---

<a id="trend-arrow"></a>
## Trend arrow

**In short.** Which way and how fast glucose is moving, with the same bands as Dexcom's
arrows, computed by the server from the readings themselves.

**Based on**
- Dexcom's arrow bands [2]: → steady, up to 1 mg/dL each minute; ↗ ↘ 1–2; ↑ ↓ 2–3;
  ⇈ ⇊ more than 3.
- The 5-minute buckets of Nightscout's `bgnow` [1].

**Our own: from our observations**
- The rate is taken over up to 15 minutes: the newest bucket mean against the oldest of
  four, each placed at its readings' mean time. One stray reading moves it less than a
  5-minute change would.
- We compute the arrow instead of taking the uploader's: xDrip+ in Dexcom native mode
  uploads "Flat" for every reading (seen at +12 mg/dL in 5 min). The uploader's direction
  is used only when there are too few readings.
- Every screen (app, widget, notification, web) shows this one arrow from the server.

**Limits**
- A sensor reading pressed while lying on it, or a stray value of 15 mg/dL or more at the
  start or the end of the 15 minutes, can turn the arrow.

---

<a id="forecast-30"></a>
## In 30 min

**In short.** Where the sensor value is likely to be in 30 minutes if it keeps moving as in
the last half hour, slowing down, with how often such forecasts came true over the past week.

**Based on**
- Least-squares slope (standard linear regression) over the last 30 minutes.

**Our own: from our observations**
- The trend is damped: value + slope × minutes × 0.4. Tried on real readings, the 30-minute
  error was 12.0 mg/dL on average, against 13.8 for Nightscout's AR2 and 12.8 for "no change".
- Accuracy is measured once an hour over the last week: from every reading, what the forecast
  would have said, against the reading 30 minutes later. A forecast "came true" when it was
  within 15 mg/dL or 15%, whichever is larger. The band around the line is the error 80% of
  forecasts stayed within (25 mg/dL before anything is measured).
- Quiet hours and the 3 hours after food are scored apart.

**Limits**
- It does not see food or insulin; within 2 hours of either it says so.
- It is not what the alarms use (see [Predicted low and high](#predicted-alarms)).

---

<a id="alarms"></a>
## Low and high alarms

**In short.** An alarm when the sensor value is below the low or above the high threshold;
a high must last a while first, a low never waits.

**Based on**
- Default thresholds 70 / 180 / 250 mg/dL are the ranges of the international consensus on
  time in range [3] and of ISPAD's targets for children; 70 is ISPAD's "alert value that
  requires attention" and the ADA's level-1 hypoglycaemia; the default urgent low, 55, sits
  next to level 2 (< 54) ([GUIDELINES](GUIDELINES.md#hypoglycaemia-levels-alarms-treatment)).
- Glucose watched overnight, and shared with caregivers, as ISPAD recommends
  ([GUIDELINES](GUIDELINES.md#sharing-glucose-with-caregivers)).
- "No data" after 15 minutes (warning) and 30 minutes (urgent), as Nightscout's defaults [1].

**Our own: from our observations**
- Every threshold, and how long a high must last, is the family's setting.
- An alarm ends only after two readings in a row are past the threshold by 5 mg/dL
  (hysteresis), so one lucky value does not call off an episode.
- Alarms judge only readings at most 10 minutes old; with older data a live alarm stays as it was.
- In the [no-sensor mode](#no-sensor) nothing alarms.

**Limits**
- The alarms follow the sensor, not the blood: a sensor reading low by mistake alarms too.

---

<a id="predicted-alarms"></a>
## Predicted low and high

**In short.** An early alarm when Nightscout's AR2 forecast says glucose is heading out of range.

**Based on**
- Nightscout's AR2 plugin [1], ported with its constants: a second-order autoregressive model
  on log(glucose / 140), coefficients −0.723 and 1.716, six 5-minute steps; the warning and
  urgent levels from its average-loss score (0.05 / 0.10), the side from the value in 20 minutes.

**Our own: from our observations**
- A predicted alarm is not raised while the same side already alarms.

**Limits**
- AR2 sees only the last two buckets; food and insulin are invisible to it.

---

<a id="alarm-episodes"></a>
## An alarm being handled

**In short.** One parent taps "I'm on it"; the others are not woken unless it gets worse,
nobody answers a check-in, or it lasts too long.

**Based on**
- Nothing published: this is how the family shares the night.

**Our own: from our observations**
- Untaken alarms ring again: urgent every 5 minutes, warnings every 15.
- A taken alarm wakes everyone when the value moves 10 mg/dL past the value at take-over,
  when a check-in (first after 20 minutes for urgent, then every 15) is not answered in
  5 minutes, or when an urgent alarm is still taken after 45 minutes. Every one of these
  is the family's setting.

**Limits**
- It relies on the phones getting the push; see the setup notes in the README.

---

<a id="recheck"></a>
## Recheck after a low

**In short.** Carbs logged while glucose is low: 15 minutes later every phone is asked to
measure again with the glucometer; a check still below 70 says so and asks to treat again.

**Based on**
- After treating a low, recheck glucose in 15 minutes and treat again if it is still low:
  ADA Standards of Care 2026, 6.15 (grade B); ISPAD 2022, hypoglycaemia (grade E); NICE NG18,
  1.2.88 ([GUIDELINES](GUIDELINES.md#hypoglycaemia-levels-alarms-treatment)).
- Below 70 mg/dL (3.9 mmol/L) is hypoglycaemia in all three.

**Our own: from our observations**
- "Low" when the carbs are logged: a live low alarm by the family's thresholds, or the newest
  sensor reading of the 10 minutes before, or glucometer check of the 15 minutes before,
  below 70. Carbs logged with a time more than 30 minutes back are not counted.
- A check counts as the recheck from 5 minutes after the carbs: an earlier one is the low
  itself, entered late. 70 or above ends it for every phone; below 70 shows "still low".
- New carbs replace it, counted from them. Closed by hand on any phone, closed for all; gone
  by itself 2 hours after it was due.
- A push to every phone when it is due and when the recheck is still low; no siren.

**Limits**
- DiaLife does not say how much to give: that needs the child's weight (ISPAD: about
  0.3 g/kg) and the family's plan.
- The alarms are untouched: it adds a reminder, it silences nothing.

---

<a id="meter-estimate"></a>
## ≈ meter

**In short.** What the glucometer would most likely show for a sensor value, learned only from
the family's glucometer checks on the current sensor since its start or its last calibration.

**Based on**
- The sensor reads the fluid under the skin, which follows the blood with some delay;
  rapid changes show in it late [4].
- When glucose changes faster than 2 mg/dL per minute, CGM and blood glucose can disagree
  for that reason alone (ADA Standards of Care 2026, section 7,
  [GUIDELINES](GUIDELINES.md#cgm-and-the-glucometer)): such checks are kept on the chart but
  left out of the model.

**Our own: from our observations**
- Sensors differ: on one family's data, four sensors in a row read from 21% above the
  glucometer to 27% below it. So only the current stretch counts: the current sensor since
  its start, or since its last calibration (a calibration changes how the sensor reads).
- Each check is paired with the sensor value [the delay](#sensor-delay) later, when the
  sensor shows the blood the check measured (interpolated between the readings around it).
- The estimate: sensor + offset × max(sensor, 100), the offset being the stretch's weighted
  mean of (meter − sensor) / max(sensor, 100); a check a day older weighs half, as the offset
  drifts within a sensor. Shown from 2 checks in the stretch, and drawn from its start only.
- Older sensors only set the band around it: how far checks fall from their own sensor's mean.
- The same check arriving twice (the same value within 15 minutes) counts once; checks
  after a shower are left out; a calibration sent to the sensor is not a check.

**Limits**
- It never changes a reading and never feeds the alarms.
- With 2–3 checks on a new sensor the offset is rough; the band says so.

---

<a id="sensor-delay"></a>
## Sensor delay

**In short.** How many minutes the sensor runs behind the glucometer, measured on the
family's own checks, with how sure that number is. Until it is sure enough, Dexcom's
published 4 minutes are used.

**Based on**
- The sensor reads glucose in the fluid under the skin, which follows the blood: 5.3–6.2
  minutes in fasting healthy adults [11], a median of 6.8 (4.8–9.8) minutes in fasting adults
  with type 1 diabetes [12]. Commercial CGM systems have been reported at 3 to 12 minutes
  in all [4].
- Dexcom gives about 4 minutes as the average lag of its CGM [13]; 3.7 ± 3.1 minutes have
  been reported for the G6 [14].
- The method: shift the sensor by one minute at a time and keep the shift with the lowest
  mean absolute difference from the reference [15][16].
- How sure: a bootstrap percentile interval, the 2.5th and 97.5th percentiles of the
  shifts found on 1000 resamples of the checks [17].

**Our own: from our observations**
- Shifts of 0 to 20 minutes are tried, against glucometer checks.
- Each sensor reads off by its own amount, and a calibration changes it, so before comparing,
  each stretch (a sensor, or a sensor since its last calibration) has its own mean difference
  taken out. Without this, sensors off by different amounts hide the delay. A stretch with a
  single check is left out.
- Nothing is measured below 10 checks.
- The measured delay replaces Dexcom's 4 minutes only once its 95% interval is no wider than
  4 minutes: an interval wider than the delay itself says too little.

**Limits**
- Only checks taken while glucose moves tell anything about the delay; on a steady level any
  shift fits. So it takes hundreds of everyday checks for the interval to narrow.
- The delay may differ between people [4] and between sensors; one number is kept for all.

---

<a id="calibration"></a>
## Calibration advice

**In short.** When the sensor keeps reading off from the glucometer, the apps say so, and when a
fresh glucometer check can be entered in xDrip+ as a calibration. The number is only ever a
check just taken, never one worked out by DiaLife.

**Based on**
- Dexcom's agreement rule for its CGM: within 20% of the glucometer at 70 mg/dL or more, within
  20 mg/dL below; consider calibrating when, watched over several hours, the sensor is always
  higher (or always lower) than the meter and always outside it [18][19].
- Dexcom: enter a calibration within 5 minutes of the fingerstick; do not calibrate while
  glucose changes rapidly (after meals or exercise), with pressure on the sensor, on a sensor
  error, or on up or down trend arrows [19].
- CGM users need a glucometer for calibration where needed and when the sensor seems
  inaccurate (ISPAD 2024, ADA Standards of Care 2026, NICE NG18;
  [GUIDELINES](GUIDELINES.md#cgm-and-the-glucometer)).
- A new sensor reads less consistently with the glucometer, aligning over its first 24 hours [18].

**Our own: from our observations**
- "Several hours" made countable: three checks in a row outside the rule, all on one side, on
  the current sensor since its start or its last calibration; checks within an hour of each
  other count as one. Each check is compared with the sensor [the delay](#sensor-delay) later;
  one taken while glucose moved faster than 2 mg/dL per minute is left out.
- In a sensor's first 12 hours only when it is far off: on average 40% (from 70 mg/dL) or
  40 mg/dL (below), or it reads LOW while the glucometer shows 70 or more, which counts at once.
  Dexcom speaks of 24 hours; 12 is the family's choice.
- "Calibrate now with N": the newest check, at most 5 minutes old, 90–150 mg/dL (the xDrip+
  docs advise a first point in the lower normal range), the sensor within 10 mg/dL over the
  15 minutes before it, and the family's absorption time since food and rapid insulin; not
  after a shower. Otherwise the apps say which of these is missing.
- Until the sensor's reading the delay later has come, the newest reading stands in for a check
  just taken, so it can still be entered in time.
- A calibration (xDrip+'s "Add Calibration") starts a new stretch and ends the advice. Closed by
  hand, it is closed for every phone until a newer check is off again. Nothing in the no-sensor
  mode.

**Limits**
- What a calibration does inside the sensor is Dexcom's; DiaLife cannot tell how much it helps.
- Dexcom notes that calibrating brings the sensor closer to the meter, not necessarily to the lab.

---

<a id="shower"></a>
## After a shower

**In short.** During a shower and for 30 minutes after it the sensor is not believed: the
reading is dimmed and the apps say to trust only the glucometer.

**Based on**
- Nothing published that we found on how long warm water moves a CGM's numbers.

**Our own: from our observations**
- The window: the shower's length (10 minutes if none was given, at most 120) plus 30 minutes.
- In it: glucometer checks stay out of [≈ meter](#meter-estimate), the sensor's readings out
  of the forecasts' scores and of [Did you forget to log?](#questions). The alarms are untouched.

**Limits**
- The 30 minutes are a guess; nothing measures the effect.

---

<a id="no-sensor"></a>
## No-sensor mode

**In short.** While the family waits for sensors nothing alarms; glucometer checks out of
range show a card, and every phone is reminded to measure.

**Based on**
- Nothing published: a workflow of the family's.

**Our own: from our observations**
- A check below the low or above the high threshold shows a bright card on every phone until
  someone closes it.
- The reminder comes once after the family's interval without a check (180 minutes by default).
- Two sensor readings within 15 minutes end the mode by themselves; everyone is told the
  alarms are back.
- No forecast line or band is drawn: a backtest on the family's data found it reassuring
  exactly when glucose falls.

**Limits**
- Without a sensor nothing watches glucose between checks.

---

<a id="stats"></a>
## Statistics

**In short.** The numbers diabetes clinics read from a CGM: time in ranges, mean, GMI,
variability and the typical day.

**Based on**
- ISPAD's targets for children (2024) and the international consensus on time in range [3]:
  time in five zones (its own are < 54,
  54–69, 70–180, 181–250, > 250), the coefficient of variation with ≤ 36% read as stable,
  and the AGP "typical day".
- GMI = 3.31 + 0.02392 × mean glucose (mg/dL) [5].
- AGP percentile bands as read in clinic: the median, the 25th–75th band (half of all
  readings) and the 5th–95th band (nine in ten) [9].
- A low counts as an event when it lasts at least 15 minutes: the 2017 consensus metric for
  episodes, as listed in [3], Table 1.

**Our own: from our observations**
- The five zones use the family's alarm thresholds; time in the standard 70–180 is shown
  as well, for the doctor.
- A gap in the data ends a low event: what happened in between is unknown.
- Daily insulin and carb totals leave out days with nothing logged.

**Limits**
- Only the sensor's readings count; checks by glucometer do not.

---

<a id="on-board"></a>
## Insulin and carbs on board

**In short.** How much of the logged rapid insulin and food is still to act, and where that
would take glucose from the current reading.

**Based on**
- Insulin on board: the exponential activity curve of OpenAPS (oref0) [6] with its
  "rapid-acting" peak at 75 minutes.

**Our own: from our observations**
- The duration of insulin action is the family's setting (240 minutes by default). OpenAPS
  allows a custom duration for this curve only when it is greater than 300 minutes [6]; a
  shorter one makes insulin look used up sooner.
- Carbs on board: nothing for the first 15 minutes, then absorbed evenly over the family's
  absorption time (180 minutes by default).
- The forecast from them: the current reading, plus the carbs absorbed by then ÷ ratio ×
  ISF, less the insulin acted by then × ISF. ISF is the family's setting, or read from the
  correction table (the typical mg/dL between rows per unit added).
- Its accuracy is measured like [In 30 min](#forecast-30).

**Limits**
- Only as good as the entries: a dose or a meal not logged is invisible.
- Exercise, illness and stress are not in it. It never feeds the alarms.

---

<a id="double-insulin"></a>
## Double insulin guard

**In short.** Two doses of the same kind close together are most likely one injection
logged twice, or two parents injecting at once: the app asks before saving, and a pair
with a dose from xDrip+ in it becomes a question.

**Based on**
- Nothing published on these windows.

**Our own: from our observations**
- Rapid insulin: two doses less than 20 minutes apart (a correction comes later than that);
  any rapid dose within the last 3 hours is noted before another one.
- Long-acting: two doses less than 16 hours apart (it is given once a day).

**Limits**
- It knows only logged doses.

---

<a id="meal-dose"></a>
## Meal dose

**In short.** The dose for a meal from the family's own numbers: carbs ÷ ratio, plus the
correction from the glucometer, rounded once to the pen's step.

**Based on**
- The family's ratio and correction table, set with their doctor; ISPAD recommends
  individual carbohydrate ratios for intensive insulin therapy. DiaLife does not suggest them.
- A pen step of 0.5 unit, which ISPAD notes is useful for young children.
- Adding the meal and correction parts first and rounding the total once, as in a paediatric
  clinic's bolus worksheet [10].

**Our own: from our observations**
- The correction comes only from a glucometer check at most 15 minutes old (a family
  setting), never from the sensor.
- Rounding rules to choose from: nearest, nearest with an exact half going down, always
  down, always up; step 1 or 0.5 unit. "Nearest" is plain arithmetic: with a 0.5 step,
  x.3 goes up to x.5, where the worksheet [10] rounds x.1–x.3 down to the whole unit.
- A dose over the family's limit needs a separate confirmation; over twice the limit it is
  treated as a mistake; over 1.5 times the usual dose it gets a note.
- Glucose below 70 on the glucometer: a bright "raise it first" warning; nothing is subtracted.
- A dose worked out is only planned: it counts for nothing until a parent confirms it was given.

**Limits**
- It does not adjust for insulin on board, exercise or illness.

---

<a id="meal-group"></a>
## One meal, several entries

**In short.** Food entries close together are one meal, and the rapid insulin near them is
counted for it.

**Based on**
- Nothing published.

**Our own: from our observations**
- Two food entries belong to one meal when they are at most 30 minutes apart. Once the family
  has 10 shorter gaps in its history, the window becomes three in four of those gaps (over
  90 days) plus 10 minutes, between 15 and 30 minutes.
- Rapid insulin from 30 minutes before the first food to 60 minutes after the last belongs
  to the meal, unless it was marked as a correction.

**Limits**
- Two meals eaten close together are read as one.

---

<a id="wait"></a>
## When to eat

**In short.** After rapid insulin for a meal, a suggestion of how long to wait before eating:
longer when glucose is high, at once when it is low or falling.

**Based on**
- ISPAD: pre-meal insulin from diabetes onset for young people of all ages (grade A); a bolus
  15–20 minutes before eating improves the rise after it
  ([GUIDELINES](GUIDELINES.md#meal-insulin)).
- Rapid insulin given 20 minutes before a meal gave a lower rise after it than given at the
  start of the meal or 20 minutes into it, in adolescents and adults with type 1 diabetes [7];
  a review puts the best time at 15–20 minutes before food [8].

**Our own: from our observations**
- The minutes and bounds are the family's: 15 minutes by default, 20 above 180 mg/dL, eat at
  once below 80 now or by the trend in 15 minutes.
- No study ties the wait to the size of the dose or the meal, so neither changes it.

**Limits**
- A suggestion only; nothing is recorded.

---

<a id="carb-suggestions"></a>
## Carb suggestions

**In short.** Grams of carbohydrate offered for a food: what the family logged for it
before, a portion table, and optionally a language model. Nothing is filled in by itself.

**Based on**
- Imported portion tables of 15 g carbohydrate servings (see the README for the tables).

**Our own: from our observations**
- The order: the family's own number first, then table portions, then the models' estimate
  when one is configured. A food the table knows is not sent to the models.
- Only the food's name and portion leave the server, never glucose or names of people.

**Limits**
- A language model's estimate can be wrong; it is always shown as such.

---

<a id="questions"></a>
## Did you forget to log?

**In short.** A rise nobody logged food for, or a fall nobody logged insulin for, asks the
parents; an unanswered question becomes a mark on the chart after 12 hours.

**Based on**
- Nothing published on these thresholds.

**Our own: from our observations**
- A rise: at least 40 mg/dL from the low point, at 1.5 mg/dL per minute or more over 20
  minutes, with no food in the 90 minutes before.
- A fall: at least 50 mg/dL from the high point, likewise, with no rapid insulin in the
  4 hours before and no exercise in the 3 hours before. At night (00–06) a slower fall,
  0.8 mg/dL per minute over 30 minutes, also counts.
- A fall faster than 4 mg/dL per minute is read as the sensor pressed (lying on it), not insulin.
- Nothing in the first 12 hours of a sensor, nor with readings missing for 15 minutes.
- Every parent answers; it closes when they agree; a reminder every 30 minutes.

**Limits**
- It sees only the sensor and the entries.

---

## References

Every source below was opened and the statement it backs was found in it.

1. Nightscout, cgm-remote-monitor (master): the buckets and the 5-minute change in
   [`lib/plugins/bgnow.js`](https://github.com/nightscout/cgm-remote-monitor/blob/master/lib/plugins/bgnow.js);
   the AR2 constants, loss thresholds and the 20-minute side in
   [`lib/plugins/ar2.js`](https://github.com/nightscout/cgm-remote-monitor/blob/master/lib/plugins/ar2.js);
   "no data" after 15 and 30 minutes in
   [`lib/plugins/timeago.js`](https://github.com/nightscout/cgm-remote-monitor/blob/master/lib/plugins/timeago.js).
2. Dexcom. Using Your G6, the trend arrows table.
   <https://s3-us-west-2.amazonaws.com/dexcompdf/Using-Your-G6.pdf>
3. Battelino T, Danne T, Bergenstal RM, et al. Clinical Targets for Continuous Glucose
   Monitoring Data Interpretation: Recommendations From the International Consensus on Time
   in Range. *Diabetes Care* 2019;42(8):1593–1603. <https://pmc.ncbi.nlm.nih.gov/articles/PMC6973648/>
4. Schmelzeisen-Redeker G, Schoemaker M, Kirchsteiger H, et al. Time Delay of CGM Sensors:
   Relevance, Causes, and Countermeasures. *J Diabetes Sci Technol* 2015;9(5):1006–1015.
   <https://pmc.ncbi.nlm.nih.gov/articles/PMC4667340/>
5. Bergenstal RM, et al. Glucose Management Indicator (GMI): A New Term for Estimating A1C
   From Continuous Glucose Monitoring. *Diabetes Care* 2018;41(11):2275–2280.
   <https://pmc.ncbi.nlm.nih.gov/articles/PMC6196826/>
6. OpenAPS documentation. Understanding Insulin on Board (IOB) Calculations: the exponential
   curves ("rapid-acting": peak at 75 minutes; a custom duration must be greater than 300 minutes).
   <https://openaps.readthedocs.io/en/latest/docs/While%20You%20Wait%20For%20Gear/understanding-insulin-on-board-calculations.html>
   The formula itself, as ported: oref0
   [`lib/iob/calculate.js`](https://github.com/openaps/oref0/blob/master/lib/iob/calculate.js).
7. Cobry E, McFann K, Messer L, et al. Timing of meal insulin boluses to achieve optimal
   postprandial glycemic control in patients with type 1 diabetes. *Diabetes Technol Ther*
   2010;12(3):173–177. <https://pubmed.ncbi.nlm.nih.gov/20151766/>
8. Slattery D, Amiel SA, Choudhary P. Optimal prandial timing of bolus insulin in diabetes
   management: a review. *Diabet Med* 2018;35(3):306–316. <https://pubmed.ncbi.nlm.nih.gov/29044708/>
9. Czupryniak L, et al. Ambulatory Glucose Profile (AGP) Report in Daily Care of Patients with
   Diabetes: Practical Tips and Recommendations. *Diabetes Ther* 2022. <https://pmc.ncbi.nlm.nih.gov/articles/PMC8991298/>
10. Nationwide Children's Hospital. Insulin Bolus Calculator (worksheet: carbohydrate bolus,
    correction bolus, total, rounded dose; rounding rules for ½ and whole units).
    <https://www.nationwidechildrens.org/-/media/nch/specialties/diabetes-clinic/insulin-bolus-calculator.pdf>
11. Basu A, Dube S, Slama M, et al. Time Lag of Glucose From Intravascular to Interstitial
    Compartment in Humans. *Diabetes* 2013;62(12):4083–4087. <https://pmc.ncbi.nlm.nih.gov/articles/PMC3837059/>
12. Basu A, Dube S, Veettil S, et al. Time lag of glucose from intravascular to interstitial
    compartment in type 1 diabetes. *J Diabetes Sci Technol* 2015;9(1):63–68.
    <https://pubmed.ncbi.nlm.nih.gov/25305282/>
13. Dexcom. What should I do if my sensor reading and capillary blood glucose value don't match?
    ("The average lag time for Dexcom CGM is approximately 4 minutes".)
    <https://www.dexcom.com/en-GB/faqs/sensor-vs-meter-readings>
14. Welsh JB, Psavko S, et al. Comparisons of Fifth-, Sixth-, and Seventh-Generation Continuous
    Glucose Monitoring Systems. *J Diabetes Sci Technol* 2024;18(1):143 (online 2022).
    <https://pmc.ncbi.nlm.nih.gov/articles/PMC10899839/>
15. Jensen MH, Dethlefsen C, Hejlesen O, et al. Simple Post-Processing of Continuous Glucose
    Monitoring Measurements Improves Endpoints in Clinical Trials. *J Diabetes Sci Technol*
    2020;14(6):1074 (online 2019). <https://pmc.ncbi.nlm.nih.gov/articles/PMC7645147/>
16. CGM Precision and Glycaemic Variability, ClinicalTrials.gov NCT03842683 (the time-shift
    outcome). <https://clinicaltrials.gov/study/NCT03842683>
17. Hesterberg TC. What Teachers Should Know about the Bootstrap: Resampling in the
    Undergraduate Statistics Curriculum. 2014 (the percentile interval, after Efron and
    Tibshirani, An Introduction to the Bootstrap, 1993). <https://arxiv.org/abs/1411.5279>
18. Dexcom. What do I do if my blood glucose meter is not within the accuracy (20/20) range of my
    Dexcom G6? <https://www.dexcom.com/en-us/faqs/blood-glucose-meter-is-not-within-accuracy-range>
19. Dexcom. Calibration Guidance for Dexcom CGM Systems. <https://www.dexcom.com/en-gb/faqs/calibration>
