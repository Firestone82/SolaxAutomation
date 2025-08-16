package me.firestone82.solaxautomation.service.export;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
class SummaryRow {
    private LocalDateTime date;
    private double importCEZ;
    private double importRest;
    private double exportCEZ;
    private double exportRest;
    private double consumption;
    private double priceEUR;
    private double priceCZK;
    private double importCostEUR;
    private double importCostCZK;
    private double exportRevenueEUR;
    private double exportRevenueCZK;
}