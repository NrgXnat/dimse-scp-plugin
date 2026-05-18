package org.nrg.xnat.dimse.xapi;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.nrg.framework.annotations.XapiRestController;
import org.nrg.xapi.exceptions.DataFormatException;
import org.nrg.xapi.exceptions.InsufficientPrivilegesException;
import org.nrg.xapi.exceptions.NotFoundException;
import org.nrg.xapi.rest.AbstractXapiRestController;
import org.nrg.xapi.rest.XapiRequestMapping;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.dimse.config.DimsePreferences;
import org.nrg.xnat.dimse.scp.DimseScpService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;

import java.util.HashMap;
import java.util.Map;

import static org.nrg.xdat.security.helpers.AccessLevel.Admin;
import static org.nrg.xdat.security.helpers.AccessLevel.Authenticated;

/**
 * REST API for DIMSE SCP preferences.
 */
@Api("DICOM C-FIND/C-GET SCP Preferences API")
@XapiRestController
@Slf4j
public class DimsePrefsApi extends AbstractXapiRestController {

    private final DimsePreferences _preferences;
    private final DimseScpService _scpService;

    /**
     * Creates the DIMSE preferences REST controller.
     *
     * @param userManagementService user management service
     * @param roleHolder            role holder
     * @param preferences           DIMSE preference bean
     * @param scpService            SCP lifecycle service
     */
    @Autowired
    public DimsePrefsApi(final UserManagementServiceI userManagementService,
                         final RoleHolder roleHolder,
                         final DimsePreferences preferences,
                         final DimseScpService scpService) {
        super(userManagementService, roleHolder);
        _preferences = preferences;
        _scpService = scpService;
    }

    /**
     * Returns the current DIMSE SCP preferences.
     *
     * @return map of preference names to values
     */
    @ApiOperation(value = "Get DIMSE SCP preferences", response = Map.class)
    @XapiRequestMapping(value = "/dicom-qr-scp/prefs", method = RequestMethod.GET,
                         produces = MediaType.APPLICATION_JSON_VALUE,
                         restrictTo = Admin)
    public Map<String, Object> getPreferences() {
        final Map<String, Object> prefs = new HashMap<>();
        prefs.put("enabled", _preferences.isEnabled());
        prefs.put("port", _preferences.getPort());
        prefs.put("enabledByDefault", _preferences.isEnabledByDefault());
        return prefs;
    }

    /**
     * Updates DIMSE SCP preferences.
     *
     * <p>If the {@code enabled} preference changes, the SCP is started
     * or stopped accordingly.</p>
     *
     * @param preferences map of preference names to new values
     */
    @ApiOperation(value = "Update DIMSE SCP preferences")
    @XapiRequestMapping(value = "/dicom-qr-scp/prefs", method = RequestMethod.POST,
                         consumes = MediaType.APPLICATION_JSON_VALUE,
                         restrictTo = Admin)
    public void updatePreferences(@RequestBody final Map<String, Object> preferences)
            throws DataFormatException {
        if (preferences.containsKey("enabledByDefault")) {
            final boolean value = toBool(preferences.get("enabledByDefault"));
            _preferences.setEnabledByDefault(value);
            log.info("Updated enabledByDefault to {}", value);
        }

        if (preferences.containsKey("port")) {
            final int value = toInt(preferences.get("port"));
            _preferences.setPort(value);
            log.info("Updated port to {} (takes effect on next SCP restart)", value);
        }

        if (preferences.containsKey("enabled")) {
            final boolean value = toBool(preferences.get("enabled"));
            _preferences.setEnabled(value);
            log.info("Updated enabled to {}", value);
            applyEnabledState(value);
        }
    }

