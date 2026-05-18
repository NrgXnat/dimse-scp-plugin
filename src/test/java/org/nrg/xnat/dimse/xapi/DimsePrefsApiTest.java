package org.nrg.xnat.dimse.xapi;

import org.junit.Before;
import org.junit.Test;
import org.nrg.xnat.dimse.config.DimsePreferences;
import org.nrg.xnat.dimse.scp.DimseScpService;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DimsePrefsApi}.
 */
public class DimsePrefsApiTest {

    private DimsePreferences preferences;
    private DimseScpService scpService;
    private DimsePrefsApi api;

    @Before
    public void setUp() {
        preferences = mock(DimsePreferences.class);
        scpService = mock(DimseScpService.class);
        api = new DimsePrefsApi(null, null, preferences, scpService);
    }

    @Test
    public void getPreferencesReturnsAllValues() {
        when(preferences.isEnabled()).thenReturn(true);
        when(preferences.getPort()).thenReturn(9104);
        when(preferences.isEnabledByDefault()).thenReturn(false);

        final Map<String, Object> prefs = api.getPreferences();

        assertNotNull(prefs);
        assertEquals(true, prefs.get("enabled"));
        assertEquals(9104, prefs.get("port"));
        assertEquals(false, prefs.get("enabledByDefault"));
    }

    @Test
    public void updateEnabledStartsScp() throws Exception {
        when(scpService.isRunning()).thenReturn(false);
        when(preferences.getPort()).thenReturn(9104);

        api.updatePreferences(Collections.singletonMap("enabled", true));

        verify(preferences).setEnabled(true);
        verify(scpService).start(9104);
    }

    @Test
    public void updateEnabledFalseStopsScp() throws Exception {
        when(scpService.isRunning()).thenReturn(true);

        api.updatePreferences(Collections.singletonMap("enabled", false));

        verify(preferences).setEnabled(false);
        verify(scpService).stop();
    }

    @Test
    public void updateEnabledTrueWhenAlreadyRunningDoesNotRestart()
            throws Exception {
        when(scpService.isRunning()).thenReturn(true);

        api.updatePreferences(Collections.singletonMap("enabled", true));

        verify(scpService, never()).start(9104);
        verify(scpService, never()).stop();
    }

    @Test
    public void updateEnabledByDefault() throws Exception {
        api.updatePreferences(
                Collections.singletonMap("enabledByDefault", false));

        verify(preferences).setEnabledByDefault(false);
    }

    @Test
    public void updatePort() throws Exception {
        api.updatePreferences(Collections.singletonMap("port", 11112));

        verify(preferences).setPort(11112);
    }

    @Test
    public void updateMultiplePreferences() throws Exception {
        when(scpService.isRunning()).thenReturn(false);
        when(preferences.getPort()).thenReturn(11112);

        final Map<String, Object> prefs = new HashMap<>();
        prefs.put("port", 11112);
        prefs.put("enabledByDefault", false);
        prefs.put("enabled", true);

        api.updatePreferences(prefs);

        verify(preferences).setPort(11112);
        verify(preferences).setEnabledByDefault(false);
        verify(preferences).setEnabled(true);
        verify(scpService).start(11112);
    }
}
