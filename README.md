# Solax Inverter Automation

**Author:** Pavel Mikula

A Spring Boot application that runs a Solax X3-Hybrid-G4 for you: it decides when to charge the
battery, when to give surplus to the grid, when to hold the battery back as an outage reserve, and
when to sell it into the day's price peak. It runs on a Raspberry Pi 4B next to the inverter and
serves a web dashboard so you never have to open SolaxCloud to see what it is doing.

> Built for one private installation. It is provided "as-is" — read the configuration before you
> point it at your own inverter.

---

## How it works

Two transports talk to the inverter, and each is used for what it is actually good at:

| | Modbus TCP | SolaX Cloud OpenAPI |
|---|---|---|
| Latency | instant | commands queued, readings minutes old |
| Cost | wears the inverter's flash on every write | free |
| Work mode | authoritative read **and** write | can only be inferred from device status |
| Remote control (selling) | not available | the only way |
| Reports | battery %, work mode, export limit, PV power | everything above plus grid/load power, daily energies, temperatures, SOH |

So: **persistent work mode changes go over Modbus**, **selling goes over cloud remote control**, and
readings take the authoritative values from Modbus and everything else from the cloud. All of that is
configurable under `solax.control`.

### Why selling uses remote control rather than a work mode

Putting the inverter into `MANUAL / FORCE_DISCHARGE` is persistent: if this application crashes, the
Pi loses power, or the network drops mid-sale, the inverter keeps emptying the battery into the grid
until somebody notices. A remote control session (`push_power/positive_or_negative_mode`) carries its
own duration and `nextMotion: exit remote control`, so the inverter returns to its configured work
mode on its own — no matter what happens on this side. The persistent work mode is therefore only
ever touched by the weather and battery modules, and stays meaningful across restarts.

The old Modbus path is still there behind `automation.discharge.fallback-to-manual-mode`, off by
default.

### Changes made outside the automation

The inverter is not only this application's to move: the SolaX app, the panel on the inverter
itself and a schedule stored on it can all change the persistent work mode, and none of them say
so. So the work mode is **read back on a timer** (`solax.control.work-mode-watch`, every minute by
default) and a mode that moved on its own is recorded in the activity history like any other
change — filed under _Inverter_ rather than a module, so the activity list shows when it happened
and says who did not do it.

Nothing extra is read for this. It looks at the same cached snapshot the dashboard and the modules
already poll, so the only cost is a Modbus round trip that would have happened anyway.

A change this application made is not recorded twice: every successful work mode write is announced
to the watcher, and a mode matching one written inside `attribution-window` (5 min, long enough to
outlast a queued cloud command) is understood as its own. Two things it deliberately cannot see: a
change made while it was down or the inverter unreachable — the dashboard then says the live mode
disagrees with the last change on record rather than inventing a time for it — and anything during
a remote control session, which steers the inverter without touching the persistent mode.

Detecting a change is all this does. No module reacts to it: a mode set by hand holds until the
next checkpoint of the weather or battery module comes round and decides otherwise, exactly as
before.

---

## Modules

Every automation is an independent module: its own package, its own configuration section, and its
own `enabled` flag. Nothing enumerates them by name — dropping a new `AutomationModule` bean on the
classpath is enough for the registry, the API and the timeline to pick it up (give it a
`module.<id>.name` in `i18n.js` and the dashboard names it in the history too). Deleting a module means deleting its package.

| Module | Config prefix | What it does |
|---|---|---|
| Battery charge guard | `automation.battery` | Checks the battery against charge targets through the day: switches to self use when behind schedule, and to feed-in priority when comfortably ahead of it so surplus production is sold rather than wasted. A checkpoint counts as met while the battery is within `tolerance` of it, so 79 % against an 80 % target is not treated as behind schedule. Switching to feed-in priority also asks the forecast first: a charged battery only pays off as export while the coming hours are sunny enough for there to be a surplus (`feed-in-weather`). |
| Export limit | `automation.export` | Closes the export limit while the spot price is too low to be worth selling, and throttles it around midday on dull days. Runs every quarter of an hour, matching how often the price changes. |
| Weather work mode | `automation.weather` | Chooses between feed-in priority and self use from the forecast, and moves to backup ahead of a thunderstorm. |
| Grid selling | `automation.discharge` | Finds the most valuable quarter-hour window of the day and sells the battery into it through remote control. |
| Morning buy and sell | `automation.morning` | Off by default. Fills the battery from the night tariff of the second supply when the day looks bad or the morning price is worth selling into, and sells the morning peak — on a good day only. |
| Battery balancing | `automation.balance` | Started from the dashboard. Fills the battery to 100 % and holds it there for an hour without a break, so the battery management system can even out its cells. |

### Writing a new module

