package me.firestone82.solaxautomation.integration.solaxstatistics;

import java.time.LocalDate;

/**
 * One row of a SolaxStatistics summary: a day, a month or a year.
 * <p>
 * Energies are kWh, money is CZK. "Grid" is what passed the distributor's meter - the metered
 * supply - and "self" what went to and came from the second supply. Every value is as
 * SolaxStatistics computed it; nothing here re-prices anything, so the numbers match the
 * workbook the other application writes.
 *
 * @param date              the day, or the first day of the month or year the row covers
 * @param yield             what the panels produced
 * @param consumption       what the house used
 * @param importGrid        bought through the meter
 * @param importSelf        taken from the second supply
 * @param importCostGrid    what the metered import cost
 * @param importCostSelf    what the import from the second supply cost
 * @param exportGrid        sold through the meter
 * @param exportSelf        given to the second supply
 * @param exportRevenueGrid what the metered export earned
 * @param exportRevenueSelf what the export to the second supply was worth
 * @param selfConsumption   production the house used itself
 * @param savings           what that self-consumption saved at the tariff
 * @param selfUseRate       share of the production used at home, %
 * @param profit            savings plus revenue minus cost
 */
public record BillingRow(
        LocalDate date,
        Double yield,
        Double consumption,
        Double importGrid,
        Double importSelf,
        Double importCostGrid,
        Double importCostSelf,
        Double exportGrid,
        Double exportSelf,
        Double exportRevenueGrid,
        Double exportRevenueSelf,
        Double selfConsumption,
        Double savings,
        Double selfUseRate,
        Double profit
) {

    /** Savings plus every revenue minus every cost, for a row the source gave no profit for. */
    static Double profitOf(Double savings, Double revenueGrid, Double revenueSelf, Double costGrid, Double costSelf) {
        if (savings == null && revenueGrid == null && revenueSelf == null && costGrid == null && costSelf == null) {
            return null;
        }

        return zero(savings) + zero(revenueGrid) + zero(revenueSelf) - zero(costGrid) - zero(costSelf);
    }

    private static double zero(Double value) {
        return value == null ? 0 : value;
    }
}
