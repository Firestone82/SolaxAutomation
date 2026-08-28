package me.firestone82.solaxautomation.module.export;

import com.pi4j.io.gpio.digital.DigitalState;
import jakarta.annotation.PostConstruct;
import me.firestone82.solaxautomation.core.module.*;
import me.firestone82.solaxautomation.core.module.PlannedAction.Message;
import me.firestone82.solaxautomation.core.schedule.Schedules;
import me.firestone82.solaxautomation.core.timeline.TimelineService;
import me.firestone82.solaxautomation.integration.meteosource.MeteoSourceService;
import me.firestone82.solaxautomation.integration.meteosource.model.MeteoDayHourly;
import me.firestone82.solaxautomation.integration.meteosource.model.WeatherForecast;
import me.firestone82.solaxautomation.integration.ote.OteService;
import me.firestone82.solaxautomation.integration.ote.model.PriceSlot;
import me.firestone82.solaxautomation.integration.raspberry.RaspberryPiService;
import me.firestone82.solaxautomation.integration.solax.InverterGateway;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Locale;

/**
 * Keeps the inverter from exporting when exporting is not worth it.
 * <p>
 * Spot prices regularly go to zero or below around midday. Exporting into a negative price
 * costs money, so the module drops the export limit to a token value while the price is low
 * and opens it again once the price recovers.
 * <p>
 * A GPIO switch tells the application which supply the house is currently connected to. When
 * it is LOW the export does not reach the metered grid connection at all, so a low price is
 * no reason to hold back and the limit stays open - except during the configured midday
 * window on dull days, where a reduced limit keeps more of the production in the battery.
 */
@Component
public class ExportModule extends AbstractAutomationModule<ExportProperties> {

    public static final String ID = "export";

    private final InverterGateway inverter;
    private final OteService oteService;
    private final MeteoSourceService weatherService;
    private final RaspberryPiService raspberryPi;

    /** Own dedup state for {@link #subscribeToConnectionSwitch()}, separate from
     * {@link RaspberryPiService}'s tracking so the two concerns don't share mutable state. */
    private volatile DigitalState previousSwitchState;

    public ExportModule(
            ExportProperties properties,
            InverterGateway inverter,
            OteService oteService,
            MeteoSourceService weatherService,
            RaspberryPiService raspberryPi,
            TimelineService timeline
    ) {
        super(properties, timeline);
        this.inverter = inverter;
        this.oteService = oteService;
        this.weatherService = weatherService;
        this.raspberryPi = raspberryPi;
    }

    /** Reacts to the physical supply switch as well as to the hourly schedule. */
    @PostConstruct
    void subscribeToConnectionSwitch() {
        this.previousSwitchState = raspberryPi.getConnectionSwitchState();

        raspberryPi.getConnectionSwitch().addListener(event -> {
            if (!isEnabled()) {
                return;
            }

            DigitalState newState = event.state();

            if (previousSwitchState == newState) {
                log.debug("Connection switch reported {} again, ignoring", newState);
                return;
            }

            previousSwitchState = newState;

            int hour = LocalTime.now().getHour();
            if (!properties.getActiveHours().contains(hour)) {
                log.debug("Connection switch changed to {} outside the active hours, ignoring", newState);
                return;
            }

            run("Connection switch changed to " + newState, this::evaluate);
        });
    }

