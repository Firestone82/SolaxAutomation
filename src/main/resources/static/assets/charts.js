/* ============================================================================
   Hand-rolled SVG charts.
   No chart library on purpose: the dashboard has to work on a Raspberry Pi
   with no internet access, and three chart types do not justify a dependency.
   Colours all come from CSS custom properties, so the charts follow the theme
   without being redrawn.

   Every chart shares one hover tooltip. It is positioned from the mouse event's
   client coordinates rather than from SVG units, so it stays correct whatever
   the viewBox scales to.
   ========================================================================= */

const Charts = (() => {

    const SVG_NS = 'http://www.w3.org/2000/svg';

    function element(name, attributes = {}) {
        const node = document.createElementNS(SVG_NS, name);

        Object.entries(attributes).forEach(([key, value]) => {
            if (value !== null && value !== undefined) {
                node.setAttribute(key, String(value));
            }
        });

        return node;
    }

    function createSvg(width, height) {
        return element('svg', {
            viewBox: `0 0 ${width} ${height}`,
            preserveAspectRatio: 'none',
            role: 'img'
        });
    }

    function empty(container, message) {
        container.replaceChildren();

        const paragraph = document.createElement('p');
        paragraph.className = 'chart-empty';
        paragraph.textContent = message;
        container.appendChild(paragraph);
    }

    /* -------------------------------------------------------------- tooltip */

    /**
     * The single tooltip every chart hovers into.
     *
     * Content is passed in as {title, subtitle, accent, rows:[[label, value]]}
     * and rendered with textContent throughout, so nothing a chart shows can
     * inject markup.
     */
    const Tooltip = (() => {
        let node = null;

        function ensure() {
            if (node) {
                return node;
            }

            node = document.createElement('div');
            node.className = 'chart-tooltip';
            node.setAttribute('role', 'tooltip');
            node.hidden = true;
            document.body.appendChild(node);

            return node;
        }

        function render(content) {
            const tip = ensure();
            tip.replaceChildren();

            if (content.accent) {
                tip.style.setProperty('--tooltip-accent', content.accent);
            } else {
                tip.style.removeProperty('--tooltip-accent');
            }

            const header = document.createElement('div');
            header.className = 'chart-tooltip-title';
            header.textContent = content.title;
            tip.appendChild(header);

            if (content.subtitle) {
                const subtitle = document.createElement('div');
                subtitle.className = 'chart-tooltip-subtitle';
                subtitle.textContent = content.subtitle;
                tip.appendChild(subtitle);
            }

            (content.rows || []).forEach(([label, value]) => {
                if (value === null || value === undefined || value === '') {
                    return;
                }

                const row = document.createElement('div');
                row.className = 'chart-tooltip-row';

                const key = document.createElement('span');
                key.textContent = label;
                row.appendChild(key);

                const val = document.createElement('strong');
                val.textContent = value;
                row.appendChild(val);

                tip.appendChild(row);
            });

            if (content.note) {
                const note = document.createElement('div');
                note.className = 'chart-tooltip-note';
                note.textContent = content.note;
                tip.appendChild(note);
            }
        }

        /** Places the tooltip beside the cursor, flipping away from the viewport edges. */
        function position(event) {
            const tip = ensure();
            const box = tip.getBoundingClientRect();
            const margin = 14;

            let left = event.clientX + margin;
            if (left + box.width > window.innerWidth - 8) {
                left = event.clientX - box.width - margin;
            }

            let top = event.clientY - box.height - margin;
            if (top < 8) {
                top = event.clientY + margin;
            }

            tip.style.left = Math.max(8, left) + 'px';
            tip.style.top = Math.max(8, top) + 'px';
        }

        return {
            show(content, event) {
                render(content);
                ensure().hidden = false;
                position(event);
            },

            move(event) {
                if (node && !node.hidden) {
                    position(event);
                }
            },

            hide() {
                if (node) {
                    node.hidden = true;
                }
            }
        };
    })();

    /**
     * Makes a shape hoverable. {@code build} returns the tooltip content, or a
     * falsy value to show nothing.
     */
    function hoverable(shape, build, onEnter, onLeave) {
        shape.addEventListener('mouseenter', event => {
            const content = build();

            if (content) {
                Tooltip.show(content, event);
            }

            if (onEnter) {
                onEnter();
            }
        });

        shape.addEventListener('mousemove', Tooltip.move);

        shape.addEventListener('mouseleave', () => {
            Tooltip.hide();

            if (onLeave) {
                onLeave();
            }
        });

        return shape;
    }

    /* --------------------------------------------------------- price chart */

    /**
     * Hatch used to mark the intervals the export limit module closes the export in.
     * Defined once per chart, because a pattern is referenced by id.
     */
    function blockedHatch(id) {
        const defs = element('defs');
        const pattern = element('pattern', {
            id: id, width: 6, height: 6, patternUnits: 'userSpaceOnUse', patternTransform: 'rotate(45)'
        });

        pattern.appendChild(element('rect', {class: 'blocked-hatch-ground', width: 6, height: 6}));
        pattern.appendChild(element('line', {class: 'blocked-hatch-line', x1: 0, y1: 0, x2: 0, y2: 6}));

        defs.appendChild(pattern);
        return defs;
    }

    /**
     * Runs of consecutive points matching {@code matches}, as [from, to) index pairs.
     */
    function bands(points, matches) {
        const runs = [];

        points.forEach((point, index) => {
            if (!matches(point)) {
                return;
            }

            const last = runs[runs.length - 1];

            if (last && last[1] === index) {
                last[1] = index + 1;
            } else {
                runs.push([index, index + 1]);
            }
        });

        return runs;
    }

    /**
     * Bar chart of the day's quarter-hour prices.
     * Bars are coloured by price band, the interval that is running now gets an
     * outline, and intervals inside an armed selling window are painted in the
     * selling colour so the plan is readable at a glance.
     *
     * Two automation rules are drawn behind the bars, because what the day is worth
     * is exactly what they act on. Intervals where the price is under the export
     * limit module's minimum - production is not worth putting on the grid - are
     * hatched over their full height. Intervals the selling module may sell the
     * battery into, inside its search hours and at or above its own minimum price,
     * are washed in the selling colour instead, with that minimum drawn in as a
     * dashed line. Between them the chart answers "what will the automation make of
     * this day" without opening the module page.
     *
     * Each bar carries a full-height transparent hit area, so hovering anywhere
     * in the interval's column works rather than only over the bar itself.
     */
    function priceChart(container, points, options = {}) {
        if (!points || points.length === 0) {
            empty(container, options.emptyMessage || 'No data');
            return;
        }

        const width = 960;
        const height = 260;
        const padding = {top: 16, right: 12, bottom: 24, left: 44};

        const plotWidth = width - padding.left - padding.right;
        const plotHeight = height - padding.top - padding.bottom;

        const values = points.map(point => point.czkPerKwh);
        const maxValue = Math.max(...values, 0);
        const minValue = Math.min(...values, 0);
        const span = (maxValue - minValue) || 1;

        const scaleY = value => padding.top + plotHeight - ((value - minValue) / span) * plotHeight;
        const barWidth = plotWidth / points.length;

        const svg = createSvg(width, height);
        const hatchId = 'price-blocked-hatch';
        svg.appendChild(blockedHatch(hatchId));

        // Both bands are drawn first, so the bars and the axis stay on top of them.
        // Adjacent columns are merged into one rectangle: 96 abutting rectangles show
        // seams where their edges meet, and one band per run does not.
        bands(points, point => point.exportable === false).forEach(([from, to]) =>
            svg.appendChild(element('rect', {
                class: 'slot-blocked',
                x: padding.left + from * barWidth,
                y: padding.top,
                width: (to - from) * barWidth,
                height: plotHeight,
                fill: `url(#${hatchId})`
            })));

        bands(points, point => point.sellable === true).forEach(([from, to]) =>
            svg.appendChild(element('rect', {
                class: 'slot-sellable',
                x: padding.left + from * barWidth,
                y: padding.top,
                width: (to - from) * barWidth,
                height: plotHeight
            })));

        // horizontal grid + value axis
        const ticks = 4;
        for (let i = 0; i <= ticks; i++) {
            const value = minValue + (span * i) / ticks;
            const y = scaleY(value);

            svg.appendChild(element('line', {
                class: 'grid-line', x1: padding.left, x2: width - padding.right, y1: y, y2: y
            }));

            const text = element('text', {
                class: 'axis-text', x: padding.left - 6, y: y + 3, 'text-anchor': 'end'
            });
            text.textContent = value.toFixed(1);
            svg.appendChild(text);
        }

        // zero line, drawn stronger when prices go negative
        if (minValue < 0) {
            svg.appendChild(element('line', {
                class: 'axis-line', x1: padding.left, x2: width - padding.right, y1: scaleY(0), y2: scaleY(0)
            }));
        }

        // Minimum price a peak has to reach before the battery is sold at all.
        if (options.sellThreshold !== null && options.sellThreshold !== undefined
                && options.sellThreshold > minValue && options.sellThreshold < maxValue) {

            svg.appendChild(element('line', {
                class: 'threshold-sell',
                x1: padding.left, x2: width - padding.right,
                y1: scaleY(options.sellThreshold), y2: scaleY(options.sellThreshold),
                'stroke-width': 1.5, 'stroke-dasharray': '5 4'
            }));
        }

        const zeroY = scaleY(0);

        points.forEach((point, index) => {
            const x = padding.left + index * barWidth;
            const barHeight = Math.max(1, Math.abs(zeroY - scaleY(point.czkPerKwh)));

            const classes = ['bar-' + (point.level || 'medium')];
            if (point.selling) {
                classes.length = 0;
                classes.push('bar-selling');
            }
            if (point.exportable === false) {
                classes.push('bar-blocked');
            }
            if (point.current) {
                classes.push('bar-current');
            }

            svg.appendChild(element('rect', {
                class: classes.join(' '),
                x: x + 0.5,
                y: point.czkPerKwh >= 0 ? scaleY(point.czkPerKwh) : zeroY,
                width: Math.max(1, barWidth - 1),
                height: barHeight,
                rx: Math.min(2, barWidth / 3)
            }));
        });

        // hour labels every three hours
        points.forEach((point, index) => {
            if (point.minute !== 0 || point.hour % 3 !== 0) {
                return;
            }

            const text = element('text', {
                class: 'axis-text',
                x: padding.left + index * barWidth,
                y: height - 8,
                'text-anchor': 'middle'
            });
            text.textContent = String(point.hour).padStart(2, '0');
            svg.appendChild(text);
        });

        // hover layer, drawn last so it sits above the bars
        points.forEach((point, index) => {
            const x = padding.left + index * barWidth;

            const highlight = element('rect', {
                class: 'bar-hover', x: x, y: padding.top, width: barWidth, height: plotHeight, opacity: 0
            });
            svg.appendChild(highlight);

            const hit = element('rect', {
                class: 'hit-area', x: x, y: padding.top, width: barWidth, height: plotHeight
            });

            hoverable(
                hit,
                () => options.tooltip ? options.tooltip(point, index) : null,
                () => highlight.setAttribute('opacity', '1'),
                () => highlight.setAttribute('opacity', '0')
            );

            svg.appendChild(hit);
        });

        container.replaceChildren(svg);
    }

    /* ------------------------------------------------------- weather chart */

    /**
     * Line chart of the forecast quality with the automation's two thresholds
     * drawn in. The thresholds are unlabelled on purpose - the legend under the
     * chart names them, which keeps the plot itself uncluttered.
     * <p>
     * The x axis is time, not the position of a point in the list: a window the
     * data does not fill - a whole day whose morning was never recorded - has to
     * show the hours it is missing rather than stretching what it has across
     * them. The window is the caller's; without one it spans the points given.
     */
    function weatherChart(container, points, options = {}) {
        if (!points || points.length === 0) {
            empty(container, options.emptyMessage || 'No data');
            return;
        }

        const width = 960;
        const height = 260;
        const padding = {top: 16, right: 16, bottom: 24, left: 44};

        const plotWidth = width - padding.left - padding.right;
        const plotHeight = height - padding.top - padding.bottom;

        const values = points.map(point => point.quality);
        const maxValue = Math.max(...values, options.stormThreshold ?? 0, 6) * 1.1;
        const scaleY = value => padding.top + plotHeight - (value / maxValue) * plotHeight;

        const times = points.map(point => new Date(point.dateTime));
        const start = options.start ? new Date(options.start) : times[0];
        const end = options.end ? new Date(options.end) : times[times.length - 1];
        const span = Math.max(1, end - start);

        const scaleX = at => padding.left + ((at - start) / span) * plotWidth;

        const svg = createSvg(width, height);

        for (let i = 0; i <= 4; i++) {
            const value = (maxValue * i) / 4;
            const y = scaleY(value);

            svg.appendChild(element('line', {
                class: 'grid-line', x1: padding.left, x2: width - padding.right, y1: y, y2: y
            }));

            const text = element('text', {class: 'axis-text', x: padding.left - 6, y: y + 3, 'text-anchor': 'end'});
            text.textContent = value.toFixed(0);
            svg.appendChild(text);
        }

        const threshold = (value, className) => {
            if (value === null || value === undefined) {
                return;
            }

            svg.appendChild(element('line', {
                class: className, x1: padding.left, x2: width - padding.right,
                y1: scaleY(value), y2: scaleY(value), 'stroke-width': 1.5, 'stroke-dasharray': '5 4'
            }));
        };

        threshold(options.cloudyThreshold, 'threshold-sunny');
        threshold(options.stormThreshold, 'threshold-storm');

        const linePoints = points.map((point, index) => `${scaleX(times[index])},${scaleY(point.quality)}`);
        const baseline = padding.top + plotHeight;

        // The hours that already happened are washed over: the curve left of the marker is
        // what the weather did, right of it what it is expected to do. Whole hours only -
        // the hour running now is still a forecast.
        const now = options.now || new Date();
        const currentHour = new Date(now.getTime());
        currentHour.setMinutes(0, 0, 0);

        if (currentHour > start && currentHour <= end) {
            svg.appendChild(element('rect', {
                class: 'quality-past',
                x: padding.left, y: padding.top,
                width: Math.max(0, scaleX(currentHour) - padding.left), height: plotHeight
            }));

            svg.appendChild(element('line', {
                class: 'quality-now',
                x1: scaleX(currentHour), x2: scaleX(currentHour),
                y1: padding.top - 4, y2: baseline
            }));
        }

        svg.appendChild(element('polygon', {
            class: 'quality-area',
            points: `${scaleX(times[0])},${baseline} ${linePoints.join(' ')} ${scaleX(times[times.length - 1])},${baseline}`
        }));

        svg.appendChild(element('polyline', {class: 'quality-line', points: linePoints.join(' ')}));

        points.forEach((point, index) => {
            svg.appendChild(element('circle', {
                class: 'quality-dot', cx: scaleX(times[index]), cy: scaleY(point.quality), r: 2.5
            }));
        });

        // Hour labels come from the window rather than from the points, so an hour with no
        // reading is still named on the axis instead of silently closing the gap.
        const windowHours = Math.round(span / 3600000);
        const labelStep = windowHours > 26 ? 6 : 3;

        for (let hour = 0; hour <= windowHours; hour += labelStep) {
            const at = new Date(start.getTime() + hour * 3600 * 1000);

            const text = element('text', {
                class: 'axis-text', x: scaleX(at), y: height - 8, 'text-anchor': 'middle'
            });
            text.textContent = String(at.getHours()).padStart(2, '0') + ':00';
            svg.appendChild(text);
        }

        // hover layer: one column per hour, so the whole strip is hoverable
        const columnWidth = Math.max(4, plotWidth / Math.max(1, windowHours));

        points.forEach((point, index) => {
            const centre = scaleX(times[index]);

            const marker = element('line', {
                class: 'quality-marker',
                x1: centre, x2: centre, y1: padding.top, y2: baseline, opacity: 0
            });
            svg.appendChild(marker);

            const hit = element('rect', {
                class: 'hit-area',
                x: centre - columnWidth / 2,
                y: padding.top,
                width: columnWidth,
                height: plotHeight
            });

            hoverable(
                hit,
                () => options.tooltip ? options.tooltip(point, index) : null,
                () => marker.setAttribute('opacity', '1'),
                () => marker.setAttribute('opacity', '0')
            );

            svg.appendChild(hit);
        });

        container.replaceChildren(svg);
    }

    /* ------------------------------------------------------ timeline chart */

    /** The hour {@code date} falls in, as a new Date. */
    function hourOf(date) {
        const hour = new Date(date.getTime());
        hour.setMinutes(0, 0, 0);
        return hour;
    }

    const ACTION_COLOURS = {
        WORK_MODE_CHANGE: 'var(--accent)',
        GRID_SELL: 'var(--sell)',
        GRID_CHARGE: 'var(--charge)',
        EXPORT_LIMIT: 'var(--warning)',
        REMOTE_CONTROL_EXIT: 'var(--text-faint)',
        GPIO_STATE_CHANGE: 'var(--text-faint)',
        CHECK: 'var(--border-strong)'
    };

    /**
     * Band of what the modules did and intend to do.
     * Instantaneous actions become ticks, windows become bars, and "now" is a
     * dashed marker so the plan can be read relative to the current time.
     *
     * The window is the caller's to choose - the next 24 hours, or a whole day
     * with the morning already behind it - and entries outside it are dropped
     * rather than squeezed in.
     *
     * A tick is only a few pixels wide, so every entry gets a wider invisible
     * hit area on top of it - otherwise the checks would be unhoverable.
     */
    function timelineChart(container, entries, options = {}) {
        const width = 960;
        const rowHeight = 30;

        // The module name gets its own gutter so it never sits on top of the bars.
        const padding = {top: 22, right: 12, bottom: 22, left: 150};

        const now = options.now || new Date();

        const start = options.start ? new Date(options.start) : hourOf(now);
        const end = options.end ? new Date(options.end) : new Date(start.getTime() + 24 * 3600 * 1000);

        const visible = (entries || []).filter(entry => {
            const from = new Date(entry.from);
            return from >= start && from <= end;
        });

        if (visible.length === 0) {
            empty(container, options.emptyMessage || 'Nothing planned');
            return;
        }

        // one row per module keeps overlapping actions readable
        const modules = [...new Set(visible.map(entry => entry.moduleId))].sort();
        const height = padding.top + modules.length * rowHeight + padding.bottom;
        const plotWidth = width - padding.left - padding.right;

        const scaleX = date => padding.left + ((date - start) / (end - start)) * plotWidth;

        const svg = createSvg(width, height);

        const windowHours = Math.round((end - start) / 3600000);

        for (let hour = 0; hour <= windowHours; hour += 3) {
            const at = new Date(start.getTime() + hour * 3600 * 1000);
            const x = scaleX(at);

            svg.appendChild(element('line', {
                class: 'grid-line', x1: x, x2: x, y1: padding.top - 6, y2: height - padding.bottom
            }));

            const text = element('text', {class: 'axis-text', x: x, y: 12, 'text-anchor': 'middle'});
            text.textContent = String(at.getHours()).padStart(2, '0') + ':00';
            svg.appendChild(text);
        }

        modules.forEach((moduleId, row) => {
            const y = padding.top + row * rowHeight;

            svg.appendChild(element('rect', {
                class: 'timeline-track', x: padding.left, y: y + 5, width: plotWidth, height: rowHeight - 12, rx: 5
            }));

            const label = element('text', {
                class: 'timeline-label', x: padding.left - 10, y: y + rowHeight / 2 + 3, 'text-anchor': 'end'
            });
            label.textContent = options.moduleNames?.[moduleId] || moduleId;
            svg.appendChild(label);
        });

        visible.forEach(entry => {
            const row = modules.indexOf(entry.moduleId);
            const y = padding.top + row * rowHeight;
            const from = new Date(entry.from);
            const to = entry.to ? new Date(entry.to) : null;

            // A run that failed is worth spotting from across the room; everything else
            // is coloured by what kind of action it is.
            const colour = entry.success === false
                ? 'var(--danger)'
                : (ACTION_COLOURS[entry.type] || 'var(--accent)');

            // What already happened steps back, so the plan stays the thing you read first.
            const opacity = entry.past ? 0.45 : (entry.certain ? 0.95 : 0.55);

            const x = scaleX(from);
            const barWidth = to ? Math.max(5, scaleX(to) - x) : 5;
            const barX = to ? x : x - 2;

            const bar = element('rect', {
                class: 'timeline-bar' + (entry.past ? ' is-past' : ''),
                x: barX, y: y + 5, width: barWidth, height: rowHeight - 12, rx: 3,
                fill: colour, opacity: opacity
            });
            svg.appendChild(bar);

            // Ticks are too narrow to hover, so widen the target without widening the mark.
            const hitWidth = Math.max(barWidth, 12);
            const hit = element('rect', {
                class: 'hit-area',
                x: barX - (hitWidth - barWidth) / 2,
                y: y + 2,
                width: hitWidth,
                height: rowHeight - 6
            });

            hoverable(
                hit,
                () => options.tooltip ? options.tooltip(entry) : null,
                () => bar.setAttribute('opacity', '1'),
                () => bar.setAttribute('opacity', String(opacity))
            );

            svg.appendChild(hit);
        });

        if (now >= start && now <= end) {
            const nowX = scaleX(now);
            svg.appendChild(element('line', {
                class: 'timeline-now', x1: nowX, x2: nowX, y1: padding.top - 6, y2: height - padding.bottom
            }));
        }

        container.replaceChildren(svg);
    }

    return {priceChart, weatherChart, timelineChart, hideTooltip: Tooltip.hide};
})();
