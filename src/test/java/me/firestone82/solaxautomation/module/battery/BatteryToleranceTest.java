package me.firestone82.solaxautomation.module.battery;

import me.firestone82.solaxautomation.core.timeline.TimelineProperties;
import me.firestone82.solaxautomation.core.timeline.TimelineService;
import me.firestone82.solaxautomation.integration.meteosource.MeteoSourceService;
import me.firestone82.solaxautomation.integration.solax.InverterGateway;
import me.firestone82.solaxautomation.integration.solax.model.InverterMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * A checkpoint is an expectation, not a contract.
 * <p>
 * A battery one or two percent short of its target is on schedule for every practical
 * purpose, and switching the inverter over that difference only makes the work mode flap
 * around the checkpoint hour - so {@code automation.battery.tolerance} decides how much of a
 * shortfall still counts as having reached the checkpoint.
 */
class BatteryToleranceTest {

    private static final int SELF_USE_HOUR = 13;
    private static final int FEED_IN_HOUR = 9;

    private BatteryProperties properties;
    private InverterGateway inverter;
    private BatteryModule module;

    @BeforeEach
    void setUp(@TempDir Path directory) {
        properties = new BatteryProperties();
        properties.setEnabled(true);
        properties.setSelfUseThresholds(Map.of(SELF_USE_HOUR, 80));
        properties.setFeedInThresholds(Map.of(FEED_IN_HOUR, 80));
        properties.setTolerance(2);
        // The sky is the subject of BatterySunnyFeedInTest; here only the tolerance decides.
        properties.getFeedInWeather().setEnabled(false);

        inverter = mock(InverterGateway.class);
        when(inverter.setWorkMode(any())).thenReturn(true);

        TimelineProperties timelineProperties = new TimelineProperties();
        timelineProperties.setPersist(false);
        timelineProperties.setFile(directory.resolve("timeline.json"));

        module = new BatteryModule(properties, inverter, mock(MeteoSourceService.class),
                new TimelineService(timelineProperties));
    }

    private void inverterAt(int soc, InverterMode mode) {
        when(inverter.getBatterySoc()).thenReturn(Optional.of(soc));
        when(inverter.getWorkMode()).thenReturn(Optional.of(mode));
    }

    /** Runs the checkpoint for one hour and returns the translation key of what it decided. */
    private String summaryKeyAt(int hour) {
        module.runCheckpoint(hour);
        return module.getStatus().summaryKey();
    }

    @Test
    @DisplayName("a battery inside the tolerance stays in feed-in priority")
    void shortfallInsideTheToleranceKeepsFeedInPriority() {
        inverterAt(78, InverterMode.FEED_IN_PRIORITY);

        assertEquals("outcome.battery.withinTolerance", summaryKeyAt(SELF_USE_HOUR));
        verify(inverter, never()).setWorkMode(any());
    }

    @Test
    @DisplayName("a battery past the tolerance drops back to self use")
    void shortfallPastTheToleranceSwitchesToSelfUse() {
        inverterAt(77, InverterMode.FEED_IN_PRIORITY);

        assertEquals("outcome.battery.switchedSelfUse", summaryKeyAt(SELF_USE_HOUR));
        verify(inverter).setWorkMode(InverterMode.SELF_USE);
    }

    @Test
    @DisplayName("a battery on its target is on schedule, tolerance or not")
    void reachingTheTargetIsOnSchedule() {
        inverterAt(80, InverterMode.FEED_IN_PRIORITY);

        assertEquals("outcome.battery.onSchedule", summaryKeyAt(SELF_USE_HOUR));
        verify(inverter, never()).setWorkMode(any());
    }

    @Test
    @DisplayName("the feed-in checkpoint counts as reached inside the tolerance too")
    void feedInCheckpointIsReachedInsideTheTolerance() {
        inverterAt(78, InverterMode.SELF_USE);

        assertEquals("outcome.battery.switchedFeedIn", summaryKeyAt(FEED_IN_HOUR));
        verify(inverter).setWorkMode(InverterMode.FEED_IN_PRIORITY);
    }

    @Test
    @DisplayName("past the tolerance the feed-in checkpoint is not reached")
    void feedInCheckpointStaysUnreachedPastTheTolerance() {
        inverterAt(77, InverterMode.SELF_USE);

        assertEquals("outcome.battery.stayingSelfUse", summaryKeyAt(FEED_IN_HOUR));
        verify(inverter, never()).setWorkMode(any());
    }

    @Test
    @DisplayName("with no tolerance a single percent short is behind schedule")
    void zeroToleranceKeepsTheOldBehaviour() {
        properties.setTolerance(0);
        inverterAt(79, InverterMode.FEED_IN_PRIORITY);

        assertEquals("outcome.battery.switchedSelfUse", summaryKeyAt(SELF_USE_HOUR));
        verify(inverter).setWorkMode(InverterMode.SELF_USE);
    }

    @Test
    @DisplayName("the weekend bonus is applied before the tolerance")
    void weekendBonusRaisesTheTargetTheToleranceIsMeasuredAgainst() {
        properties.setWeekendIncrease(10);
        inverterAt(88, InverterMode.FEED_IN_PRIORITY);

        // Weekday: the 80 % target is long met. Weekend: 90 % is expected, and 88 % is
        // within the 2 % tolerance either way - so the outcome only differs by which
        // checkpoint it was measured against, never by a work mode change.
        String key = summaryKeyAt(SELF_USE_HOUR);

        assertEquals(true, key.equals("outcome.battery.onSchedule") || key.equals("outcome.battery.withinTolerance"));
        verify(inverter, never()).setWorkMode(any());
    }
}
