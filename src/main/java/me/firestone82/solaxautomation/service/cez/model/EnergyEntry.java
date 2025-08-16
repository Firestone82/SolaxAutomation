package me.firestone82.solaxautomation.service.cez.model;

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
@NoArgsConstructor
@AllArgsConstructor
public class EnergyEntry {
    private LocalDateTime dateTime;
    private double importMWh;
    private double exportMWh;

    public static Map<LocalDateTime, EnergyEntry> aggregateHourly(List<EnergyEntry> data) {
        return data.stream().collect(Collectors.groupingBy(
                e -> e.getDateTime().minusMinutes(15).withMinute(0).withSecond(0).withNano(0),
                Collectors.collectingAndThen(Collectors.toList(), list -> {
                    LocalDateTime date = list.getFirst().getDateTime().withMinute(0).withSecond(0).withNano(0);
                    double sumImport = list.stream().mapToDouble(EnergyEntry::getImportMWh).sum() / 4.0;
                    double sumExport = list.stream().mapToDouble(EnergyEntry::getExportMWh).sum() / 4.0;
                    return new EnergyEntry(date, sumImport, sumExport);
                })
        ));
    }
}
