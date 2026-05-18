package org.nrg.xnat.dimse;

import lombok.extern.slf4j.Slf4j;
import org.nrg.framework.annotations.XnatPlugin;
import org.nrg.xnat.dimse.config.DimsePreferences;
import org.nrg.xnat.dimse.scp.DimseScpService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.FileCopyUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.security.GeneralSecurityException;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * XNAT plugin providing DICOM DIMSE SCP services (C-FIND, C-GET).
 */
@Slf4j
@XnatPlugin(
    value = "dimse",
    name = "DIMSE Plugin",
    description = "DICOM C-FIND and C-GET SCP for querying and retrieving data from XNAT",
    logConfigurationFile = "META-INF/resources/dimse-logback.xml"
)
@ComponentScan({"org.nrg.xnat.dimse"})
public class DimsePlugin {
    /**
     * Starts the DIMSE SCP on plugin initialization, if enabled.
     *
     * @param scpService  SCP lifecycle service
     * @param preferences DIMSE preference bean
     * @throws GeneralSecurityException if TLS setup fails
     * @throws IOException              if the port cannot be bound
     */
    @Autowired
    public DimsePlugin(final DimseScpService scpService,
                       final DimsePreferences preferences)
            throws GeneralSecurityException, IOException {
        if (preferences.isEnabled()) {
            scpService.start(preferences.getPort());
        } else {
            log.info("DIMSE SCP is disabled; not starting");
        }
    }

    public final static ResourceLoader RESOURCE_LOADER = new DefaultResourceLoader();

    public static String readResource(final Resource resource) {
        try (Reader reader = new InputStreamReader(resource.getInputStream(), UTF_8)) {
            return FileCopyUtils.copyToString(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String readResource(final String path) {
        return readResource(RESOURCE_LOADER.getResource(path));
    }
}
