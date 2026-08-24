package me.firestone82.solaxautomation.integration.solax.cloud.model;

import me.firestone82.solaxautomation.integration.solax.model.InverterMode;

import java.util.Optional;

/**
 * Interpretation of the cloud {@code deviceStatus} code (API docs, Appendix 6).
 * <p>
 * The cloud has no endpoint that reads back the configured work mode, so the running
 * status is the closest thing available. It is good enough to display and to detect an
 * active remote control session, but Modbus stays authoritative for control decisions.
 */
public final class DeviceStatus {

    /** Inverter is being steered by a remote control (VPP) session. */
    public static final int VPP_MODE = 130;

    /** Remote control sub-modes, 1301 (power control) through 1309 (PV and battery target SOC). */
    public static final int REMOTE_CONTROL_FIRST = 1301;
    public static final int REMOTE_CONTROL_LAST = 1309;

    /**
     * "Normal Mode(R-1)" through "Normal Mode(R-7)", API docs Appendix 6.
     * <p>
     * Named rather than shown as a bare number because this is what the SolaX app shows an
     * inverter that has not left remote control. They are deliberately <i>not</i> treated as
     * an active session by {@link #isRemoteControlActive(Integer)}: the documentation names
     * them without saying what the R stands for, and only 130 and 1301-1309 are stated to be
     * remote control.
     */
    private static final int NORMAL_R_FIRST = 141;
    private static final int NORMAL_R_LAST = 147;

    private static final int SELF_USE = 150;
    private static final int FORCE_TIME_USE = 151;
    private static final int BACK_UP = 152;
    private static final int FEED_IN_PRIORITY = 153;

    private DeviceStatus() {
    }

    /** Maps a status code to the work mode it implies, when it implies one. */
    public static Optional<InverterMode> toWorkMode(Integer status) {
        if (status == null) {
            return Optional.empty();
        }

        return switch (status) {
            case SELF_USE -> Optional.of(InverterMode.SELF_USE);
            case BACK_UP -> Optional.of(InverterMode.BACKUP);
            case FEED_IN_PRIORITY -> Optional.of(InverterMode.FEED_IN_PRIORITY);
            case FORCE_TIME_USE -> Optional.of(InverterMode.MANUAL);
            default -> Optional.empty();
        };
    }

    /** True when the code says a remote control session is currently running. */
    public static boolean isRemoteControlActive(Integer status) {
        if (status == null) {
            return false;
        }

        return status == VPP_MODE || (status >= REMOTE_CONTROL_FIRST && status <= REMOTE_CONTROL_LAST);
    }

    /** Short label for logs and the dashboard. */
    public static String describe(Integer status) {
        if (status == null) {
            return "unknown";
        }

        return switch (status) {
            case 100 -> "Waiting";
            case 101 -> "Self-check";
            case 102 -> "Normal";
            case 103 -> "Fault";
            case 104 -> "Permanent fault";
            case 105 -> "Firmware update";
            case 106 -> "EPS check";
            case 107 -> "EPS (off-grid)";
            case 108 -> "Self test";
            case 109 -> "Idle";
            case 110 -> "Standby";
            case 111 -> "PV waking the battery";
            case 112 -> "Generator check";
            case 113 -> "Generator running";
            case 114 -> "Rapid shutdown standby";
            case VPP_MODE -> "Remote control";
            case 131 -> "Time of use: self use";
            case 132 -> "Time of use: charging";
            case 133 -> "Time of use: discharging";
            case 134 -> "Time of use: battery off";
            case 135 -> "Time of use: peak shaving";
            case 136 -> "Normal (generator)";
            case 137 -> "Normal (battery expansion)";
            case 138 -> "Normal (battery heating)";
            case 139 -> "EPS (battery heating)";
            case 140 -> "Starting";
            // 141-147 are the inverter's "Normal Mode(R-n)" states, named here exactly as
            // the API documentation and the SolaX app name them.
            case NORMAL_R_FIRST, 142, 143, 144, 145, 146, NORMAL_R_LAST ->
                    "Normal Mode(R-" + (status - NORMAL_R_FIRST + 1) + ")";
            case SELF_USE -> "Self use";
            case FORCE_TIME_USE -> "Force time use";
            case BACK_UP -> "Back up";
            case FEED_IN_PRIORITY -> "Feed-in priority";
            case 154 -> "Demand";
            case 155 -> "Constant power";
            case 160 -> "OpenADR";
            case 170 -> "Stopped";
            case 171 -> "Debug";
            case 174 -> "Normal (smart self use)";
            case 175 -> "Normal (smart feed-in)";
            case 176 -> "Normal (smart, battery not discharging)";
            case 177 -> "Normal (winter load 0 %)";
            case 1301 -> "Remote control: power target";
            case 1302 -> "Remote control: energy target";
            case 1303 -> "Remote control: SOC target";
            case 1304 -> "Remote control: push power";
            case 1305 -> "Remote control: push power zero";
            case 1306 -> "Remote control: self-consume";
            case 1307 -> "Remote control: charge only";
            case 1308 -> "Remote control: PV and battery duration";
            case 1309 -> "Remote control: PV and battery target SOC";
            default -> "status " + status;
        };
    }
}