```java
@Component
public class MyModule extends AbstractAutomationModule<MyProperties> {

    public MyModule(MyProperties properties) {
        super(properties);
    }

    @Override public String getId()           { return "my-module"; }
    @Override public String getName()         { return "My module"; }
    @Override public String getDescription()  { return "One or two sentences."; }
    @Override public String getConfigPrefix() { return "automation.my-module"; }

    @Override
    public List<ConfigEntry> getConfiguration() {
        return List.of(ConfigEntry.of("automation.my-module.threshold", "Threshold",
                properties.getThreshold(), "%", "What this value controls"));
    }

    @Scheduled(cron = "0 7 * * * *")
    public void check() {
        run("Hourly check", () -> {
            // ... read state, decide, act
            return RunOutcome.unchanged("Nothing to do");
        });
    }
}
```

`MyProperties` implements `ModuleProperties` and is a plain `@ConfigurationProperties` class. The base
class handles the enabled check, the run header, timing, failure capture and the dashboard status.

---

## Selling: how the window is chosen

The Czech market settles in **15 minute intervals**, so a day has 96 prices rather than 24 — the
application uses `/api/v1/price/get-prices-json-qh` throughout (the hourly `get-prices-json` endpoint
is deprecated).

Selling only into the single most expensive interval wastes most of the battery; selling across the
whole evening gives energy away at mediocre prices. `DischargeWindowPlanner` therefore works in three
steps:

1. **Peak** — the most expensive interval inside `search-from`…`search-to`.
2. **Plateau** — grow outwards from the peak while neighbouring intervals stay within
   `price-tolerance` (1 CZK/kWh by default) of it. That run is the part of the evening genuinely worth
   selling into.
3. **Fit** — the battery rarely covers the whole plateau, so slide a window of the length the battery
   can actually sustain (`(soc − reserve) × capacity × efficiency ÷ discharge-power`) across the
   plateau and keep the placement that earns the most. **Equal-earning placements resolve to the
   latest one**, which pushes the discharge towards the end of the plateau instead of starting at its
   first interval and running dry before the peak.

**Planning never looks at the battery.** It runs at 15:00, hours before the evening peak, with the
sun still charging — the level read then says nothing about the level at 19:00. So a window is armed
on price alone, and `soc` in the formula above is `min-battery` (50 % by default: the charge the sale
requires anyway), floored at whatever the battery already has. Whether the sale is actually worth
starting is decided again **when the window opens**, against that same `min-battery`: too little
charge and the window is simply dropped, with the reason in the log. A window armed by hand from the
dashboard skips that check — it is the person's call — but never the reserve.

Over-estimating is safe either way: the guard ends the sale as soon as the reserve is reached.
Under-estimating is not, because it arms a window too short to use the peak.

**`discharge-power` is not export power**, and the difference is money. The house is fed from the
sale before anything reaches the meter — so a sale at exactly `automation.export.power.maximum` exports the limit *minus* whatever is running
in the house, and the kettle spends the evening being run off energy that was meant to be sold at the
day's highest price. Setting it **above** the export limit fixes that: the extra covers the house,
the inverter's export limit holds the meter at the limit, and the whole limit is sold. The default
(`4600` against a 3950 W limit) leaves 650 W of headroom for consumption.

Two ceilings still apply. The battery and the inverter cap what can be delivered at all — ask for
more and the inverter simply produces what it can — and while a sale runs, the export limit is the
only thing keeping the extra off the meter, so the headroom is worth setting to the house's real
consumption rather than to the inverter's rating. `0` returns to the old behaviour of following the
export limit exactly.

The two numbers are used for different things, and the log says both: the window is sized on the
**discharge** power, since that is the rate the battery actually empties at, while the expected
revenue is on the **exported** part alone — what the house eats during the sale is not bought by
anybody.

### Panels first

A sale used to stop the panels. The remote control session (`push_power/positive_or_negative_mode`)
pins the **battery** at a fixed power, and with the battery alone already filling the export limit
plus the house — 4600 W against a 3950 W limit — there is nowhere left for the panels' output to go:
the meter is capped, the battery's setpoint cannot move, so the inverter curtails the panels to
nothing for the length of the sale. On an evening with sun left, that sun was thrown away while the
battery emptied at full power.

With `automation.discharge.pv-first` (on by default) the battery is only asked for what the panels do
not cover: `discharge-power` minus the live PV reading, in tens of watts, never below zero. The two
together still put out `discharge-power`, so the meter still sees the full limit — the panels' share
simply stays in the battery. The guard re-reads the panels every `guard-interval` (now 2 min) and
re-sends the battery's share whenever it should move by more than `pv-follow-deadband` (150 W), so the
battery takes over as the sun sets. Each re-send carries the time left in the window, so the session
still expires on its own. Following is synchronised with cancelling, and checks the window is still
the armed one before sending anything, so a cancelled sale is never restarted behind the dashboard's
back. Without a PV reading the battery takes the whole sale, as before. The start of the sale logs the
split, and the selling card shows it while it runs.

