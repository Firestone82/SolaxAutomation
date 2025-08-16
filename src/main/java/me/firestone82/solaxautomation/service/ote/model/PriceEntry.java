package me.firestone82.solaxautomation.service.ote.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

@Getter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class PriceEntry {
    private LocalDateTime dateTime;
    private double czkPriceMWh;
    private double eurPriceMWh;
}
