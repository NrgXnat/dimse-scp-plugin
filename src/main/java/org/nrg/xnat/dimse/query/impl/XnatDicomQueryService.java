package org.nrg.xnat.dimse.query.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.nrg.xdat.bean.CatDcmentryBean;
import org.nrg.xdat.model.CatEntryMetafieldI;
import org.nrg.xdat.om.XnatImagesessiondata;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xnat.dimse.CatalogEntryUtils;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.dimse.query.DicomMatchUtils;
import org.nrg.xnat.dimse.query.DicomQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Stream;

/**
 * XNAT-backed implementation of {@link DicomQueryService}.
 *
 * <p>Translates DICOM C-FIND query keys into SQL queries via
 * {@link DimseDataAccess}. Indexed attributes are pushed down
 * to SQL; non-indexed attributes are post-filtered in Java
 * using {@link DicomMatchUtils}.</p>
 */
@Slf4j
@Service
public class XnatDicomQueryService implements DicomQueryService {

    private final DimseDataAccess _dataAccess;

    /**
     * Creates the query service.
     *
     * @param dataAccess the SQL-based data access layer
     */
    @Autowired
    public XnatDicomQueryService(final DimseDataAccess dataAccess) {
        _dataAccess = dataAccess;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Stream<Attributes> findStudies(final XnatProjectdata project,
                                          final Attributes keys,
                                          final UserI user) {
        final String projectId = project.getId();
        log.debug("Querying studies in project '{}' with keys: {}", projectId, keys);

        try {
            return _dataAccess.searchStudies(projectId, keys)
                    .filter(DicomMatchUtils.matchesQuery(keys));
        } catch (final Exception e) {
            log.error("Error querying studies in project '{}'", projectId, e);
            return Stream.empty();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Stream<Attributes> findSeries(final XnatProjectdata project,
                                         final Attributes keys,
                                         final UserI user) {
        final String projectId = project.getId();
        final String studyUid = keys.getString(Tag.StudyInstanceUID);
        if (StringUtils.isBlank(studyUid)) {
            log.warn("SERIES query missing Study Instance UID for project '{}'", projectId);
            return Stream.empty();
        }

        log.debug("Querying series in project '{}', study UID '{}'", projectId, studyUid);

        try {
            return _dataAccess.searchSeries(projectId, studyUid, keys)
                    .filter(DicomMatchUtils.matchesQuery(keys));
        } catch (final Exception e) {
            log.error("Error querying series in project '{}'", projectId, e);
            return Stream.empty();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Stream<Attributes> findPatients(final XnatProjectdata project,
                                           final Attributes keys,
                                           final UserI user) {
        final String projectId = project.getId();
        log.debug("Querying patients in project '{}'", projectId);

        try {
            return _dataAccess.searchPatients(projectId, keys)
                    .filter(DicomMatchUtils.matchesQuery(keys));
        } catch (final Exception e) {
            log.error("Error querying patients in project '{}'", projectId, e);
            return Stream.empty();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Instance-level queries use the archive catalog for metadata
     * (SOP Instance UID, SOP Class UID, Instance Number) since these
     * are not stored in database tables. The session ORM is loaded to
     * access catalog entries via {@link CatalogEntryUtils}.</p>
     */
    @Override
    public Stream<Attributes> findInstances(final XnatProjectdata project,
                                            final Attributes keys,
                                            final UserI user) {
        final String projectId = project.getId();
        final String studyUid = keys.getString(Tag.StudyInstanceUID);
        final String seriesUid = keys.getString(Tag.SeriesInstanceUID);
        if (StringUtils.isEmpty(studyUid)) {
            log.warn("IMAGE query missing Study Instance UID for project '{}'", projectId);
            return Stream.empty();
        }
        if (StringUtils.isEmpty(seriesUid)) {
            log.warn("IMAGE query missing Series Instance UID for project '{}'", projectId);
            return Stream.empty();
        }

        log.debug("Querying instances in project '{}', study '{}', series '{}'",
                projectId, studyUid, seriesUid);

        final List<XnatImagesessiondata> sessions =
                XnatImagesessiondata.getXnatImagesessiondatasByField(
                        "xnat:imageSessionData/UID", studyUid, user, false);

        // Owned-or-shared scope, matching the STUDY and SERIES SQL. Catalogs
        // resolve against the owning project, where the files live.
        return sessions.stream()
                .filter(session -> session.hasProject(projectId))
                .flatMap(session -> session.getScans_scan().stream()
                        .filter(scan -> seriesUid.equals(scan.getUid()))
                        .flatMap(scan -> CatalogEntryUtils.scanEntries(scan, session.getProject())))
                .map(entry -> catalogEntryToAttributes(entry, studyUid, seriesUid, keys))
                .filter(DicomMatchUtils.matchesQuery(keys));
    }

    /**
     * Converts a DICOM catalog entry to instance-level Attributes.
     */
    private Attributes catalogEntryToAttributes(final CatDcmentryBean entry,
                                                final String studyUid,
                                                final String seriesUid,
                                                final Attributes keys) {
        final Attributes attrs = new Attributes();
        attrs.setString(Tag.QueryRetrieveLevel, VR.CS, "IMAGE");
        attrs.setString(Tag.StudyInstanceUID, VR.UI, studyUid);
        attrs.setString(Tag.SeriesInstanceUID, VR.UI, seriesUid);
        attrs.setString(Tag.SOPInstanceUID, VR.UI, nvl(entry.getUid()));
        attrs.setString(Tag.SOPClassUID, VR.UI, sopClassUid(entry));
        final Integer instanceNumber = entry.getInstancenumber();
        if (instanceNumber != null) {
            attrs.setInt(Tag.InstanceNumber, VR.IS, instanceNumber);
        } else {
            attrs.setString(Tag.InstanceNumber, VR.IS, "");
        }
        DimseDataAccess.copyReturnKeys(keys, attrs);
        return attrs;
    }

    /**
     * Extracts the SOP Class UID from a catalog entry's metafields.
     */
    private static String sopClassUid(final CatDcmentryBean entry) {
        return entry.getMetafields_metafield().stream()
                .filter(mf -> "SOP_Class_UID".equals(mf.getName()))
                .map(CatEntryMetafieldI::getMetafield)
                .findFirst()
                .orElse("");
    }

    private static String nvl(final String value) {
        return value != null ? value : "";
    }
}
