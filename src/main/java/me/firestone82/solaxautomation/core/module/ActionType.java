package me.firestone82.solaxautomation.core.module;

/**
 * Kinds of action a module can plan or perform. The dashboard maps these to
 * translated labels and timeline colours, so keep the set small and stable.
 */
public enum ActionType {

    /** Persistent inverter work mode change (SELF_USE / FEED_IN_PRIORITY / BACKUP). */
    WORK_MODE_CHANGE,

    /** Battery discharged to the grid through remote control. */
    GRID_SELL,

    /** Battery charged from the grid through remote control. */
    GRID_CHARGE,

    /** Export power limit changed. */
    EXPORT_LIMIT,

    /** Remote control session ended, inverter handed back to its work mode. */
    REMOTE_CONTROL_EXIT,

    /** A scheduled evaluation that may or may not result in a change. */
    CHECK
}
