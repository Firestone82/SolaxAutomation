package me.firestone82.solaxautomation.statistics;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Everything recorded about one calendar day.
 * <p>
 * The energies are the inverter's own day counters as last seen - they only ever grow during
 * a day, so the last reading is the day's total. The money is not something the inverter
 * reports: it is built up sample by sample, each sample's growth in the export and import
 * counters valued at the spot price of the interval it was reported in.
 * <p>
 * Mutable and plain on purpose, so it serialises to and from the statistics file as is.
 */
@Data
@NoArgsConstructor
public class DayStatistics {

    private LocalDate date;

    /** PV yield, kWh. */
    private Double yieldKwh;

    /** Energy exported to the grid, kWh. */
    private Double exportKwh;

    /** Energy imported from the grid, kWh. */
    private Double importKwh;

    /** Energy charged into the battery, kWh. */
    private Double chargedKwh;

    /** Energy discharged from the battery, kWh. */
    private Double dischargedKwh;

    /** What the exported energy was worth at the spot price, CZK. */
    private double exportValue;

    /** What the imported energy cost at the spot price, CZK. */
    private double importCost;

    /** Energy exported while a sale was running, kWh - part of {@link #exportKwh}. */
    private double soldKwh;

    /** What that was worth, CZK - part of {@link #exportValue}. */
    private double soldValue;

    /**
     * Start times of the sales that ran, {@code HH:mm}. A set rather than a counter so a
     * restart in the middle of a sale does not count it twice.
     */
    private TreeSet<String> sales = new TreeSet<>();

    /**
     * True when the money only covers part of the day: the first reading of the day came
     * long after midnight, so everything exported or imported before it could not be put
     * against a price.
     */
    private boolean partial;

    private Integer socMin;
    private Integer socMax;

    /** Highest production seen, W. */
    private Double pvPeak;

    /** Highest house load seen, W. */
    private Double loadPeak;

    private Double priceAverage;
    private Double priceMin;
    private Double priceMax;

    /** Minutes spent in each work mode, by mode name. */
    private Map<String, Integer> modeMinutes = new LinkedHashMap<>();

    /** Minutes the inverter spent under a remote control session. */
    private int remoteControlMinutes;

    private int samples;
    private LocalDateTime firstSampleAt;
    private LocalDateTime lastSampleAt;

    public DayStatistics(LocalDate date) {
        this.date = date;
    }

    /**
     * House consumption, kWh, from the energy balance: what the panels made, plus what came
     * from the grid and out of the battery, minus what went to the grid and into the battery.
     * Without the battery counters the battery is left out, which is close over a day whose
     * battery ends roughly where it started. {@code null} until all three main counters exist.
     */
    public Double consumptionKwh() {
        if (yieldKwh == null || importKwh == null || exportKwh == null) {
            return null;
        }

        double battery = chargedKwh != null && dischargedKwh != null ? dischargedKwh - chargedKwh : 0;
        return Math.max(0, yieldKwh + importKwh - exportKwh + battery);
    }

    /** Share of the consumption that did not come from the grid, %. */
    public Double selfSufficiency() {
        Double consumption = consumptionKwh();

        if (consumption == null || consumption <= 0) {
            return null;
        }

        return Math.max(0, Math.min(100, (consumption - importKwh) / consumption * 100));
    }

    /** True once at least one energy counter has been recorded. */
    public boolean hasEnergy() {
        return yieldKwh != null || exportKwh != null || importKwh != null;
    }
}