One limit is worth knowing: the PV reading is what the panels deliver, not what they could. If the sun
comes out *during* a sale, the extra still has nowhere to go and stays curtailed until production
falls below where it was at the last re-send. In the evening, when sales happen, production only
falls, so this rarely matters. `pv-first: false` restores the battery-only sale.

At the window's start a remote control session is opened for exactly the window's length. A guard
checks the battery every `guard-interval` and ends the session early once the reserve is reached. If
the export limit happens to be closed below what the sale means to export — the export module holding
it down, or a change made by hand — the run logs a warning rather than quietly trickling energy out
at the limit.

The placement rules are covered by `DischargeWindowPlannerTest` — they decide how much money the
battery earns, so they are pinned down rather than only observed in production logs.

---

## Morning buy and sell

The house has a second supply with a cheap night tariff, from midnight to 06:00. With
`automation.morning.enabled` on, the module decides at the start of the night charge window
(02:00) whether filling the battery from that tariff is worth it. There are two reasons it can be:

- **A bad day.** The mean forecast quality over the day's productive hours (`bad-weather`, 08:00 to
  16:00) is at or above `min-quality` (3.0, about mostly cloudy). The panels will not fill the battery
  today, so the night tariff does it, and the house runs on that rather than on daytime grid power.
- **A good morning price.** Some interval of the morning sale window (06:15 to 09:00) reaches
  `sell.min-price`, the price X. Energy bought at night is then sold at a profit after it.
  The day's prices are published the afternoon before, so they are known at 02:00.

Either reason is enough. Nothing is bought when the battery is already at `target-battery` (100 %),
or when the house is on the metered grid, where the night tariff does not apply
(`require-second-supply`, read from the connection switch). The charge is a remote control session
sent for exactly the rest of the window, so it ends at 06:00 on its own even if this application does
not. A check every minute ends it earlier once the battery is full, or if the house moves to the
metered grid. The decision runs on a minute tick rather than a single cron firing, so a restart at
03:30 still charges the rest of the night instead of losing it.

From 06:15 the morning is sold into, wherever the price is at or above X: the first unbroken run of
such intervals up to 09:00. Only on the metered grid (`require-metered-grid`), because export from
the second supply never reaches the meter. The sale is handed to the grid selling module
(`DischargeModule.armFor`), so it runs exactly like an evening sale. The panels go first, the guard
stops it at the reserve, the selling card shows it as armed by the morning automation, and the
statistics count it as a sale. That module therefore has to be enabled. It sells down to
`sell.reserve-battery` (40 %). A window already armed is never replaced.

**On a bad day the morning is not sold at all.** Emptying the battery only pays when the panels
fill it again the same day; on a bad day the night charge was bought to carry the house, and
selling it would only buy it back from the grid in the afternoon. The same judgement as the night
charge decides it (`bad-weather`), and a morning with no forecast counts as bad, because nothing says
the sun will refill the battery. The activity list says which of the two stopped the sale, and the
planned actions show the morning as _No morning sale_ as soon as the forecast says so. Set
`sell.on-bad-day: true` to sell anyway, down to `sell.bad-weather-reserve` (80 %).

Set X above what the night tariff costs plus the losses of charging and discharging, or the round
trip loses money. The planned actions on the History page preview tonight's decision as soon as the
forecast and the prices allow, and every decision is in the activity list with its reason.

The rules are covered by `MorningModuleTest` and `DischargeArmForTest`.

## Battery balancing

A lithium pack evens its cells out only while it sits full, and one that is cycled every day and
rarely stays at the top drifts apart a little at a time. **Balance the battery** in the Control
card charges it to `automation.balance.target-battery` (100 %) and holds it there for
`hold` (60 min) without a break, then hands the inverter back to its work mode.

It runs as a remote control charge at `power` (3000 W): the panels cover what they can, the grid the
rest, and a battery told to charge cannot be drawn down by the house, which is what keeps it at the
top for the hour. Two sessions are sent, one long enough to get full (`timeout`, 6 h) and one for the
hold ending five minutes after it, so the inverter lets go on its own even if the application stops.
A check every minute times the hold. A drop off the top starts the hour over, and a battery not full
within `timeout` gives up. A sale into the grid takes precedence: balancing does not start while one
runs, and steps aside if one starts. If another module ends its session — the night charge stopping
at a full battery — the session is sent again after `resend-after` (3 min). _Exit remote control_
cancels balancing too.

While it runs, the card shows whether it is still charging or already holding, with a bar for the
hour; the work mode band and the price chart draw it like any other charge from the grid, and every
step is in the activity list. Nothing starts it on its own. The rules are covered by
`BalanceModuleTest`.

---

## Dashboard

`http://<host>:8080/` — no build step, no CDN, works offline on the Pi.

