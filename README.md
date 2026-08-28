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

---

## Modules

Every automation is an independent module: its own package, its own configuration section, its own
widget on the dashboard, and its own `enabled` flag. Nothing enumerates them by name — dropping a new
`AutomationModule` bean on the classpath is enough for the registry, the dashboard and the timeline to
pick it up. Deleting a module means deleting its package.

| Module | Config prefix | What it does |
|---|---|---|
| Battery charge guard | `automation.battery` | Checks the battery against charge targets through the day: switches to self use when behind schedule, and to feed-in priority when comfortably ahead of it so surplus production is sold rather than wasted. |
| Export limit | `automation.export` | Closes the export limit while the spot price is too low to be worth selling, and throttles it around midday on dull days. Runs every quarter of an hour, matching how often the price changes. |
| Weather work mode | `automation.weather` | Chooses between feed-in priority and self use from the forecast, and moves to backup ahead of a thunderstorm. |
| Grid selling | `automation.discharge` | Finds the most valuable quarter-hour window of the day and sells the battery into it through remote control. |

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

`discharge-power` is not configured separately — it is `automation.export.power.maximum`, since a sale
can never leave the installation faster than that ceiling allows anyway.

At the window's start a remote control session is opened for exactly the window's length. A guard
checks the battery every `guard-interval` and ends the session early once the reserve is reached. If
the export limit happens to be closed at that moment, the run logs a warning rather than quietly
trickling energy out at the limit.

The placement rules are covered by `DischargeWindowPlannerTest` — they decide how much money the
battery earns, so they are pinned down rather than only observed in production logs.

---

## Dashboard

`http://<host>:8080/` — no build step, no CDN, works offline on the Pi.

**Overview** — live battery/PV/grid/load, work mode, current price, and the Raspberry Pi's
connection switch (HIGH is the metered grid, LOW the second supply — the tile says which, and says
plainly when it is the off-Pi stub rather than a pin); the day's 96 quarter-hour prices
with the armed selling window highlighted; the weather quality curve with the thresholds the modules
compare against; a 24 hour timeline of what every module intends to do; and recent activity.

The timeline shows every run each module has coming up, not just the next one. Two modules are left
out of it: the export limit is re-checked every quarter of an hour and the weather work mode every
hour, nearly always with the same outcome, and 92 identical rows bury the handful that say something.
Their own widget on the modules page always shows the full schedule.

The list beneath the chart pages in tens, so a long plan stays one screen tall. The page you are on
survives a refresh.

Recent activity survives a restart: the newest `timeline.persisted-events` entries are kept in a small
JSON file (`data/timeline.json` by default). It is a convenience for the dashboard, not an audit
log - the rolling log files remain the durable record.

All three charts are hoverable. A price interval reports its exact price in both currencies and how it
compares with that day's average; a forecast hour reports its
quality, the band that quality falls into, cloud cover and temperature; a planned action reports the
module behind it, its kind, the window, how long it lasts, how far away it is and whether it is
committed or a routine check. The weather quality formula sits behind the ⓘ button in that card's
header rather than taking up space on every visit, and the two thresholds are named in the legend
instead of being written across the plot.

**Modules** — two columns of widgets, each split into three panels: status (what the module is and how
its last run went), configuration (every documented value it reads) and plan (what it will do next).
A switch disables a module until the next restart. Every card is exactly as tall as its own content:
the page lays them out in **columns** rather than grid rows, so a module with fifteen configuration
values does not set the height of a row and leave the card beside it half empty, and nothing is
clipped or scrolled. The status line appears once the module has something to report — a module that
has not run yet says nothing rather than "Idle · not run yet", and a module mid-run says what it is
doing rather than leaving the previous line up.

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

**Quick actions** — the card beside selling, for what the automation cannot know about: a car to
charge tonight, a storm the forecast missed, a sale to stop early. It is split the way the commands
themselves are:

- **Work mode** — _Self use_, _Feed-in priority_, _Backup_. Persistent: it survives a restart, and a
  module may well move it again at its next run. The mode the inverter is already in is marked.
- **Remote control** — _Charge from grid_, _Sell to grid_ (the same dialog the selling card opens) and
  _Exit remote control_. These hand the inverter back on their own, even if this application stops;
  the work mode is left alone. They need the SolaX Cloud connection, and say so plainly when it is
  not configured.

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

English and Czech, light/dark/system theme, both remembered per browser. Module names, descriptions,
configuration labels, planned actions, history entries and lifecycle statuses are translated;
per-run outcome sentences stay in English on purpose, because they are the same wording the log file
contains.

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
dashboard/       REST API + DTOs; the SPA lives in resources/static
```

## License & disclaimer

Provided "as-is" for personal use. No warranty. Adapt it for your needs, but please do not
redistribute without permission.
