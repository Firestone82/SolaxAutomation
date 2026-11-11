package me.firestone82.solaxautomation.dashboard.api;

import lombok.RequiredArgsConstructor;
import me.firestone82.solaxautomation.dashboard.dto.DashboardDtos.BillingOverview;
import me.firestone82.solaxautomation.dashboard.dto.DashboardDtos.BillingView;
import me.firestone82.solaxautomation.integration.solaxstatistics.SolaxStatisticsProperties;
import me.firestone82.solaxautomation.integration.solaxstatistics.SolaxStatisticsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * The SolaxStatistics summaries, for the dashboard's billing page: what there is, a month by
 * day and a year by month. Read-only - this application never writes the other one's files.
 */
@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dashboard", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BillingController {

    private final SolaxStatisticsService statistics;
    private final SolaxStatisticsProperties properties;

    @GetMapping("/overview")
    public BillingOverview getOverview() {
        List<YearMonth> months = statistics.getMonths();

        return new BillingOverview(
                properties.isEnabled(),
                statistics.isAvailable(),
                statistics.getSummaryDir().toString(),
                months.stream().map(YearMonth::toString).toList(),
                statistics.getMonthsWithDays().stream().map(YearMonth::toString).toList(),
                months.stream().map(YearMonth::getYear).distinct().toList(),
                statistics.getUpdatedAt().orElse(null)
        );
    }

    /** A month by day, {@code YYYY-MM}. */
    @GetMapping("/month/{month}")
    public ResponseEntity<BillingView> getMonth(@PathVariable String month) {
        YearMonth parsed;

        try {
            parsed = YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok(new BillingView(
                "month", parsed.toString(),
                statistics.getDays(parsed),
                statistics.getMonth(parsed).orElse(null)
        ));
    }

    /** A year by month. */
    @GetMapping("/year/{year}")
    public ResponseEntity<BillingView> getYear(@PathVariable int year) {
        if (year < 2000 || year > 2200) {
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok(new BillingView(
                "year", String.valueOf(year),
                statistics.getMonthsOf(year),
                statistics.getYear(year).orElse(null)
        ));
    }
}
