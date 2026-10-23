package org.nrg.xnat.dimse.query.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.service.DicomServiceException;
import org.nrg.xdat.bean.CatDcmentryBean;
import org.nrg.xdat.model.CatEntryMetafieldI;
import org.nrg.xdat.om.XnatImagesessiondata;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xnat.dimse.CatalogEntryUtils;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.dimse.query.DicomMatchUtils;
import org.nrg.xnat.dimse.query.DicomQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
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
                                          final UserI user)
            throws DicomServiceException {
        final String projectId = project.getId();
        log.debug("Querying studies in project '{}' with keys: {}", projectId, keys);

        return query("studies", projectId, () -> _dataAccess.searchStudies(projectId, keys))
                .filter(DicomMatchUtils.matchesQuery(keys));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Stream<Attributes> findSeries(final XnatProjectdata project,
                                         final Attributes keys,
                                         final UserI user)
            throws DicomServiceException {
        final String projectId = project.getId();
        final String studyUid = keys.getString(Tag.StudyInstanceUID);
        if (StringUtils.isBlank(studyUid)) {
            log.warn("SERIES query missing Study Instance UID for project '{}'", projectId);
            return Stream.empty();
        }

        log.debug("Querying series in project '{}', study UID '{}'", projectId, studyUid);

        final Optional<UnaryOperator<Attributes>> patient = patientAbove(projectId, studyUid, keys);
        if (patient.isEmpty()) {
            return Stream.empty();
        }
        return query("series", projectId, () -> _dataAccess.searchSeries(projectId, studyUid, keys))
                .map(patient.get())
                .filter(DicomMatchUtils.matchesQuery(keys));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Stream<Attributes> findPatients(final XnatProjectdata project,
                                           final Attributes keys,
                                           final UserI user)
            throws DicomServiceException {
        final String projectId = project.getId();
        log.debug("Querying patients in project '{}'", projectId);

        return query("patients", projectId, () -> _dataAccess.searchPatients(projectId, keys))
                .filter(DicomMatchUtils.matchesQuery(keys));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Instance-level queries use the archive catalog for metadata
     * (SOP Instance UID, SOP Class UID, Instance Number) since these
     * are not stored in database tables. The session ORM is loaded to
     * access catalog entries via {@link CatalogEntryUtils}.</p>
     *
     * <p>Exception to the interface's failure contract: the session
     * lookup goes through XFT, which logs a failed query and returns no
     * sessions, so such a failure reads as no matches.</p>
     */
    @Override
    public Stream<Attributes> findInstances(final XnatProjectdata project,
                                            final Attributes keys,
                                            final UserI user)
            throws DicomServiceException {
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

        final Optional<UnaryOperator<Attributes>> patient = patientAbove(projectId, studyUid, keys);
        if (patient.isEmpty()) {
            return Stream.empty();
        }
        final List<XnatImagesessiondata> sessions =
                XnatImagesessiondata.getXnatImagesessiondatasByField(
                        "xnat:imageSessionData/UID", studyUid, user, false);

        // XFT logs and swallows a failed lookup, returning no sessions, so a
        // database error here cannot be reported as a failure.
        // Owned-or-shared scope, matching the STUDY and SERIES SQL. Catalogs
        // resolve against the owning project, where the files live.
        return sessions.stream()
                .filter(session -> session.hasProject(projectId))
                .flatMap(session -> session.getScans_scan().stream()
                        .filter(scan -> seriesUid.equals(scan.getUid()))
                        .flatMap(scan -> CatalogEntryUtils.scanEntries(scan, session.getProject())))
                .map(entry -> catalogEntryToAttributes(entry, studyUid, seriesUid, keys))
                .map(patient.get())
                .filter(DicomMatchUtils.matchesQuery(keys));
    }

    /**
     * Applies the Patient ID above a SERIES or IMAGE level query. In the
     * Patient Root model the Identifier holds the Patient ID of the
     * study's patient (PS3.4 C.4.1.2.1), and the hierarchical search finds
     * matches only under a patient matching it (C.4.1.3.1.1); each match
     * returns it as a Unique Key of a higher level. Series and instance
     * records have no Patient ID, so the study is looked up with this key.
     *
     * @param projectId the project searched
     * @param studyUid  the Study Instance UID from the Identifier
     * @param keys      the Identifier, holding only supported keys
     * @return a function setting the study's Patient ID on a match, or
     *         the identity if the Identifier has no Patient ID (as in the
     *         Study Root model); empty if the study is not under a patient
     *         matching it, so there are no matches
     * @throws DicomServiceException with status C000H if the study query
     *         fails
     */
    private Optional<UnaryOperator<Attributes>> patientAbove(final String projectId,
                                                             final String studyUid,
                                                             final Attributes keys)
            throws DicomServiceException {
        if (!keys.contains(Tag.PatientID)) {
            return Optional.of(UnaryOperator.identity());
        }
        final Attributes studyKeys = new Attributes(2);
        studyKeys.setString(Tag.StudyInstanceUID, VR.UI, studyUid);
        studyKeys.addSelected(keys, Tag.PatientID);
        return query("studies", projectId, () -> _dataAccess.searchStudies(projectId, studyKeys))
                .filter(DicomMatchUtils.matchesQuery(studyKeys))
                .map(study -> study.getString(Tag.PatientID, ""))
                .findFirst()
                .map(XnatDicomQueryService::withPatientId);
    }

    private static UnaryOperator<Attributes> withPatientId(final String patientId) {
        return match -> {
            match.setString(Tag.PatientID, VR.LO, patientId);
            return match;
        };
    }

    /**
     * Runs a database query, reporting a failure as C000H Unable to
     * Process rather than as no matches. The cause is logged, not put in
     * the Error Comment, which the SCU can read.
     *
     * @param what      what is being queried, for the log
     * @param projectId the project searched, for the log
     * @param query     the query; the SQL runs when this is called
     * @return the query's rows
     * @throws DicomServiceException with status C000H if the query fails
     */
    private static Stream<Attributes> query(final String what,
                                            final String projectId,
                                            final Supplier<Stream<Attributes>> query)
            throws DicomServiceException {
        try {
            return query.get();
        } catch (final DataAccessException e) {
            log.error("Error querying {} in project '{}'", what, projectId, e);
            final DicomServiceException failure =
                    new DicomServiceException(Status.UnableToProcess, "Query failed");
            failure.initCause(e);
            throw failure;
        }
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
