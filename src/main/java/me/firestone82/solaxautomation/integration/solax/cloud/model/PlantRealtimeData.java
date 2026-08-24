package me.firestone82.solaxautomation.integration.solax.cloud.model;

import lombok.Data;

/**
 * Payload of {@code GET /openapi/v2/plant/realtime_data}. All energies are kWh.
 */
@Data
public class PlantRealtimeData {

    private String plantId;
    private String plantLocalTime;

    private Double dailyYield;
    private Double totalYield;
    private Double dailyCharged;
    private Double totalCharged;
    private Double dailyDischarged;
    private Double totalDischarged;
    private Double dailyImported;
    private Double totalImported;
    private Double dailyExported;
    private Double totalExported;
    private Double dailyEarnings;
    private Double totalEarnings;
}