Three pages: **Control**, which is the dashboard proper, **Statistics**, which is the days behind
us, and **History**, which is what the modules will do and what they did. The page you were on is
remembered per browser.

**Control** — first the live tiles, then the day's 96 quarter-hour prices with the armed selling window highlighted, and
the weather quality curve with the thresholds the modules compare against: every decision the
controls make is read off those two. Both cards browse a day at a time (below). Then the controls —
**Selling** and **Charging from the grid** side by side, and the **Control** card under them. Then
**Work mode today**: the day's work mode as a band,
rebuilt from the recorded changes, with the thinner band underneath saying which supply the house
was on — the connection switch's throws drawn in time, read against what the inverter was doing.
A ribbon along the bottom of the mode band marks the remote control sessions that ran on top of it:
selling to the grid in purple, charging from the grid in cyan, each hollow while it is still only
planned. On the price chart the intervals a charge covered are marked by a hatched strip under the
bars rather than by recolouring them, so every bar keeps its price band and what the energy was
bought at stays readable.

A charge's stretch is drawn from its start to the earliest known end: the end it was sent with (a
timed charge from the dashboard, the night charge), or the first exit, sale or new charge after it.
A charge to a target state of charge has no end anybody can name in advance — the inverter stops it
when the battery gets there — so the inverter watcher (`solax.control.work-mode-watch`) records the
moment the remote control session is seen to end, as _Charging from the grid ended_ in the activity
list. A charge whose end was never seen is drawn to the next thing on record and its tooltip says so.
The live tiles at the top are battery/PV/grid/load, work mode, current price, and the Raspberry Pi's
connection switch (HIGH is the metered grid, LOW the second supply — the tile says which, and says
plainly when it is the off-Pi stub rather than a pin).

**Statistics** — the summary as a row of tiles under a plain heading, with the period controls beside
it; then energy and money per day, the battery's daily range, the time in each work mode and the
daily breakdown, for the last 7, 30 or 90 days or a calendar month (below).

**History** — the planned actions (a timeline of what every module intends to do and what it already
did, with the list of what is coming) and the activity list, with its day filter, _Changes only_ and
row count. Neither grows the control page: the plan is long on a busy day and the activity list grows
all day.

### Statistics

Nothing upstream keeps a history this application can ask for — the inverter and the cloud report
*today's* counters, the price service *today's* prices — so the application records each day as it
goes. Every `statistics.sample-interval` (5 min) it reads the inverter's day counters (yield, export,
import, battery charged and discharged), the battery level, the production and load, the work mode
and the price, and keeps one record per calendar day in `statistics.file` (`data/statistics.json`).
**The statistics therefore start on the day this version first runs and fill in from there**. Days
the application was down are drawn as gaps rather than closed up.

Money cannot be read off the counters at the end of the day, because the price moves every quarter of
an hour. Each sample's growth in the export and import counters is valued at the spot price of the
interval the inverter reported it in, and whatever was exported while a sale was running is kept apart
as the sale's own takings. That is the spot price alone, without distribution fees or your tariff's
margins — an estimate of what the energy was worth on the market, not a bill. A day first seen long
after midnight (the first day, a restart) keeps its energy but marks its money as partial.

The section shows:

- **Tiles** — production (and the average and best day), consumption and self-sufficiency, export and
  what it was worth, import and what it cost, the net of the two, battery sales (money, kWh, how many),
  battery throughput, the average spot price with its range, and the peak production seen.
- **Energy per day** — production, consumption, export and import side by side.
- **Money per day** — income stacked above the axis (sales, then other export), import cost below it,
  and a tick for the day's net.
- **Battery per day** — a bar from the lowest to the highest state of charge.
- **Time in each work mode** — the watched time split between the modes.
- **Daily breakdown** — every recorded day in a table, newest first, with totals, built only while
  it is open.

**Which days.** The switch picks the last 7, 30 or 90 days, ending today. Beside it is a **month
browser**: ‹ goes back a calendar month (from a rolling view, to last month), › forward again, and the
list jumps straight to any month there is something recorded for — back to the month recording began,
or the oldest month still within `retention`. The current month runs from the 1st to today. Which
kind of view is remembered per browser, but the month browsed to is not, so the page always opens on the
present. The line under the heading names the period on screen and how many of its days have data.
The API takes either shape: `GET /api/statistics?days=30` or
`GET /api/statistics?from=2026-10-01&to=2026-10-31`, clipped to what is kept and never past today.

Weekends get a faint wash and today, still running, a faint outline. Hovering any day gives every
number recorded for it. Consumption is the energy balance
(production + import + battery out − export − battery in), and the energies and money need the SolaX
Cloud connection — Modbus alone reports neither, so without the cloud only the battery, production
peak and work mode time are recorded. The file is rewritten at most every `persist-interval` (15 min),
at a day change and at shutdown, so the SD card on the Pi is not worn every five minutes. Days older
than `retention` (400 days) are dropped.

