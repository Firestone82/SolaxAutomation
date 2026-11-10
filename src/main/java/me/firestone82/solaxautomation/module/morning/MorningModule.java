package me.firestone82.solaxautomation.module.morning;

import me.firestone82.solaxautomation.core.module.AbstractAutomationModule;
import me.firestone82.solaxautomation.core.module.ActionType;
import me.firestone82.solaxautomation.core.module.ConfigEntry;
import me.firestone82.solaxautomation.core.module.ModuleState;
import me.firestone82.solaxautomation.core.module.PlannedAction;
import me.firestone82.solaxautomation.core.module.PlannedAction.Message;
import me.firestone82.solaxautomation.core.timeline.TimelineService;
import me.firestone82.solaxautomation.integration.meteosource.MeteoSourceService;
import me.firestone82.solaxautomation.integration.meteosource.model.MeteoDayHourly;
import me.firestone82.solaxautomation.integration.ote.OteService;
import me.firestone82.solaxautomation.integration.ote.model.PriceForecast;
import me.firestone82.solaxautomation.integration.ote.model.PriceSlot;
import me.firestone82.solaxautomation.integration.raspberry.RaspberryPiService;
import me.firestone82.solaxautomation.integration.solax.InverterGateway;
import me.firestone82.solaxautomation.module.discharge.DischargeModule;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Buys the battery full from the night tariff and sells the morning peak.
 * <p>
 * The house has a second supply with a cheap night tariff. When the module is on, it decides
 * at the start of the night charge window whether filling the battery from that tariff is
 * worth it, and there are two reasons it can be:
 * <ul>
 *   <li><b>A bad day.</b> The forecast for the day's productive hours is dull enough that the
 *       panels will not fill the battery, so the night tariff does it instead and the house
 *       runs on that rather than on daytime grid power.</li>
 *   <li><b>A good morning price.</b> Some interval of the morning sale window reaches the
 *       configured price, so energy bought at night can be sold at a profit after it.</li>
 * </ul>
 * The charge runs as a remote control session timed to end with the window, so it stops on its
 * own even if this application does not. A check every minute ends it early once the battery is
 * full, or if the house moves off the second supply.
 * <p>
 * From the start of the sale window, the morning is sold into wherever the price reaches the
 * same threshold - but only on the metered grid, where the export is actually paid for. The
 * sale is handed to the selling module, so it runs exactly like an evening sale: the panels go
 * first, the guard stops it at the reserve, and the dashboard shows it. On a bad day nothing is
 * sold: the battery is only worth emptying when the sun will fill it again, and that charge was
 * bought to carry the house. {@code sell.on-bad-day} sells down to a higher reserve instead.
 */
@Component
public class MorningModule extends AbstractAutomationModule<MorningProperties> {

    public static final String ID = "morning";

    private final InverterGateway inverter;
    private final OteService oteService;
    private final MeteoSourceService weatherService;
    private final RaspberryPiService raspberryPi;
    private final ObjectProvider<DischargeModule> dischargeModule;

    /** The day the night charge was last decided for, so it is decided once per day. */
    private volatile LocalDate chargeDecidedFor = null;

    /** The day the morning sale was last decided for. */
    private volatile LocalDate sellDecidedFor = null;

    /** When the running night charge ends, {@code null} while none is running. */
    private volatile LocalDateTime chargingUntil = null;

    public MorningModule(
            MorningProperties properties,
            TimelineService timeline,
            InverterGateway inverter,
            OteService oteService,
            MeteoSourceService weatherService,
            RaspberryPiService raspberryPi,
            ObjectProvider<DischargeModule> dischargeModule
    ) {
        super(properties, timeline);
        this.inverter = inverter;
        this.oteService = oteService;
        this.weatherService = weatherService;
        this.raspberryPi = raspberryPi;
        this.dischargeModule = dischargeModule;
    }

