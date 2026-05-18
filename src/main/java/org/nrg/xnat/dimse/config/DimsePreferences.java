package org.nrg.xnat.dimse.config;

import lombok.extern.slf4j.Slf4j;
import org.nrg.framework.configuration.ConfigPaths;
import org.nrg.framework.constants.Scope;
import org.nrg.framework.utilities.OrderedProperties;
import org.nrg.prefs.annotations.NrgPreference;
import org.nrg.prefs.annotations.NrgPreferenceBean;
import org.nrg.prefs.beans.AbstractPreferenceBean;
import org.nrg.prefs.exceptions.InvalidPreferenceName;
import org.nrg.prefs.services.NrgPreferenceService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Preference bean for DIMSE SCP configuration.
 */
@Slf4j
@NrgPreferenceBean(
    toolId = "dimse",
    toolName = "DICOM C-FIND/C-GET SCP Configuration",
    description = "Configuration settings for the DIMSE C-FIND/C-GET SCP",
    properties = "config/dimse/dimse.properties"
)
public class DimsePreferences extends AbstractPreferenceBean {

    /**
     * Creates the DIMSE preference bean.
     *
     * @param preferenceService the XNAT preference service
     * @param configPaths       configuration paths
     * @param initPrefs         initial property values
     */
    @Autowired
    public DimsePreferences(final NrgPreferenceService preferenceService,
                            final ConfigPaths configPaths,
                            final OrderedProperties initPrefs) {
        super(preferenceService, configPaths, initPrefs);
    }

    /**
     * Returns whether the DIMSE SCP is enabled sitewide.
     *
     * @return true if the SCP should accept associations
     */
    @NrgPreference(defaultValue = "true")
    public boolean isEnabled() {
        return getBooleanValue("enabled");
    }

    /**
     * Sets whether the DIMSE SCP is enabled sitewide.
     *
     * @param enabled true to enable, false to disable
     */
    public void setEnabled(final boolean enabled) {
        try {
            setBooleanValue(enabled, "enabled");
        } catch (InvalidPreferenceName e) {
            log.error("Invalid preference name 'enabled'", e);
        }
    }

    /**
     * Returns the TCP port the SCP listens on.
     *
     * @return the port number
     */
    @NrgPreference(defaultValue = "9104")
    public int getPort() {
        return getIntegerValue("port");
    }

    /**
     * Sets the TCP port the SCP listens on.
     *
     * @param port the port number
     */
    public void setPort(final int port) {
        try {
            setIntegerValue(port, "port");
        } catch (InvalidPreferenceName e) {
            log.error("Invalid preference name 'port'", e);
        }
    }

    /**
     * Returns whether C-FIND/C-GET is enabled by default for new projects.
     *
     * @return true if projects have DIMSE access enabled unless overridden
     */
    @NrgPreference(defaultValue = "true")
    public boolean isEnabledByDefault() {
        return getBooleanValue("enabledByDefault");
    }

    /**
     * Sets whether C-FIND/C-GET is enabled by default for new projects.
     *
     * @param enabledByDefault true to enable by default
     */
    public void setEnabledByDefault(final boolean enabledByDefault) {
        try {
            setBooleanValue(enabledByDefault, "enabledByDefault");
        } catch (InvalidPreferenceName e) {
            log.error("Invalid preference name 'enabledByDefault'", e);
        }
    }

    /**
     * Returns whether C-FIND/C-GET is enabled for a specific project.
     *
     * <p>If a project-level override exists, it is returned; otherwise
     * the sitewide {@link #isEnabledByDefault()} value is used.</p>
     *
     * @param projectId the XNAT project ID
     * @return true if the project is accessible via DIMSE
     */
    public boolean isProjectEnabled(final String projectId) {
        final Boolean override = getBooleanValue(
                Scope.Project, projectId, "enabled");
        return override != null ? override : isEnabledByDefault();
    }

    /**
     * Sets whether C-FIND/C-GET is enabled for a specific project.
     *
     * @param projectId the XNAT project ID
     * @param enabled   true to enable, false to disable
     */
    public void setProjectEnabled(final String projectId,
                                  final boolean enabled) {
        try {
            setBooleanValue(Scope.Project, projectId, enabled, "enabled");
        } catch (InvalidPreferenceName e) {
            log.error("Invalid preference name 'enabled' for project {}",
                    projectId, e);
        }
    }

    /**
     * Removes the project-level override so the sitewide default applies.
     *
     * @param projectId the XNAT project ID
     */
    public void deleteProjectEnabled(final String projectId) {
        try {
            delete(Scope.Project, projectId, "enabled");
        } catch (InvalidPreferenceName e) {
            log.error("Failed to delete preference 'enabled' for project {}",
                    projectId, e);
        }
    }

    /**
     * Returns whether a project-level override exists.
     *
     * @param projectId the XNAT project ID
     * @return true if the project has an explicit enabled/disabled setting
     */
    public boolean hasProjectOverride(final String projectId) {
        return getBooleanValue(Scope.Project, projectId, "enabled") != null;
    }
}
