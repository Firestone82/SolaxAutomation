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
     * Bar chart of the day's quarter-hour prices.
     * Bars are coloured by price band, the interval that is running now gets an
     * outline, and intervals inside an armed selling window are painted in the
     * selling colour so the plan is readable at a glance.
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

        const zeroY = scaleY(0);

        points.forEach((point, index) => {
            const x = padding.left + index * barWidth;
            const barHeight = Math.max(1, Math.abs(zeroY - scaleY(point.czkPerKwh)));

            const classes = ['bar-' + (point.level || 'medium')];
            if (point.selling) {
                classes.length = 0;
                classes.push('bar-selling');
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
        const scaleX = index => padding.left + (index / Math.max(1, points.length - 1)) * plotWidth;

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

        const linePoints = points.map((point, index) => `${scaleX(index)},${scaleY(point.quality)}`);
        const baseline = padding.top + plotHeight;

        svg.appendChild(element('polygon', {
            class: 'quality-area',
            points: `${padding.left},${baseline} ${linePoints.join(' ')} ${scaleX(points.length - 1)},${baseline}`
        }));

        svg.appendChild(element('polyline', {class: 'quality-line', points: linePoints.join(' ')}));

        points.forEach((point, index) => {
            svg.appendChild(element('circle', {
                class: 'quality-dot', cx: scaleX(index), cy: scaleY(point.quality), r: 2.5
            }));

            if (index % 3 === 0) {
                const text = element('text', {
                    class: 'axis-text', x: scaleX(index), y: height - 8, 'text-anchor': 'middle'
                });
                text.textContent = point.time;
                svg.appendChild(text);
            }
        });

        // hover layer: one column per hour, so the whole strip is hoverable
        const columnWidth = plotWidth / Math.max(1, points.length - 1);

        points.forEach((point, index) => {
            const centre = scaleX(index);

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

    const ACTION_COLOURS = {
        WORK_MODE_CHANGE: 'var(--accent)',
        GRID_SELL: 'var(--sell)',
        GRID_CHARGE: 'var(--charge)',
        EXPORT_LIMIT: 'var(--warning)',
        REMOTE_CONTROL_EXIT: 'var(--text-faint)',
        CHECK: 'var(--border-strong)'
    };

    /**
     * Twenty-four hour band of what the modules intend to do.
     * Instantaneous actions become ticks, windows become bars, and "now" is a
     * dashed marker so the plan can be read relative to the current time.
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
        const start = new Date(now.getTime());
        start.setMinutes(0, 0, 0);
        const end = new Date(start.getTime() + 24 * 3600 * 1000);

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

        for (let hour = 0; hour <= 24; hour += 3) {
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
            const colour = ACTION_COLOURS[entry.type] || 'var(--accent)';

            const x = scaleX(from);
            const barWidth = to ? Math.max(5, scaleX(to) - x) : 5;
            const barX = to ? x : x - 2;

            const bar = element('rect', {
                class: 'timeline-bar',
                x: barX, y: y + 5, width: barWidth, height: rowHeight - 12, rx: 3,
                fill: colour, opacity: entry.certain ? 0.95 : 0.55
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
                () => bar.setAttribute('opacity', entry.certain ? '0.95' : '0.55')
            );

            svg.appendChild(hit);
        });

        const nowX = scaleX(now);
        svg.appendChild(element('line', {
            class: 'timeline-now', x1: nowX, x2: nowX, y1: padding.top - 6, y2: height - padding.bottom
        }));

        container.replaceChildren(svg);
    }

    return {priceChart, weatherChart, timelineChart, hideTooltip: Tooltip.hide};
})();
