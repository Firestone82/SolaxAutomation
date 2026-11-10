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

    /**
     * The chart is drawn in the number of user units its container is CSS pixels wide, so
     * one unit is one pixel and the 10px axis labels stay 10px whatever the screen.
     * <p>
     * Drawing every chart 960 units wide and letting the browser scale it down was fine on a
     * desktop and unreadable on a phone: a 960-unit chart squeezed into a 350px card renders
     * its labels at under 4px. Below {@code COMPACT_WIDTH} there is not room for the desktop
     * gutters and a label every three hours either, so the callers thin both out.
     *
     * A container that is not laid out yet - a hidden page, the first paint - measures 0;
     * that falls back to the old width, and the caller re-renders once it is visible.
     */
    const COMPACT_WIDTH = 560;

    function measure(container, maximum = 960) {
        const available = Math.round(container.getBoundingClientRect().width);
        const width = available > 0 ? Math.max(260, Math.min(maximum, available)) : maximum;

        return {width, compact: width < COMPACT_WIDTH};
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

        /*
           On a touch screen a tooltip stays open after the tap that opened it, so it has to
           be closed by something. The mark that opened it is remembered together with the
           callback that puts it back to its resting state, and the next touch anywhere else
           on the document runs both.
        */
        let heldBy = null;
        let release = null;

        function releaseHeld() {
            const callback = release;

            heldBy = null;
            release = null;

            if (callback) {
                callback();
            }

            if (node) {
                node.hidden = true;
            }
        }

        document.addEventListener('pointerdown', event => {
            if (heldBy && event.pointerType !== 'mouse' && !heldBy.contains(event.target)) {
                releaseHeld();
            }
        }, true);

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
            },

            /** The mark whose tooltip a touch is currently holding open, if any. */
            owner() {
                return heldBy;
            },

            claim(shape, onRelease) {
                heldBy = shape;
                release = onRelease;
            },

            release: releaseHeld
        };
    })();

    /**
     * Makes a shape hoverable. {@code build} returns the tooltip content, or a
     * falsy value to show nothing.
     */
    function hoverable(shape, build, onEnter, onLeave) {
        const enter = event => {
            const content = build();

            if (content) {
                Tooltip.show(content, event);
            }

            if (onEnter) {
                onEnter();
            }
        };

        const leave = () => {
            Tooltip.hide();

            if (onLeave) {
                onLeave();
            }
        };

        shape.addEventListener('mouseenter', enter);
        shape.addEventListener('mousemove', Tooltip.move);
        shape.addEventListener('mouseleave', leave);

        /*
           A finger never hovers, so on a touch screen the same mark opens its tooltip on tap
           and closes it on the next tap anywhere - including on the mark itself, which makes
           a second tap dismiss what the first one opened.

           It hangs off click rather than off the touch itself: a tap fires click, a scroll
           that happens to start on the chart does not, so swiping the page never leaves a
           tooltip behind. Nothing is prevented, so the swipe still scrolls.
        */
        let byTouch = false;

        shape.addEventListener('pointerdown', event => {
            byTouch = event.pointerType !== 'mouse';
        });

        shape.addEventListener('click', event => {
            if (!byTouch) {
                return;
            }

            if (Tooltip.owner() === shape) {
                Tooltip.release();
                return;
            }

            Tooltip.release();
            enter(event);
            Tooltip.claim(shape, leave);
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
     * this day" without looking up either module's configuration.
     *
     * Each bar carries a full-height transparent hit area, so hovering anywhere
     * in the interval's column works rather than only over the bar itself.
     */
    function priceChart(container, points, options = {}) {
        if (!points || points.length === 0) {
            empty(container, options.emptyMessage || 'No data');
            return;
        }

        const {width, compact} = measure(container);

        // A lane under the plot for the intervals sold into and charged from the grid,
        // reserved only when the day has either, so a day without stays exactly as it was.
        const charged = points.some(point => point.charging);
        const sold = points.some(point => point.selling);
        const laneHeight = charged || sold ? 9 : 0;

        const height = (compact ? 200 : 260) + laneHeight;
        const padding = compact
            ? {top: 12, right: 6, bottom: 20 + laneHeight, left: 30}
            : {top: 16, right: 12, bottom: 24 + laneHeight, left: 44};

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

        /*
           Sold and charged: strips in the lane under the bars rather than colours on them,
           so every bar keeps the price band it is in - what the energy was sold or bought
           at is the point of marking it - and nothing reads as another price level. A sale
           is a solid strip, a charge a hatched one; the two never share an interval.
        */
        // Every strip drawn, so the hover layer at the end can give each its own target.
        const strips = [];

        if (sold) {
            const laneY = padding.top + plotHeight + 3;
            const slotWidth = plotWidth / points.length;

            bands(points, point => point.selling === true).forEach(([from, to]) => {
                const rect = element('rect', {
                    class: 'sell-strip',
                    x: padding.left + from * slotWidth + 0.5,
                    y: laneY,
                    width: Math.max(2, (to - from) * slotWidth - 1),
                    height: laneHeight - 3,
                    rx: 2
                });

                svg.appendChild(rect);
                strips.push({kind: 'selling', from, to, rect});
            });
        }

        if (charged) {
            const stripeId = 'price-charge-stripes';
            const defs = element('defs');
            const pattern = element('pattern', {
                id: stripeId, width: 5, height: 5, patternUnits: 'userSpaceOnUse', patternTransform: 'rotate(45)'
            });
            pattern.appendChild(element('line', {class: 'charge-stripe', x1: 0, y1: 0, x2: 0, y2: 5}));
            defs.appendChild(pattern);
            svg.appendChild(defs);

            const laneY = padding.top + plotHeight + 3;
            const slotWidth = plotWidth / points.length;

            bands(points, point => point.charging === true).forEach(([from, to]) => {
                const rect = element('rect', {
                    class: 'charge-strip',
                    x: padding.left + from * slotWidth + 0.5,
                    y: laneY,
                    width: Math.max(2, (to - from) * slotWidth - 1),
                    height: laneHeight - 3,
                    rx: 2,
                    fill: `url(#${stripeId})`
                });

                svg.appendChild(rect);
                strips.push({kind: 'charging', from, to, rect});
            });
        }

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
        const ticks = compact ? 3 : 4;
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

            // The colour is the price band and nothing else; the selling window and the
            // charges are marked in the lane underneath.
            const classes = ['bar-' + (point.level || 'medium')];
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

        // Hour labels every three hours, every six where they would otherwise collide.
        const labelStep = compact ? 6 : 3;

        points.forEach((point, index) => {
            if (point.minute !== 0 || point.hour % labelStep !== 0) {
                return;
            }

            const text = element('text', {
                class: 'axis-text',
                x: padding.left + index * barWidth,
                y: height - 6,
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

        // The strips under the bars report the sale or the charge they mark - the window,
        // the power, who started it - rather than the price of the interval above them.
        // Their targets reach a little above and below the strip, which is only a few
        // pixels tall.
        strips.forEach(strip => {
            const box = {
                x: Number(strip.rect.getAttribute('x')),
                y: Number(strip.rect.getAttribute('y')),
                width: Number(strip.rect.getAttribute('width')),
                height: Number(strip.rect.getAttribute('height'))
            };

            const hit = element('rect', {
                class: 'hit-area',
                x: box.x, y: box.y - 3, width: box.width, height: box.height + 6
            });

            hoverable(
                hit,
                () => options.stripTooltip ? options.stripTooltip(strip.kind, points[strip.from], points[strip.to - 1]) : null,
                () => strip.rect.classList.add('is-hovered'),
                () => strip.rect.classList.remove('is-hovered')
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

        const {width, compact} = measure(container);
        const height = compact ? 200 : 260;
        const padding = compact
            ? {top: 12, right: 8, bottom: 20, left: 30}
            : {top: 16, right: 16, bottom: 24, left: 44};

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

        const ticks = compact ? 3 : 4;

        for (let i = 0; i <= ticks; i++) {
            const value = (maxValue * i) / ticks;
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
        // the hour running now is still a forecast. A day browsed back to is washed whole,
        // and has no marker: "now" is not on it.
        const now = options.now || new Date();
        const currentHour = new Date(now.getTime());
        currentHour.setMinutes(0, 0, 0);

        if (currentHour > start) {
            const pastEdge = currentHour <= end ? scaleX(currentHour) : padding.left + plotWidth;

            svg.appendChild(element('rect', {
                class: 'quality-past',
                x: padding.left, y: padding.top,
                width: Math.max(0, pastEdge - padding.left), height: plotHeight
            }));

            if (currentHour <= end) {
                svg.appendChild(element('line', {
                    class: 'quality-now',
                    x1: pastEdge, x2: pastEdge,
                    y1: padding.top - 4, y2: baseline
                }));
            }
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
        const labelStep = compact
            ? (windowHours > 26 ? 12 : 6)
            : (windowHours > 26 ? 6 : 3);

        for (let hour = 0; hour <= windowHours; hour += labelStep) {
            const at = new Date(start.getTime() + hour * 3600 * 1000);
            const x = scaleX(at);

            // The labels at the two ends are anchored inwards, or half of "00:00" hangs off
            // the side of the chart - which on a phone is half of it hanging off the page.
            const anchor = x < padding.left + 16 ? 'start'
                : x > width - padding.right - 16 ? 'end' : 'middle';

            const text = element('text', {
                class: 'axis-text', x: x, y: height - 6, 'text-anchor': anchor
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
     * Rows are groups the caller names - what kind of action it is - rather than one
     * per module, so a handful of rows says what a dozen modules would. Two entries
     * of a row that would land on top of each other are not drawn over one another:
     * the row splits into lanes, the later entry going to the first lane it fits in,
     * so everything that happens at the same time stays visible.
     *
     * A tick is only a few pixels wide, so every entry gets a wider invisible
     * hit area on top of it - otherwise the checks would be unhoverable.
     *
     * options: groupOf (entry => key), groupNames ({key: label}), groupOrder ([key]),
     * start, end, now, tooltip, emptyMessage.
     */
    function timelineChart(container, entries, options = {}) {
        const {width, compact} = measure(container);

        const padding = compact
            ? {top: 20, right: 6, bottom: 18, left: 6}
            : {top: 22, right: 12, bottom: 22, left: 150};

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

        const plotWidth = width - padding.left - padding.right;
        const scaleX = date => padding.left + ((date - start) / (end - start)) * plotWidth;

        const groupOf = options.groupOf || (entry => entry.moduleId);
        const order = options.groupOrder || [];
        const keys = [...new Set(visible.map(groupOf))]
            .sort((a, b) => {
                const ia = order.indexOf(a);
                const ib = order.indexOf(b);
                return (ia < 0 ? order.length : ia) - (ib < 0 ? order.length : ib) || String(a).localeCompare(String(b));
            });

        // Where each entry is drawn, in pixels, worked out before the rows are: how many
        // lanes a row needs depends on how its entries overlap on screen, not in time.
        const hitMin = compact ? 16 : 12;
        const placed = visible.map(entry => {
            const from = new Date(entry.from);
            const to = entry.to ? new Date(entry.to) : null;
            const x = scaleX(from);
            const barWidth = to ? Math.max(5, scaleX(to) - x) : 5;
            const barX = to ? x : x - 2;
            const hitWidth = Math.max(barWidth, hitMin);
            const hitX = barX - (hitWidth - barWidth) / 2;

            return {entry, group: groupOf(entry), barX, barWidth, hitX, hitWidth};
        });

        // Greedy lanes: in order of where they start, each entry takes the first lane whose
        // last entry ends before it begins, with a little air so two ticks never touch.
        const lanesOf = {};

        keys.forEach(key => {
            const lanes = [];

            placed
                .filter(item => item.group === key)
                .sort((a, b) => a.hitX - b.hitX)
                .forEach(item => {
                    let lane = lanes.findIndex(last => last + 2 <= item.hitX);

                    if (lane < 0) {
                        lane = lanes.length;
                        lanes.push(0);
                    }

                    lanes[lane] = item.hitX + item.hitWidth;
                    item.lane = lane;
                });

            lanesOf[key] = Math.max(1, lanes.length);
        });

        const singleHeight = compact ? 20 : 18;
        const laneHeight = compact ? 14 : 12;
        const laneGap = 3;
        const labelSpace = compact ? 14 : 0;
        const rowGap = compact ? 12 : 12;

        const trackHeightOf = lanes => lanes === 1 ? singleHeight : lanes * laneHeight + (lanes - 1) * laneGap;
        const laneHeightOf = lanes => lanes === 1 ? singleHeight : laneHeight;

        const rows = [];
        let y = padding.top;

        keys.forEach(key => {
            const lanes = lanesOf[key];
            const trackTop = y + labelSpace;
            const trackHeight = trackHeightOf(lanes);

            rows.push({key, lanes, top: y, trackTop, trackHeight});
            y = trackTop + trackHeight + rowGap;
        });

        const height = y - rowGap + padding.bottom;
        const svg = createSvg(width, height);

        const windowHours = Math.round((end - start) / 3600000);
        const hourStep = compact ? 6 : 3;

        for (let hour = 0; hour <= windowHours; hour += hourStep) {
            const at = new Date(start.getTime() + hour * 3600 * 1000);
            const x = scaleX(at);

            svg.appendChild(element('line', {
                class: 'grid-line', x1: x, x2: x, y1: padding.top - 6, y2: height - padding.bottom
            }));

            const anchor = x < padding.left + 16 ? 'start'
                : x > width - padding.right - 16 ? 'end' : 'middle';

            const text = element('text', {class: 'axis-text', x: x, y: 11, 'text-anchor': anchor});
            text.textContent = String(at.getHours()).padStart(2, '0') + ':00';
            svg.appendChild(text);
        }

        rows.forEach(row => {
            svg.appendChild(element('rect', {
                class: 'timeline-track',
                x: padding.left, y: row.trackTop, width: plotWidth, height: row.trackHeight, rx: 5
            }));

            const label = compact
                ? element('text', {class: 'timeline-label', x: padding.left + 1, y: row.top + 10})
                : element('text', {
                    class: 'timeline-label',
                    x: padding.left - 10, y: row.trackTop + row.trackHeight / 2 + 3, 'text-anchor': 'end'
                });

            label.textContent = options.groupNames?.[row.key] || row.key;
            svg.appendChild(label);
        });

        placed.forEach(item => {
            const {entry, barX, barWidth, hitX, hitWidth} = item;
            const row = rows.find(candidate => candidate.key === item.group);
            const height_ = laneHeightOf(row.lanes);
            const top = row.trackTop + item.lane * (height_ + (row.lanes === 1 ? 0 : laneGap));

            // A run that failed is worth spotting from across the room; everything else
            // is coloured by what kind of action it is.
            const colour = entry.success === false
                ? 'var(--danger)'
                : (ACTION_COLOURS[entry.type] || 'var(--accent)');

            // What already happened steps back, so the plan stays the thing you read first.
            const opacity = entry.past ? 0.45 : (entry.certain ? 0.95 : 0.55);

            const bar = element('rect', {
                class: 'timeline-bar' + (entry.past ? ' is-past' : ''),
                x: barX, y: top, width: barWidth, height: height_, rx: 3,
                fill: colour, opacity: opacity
            });
            svg.appendChild(bar);

            // Ticks are too narrow to hover, so widen the target without widening the mark.
            const hit = element('rect', {
                class: 'hit-area',
                x: hitX, y: top - 1, width: hitWidth, height: height_ + 2
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

    /* ----------------------------------------------------- work mode chart */

    /**
     * The day as one or more stacked bands sharing a single time axis.
     *
     * Segments come in already resolved - {from, to, mode, ...} - because deciding
     * what the inverter was in between two recorded changes is a question about the
     * activity history, not about drawing. Here they are only laid out: one colour
     * per value, a tick where it changed, and the dashed "now" marker the other
     * charts use.
     *
     * More than one band because more than one thing holds a state all day and moves
     * a handful of times: the work mode, and which supply the house is on. They are
     * the same shape and the same question - what was it at this hour - so they share
     * the axis rather than each getting a chart, and reading one against the other is
     * the point: what the export limit was doing all afternoon only means something
     * next to which supply it was exporting into.
     *
     * The axis always spans the whole window even though a band stops at its last
     * segment. An empty right-hand end is the point: it says the day is not over,
     * where a band stretched to the full width would read as a mode that is somehow
     * already known for the evening.
     *
     * A band's {@code overlays} are stretches where something was happening on top of
     * its value rather than instead of it - a sale into the grid, which runs through a
     * remote control session and leaves the mode where it was. They are drawn as a
     * ribbon along the bottom of that band so both stay readable, and one still ahead
     * of us is drawn hollow.
     *
     * @param bands [{segments, colours, labels, tooltip, overlays, overlayTooltip, secondary}]
     *              in the order they are stacked; a band with no segments is skipped
     */
    function workModeChart(container, bands, options = {}) {
        const rows = (bands || []).filter(band => band.segments && band.segments.length > 0);

        if (rows.length === 0) {
            empty(container, options.emptyMessage || 'No work mode recorded');
            return;
        }

        const {width, compact} = measure(container);

        const padding = compact
            ? {top: 20, right: 6, bottom: 18, left: 6}
            : {top: 22, right: 12, bottom: 20, left: 12};

        // A secondary band is drawn thinner: it is the context the first one is read
        // against, and two bands of equal weight would argue over which is the subject.
        const bandHeight = band => band.secondary ? (compact ? 12 : 16) : (compact ? 26 : 34);
        const gap = compact ? 4 : 6;

        const bandsHeight = rows.reduce((total, band) => total + bandHeight(band), 0) + gap * (rows.length - 1);
        const height = padding.top + bandsHeight + padding.bottom;

        const start = new Date(options.start);
        const end = new Date(options.end);
        const now = options.now || new Date();

        const plotWidth = width - padding.left - padding.right;
        const scaleX = date => padding.left + ((date - start) / (end - start)) * plotWidth;

        const svg = createSvg(width, height);

        // Where each band sits, worked out once: the tracks are drawn under the grid
        // lines and the bands themselves over them.
        let y = padding.top;

        const placed = rows.map(band => {
            const at = {band: band, top: y, height: bandHeight(band)};
            y += at.height + gap;
            return at;
        });

        // The track shows through wherever a band has nothing to say - the evening
        // ahead of us, and any gap the history cannot account for.
        placed.forEach(at => svg.appendChild(element('rect', {
            class: 'timeline-track',
            x: padding.left, y: at.top, width: plotWidth, height: at.height, rx: 6
        })));

        const windowHours = Math.round((end - start) / 3600000);
        const hourStep = compact ? 6 : 3;

        for (let hour = 0; hour <= windowHours; hour += hourStep) {
            const at = new Date(start.getTime() + hour * 3600 * 1000);
            const x = scaleX(at);

            svg.appendChild(element('line', {
                class: 'grid-line', x1: x, x2: x, y1: padding.top - 6, y2: height - padding.bottom
            }));

            const anchor = x < padding.left + 16 ? 'start'
                : x > width - padding.right - 16 ? 'end' : 'middle';

            const text = element('text', {class: 'axis-text', x: x, y: 11, 'text-anchor': anchor});
            text.textContent = String(at.getHours()).padStart(2, '0') + ':00';
            svg.appendChild(text);
        }

        placed.forEach(at => drawBand(svg, at.band, {
            top: at.top,
            height: at.height,
            scaleX: scaleX,
            width: width,
            padding: padding,
            compact: compact
        }));

        if (now >= start && now <= end) {
            const nowX = scaleX(now);
            svg.appendChild(element('line', {
                class: 'timeline-now', x1: nowX, x2: nowX, y1: padding.top - 6, y2: height - padding.bottom
            }));
        }

        container.replaceChildren(svg);
    }

    /** One band of {@link workModeChart}: its segments, their labels and its ribbon. */
    function drawBand(svg, band, layout) {
        const {top, height, scaleX, width, padding, compact} = layout;

        // The ribbon lane along the bottom of the band. Reserved before the segments are
        // drawn so a label can sit clear of it rather than on top of it.
        const overlays = band.overlays || [];
        const ribbonHeight = compact ? 6 : 8;
        const ribbonY = top + height - ribbonHeight - 3;

        band.segments.forEach(segment => {
            const from = new Date(segment.from);
            const to = new Date(segment.to);

            const x = scaleX(from);
            // A change and the value it produced can be minutes apart; a segment that
            // rounds to nothing would leave a hole in an otherwise continuous band.
            const segmentWidth = Math.max(2, scaleX(to) - x);

            const colour = band.colours?.[segment.mode] || 'var(--text-faint)';

            const bar = element('rect', {
                class: 'mode-bar' + (segment.mode ? '' : ' is-unknown'),
                x: x, y: top, width: segmentWidth, height: height,
                fill: colour, opacity: segment.mode ? 0.85 : 0.25
            });
            svg.appendChild(bar);

            // The label only goes in where it fits; the tooltip always says it. A thin
            // band has no room for one at all, and says what it is through its colour.
            const label = band.labels?.[segment.mode];

            if (label && !compact && height >= 24 && segmentWidth > label.length * 6.5 + 14) {
                const text = element('text', {
                    class: 'mode-label',
                    x: x + segmentWidth / 2,
                    y: top + height / 2 + 4 - (overlays.length ? 4 : 0),
                    'text-anchor': 'middle'
                });
                text.textContent = label;
                svg.appendChild(text);
            }

            const hit = element('rect', {
                class: 'hit-area',
                x: x, y: top, width: segmentWidth, height: height
            });

            hoverable(
                hit,
                () => band.tooltip ? band.tooltip(segment) : null,
                () => bar.setAttribute('opacity', '1'),
                () => bar.setAttribute('opacity', String(segment.mode ? 0.85 : 0.25))
            );

            svg.appendChild(hit);

            // Where the segment began because something changed the value, rather than
            // because the window did. Drawn over the band so the change is findable.
            if (segment.changedBy) {
                svg.appendChild(element('line', {
                    class: 'mode-change',
                    x1: x, x2: x, y1: top - 3, y2: top + height + 3
                }));
            }
        });

        // Drawn after the band, and hit-tested after it too, so hovering the ribbon
        // reports the sale rather than the mode underneath it.
        overlays.forEach(overlay => {
            const from = new Date(overlay.from);
            const to = new Date(overlay.to);

            // A window can start before the window we draw, or run past its end.
            const x = Math.max(padding.left, scaleX(from));
            const overlayWidth = Math.max(2, Math.min(width - padding.right, scaleX(to)) - x);

            if (overlayWidth <= 0) {
                return;
            }

            const bar = element('rect', {
                class: 'mode-overlay' + (overlay.kind === 'charge' ? ' is-charge' : '')
                    + (overlay.pending ? ' is-pending' : ''),
                x: x, y: ribbonY, width: overlayWidth, height: ribbonHeight, rx: ribbonHeight / 2
            });
            svg.appendChild(bar);

            // The ribbon is a few pixels tall; the whole band height is the hit area.
            const hit = element('rect', {
                class: 'hit-area', x: x, y: top, width: overlayWidth, height: height
            });

            hoverable(
                hit,
                () => band.overlayTooltip ? band.overlayTooltip(overlay) : null,
                () => bar.classList.add('is-hovered'),
                () => bar.classList.remove('is-hovered')
            );

            svg.appendChild(hit);
        });
    }

    /* --------------------------------------------------------- daily chart */

    /** A round step for an axis spanning {@code raw} per tick: 1, 2 or 5 times a power of ten. */
    function niceStep(raw) {
        if (!(raw > 0)) {
            return 1;
        }

        const power = Math.pow(10, Math.floor(Math.log10(raw)));
        const fraction = raw / power;
        return (fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 5 ? 5 : 10) * power;
    }

    /** Ticks covering [min, max] at a round step, always including zero when the range spans it. */
    function niceTicks(min, max, count) {
        if (max <= min) {
            max = min + 1;
        }

        const step = niceStep((max - min) / count);
        const start = Math.floor(min / step) * step;
        const end = Math.ceil(max / step) * step;
        const ticks = [];

        for (let value = start; value <= end + step / 2; value += step) {
            // Rounded back onto the step, or 0.1 + 0.2 shows up on the axis.
            ticks.push(Number((Math.round(value / step) * step).toPrecision(12)));
        }

        return ticks;
    }

    /**
     * One column per calendar day, for the statistics section. Three shapes:
     * <ul>
     *   <li>{@code grouped} - every series its own bar from zero, side by side,</li>
     *   <li>{@code stacked} - positive values stacked upwards from zero and negative ones
     *       downwards, so income and cost read as one column split by the axis,</li>
     *   <li>{@code range} - one floating bar from a low to a high value.</li>
     * </ul>
     * A day nothing was recorded on keeps its column, empty, so a gap in the record shows as a
     * gap rather than being closed up. Weekends get a faint wash and today, which is still
     * running, a faint outline. Each column is one hover target.
     *
     * options: series [{value: day => number, colour}], mode, range {low, high, colour},
     * marker (day => number, drawn as a tick), min/max to pin the axis, axisFormat,
     * xLabel (day => text), tooltip (day => content), emptyMessage.
     */
    function dailyChart(container, days, options = {}) {
        if (!days || days.length === 0 || !days.some(day => day.recorded)) {
            empty(container, options.emptyMessage || 'No data');
            return;
        }

        const {width, compact} = measure(container);
        const height = options.height || (compact ? 170 : 210);
        const padding = compact
            ? {top: 10, right: 6, bottom: 20, left: 36}
            : {top: 12, right: 12, bottom: 22, left: 48};

        const plotWidth = width - padding.left - padding.right;
        const plotHeight = height - padding.top - padding.bottom;
        const mode = options.mode || 'grouped';
        const series = options.series || [];
        const present = value => value !== null && value !== undefined && !Number.isNaN(value);

        let maxValue = 0;
        let minValue = 0;

        days.forEach(day => {
            if (!day.recorded) {
                return;
            }

            const values = [];

            if (mode === 'range') {
                values.push(options.range.low(day), options.range.high(day));
            } else if (mode === 'stacked') {
                let up = 0;
                let down = 0;

                series.forEach(item => {
                    const value = item.value(day);

                    if (present(value)) {
                        if (value > 0) {
                            up += value;
                        } else {
                            down += value;
                        }
                    }
                });

                values.push(up, down);
            } else {
                series.forEach(item => values.push(item.value(day)));
            }

            if (options.marker) {
                values.push(options.marker(day));
            }

            values.filter(present).forEach(value => {
                maxValue = Math.max(maxValue, value);
                minValue = Math.min(minValue, value);
            });
        });

        if (options.min !== undefined) {
            minValue = options.min;
        }

        if (options.max !== undefined) {
            maxValue = options.max;
        }

        const ticks = niceTicks(minValue, maxValue, compact ? 3 : 4);
        const low = ticks[0];
        const high = ticks[ticks.length - 1];
        const span = (high - low) || 1;
        const scaleY = value => padding.top + plotHeight - ((value - low) / span) * plotHeight;
        const zeroY = scaleY(Math.max(low, Math.min(high, 0)));

        const band = plotWidth / days.length;
        const gap = Math.max(1, Math.min(6, band * 0.22));
        const inner = Math.max(1, band - gap);
        const columnX = index => padding.left + index * band;
        const today = new Date().toDateString();

        const svg = createSvg(width, height);

        // Column washes first, so everything else sits on top of them.
        days.forEach((day, index) => {
            const date = new Date(day.date + 'T00:00:00');
            const weekday = date.getDay();
            const classes = [];

            if (weekday === 0 || weekday === 6) {
                classes.push('day-weekend');
            }

            if (date.toDateString() === today) {
                classes.push('day-today');
            }

            if (classes.length) {
                svg.appendChild(element('rect', {
                    class: classes.join(' '),
                    x: columnX(index), y: padding.top, width: band, height: plotHeight
                }));
            }
        });

        const format = options.axisFormat || (value => String(value));

        ticks.forEach(value => {
            const y = scaleY(value);

            svg.appendChild(element('line', {
                class: value === 0 ? 'axis-line' : 'grid-line',
                x1: padding.left, x2: width - padding.right, y1: y, y2: y
            }));

            const text = element('text', {class: 'axis-text', x: padding.left - 6, y: y + 3, 'text-anchor': 'end'});
            text.textContent = format(value);
            svg.appendChild(text);
        });

        const bar = (x, from, to, colour, barWidth) => {
            const top = Math.min(scaleY(from), scaleY(to));
            const size = Math.max(1, Math.abs(scaleY(from) - scaleY(to)));

            svg.appendChild(element('rect', {
                class: 'day-bar',
                x: x, y: top, width: barWidth, height: size,
                rx: Math.min(2, barWidth / 3),
                fill: colour
            }));
        };

        days.forEach((day, index) => {
            if (!day.recorded) {
                return;
            }

            const x = columnX(index) + gap / 2;

            if (mode === 'range') {
                const from = options.range.low(day);
                const to = options.range.high(day);

                if (present(from) && present(to)) {
                    bar(x, from, to, options.range.colour, inner);
                }
            } else if (mode === 'stacked') {
                let up = 0;
                let down = 0;

                series.forEach(item => {
                    const value = item.value(day);

                    if (!present(value) || value === 0) {
                        return;
                    }

                    if (value > 0) {
                        bar(x, up, up + value, item.colour, inner);
                        up += value;
                    } else {
                        bar(x, down, down + value, item.colour, inner);
                        down += value;
                    }
                });
            } else {
                const each = inner / Math.max(1, series.length);

                series.forEach((item, position) => {
                    const value = item.value(day);

                    if (present(value)) {
                        bar(x + position * each, 0, value, item.colour, Math.max(1, each - (each > 4 ? 1 : 0)));
                    }
                });
            }

            if (options.marker) {
                const value = options.marker(day);

                if (present(value)) {
                    const y = scaleY(value);
                    svg.appendChild(element('line', {
                        class: 'day-marker', x1: x - 1, x2: x + inner + 1, y1: y, y2: y
                    }));
                }
            }
        });

        // Day labels, anchored on today at the right edge and thinned to what fits.
        const labelStep = Math.max(1, Math.ceil((compact ? 30 : 38) / band));

        days.forEach((day, index) => {
            if ((days.length - 1 - index) % labelStep !== 0) {
                return;
            }

            const text = element('text', {
                class: 'axis-text',
                x: columnX(index) + band / 2,
                y: height - 6,
                'text-anchor': 'middle'
            });
            text.textContent = options.xLabel ? options.xLabel(day) : day.date.slice(8);
            svg.appendChild(text);
        });

        // Hover layer, last so it sits above the bars.
        days.forEach((day, index) => {
            const highlight = element('rect', {
                class: 'bar-hover', x: columnX(index), y: padding.top, width: band, height: plotHeight, opacity: 0
            });
            svg.appendChild(highlight);

            const hit = element('rect', {
                class: 'hit-area', x: columnX(index), y: padding.top, width: band, height: plotHeight
            });

            hoverable(
                hit,
                () => options.tooltip ? options.tooltip(day) : null,
                () => highlight.setAttribute('opacity', '1'),
                () => highlight.setAttribute('opacity', '0')
            );

            svg.appendChild(hit);
        });

        // Keeps the zero line visible above the bars it separates.
        if (low < 0 && high > 0) {
            svg.appendChild(element('line', {
                class: 'axis-line day-zero', x1: padding.left, x2: width - padding.right, y1: zeroY, y2: zeroY
            }));
        }

        container.replaceChildren(svg);
    }

    /* A re-render throws away the marks, so anything a touch is holding open goes with them. */
    return {
        priceChart, weatherChart, timelineChart, workModeChart, dailyChart,
        hideTooltip: Tooltip.release, COMPACT_WIDTH
    };
})();
