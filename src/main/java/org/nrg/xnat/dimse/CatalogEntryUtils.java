package org.nrg.xnat.dimse;

import org.nrg.action.ServerException;
import org.nrg.xdat.bean.CatDcmentryBean;
import org.nrg.xdat.model.XnatImagescandataI;
import org.nrg.xdat.om.XnatAbstractresource;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xnat.utils.CatalogUtils;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.stream.Stream;

/**
 * Utilities for accessing DICOM catalog entries from XNAT scans.
 */
public class CatalogEntryUtils {

    private CatalogEntryUtils() {}

    /**
     * Tests whether a resource appears to contain DICOM data,
     * based on its label, format, and content descriptors.
     *
     * @param resource the resource to test
     * @return true if any descriptor suggests DICOM content
     */
    public static boolean isDicomResource(final XnatAbstractresource resource) {
        return matchesDicomDescriptor(resource.getLabel())
                || matchesDicomDescriptor(resource.getFormat())
                || matchesDicomDescriptor(resource.getContent());
    }

    /**
     * Streams DICOM resource catalogs for a scan.
     *
     * @param scan scan that may contain catalog resources
     * @return DICOM resource catalogs for the scan
     */
    public static Stream<XnatResourcecatalog> dicomCatalogs(final XnatImagescandataI scan) {
        return scan.getFile().stream()
                .filter(Objects::nonNull)
                .filter(XnatAbstractresource.class::isInstance)
                .map(XnatAbstractresource.class::cast)
                .filter(CatalogEntryUtils::isDicomResource)
                .filter(XnatResourcecatalog.class::isInstance)
                .map(XnatResourcecatalog.class::cast);
    }

    /**
     * Streams DICOM catalog entries for a scan.
     *
     * @param scan scan that may contain catalog resources
     * @param projectId name of project containing scan
     * @return DICOM catalog entries in the scan
     */
    public static Stream<CatDcmentryBean> scanEntries(final XnatImagescandataI scan,
                                                      final String projectId) {
        return dicomCatalogs(scan)
                .flatMap(r -> catalogEntries(r, projectId));
    }

    /**
     * Loads a resource catalog and streams its DICOM entries.
     */
    private static Stream<CatDcmentryBean> catalogEntries(final XnatResourcecatalog resource,
                                                          final String projectId) {
        try {
            return CatalogUtils.CatalogData.get(resource, projectId)
                    .map(cd -> cd.catBean.getEntries_entry().stream()
                            .filter(CatDcmentryBean.class::isInstance)
                            .map(CatDcmentryBean.class::cast))
                    .orElse(Stream.empty());
        } catch (final ServerException e) {
            LoggerFactory.getLogger(CatalogEntryUtils.class)
                    .warn("Failed to read catalog for resource '{}'", resource.getLabel(), e);
            return Stream.empty();
        }
    }

    private static boolean matchesDicomDescriptor(final String value) {
        if (value == null) {
            return false;
        }
        final String normalized = value.trim().toLowerCase();
        return normalized.contains("dicom") || normalized.contains("secondary");
    }
}
