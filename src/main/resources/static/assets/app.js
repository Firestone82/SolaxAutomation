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
    const STORAGE_TIMELINE_RANGE = 'solax.timelineRange';
    const STORAGE_WEATHER_RANGE = 'solax.weatherRange';
    const STORAGE_TIMELINE_CHART = 'solax.timelineChart';

    const state = {
        config: {refreshSeconds: 30, allowControl: true, currency: 'CZK', defaultLanguage: 'en', defaultTheme: 'system'},
        overview: null,
        prices: null,
        weather: null,
        timeline: null,
        modules: [],
        selling: null,
        priceDay: 'today',
        page: 'overview',
        theme: 'system',
        historyRows: 10,

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

    /** Config label, translated through the key the backend supplies. */
    const configLabel = entry => I18N.message(entry.i18nKey, entry.i18nParams, entry.label);

    /** Config help text, translated through `<i18nKey>.desc`. */
    const configDescription = entry =>
        I18N.message(entry.i18nKey ? entry.i18nKey + '.desc' : null, null, entry.description);

    /** Module status headline, e.g. "Export limit unchanged". */
    const moduleSummary = module => I18N.message(module.summaryKey, module.summaryParams, module.summary) || '';

    /** The sentence under it, saying what the module actually decided and why. */
    const moduleDetail = module =>
        I18N.message(module.summaryDetailKey, module.summaryDetailParams, module.summaryDetail) || '';

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

    function showPage(page) {
        state.page = page;
        localStorage.setItem(STORAGE_PAGE, page);

        document.querySelectorAll('.tab').forEach(tab =>
            tab.classList.toggle('is-active', tab.dataset.page === page));

        el('page-overview').hidden = page !== 'overview';
        el('page-modules').hidden = page !== 'modules';

        // A chart drawn while its page was hidden had no width to measure, so it was drawn
        // at the fallback size. Coming back to the page is when its real width exists.
        if (page === 'overview' && state.overview) {
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
            if (state.page === 'overview' && state.overview) {
                renderCharts();
            }

            renderTimelineChartToggle();
            renderLanguageControl();
        }, 150);
    }

    /** Just the three charts - the lists and cards around them do not depend on the width. */
    function renderCharts() {
        Charts.hideTooltip();
        renderPrices();
        renderWeather();
        renderWorkMode();
        renderTimeline();
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

        const push = (label, value, unit, note, bar) => tiles.push({label, value, unit, note, bar});

        push(
            t('stat.battery'),
            formatNumber(overview.batterySoc, 0),
            '%',
            overview.batteryRemainingKwh !== null && overview.batteryRemainingKwh !== undefined
                ? t('stat.batteryNote', {
                    remaining: formatNumber(overview.batteryRemainingKwh, 1),
                    soh: formatNumber(overview.batterySoh, 0)
                })
                : null,
            overview.batterySoc
        );

        const pv = formatPower(overview.pvPower);
        push(t('stat.pv'), pv.value, pv.unit, null, null);

        if (overview.gridPower !== null && overview.gridPower !== undefined) {
            const grid = formatPower(Math.abs(overview.gridPower));

            // At zero neither direction is true, so saying one of them would be wrong.
            const direction = overview.gridPower === 0
                ? null
                : (overview.gridPower > 0 ? t('stat.gridExport') : t('stat.gridImport'));

            push(t('stat.grid'), grid.value, grid.unit, direction, null);
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
            push(t('stat.today'), formatNumber(overview.dailyYield, 1), 'kWh',
                t('stat.todayNote', {
                    export: formatNumber(overview.dailyExport ?? 0, 1),
                    import: formatNumber(overview.dailyImport ?? 0, 1)
                }), null);
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

        container.replaceChildren(...tiles.map(tile => {
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

            if (tile.note) {
                const note = document.createElement('span');
                note.className = 'stat-note';
                note.textContent = tile.note;
                node.appendChild(note);
            }

            if (typeof tile.bar === 'number') {
                const bar = document.createElement('div');
                bar.className = 'stat-bar';

                const fill = document.createElement('i');
                fill.style.width = Math.max(0, Math.min(100, tile.bar)) + '%';
                fill.style.background = tile.bar < 25 ? 'var(--danger)' : (tile.bar < 50 ? 'var(--warning)' : 'var(--success)');

                bar.appendChild(fill);
                node.appendChild(bar);
            }

            return node;
        }));
    }

    /**
     * The inverter's running status. Translated through its numeric code where the dashboard
     * knows it, and left as the English label the backend derived otherwise - the vendor's
     * list is long and mostly made of states nobody ever sees.
     */
    function deviceStatus(overview) {
        return I18N.message('device.' + overview.deviceStatusCode, null, overview.deviceStatus);
    }

    /** Points for the day the switch is on, or null when tomorrow is not published. */
    function selectedPricePoints() {
        const prices = state.prices;

        if (!prices) {
            return null;
        }

        if (state.priceDay === 'tomorrow') {
            return prices.tomorrowPublished ? prices.tomorrow : null;
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
        const points = selectedPricePoints();

        renderPriceLegend();

        if (!points) {
            const message = state.priceDay === 'tomorrow' ? t('prices.noTomorrow') : t('prices.none');
            Charts.priceChart(container, [], {emptyMessage: message});
            summaryLine.textContent = '';
            return;
        }

        const prices = state.prices;
        const summary = priceSummary(points);

        Charts.priceChart(container, points, {
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

    /** What the automation makes of this interval, in three words, for the tooltip. */
    function automationLabel(point) {
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
     * The window the forecast curve covers: the day ahead, or today from midnight.
     * <p>
     * The whole-day window is a fixed 24 hours whether or not there is a reading for every
     * hour of it - an application started at nine has nothing for the morning, and an axis
     * that quietly ends at the last reading it has would read as a shorter day rather than
     * as a missing one.
     */
    function weatherWindow() {
        const now = new Date();

        if (state.weatherRange === 'day') {
            const start = startOfDay(now);
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
        const range = weatherWindow();
        const points = weatherPoints(range);

        el('weather-formula').textContent = weather?.qualityFormula || '';
        renderWeatherGap(range, points);

        Charts.weatherChart(el('weather-chart'), points, {
            emptyMessage: t('weather.none'),
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
     * Says so when the window reaches back further than the recorded hours do.
     * <p>
     * The forecast only ever looks forward, so the hours behind us are the ones this
     * application saw go by. After a restart there is a gap at the start of the day, and an
     * empty stretch of axis with no explanation looks like a broken chart.
     */
    function renderWeatherGap(range, points) {
        const note = el('weather-gap');
        const first = points.length ? new Date(points[0].dateTime) : null;

        // An hour of slack: the window starts on the hour, so a first point exactly there
        // is a full window, not a gap. With no forecast at all the chart says so itself,
        // and saying it twice helps nobody.
        const missing = state.weatherRange === 'day'
            && (state.weather?.hours?.length || 0) > 0
            && (!first || first - range.start >= 3600 * 1000);

        note.hidden = !missing;

        if (missing) {
            note.textContent = first
                ? t('weather.recordedFrom', {time: formatTimeOnly(first.toISOString())})
                : t('weather.recordedNone');
        }
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

    function moduleNames() {
        return Object.fromEntries(state.modules.map(module =>
            [module.id, moduleText(module.id, 'name', module.name)]));
    }

    /* ------------------------------------------------- work mode over time */

    /**
     * One colour per work mode, used by the band and its legend alike.
     * <p>
     * Feed-in priority keeps the accent the timeline already paints work mode changes in,
     * self use is the quiet green of production staying home, backup the amber of something
     * being held in reserve, and manual the selling purple - manual only ever happens because
     * someone or something is driving the battery directly.
     */
    const MODE_COLOURS = {
        SELF_USE: 'var(--success)',
        FEED_IN_PRIORITY: 'var(--accent)',
        BACKUP: 'var(--warning)',
        MANUAL: 'var(--sell)'
    };

    /**
     * The day's work mode, rebuilt from the changes the automation recorded.
     * <p>
     * Nothing samples the inverter into a history, so the band is an inference: the mode
     * between two recorded changes is whatever the earlier one set. That makes the boundaries
     * of what can be known worth drawing honestly rather than papering over:
     * <ul>
     *   <li>before the first change of the day the mode is the one that change moved away
     *       from - which only a module records, so a day that opens with a change made from
     *       the dashboard opens as an unknown stretch instead of a guess;</li>
     *   <li>with no change recorded at all the mode has not moved since before midnight, so
     *       the live mode is drawn across the whole day and the tooltip says why;</li>
     *   <li>a mode set outside this application - the SolaX app, the inverter's own panel -
     *       is invisible here. When the live mode disagrees with the last change recorded,
     *       the note under the chart says so rather than the band inventing a change.</li>
     * </ul>
     */
    function workModeSegments() {
        const now = new Date();
        const start = startOfDay(now);

        // The history reads newest first, which is what the activity list wants and the
        // opposite of what a band does.
        const changes = (state.timeline?.history || [])
            .filter(entry => entry.type === 'WORK_MODE_CHANGE' && entry.success && entry.params?.to)
            .map(entry => ({at: new Date(entry.at), to: entry.params.to, from: entry.params.from || null, entry}))
            .filter(change => change.at >= start && change.at <= now)
            .sort((first, second) => first.at - second.at);

        const live = state.overview?.workMode || null;

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

    function workModeTooltip(segment) {
        const mode = segment.mode ? t('mode.' + segment.mode) : t('workMode.unknown');

        const subtitle = segment.changedBy
            ? moduleText(segment.changedBy, 'name', segment.changedBy)
            : (segment.held ? t('workMode.held') : t('workMode.beforeFirst'));

        return {
            title: mode,
            subtitle: subtitle,
            accent: MODE_COLOURS[segment.mode] || 'var(--text-faint)',
            rows: [
                [t('tooltip.when'), `${formatTimeOnly(segment.from.toISOString())} – ${formatTimeOnly(segment.to.toISOString())}`],
                [t('tooltip.duration'), durationBetween(segment.from.toISOString(), segment.to.toISOString())],
                [t('workMode.setBy'), segment.entry ? msg(segment.entry) : null]
            ],
            note: segment.entry ? detailOf(segment.entry) : (segment.mode ? null : t('workMode.unknownNote'))
        };
    }

    function renderWorkMode() {
        const segments = workModeSegments();
        const now = new Date();
        const start = startOfDay(now);

        Charts.workModeChart(el('workmode-chart'), segments, {
            emptyMessage: t('workMode.none'),
            start: start,
            end: new Date(start.getTime() + DAY_MS),
            now: now,
            colours: MODE_COLOURS,
            labels: Object.fromEntries(Object.keys(MODE_COLOURS).map(mode => [mode, t('mode.' + mode)])),
            tooltip: workModeTooltip
        });

        renderWorkModeNote(segments);
        renderWorkModeLegend(segments);
    }

    /**
     * The one thing the band cannot show: a mode this application did not set.
     * <p>
     * The inverter can be moved from the SolaX app or its own panel, and nothing tells us
     * when that happened - so the band stops at the last change we know of and the mismatch
     * is stated here instead of being drawn as a change at an invented time.
     */
    function renderWorkModeNote(segments) {
        const note = el('workmode-note');
        const live = state.overview?.workMode || null;
        const last = segments.length ? segments[segments.length - 1] : null;

        const outside = live && last && !last.held && last.mode && last.mode !== live;

        note.hidden = !outside;

        if (outside) {
            note.textContent = t('workMode.changedOutside', {mode: t('mode.' + live)});
        }
    }

    /** Only the modes the day actually contains - a legend of four is mostly noise. */
    function renderWorkModeLegend(segments) {
        const legend = el('workmode-legend');
        const modes = [...new Set(segments.map(segment => segment.mode).filter(Boolean))];

        legend.replaceChildren(...modes.map(mode => {
            const item = document.createElement('li');

            const swatch = document.createElement('i');
            swatch.className = 'swatch';
            swatch.style.background = MODE_COLOURS[mode] || 'var(--text-faint)';
            item.appendChild(swatch);

            item.appendChild(textSpan(t('mode.' + mode)));
            return item;
        }));
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

        renderEntryList(el('timeline-history'), timeline?.history || [], t('history.none'), entry => ({
            time: formatDateTime(entry.at),
            summary: msg(entry),
            // What the module did, in a sentence - the headline above only names the outcome.
            detail: detailOf(entry),
            badge: t('action.' + entry.type),
            badgeClass: entry.success ? 'pill-ok' : 'pill-error'
        }), state.historyRows);
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
        markRange('weather-range', range);
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
            button.className = 'button button-small';
            button.textContent = label;
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
            step(-1, '‹', page === 0),
            position,
            step(1, '›', page >= pages - 1)
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

        const notes = [
            selling.running ? t('selling.endsIn', {duration: relativeTo(selling.to)}) : relativeToStart(selling.from),
            selling.manual ? t('selling.manual') : t('selling.automatic')
        ].filter(Boolean);

        const footer = document.createElement('p');
        footer.className = 'selling-hero-note';
        footer.textContent = notes.join(' · ');
        hero.appendChild(footer);
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
     * Manual overrides. Two groups, because the two kinds of command behave differently
     * and confusing them is expensive: a work mode sticks until something changes it,
     * a remote control session expires on its own.
     */
    function renderQuickActions() {
        const overview = state.overview;
        const controllable = state.config.allowControl;

        renderQuickBadge(overview);

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

        el('quick-actions').replaceChildren(
            button(t('quick.charge'), '', chargeFromGrid, remote),
            // Same window the selling card arms - this is just the shorter way to reach it.
            button(t('quick.sell'), '', () => openArmDialog('arm'), state.selling?.enabled),
            button(t('quick.exit'), 'button-danger', exitRemoteControl, remote)
        );

        // Its own line, not the message line: this is a standing fact about the setup, and
        // it must not wipe out what the last button press reported.
        const note = !controllable ? t('selling.controlDisabled')
            : !remote ? t('quick.remoteUnavailable')
                : '';

        el('quick-note').textContent = note;
        el('quick-note').hidden = !note;

        updateChargePreview();
    }

    /** Says what the inverter is doing right now, which is the context for every button. */
    function renderQuickBadge(overview) {
        const badge = el('quick-mode');

        if (!overview?.online) {
            badge.textContent = t('status.offline');
            badge.className = 'pill pill-warn';
            return;
        }

        if (overview.remoteControlActive) {
            badge.textContent = t('quick.underRemoteControl');
            badge.className = 'pill pill-sell';
            return;
        }

        badge.textContent = overview.workMode ? t('mode.' + overview.workMode) : '—';
        badge.className = 'pill pill-muted';
    }

    const STATE_PILLS = {
        DISABLED: 'pill-muted',
        IDLE: 'pill-muted',
        RUNNING: 'pill-active',
        ACTIVE: 'pill-ok',
        DEGRADED: 'pill-warn',
        FAILED: 'pill-error'
    };

    function renderModules() {
        const grid = el('module-grid');

        if (!state.modules.length) {
            const empty = document.createElement('p');
            empty.className = 'empty-row';
            empty.textContent = t('modules.none');
            grid.replaceChildren(empty);
            return;
        }

        grid.replaceChildren(...state.modules.map(renderModuleCard));
    }

    /**
     * One module widget, built as three clearly separated blocks: what it is and
     * how it is doing, what it is configured with, and what it will do next.
     */
    function renderModuleCard(module) {
        const card = document.createElement('article');
        card.className = 'module-card' + (module.enabled ? '' : ' is-disabled');

        card.appendChild(moduleHeader(module));

        // Everything below the header scrolls together, so a long configuration table
        // cannot stretch the whole row of cards.
        const body = document.createElement('div');
        body.className = 'module-body';
        body.appendChild(moduleInfoSection(module));
        body.appendChild(moduleConfigSection(module));
        body.appendChild(modulePlannedSection(module));

        card.appendChild(body);
        return card;
    }

    function moduleHeader(module) {
        const head = document.createElement('div');
        head.className = 'module-head';

        const titleBlock = document.createElement('div');
        titleBlock.className = 'module-title';

        const name = document.createElement('h2');
        name.textContent = moduleText(module.id, 'name', module.name);
        titleBlock.appendChild(name);

        const prefix = document.createElement('code');
        prefix.textContent = module.configPrefix;
        titleBlock.appendChild(prefix);

        head.appendChild(titleBlock);

        const toggle = document.createElement('label');
        toggle.className = 'switch';
        toggle.title = module.configPrefix + '.enabled';

        const input = document.createElement('input');
        input.type = 'checkbox';
        input.checked = module.enabled;
        input.disabled = !state.config.allowControl;
        input.addEventListener('change', () => toggleModule(module.id, input.checked));
        toggle.appendChild(input);

        const track = document.createElement('span');
        track.className = 'switch-track';
        toggle.appendChild(track);

        head.appendChild(toggle);
        return head;
    }

    /** What the module is, and how its last run went. */
    function moduleInfoSection(module) {
        const section = moduleSection(t('modules.status'));

        const description = document.createElement('p');
        description.className = 'module-desc';
        description.textContent = moduleText(module.id, 'description', module.description);
        section.appendChild(description);

        // A module that has not run yet has nothing to report, and "Idle - not run yet"
        // says less than the schedule below it already does. The line appears once it
        // means something: the module acted, or it is doing something now.
        const summaryText = moduleSummary(module);
        const neverRun = module.state === 'IDLE' && !module.runCount;

        if (!neverRun) {
            const status = document.createElement('div');
            status.className = 'module-status';

            const statePill = document.createElement('span');
            statePill.className = 'pill pill-state ' + (STATE_PILLS[module.state] || 'pill-muted');
            statePill.textContent = t('state.' + module.state);
            status.appendChild(statePill);

            if (summaryText) {
                const summary = document.createElement('span');
                summary.className = 'module-summary';
                summary.textContent = summaryText;
                status.appendChild(summary);
            }

            section.appendChild(status);

            // The headline says what happened, this says why - the same split the activity
            // list uses, so the two read the same way.
            const detailText = moduleDetail(module);

            if (detailText) {
                const detail = document.createElement('p');
                detail.className = 'module-status-detail';
                detail.textContent = detailText;
                section.appendChild(detail);
            }
        }

        const facts = document.createElement('dl');
        facts.className = 'module-facts';

        // dt and dd are wrapped together so each pair is one grid cell rather than two.
        const addFact = (label, value, danger) => {
            const pair = document.createElement('div');

            const term = document.createElement('dt');
            term.textContent = label;
            pair.appendChild(term);

            const definition = document.createElement('dd');
            definition.textContent = value;

            if (danger) {
                definition.style.color = 'var(--danger)';
            }

            pair.appendChild(definition);
            facts.appendChild(pair);
        };

        addFact(t('modules.lastRun'), module.lastRunAt ? formatDateTime(module.lastRunAt) : t('modules.never'));
        addFact(t('modules.nextRun'), module.nextRunAt ? formatDateTime(module.nextRunAt) : '—');
        addFact(t('modules.runCount'), String(module.runCount));

        if (module.failCount > 0) {
            addFact(t('modules.failCount'), String(module.failCount), true);
        }

        section.appendChild(facts);

        if (module.lastError) {
            const error = document.createElement('p');
            error.className = 'module-error';
            error.textContent = module.lastError;
            section.appendChild(error);
        }

        return section;
    }

    function moduleConfigSection(module) {
        const section = moduleSection(t('modules.configuration'));

        if (!module.configuration?.length) {
            section.appendChild(emptyNote(t('modules.noConfiguration')));
            return section;
        }

        const table = document.createElement('table');
        table.className = 'config-table';

        const body = document.createElement('tbody');

        module.configuration.forEach(entry => {
            const row = document.createElement('tr');

            const label = document.createElement('td');
            label.className = 'config-label';
            label.textContent = configLabel(entry);

            const help = configDescription(entry);
            if (help) {
                const description = document.createElement('small');
                description.textContent = help;
                label.appendChild(description);
            }

            row.appendChild(label);

            const value = document.createElement('td');
            value.className = 'config-value';
            value.textContent = entry.unit ? `${entry.value} ${entry.unit}` : String(entry.value);
            row.appendChild(value);

            body.appendChild(row);
        });

        table.appendChild(body);
        section.appendChild(table);
        return section;
    }

    /**
     * A module that checks every quarter of an hour publishes dozens of runs a day. The
     * timeline wants all of them; a widget wants the next few and a count of the rest.
     */
    const PLANNED_IN_WIDGET = 6;

    function modulePlannedSection(module) {
        const section = moduleSection(t('modules.planned'));

        if (!module.planned?.length) {
            section.appendChild(emptyNote(t('modules.noPlanned')));
            return section;
        }

        const actions = document.createElement('ul');
        actions.className = 'module-actions';

        module.planned.slice(0, PLANNED_IN_WIDGET).forEach(action => {
            const item = document.createElement('li');

            const time = document.createElement('time');
            time.dateTime = action.from;
            time.textContent = action.to
                ? `${formatDateTime(action.from)}–${formatDateTime(action.to)}`
                : formatDateTime(action.from);
            item.appendChild(time);

            const summary = document.createElement('span');
            summary.textContent = msg(action);
            item.appendChild(summary);

            actions.appendChild(item);
        });

        section.appendChild(actions);

        const hidden = module.planned.length - PLANNED_IN_WIDGET;
        if (hidden > 0) {
            section.appendChild(emptyNote(t('modules.morePlanned', {count: hidden})));
        }

        return section;
    }

    /** A titled block inside a module card. */
    function moduleSection(title) {
        const section = document.createElement('section');
        section.className = 'module-section';
        section.appendChild(sectionTitle(title));
        return section;
    }

    function emptyNote(text) {
        const note = document.createElement('p');
        note.className = 'module-empty';
        note.textContent = text;
        return note;
    }

    function sectionTitle(text) {
        const node = document.createElement('h3');
        node.className = 'module-section-title';
        node.textContent = text;
        return node;
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
        renderModules();
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
        el('module-grid').replaceChildren(...block('skeleton-card', 4));
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

    async function toggleModule(id, enabled) {
        try {
            await Api.setModuleEnabled(id, enabled);
            await refresh();
        } catch (error) {
            console.error('Failed to toggle module', id, error);
            await refresh();
        }
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

    function setQuickMessage(text, ok) {
        writeMessage(el('quick-message'), text, ok);
    }

    /** Runs one inverter command and reports it, so all three read the same way. */
    async function runQuickAction(call) {
        setQuickMessage('', null);

        try {
            const result = await call();
            setQuickMessage(actionMessage(result), result.success);
            await refresh();
        } catch (error) {
            setQuickMessage(error.message, false);
        }
    }

    function setWorkMode(mode) {
        return runQuickAction(() => Api.setWorkMode(mode));
    }

    function chargeFromGrid() {
        const target = targetSoc();

        // With a target the inverter decides when to stop, so sending a duration as well
        // would only be a second answer to a question that already has one.
        return runQuickAction(() => Api.chargeFromGrid(target === null
            ? {watts: chargeWatts(), durationMinutes: Number(el('quick-duration').value)}
            : {watts: chargeWatts(), targetSoc: target}));
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
        return runQuickAction(() => Api.exitRemoteControl());
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
            const [overview, prices, weather, timeline, modules, selling] = await Promise.all([
                Api.overview(), Api.prices(), Api.weather(), Api.timeline(), Api.modules(), Api.selling()
            ]);

            Object.assign(state, {overview, prices, weather, timeline, modules, selling});

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

        renderLanguageControl();

        window.addEventListener('resize', onViewportChange);
        window.addEventListener('orientationchange', onViewportChange);
        COMPACT_QUERY.addEventListener('change', onViewportChange);
        NARROW_QUERY.addEventListener('change', onViewportChange);

        showPage(localStorage.getItem(STORAGE_PAGE) || 'overview');

        document.querySelectorAll('.tab').forEach(tab =>
            tab.addEventListener('click', () => showPage(tab.dataset.page)));

        document.querySelectorAll('#price-day-switch .segment').forEach(button =>
            button.addEventListener('click', () => {
                state.priceDay = button.dataset.day;
                document.querySelectorAll('#price-day-switch .segment').forEach(other =>
                    other.classList.toggle('is-active', other === button));
                renderPrices();
            }));

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