    /**
     * Returns the DIMSE preferences for a specific project.
     *
     * <p>The {@code enabled} field is a three-valued string:
     * {@code "default"} (follow sitewide setting), {@code "true"},
     * or {@code "false"}.</p>
     *
     * @param projectId the XNAT project ID
     * @return map containing the enabled state
     * @throws NotFoundException              if the project does not exist
     * @throws InsufficientPrivilegesException if the user is not an admin or project owner
     */
    @ApiOperation(value = "Get project DIMSE preferences", response = Map.class)
    @XapiRequestMapping(value = "/dicom-qr-scp/{projectId}",
                         method = RequestMethod.GET,
                         produces = MediaType.APPLICATION_JSON_VALUE,
                         restrictTo = Authenticated)
    public Map<String, Object> getProjectPreferences(@PathVariable final String projectId)
            throws NotFoundException, InsufficientPrivilegesException {
        requireProjectOwner(projectId);
        final Map<String, Object> prefs = new HashMap<>();
        if (_preferences.hasProjectOverride(projectId)) {
            prefs.put("enabled", String.valueOf(_preferences.isProjectEnabled(projectId)));
        } else {
            prefs.put("enabled", "default");
        }
        return prefs;
    }

    /**
     * Updates the DIMSE preference for a specific project.
     *
     * <p>Admins can modify any project; otherwise only project owners
     * can change the setting. The {@code enabled} field accepts
     * {@code "true"}, {@code "false"}, or {@code "default"} (removes
     * the project override so the sitewide default applies).</p>
     *
     * @param projectId   the XNAT project ID
     * @param preferences map containing the {@code enabled} value
     * @throws NotFoundException              if the project does not exist
     * @throws InsufficientPrivilegesException if the user is not an admin
     *                                         or project owner
     */
    @ApiOperation(value = "Update project DIMSE preferences")
    @XapiRequestMapping(value = "/dicom-qr-scp/{projectId}",
                         method = RequestMethod.POST,
                         consumes = MediaType.APPLICATION_JSON_VALUE,
                         restrictTo = Authenticated)
    public void updateProjectPreferences(
            @PathVariable final String projectId,
            @RequestBody final Map<String, Object> preferences)
            throws NotFoundException, InsufficientPrivilegesException {
        requireProjectOwner(projectId);
        if (preferences.containsKey("enabled")) {
            final String value = String.valueOf(preferences.get("enabled"));
            if ("default".equalsIgnoreCase(value) || "null".equals(value)) {
                _preferences.deleteProjectEnabled(projectId);
                log.info("Removed DIMSE override for project {}", projectId);
            } else {
                final boolean enabled = Boolean.parseBoolean(value);
                _preferences.setProjectEnabled(projectId, enabled);
                log.info("Set DIMSE enabled={} for project {}", enabled, projectId);
            }
        }
    }

    /**
     * Verifies the project exists and the caller is an admin or
     * project owner.
     *
     * @param projectId the XNAT project ID
     * @throws NotFoundException              if the project does not exist
     * @throws InsufficientPrivilegesException if the user lacks access
     */
    private void requireProjectOwner(final String projectId)
            throws NotFoundException, InsufficientPrivilegesException {
        final UserI user = getSessionUser();
        final XnatProjectdata project = XnatProjectdata.getProjectByIDorAlias(projectId, user, false);
        if (project == null) {
            throw new NotFoundException(XnatProjectdata.SCHEMA_ELEMENT_NAME, projectId);
        }
        if (!getRoleHolder().isSiteAdmin(user) && !Permissions.isProjectOwner(user, project.getId())) {
            throw new InsufficientPrivilegesException(user.getUsername(), project.getId());
        }
    }

    /**
     * Starts or stops the SCP to match the enabled preference.
     *
     * @param enabled true to start, false to stop
     */
    private void applyEnabledState(final boolean enabled) {
        try {
            if (enabled && !_scpService.isRunning()) {
                _scpService.start(_preferences.getPort());
            } else if (!enabled && _scpService.isRunning()) {
                _scpService.stop();
            }
        } catch (Exception e) {
            log.error("Failed to {} DIMSE SCP", enabled ? "start" : "stop", e);
        }
    }

    private static boolean toBool(final Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private static int toInt(final Object value) throws DataFormatException {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            throw new DataFormatException("Invalid port value: " + value, e);
        }
    }
}
