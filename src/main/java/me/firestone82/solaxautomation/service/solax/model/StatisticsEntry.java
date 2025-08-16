package me.firestone82.solaxautomation.service.solax.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Data
@ToString
@AllArgsConstructor
@NoArgsConstructor
public class StatisticsEntry implements Cloneable {
    private LocalDateTime dateTime;
    private double yieldMWh;
    private double exportMWh;
    private double consumptionMWh;
    private double importMWh;

    public void subtract(StatisticsEntry other) {
        this.yieldMWh -= other.yieldMWh;
        this.exportMWh -= other.exportMWh;
        this.consumptionMWh -= other.consumptionMWh;
        this.importMWh -= other.importMWh;
    }

    public static Map<LocalDateTime, StatisticsEntry> aggregateHourly(List<StatisticsEntry> data) {
        return data.stream().collect(Collectors.groupingBy(
                e -> e.getDateTime().minusMinutes(5).withMinute(0).withSecond(0).withNano(0),
                Collectors.collectingAndThen(Collectors.toList(), list -> {
                    LocalDateTime date = list.getFirst().getDateTime().withMinute(0).withSecond(0).withNano(0);
                    double sumYield = list.stream().mapToDouble(StatisticsEntry::getYieldMWh).sum();
                    double sumExported = list.stream().mapToDouble(StatisticsEntry::getExportMWh).sum();
                    double sumConsumption = list.stream().mapToDouble(StatisticsEntry::getConsumptionMWh).sum();
                    double sumImported = list.stream().mapToDouble(StatisticsEntry::getImportMWh).sum();
                    return new StatisticsEntry(date, sumYield, sumExported, sumConsumption, sumImported);
                })
        ));
    }

    @Override
    public StatisticsEntry clone() {
        try {
            return (StatisticsEntry) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }
    }
}
