/* ============================================================================
   Dashboard application.
   Keeps one state object, refreshes it on a timer, and re-renders whatever
   changed. Language and theme live in localStorage so a reload keeps them.
   ========================================================================= */

(() => {

    const STORAGE_LANGUAGE = 'solax.language';
    const STORAGE_THEME = 'solax.theme';
    const STORAGE_PAGE = 'solax.page';
    const STORAGE_HISTORY_ROWS = 'solax.historyRows';
    const STORAGE_HISTORY_DAY = 'solax.historyDay';
    const STORAGE_HISTORY_CHANGES = 'solax.historyChangesOnly';
    const STORAGE_TIMELINE_RANGE = 'solax.timelineRange';
    const STORAGE_WEATHER_RANGE = 'solax.weatherRange';
    const STORAGE_TIMELINE_CHART = 'solax.timelineChart';
    const STORAGE_STATS_DAYS = 'solax.statisticsDays';

    const state = {
        config: {refreshSeconds: 30, allowControl: true, currency: 'CZK', defaultLanguage: 'en', defaultTheme: 'system'},
        overview: null,
        prices: null,
        weather: null,
        timeline: null,
        selling: null,
        balance: null,
        statistics: null,

        // What the statistics section shows: the last 7, 30 or 90 days, or a calendar month.
        statsRange: '30',

        // The month browsed to, 'YYYY-MM', while statsRange is 'month'.
        statsMonth: null,

        // Which day the price and the weather cards show, as days from today: 0 is today,
        // 1 tomorrow, -1 yesterday. Days back are what the application recorded as they
        // went by, so how far back either can go is a question for the data.
        priceDay: 0,
        weatherDay: 0,

        page: 'overview',
        theme: 'system',
        historyRows: 10,

        // Which day of the retained history the activity list shows, and whether the
        // quarter-hourly checks that changed nothing are in it.
        historyDay: 'today',
        historyChangesOnly: false,

        // 'ahead' looks forward from this hour, 'day' shows today from midnight - the same
        // choice on both time charts, so switching one does not have to be relearned on the other.
        timelineRange: 'ahead',
        weatherRange: 'ahead'
    };

    const el = id => document.getElementById(id);
    const t = (key, params) => I18N.t(key, params);

    /*
       The browser's offer to install the dashboard as an app, held from the moment it is
       made until the button in the header is pressed. Caught here, at parse time, because
       it can be fired before init has finished starting up.
    */
    let deferredInstall = null;

    window.addEventListener('beforeinstallprompt', event => {
        // Keeps the browser from showing its own bar, and keeps the event usable later.
        event.preventDefault();
        deferredInstall = event;
    });

    /** Backend sentence: translated when we have a key for it, English otherwise. */
    const msg = entry => I18N.message(entry.messageKey, entry.params, entry.summary);

    /** The sentence under a backend message, translated the same way as the message itself. */
    const detailOf = entry => I18N.message(entry.detailKey, entry.detailParams, entry.detail);

    /** Module name/description, translated when the dashboard knows the module. */
    const moduleText = (id, field, fallback) => I18N.message(`module.${id}.${field}`, null, fallback);

    /** Timeline bar colours, mirrored from charts.js so tooltips match the marks. */
    const ACTION_COLOURS = {
        WORK_MODE_CHANGE: 'var(--accent)',
        GRID_SELL: 'var(--sell)',
        GRID_CHARGE: 'var(--charge)',
        EXPORT_LIMIT: 'var(--warning)',
        REMOTE_CONTROL_EXIT: 'var(--text-faint)',
        GPIO_STATE_CHANGE: 'var(--text-faint)',
        CHECK: 'var(--border-strong)'
    };

    /* ------------------------------------------------------------ formatting */

    const locale = () => I18N.language === 'cs' ? 'cs-CZ' : 'en-GB';

    function formatNumber(value, digits = 0) {
        if (value === null || value === undefined || Number.isNaN(value)) {
            return '—';
        }

        return value.toLocaleString(locale(), {
            minimumFractionDigits: digits,
            maximumFractionDigits: digits
        });
    }

    /** Always returns {value, unit} so callers can render the unit separately. */
    function formatPower(watts) {
        if (watts === null || watts === undefined) {
            return {value: '—', unit: null};
        }

        return Math.abs(watts) >= 1000
            ? {value: formatNumber(watts / 1000, 2), unit: 'kW'}
            : {value: formatNumber(watts, 0), unit: 'W'};
    }

    function formatDateTime(value) {
        if (!value) {
            return '—';
        }

        const date = new Date(value);
        const today = new Date();
        const sameDay = date.toDateString() === today.toDateString();
        const time = date.toLocaleTimeString(locale(), {hour: '2-digit', minute: '2-digit'});

        if (sameDay) {
            return time;
        }

        return date.toLocaleDateString(locale(), {day: 'numeric', month: 'short'}) + ' ' + time;
    }

    /** End of a 15 minute interval, for the price tooltip's time range. */
    function endOfSlot(point) {
        const minutes = point.hour * 60 + point.minute + 15;
        return `${String(Math.floor(minutes / 60) % 24).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
    }

    /** How this interval compares with its own day's average, e.g. "+18 %". */
    function priceVersusAverage(point, summary) {
        if (!summary || !summary.average) {
            return null;
        }

        const percent = Math.round(((point.czkPerKwh - summary.average) / Math.abs(summary.average)) * 100);
        return (percent > 0 ? '+' : '') + percent + ' %';
    }

    /** Human duration between two ISO timestamps, or null for instant actions. */
    function durationBetween(from, to) {
        if (!to) {
            return null;
        }

        return humanMinutes(Math.round((new Date(to) - new Date(from)) / 60000));
    }

    /** "in 3 h 12 min", "now", or "2 h ago". */
    function relativeTo(when) {
        const minutes = Math.round((new Date(when) - Date.now()) / 60000);

        if (Math.abs(minutes) < 1) {
            return t('common.now');
        }

        return minutes > 0
            ? t('tooltip.inFuture', {duration: humanMinutes(minutes)})
            : t('tooltip.inPast', {duration: humanMinutes(-minutes)});
    }

    function humanMinutes(minutes) {
        if (minutes < 60) {
            return t('tooltip.minutes', {count: minutes});
        }

        const hours = Math.floor(minutes / 60);
        const rest = minutes % 60;

        return rest === 0
            ? t('tooltip.hours', {count: hours})
            : t('tooltip.hoursMinutes', {hours: hours, minutes: rest});
    }

    /** Time of day only, for the selling window headline. */
    function formatTimeOnly(value) {
        if (!value) {
            return '—';
        }

        return new Date(value).toLocaleTimeString(locale(), {hour: '2-digit', minute: '2-digit'});
    }

    /** "starts in 5 h 23 min", or "started 12 min ago" once the window has opened. */
    function relativeToStart(from) {
        const minutes = Math.round((new Date(from) - Date.now()) / 60000);

        return minutes >= 0
            ? t('selling.startsIn', {duration: humanMinutes(minutes)})
            : t('selling.startedAgo', {duration: humanMinutes(-minutes)});
    }

    const DAY_MS = 24 * 3600 * 1000;

    function startOfHour(date) {
        const hour = new Date(date.getTime());
        hour.setMinutes(0, 0, 0);
        return hour;
    }

    function startOfDay(date) {
        const day = new Date(date.getTime());
        day.setHours(0, 0, 0, 0);
        return day;
    }

    /** Value for an <input type="datetime-local">, which needs local time without a zone. */
    function toInputValue(date) {
        const pad = n => String(n).padStart(2, '0');
        return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
            + `T${pad(date.getHours())}:${pad(date.getMinutes())}`;
    }

    /* ---------------------------------------------------------------- theme */

    function resolveTheme(theme) {
        if (theme === 'system') {
            return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
        }

        return theme;
    }

    function applyTheme(theme) {
        state.theme = theme;
        document.documentElement.setAttribute('data-theme', resolveTheme(theme));
        el('theme-icon').textContent = theme === 'system' ? '◐' : (theme === 'dark' ? '☾' : '☀');
        el('theme-toggle').title = theme;
        localStorage.setItem(STORAGE_THEME, theme);
    }

    function cycleTheme() {
        const order = ['system', 'light', 'dark'];
        applyTheme(order[(order.indexOf(state.theme) + 1) % order.length]);
    }

    /* --------------------------------------------------------------- routing */

    /**
     * The pages there are: control (kept as 'overview' so a stored choice still opens it),
     * statistics and history. A stored page that no longer exists - the old modules page -
     * opens the control page.
     */
    const PAGES = ['overview', 'statistics', 'history'];

    function showPage(requested) {
        const page = PAGES.includes(requested) ? requested : 'overview';

        state.page = page;
        localStorage.setItem(STORAGE_PAGE, page);

        document.querySelectorAll('.tab').forEach(tab =>
            tab.classList.toggle('is-active', tab.dataset.page === page));

        PAGES.forEach(id => {
            el('page-' + id).hidden = id !== page;
        });

        // A chart drawn while its page was hidden had no width to measure, so it was drawn
        // at the fallback size. Coming back to the page is when its real width exists, and
        // every page has charts of its own.
        if (state.overview) {
            renderCharts();
        }
    }

    /* ----------------------------------------------------------- chart sizing */

    /**
     * Below this the timeline chart is folded away behind a button: 150 units of module
     * name and a band of hour-wide bars have nowhere to go on a phone, and the list under
     * the chart already says the same thing in words.
     */
    const COMPACT_QUERY = window.matchMedia('(max-width: 760px)');

    /**
     * Where the header runs out of room for four controls on one line. The language picker
     * is the widest of them and the only one that can give anything back, so below this it
     * shows the language code instead of the language's name.
     */
    const NARROW_QUERY = window.matchMedia('(max-width: 480px)');

    const isCompact = () => COMPACT_QUERY.matches;

    function renderLanguageControl() {
        const select = el('language');

        [...select.options].forEach(option => {
            // The full name is kept the first time round, before anything shortens it.
            option.dataset.full = option.dataset.full || option.textContent;
            option.textContent = NARROW_QUERY.matches ? option.value.toUpperCase() : option.dataset.full;
        });
    }

    /** Whether the timeline chart is asked for. Wide screens always show it. */
    let timelineChartShown = localStorage.getItem(STORAGE_TIMELINE_CHART) === 'yes';

    function timelineChartVisible() {
        return !isCompact() || timelineChartShown;
    }

    /**
     * Charts are drawn at the pixel size of their container, so anything that changes that
     * size - a rotation, a resized window, the browser chrome sliding away - has to redraw
     * them. Coalesced into one frame, because a drag fires this continuously.
     */
    let resizeHandle = null;

    function onViewportChange() {
        clearTimeout(resizeHandle);

        resizeHandle = setTimeout(() => {
            if (state.overview) {
                renderCharts();
            }

            renderTimelineChartToggle();
            renderLanguageControl();
        }, 150);
    }

    /** Just the charts - the lists and cards around them do not depend on the width. */
    function renderCharts() {
        Charts.hideTooltip();
        renderPrices();
        renderWeather();
        renderWorkMode();
        renderTimeline();
        renderStatisticsCharts();
    }

    /**
     * The button that folds the timeline chart away, and the chart's own visibility. On a
     * wide screen there is nothing to fold, so the button is out of the layout and the
     * chart is always drawn.
     */
    function renderTimelineChartToggle() {
        const button = el('timeline-chart-toggle');
        const chart = el('timeline-chart');
        const showing = timelineChartVisible();

        chart.hidden = !showing;

        const key = showing ? 'timeline.hideChart' : 'timeline.showChart';
        button.dataset.i18n = key;
        button.textContent = t(key);
        button.setAttribute('aria-expanded', String(showing));
    }

    function toggleTimelineChart() {
        timelineChartShown = !timelineChartVisible();
        localStorage.setItem(STORAGE_TIMELINE_CHART, timelineChartShown ? 'yes' : 'no');

        renderTimelineChartToggle();

        if (timelineChartVisible()) {
            renderTimeline();
        }
    }

    /* --------------------------------------------------------------- render */

    function renderStats() {
        const overview = state.overview;
        const container = el('stat-tiles');

        if (!overview) {
            container.replaceChildren();
            return;
        }

        const tiles = [];

        /**
         * One tile. {@code notes} is a list rather than a sentence: two facts joined by a
         * separator read as one long line and are scanned as neither, where a line each is
         * read at a glance. {@code bar} is a percentage, or {@code null} where the value has
         * no ceiling worth drawing it against.
         */
        const push = (label, value, unit, notes, bar, barColour = null) =>
            tiles.push({label, value, unit, notes: [].concat(notes || []).filter(Boolean), bar, barColour});

        /** Where a value has a known maximum, how full it is - clamped, never over 100. */
        const share = (value, maximum) => {
            if (value === null || value === undefined || !maximum) {
                return null;
            }

            return Math.max(0, Math.min(100, (value / maximum) * 100));
        };

        push(
            t('stat.battery'),
            formatNumber(overview.batterySoc, 0),
            '%',
            [
                overview.batteryRemainingKwh === null || overview.batteryRemainingKwh === undefined
                    ? null
                    : t('stat.batteryStored', {remaining: formatNumber(overview.batteryRemainingKwh, 1)}),
                overview.batterySoh === null || overview.batterySoh === undefined
                    ? null
                    : t('stat.batteryHealth', {soh: formatNumber(overview.batterySoh, 0)})
            ],
            overview.batterySoc
        );

        const pv = formatPower(overview.pvPower);
        const pvPeak = state.config.pvPeak;

        push(
            t('stat.pv'),
            pv.value,
            pv.unit,
            // What the bar is measured against, said in words - a bar with no scale is a
            // decoration.
            pvPeak && overview.pvPower !== null && overview.pvPower !== undefined
                ? t('stat.ofPeak', {maximum: formatNumber(pvPeak, 0)})
                : null,
            share(overview.pvPower, pvPeak),
            // Not the battery's traffic light: little production at eight in the morning is
            // the sun's doing, not a problem to colour red.
            'var(--accent)'
        );

        if (overview.gridPower !== null && overview.gridPower !== undefined) {
            const grid = formatPower(Math.abs(overview.gridPower));

            // At zero neither direction is true, so saying one of them would be wrong.
            const direction = overview.gridPower === 0
                ? null
                : (overview.gridPower > 0 ? t('stat.gridExport') : t('stat.gridImport'));

            // Only the export has a ceiling to be measured against: what comes the other way
            // is bounded by the house's appetite, not by anything this application knows.
            const exporting = overview.gridPower > 0;
            const limit = overview.exportLimit;

            push(
                t('stat.grid'),
                grid.value,
                grid.unit,
                [
                    direction,
                    exporting && limit ? t('stat.ofLimit', {maximum: formatNumber(limit, 0)}) : null
                ],
                exporting ? share(overview.gridPower, limit) : null,
                'var(--accent)'
            );
        }

        if (overview.loadPower !== null && overview.loadPower !== undefined) {
            const load = formatPower(overview.loadPower);
            push(t('stat.load'), load.value, load.unit, null, null);
        }

        push(
            t('stat.mode'),
            overview.workMode ? t('mode.' + overview.workMode) : t('common.unknown'),
            null,
            overview.remoteControlActive ? t('stat.remoteControl') : deviceStatus(overview),
            null
        );

        push(
            t('stat.price'),
            formatNumber(overview.currentPrice, 2),
            `${state.config.currency}/kWh`,
            overview.currentPriceLevel ? t('prices.' + overview.currentPriceLevel) : null,
            null
        );

        if (overview.dailyYield !== null && overview.dailyYield !== undefined) {
            push(t('stat.today'), formatNumber(overview.dailyYield, 1), 'kWh', [
                t('stat.todayExport', {export: formatNumber(overview.dailyExport ?? 0, 1)}),
                t('stat.todayImport', {import: formatNumber(overview.dailyImport ?? 0, 1)})
            ], null);
        }

        if (overview.exportLimit !== null && overview.exportLimit !== undefined) {
            push(t('stat.exportLimit'), formatNumber(overview.exportLimit, 0), 'W', null, null);
        }

        // The switch decides whether exporting is even billed, so what the export module
        // does all day only makes sense next to it.
        if (overview.connectionSwitch) {
            const high = overview.connectionSwitch === 'HIGH';

            push(
                t('stat.supply'),
                t(high ? 'stat.supplyMetered' : 'stat.supplySecond'),
                null,
                overview.connectionSwitchSimulated
                    ? t('stat.supplySimulated')
                    : t('stat.supplyPin', {state: overview.connectionSwitch}),
                null
            );
        }

        container.replaceChildren(...tiles.map(tileNode));
    }

    /**
     * One stat tile: label, value with its unit, a line per note, and a bar where the value
     * has a ceiling. Shared by the live tiles and the statistics section, so the two read as
     * one system.
     */
    function tileNode(tile) {
        const node = document.createElement('div');
        node.className = 'stat';

        const label = document.createElement('span');
        label.className = 'stat-label';
        label.textContent = tile.label;
        node.appendChild(label);

        const value = document.createElement('span');
        value.className = 'stat-value';
        value.textContent = tile.value;

        if (tile.unit) {
            const unit = document.createElement('span');
            unit.className = 'unit';
            unit.textContent = tile.unit;
            value.appendChild(unit);
        }

        node.appendChild(value);

        tile.notes.forEach(text => {
            const note = document.createElement('span');
            note.className = 'stat-note';
            note.textContent = text;
            node.appendChild(note);
        });

        if (typeof tile.bar === 'number') {
            const bar = document.createElement('div');
            bar.className = 'stat-bar';

            const fill = document.createElement('i');
            fill.style.width = Math.max(0, Math.min(100, tile.bar)) + '%';
            // A tile that names its own colour means the bar is a proportion. Without
            // one it is a level, and a level low enough is worth a warning.
            fill.style.background = tile.barColour
                || (tile.bar < 25 ? 'var(--danger)' : (tile.bar < 50 ? 'var(--warning)' : 'var(--success)'));

            bar.appendChild(fill);
            node.appendChild(bar);
        }

        return node;
    }

    /**
     * The inverter's running status. Translated through its numeric code where the dashboard
     * knows it, and left as the English label the backend derived otherwise - the vendor's
     * list is long and mostly made of states nobody ever sees.
     */
    function deviceStatus(overview) {
        return I18N.message('device.' + overview.deviceStatusCode, null, overview.deviceStatus);
    }

    /* ------------------------------------------------------------ day browser */

    /** Midnight at the start of the day {@code offset} days from today, in local time. */
    function dayAt(offset) {
        const day = startOfDay(new Date());
        // By the calendar rather than by milliseconds, so a clock change does not shift it.
        day.setDate(day.getDate() + offset);
        return day;
    }

    /** 'YYYY-MM-DD' of a date, in local time - the shape the backend names a day by. */
    function dateKey(date) {
        return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`;
    }

    /** How many days from today an ISO date is: 0 today, -1 yesterday. */
    function dayOffsetOf(isoDate) {
        return Math.round((new Date(isoDate + 'T00:00:00') - startOfDay(new Date())) / DAY_MS);
    }

    /** "Today", "Yesterday", or the date with its weekday once it is further away than that. */
    function dayOffsetLabel(offset) {
        if (offset === 0) {
            return t('day.today');
        }

        if (offset === 1) {
            return t('day.tomorrow');
        }

        if (offset === -1) {
            return t('day.yesterday');
        }

        return dayAt(offset).toLocaleDateString(locale(), {weekday: 'short', day: 'numeric', month: 'short'});
    }

    const clampDay = (offset, bounds) => Math.min(bounds.max, Math.max(bounds.min, offset));

    /**
     * The day browser in a card header: back a day, the day's name, forward a day. The name
     * is itself a button that comes back to today, and rests while today is what is shown.
     * The arrows stop at the edges of what there is to show.
     */
    function renderDayNav(id, offset, bounds) {
        const nav = el(id);

        nav.querySelector('[data-step="-1"]').disabled = offset <= bounds.min;
        nav.querySelector('[data-step="1"]').disabled = offset >= bounds.max;

        const label = nav.querySelector('.day-nav-label');
        label.textContent = dayOffsetLabel(offset);
        label.disabled = offset === 0;

        // The arrows carry no words of their own, so a screen reader gets them in the page's language.
        nav.querySelector('[data-step="-1"]').setAttribute('aria-label', t('day.previous'));
        nav.querySelector('[data-step="1"]').setAttribute('aria-label', t('day.next'));
    }

    /** Wires a day browser: the arrows step, the name in the middle comes back to today. */
    function bindDayNav(id, onChange) {
        el(id).querySelectorAll('[data-step]').forEach(button =>
            button.addEventListener('click', () => onChange(Number(button.dataset.step))));
    }

    /** How far the price card can browse: back over the days on record, and on to tomorrow. */
    function priceDayBounds() {
        const history = state.prices?.history || [];
        const oldest = history.length ? dayOffsetOf(history[0].date) : 0;

        // Tomorrow is always reachable, so the card can say when it will be published.
        return {min: Math.min(0, oldest), max: 1};
    }

    function setPriceDay(step) {
        const bounds = priceDayBounds();
        state.priceDay = step === 0 ? 0 : clampDay(state.priceDay + step, bounds);
        renderPrices();
    }

    /** Points for the day the browser is on, or null when there are none for it. */
    function selectedPricePoints() {
        const prices = state.prices;

        if (!prices) {
            return null;
        }

        if (state.priceDay === 1) {
            return prices.tomorrowPublished ? prices.tomorrow : null;
        }

        if (state.priceDay < 0) {
            const key = dateKey(dayAt(state.priceDay));
            return (prices.history || []).find(day => day.date === key)?.points || null;
        }

        return prices.today;
    }

    /**
     * Aggregates for the day being shown.
     * Computed here rather than taken from the API, which only reports today's -
     * the summary and the tooltip comparison have to follow the day switch.
     */
    function priceSummary(points) {
        if (!points || points.length === 0) {
            return null;
        }

        const values = points.map(point => point.czkPerKwh);
        const maximum = Math.max(...values);
        const peak = points.find(point => point.czkPerKwh === maximum);

        return {
            average: values.reduce((sum, value) => sum + value, 0) / values.length,
            minimum: Math.min(...values),
            maximum: maximum,
            peakTime: peak ? peak.time : null
        };
    }

    function renderPrices() {
        const container = el('price-chart');
        const summaryLine = el('price-summary');
        const note = el('price-note');

        // The days on record change at midnight and after a restart, so the day shown may no
        // longer be one there is anything for.
        const bounds = priceDayBounds();
        state.priceDay = clampDay(state.priceDay, bounds);
        renderDayNav('price-day-nav', state.priceDay, bounds);

        const points = selectedPricePoints();

        renderPriceLegend();

        note.hidden = state.priceDay >= 0;
        note.textContent = state.priceDay < 0 ? t('prices.historyNote') : '';

        if (!points || points.length === 0) {
            const message = state.priceDay === 1 ? t('prices.noTomorrow')
                : state.priceDay < 0 ? t('prices.noneDay') : t('prices.none');
            Charts.priceChart(container, [], {emptyMessage: message});
            renderChargeLegend(false);
            summaryLine.textContent = '';
            return;
        }

        const prices = state.prices;
        const summary = priceSummary(points);
        const charged = withCharging(points, dayAt(state.priceDay));

        renderChargeLegend(charged.some(point => point.charging));

        Charts.priceChart(container, charged, {
            emptyMessage: t('prices.none'),
            sellThreshold: prices.sellMinPrice,
            tooltip: point => ({
                title: `${point.time} – ${endOfSlot(point)}`,
                subtitle: automationLabel(point),
                accent: point.selling ? 'var(--sell)'
                    : point.sellable ? 'var(--sell)'
                        : point.exportable === false ? 'var(--text-faint)' : `var(--price-${point.level || 'medium'})`,
                rows: [
                    [t('tooltip.price'), `${formatNumber(point.czkPerKwh, 2)} ${state.config.currency}/kWh`],
                    [t('tooltip.priceEur'), `${formatNumber(point.eurPerKwh, 4)} EUR/kWh`],
                    [t('tooltip.vsAverage'), priceVersusAverage(point, summary)]
                ],
                note: point.current ? t('tooltip.currentInterval') : automationNote(point)
            })
        });

        summaryLine.textContent = summary ? t('prices.summary', {
            avg: formatNumber(summary.average, 2),
            min: formatNumber(summary.minimum, 2),
            max: formatNumber(summary.maximum, 2),
            peak: summary.peakTime
        }) : '';
    }

    /**
     * The day's intervals with the ones the battery was charged from the grid in marked, so
     * what the charge was bought at reads off the chart the way the selling window does. The
     * stretches come from the activity history, which reaches two days back, and from the plan.
     */
    function withCharging(points, day) {
        const stretches = chargeStretches();

        if (!stretches.length) {
            return points;
        }

        return points.map(point => {
            const from = new Date(day.getTime());
            from.setHours(point.hour, point.minute, 0, 0);
            const to = new Date(from.getTime() + 15 * 60000);

            // An interval counts once a charge covers part of it: a charge started from the
            // dashboard at 14:07 bought the 14:00 interval too.
            const charging = stretches.some(stretch => stretch.from < to && stretch.to > from);
            return charging ? {...point, charging: true} : point;
        });
    }

    /** The charge swatch is only in the price legend while the day shown has a charge on it. */
    function renderChargeLegend(shown) {
        const item = el('legend-charging');

        item.hidden = !shown;

        if (shown) {
            el('legend-charging-text').textContent = t('workMode.charging');
        }
    }

    /** What the automation makes of this interval, in three words, for the tooltip. */
    function automationLabel(point) {
        if (point.charging) {
            return t('workMode.charging');
        }

        if (point.selling) {
            return t('prices.sellingWindow');
        }

        if (point.sellable) {
            return t('prices.sellable');
        }

        return point.exportable === false ? t('prices.notExportable') : null;
    }

    /** The same, spelled out with the numbers the rule is made of. */
    function automationNote(point) {
        const prices = state.prices;
        const currency = state.config.currency;

        if (point.exportable === false) {
            return t('prices.notExportableHint', {
                price: formatNumber(prices?.exportMinPrice, 2),
                currency: currency
            });
        }

        return point.sellable
            ? t('prices.sellableHint', {
                price: formatNumber(prices?.sellMinPrice, 2),
                currency: currency,
                from: prices?.sellFrom,
                to: prices?.sellTo
            })
            : null;
    }

    /**
     * The price legend names the two bands and the minimum price line - the marks on the
     * chart whose meaning is not self-evident, and the only place their values are shown.
     */
    function renderPriceLegend() {
        const prices = state.prices;
        const currency = state.config.currency;

        const known = value => value !== null && value !== undefined;

        const items = [
            ['legend-export-blocked', 'legend-export-blocked-text', known(prices?.exportMinPrice),
                () => t('prices.exportThreshold', {price: formatNumber(prices.exportMinPrice, 2), currency: currency})],
            ['legend-sellable', 'legend-sellable-text', known(prices?.sellMinPrice),
                () => t('prices.sellable')],
            ['legend-sell-threshold', 'legend-sell-threshold-text', known(prices?.sellMinPrice),
                () => t('prices.sellThreshold', {price: formatNumber(prices.sellMinPrice, 2), currency: currency})]
        ];

        items.forEach(([itemId, textId, shown, text]) => {
            el(itemId).hidden = !shown;

            if (shown) {
                el(textId).textContent = text();
            }
        });
    }

    /**
     * How far the weather card can browse: back to the oldest recorded hour, and forward to
     * tomorrow where the forecast already reaches into it.
     */
    function weatherDayBounds() {
        const hours = state.weather?.hours || [];

        if (!hours.length) {
            return {min: 0, max: 0};
        }

        const first = dayOffsetOf(hours[0].dateTime.slice(0, 10));
        const last = dayOffsetOf(hours[hours.length - 1].dateTime.slice(0, 10));

        return {min: Math.min(0, first), max: Math.min(1, Math.max(0, last))};
    }

    function setWeatherDay(step) {
        const bounds = weatherDayBounds();
        state.weatherDay = step === 0 ? 0 : clampDay(state.weatherDay + step, bounds);
        renderWeather();
    }

    /**
     * The window the forecast curve covers: the day ahead, today from midnight, or another
     * day whole - a day that is not today has no "from now" to look forward from.
     * <p>
     * The whole-day window is a fixed 24 hours whether or not there is a reading for every
     * hour of it - an application started at nine has nothing for the morning, and an axis
     * that quietly ends at the last reading it has would read as a shorter day rather than
     * as a missing one.
     */
    function weatherWindow() {
        const now = new Date();

        if (state.weatherDay !== 0 || state.weatherRange === 'day') {
            const start = dayAt(state.weatherDay);
            return {start: start, end: new Date(start.getTime() + DAY_MS)};
        }

        const hours = state.weather?.hours || [];
        const start = startOfHour(now);
        const last = hours.length ? new Date(hours[hours.length - 1].dateTime) : null;

        return {start: start, end: last && last > start ? last : new Date(start.getTime() + DAY_MS)};
    }

    /** The forecast hours inside that window. */
    function weatherPoints(range) {
        return (state.weather?.hours || []).filter(point => {
            const at = new Date(point.dateTime);
            return at >= range.start && at <= range.end;
        });
    }

    function renderWeather() {
        const weather = state.weather;

        const bounds = weatherDayBounds();
        state.weatherDay = clampDay(state.weatherDay, bounds);
        renderDayNav('weather-day-nav', state.weatherDay, bounds);
        renderWeatherRangeSwitch();

        const range = weatherWindow();
        const points = weatherPoints(range);

        el('weather-formula').textContent = weather?.qualityFormula || '';
        renderWeatherGap(range, points);

        // A day with nothing on it says why, and the reason differs by which side of today it is on.
        const emptyMessage = state.weatherDay < 0 ? t('weather.noneDay')
            : state.weatherDay > 0 ? t('weather.noneAhead') : t('weather.none');

        Charts.weatherChart(el('weather-chart'), points, {
            emptyMessage: emptyMessage,
            start: range.start,
            end: range.end,
            cloudyThreshold: weather?.cloudyThreshold,
            stormThreshold: weather?.stormThreshold,
            tooltip: point => ({
                title: formatDateTime(point.dateTime),
                subtitle: point.weather ? t('weatherType.' + point.weather, null) || prettyWeather(point.weather) : null,
                accent: qualityColour(point.quality, weather),
                rows: [
                    [t('tooltip.quality'), formatNumber(point.quality, 2)],
                    [t('tooltip.outlook'), qualityLabel(point.quality, weather)],
                    [t('tooltip.cloudCover'), `${formatNumber(point.cloudCover, 0)} %`],
                    [t('tooltip.temperature'), `${formatNumber(point.temperature, 1)} °C`]
                ],
                note: new Date(point.dateTime) < startOfHour(new Date()) ? t('weather.alreadyHappened') : null
            })
        });

        renderWeatherLegend(weather);
    }

    /**
     * The range switch rests while a day other than today is shown: that day is always drawn
     * whole, because "from now" is not on it. It comes back as it was when the browser does.
     */
    function renderWeatherRangeSwitch() {
        const browsing = state.weatherDay !== 0;

        document.querySelectorAll('#weather-range .segment').forEach(button => {
            button.disabled = browsing;
            button.classList.toggle('is-active', button.dataset.range === (browsing ? 'day' : state.weatherRange));
        });
    }

    /**
     * Says so when the window reaches back further than the recorded hours do.
     * <p>
     * The forecast only ever looks forward, so the hours behind us are the ones this
     * application saw go by. After a restart there is a gap at the start of the day, and an
     * empty stretch of axis with no explanation looks like a broken chart.
     */
    function renderWeatherGap(range, points) {
        const note = el('weather-gap');
        const first = points.length ? new Date(points[0].dateTime) : null;
        const wholeDay = state.weatherDay !== 0 || state.weatherRange === 'day';

        // An hour of slack: the window starts on the hour, so a first point exactly there
        // is a full window, not a gap. With no forecast at all - or nothing for a day browsed
        // back to - the chart says so itself, and saying it twice helps nobody. A day ahead
        // can only be short at its end, which the axis shows on its own.
        let text = null;

        if (wholeDay && state.weatherDay <= 0 && (state.weather?.hours?.length || 0) > 0) {
            if (first && first - range.start >= 3600 * 1000) {
                text = t('weather.recordedFrom', {time: formatTimeOnly(first.toISOString())});
            } else if (!first && state.weatherDay === 0) {
                text = t('weather.recordedNone');
            }
        }

        note.hidden = !text;
        note.textContent = text || '';
    }

    /**
     * The thresholds are not labelled inside the plot, so the legend has to name
     * them - it is the only place their values are shown.
     */
    function renderWeatherLegend(weather) {
        const legend = el('weather-legend');

        if (!weather) {
            legend.replaceChildren();
            return;
        }

        const items = [
            ['line-sunny', t('weather.sunnyBelow', {value: formatNumber(weather.cloudyThreshold, 1)})],
            ['line-storm', t('weather.stormAbove', {value: formatNumber(weather.stormThreshold, 1)})],
            ['swatch-quality', t('weather.qualitySeries')]
        ];

        legend.replaceChildren(...items.map(([className, label]) => {
            const item = document.createElement('li');

            const swatch = document.createElement('i');
            swatch.className = 'swatch ' + className;
            item.appendChild(swatch);

            const text = document.createElement('span');
            text.textContent = label;
            item.appendChild(text);

            return item;
        }));
    }

    /** Which band an hour falls into, for the tooltip. */
    function qualityLabel(quality, weather) {
        if (!weather) {
            return '';
        }

        if (quality > weather.stormThreshold) {
            return t('weather.storm');
        }

        return quality <= weather.cloudyThreshold ? t('weather.sunny') : t('weather.cloudy');
    }

    function qualityColour(quality, weather) {
        if (!weather) {
            return 'var(--accent)';
        }

        if (quality > weather.stormThreshold) {
            return 'var(--danger)';
        }

        return quality <= weather.cloudyThreshold ? 'var(--success)' : 'var(--warning)';
    }

    /** MOSTLY_CLOUDY -> Mostly cloudy, for weather types we have no translation for. */
    function prettyWeather(code) {
        const words = code.toLowerCase().replace(/_/g, ' ');
        return words.charAt(0).toUpperCase() + words.slice(1);
    }

    /**
     * Row labels for the timeline chart.
     * <p>
     * Not everything that writes history is a module: a quick action comes from the dashboard,
     * a supply change from the GPIO, and a work mode somebody set on the inverter itself from
     * the inverter. They have no widget to take a name from, so they are named here rather
     * than labelling a row with a bare id.
     */
    const NON_MODULE_SOURCES = ['dashboard', 'raspberry', 'inverter'];

    /**
     * Every source the history can name. The modules are named from the dictionary rather
     * than fetched - the dashboard no longer has a modules page to fetch them for - and an id
     * that turns up in the history without a translation is shown as it is.
     */
    const KNOWN_MODULES = ['battery', 'export', 'weather', 'discharge', 'morning', 'balance'];

    function moduleNames() {
        const ids = new Set([...KNOWN_MODULES, ...NON_MODULE_SOURCES]);

        [...(state.timeline?.history || []), ...(state.timeline?.planned || [])]
            .forEach(entry => ids.add(entry.moduleId));

        return Object.fromEntries([...ids].map(id => [id, moduleText(id, 'name', id)]));
    }

    /* ------------------------------------------------- work mode over time */

    /**
     * One colour per work mode, used by the band, its legend and the statistics' time-in-mode
     * bar alike.
     * <p>
     * Feed-in priority keeps the accent the timeline already paints work mode changes in,
     * self use is the quiet green of production staying home, backup the amber of something
     * being held in reserve, and manual the cyan the dashboard charges the battery in - the
     * selling purple is spoken for by the ribbon that runs on top of these, and a manual mode
     * under a sale would otherwise be purple on purple.
     */
    const MODE_COLOURS = {
        SELF_USE: 'var(--success)',
        FEED_IN_PRIORITY: 'var(--accent)',
        BACKUP: 'var(--warning)',
        MANUAL: 'var(--charge)'
    };

    /**
     * The two ends of the connection switch.
     * <p>
     * Deliberately outside the mode palette: the supply is a different question, and reusing
     * a mode's colour a band below it would read as the two saying the same thing. The
     * metered grid is the ordinary case and gets the quiet slate; the second supply, where
     * export never reaches the meter, is the one worth spotting from across the room.
     */
    const SUPPLY_COLOURS = {
        HIGH: 'var(--supply-metered)',
        LOW: 'var(--supply-second)'
    };

    /**
     * The day's work mode, rebuilt from the recorded changes.
     * <p>
     * Both kinds are in there: what the automation did, and what somebody did from the SolaX
     * app or the inverter's own panel - the backend reads the mode back on a timer and
     * records a change it did not make like any other, which is why an entry filed under the
     * inverter is one nothing here set.
     */
    function workModeSegments() {
        return changeSegments('WORK_MODE_CHANGE', state.overview?.workMode || null);
    }

    /**
     * The day's supply, rebuilt the same way from the connection switch.
     * <p>
     * HIGH is the metered grid connection, LOW the second supply, and which one the house is
     * on decides whether exporting is billed at all - so it belongs under the work mode
     * rather than in a chart of its own. Every physical throw of the switch is recorded as it
     * happens, which makes this band the more reliable of the two; the same limits still
     * apply to a throw made while the application was not running.
     * <p>
     * Nothing is drawn off a Raspberry Pi: the stub reports a constant HIGH, and a full day
     * of "metered grid" that nothing ever measured would be an invention.
     */
    function supplySegments() {
        const overview = state.overview;

        if (!overview?.connectionSwitch || overview.connectionSwitchSimulated) {
            return [];
        }

        return changeSegments('GPIO_STATE_CHANGE', overview.connectionSwitch);
    }

    /**
     * The day of one thing that holds a state and changes a handful of times, rebuilt from
     * the changes recorded for it and what it is right now.
     * <p>
     * Nothing samples any of this into a history, so a band is an inference: the value
     * between two recorded changes is whatever the earlier one set. That makes the boundaries
     * of what can be known worth drawing honestly rather than papering over:
     * <ul>
     *   <li>before the first change of the day the value is the one that change moved away
     *       from - which a change made from the dashboard does not record, so a day opening
     *       with one opens as an unknown stretch instead of a guess;</li>
     *   <li>with no change recorded at all it has not moved since before midnight, so the
     *       live value is drawn across the whole day and the tooltip says why;</li>
     *   <li>a change made while nothing was watching - over a restart, or with the source
     *       unreachable - still cannot be placed in time. When the live value disagrees with
     *       the last change recorded, the note under the chart says so rather than the band
     *       inventing a change.</li>
     * </ul>
     *
     * @param type action type of the history entries that record a change of this value
     * @param live what it is at this moment, or null when that is not known either
     */
    function changeSegments(type, live) {
        const now = new Date();
        const start = startOfDay(now);

        // The history reads newest first, which is what the activity list wants and the
        // opposite of what a band does.
        const changes = (state.timeline?.history || [])
            .filter(entry => entry.type === type && entry.success && entry.params?.to)
            .map(entry => ({at: new Date(entry.at), to: entry.params.to, from: entry.params.from || null, entry}))
            .filter(change => change.at >= start && change.at <= now)
            .sort((first, second) => first.at - second.at);

        if (changes.length === 0) {
            return live ? [{from: start, to: now, mode: live, changedBy: null, entry: null, held: true}] : [];
        }

        const segments = [{
            from: start,
            to: changes[0].at,
            mode: changes[0].from,
            changedBy: null,
            entry: null,
            held: false
        }];

        changes.forEach((change, index) => {
            segments.push({
                from: change.at,
                to: index + 1 < changes.length ? changes[index + 1].at : now,
                mode: change.to,
                changedBy: change.entry.moduleId,
                entry: change.entry,
                held: false
            });
        });

        // A change at midnight, or two in the same minute, leaves nothing to draw.
        return segments.filter(segment => segment.to > segment.from);
    }

    /**
     * The stretches of the day the battery was being sold into the grid.
     * <p>
     * A sale does not change the work mode - it runs through a remote control session and
     * hands the inverter back afterwards - so it belongs on top of the band rather than in
     * it. Two sources, because neither alone covers the day: the module forgets an armed
     * window the moment it ends, so a sale that already ran is only in the activity history,
     * where a start and an end marker bracket it; and a window that has not started yet is
     * only in the module, which is the one that knows when it intends to run.
     */
    function sellingOverlays() {
        const now = new Date();
        const start = startOfDay(now);
        const end = new Date(start.getTime() + DAY_MS);

        const marks = (state.timeline?.history || [])
            .filter(entry => entry.params?.sale)
            .map(entry => ({at: new Date(entry.at), phase: entry.params.sale, entry}))
            .sort((first, second) => first.at - second.at);

        const overlays = [];
        let open = null;

        marks.forEach(mark => {
            if (mark.phase === 'start') {
                open = mark;
                return;
            }

            if (open) {
                overlays.push({
                    from: open.at, to: mark.at, entry: open.entry, endedBy: mark.entry,
                    pending: false, running: false
                });
                open = null;
            }
        });

        const selling = state.selling;
        const armed = selling?.armed && selling.from && selling.to;

        // A start with no end yet is the sale running at this moment. The module knows when
        // that one is meant to stop, so where it can say so, it wins over "up to now".
        if (open && !(armed && selling.running)) {
            overlays.push({from: open.at, to: now, entry: open.entry, endedBy: null, pending: false, running: true});
        }

        if (armed) {
            overlays.push({
                from: new Date(selling.from),
                to: new Date(selling.to),
                entry: open ? open.entry : null,
                endedBy: null,
                // Nothing is being sold yet, so the ribbon is drawn hollow.
                pending: !selling.running,
                running: !!selling.running,
                watts: selling.watts,
                revenue: selling.expectedRevenue,
                manual: selling.manual
            });
        }

        // The band draws one day; a window may hang over either end of it.
        return overlays
            .map(overlay => ({
                ...overlay,
                from: new Date(Math.max(overlay.from, start)),
                to: new Date(Math.min(overlay.to, end))
            }))
            .filter(overlay => overlay.to > overlay.from);
    }

    /**
     * Every stretch the battery was charged from the grid in, as far back as the history goes,
     * and the ones still planned.
     * <p>
     * A charge is recorded where it starts. Where it stops is the earliest of what is known
     * about it: the end it was sent with ({@code until} - a timed charge, the night charge),
     * and the first entry after it that ends or replaces the session - an exit from the
     * dashboard or a module, the inverter watcher noticing that a charge to a target stopped,
     * a sale or another charge taking over. A charge with neither is still running while the
     * inverter says a session is; otherwise its end was never seen, and it is drawn up to the
     * next thing on record with a note saying so.
     */
    function chargeStretches() {
        const now = new Date();
        const history = [...(state.timeline?.history || [])]
            .sort((first, second) => new Date(first.at) - new Date(second.at));

        const endsSession = entry => entry.success && (entry.type === 'REMOTE_CONTROL_EXIT'
            || entry.type === 'GRID_CHARGE' || entry.params?.sale === 'start');

        const stretches = [];

        history.forEach((entry, index) => {
            if (entry.type !== 'GRID_CHARGE' || !entry.success) {
                return;
            }

            const from = new Date(entry.at);
            const params = entry.params || {};

            // Entries from before the end was recorded with the start still say how long a
            // timed charge was sent for.
            const planned = params.until ? new Date(params.until)
                : params.minutes ? new Date(from.getTime() + params.minutes * 60000) : null;

            const later = history.slice(index + 1);
            const closer = later.find(endsSession) || null;

            let to = planned;
            let endedBy = null;
            let endUnknown = false;

            if (closer && (!planned || new Date(closer.at) < planned)) {
                to = new Date(closer.at);
                endedBy = closer;
            }

            const open = !endedBy && (!planned || planned > now);
            const running = open && state.overview?.remoteControlActive !== false;

            if (!to) {
                if (running) {
                    to = now;
                } else {
                    endUnknown = true;
                    to = later.length ? new Date(later[0].at) : now;
                }
            }

            stretches.push({
                kind: 'charge', from, to, entry, endedBy, endUnknown,
                pending: false, running,
                watts: params.watts, targetSoc: params.targetSoc
            });
        });

        // A charge the night module intends to start. The one already running is on record
        // above, so only what is still ahead is taken from the plan.
        (state.timeline?.planned || [])
            .filter(entry => entry.type === 'GRID_CHARGE' && entry.to && new Date(entry.from) > now)
            .forEach(entry => stretches.push({
                kind: 'charge', from: new Date(entry.from), to: new Date(entry.to), entry: null, plannedEntry: entry,
                endedBy: null, endUnknown: false, pending: true, running: false,
                watts: entry.params?.watts, targetSoc: entry.params?.target, certain: entry.certain
            }));

        return stretches.filter(stretch => stretch.to > stretch.from);
    }

    /** The charges that fall on today, clipped to it, for the work mode band. */
    function chargeOverlays() {
        const start = startOfDay(new Date());
        const end = new Date(start.getTime() + DAY_MS);

        return chargeStretches()
            .map(stretch => ({
                ...stretch,
                from: new Date(Math.max(stretch.from, start)),
                to: new Date(Math.min(stretch.to, end))
            }))
            .filter(stretch => stretch.to > stretch.from);
    }

    function chargeTooltip(overlay) {
        const source = overlay.entry || overlay.plannedEntry;

        const status = overlay.pending ? t('workMode.chargePlanned')
            : overlay.running ? t('workMode.sellingRunning')
                : t('workMode.sellingDone');

        const note = overlay.endedBy ? msg(overlay.endedBy)
            : overlay.endUnknown ? t('workMode.chargeEndUnknown')
                : overlay.plannedEntry ? msg(overlay.plannedEntry)
                    : (overlay.entry ? detailOf(overlay.entry) : null);

        return {
            title: t('workMode.charging'),
            subtitle: source ? moduleText(source.moduleId, 'name', source.moduleId) : null,
            accent: 'var(--charge)',
            rows: [
                [t('tooltip.when'), `${formatTimeOnly(overlay.from.toISOString())} – ${formatTimeOnly(overlay.to.toISOString())}`],
                [t('tooltip.duration'), durationBetween(overlay.from.toISOString(), overlay.to.toISOString())],
                [t('selling.power'), hasValue(overlay.watts) ? formatNumber(overlay.watts, 0) : null],
                [t('workMode.chargeTarget'), hasValue(overlay.targetSoc) ? `${overlay.targetSoc} %` : null],
                [t('workMode.sellingState'), status]
            ],
            note: note
        };
    }

    /** The ribbon's tooltip, by what the ribbon is. */
    function overlayTooltip(overlay) {
        return overlay.kind === 'charge' ? chargeTooltip(overlay) : sellingTooltip(overlay);
    }

    function sellingTooltip(overlay) {
        const state_ = overlay.pending ? t('workMode.sellingArmed')
            : overlay.running ? t('workMode.sellingRunning')
                : t('workMode.sellingDone');

        return {
            title: t('workMode.selling'),
            subtitle: overlay.manual ? t('workMode.sellingManual') : moduleText('discharge', 'name', 'Grid selling'),
            accent: 'var(--sell)',
            rows: [
                [t('tooltip.when'), `${formatTimeOnly(overlay.from.toISOString())} – ${formatTimeOnly(overlay.to.toISOString())}`],
                [t('tooltip.duration'), durationBetween(overlay.from.toISOString(), overlay.to.toISOString())],
                [t('selling.power'), overlay.watts ? formatNumber(overlay.watts, 0) : null],
                [t('selling.expected'), overlay.revenue !== null && overlay.revenue !== undefined
                    ? `${formatNumber(overlay.revenue, 0)} ${state.config.currency}` : null],
                [t('workMode.sellingState'), state_]
            ],
            // How the sale ended is the part the band itself cannot show.
            note: overlay.endedBy ? msg(overlay.endedBy) : null
        };
    }

    /**
     * A stretch of either band: what it was, who moved it there, and for how long.
     * <p>
     * Only naming the value differs between the two, so that is all the caller passes -
     * everything else a person wants to know about a stretch is the same question whether it
     * is a work mode or a supply.
     */
    function segmentTooltip(segment, labels, colours) {
        const subtitle = segment.changedBy
            ? moduleText(segment.changedBy, 'name', segment.changedBy)
            : (segment.held ? t('workMode.held') : t('workMode.beforeFirst'));

        return {
            title: labels[segment.mode] || t('workMode.unknown'),
            subtitle: subtitle,
            accent: colours[segment.mode] || 'var(--text-faint)',
            rows: [
                [t('tooltip.when'), `${formatTimeOnly(segment.from.toISOString())} – ${formatTimeOnly(segment.to.toISOString())}`],
                [t('tooltip.duration'), durationBetween(segment.from.toISOString(), segment.to.toISOString())],
                [t('workMode.setBy'), segment.entry ? msg(segment.entry) : null]
            ],
            note: segment.entry ? detailOf(segment.entry) : (segment.mode ? null : t('workMode.unknownNote'))
        };
    }

    /** Mode names as the rest of the page writes them, keyed the way a segment carries them. */
    function modeLabels() {
        return Object.fromEntries(Object.keys(MODE_COLOURS).map(mode => [mode, t('mode.' + mode)]));
    }

    /** The two ends of the connection switch, named as the stat tile names them. */
    function supplyLabels() {
        return {HIGH: t('stat.supplyMetered'), LOW: t('stat.supplySecond')};
    }

    function renderWorkMode() {
        const segments = workModeSegments();
        const supply = supplySegments();

        // Selling and charging both run through a remote control session on top of the work
        // mode, so both are ribbons along the bottom of its band - one session at a time, so
        // they never overlap.
        const overlays = [...sellingOverlays(), ...chargeOverlays()];
        const now = new Date();
        const start = startOfDay(now);

        const labels = modeLabels();
        const supplyNames = supplyLabels();

        // The work mode is the subject; the supply is what it is read against, so it goes
        // underneath as the thinner band rather than beside it as a chart of its own.
        const bands = [
            {
                segments: segments,
                colours: MODE_COLOURS,
                labels: labels,
                tooltip: segment => segmentTooltip(segment, labels, MODE_COLOURS),
                overlays: overlays,
                overlayTooltip: overlayTooltip
            },
            {
                segments: supply,
                colours: SUPPLY_COLOURS,
                labels: supplyNames,
                tooltip: segment => segmentTooltip(segment, supplyNames, SUPPLY_COLOURS),
                secondary: true
            }
        ];

        Charts.workModeChart(el('workmode-chart'), bands, {
            emptyMessage: t('workMode.none'),
            start: start,
            end: new Date(start.getTime() + DAY_MS),
            now: now
        });

        renderWorkModeNote(segments, supply);
        renderWorkModeLegend(segments, supply, overlays);
    }

    /**
     * The one thing a band cannot show: a change nothing was there to see.
     * <p>
     * A mode set from the SolaX app or the inverter's panel is caught by the backend reading
     * the mode back, and every throw of the connection switch is recorded as it happens, so
     * both are normally on their band. What neither can catch is a change made while nothing
     * was watching - over a restart, or with the inverter unreachable - and no time can be
     * put on one of those, so the band stops at the last change we know of and the mismatch
     * is stated here instead of being drawn at an invented time.
     */
    function renderWorkModeNote(segments, supply) {
        const note = el('workmode-note');

        // A band that was drawn from the live value alone (held) claims nothing about when
        // anything changed, so it can never disagree with it.
        const disagrees = (band, live) => {
            const last = band.length ? band[band.length - 1] : null;
            return live && last && !last.held && last.mode && last.mode !== live;
        };

        const sentences = [];

        const mode = state.overview?.workMode || null;
        if (disagrees(segments, mode)) {
            sentences.push(t('workMode.changedOutside', {mode: t('mode.' + mode)}));
        }

        const switchState = state.overview?.connectionSwitch || null;
        if (disagrees(supply, switchState)) {
            sentences.push(t('workMode.supplyChangedOutside', {supply: supplyLabels()[switchState] || switchState}));
        }

        note.hidden = sentences.length === 0;
        note.textContent = sentences.join(' ');
    }

    /** Only what the day actually contains - a legend of every mode is mostly noise. */
    function renderWorkModeLegend(segments, supply, overlays) {
        const legend = el('workmode-legend');
        const supplyNames = supplyLabels();

        const item = (background, label) => {
            const node = document.createElement('li');

            const swatch = document.createElement('i');
            swatch.className = 'swatch';
            swatch.style.background = background;
            node.appendChild(swatch);

            node.appendChild(textSpan(label));
            return node;
        };

        const present = band => [...new Set(band.map(segment => segment.mode).filter(Boolean))];

        const items = present(segments)
            .map(mode => item(MODE_COLOURS[mode] || 'var(--text-faint)', t('mode.' + mode)));

        // The supply band's colours mean nothing on their own - unlike a mode, which the band
        // labels where it fits - so whichever of the two the day contains is named here.
        present(supply).forEach(state_ =>
            items.push(item(SUPPLY_COLOURS[state_] || 'var(--text-faint)', supplyNames[state_] || state_)));

        // The ribbons are the marks on this chart that are not a band, so they are the ones the
        // legend has to explain rather than merely name.
        if ((overlays || []).some(overlay => overlay.kind !== 'charge')) {
            items.push(item('var(--sell)', t('prices.selling')));
        }

        if ((overlays || []).some(overlay => overlay.kind === 'charge')) {
            items.push(item('var(--charge)', t('workMode.charging')));
        }

        legend.replaceChildren(...items);
    }

    /** The window the timeline chart draws: the day ahead, or today from midnight. */
    function timelineWindow() {
        const now = new Date();

        if (state.timelineRange === 'day') {
            const start = startOfDay(now);
            return {start: start, end: new Date(start.getTime() + DAY_MS)};
        }

        const start = startOfHour(now);
        return {start: start, end: new Date(start.getTime() + DAY_MS)};
    }

    /**
     * Everything the timeline chart can draw: what the modules will do, and what they already
     * did. Both are the same shape, so the chart only has to know which side of now it is on -
     * and looking back over a whole day is only worth a switch because the past is in there.
     */
    function timelineEntries() {
        const timeline = state.timeline;

        const done = (timeline?.history || []).map(entry => ({
            moduleId: entry.moduleId,
            from: entry.at,
            to: null,
            type: entry.type,
            summary: entry.summary,
            messageKey: entry.messageKey,
            params: entry.params,
            detail: entry.detail,
            detailKey: entry.detailKey,
            detailParams: entry.detailParams,
            certain: true,
            past: true,
            success: entry.success
        }));

        return [...done, ...(timeline?.planned || [])];
    }

    /**
     * A planned action is titled by its kind and explained underneath; one that already ran is
     * titled by what it decided, which is the short headline the activity list shows.
     */
    function timelineTooltip(entry) {
        const module = moduleText(entry.moduleId, 'name', entry.moduleId);
        const accent = entry.success === false ? 'var(--danger)' : ACTION_COLOURS[entry.type];

        if (entry.past) {
            return {
                title: msg(entry),
                subtitle: module,
                accent: accent,
                rows: [
                    [t('tooltip.when'), formatDateTime(entry.from)],
                    [t('tooltip.happened'), relativeTo(entry.from)],
                    [t('tooltip.result'), entry.success ? t('tooltip.done') : t('tooltip.failed')]
                ],
                note: detailOf(entry)
            };
        }

        return {
            title: t('action.' + entry.type),
            subtitle: module,
            accent: accent,
            rows: [
                [t('tooltip.when'), entry.to
                    ? `${formatDateTime(entry.from)} – ${formatDateTime(entry.to)}`
                    : formatDateTime(entry.from)],
                [t('tooltip.duration'), durationBetween(entry.from, entry.to)],
                [t('tooltip.startsIn'), relativeTo(entry.from)],
                [t('tooltip.certainty'), entry.certain ? t('tooltip.committed') : t('tooltip.routine')]
            ],
            note: msg(entry)
        };
    }

    function renderTimeline() {
        const timeline = state.timeline;
        const range = timelineWindow();

        renderTimelineHint();
        renderTimelineChartToggle();

        // Drawing into a hidden container would measure nothing; the toggle redraws it.
        if (timelineChartVisible()) {
            Charts.timelineChart(el('timeline-chart'), timelineEntries(), {
                emptyMessage: t('timeline.none'),
                moduleNames: moduleNames(),
                start: range.start,
                end: range.end,
                tooltip: timelineTooltip
            });
        }

        // The chart above carries all of them; this list is a readable digest of what is
        // coming next, otherwise a quarter-hourly module turns it into a hundred rows.
        renderEntryList(el('timeline-planned'), timeline?.planned || [], t('timeline.none'), entry => ({
            time: entry.to
                ? `${formatDateTime(entry.from)} – ${formatDateTime(entry.to)}`
                : formatDateTime(entry.from),
            summary: msg(entry),
            detail: null,
            badge: t('action.' + entry.type),
            badgeClass: entry.certain ? 'pill-sell' : 'pill-muted'
        }));

        const history = historyEntries();

        renderEntryList(el('timeline-history'), history, t(historyEmptyKey()), entry => ({
            time: formatDateTime(entry.at),
            summary: msg(entry),
            // What the module did, in a sentence - the headline above only names the outcome.
            detail: detailOf(entry),
            badge: t('action.' + entry.type),
            badgeClass: entry.success ? 'pill-ok' : 'pill-error'
        }), state.historyRows);

        renderHistoryFilters(history);
    }

    /**
     * The activity list, filtered the way the two switches above it say.
     * <p>
     * The backend now keeps two days rather than the last handful, so that a restart does not
     * leave the charts and this list with holes nothing can fill back in - which also makes
     * "yesterday" a question worth being able to ask. Everything retained arrives in one
     * payload and the filtering happens here, because switching days should not mean waiting
     * for a round trip.
     */
    function historyEntries() {
        const all = state.timeline?.history || [];
        const day = state.historyDay;

        const withinDay = entry => {
            if (day === 'all') {
                return true;
            }

            const at = new Date(entry.at);
            const start = startOfDay(new Date());
            const from = day === 'yesterday' ? new Date(start.getTime() - DAY_MS) : start;

            return at >= from && at < new Date(from.getTime() + DAY_MS);
        };

        // A check that decided to leave everything alone is what the toggle is for. A check
        // that failed is not: it is the one kind of row that has to stay findable, whatever
        // is being filtered out around it.
        const didSomething = entry => entry.type !== 'CHECK' || !entry.success;

        return all.filter(entry => withinDay(entry) && (!state.historyChangesOnly || didSomething(entry)));
    }

    /** "Nothing happened" and "nothing is left after filtering" are different things to say. */
    function historyEmptyKey() {
        if ((state.timeline?.history || []).length === 0) {
            return 'history.none';
        }

        return state.historyChangesOnly ? 'history.noneChanges' : 'history.noneDay';
    }

    /** Keeps the two switches showing what is actually being filtered, and how much of it. */
    function renderHistoryFilters(shown) {
        document.querySelectorAll('#history-day .segment').forEach(segment =>
            segment.classList.toggle('is-active', segment.dataset.day === state.historyDay));

        const toggle = el('history-changes-only');
        toggle.classList.toggle('is-active', state.historyChangesOnly);
        toggle.setAttribute('aria-pressed', String(state.historyChangesOnly));

        // How many rows the toggle is currently hiding, so turning it on is not a leap of
        // faith and turning it off is an informed choice.
        const total = (state.timeline?.history || []).length;
        toggle.title = t('history.filtered', {shown: shown.length, total: total});
    }

    function setHistoryDay(day) {
        state.historyDay = day;
        localStorage.setItem(STORAGE_HISTORY_DAY, day);

        listPages['timeline-history'] = 0;
        renderTimeline();
    }

    function setHistoryChangesOnly(only) {
        state.historyChangesOnly = only;
        localStorage.setItem(STORAGE_HISTORY_CHANGES, only ? '1' : '0');

        listPages['timeline-history'] = 0;
        renderTimeline();
    }

    /**
     * What the chart above the list is currently showing. Kept in step with the switch through
     * data-i18n, so the next I18N.apply() does not put the other range's wording back.
     */
    function renderTimelineHint() {
        const hint = el('timeline-hint');
        const key = state.timelineRange === 'day' ? 'timeline.hintDay' : 'timeline.hintAhead';

        hint.dataset.i18n = key;
        hint.textContent = t(key);
    }

    /**
     * The two time charts each look either forward from this hour or across the whole of today.
     * Both remember the choice, because which one is useful depends on the time of day rather
     * than on the visit.
     */
    function renderRangeSwitches() {
        markRange('timeline-range', state.timelineRange);
        markRange('weather-range', state.weatherRange);
    }

    function markRange(id, active) {
        document.querySelectorAll(`#${id} .segment`).forEach(button =>
            button.classList.toggle('is-active', button.dataset.range === active));
    }

    function setTimelineRange(range) {
        state.timelineRange = range;
        localStorage.setItem(STORAGE_TIMELINE_RANGE, range);
        markRange('timeline-range', range);
        renderTimeline();
    }

    function setWeatherRange(range) {
        state.weatherRange = range;
        localStorage.setItem(STORAGE_WEATHER_RANGE, range);
        renderWeather();
    }

    /** A stored range, or the default when nothing sensible is stored. */
    function storedRange(key) {
        return localStorage.getItem(key) === 'day' ? 'day' : 'ahead';
    }

    /** Rows per page. Past this a list gets pager controls instead of growing. */
    const ENTRIES_PER_PAGE = 10;

    /** What the activity list offers as a row count, and what it falls back to. */
    const HISTORY_ROW_CHOICES = [5, 10, 20, 50, 100];

    /** Page each list is showing, by list id, so a refresh does not send it back to page 1. */
    const listPages = {};

    /**
     * A drawn chevron for a step button. Drawn rather than set as a glyph: the angle quotes
     * sit on the text baseline and come out visibly off centre in a square button, and their
     * shape differs from one system font to the next.
     */
    function chevron(direction) {
        const ns = 'http://www.w3.org/2000/svg';
        const svg = document.createElementNS(ns, 'svg');
        svg.setAttribute('class', 'chevron');
        svg.setAttribute('viewBox', '0 0 16 16');
        svg.setAttribute('aria-hidden', 'true');
        svg.setAttribute('fill', 'none');
        svg.setAttribute('stroke', 'currentColor');
        svg.setAttribute('stroke-width', '1.9');
        svg.setAttribute('stroke-linecap', 'round');
        svg.setAttribute('stroke-linejoin', 'round');

        const path = document.createElementNS(ns, 'path');
        path.setAttribute('d', direction === 'left' ? 'M10 3.5 5.5 8l4.5 4.5' : 'M6 3.5 10.5 8 6 12.5');
        svg.appendChild(path);

        return svg;
    }

    function renderEntryList(list, entries, emptyMessage, mapper, perPage = ENTRIES_PER_PAGE) {
        const pager = el(list.id + '-pager');

        if (!entries.length) {
            const empty = document.createElement('li');
            empty.className = 'empty-row';
            empty.textContent = emptyMessage;
            list.replaceChildren(empty);

            if (pager) {
                pager.hidden = true;
            }

            return;
        }

        // Entries come and go between refreshes - and so does the chosen row count - so the
        // remembered page may no longer exist.
        const pages = Math.ceil(entries.length / perPage);
        const page = Math.min(Math.max(listPages[list.id] || 0, 0), pages - 1);
        listPages[list.id] = page;

        const shown = entries.slice(page * perPage, (page + 1) * perPage);

        list.replaceChildren(...shown.map(entry => {
            const view = mapper(entry);
            const item = document.createElement('li');

            const time = document.createElement('span');
            time.className = 'timeline-time';
            time.textContent = view.time;
            item.appendChild(time);

            const summary = document.createElement('span');
            summary.className = 'timeline-summary';
            summary.textContent = view.summary;

            if (view.detail) {
                const detail = document.createElement('small');
                detail.textContent = view.detail;
                summary.appendChild(detail);
            }

            item.appendChild(summary);

            const badge = document.createElement('span');
            badge.className = 'pill ' + view.badgeClass;
            badge.textContent = view.badge;
            item.appendChild(badge);

            return item;
        }));

        if (pager) {
            renderPager(pager, list, page, pages, entries.length);
        }
    }

    /**
     * Page controls under a list. Hidden entirely while everything fits, so a short list
     * looks exactly as it did before pagination existed.
     */
    function renderPager(pager, list, page, pages, total) {
        pager.hidden = pages <= 1;

        if (pager.hidden) {
            pager.replaceChildren();
            return;
        }

        const step = (delta, label, disabled) => {
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'button button-small icon-step';
            button.appendChild(chevron(delta < 0 ? 'left' : 'right'));
            button.setAttribute('aria-label', label);
            button.disabled = disabled;
            button.addEventListener('click', () => {
                listPages[list.id] = page + delta;
                renderTimeline();
            });
            return button;
        };

        const position = document.createElement('span');
        position.className = 'pager-position';
        position.textContent = t('pager.position', {page: page + 1, pages, total});

        pager.replaceChildren(
            step(-1, t('pager.previous'), page === 0),
            position,
            step(1, t('pager.next'), page >= pages - 1)
        );
    }

    /**
     * Row count of the activity list.
     * <p>
     * How much of the recent activity is worth having on screen is a matter of taste and of
     * how big the screen is, so it is a choice rather than a constant - kept in localStorage
     * so a reload does not undo it.
     */
    function renderHistoryRowsControl() {
        const select = el('history-rows');

        if (select.options.length === 0) {
            HISTORY_ROW_CHOICES.forEach(rows => {
                const option = document.createElement('option');
                option.value = String(rows);
                option.textContent = String(rows);
                select.appendChild(option);
            });
        }

        select.value = String(state.historyRows);
    }

    function setHistoryRows(rows) {
        state.historyRows = rows;
        localStorage.setItem(STORAGE_HISTORY_ROWS, String(rows));

        // A shorter list means the remembered page can be past the end - start over.
        listPages['timeline-history'] = 0;
        renderTimeline();
    }

    /** The stored day filter, or today when nothing sensible is stored. */
    function storedHistoryDay() {
        const stored = localStorage.getItem(STORAGE_HISTORY_DAY);
        return ['today', 'yesterday', 'all'].includes(stored) ? stored : 'today';
    }

    /** The stored row count, or the default when nothing sensible is stored. */
    function storedHistoryRows() {
        const stored = Number(localStorage.getItem(STORAGE_HISTORY_ROWS));
        return HISTORY_ROW_CHOICES.includes(stored) ? stored : ENTRIES_PER_PAGE;
    }

    /**
     * The selling card leads with what is actually happening - the window, the power
     * and what it is worth - and keeps the planner's reasoning and the manual controls
     * below it.
     */
    function renderSelling() {
        const selling = state.selling;

        renderSellingBadge(selling);
        renderSellingHero(selling);
        renderSellingFacts(selling);

        renderSellingActions(selling);

        if (!state.config.allowControl) {
            setMessage(t('selling.controlDisabled'), null);
        }
    }

    /**
     * Which controls make sense right now. Arming happens in a dialog, so the card only
     * carries the decisions: start one, replace one, or stop one.
     */
    function renderSellingActions(selling) {
        const row = el('selling-actions');
        const controllable = state.config.allowControl && selling?.enabled;

        const button = (label, className, onClick) => {
            const node = document.createElement('button');
            node.type = 'button';
            node.className = 'button ' + className;
            node.textContent = label;
            node.disabled = !controllable;
            node.addEventListener('click', onClick);
            return node;
        };

        const buttons = [];

        if (selling?.armed) {
            buttons.push(button(t('selling.disarm'), 'button-danger', disarm));
            buttons.push(button(t('selling.rearm'), '', () => openArmDialog('rearm')));
        } else {
            buttons.push(button(t('selling.arm'), 'button-primary', () => openArmDialog('arm')));
        }

        buttons.push(button(t('selling.replan'), '', replanSelling));
        row.replaceChildren(...buttons);
    }

    function renderSellingBadge(selling) {
        const badge = el('selling-badge');

        if (!selling) {
            badge.textContent = '';
            badge.className = 'pill pill-muted';
            return;
        }

        const [label, className] = !selling.enabled ? [t('selling.disabled'), 'pill-muted']
            : selling.running ? [t('selling.running'), 'pill-sell']
                : selling.armed ? [t('selling.armed'), 'pill-active']
                    : [t('selling.notArmed'), 'pill-muted'];

        badge.textContent = label;
        badge.className = 'pill ' + className;
    }

    /** The headline block: three figures when a window is armed, one line when not. */
    function renderSellingHero(selling) {
        const hero = el('selling-hero');

        if (!selling || !selling.armed) {
            hero.className = 'selling-hero is-idle';

            const message = document.createElement('p');
            message.className = 'selling-idle';
            message.textContent = !selling?.enabled
                ? t('selling.disabledExplain')
                : t('selling.notArmedExplain');
            hero.replaceChildren(message);
            return;
        }

        hero.className = 'selling-hero' + (selling.running ? ' is-running' : ' is-armed');

        const figures = document.createElement('div');
        figures.className = 'selling-figures';

        const addFigure = (label, value, unit) => {
            const figure = document.createElement('div');
            figure.className = 'selling-figure';

            const caption = document.createElement('span');
            caption.className = 'selling-figure-label';
            caption.textContent = label;
            figure.appendChild(caption);

            const amount = document.createElement('span');
            amount.className = 'selling-figure-value';
            amount.textContent = value;

            if (unit) {
                const suffix = document.createElement('span');
                suffix.className = 'unit';
                suffix.textContent = unit;
                amount.appendChild(suffix);
            }

            figure.appendChild(amount);
            figures.appendChild(figure);
        };

        addFigure(t('selling.window'), `${formatTimeOnly(selling.from)} – ${formatTimeOnly(selling.to)}`);
        addFigure(t('selling.power'), formatNumber(selling.watts, 0), 'W');
        addFigure(t('selling.expected'), formatNumber(selling.expectedRevenue, 0), state.config.currency);

        hero.replaceChildren(figures);

        // While a sale runs with the panels carrying part of it, what the battery is giving is
        // the number that says how fast the reserve is coming.
        const pvShare = selling.running && hasValue(selling.batteryWatts) && selling.batteryWatts < selling.watts
            ? t('selling.split', {
                battery: formatNumber(selling.batteryWatts, 0),
                pv: formatNumber(selling.watts - selling.batteryWatts, 0)
            })
            : null;

        const notes = [
            selling.running ? t('selling.endsIn', {duration: relativeTo(selling.to)}) : relativeToStart(selling.from),
            pvShare,
            armedByLabel(selling)
        ].filter(Boolean);

        const footer = document.createElement('p');
        footer.className = 'selling-hero-note';
        footer.textContent = notes.join(' · ');
        hero.appendChild(footer);
    }

    /**
     * Who armed the window. Another module - the morning sale - has a sentence of its own,
     * looked up by its id, and one the dictionary does not know falls back to "manually".
     */
    function armedByLabel(selling) {
        if (selling.armedBy && selling.armedBy !== 'planner' && selling.armedBy !== 'dashboard') {
            return I18N.message('selling.armedBy.' + selling.armedBy, null, t('selling.manual'));
        }

        return selling.manual ? t('selling.manual') : t('selling.automatic');
    }

    function renderSellingFacts(selling) {
        const facts = el('selling-facts');

        if (!selling) {
            facts.replaceChildren();
            return;
        }

        const rows = [
            [t('selling.reason'),
                I18N.message(selling.planReasonKey, selling.planReasonParams, selling.planReason) || '—'],
            [t('selling.nextPlanning'), formatDateTime(selling.nextPlanningAt)],
            [t('selling.remoteControl'),
                selling.remoteControlAvailable ? t('selling.remoteAvailable') : t('selling.remoteUnavailable')]
        ];

        facts.replaceChildren(...rows.map(([label, value]) => {
            const row = document.createElement('div');

            const dt = document.createElement('dt');
            dt.textContent = label;
            row.appendChild(dt);

            const dd = document.createElement('dd');
            dd.textContent = value;
            row.appendChild(dd);

            return row;
        }));
    }

    /* ------------------------------------------------------- quick actions */

    /** Work modes offered as buttons, in the order they make sense to reach for. */
    const QUICK_MODES = ['SELF_USE', 'FEED_IN_PRIORITY', 'BACKUP'];

    /**
     * Manual overrides, in two cards: charging from the grid beside the selling card, and the
     * control card under both - the work mode, balancing, and leaving remote control. The two
     * kinds of command behave differently and confusing them is expensive: a work mode sticks
     * until something changes it, a remote control session expires on its own.
     */
    function renderQuickActions() {
        const overview = state.overview;
        const controllable = state.config.allowControl;

        const button = (label, className, onClick, enabled = true) => {
            const node = document.createElement('button');
            node.type = 'button';
            node.className = 'button ' + className;
            node.textContent = label;
            node.disabled = !controllable || !enabled;
            node.addEventListener('click', onClick);
            return node;
        };

        // The mode the inverter is already in is marked rather than disabled - pressing it
        // again is harmless and is sometimes exactly what you want after a manual change.
        el('quick-modes').replaceChildren(...QUICK_MODES.map(mode => button(
            t('mode.' + mode),
            overview?.workMode === mode ? 'button-primary' : '',
            () => setWorkMode(mode)
        )));

        const remote = state.selling?.remoteControlAvailable;
        // Nothing to cancel unless a session is actually running, and a button that is
        // always live invites pressing it to find out.
        const underRemoteControl = remote && overview?.remoteControlActive === true;

        el('charge-actions').replaceChildren(
            button(t('quick.charge'), 'button-primary', chargeFromGrid, remote));

        // Last on the control card, because it undoes whatever is running: a sale, a charge
        // or balancing alike.
        el('exit-actions').replaceChildren(
            button(t('quick.exit'), 'button-danger', exitRemoteControl, underRemoteControl));

        renderChargeStatus(remote);

        // Its own line, not the message line: this is a standing fact about the setup, and
        // it must not wipe out what the last button press reported.
        const note = !controllable ? t('selling.controlDisabled')
            : !remote ? t('quick.remoteUnavailable')
                : !underRemoteControl ? t('quick.noSession')
                    : t('quick.underRemoteControl');

        el('quick-note').textContent = note;
        el('quick-note').hidden = !note;

        updateChargePreview();
    }

    /**
     * The charge card's badge and, while one runs, what is charging: who started it, at what
     * power and until when. Balancing and the night charge are charges too, and say so.
     */
    function renderChargeStatus(remote) {
        const now = new Date();
        const running = chargeStretches().find(stretch => stretch.running && stretch.to >= now && stretch.from <= now);
        const badge = el('charge-badge');
        const status = el('charge-status');

        if (!remote) {
            badge.textContent = t('charge.unavailable');
            badge.className = 'pill pill-muted';
        } else if (running) {
            badge.textContent = t('charge.running');
            badge.className = 'pill pill-charge';
        } else {
            badge.textContent = t('charge.idle');
            badge.className = 'pill pill-muted';
        }

        status.hidden = !running;

        if (!running) {
            status.replaceChildren();
            return;
        }

        const source = running.entry || running.plannedEntry;
        const title = document.createElement('strong');
        title.textContent = hasValue(running.targetSoc) && !running.entry?.params?.until
            ? t('charge.runningToSoc', {target: running.targetSoc})
            : t('charge.runningUntil', {until: formatTimeOnly(running.to.toISOString())});

        const note = document.createElement('span');
        note.textContent = [
            source ? moduleText(source.moduleId, 'name', source.moduleId) : null,
            hasValue(running.watts) ? `${formatNumber(running.watts, 0)} W` : null,
            t('charge.since', {from: formatTimeOnly(running.from.toISOString())})
        ].filter(Boolean).join(' · ');

        status.replaceChildren(title, note);
    }

    /* -------------------------------------------------------- battery balancing */

    /**
     * The balancing control: what it does, where a running one is, and the one button that
     * starts or stops it. A running balancing says which half it is in - still charging, or
     * full and being held - because the hour only starts counting once the battery is full.
     */
    function renderBalance() {
        const balance = state.balance;
        const status = el('balance-status');
        const controllable = state.config.allowControl && balance?.available;

        el('balance-hint').textContent = t('balance.hint', {
            target: balance?.targetBattery ?? 100,
            minutes: balance?.holdMinutes ?? 60,
            watts: formatNumber(balance?.defaultWatts ?? 3000, 0)
        });

        const button = (label, className, onClick) => {
            const node = document.createElement('button');
            node.type = 'button';
            node.className = 'button ' + className;
            node.textContent = label;
            node.disabled = !state.config.allowControl || (!balance?.active && !controllable);
            node.addEventListener('click', onClick);
            return node;
        };

        el('balance-actions').replaceChildren(balance?.active
            ? button(t('balance.cancel'), 'button-danger', cancelBalance)
            : button(t('balance.start'), '', startBalance));

        const lines = balanceStatus(balance);
        status.hidden = !lines;

        if (lines) {
            const title = document.createElement('strong');
            title.textContent = lines.title;

            const note = document.createElement('span');
            note.textContent = lines.note || '';

            const children = [title, note];

            if (typeof lines.bar === 'number') {
                const bar = document.createElement('div');
                bar.className = 'stat-bar';
                const fill = document.createElement('i');
                fill.style.width = Math.max(0, Math.min(100, lines.bar)) + '%';
                bar.appendChild(fill);
                children.push(bar);
            }

            status.replaceChildren(...children);
        }
    }

    /** The status block's lines, or null when there is nothing worth saying. */
    function balanceStatus(balance) {
        if (!balance) {
            return null;
        }

        if (!balance.available && !balance.active) {
            return {title: t('balance.unavailable'), note: null};
        }

        if (balance.active && balance.holding) {
            const total = balance.holdMinutes * 60000;
            const done = Date.now() - new Date(balance.fullSince).getTime();
            const left = Math.max(0, Math.round((new Date(balance.holdUntil) - Date.now()) / 60000));

            return {
                title: t('balance.holding', {target: balance.targetBattery, until: formatTimeOnly(balance.holdUntil)}),
                note: t('balance.holdingLeft', {left: humanMinutes(left)}),
                bar: total > 0 ? done / total * 100 : null
            };
        }

        if (balance.active) {
            const soc = state.overview?.batterySoc;

            return {
                title: t('balance.charging', {target: balance.targetBattery}),
                note: t('balance.chargingNote', {
                    battery: hasValue(soc) ? soc : '—',
                    deadline: formatTimeOnly(balance.deadline)
                }),
                bar: hasValue(soc) ? soc : null
            };
        }

        // Idle: the last balancing's outcome, as long as there was one.
        if (balance.summaryKey && balance.summaryKey.startsWith('history.balance.')) {
            return {
                title: I18N.message(balance.summaryKey, balance.summaryParams, balance.summary),
                note: I18N.message(balance.detailKey, balance.detailParams, balance.detail)
            };
        }

        return null;
    }

    function setBalanceMessage(text, ok) {
        writeMessage(el('balance-message'), text, ok);
    }

    async function startBalance() {
        setBalanceMessage('', null);

        try {
            const result = await Api.startBalance({});
            setBalanceMessage(actionMessage(result), result.success);
            await refresh();
        } catch (error) {
            setBalanceMessage(error.message, false);
        }
    }

    async function cancelBalance() {
        setBalanceMessage('', null);

        try {
            const result = await Api.cancelBalance();
            setBalanceMessage(actionMessage(result), result.success);
            await refresh();
        } catch (error) {
            setBalanceMessage(error.message, false);
        }
    }

    /* ------------------------------------------------------------ statistics */

    /** The ranges the statistics section offers, in days. */
    const STATS_RANGES = [7, 30, 90];

    /**
     * One colour per quantity, from the stylesheet so they follow the theme: production the
     * sun's yellow, consumption the house's slate, export the blue the live grid tile draws
     * it in, import a rose that reads as the other direction, and a sale the selling purple
     * the rest of the page already uses for it.
     */
    const STAT_COLOURS = {
        production: 'var(--stat-production)',
        consumption: 'var(--stat-consumption)',
        export: 'var(--stat-export)',
        import: 'var(--stat-import)',
        sold: 'var(--sell)',
        net: 'var(--text)',
        battery: 'var(--success)'
    };

    const hasValue = value => value !== null && value !== undefined && !Number.isNaN(value);

    /** kWh with a decimal where it still says something. */
    const kwh = value => hasValue(value) ? formatNumber(value, Math.abs(value) >= 100 ? 0 : 1) : '—';

    /** Money in whole units - the spot valuation is an estimate, the halíře would be noise. */
    const money = value => hasValue(value) ? formatNumber(value, 0) : '—';

    /**
     * The stored choice: one of the rolling ranges, or 'month'. Only the kind of view is
     * remembered, not which month - coming back a week later should not open on a month
     * browsed to long ago, so the month always starts out as the current one.
     */
    function storedStatsRange() {
        const stored = localStorage.getItem(STORAGE_STATS_DAYS);
        return stored === 'month' || STATS_RANGES.includes(Number(stored)) ? stored : '30';
    }

    const pad2 = n => String(n).padStart(2, '0');

    /** 'YYYY-MM' of a date, in local time. */
    function monthKey(date) {
        return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}`;
    }

    /** The month {@code delta} months away from 'YYYY-MM'. */
    function shiftMonth(key, delta) {
        const [year, month] = key.split('-').map(Number);
        return monthKey(new Date(year, month - 1 + delta, 1));
    }

    /** "Listopad 2026" - the month's own name, capitalised for a heading. */
    function monthLabel(key) {
        const [year, month] = key.split('-').map(Number);
        const text = new Date(year, month - 1, 1).toLocaleDateString(locale(), {month: 'long', year: 'numeric'});
        return text.charAt(0).toUpperCase() + text.slice(1);
    }

    /**
     * Every month there can be anything to show for, newest first: from the month recording
     * started in - or the oldest still kept, if that is later - to this one.
     */
    function availableMonths() {
        const current = monthKey(new Date());
        const stats = state.statistics;

        if (!stats?.collectingSince) {
            return [current];
        }

        const kept = new Date();
        kept.setDate(kept.getDate() - (stats.retentionDays || 400) + 1);

        const since = stats.collectingSince.slice(0, 7);
        const oldest = since > monthKey(kept) ? since : monthKey(kept);

        const months = [];
        for (let key = current; key >= oldest; key = shiftMonth(key, -1)) {
            months.push(key);
        }

        return months;
    }

    /** What to ask the backend for: a calendar month by its first and last day, or the last N days. */
    function statsQuery() {
        if (state.statsRange !== 'month') {
            return {days: state.statsRange};
        }

        const [year, month] = state.statsMonth.split('-').map(Number);
        const last = new Date(year, month, 0).getDate();

        return {from: `${state.statsMonth}-01`, to: `${state.statsMonth}-${pad2(last)}`};
    }

    /**
     * The range switch and the month browser beside it. The browser is always there, so going
     * back a month is one press from any view. While a rolling range is shown it steps back
     * to its quieter look, because the month in it is not what is on screen.
     */
    function markStatsRange() {
        document.querySelectorAll('#stats-range .segment').forEach(button =>
            button.classList.toggle('is-active', button.dataset.days === state.statsRange));

        const months = availableMonths();
        const select = el('stats-month');
        const browsing = state.statsRange === 'month';
        const shown = browsing ? state.statsMonth : monthKey(new Date());

        // Rebuilt only when the list itself changed, so an open dropdown is not yanked shut
        // by a refresh, and relabelled when the language changes.
        const signature = I18N.language + '|' + months.join(',');

        if (select.dataset.signature !== signature) {
            select.replaceChildren(...months.map(key => {
                const option = document.createElement('option');
                option.value = key;
                option.textContent = monthLabel(key);
                return option;
            }));
            select.dataset.signature = signature;
        }

        select.value = months.includes(shown) ? shown : months[0];
        el('stats-month-nav').classList.toggle('is-idle', !browsing);

        // From a rolling view the arrow goes to last month, inside a month to the one before.
        const back = browsing ? shiftMonth(state.statsMonth, -1) : shiftMonth(monthKey(new Date()), -1);
        el('stats-month-prev').disabled = !months.includes(back);
        el('stats-month-next').disabled = !browsing || state.statsMonth >= months[0];

        // The arrows carry no words of their own, so a screen reader gets them in the page's language.
        el('stats-month-prev').setAttribute('aria-label', t('stats.prevMonth'));
        el('stats-month-next').setAttribute('aria-label', t('stats.nextMonth'));
    }

    async function loadStatistics() {
        markStatsRange();

        try {
            state.statistics = await Api.statistics(statsQuery());
        } catch (error) {
            console.warn('Statistics unavailable', error);
        }

        renderStatistics();
    }

    function setStatsRange(range) {
        state.statsRange = range;
        localStorage.setItem(STORAGE_STATS_DAYS, range);

        if (range === 'month' && !state.statsMonth) {
            state.statsMonth = monthKey(new Date());
        }

        loadStatistics();
    }

    function setStatsMonth(key) {
        state.statsMonth = key;
        setStatsRange('month');
    }

    /** One month back - or, from a rolling view, last month. */
    function previousStatsMonth() {
        const from = state.statsRange === 'month' ? state.statsMonth : monthKey(new Date());
        setStatsMonth(shiftMonth(from, -1));
    }

    function nextStatsMonth() {
        if (state.statsRange === 'month') {
            setStatsMonth(shiftMonth(state.statsMonth, 1));
        }
    }

    /** The period on screen, in words: "Listopad 2026", or "9. 10. – 7. 11." for a rolling view. */
    function statsPeriodLabel(stats) {
        if (state.statsRange === 'month' && state.statsMonth) {
            return monthLabel(state.statsMonth);
        }

        return `${formatDay(stats.from)} – ${formatDay(stats.to)}`;
    }

    /** "3 Nov", or "Mon 3 Nov" with the weekday. */
    function formatDay(isoDate, withWeekday = false) {
        return new Date(isoDate + 'T00:00:00').toLocaleDateString(locale(), withWeekday
            ? {weekday: 'short', day: 'numeric', month: 'short'}
            : {day: 'numeric', month: 'short'});
    }

    function isToday(isoDate) {
        return new Date(isoDate + 'T00:00:00').toDateString() === new Date().toDateString();
    }

    function renderStatistics() {
        const stats = state.statistics;

        markStatsRange();
        renderStatsSub(stats);
        renderStatsTiles(stats);
        renderStatisticsCharts();
        renderModeShare(stats);
        renderStatsTable(stats);
    }

    /** Where the numbers come from, and since when - the record starts when the application did. */
    function renderStatsSub(stats) {
        const sub = el('stats-sub');

        if (!stats || !stats.collectingSince) {
            sub.textContent = t('stats.notYet');
            return;
        }

        const parts = [t('stats.periodSummary', {
            period: statsPeriodLabel(stats),
            date: formatDay(stats.collectingSince),
            recorded: stats.totals.recordedDays,
            days: stats.days.length
        })];

        if (stats.totals.partialDays > 0) {
            parts.push(t('stats.partial', {count: stats.totals.partialDays}));
        }

        sub.textContent = parts.join(' ');
    }

    function renderStatsTiles(stats) {
        const container = el('stats-tiles');
        const totals = stats?.totals;

        if (!totals) {
            container.replaceChildren();
            return;
        }

        const currency = stats.currency || state.config.currency;
        const tiles = [];
        const push = (label, value, unit, notes, bar = null, barColour = null) =>
            tiles.push({label, value, unit, notes: [].concat(notes || []).filter(Boolean), bar, barColour});

        if (hasValue(totals.yieldKwh)) {
            push(t('stats.production'), kwh(totals.yieldKwh), 'kWh', [
                hasValue(totals.averageDailyYield) ? t('stats.perDay', {value: kwh(totals.averageDailyYield)}) : null,
                totals.bestDay ? t('stats.bestDay', {date: formatDay(totals.bestDay), value: kwh(totals.bestDayYield)}) : null
            ]);
        }

        if (hasValue(totals.consumptionKwh)) {
            push(t('stats.consumption'), kwh(totals.consumptionKwh), 'kWh', [
                hasValue(totals.averageDailyConsumption)
                    ? t('stats.perDay', {value: kwh(totals.averageDailyConsumption)}) : null,
                hasValue(totals.selfSufficiency)
                    ? t('stats.selfSufficiency', {value: formatNumber(totals.selfSufficiency, 0)}) : null
            ], totals.selfSufficiency, STAT_COLOURS.production);
        }

        if (hasValue(totals.exportKwh)) {
            push(t('stats.exported'), kwh(totals.exportKwh), 'kWh',
                hasValue(totals.exportValue) ? t('stats.worth', {value: money(totals.exportValue), currency}) : null);
        }

        if (hasValue(totals.importKwh)) {
            push(t('stats.imported'), kwh(totals.importKwh), 'kWh',
                hasValue(totals.importCost) ? t('stats.cost', {value: money(totals.importCost), currency}) : null);
        }

        if (hasValue(totals.net)) {
            push(t('stats.net'), money(totals.net), currency, t('stats.netNote'));
        }

        if (hasValue(totals.soldValue)) {
            push(t('stats.sales'), money(totals.soldValue), currency,
                t('stats.salesNote', {kwh: kwh(totals.soldKwh), count: totals.sales}));
        }

        if (hasValue(totals.dischargedKwh)) {
            push(t('stats.batteryOut'), kwh(totals.dischargedKwh), 'kWh',
                hasValue(totals.chargedKwh) ? t('stats.batteryCharged', {value: kwh(totals.chargedKwh)}) : null);
        }

        if (hasValue(totals.priceAverage)) {
            push(t('stats.price'), formatNumber(totals.priceAverage, 2), `${currency}/kWh`,
                hasValue(totals.priceMin) && hasValue(totals.priceMax)
                    ? t('stats.priceRange', {min: formatNumber(totals.priceMin, 2), max: formatNumber(totals.priceMax, 2)})
                    : null);
        }

        if (hasValue(totals.pvPeak)) {
            const peak = formatPower(totals.pvPeak);
            const pvPeak = state.config.pvPeak;

            push(t('stats.pvPeak'), peak.value, peak.unit,
                pvPeak ? t('stat.ofPeak', {maximum: formatNumber(pvPeak, 0)}) : null,
                pvPeak ? Math.min(100, totals.pvPeak / pvPeak * 100) : null, 'var(--accent)');
        }

        container.replaceChildren(...tiles.map(tileNode));
    }

    /** Day labels: the weekday as well when there is room for a week, the date alone otherwise. */
    function statsDayLabel(days) {
        return day => new Date(day.date + 'T00:00:00').toLocaleDateString(locale(), days.length <= 7
            ? {weekday: 'short', day: 'numeric'}
            : {day: 'numeric'});
    }

    /** Everything recorded about a day - the same tooltip on every statistics chart. */
    function statsTooltip(day, accent) {
        const currency = state.statistics?.currency || state.config.currency;

        if (!day.recorded) {
            return {title: formatDay(day.date, true), accent: 'var(--text-faint)', note: t('stats.notRecorded')};
        }

        const range = hasValue(day.socMin) && hasValue(day.socMax) ? `${day.socMin} – ${day.socMax} %` : null;
        const net = hasValue(day.exportValue) || hasValue(day.importCost)
            ? (day.exportValue || 0) - (day.importCost || 0) : null;

        const status = [
            isToday(day.date) ? t('stats.today') : null,
            day.partial ? t('stats.partialDay') : null
        ].filter(Boolean).join(' · ');

        return {
            title: formatDay(day.date, true),
            subtitle: status || null,
            accent: accent,
            rows: [
                [t('stats.production'), hasValue(day.yieldKwh) ? `${kwh(day.yieldKwh)} kWh` : null],
                [t('stats.consumption'), hasValue(day.consumptionKwh) ? `${kwh(day.consumptionKwh)} kWh` : null],
                [t('stats.exported'), hasValue(day.exportKwh) ? `${kwh(day.exportKwh)} kWh` : null],
                [t('stats.imported'), hasValue(day.importKwh) ? `${kwh(day.importKwh)} kWh` : null],
                [t('stats.col.selfSufficiency'), hasValue(day.selfSufficiency) ? `${formatNumber(day.selfSufficiency, 0)} %` : null],
                [t('stats.legend.exportValue'), hasValue(day.exportValue) ? `${money(day.exportValue)} ${currency}` : null],
                [t('stats.sales'), day.sales > 0 ? `${money(day.soldValue)} ${currency} · ${kwh(day.soldKwh)} kWh` : null],
                [t('stats.legend.importCost'), hasValue(day.importCost) ? `${money(day.importCost)} ${currency}` : null],
                [t('stats.net'), hasValue(net) ? `${money(net)} ${currency}` : null],
                [t('stats.col.battery'), range],
                [t('stats.col.price'), hasValue(day.priceAverage) ? `${formatNumber(day.priceAverage, 2)} ${currency}/kWh` : null]
            ]
        };
    }

    function renderLegend(id, items) {
        el(id).replaceChildren(...items.map(([colour, label, line]) => {
            const item = document.createElement('li');
            const swatch = document.createElement('i');

            swatch.className = 'swatch' + (line ? ' swatch-tick' : '');
            swatch.style.background = colour;

            item.append(swatch, textSpan(label));
            return item;
        }));
    }

    function renderStatisticsCharts() {
        const stats = state.statistics;
        const days = stats?.days || [];
        const currency = stats?.currency || state.config.currency;
        const emptyMessage = t(stats?.collectingSince ? 'stats.noneInRange' : 'stats.notYetShort');
        const xLabel = statsDayLabel(days);
        const axisFormat = value => formatNumber(value, Math.abs(value) < 10 && value % 1 !== 0 ? 1 : 0);

        el('stats-money-unit').textContent = currency;

        Charts.dailyChart(el('stats-energy-chart'), days, {
            mode: 'grouped',
            series: [
                {value: day => day.yieldKwh, colour: STAT_COLOURS.production},
                {value: day => day.consumptionKwh, colour: STAT_COLOURS.consumption},
                {value: day => day.exportKwh, colour: STAT_COLOURS.export},
                {value: day => day.importKwh, colour: STAT_COLOURS.import}
            ],
            axisFormat, xLabel, emptyMessage,
            tooltip: day => statsTooltip(day, STAT_COLOURS.production)
        });

        renderLegend('stats-energy-legend', [
            [STAT_COLOURS.production, t('stats.production')],
            [STAT_COLOURS.consumption, t('stats.consumption')],
            [STAT_COLOURS.export, t('stats.exported')],
            [STAT_COLOURS.import, t('stats.imported')]
        ]);

        // Income stacks up from the axis, cost hangs below it. A sale is part of the export,
        // so it is taken out of the export bar rather than drawn on top of all of it.
        Charts.dailyChart(el('stats-money-chart'), days, {
            mode: 'stacked',
            series: [
                {value: day => day.soldValue, colour: STAT_COLOURS.sold},
                {value: day => hasValue(day.exportValue) ? day.exportValue - (day.soldValue || 0) : null, colour: STAT_COLOURS.export},
                {value: day => hasValue(day.importCost) ? -day.importCost : null, colour: STAT_COLOURS.import}
            ],
            marker: day => hasValue(day.exportValue) || hasValue(day.importCost)
                ? (day.exportValue || 0) - (day.importCost || 0) : null,
            axisFormat, xLabel, emptyMessage,
            tooltip: day => statsTooltip(day, STAT_COLOURS.sold)
        });

        renderLegend('stats-money-legend', [
            [STAT_COLOURS.sold, t('stats.sales')],
            [STAT_COLOURS.export, t('stats.legend.otherExport')],
            [STAT_COLOURS.import, t('stats.legend.importCost')],
            [STAT_COLOURS.net, t('stats.net'), true]
        ]);

        Charts.dailyChart(el('stats-battery-chart'), days, {
            mode: 'range',
            min: 0,
            max: 100,
            range: {low: day => day.socMin, high: day => day.socMax, colour: STAT_COLOURS.battery},
            axisFormat: value => formatNumber(value, 0),
            xLabel, emptyMessage,
            tooltip: day => statsTooltip(day, STAT_COLOURS.battery)
        });
    }

    /** "12 h", or "3.5 h" while it is still short enough for the half to matter. */
    function formatHours(minutes) {
        return t('stats.hours', {hours: formatNumber(minutes / 60, minutes < 600 ? 1 : 0)});
    }

    /** How the watched time split between the work modes, as one bar and a legend that says it in numbers. */
    function renderModeShare(stats) {
        const container = el('stats-modes');
        const minutes = stats?.totals?.modeMinutes || {};
        const entries = Object.entries(minutes).filter(([, value]) => value > 0).sort((a, b) => b[1] - a[1]);
        const total = entries.reduce((sum, [, value]) => sum + value, 0);

        if (!total) {
            const empty = document.createElement('p');
            empty.className = 'chart-empty';
            empty.textContent = t('stats.modesNone');
            container.replaceChildren(empty);
            el('stats-modes-legend').replaceChildren();
            el('stats-modes-sub').textContent = '';
            return;
        }

        el('stats-modes-sub').textContent = t('stats.modesSub', {hours: formatHours(total)});

        const bar = document.createElement('div');
        bar.className = 'mode-share-bar';

        entries.forEach(([mode, value]) => {
            const part = document.createElement('span');
            part.style.width = (value / total * 100) + '%';
            part.style.background = MODE_COLOURS[mode] || 'var(--text-faint)';
            part.title = `${t('mode.' + mode)} · ${formatHours(value)}`;
            bar.appendChild(part);
        });

        container.replaceChildren(bar);

        renderLegend('stats-modes-legend', entries.map(([mode, value]) => [
            MODE_COLOURS[mode] || 'var(--text-faint)',
            t('stats.modeShare', {
                mode: t('mode.' + mode),
                hours: formatHours(value),
                percent: formatNumber(value / total * 100, 0)
            })
        ]));
    }

    /**
     * Every recorded day in numbers, newest first. Only built while the section is open:
     * ninety rows of twelve columns are not worth building on every refresh for nobody.
     */
    function renderStatsTable(stats) {
        const table = el('stats-table');

        if (!el('stats-details').open) {
            table.replaceChildren();
            return;
        }

        const days = (stats?.days || []).filter(day => day.recorded).reverse();

        if (!days.length) {
            const caption = document.createElement('caption');
            caption.textContent = t(stats?.collectingSince ? 'stats.noneInRange' : 'stats.notYetShort');
            table.replaceChildren(caption);
            return;
        }

        const currency = stats.currency || state.config.currency;
        const totals = stats.totals;
        const net = day => hasValue(day.exportValue) || hasValue(day.importCost)
            ? (day.exportValue || 0) - (day.importCost || 0) : null;

        const columns = [
            {label: t('stats.col.day'), day: day => formatDay(day.date, true), total: () => t('stats.col.total'), text: true},
            {label: `${t('stats.production')} (kWh)`, day: day => kwh(day.yieldKwh), total: () => kwh(totals.yieldKwh)},
            {label: `${t('stats.consumption')} (kWh)`, day: day => kwh(day.consumptionKwh), total: () => kwh(totals.consumptionKwh)},
            {label: `${t('stats.exported')} (kWh)`, day: day => kwh(day.exportKwh), total: () => kwh(totals.exportKwh)},
            {label: `${t('stats.imported')} (kWh)`, day: day => kwh(day.importKwh), total: () => kwh(totals.importKwh)},
            {label: `${t('stats.col.selfSufficiency')} (%)`,
                day: day => hasValue(day.selfSufficiency) ? formatNumber(day.selfSufficiency, 0) : '—',
                total: () => hasValue(totals.selfSufficiency) ? formatNumber(totals.selfSufficiency, 0) : '—'},
            {label: `${t('stats.legend.exportValue')} (${currency})`, day: day => money(day.exportValue), total: () => money(totals.exportValue)},
            {label: `${t('stats.sales')} (${currency})`, day: day => day.sales > 0 ? money(day.soldValue) : '—', total: () => money(totals.soldValue)},
            {label: `${t('stats.legend.importCost')} (${currency})`, day: day => money(day.importCost), total: () => money(totals.importCost)},
            {label: `${t('stats.net')} (${currency})`, day: day => money(net(day)), total: () => money(totals.net)},
            {label: `${t('stats.col.battery')} (%)`,
                day: day => hasValue(day.socMin) && hasValue(day.socMax) ? `${day.socMin}–${day.socMax}` : '—',
                total: () => ''},
            {label: `${t('stats.col.price')} (${currency}/kWh)`,
                day: day => hasValue(day.priceAverage) ? formatNumber(day.priceAverage, 2) : '—',
                total: () => hasValue(totals.priceAverage) ? formatNumber(totals.priceAverage, 2) : '—'}
        ];

        const cell = (tag, text, numeric) => {
            const node = document.createElement(tag);
            node.textContent = text;
            if (numeric) {
                node.className = 'num';
            }
            return node;
        };

        const head = document.createElement('thead');
        const headRow = document.createElement('tr');
        columns.forEach(column => headRow.appendChild(cell('th', column.label, !column.text)));
        head.appendChild(headRow);

        const body = document.createElement('tbody');
        days.forEach(day => {
            const row = document.createElement('tr');

            if (day.partial || isToday(day.date)) {
                row.className = 'is-partial';
                row.title = [isToday(day.date) ? t('stats.today') : null, day.partial ? t('stats.partialDay') : null]
                    .filter(Boolean).join(' · ');
            }

            columns.forEach(column => row.appendChild(cell('td', column.day(day), !column.text)));
            body.appendChild(row);
        });

        const foot = document.createElement('tfoot');
        const footRow = document.createElement('tr');
        columns.forEach(column => footRow.appendChild(cell('td', column.total(), !column.text)));
        foot.appendChild(footRow);

        table.replaceChildren(head, body, foot);
    }

    function textSpan(text) {
        const node = document.createElement('span');
        node.textContent = text;
        return node;
    }

    function renderAll() {
        I18N.apply();
        renderHistoryRowsControl();
        renderRangeSwitches();
        renderStats();
        renderPrices();
        renderWeather();
        renderWorkMode();
        renderTimeline();
        renderSelling();
        renderQuickActions();
        renderBalance();
        renderStatistics();
    }

    /* ------------------------------------------------------------ skeletons */

    /**
     * Placeholders shown until the first fetch lands.
     * <p>
     * The first read of the inverter is genuinely slow - every Modbus request is spaced a
     * second apart - so without these the page sits empty long enough to look broken.
     * Every render function replaces children, so nothing has to clear them afterwards.
     */
    function renderSkeletons() {
        const block = (className, count = 1) => Array.from({length: count}, () => {
            const node = document.createElement('div');
            node.className = 'skeleton ' + className;
            return node;
        });

        const lines = (...classNames) => classNames.map(className => {
            const node = document.createElement('div');
            node.className = 'skeleton skeleton-line ' + className;
            return node;
        });

        el('stat-tiles').replaceChildren(...block('skeleton-tile', 4));
        el('price-chart').replaceChildren(...block('skeleton-chart'));
        el('weather-chart').replaceChildren(...block('skeleton-chart'));
        el('workmode-chart').replaceChildren(...block('skeleton-chart'));
        el('timeline-chart').replaceChildren(...block('skeleton-chart'));
        el('selling-hero').replaceChildren(...block('skeleton-hero'));
        el('selling-facts').replaceChildren(...lines('is-medium', '', 'is-short'));
        el('timeline-planned').replaceChildren(...lines('', 'is-medium', 'is-short'));
        el('timeline-history').replaceChildren(...lines('', 'is-short'));
        el('stats-tiles').replaceChildren(...block('skeleton-tile', 4));
        el('stats-energy-chart').replaceChildren(...block('skeleton-chart'));
        el('stats-money-chart').replaceChildren(...block('skeleton-chart'));
        el('stats-battery-chart').replaceChildren(...block('skeleton-chart'));
    }

    /* -------------------------------------------------------------- actions */

    function setMessage(text, ok) {
        writeMessage(el('arm-message'), text, ok);
    }

    function setDialogMessage(text, ok) {
        writeMessage(el('arm-dialog-message'), text, ok);
    }

    /** Control responses carry an optional translation key, same as everything else. */
    const actionMessage = result => I18N.message(result.messageKey, result.params, result.message);

    function writeMessage(node, text, ok) {
        node.textContent = text || '';
        node.className = 'form-message' + (ok === true ? ' is-ok' : ok === false ? ' is-error' : '');
    }

    /* --------------------------------------------------------- arm dialog */

    /** Which of the dialog's two shapes is active. */
    let armMode = 'window';

    /**
     * Opens the arming dialog.
     *
     * @param intent 'arm' for a fresh window, 'rearm' to replace the armed one - which only
     *               changes the wording and what the fields start out as
     */
    function openArmDialog(intent) {
        const dialog = el('arm-dialog');
        const selling = state.selling;

        el('arm-dialog-title').textContent = intent === 'rearm' ? t('selling.rearmTitle') : t('selling.armTitle');
        el('arm-submit').textContent = intent === 'rearm' ? t('selling.rearm') : t('selling.arm');
        setDialogMessage('', null);

        // Re-arming starts from the current window; arming fresh starts from the next hour.
        if (intent === 'rearm' && selling?.from) {
            el('arm-from').value = toInputValue(new Date(selling.from));
            el('arm-to').value = toInputValue(new Date(selling.to));
        } else {
            const start = new Date();
            start.setMinutes(0, 0, 0);
            start.setHours(start.getHours() + 1);

            el('arm-from').value = toInputValue(start);
            el('arm-to').value = toInputValue(new Date(start.getTime() + 2 * 3600 * 1000));
        }

        el('arm-watts').value = selling?.watts || selling?.defaultWatts || '';
        setArmMode('window');
        updateArmPreview();

        dialog.showModal();
    }

    function setArmMode(mode) {
        armMode = mode;

        document.querySelectorAll('#arm-mode .segment').forEach(button =>
            button.classList.toggle('is-active', button.dataset.mode === mode));

        document.querySelectorAll('[data-mode-panel]').forEach(panel =>
            panel.hidden = panel.dataset.modePanel !== mode);

        updateArmPreview();
    }

    /** Spells out the window the current inputs describe, so nobody arms a surprise. */
    function updateArmPreview() {
        const preview = el('arm-preview');
        const watts = Number(el('arm-watts').value) || state.selling?.defaultWatts || 0;

        let from;
        let to;

        if (armMode === 'now') {
            const minutes = Number(el('arm-duration').value);
            from = new Date();
            to = new Date(from.getTime() + minutes * 60000);
        } else {
            from = el('arm-from').value ? new Date(el('arm-from').value) : null;
            to = el('arm-to').value ? new Date(el('arm-to').value) : null;
        }

        if (!from || !to || Number.isNaN(from.getTime()) || Number.isNaN(to.getTime()) || to <= from) {
            preview.textContent = '';
            return;
        }

        const minutes = Math.round((to - from) / 60000);
        const energy = (watts / 1000) * (minutes / 60);

        preview.textContent = t('selling.preview', {
            from: formatDateTime(from.toISOString()),
            to: formatDateTime(to.toISOString()),
            duration: humanMinutes(minutes),
            energy: formatNumber(energy, 1)
        });
    }

    function closeArmDialog() {
        el('arm-dialog').close();
    }

    async function submitArmDialog(event) {
        event.preventDefault();
        setDialogMessage('', null);

        const watts = el('arm-watts').value ? Number(el('arm-watts').value) : null;

        // In "start now" mode the server anchors the start to its own clock, so a browser
        // whose clock is off cannot arm a window that begins in the past or the future.
        const payload = armMode === 'now'
            ? {startNow: true, durationMinutes: Number(el('arm-duration').value), watts}
            : {from: el('arm-from').value, to: el('arm-to').value, watts};

        try {
            const result = await Api.armSelling(payload);

            if (!result.success) {
                setDialogMessage(actionMessage(result), false);
                return;
            }

            closeArmDialog();
            setMessage(actionMessage(result), true);
            await refresh();
        } catch (error) {
            setDialogMessage(error.message, false);
        }
    }

    async function disarm() {
        setMessage('', null);

        try {
            const result = await Api.cancelSelling();
            setMessage(actionMessage(result), result.success);
            await refresh();
        } catch (error) {
            setMessage(error.message, false);
        }
    }

    async function replanSelling() {
        setMessage('', null);

        try {
            const result = await Api.replanSelling();
            setMessage(actionMessage(result), result.success);
            await refresh();
        } catch (error) {
            setMessage(error.message, false);
        }
    }

    /* ------------------------------------------------- quick action handlers */

    /**
     * Runs one inverter command and reports it under the control it came from, so all of them
     * read the same way.
     */
    async function runQuickAction(call, messageId = 'quick-message') {
        const node = el(messageId);
        writeMessage(node, '', null);

        try {
            const result = await call();
            writeMessage(node, actionMessage(result), result.success);
            await refresh();
        } catch (error) {
            writeMessage(node, error.message, false);
        }
    }

    function setWorkMode(mode) {
        return runQuickAction(() => Api.setWorkMode(mode), 'quick-message');
    }

    function chargeFromGrid() {
        const target = targetSoc();

        // With a target the inverter decides when to stop, so sending a duration as well
        // would only be a second answer to a question that already has one.
        return runQuickAction(() => Api.chargeFromGrid(target === null
            ? {watts: chargeWatts(), durationMinutes: Number(el('quick-duration').value)}
            : {watts: chargeWatts(), targetSoc: target}), 'charge-message');
    }

    /** The target SOC field, or null when it is empty or nonsense. */
    function targetSoc() {
        const raw = el('quick-target-soc').value;

        if (raw === '') {
            return null;
        }

        const value = Number(raw);
        return Number.isFinite(value) && value >= 1 && value <= 100 ? Math.round(value) : null;
    }

    function chargeWatts() {
        return Number(el('quick-watts').value) || null;
    }

    /**
     * Spells out which of the two shapes the next charge will take, because the difference
     * between "for two hours" and "until 90 %" is the whole point of the field.
     */
    function updateChargePreview() {
        const target = targetSoc();
        const duration = el('quick-duration');

        // The duration is not wrong when a target is set, it is unused - so it is dimmed
        // rather than emptied, and comes straight back when the target is cleared.
        duration.disabled = target !== null;
        duration.closest('.field').classList.toggle('is-muted', target !== null);

        el('quick-charge-preview').textContent = target === null
            ? t('quick.previewDuration', {duration: durationLabel()})
            : t('quick.previewSoc', {soc: target});
    }

    function durationLabel() {
        const select = el('quick-duration');
        return select.options[select.selectedIndex]?.text || '';
    }

    function exitRemoteControl() {
        return runQuickAction(() => Api.exitRemoteControl(), 'exit-message');
    }

    /* --------------------------------------------------------------- polling */

    function setConnection(key, className) {
        const node = el('connection');

        // Keep data-i18n in step, otherwise the next I18N.apply() resets the text.
        node.dataset.i18n = key;
        node.textContent = t(key);
        node.className = 'pill ' + className;
    }

    async function refresh() {
        try {
            const [overview, prices, weather, timeline, selling, balance, statistics] = await Promise.all([
                Api.overview(), Api.prices(), Api.weather(), Api.timeline(), Api.selling(),
                // Like the statistics, balancing is one control of the page, and an older
                // backend without it must not take the rest of the refresh down.
                Api.balance().catch(error => {
                    console.warn('Balancing unavailable', error);
                    return null;
                }),
                // The statistics are a section of the page, not the page: failing to load them
                // keeps what was shown last rather than taking the whole refresh down.
                Api.statistics(statsQuery()).catch(error => {
                    console.warn('Statistics unavailable', error);
                    return state.statistics;
                })
            ]);

            Object.assign(state, {overview, prices, weather, timeline, selling, balance, statistics});

            setConnection(overview.online ? 'status.online' : 'status.offline',
                overview.online ? 'pill-ok' : 'pill-warn');

            el('last-updated').textContent = formatDateTime(new Date().toISOString());

            // The charts are rebuilt from scratch, so any open tooltip is now orphaned.
            Charts.hideTooltip();
            renderAll();
        } catch (error) {
            console.error('Refresh failed', error);
            setConnection('status.error', 'pill-error');

            // Leaving the skeletons up would suggest the page is still loading.
            if (!state.overview) {
                renderAll();
            }
        }
    }

    /* ------------------------------------------------------------------ init */

    async function init() {
        applyTheme(localStorage.getItem(STORAGE_THEME) || 'system');

        // Before the first request, not after it - that request is what they cover for.
        renderSkeletons();

        window.matchMedia('(prefers-color-scheme: dark)')
            .addEventListener('change', () => {
                if (state.theme === 'system') {
                    applyTheme('system');
                }
            });

        try {
            state.config = await Api.config();
        } catch (error) {
            console.warn('Falling back to default dashboard settings', error);
        }

        const language = localStorage.getItem(STORAGE_LANGUAGE) || state.config.defaultLanguage || 'en';
        I18N.setLanguage(language);
        el('language').value = I18N.language;

        if (!localStorage.getItem(STORAGE_THEME) && state.config.defaultTheme) {
            applyTheme(state.config.defaultTheme);
        }

        state.historyRows = storedHistoryRows();
        renderHistoryRowsControl();

        el('history-rows').addEventListener('change', event => setHistoryRows(Number(event.target.value)));

        state.historyDay = storedHistoryDay();
        state.historyChangesOnly = localStorage.getItem(STORAGE_HISTORY_CHANGES) === '1';

        document.querySelectorAll('#history-day .segment').forEach(button =>
            button.addEventListener('click', () => setHistoryDay(button.dataset.day)));

        el('history-changes-only').addEventListener('click',
            () => setHistoryChangesOnly(!state.historyChangesOnly));

        state.timelineRange = storedRange(STORAGE_TIMELINE_RANGE);
        state.weatherRange = storedRange(STORAGE_WEATHER_RANGE);
        renderRangeSwitches();
        renderTimelineHint();

        document.querySelectorAll('#timeline-range .segment').forEach(button =>
            button.addEventListener('click', () => setTimelineRange(button.dataset.range)));

        document.querySelectorAll('#weather-range .segment').forEach(button =>
            button.addEventListener('click', () => setWeatherRange(button.dataset.range)));

        el('timeline-chart-toggle').addEventListener('click', toggleTimelineChart);
        renderTimelineChartToggle();

        state.statsRange = storedStatsRange();
        state.statsMonth = monthKey(new Date());
        markStatsRange();

        document.querySelectorAll('#stats-range .segment').forEach(button =>
            button.addEventListener('click', () => {
                // A rolling range always ends today, and the month browser starts over from this month.
                state.statsMonth = monthKey(new Date());
                setStatsRange(button.dataset.days);
            }));

        el('stats-month').addEventListener('change', event => setStatsMonth(event.target.value));
        el('stats-month-prev').addEventListener('click', previousStatsMonth);
        el('stats-month-next').addEventListener('click', nextStatsMonth);

        // The table is only built while it is open, so opening it has to build it.
        el('stats-details').addEventListener('toggle', () => {
            const hint = el('stats-details').querySelector('summary .card-hint');
            hint.dataset.i18n = el('stats-details').open ? 'stats.tableHide' : 'stats.tableHint';
            hint.textContent = t(hint.dataset.i18n);
            renderStatsTable(state.statistics);
        });

        renderLanguageControl();

        window.addEventListener('resize', onViewportChange);
        window.addEventListener('orientationchange', onViewportChange);
        COMPACT_QUERY.addEventListener('change', onViewportChange);
        NARROW_QUERY.addEventListener('change', onViewportChange);

        showPage(localStorage.getItem(STORAGE_PAGE) || 'overview');

        document.querySelectorAll('.tab').forEach(tab =>
            tab.addEventListener('click', () => showPage(tab.dataset.page)));

        bindDayNav('price-day-nav', setPriceDay);
        bindDayNav('weather-day-nav', setWeatherDay);

        el('language').addEventListener('change', event => {
            I18N.setLanguage(event.target.value);
            localStorage.setItem(STORAGE_LANGUAGE, I18N.language);
            renderAll();
        });

        el('theme-toggle').addEventListener('click', cycleTheme);

        // The quality formula is reference material, not something to read every visit.
        el('weather-info-toggle').addEventListener('click', () => {
            const panel = el('weather-info');
            const showing = panel.hidden;

            panel.hidden = !showing;
            el('weather-info-toggle').setAttribute('aria-expanded', String(showing));
            el('weather-info-toggle').classList.toggle('is-active', showing);
        });
        el('arm-form').addEventListener('submit', submitArmDialog);
        el('arm-close').addEventListener('click', closeArmDialog);
        el('arm-cancel').addEventListener('click', closeArmDialog);

        document.querySelectorAll('#arm-mode .segment').forEach(button =>
            button.addEventListener('click', () => setArmMode(button.dataset.mode)));

        ['arm-from', 'arm-to', 'arm-duration', 'arm-watts'].forEach(id =>
            el(id).addEventListener('input', updateArmPreview));

        ['quick-duration', 'quick-target-soc'].forEach(id =>
            el(id).addEventListener('input', updateChargePreview));

        // Clicking the backdrop closes the dialog, which <dialog> does not do on its own.
        el('arm-dialog').addEventListener('click', event => {
            if (event.target === el('arm-dialog')) {
                closeArmDialog();
            }
        });

        I18N.apply();
        installPrompt();
        registerServiceWorker();

        await refresh();

        setInterval(refresh, Math.max(5, state.config.refreshSeconds) * 1000);
    }

    /* ------------------------------------------------------------------- pwa */

    /** True when the page is already running as an installed app rather than in a tab. */
    function runningInstalled() {
        return window.matchMedia('(display-mode: standalone)').matches
            || window.matchMedia('(display-mode: minimal-ui)').matches
            || navigator.standalone === true;
    }

    /**
     * Wires up the install button.
     * <p>
     * Chrome fires beforeinstallprompt when the page qualifies - a manifest, a service
     * worker, a secure origin - and never again once the app is installed, so the event is
     * caught at the top of this file rather than here: init awaits the configuration
     * request first, and the offer can land before that comes back.
     * <p>
     * The button is offered whether or not that happened. Showing it only on the offer meant
     * that on the two occasions someone actually goes looking for it - an iPhone, where the
     * event does not exist, and a dashboard opened over plain http, where no browser will
     * install anything - there was nothing on the page at all, and no way to find out why.
     * With no offer to open, the button opens the dialog that explains the situation.
     */
    function installPrompt() {
        const button = el('install-app');

        button.classList.toggle('is-installed', runningInstalled());

        button.addEventListener('click', async () => {
            if (!deferredInstall) {
                openInstallDialog();
                return;
            }

            deferredInstall.prompt();
            await deferredInstall.userChoice;

            // The event is single-use, whatever the answer was.
            deferredInstall = null;
        });

        el('install-close').addEventListener('click', () => el('install-dialog').close());
        el('install-dismiss').addEventListener('click', () => el('install-dialog').close());

        el('install-dialog').addEventListener('click', event => {
            if (event.target === el('install-dialog')) {
                el('install-dialog').close();
            }
        });

        window.addEventListener('appinstalled', () => {
            deferredInstall = null;
            button.classList.add('is-installed');
        });
    }

    /**
     * Says why the browser has not offered to install the dashboard, and what to do instead.
     * <p>
     * There are two reasons, and they need different answers. Over a plain http address -
     * which is how a dashboard on the local network is normally reached - no browser will
     * install anything at all, and the way out is an address that is https or a per-device
     * exception. Over an address that is fine, the browser simply has no prompt of its own
     * (Safari, Firefox), and the way in is its own menu.
     */
    function openInstallDialog() {
        const body = el('install-body');
        const origin = window.location.origin;

        const note = document.createElement('p');
        note.className = 'install-note';

        const steps = document.createElement('ul');
        steps.className = 'install-steps';

        const step = (title, text) => {
            const item = document.createElement('li');

            const strong = document.createElement('strong');
            strong.textContent = title + ' ';
            item.appendChild(strong);
            item.appendChild(document.createTextNode(text));

            steps.appendChild(item);
            return item;
        };

        if (!window.isSecureContext) {
            // The address is the whole problem, so it is set apart rather than buried in the line.
            note.textContent = t('app.installInsecure') + ' ';

            const address = document.createElement('code');
            address.textContent = origin;
            note.appendChild(address);

            /*
               Ordered by what it costs the reader. The iPhone one is first because it costs
               nothing at all: only Chrome ties the home screen to a secure address, and an
               iPhone will add this page as a full screen app from here as it stands.
            */
            step(t('app.installIosNowTitle'), t('app.installIosNow'));

            const flag = step(t('app.installFlagTitle'), t('app.installFlag'));
            const flagAddress = document.createElement('code');
            flagAddress.textContent = origin;
            flag.appendChild(document.createTextNode(' '));
            flag.appendChild(flagAddress);

            step(t('app.installProxyTitle'), t('app.installProxy'));
        } else {
            note.textContent = t('app.installManual');

            step(t('app.installIosTitle'), t('app.installIos'));
            step(t('app.installChromeTitle'), t('app.installChrome'));
            step(t('app.installFirefoxTitle'), t('app.installFirefox'));
        }

        body.replaceChildren(note, steps);
        el('install-dialog').showModal();
    }

    /**
     * The service worker is what lets the installed app open as itself - rather than as the
     * browser's offline page - while the phone is off the home network. It caches the page
     * and its assets, never the data.
     * <p>
     * Registration needs a secure context, which over the local network means the
     * dashboard has to be reached over https or through localhost. On plain http the
     * browser refuses, so it is not attempted: the page works exactly as before, just
     * without offline support.
     */
    function registerServiceWorker() {
        if (!('serviceWorker' in navigator) || !window.isSecureContext) {
            return;
        }

        navigator.serviceWorker.register('sw.js')
            .catch(error => console.warn('Service worker not registered', error));
    }

    document.addEventListener('DOMContentLoaded', init);
})();