**Every tile keeps one fact per line**, and the ones with a known ceiling draw it. The battery has its
level as a bar, coloured as a warning when it runs low. Solar production is drawn against
`dashboard.pv-peak` — the array's installed peak, **7.2 kWp** for this installation, and deliberately
not the 3950 W export limit: the panels can out-produce what may leave the house, and the difference
is what the battery and the house absorb. (`0` falls back to `automation.export.power.maximum` for an
installation that has not set it.) Grid **export** is drawn against the live export limit. Both use
the neutral accent rather than the battery's traffic light: little production at eight in the morning
is the sun's doing, not a problem to colour red. What comes the other way through the meter gets no
bar — an import has no ceiling worth drawing, only what the house asks for.

The price chart also draws what the two price-driven automations make of the day, because the whole
point of the prices is what those two do about them. Intervals under `automation.export.min-price`,
where the export limit module closes the export because production is not worth putting on the grid,
are **hatched** over their full height and their bars step back. Intervals the selling module may
sell the battery into — inside its search hours and at or above `automation.discharge.min-price` —
are **washed in the selling colour** instead, with that minimum drawn in as a dashed line. Adjacent
intervals merge into one band, so each reads as a part of the day rather than 96 stripes.

The wash is where a window *can* be armed, not where one *will* be: which of those intervals is
actually chosen depends on the peak, the plateau around it and the charge in the battery, and only
the planner can answer that. The armed window itself keeps its own solid colour on top.

The timeline shows every run each module has coming up, not just the next one. Two modules are left
out of it: the export limit is re-checked every quarter of an hour and the weather work mode every
hour, nearly always with the same outcome, and 92 identical rows bury the handful that say something.
`GET /api/modules` still reports every module's full schedule.

**Both time charts switch between looking forward and showing the whole day.** _From now_ is the
default — the next 24 hours of the timeline, the forecast from this hour on. _Whole day_ starts at
midnight instead, so the morning is on screen next to the afternoon, and the choice is remembered per
browser. Looking back is only worth a switch because there is something there: the timeline fills the
hours behind now with what actually ran, taken from the same activity history the list below shows,
drawn on its module's own row and stepped back so the plan still reads first. A run that failed is
red. On the weather curve the hours already past are washed over and separated from the forecast by
the same "now" marker.

The forecast only ever looks forward, so the hours behind us are the ones the application saw go by.
They are written to `meteosource.history.file` (`data/weather-history.json` by default) so a restart
does not start the day over — and where the window still reaches back further than the record does,
the axis keeps the missing hours rather than stretching the readings across them, with a line under
the chart saying from when it has been watching. Turn the file off with
`meteosource.history.persist: false` and the record lives for as long as the process does.

**Both the price and the weather card browse a day at a time.** In each header ‹ goes back a day,
› forward, and the day's name between them — _Today_, _Yesterday_, a date — is a button that comes
straight back to today. The weather keeps `meteosource.history.retention` (**7 days**) of passed
hours, so the quality curve of any day in the past week can be put next to today's; a day other than
today is always shown whole, washed over as already happened, and the _From now / Whole day_ switch
rests until the browser is back on today. Forward, the card reaches as far into tomorrow as the
forecast does.

The prices work the same way, with the same limit upstream: the price service serves today and
tomorrow and nothing before, so each day's 96 intervals are kept as they are first published —
written to `ote.history.file` (`data/price-history.json` by default), kept for
`ote.history.retention` (**7 days**), turned off with `ote.history.persist: false`. A day back shows
the prices as they were, with the same hatch and wash for what the export limit and the selling
module would have made of them, and the line above the chart says so. _Tomorrow_ is always one
press away, and says when it will be published until it is.

The list beneath the chart pages in tens, so a long plan stays one screen tall. The page you are on
survives a refresh. Recent activity pages the same way, but how many rows fit on a screen is a matter
of taste, so its row count is a control in the card header — 5 to 100 rows, remembered per browser.

**Recent activity is filtered rather than truncated.** Two switches sit in that card's header, both
remembered per browser. _Today / Yesterday / All_ picks the day, because the history now reaches two
days back rather than the last handful of rows. _Changes only_ takes out the checks that decided to
leave everything alone — the export limit is re-evaluated every quarter of an hour and the answer is
almost always "nothing to do", which is 68 rows a day burying the handful that say something. A check
that **failed** stays visible whatever the switch says: it is the one row that must not become hard to
find. Hovering the switch says how many of the retained entries are currently on screen.

