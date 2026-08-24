package me.firestone82.solaxautomation.core.module;

/**
 * Contract every module's {@code @ConfigurationProperties} class implements.
 * <p>
 * Only the on/off switch is shared - everything else is module specific and lives in
 * the module's own configuration section.
 */
public interface ModuleProperties {

    /** Whether the module is allowed to run at all. */
    boolean isEnabled();

    void setEnabled(boolean enabled);
}