    // ------------------------------------------------------------------ module metadata

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Export limit";
    }

    @Override
    public String getDescription() {
        return "Closes the export limit while the spot price is too low to be worth selling, "
                + "and throttles it around midday on dull days.";
    }

    @Override
    public String getConfigPrefix() {
        return "automation.export";
    }

    @Override
    public List<ConfigEntry> getConfiguration() {
        return List.of(
                ConfigEntry.of("automation.export.check-cron", "Checked", properties.getCheckCron(),
                        "When the export limit is evaluated"),
                ConfigEntry.of("automation.export.min-price", "Minimum price", properties.getMinPrice(), "CZK/kWh",
                        "Below this price the export limit is closed"),
                ConfigEntry.of("automation.export.active-hours", "Active hours",
                        properties.getActiveHours().getFrom() + ":00 - " + properties.getActiveHours().getTo() + ":59",
                        "Outside these hours the export limit is left untouched"),
                ConfigEntry.of("automation.export.power.maximum", "Open limit", properties.getPower().getMaximum(), "W",
                        "Export limit while exporting is worth it"),
                ConfigEntry.of("automation.export.power.minimum", "Closed limit", properties.getPower().getMinimum(), "W",
                        "Export limit while the price is too low"),
                ConfigEntry.of("automation.export.power.reduced", "Reduced limit", properties.getPower().getReduced(), "W",
                        "Export limit during the midday reduction window"),
                ConfigEntry.of("automation.export.reduced-window", "Reduction window",
                        properties.getReducedWindow().isEnabled()
                                ? properties.getReducedWindow().getFrom() + ":00 - " + properties.getReducedWindow().getTo() + ":59"
                                : "disabled",
                        "Midday window where export is throttled on dull days"),
                ConfigEntry.of("automation.export.reduced-window.max-weather-quality", "Reduction threshold",
                        properties.getReducedWindow().getMaxWeatherQuality(),
                        "Reduce only while the forecast quality is at or below this value")
        );
    }

    @Override
    public List<PlannedAction> getPlannedActions() {
        // Every firing in the next day, not just the next one - a quarter-hourly module
        // should look quarter-hourly on the timeline.
        return Schedules.upcoming(properties.getCheckCron(), at -> properties.getActiveHours().contains(at.getHour()))
                .stream()
                .map(at -> PlannedAction.at(ID, at, ActionType.CHECK, Message
                        .key("planned.export.check",
                                "Compare the spot price against " + properties.getMinPrice() + " CZK/kWh and set the export limit")
                        .with("price", properties.getMinPrice())
                        .build()))
                .toList();
    }

    // ------------------------------------------------------------------ execution

    /**
     * Quarter-hourly check within the active hours, matching how often the price changes.
     */
    @Scheduled(cron = "${automation.export.check-cron:0 4,19,34,49 * * * *}")
    public void checkExportLimit() {
        if (!isEnabled()) {
            return;
        }

        LocalTime now = LocalTime.now();
        if (!properties.getActiveHours().contains(now.getHour())) {
            log.debug("{}:00 is outside the active hours {}-{}, nothing to do",
                    now.getHour(), properties.getActiveHours().getFrom(), properties.getActiveHours().getTo());
            return;
        }

        run(String.format(Locale.ROOT, "Export limit check for %02d:%02d", now.getHour(), now.getMinute()), this::evaluate);
    }

    private RunOutcome evaluate() {
        Optional<PriceSlot> priceOpt = oteService.getCurrentPrice();
        if (priceOpt.isEmpty()) {
            return RunOutcome.incomplete("the current spot price is unavailable");
        }

        Optional<Integer> currentLimitOpt = inverter.getExportLimit();
        if (currentLimitOpt.isEmpty()) {
            return RunOutcome.incomplete("the current export limit could not be read");
        }

        PriceSlot price = priceOpt.get();
        int currentLimit = currentLimitOpt.get();
        DigitalState switchState = raspberryPi.getConnectionSwitch().state();
        boolean meteredGrid = switchState.isHigh();

        log.detail("Spot price", "{} CZK/kWh (threshold {} CZK/kWh)",
                round(price.getPriceCzkPerKwh()), properties.getMinPrice());
        log.detail("Connection switch", "{} ({})", switchState.name(), meteredGrid ? "metered grid" : "second supply");
        log.detail("Current limit", "{} W", currentLimit);

        int target;
        String because;

        if (price.getPriceCzkPerKwh() >= properties.getMinPrice()) {
            target = properties.getPower().getMaximum();
            because = "price is worth exporting at";
        } else if (meteredGrid) {
            target = properties.getPower().getMinimum();
            because = "price is too low and the house is on the metered grid";
        } else {
            target = properties.getPower().getMaximum();
            because = "price is too low but the export does not reach the metered grid";
        }

        // Midday throttle: only while the export is open and the sky is dull.
        if (target > properties.getPower().getMinimum() && !meteredGrid) {
            Optional<Double> qualityOpt = averageWeatherQuality();

            if (properties.getReducedWindow().contains(LocalTime.now().getHour()) && qualityOpt.isPresent()) {
                double quality = qualityOpt.get();

                log.detail("Weather quality", "{} (reduce at or below {})",
                        round(quality), properties.getReducedWindow().getMaxWeatherQuality());

                if (quality <= properties.getReducedWindow().getMaxWeatherQuality()) {
                    target = properties.getPower().getReduced();
                    because = "midday window and the forecast is dull, keeping more production in the battery";
                }
            }
        }

        log.detail("Target limit", "{} W", target);

        if (currentLimit == target) {
            log.noAction("the export limit is already {} W", target);
            return RunOutcome.unchanged(String.format(Locale.ROOT, "Export limit stays at %d W (%s)", target, because));
        }

        log.action("Changing the export limit from {} W to {} W - {}", currentLimit, target, because);

        boolean changed = inverter.setExportLimit(target);
        timeline.record(ID, ActionType.EXPORT_LIMIT, Message
                        .key("history.export.limit", String.format(Locale.ROOT, "Export limit %d W -> %d W", currentLimit, target))
                        .with("from", currentLimit)
                        .with("to", target)
                        .build(),
                changed, because);

        if (!changed) {
            log.error("The inverter did not accept the new export limit");
            return RunOutcome.incomplete("the inverter did not accept the new export limit");
        }

        log.success("Export limit set to {} W", target);
        return RunOutcome.changed(String.format(Locale.ROOT, "Export limit set to %d W (%s)", target, because));
    }

    /** Mean forecast quality for the hour before through two hours after now. */
    private Optional<Double> averageWeatherQuality() {
        Optional<WeatherForecast> forecastOpt = weatherService.getForecast();

        if (forecastOpt.isEmpty()) {
            log.debug("Weather forecast unavailable, skipping the midday reduction check");
            return Optional.empty();
        }

        LocalDateTime now = LocalDateTime.of(LocalDate.now(), LocalTime.now()).truncatedTo(ChronoUnit.HOURS);
        List<MeteoDayHourly> hours = forecastOpt.get().getHourlyBetween(now.minusHours(1), now.plusHours(2));

        if (hours.isEmpty()) {
            log.debug("Weather forecast has no hours around now, skipping the midday reduction check");
            return Optional.empty();
        }

        return Optional.of(MeteoDayHourly.avgQuality(hours));
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