Every activity row is a headline and a sentence: what the module decided ("Export limit stays at
3950 W") and why it decided it ("the spot price 2.15 CZK/kWh is at or above the 0.50 CZK/kWh
exporting is worth it at, so the limit is fully open").

Recent activity survives a restart, and enough of it to be worth having: everything inside
`timeline.retention` (**48 h**) is kept in a small JSON file (`data/timeline.json` by default), capped
at `timeline.persisted-events` as a safety valve so a module stuck in a loop cannot grow the file
without bound. That window is what makes the charts and the list whole after a restart — the day
behind us is drawn out of these entries, and a restart that dropped everything but the last few rows
left holes nothing could fill back in — and it is what "yesterday" filters. Entries older than the
window are dropped when the file is read, so an application that was off for a week does not come
back with a week-old "recent activity". It is still a convenience for the dashboard, not an audit
log — the rolling log files remain the durable record.

Every chart is hoverable. A price interval reports its exact price in both currencies, how it
compares with that day's average and, where it falls into one of the two bands, the rule that put it
there; a forecast hour reports its quality, the band that quality falls into, cloud cover and
temperature, and says when it is already behind us; a planned action is titled by its kind and the
module behind it, reports the window, how long it lasts, how far away it is and whether it is
committed or a routine check, and carries the sentence describing it underneath rather than as a
title. A run that already happened is titled by what it decided instead, with how long ago it ran and
whether the inverter took it. The weather quality formula sits behind the ⓘ button in that card's
header rather than taking up space on every visit, and the two thresholds are named in the legend
instead of being written across the plot.

There is no modules page any more. A module is switched off with its `enabled` flag. The API behind
the old page is still there for a script that wants it — `GET /api/modules` reports every module's
status, configuration and plan, and `POST /api/modules/{id}/enabled?value=false` disables one until
the next restart.

The page re-fetches every `dashboard.refresh-seconds` (a minute by default, which is about as fast as
the underlying values actually move), and shows skeleton placeholders until the first fetch lands.

**Why it is fast.** Reading the inverter is slow: every Modbus request is spaced a second apart by the
request queue, so one snapshot costs several seconds and can queue behind a module's own reads.
Blocking the browser on that made every poll feel like a hang. The gateway therefore serves the last
reading and refreshes behind it, and warms the cache at start-up, so no request ever waits on Modbus -
a full dashboard load is a few hundred milliseconds. The values are at most one refresh interval old,
well inside how fast any of them move.

**Selling controls** — the card carries the decisions and arming happens in a dialog. With nothing
armed there is **Arm**; with a window armed there is **Disarm** and **Re-arm**, which opens the dialog
pre-filled with the current window. **Re-plan now** re-runs the planner either way.

The dialog arms in two shapes: between two times, or starting now for a chosen duration. "Start now"
is anchored to the application's clock rather than the browser's, so a browser whose clock is off
cannot arm a window that begins in the past. Either way it previews the window, its length and roughly
how much energy it will move before anything is armed.

**Manual overrides** — for what the automation cannot know about: a car to charge tonight, a storm
the forecast missed, a sale to stop early. They are split the way the commands themselves are:

- **Charging from the grid** — the card beside selling, the other direction a remote control session
  can push the battery in. It hands the inverter back on its own, even if this application stops, and
  the work mode is left alone. While any charge runs — one started here, the night charge, balancing —
  the card says so above its fields: who started it, at what power, and until when.
- **Control** — the card under both, in three groups. **Work mode** — _Self use_, _Feed-in
  priority_, _Backup_. Persistent: it survives a restart, and a module may well move it again at its
  next run. The mode the inverter is already in is marked, and the mark moves the moment a change is
  accepted rather than on the next reading. A change made here also records which mode it moved away
  from, so the activity list can say what it replaced. **Battery balancing** (below). And last
  **Remote control** — _Exit remote control_, which ends whatever session is running, a sale, a charge
  or balancing alike. It is live only while a session is actually running, and the line above it says
  which of the two reasons it is off for.

Remote control needs the SolaX Cloud connection, and the cards say so plainly when it is not
configured. A sale is armed from the selling card.

Charging takes either shape: **for a time**, or **to a battery level**. Fill in _To SOC_ and the
session runs until the battery gets there, however long that takes — that is the cloud's
`soc_target_control_mode`, which carries no timer and which the inverter itself ends when the target
is met. The duration dims to show it is not being used, and the line under the fields spells out
which of the two will happen before anything is sent.

Exiting remote control while a sale is running cancels it through the selling module rather than
behind its back, so the armed window and the history stay in step with the inverter.

**Leaving remote control takes two commands, not one.** `exit_vpp_mode` is reported successful the
moment the cloud has queued it, and some inverters stop the session but stay in their remote-control
running state — _Normal Mode(R-n)_ in the SolaX app — until the documented *exit remote control*
transition actually runs. So the exit is sent as a one second, zero power session with
`nextMotion = 160` first, and the direct exit lands on top of it. One second at 0 W changes nothing
about the battery. Set `solax.cloud.exit-with-push-power: false` if your inverter leaves on the
direct exit alone.

English and Czech, light/dark/system theme, both remembered per browser. Everything the dashboard
shows is translated: module names and descriptions, configuration labels, planned actions, activity
headlines and their explanations, and every per-run outcome. Each of those travels as an English
sentence plus a translation key with its values, so the log files and the API keep reading in English
while the page renders in whichever language is selected — a message the dictionary does not know
falls back to the English the backend already rendered rather than showing a raw key.

### On a phone

The dashboard is one layout, not a separate mobile site, and it reshapes itself under about 760px.
The charts are the part that actually changes: each is drawn at the pixel width of its own card
rather than at a fixed 960 units scaled down, so the axis labels stay the size they were meant to be
instead of shrinking to a smudge, and the price and weather charts thin their gutters and label every
sixth hour rather than every third.

The timeline chart is the one that does not survive the width — a column of module names and a band
of hour-wide bars needs the room — so on a narrow screen it starts folded away behind **Show chart**
and the list beneath it, which says the same thing in words, is what the card opens on. The choice is
remembered per browser. Activity rows put the time and the action badge on one line with the sentence
underneath, rather than squeezing three columns into a phone's width.

A finger cannot hover, so on a touch screen a tooltip opens on a tap on the mark and closes on the
next tap. Swiping the page still scrolls it: the tooltip hangs off the tap, not off the touch.

**The dashboard installs as an app.** It ships a web manifest, an icon set and a service worker, so
a phone or a desktop browser can add it to the home screen and open it in its own window without the
browser's chrome. The **Install** button in the header opens the browser's own offer where there is
one, and where there is not it says why and what to do instead — which is the case worth reading,
because it is the usual one.

**On an iPhone this works over plain `http://` today.** Safari's _Share → Add to Home Screen_ has
never asked for a certificate, and with the `apple-mobile-web-app-capable` meta tag the shortcut
opens full screen with no browser around it — an app on the home screen, from
`http://192.168.x.y:8080` as it stands. It is the only browser on iOS that can do it. What it does
not get without https is the service worker, so an iPhone opened away from the home network shows
Safari's offline page rather than the dashboard saying the application is unreachable.

**Chrome is the one that insists on a secure context.** It installs from `https://` addresses and
from `localhost`, and from nothing else; over `http://192.168.x.y:8080` there is no install prompt,
no service worker, and its own reason — `not-from-secure-origin` — never reaches the person looking
at the page. _Add to Home screen_ still exists there and still makes a shortcut, but the shortcut
opens in a browser tab rather than as an app. Three ways round it, cheapest first:

1. **Allow the one address, per phone.** In Chrome or Edge open
   `chrome://flags/#unsafely-treat-insecure-origin-as-secure`, set it to _Enabled_, put
   `http://192.168.x.y:8080` in its box and restart the browser. Chrome on Android has the same
   flag. Nothing to set up on the Pi, half a minute per phone, and it has to be redone on each new
   one — which for a household of two or three phones is usually the whole job.
2. **Give the Pi a certificate every phone already trusts** — `deploy/https/` has the whole thing
   ready: a free DuckDNS name pointed at the Pi's address on your own network, and Caddy in front
   of the dashboard with a Let's Encrypt certificate issued over the DNS challenge, so nothing is
   port-forwarded and nothing is reachable from outside. Nothing to do per phone, now or when a new
   one arrives. About twenty minutes, once; [the steps are written out
   there](deploy/https/README.md).
3. **Tailscale**, if the Pi is on one: `tailscale serve` fronts the dashboard on the tailnet with a
   real `*.ts.net` certificate, and the phone reaches it from anywhere, home network or not.

A self-signed certificate — including `server.ssl.*` pointed at one you generated yourself — is not
enough on its own. Clicking through the warning leaves the origin with a certificate error, and no
service worker will register there. A certificate from a local authority that each phone has been
told to trust (mkcert and its like) does work, at the cost of installing that authority on every
phone — the same per-device tax as the flag, for more effort.

What the service worker caches is the page, its stylesheet, its scripts and its icons — never the
data. Prices, the battery and what the modules are about to do are only worth having live, so an
installed app opened away from the home network comes up as itself and says the application is
unreachable, rather than as the browser's offline page. Bump `CACHE` in `static/sw.js` when you
change a file it precaches.

Set `dashboard.allow-control: false` if the dashboard is reachable from outside your local network —
the application has no authentication of its own.

---

## Logs

Appenders live in `src/main/resources/log4j2.xml`. Despite the name that file is **logback**
configuration and always has been - logback is the backend Spring Boot brings in by default.

Every module run reads as a short report rather than a stream of unrelated lines:

```
══════════════════════════════════════════════════════════════════════════════
[discharge] Evaluating today's prices for a selling window
──────────────────────────────────────────────────────────────────────────────
[discharge]   · Battery .................. 87 % now, planning for 100 % (need 50 % to sell, reserve 40 %)
[discharge]   · Search window ............ 15:00 - 23:45 (36 intervals)
[discharge]   · Peak ..................... 19:15 at 8.85 CZK/kWh
[discharge]   · Peak plateau ............. 18:30-20:30 (120 min within 1.0 CZK/kWh)
[discharge]   · Usable energy ............ 6.4 kWh -> 6 interval(s) at 3950 W
[discharge]   · Selling into
[discharge]       | 19:15-19:30   8.85 CZK/kWh  (high, rank 96/96)
[discharge]       | 19:30-19:45   8.79 CZK/kWh  (high, rank 95/96)
[discharge]       | 19:45-20:00   8.61 CZK/kWh  (high, rank 93/96)
[discharge]       | 20:00-20:15   8.40 CZK/kWh  (high, rank 91/96)
[discharge]   ✓ Armed 19:15 - 20:45 at 3950 W (starts in 3 h 42 min)
```

Every line carries its module id, so one module can be grepped out of a shared file even when runs
interleave. Console output is coloured; `logs/app.log` rotates daily and is kept for 30 days.

---

## Prerequisites

**Hardware** — Raspberry Pi 4B, a Solax X3-Hybrid-G4 with Modbus TCP reachable (an RS485→Ethernet
converter in front of it is fine), optionally a GPIO switch on BCM 17 reporting which supply the house
is on.

**Accounts**

1. [Meteosource](https://www.meteosource.com/) — free tier is enough.
2. [SolaX developer portal](https://developer.solaxcloud.com/doc) — create an application, note the
   client id/secret, your account's API URL, and the inverter serial number. Required for selling.
3. [spotovaelektrina.cz](https://spotovaelektrina.cz/api) — no key needed.

**Software** — Java 21, Maven 3.x, and network access from the Pi to the inverter.

---

## Install

```bash
git clone https://github.com/Firestone82/SolaxAutomation.git
cd SolaxAutomation
./mvnw clean package
java -jar target/SolaxAutomation-*.jar
```

Configuration lives in `src/main/resources/application.yml`, which documents every value. Keep secrets
out of it — anything can be overridden from the environment:

```bash
export SOLAX_CLOUD_CLIENT_ID=...
export SOLAX_CLOUD_CLIENT_SECRET=...
export METEOSOURCE_KEY=...
```

As a service:

```ini
[Unit]
Description=Solax Automation
After=network-online.target
Wants=network-online.target

[Service]
User=pi
WorkingDirectory=/home/pi/SolaxAutomation
ExecStart=/usr/bin/java -jar /home/pi/SolaxAutomation/target/SolaxAutomation-0.0.1-SNAPSHOT.jar
EnvironmentFile=/home/pi/SolaxAutomation/.env
Restart=on-failure
RestartSec=30

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now solax-automation
```

### Running away from the installation

```bash
java -jar target/SolaxAutomation-*.jar --solax.modbus.enabled=false --raspberry.enabled=false
```

Modbus and the GPIO switch are stubbed out, the dashboard still serves real prices and weather.

---

## Safety limits

These exist because the far end is real hardware, and they stop the application rather than let a bug
run away with it:

- **Write budget** — at most `solax.modbus.max-writes-per-window` writes per `write-window` (10 per
  12 h by default). Exceeding it shuts the application down.
- **Failure limit** — `max-consecutive-failures` consecutive Modbus errors shut it down.
- **Request spacing** — all Modbus requests are serialised onto one thread and spaced by
  `request-delay`; the inverter drops requests that arrive faster.
- **Connection recycling** — the inverter closes idle connections itself, so a connection idle longer
  than `idle-timeout` (30 s) is re-opened deliberately, and any request that still hits a closed
  socket is retried once on a fresh connection. Reconnecting costs about 20 ms.
- **Fail fast** — an inverter that cannot be reached at start-up stops the application, so a
  supervisor restarts it. Set `solax.modbus.fail-fast: false` to keep the dashboard up instead.

---

## Project layout

```
core/            module federation: AutomationModule, registry, timeline, logging
integration/
  solax/         InverterGateway + modbus/ (registers, queue, client) + cloud/ (OpenAPI)
  ote/           quarter-hour spot prices
  meteosource/   weather forecast
  raspberry/     GPIO supply switch
module/
  battery/ export/ weather/ discharge/     one package per automation
statistics/      the daily record behind the dashboard's statistics section
dashboard/       REST API + DTOs; the SPA lives in resources/static
                 (index.html, assets/, plus manifest.webmanifest, sw.js and icons/ for the installable app)
```

```
deploy/https/    Caddy and systemd units that put the dashboard behind a certificate the
                 phones trust, so Chrome will install it - see its own README
```

## License & disclaimer

Provided "as-is" for personal use. No warranty. Adapt it for your needs, but please do not
redistribute without permission.
