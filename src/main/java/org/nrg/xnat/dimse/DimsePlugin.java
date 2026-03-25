package org.nrg.xnat.dimse;

import org.nrg.framework.annotations.XnatPlugin;
import org.springframework.context.annotation.ComponentScan;

/**
 * XNAT plugin providing DICOM DIMSE SCP services (C-FIND, C-GET).
 */
@XnatPlugin(
    value = "dimse",
    name = "DIMSE Plugin",
    description = "DICOM C-FIND and C-GET SCP for querying and retrieving data from XNAT",
    logConfigurationFile = "META-INF/resources/dimse-logback.xml"
)
@ComponentScan({"org.nrg.xnat.dimse"})
public class DimsePlugin {
}
