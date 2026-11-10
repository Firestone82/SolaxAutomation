package me.firestone82.solaxautomation.module.morning;

import lombok.Data;
import me.firestone82.solaxautomation.core.module.ModuleProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.LocalTime;

/**
 * Configuration of the morning buy-and-sell module.
 *
 * @see MorningModule
 */
@Data
@Validated
@ConfigurationProperties(prefix = "automation.morning")
public class MorningProperties implements ModuleProperties {

    /**
     * Buys from the night tariff and sells in the morning. Off by default: it draws from the
     * grid, so it should only run once the prices below are set for the installation.
     */
    private boolean enabled = false;

    private Charge charge = new Charge();

    private BadWeather badWeather = new BadWeather();

    private Sell sell = new Sell();

    /** The night charge, from the cheap tariff of the second supply. */
    @Data
    public static class Charge {

        /** When the charge starts, and when tonight's decision is made. */
        private LocalTime from = LocalTime.of(2, 0);

        /**
         * When the charge ends, whatever the battery reached. The remote control session is
         * sent for exactly this long, so it ends on its own even if this application does not.
         */
        private LocalTime to = LocalTime.of(6, 0);

        /** State of charge the charge stops at, %. */
        private int targetBattery = 100;

        /** Charge power, W. */
        private int power = 3000;

        /**
         * Charges only while the house is on the second supply, where the night tariff
         * applies. The connection switch is read at the start and on every check after it,
         * and the charge stops if the house moves to the metered grid.
         */
        private boolean requireSecondSupply = true;
    }

    /** What makes a day bad enough to fill the battery for. */
    @Data
    public static class BadWeather {

        /** Off, the weather is never a reason to charge. */
        private boolean enabled = true;

        /**
         * Mean forecast quality over the day's productive hours at or above which the day
         * counts as bad. Quality is the weather type level plus cloud cover / 100: about 3
         * is mostly cloudy, 4 overcast, 4.5 rain.
         */
        private double minQuality = 3.0;

        /** First hour of the day the forecast is judged over. */
        private int fromHour = 8;

        /** Hour the judged part of the day ends, exclusive. */
        private int toHour = 16;
    }

    /** The morning sale. */
    @Data
    public static class Sell {

        /**
         * Off, the morning is never sold into and a good morning price is no longer a reason
         * to charge either.
         */
        private boolean enabled = true;

        /** Earliest the sale starts. */
        private LocalTime from = LocalTime.of(6, 15);

        /** Latest the sale runs to. */
        private LocalTime to = LocalTime.of(9, 0);

        /**
         * Spot price the morning has to reach, CZK/kWh. It decides both whether the night
         * charge is worth buying and which intervals are sold into, so it should clear what
         * the night tariff costs plus the round-trip losses.
         */
        private double minPrice = 5.0;

        /** Power of the sale, W. {@code 0} follows the selling module's discharge power. */
        private int power = 0;

        /** State of charge the sale stops at, %. */
        private int reserveBattery = 40;

        /**
         * Sells on a bad day at all. Off by default: the battery is only worth emptying in the
         * morning when the day is good enough for the panels to fill it again, and on a bad
         * day the charge from the night is what carries the house. A morning whose forecast is
         * not known is treated the same way, because nothing says the sun will refill it.
         */
        private boolean onBadDay = false;

        /**
         * State of charge the sale stops at on a bad day, %, when {@link #onBadDay} lets it
         * sell at all. The battery was filled to carry the house through a day without sun, so
         * only what is above this is sold.
         */
        private int badWeatherReserve = 80;

        /**
         * Sells only while the house is on the metered grid. On the second supply the export
         * never reaches the meter, so nobody pays for it.
         */
        private boolean requireMeteredGrid = true;
    }
}