    // ------------------------------------------------------------------ module metadata

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Morning buy and sell";
    }

    @Override
    public String getDescription() {
        return "Charges the battery from the night tariff when the day looks bad or the morning price is "
                + "worth selling into, and sells the morning peak.";
    }

    @Override
    public String getConfigPrefix() {
        return "automation.morning";
    }

    @Override
    public List<ConfigEntry> getConfiguration() {
        MorningProperties.Charge charge = properties.getCharge();
        MorningProperties.BadWeather weather = properties.getBadWeather();
        MorningProperties.Sell sell = properties.getSell();

        return List.of(
                ConfigEntry.of("automation.morning.charge.from", "Charge from", charge.getFrom(),
                        "When the night charge starts and tonight's decision is made"),
                ConfigEntry.of("automation.morning.charge.to", "Charge until", charge.getTo(),
                        "When the night charge ends, whatever the battery reached"),
                ConfigEntry.of("automation.morning.charge.target-battery", "Charge to", charge.getTargetBattery(), "%",
                        "State of charge the night charge stops at"),
                ConfigEntry.of("automation.morning.charge.power", "Charge power", charge.getPower(), "W",
                        "Power the battery is charged at from the grid"),
                ConfigEntry.of("automation.morning.charge.require-second-supply", "Night tariff only", charge.isRequireSecondSupply(),
                        "Charge only while the house is on the second supply"),
                ConfigEntry.of("automation.morning.bad-weather.min-quality", "Bad day from", weather.getMinQuality(),
                        "Mean forecast quality at or above which the day counts as bad"),
                ConfigEntry.of("automation.morning.bad-weather.from-hour", "Judged from", weather.getFromHour(), "h",
                        "First hour of the day the forecast is judged over"),
                ConfigEntry.of("automation.morning.bad-weather.to-hour", "Judged until", weather.getToHour(), "h",
                        "Hour the judged part of the day ends"),
                ConfigEntry.of("automation.morning.sell.from", "Sell from", sell.getFrom(),
                        "Earliest the morning sale starts"),
                ConfigEntry.of("automation.morning.sell.to", "Sell until", sell.getTo(),
                        "Latest the morning sale runs to"),
                ConfigEntry.of("automation.morning.sell.min-price", "Minimum price", sell.getMinPrice(), "CZK/kWh",
                        "Morning price that makes the night charge worth buying and an interval worth selling into"),
                ConfigEntry.of("automation.morning.sell.reserve-battery", "Reserve", sell.getReserveBattery(), "%",
                        "State of charge the morning sale stops at"),
                ConfigEntry.of("automation.morning.sell.on-bad-day", "Sell on a bad day", sell.isOnBadDay(),
                        "Sell the morning on a bad day too, down to the bad day reserve"),
                ConfigEntry.of("automation.morning.sell.bad-weather-reserve", "Bad day reserve", sell.getBadWeatherReserve(), "%",
                        "State of charge the morning sale stops at on a bad day, when it sells at all")
        );
    }

    // ------------------------------------------------------------------ scheduling

    /**
     * Checks once a minute what is due.
     * <p>
     * A minute tick rather than a cron at the window's start, because a restart or a short
     * outage at 02:00 must not cost the night: whenever the module finds itself inside a window
     * it has not decided yet today, it decides then, for the rest of the window.
     */
    @Scheduled(cron = "${automation.morning.tick-cron:0 * * * * *}")
    public void tick() {
        if (isEnabled()) {
            tick(LocalDateTime.now());
        }
    }

    synchronized void tick(LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        LocalTime time = now.toLocalTime();

        if (chargingUntil != null) {
            guardCharge(now);
        }

        MorningProperties.Charge charge = properties.getCharge();
        if (within(time, charge.getFrom(), charge.getTo()) && !today.equals(chargeDecidedFor)) {
            chargeDecidedFor = today;
            run(Message.key("running.morning.charge", "Deciding on the night charge").build(),
                    () -> decideCharge(now));
        }

        MorningProperties.Sell sell = properties.getSell();
        if (sell.isEnabled() && within(time, sell.getFrom(), sell.getTo()) && !today.equals(sellDecidedFor)) {
            sellDecidedFor = today;
            run(Message.key("running.morning.sell", "Checking the morning prices for a sale").build(),
                    () -> decideSale(now));
        }
    }

    // ------------------------------------------------------------------ night charge

    private RunOutcome decideCharge(LocalDateTime now) {
        MorningProperties.Charge charge = properties.getCharge();
        Message notStarted = Message.key("outcome.morning.notCharging", "No night charge").build();

        Evaluation evaluation = evaluate(now.toLocalDate());
        log.detail("Forecast", "{} (bad at {} or more)", describe(evaluation.quality()), properties.getBadWeather().getMinQuality());
        log.detail("Morning peak", "{} CZK/kWh{} (worth selling at {} or more)",
                describe(evaluation.bestPrice()), evaluation.bestAt() == null ? "" : " at " + evaluation.bestAt(),
                properties.getSell().getMinPrice());

        Optional<Integer> socOpt = inverter.getBatterySoc();
        if (socOpt.isEmpty()) {
            return RunOutcome.incomplete(notStarted, Message
                    .key("outcome.morning.noSoc", "The battery level could not be read, so nothing was bought.").build());
        }

        int soc = socOpt.get();
        log.detail("Battery", "{} % (charging to {} %)", soc, charge.getTargetBattery());

        if (soc >= charge.getTargetBattery()) {
            return RunOutcome.unchanged(notStarted, Message
                    .key("outcome.morning.full", String.format(Locale.ROOT,
                            "The battery is at %d %%, at or above the %d %% the night charge fills it to.",
                            soc, charge.getTargetBattery()))
                    .with("battery", soc)
                    .with("target", charge.getTargetBattery())
                    .build());
        }

        if (!evaluation.worthCharging()) {
            log.noAction("neither a bad day nor a morning price worth selling into");
            return RunOutcome.unchanged(notStarted, reason(evaluation));
        }

        if (charge.isRequireSecondSupply() && raspberryPi.isOnMeteredGrid()) {
            return RunOutcome.unchanged(notStarted, Message
                    .key("outcome.morning.meteredGrid",
                            "There is a reason to charge tonight, but the house is on the metered grid, where the night tariff does not apply, so nothing is bought.")
                    .build());
        }

        if (!inverter.isRemoteControlAvailable()) {
            return RunOutcome.incomplete(notStarted, Message
                    .key("outcome.morning.noRemoteControl",
                            "Remote control is unavailable, so the battery cannot be charged from the grid. Configure the SolaX Cloud connection.")
                    .build());
        }

        LocalDateTime until = now.toLocalDate().atTime(charge.getTo());
        Duration duration = Duration.between(now, until);

        log.action("Charging {} W from the grid until {} ({} min)", charge.getPower(), charge.getTo(), duration.toMinutes());

        if (!inverter.startRemoteCharge(charge.getPower(), duration)) {
            return RunOutcome.incomplete(notStarted, Message
                    .key("outcome.morning.rejected", "The inverter did not accept the charge command, so nothing is being bought.")
                    .build());
        }

        chargingUntil = until;
        Message why = reason(evaluation);

        // "charge" and "until" are what the dashboard draws the charge's stretch from; the
        // entries that end it early are the remote control exits recorded below.
        timeline.record(ID, ActionType.GRID_CHARGE,
                Message.key("history.morning.chargeStarted", "Night charge started")
                        .with("charge", "start")
                        .with("watts", charge.getPower())
                        .with("targetSoc", charge.getTargetBattery())
                        .with("until", until.withNano(0).toString())
                        .build(), true, why);
        log.success("Night charge running until {}", charge.getTo());

        return RunOutcome.changed(Message
                .key("outcome.morning.charging", String.format(Locale.ROOT,
                        "Charging to %d %% until %s", charge.getTargetBattery(), charge.getTo()))
                .with("target", charge.getTargetBattery())
                .with("until", charge.getTo().toString())
                .build(), why);
    }

    /**
     * Ends the night charge when it has done its job, or when the reason it was cheap is gone.
     * The session also ends on its own at the end of the window, which is only recorded here.
     */
    private void guardCharge(LocalDateTime now) {
        MorningProperties.Charge charge = properties.getCharge();
        Integer soc = inverter.getBatterySoc().orElse(null);

        if (!now.isBefore(chargingUntil)) {
            chargingUntil = null;
            timeline.record(ID, ActionType.REMOTE_CONTROL_EXIT, Message
                            .key("history.morning.chargeFinished", "Night charge finished").build(), true,
                    Message.key("history.morning.chargeFinished.detail", String.format(Locale.ROOT,
                                    "The night tariff window ended at %s with the battery at %s %%.", charge.getTo(), describe(soc)))
                            .with("until", charge.getTo().toString())
                            .with("battery", describe(soc))
                            .build());
            publishStatus(ModuleState.IDLE, Message.key("summary.morning.chargeFinished", "Night charge finished").build());
            return;
        }

        if (soc != null && soc >= charge.getTargetBattery()) {
            stopCharge(Message.key("history.morning.chargeFull", "Night charge stopped, battery full").build(),
                    Message.key("history.morning.chargeFull.detail", String.format(Locale.ROOT,
                                    "The battery reached %d %%, the %d %% the night charge fills it to.", soc, charge.getTargetBattery()))
                            .with("battery", soc)
                            .with("target", charge.getTargetBattery())
                            .build());
            return;
        }

        if (charge.isRequireSecondSupply() && raspberryPi.isOnMeteredGrid()) {
            stopCharge(Message.key("history.morning.chargeSupply", "Night charge stopped, house on the metered grid").build(),
                    Message.key("history.morning.chargeSupply.detail", String.format(Locale.ROOT,
                                    "The house moved to the metered grid with the battery at %s %%, and the night tariff does not apply there.",
                                    describe(soc)))
                            .with("battery", describe(soc))
                            .build());
            return;
        }

        publishStatus(ModuleState.ACTIVE,
                Message.key("summary.morning.charging", String.format(Locale.ROOT,
                                "Charging to %d %% from the night tariff", charge.getTargetBattery()))
                        .with("target", charge.getTargetBattery())
                        .build(),
                Message.key("summary.morning.charging.detail", String.format(Locale.ROOT,
                                "Battery at %s %%, until %s.", describe(soc), charge.getTo()))
                        .with("battery", describe(soc))
                        .with("until", charge.getTo().toString())
                        .build());
    }

    private void stopCharge(Message headline, Message detail) {
        log.action("{} - {}", headline.text(), detail.text());
        boolean stopped = inverter.stopRemoteControl();
        chargingUntil = null;

        timeline.record(ID, ActionType.REMOTE_CONTROL_EXIT, headline, stopped, detail);
        publishStatus(ModuleState.IDLE, headline, detail);
    }

    // ------------------------------------------------------------------ morning sale

    private RunOutcome decideSale(LocalDateTime now) {
        MorningProperties.Sell sell = properties.getSell();
        Message noSale = Message.key("outcome.morning.noSale", "No morning sale").build();

        DischargeModule discharge = dischargeModule.getIfAvailable();
        if (discharge == null || !discharge.isEnabled()) {
            return RunOutcome.unchanged(noSale, Message
                    .key("outcome.morning.sellingDisabled", "The selling module is disabled, and it is the one that runs the sale.")
                    .build());
        }

        Optional<PriceForecast> forecastOpt = oteService.getForecast();
        if (forecastOpt.isEmpty()) {
            return RunOutcome.incomplete(noSale, Message
                    .key("outcome.morning.noPrices", "Spot prices are unavailable, so the morning could not be judged.").build());
        }

        List<PriceSlot> run = firstRunAtOrAbove(forecastOpt.get().getTodaySorted(), now.toLocalTime());

        if (run.isEmpty()) {
            Double best = bestPrice(slotsInSellWindow(forecastOpt.get().getTodaySorted())).map(PriceSlot::getPriceCzkPerKwh).orElse(null);

            return RunOutcome.unchanged(noSale, Message
                    .key("outcome.morning.noSale.price", String.format(Locale.ROOT,
                            "No interval between %s and %s reaches the %.2f CZK/kWh worth selling at. The best is %s CZK/kWh.",
                            sell.getFrom(), sell.getTo(), sell.getMinPrice(), describe(best)))
                    .with("from", sell.getFrom().toString())
                    .with("to", sell.getTo().toString())
                    .with("minPrice", sell.getMinPrice())
                    .with("best", describe(best))
                    .build());
        }

        if (sell.isRequireMeteredGrid() && !raspberryPi.isOnMeteredGrid()) {
            return RunOutcome.unchanged(noSale, Message
                    .key("outcome.morning.noSale.supply",
                            "The morning price is worth selling into, but the house is on the second supply, where the export never reaches the meter.")
                    .build());
        }

        Evaluation outlook = evaluate(now.toLocalDate());
        boolean badDay = outlook.badWeather();

        // The battery is only worth emptying this morning if the panels will fill it again
        // today. A bad day, or one nobody can say anything about, keeps it whole.
        if (!sell.isOnBadDay() && (badDay || outlook.quality() == null)) {
            MorningProperties.BadWeather weather = properties.getBadWeather();
            String hours = String.format(Locale.ROOT, "%02d:00-%02d:00", weather.getFromHour(), weather.getToHour());

            return RunOutcome.unchanged(noSale, outlook.quality() == null
                    ? Message.key("outcome.morning.noSale.noForecast", String.format(Locale.ROOT,
                                    "The morning price is worth selling into, but there is no forecast for %s, so nothing says the panels will fill the battery again. Nothing is sold.",
                                    hours))
                            .with("hours", hours)
                            .build()
                    : Message.key("outcome.morning.noSale.badDay", String.format(Locale.ROOT,
                                    "The morning price is worth selling into, but the forecast for %s scores %s, a bad day from %.1f. The panels will not fill the battery again, so nothing is sold.",
                                    hours, describe(outlook.quality()), weather.getMinQuality()))
                            .with("hours", hours)
                            .with("quality", describe(outlook.quality()))
                            .with("minQuality", weather.getMinQuality())
                            .build());
        }

        int reserve = badDay ? sell.getBadWeatherReserve() : sell.getReserveBattery();

        Optional<Integer> socOpt = inverter.getBatterySoc();
        if (socOpt.isEmpty()) {
            return RunOutcome.incomplete(noSale, Message
                    .key("outcome.morning.noSoc.sale", "The battery level could not be read, so nothing was sold.").build());
        }

        int soc = socOpt.get();
        if (soc <= reserve) {
            return RunOutcome.unchanged(noSale, Message
                    .key("outcome.morning.noSale.reserve", String.format(Locale.ROOT,
                            "The battery is at %d %%, at or below the %d %% kept for the day.", soc, reserve))
                    .with("battery", soc)
                    .with("reserve", reserve)
                    .build());
        }

        LocalDate today = now.toLocalDate();
        LocalDateTime from = latest(now.truncatedTo(ChronoUnit.MINUTES), today.atTime(run.getFirst().getStart()));
        LocalDateTime to = today.atTime(run.getLast().getEnd());
        double peak = bestPrice(run).map(PriceSlot::getPriceCzkPerKwh).orElse(0.0);

        log.detail("Selling into", "{} - {} (peak {} CZK/kWh)", from.toLocalTime(), to.toLocalTime(), round(peak));
        log.detail("Reserve", "{} %{}", reserve, badDay ? " (bad day)" : "");

        Optional<Message> refused = discharge.armFor(ID, from, to, sell.getPower() > 0 ? sell.getPower() : null, reserve);
        if (refused.isPresent()) {
            return RunOutcome.unchanged(noSale, refused.get());
        }

        // Whatever the night charge still had to do, the sale replaces its session now.
        chargingUntil = null;

        return RunOutcome.changed(Message
                        .key("outcome.morning.saleArmed", String.format(Locale.ROOT,
                                "Morning sale armed %s-%s", from.toLocalTime(), to.toLocalTime()))
                        .with("from", from.toLocalTime().toString())
                        .with("to", to.toLocalTime().toString())
                        .build(),
                Message.key("outcome.morning.saleArmed.detail", String.format(Locale.ROOT,
                                "The price is at or above %.2f CZK/kWh from %s to %s, peaking at %.2f CZK/kWh. The battery is sold down to %d %%.",
                                sell.getMinPrice(), from.toLocalTime(), to.toLocalTime(), peak, reserve))
                        .with("minPrice", sell.getMinPrice())
                        .with("from", from.toLocalTime().toString())
                        .with("to", to.toLocalTime().toString())
                        .with("peak", round(peak))
                        .with("reserve", reserve)
                        .build());
    }

    /**
     * The first unbroken run of intervals at or above the minimum price, from the interval
     * running at {@code now} to the end of the sale window.
     */
    private List<PriceSlot> firstRunAtOrAbove(List<PriceSlot> slots, LocalTime now) {
        List<PriceSlot> run = new ArrayList<>();

        for (PriceSlot slot : slotsInSellWindow(slots)) {
            // An interval that already ended is no use, one that is running still is.
            if (!slot.getStart().plusMinutes(PriceSlot.SLOT_MINUTES).isAfter(now)) {
                continue;
            }

            if (slot.getPriceCzkPerKwh() >= properties.getSell().getMinPrice()) {
                run.add(slot);
            } else if (!run.isEmpty()) {
                break;
            }
        }

        return run;
    }

    private List<PriceSlot> slotsInSellWindow(List<PriceSlot> slots) {
        MorningProperties.Sell sell = properties.getSell();

        return slots.stream()
                .filter(slot -> !slot.getStart().isBefore(sell.getFrom()) && slot.getStart().isBefore(sell.getTo()))
                .toList();
    }

    private static Optional<PriceSlot> bestPrice(List<PriceSlot> slots) {
        return slots.stream().max(Comparator.comparingDouble(PriceSlot::getPriceCzkPerKwh));
    }

    // ------------------------------------------------------------------ the day's outlook

    /**
     * Whether {@code day} is worth a night charge, and the numbers behind it.
     *
     * @param quality   mean forecast quality over the judged hours, {@code null} when unknown
     * @param bestPrice highest price of the sale window, CZK/kWh, {@code null} when unknown
     * @param bestAt    when that price is
     */
    record Evaluation(boolean badWeather, Double quality, boolean goodPrice, Double bestPrice, LocalTime bestAt) {

        boolean worthCharging() {
            return badWeather || goodPrice;
        }
    }

    /**
     * Judges a day against both reasons. Used for the decision itself and, ahead of it, for
     * the timeline's preview of what tonight is likely to bring.
     */
    Evaluation evaluate(LocalDate day) {
        MorningProperties.BadWeather weather = properties.getBadWeather();
        MorningProperties.Sell sell = properties.getSell();

        Double quality = null;
        if (weather.isEnabled() && weather.getToHour() > weather.getFromHour()) {
            List<MeteoDayHourly> hours = weatherService.getHoursBetween(
                    day.atTime(weather.getFromHour(), 0), day.atTime(weather.getToHour() - 1, 0));

            if (!hours.isEmpty()) {
                quality = round(MeteoDayHourly.avgQuality(hours));
            }
        }

        Double best = null;
        LocalTime bestAt = null;

        if (sell.isEnabled()) {
            Optional<PriceSlot> peak = oteService.getForecast()
                    .map(forecast -> day.equals(LocalDate.now()) ? forecast.getTodaySorted()
                            : day.equals(LocalDate.now().plusDays(1)) ? forecast.getTomorrowSorted() : List.<PriceSlot>of())
                    .flatMap(slots -> bestPrice(slotsInSellWindow(slots)));

            if (peak.isPresent()) {
                best = round(peak.get().getPriceCzkPerKwh());
                bestAt = peak.get().getStart();
            }
        }

        boolean badWeather = quality != null && quality >= weather.getMinQuality();
        boolean goodPrice = best != null && best >= sell.getMinPrice();

        return new Evaluation(badWeather, quality, goodPrice, best, bestAt);
    }

    /** The sentence saying why the night is, or is not, worth a charge. */
    private Message reason(Evaluation evaluation) {
        MorningProperties.BadWeather weather = properties.getBadWeather();
        MorningProperties.Sell sell = properties.getSell();

        String hours = String.format(Locale.ROOT, "%02d:00-%02d:00", weather.getFromHour(), weather.getToHour());
        String quality = describe(evaluation.quality());
        String price = describe(evaluation.bestPrice());
        String at = evaluation.bestAt() == null ? "-" : evaluation.bestAt().toString();

        // On a bad day the morning is not sold unless that is switched on, so the price is no
        // reason to have charged and saying so would promise a sale that will not come.
        if (evaluation.badWeather() && evaluation.goodPrice() && properties.getSell().isOnBadDay()) {
            return Message.key("outcome.morning.reason.both", String.format(Locale.ROOT,
                            "The forecast for %s scores %s, a bad day from %.1f, and the morning peaks at %s CZK/kWh at %s, at or above the %.2f CZK/kWh worth selling at. The battery is filled from the night tariff.",
                            hours, quality, weather.getMinQuality(), price, at, sell.getMinPrice()))
                    .with("hours", hours).with("quality", quality).with("minQuality", weather.getMinQuality())
                    .with("price", price).with("at", at).with("minPrice", sell.getMinPrice())
                    .build();
        }

        if (evaluation.badWeather()) {
            return Message.key("outcome.morning.reason.weather", String.format(Locale.ROOT,
                            "The forecast for %s scores %s, a bad day from %.1f, so the battery is filled from the night tariff to carry the house through it.",
                            hours, quality, weather.getMinQuality()))
                    .with("hours", hours).with("quality", quality).with("minQuality", weather.getMinQuality())
                    .build();
        }

        if (evaluation.goodPrice()) {
            return Message.key("outcome.morning.reason.price", String.format(Locale.ROOT,
                            "The morning peaks at %s CZK/kWh at %s, at or above the %.2f CZK/kWh worth selling at, so the battery is filled from the night tariff and sold from %s.",
                            price, at, sell.getMinPrice(), sell.getFrom()))
                    .with("price", price).with("at", at).with("minPrice", sell.getMinPrice())
                    .with("from", sell.getFrom().toString())
                    .build();
        }

        return Message.key("outcome.morning.reason.none", String.format(Locale.ROOT,
                        "The forecast for %s scores %s, under the %.1f of a bad day, and the morning peaks at %s CZK/kWh, under the %.2f CZK/kWh worth selling at. There is nothing to charge for.",
                        hours, quality, weather.getMinQuality(), price, sell.getMinPrice()))
                .with("hours", hours).with("quality", quality).with("minQuality", weather.getMinQuality())
                .with("price", price).with("minPrice", sell.getMinPrice())
                .build();
    }

    // ------------------------------------------------------------------ dashboard support

    @Override
    public List<PlannedAction> getPlannedActions() {
        List<PlannedAction> actions = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        MorningProperties.Charge charge = properties.getCharge();
        MorningProperties.Sell sell = properties.getSell();

        LocalDateTime running = chargingUntil;
        if (running != null) {
            actions.add(PlannedAction.committed(ID, now, running, ActionType.GRID_CHARGE, Message
                    .key("planned.morning.charge", String.format(Locale.ROOT,
                            "Charge to %d %% from the night tariff until %s", charge.getTargetBattery(), charge.getTo()))
                    .with("target", charge.getTargetBattery())
                    .with("until", charge.getTo().toString())
                    .build()));
        }

        LocalDateTime nextCharge = nextOccurrence(now, charge.getFrom(), chargeDecidedFor);
        actions.add(chargePreview(nextCharge));

        if (sell.isEnabled()) {
            LocalDateTime nextSale = nextOccurrence(now, sell.getFrom(), sellDecidedFor);
            Evaluation outlook = evaluate(nextSale.toLocalDate());

            // What the forecast already says about that morning, so a sale that the weather
            // rules out is not shown as one waiting on the price.
            Message message = !sell.isOnBadDay() && outlook.badWeather()
                    ? Message.key("planned.morning.sellSkipped", String.format(Locale.ROOT,
                                    "No morning sale, the forecast is bad (%s)", describe(outlook.quality())))
                            .with("quality", describe(outlook.quality()))
                            .build()
                    : Message.key("planned.morning.sellCheck", String.format(Locale.ROOT,
                                    "Sell from %s if the price is at or above %.2f CZK/kWh", sell.getFrom(), sell.getMinPrice()))
                            .with("from", sell.getFrom().toString())
                            .with("minPrice", sell.getMinPrice())
                            .build();

            actions.add(PlannedAction.at(ID, nextSale, ActionType.CHECK, message));
        }

        return actions;
    }

    /**
     * The next night charge as the timeline shows it: what the decision would be with what is
     * known now. The prices for a night after midnight are only published the afternoon before,
     * so until then the price half of the preview has nothing to go on.
     */
    private PlannedAction chargePreview(LocalDateTime at) {
        MorningProperties.Charge charge = properties.getCharge();
        Evaluation evaluation = evaluate(at.toLocalDate());
        LocalDateTime until = at.toLocalDate().atTime(charge.getTo());

        if (evaluation.worthCharging()) {
            Message message = evaluation.badWeather()
                    ? Message.key("planned.morning.chargeLikely.weather", String.format(Locale.ROOT,
                                    "Charge to %d %% from the night tariff, the forecast is bad (%s)",
                                    charge.getTargetBattery(), describe(evaluation.quality())))
                            .with("target", charge.getTargetBattery())
                            .with("quality", describe(evaluation.quality()))
                            .build()
                    : Message.key("planned.morning.chargeLikely.price", String.format(Locale.ROOT,
                                    "Charge to %d %% from the night tariff, the morning peaks at %s CZK/kWh",
                                    charge.getTargetBattery(), describe(evaluation.bestPrice())))
                            .with("target", charge.getTargetBattery())
                            .with("price", describe(evaluation.bestPrice()))
                            .build();

            return new PlannedAction(ID, at, until, ActionType.GRID_CHARGE,
                    message.text(), message.key(), message.params(), false);
        }

        return PlannedAction.at(ID, at, ActionType.CHECK, Message
                .key("planned.morning.chargeCheck", String.format(Locale.ROOT,
                        "Decide on the night charge: a bad day or a morning price at or above %.2f CZK/kWh",
                        properties.getSell().getMinPrice()))
                .with("minPrice", properties.getSell().getMinPrice())
                .build());
    }

    /** Today at {@code time} if that is still ahead and not decided yet, tomorrow at it otherwise. */
    private static LocalDateTime nextOccurrence(LocalDateTime now, LocalTime time, LocalDate decidedFor) {
        LocalDateTime today = now.toLocalDate().atTime(time);
        boolean todayStillOpen = today.isAfter(now) && !now.toLocalDate().equals(decidedFor);
        return todayStillOpen ? today : today.plusDays(1);
    }

    /** Whether a night charge is running right now. */
    public boolean isCharging() {
        return chargingUntil != null;
    }

    // ------------------------------------------------------------------ helpers

    /** {@code [from, to)} on one day. A window that would wrap midnight is never open. */
    private static boolean within(LocalTime time, LocalTime from, LocalTime to) {
        return !time.isBefore(from) && time.isBefore(to);
    }

    private static LocalDateTime latest(LocalDateTime a, LocalDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    private static String describe(Number value) {
        if (value == null) {
            return "?";
        }

        return value instanceof Integer ? value.toString() : String.format(Locale.ROOT, "%.2f", value.doubleValue());
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
