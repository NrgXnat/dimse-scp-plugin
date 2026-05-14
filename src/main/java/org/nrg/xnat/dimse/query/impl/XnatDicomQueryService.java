package org.nrg.xnat.dimse.query.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.nrg.xdat.bean.CatDcmentryBean;
import org.nrg.xdat.model.CatEntryMetafieldI;
import org.nrg.xdat.model.XnatAbstractresourceI;
import org.nrg.xdat.model.XnatImagescandataI;
import org.nrg.xdat.om.XnatImagesessiondata;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.om.XnatSubjectdata;
import org.nrg.xnat.dimse.CatalogEntryUtils;
import org.nrg.xft.XFTTable;
import org.nrg.xft.search.CriteriaCollection;
import org.nrg.xft.search.QueryOrganizer;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.dimse.query.DicomMatchUtils;
import org.nrg.xnat.dimse.query.DicomQueryService;
import org.springframework.stereotype.Service;

import javax.annotation.Nullable;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * XNAT-backed implementation of {@link DicomQueryService}.
 *
 * <p>Translates DICOM C-FIND query keys into XFT queries against
 * XNAT's imaging session data model. Indexed attributes are pushed
 * down to SQL; non-indexed attributes are post-filtered in Java.</p>
 */
@Slf4j
@Service
public class XnatDicomQueryService implements DicomQueryService {
    private static final String ROOT_ELEMENT = "xnat:imageSessionData";

    // XFT field paths
    private static final String FLD_ID         = ROOT_ELEMENT + "/ID";
    private static final String FLD_UID        = ROOT_ELEMENT + "/UID";
    private static final String FLD_DATE       = ROOT_ELEMENT + "/date";
    private static final String FLD_TIME       = ROOT_ELEMENT + "/time";
    private static final String FLD_LABEL      = ROOT_ELEMENT + "/label";
    private static final String FLD_PROJECT    = ROOT_ELEMENT + "/project";
    private static final String FLD_NOTE       = ROOT_ELEMENT + "/note";
    private static final String FLD_SUBJECT_ID = ROOT_ELEMENT + "/subject_ID";
    private static final String FLD_SUBJ_LABEL = "xnat:subjectData/label";
    private static final String FLD_SHARE_PROJ = ROOT_ELEMENT + "/sharing/share/project";

    private static final SimpleDateFormat DICOM_DATE_FORMAT = new SimpleDateFormat("yyyyMMdd");
    private static final SimpleDateFormat DICOM_TIME_FORMAT = new SimpleDateFormat("HHmmss");


    /**
     * Column indices for the study search query results, resolved once per query.
     */
    private static final class StudyColumns {
        final int uid, experimentLabel, subjectLabel, date, time, exptId,
                scanCount, fileCount, modalities, gender, dob;

        StudyColumns(XFTTable table) {
            uid = table.getColumnIndex("uid");
            experimentLabel = table.getColumnIndex("experiment_label");
            subjectLabel = table.getColumnIndex("subject_label");
            date = table.getColumnIndex("date");
            time = table.getColumnIndex("time");
            exptId = table.getColumnIndex("exptid");
            scanCount = table.getColumnIndex("scan_count");
            fileCount = table.getColumnIndex("scan_files_secondary_dicom");
            modalities = table.getColumnIndex("modalities");
            gender = table.getColumnIndex("subject_gender");
            dob = table.getColumnIndex("subject_dob");
        }
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
            final QueryOrganizer qo = buildQuery(projectId, keys, user);
            final String sql = qo.buildFullQuery();
            log.trace("Executing study query: {}", sql);

            final XFTTable table = XFTTable.Execute(sql, user.getDBName(), user.getUsername());
            if (table == null || table.getNumRows() == 0) {
                log.debug("No sessions found in project '{}'", projectId);
                return Stream.empty();
            }

            log.debug("Study query matched {} rows in project '{}'", table.getNumRows(), projectId);

            // Post-filter on non-indexed keys (e.g., ModalitiesInStudy)
            return mapResults(table, qo, keys, user)
                    .filter(DicomMatchUtils.matchesQuery(keys));

        } catch (final Exception e) {
            log.error("Error querying studies in project '{}'", projectId, e);
            return Stream.empty();
        }
    }

    /**
     * Builds an XFT query for imaging sessions in a project,
     * pushing indexed DICOM query keys into SQL WHERE clauses.
     */
    private QueryOrganizer buildQuery(final String projectId,
                                      final Attributes keys,
                                      final UserI user) throws Exception {
        final QueryOrganizer qo = QueryOrganizer.buildXFTQueryOrganizerWithClause(ROOT_ELEMENT, user);

        qo.addField(FLD_ID);
        qo.addField(FLD_UID);
        qo.addField(FLD_DATE);
        qo.addField(FLD_TIME);
        qo.addField(FLD_LABEL);
        qo.addField(FLD_PROJECT);
        qo.addField(FLD_NOTE);
        qo.addField(FLD_SUBJECT_ID);
        qo.addField(FLD_SUBJ_LABEL);

        // Project filter (includes shared sessions)
        final CriteriaCollection projectCc = new CriteriaCollection("OR");
        projectCc.addClause(FLD_PROJECT, projectId);
        projectCc.addClause(FLD_SHARE_PROJ, projectId);
        qo.addWhere(projectCc);

        // Push indexed keys into SQL
        addStudyInstanceUidClause(qo, keys);
        addStudyDateClause(qo, keys);
        addAccessionNumberClause(qo, keys);
        addPatientIdClause(qo, keys);
        addPatientNameClause(qo, keys);

        return qo;
    }

    /**
     * Adds a Study Instance UID filter. Supports single UID and
     * backslash-separated UID lists (PS3.4 C.2.2.2).
     */
    private void addStudyInstanceUidClause(final QueryOrganizer qo,
                                           final Attributes keys) {
        final String uid = keys.getString(Tag.StudyInstanceUID);
        if (StringUtils.isEmpty(uid)) {
            return;
        }
        if (uid.indexOf('\\') < 0) {
            // Single UID — exact match
            final CriteriaCollection cc = new CriteriaCollection("AND");
            cc.addClause(FLD_UID, uid);
            qo.addWhere(cc);
        } else {
            // UID list — OR of exact matches
            final CriteriaCollection cc = new CriteriaCollection("OR");
            for (final String singleUid : uid.split("\\\\")) {
                cc.addClause(FLD_UID, singleUid.trim());
            }
            qo.addWhere(cc);
        }
    }

    /**
     * Adds a Study Date filter. Supports exact date, date range
     * (YYYYMMDD-YYYYMMDD), and open-ended ranges.
     */
    private void addStudyDateClause(final QueryOrganizer qo,
                                    final Attributes keys) {
        final String dateValue = keys.getString(Tag.StudyDate);
        if (StringUtils.isEmpty(dateValue)) {
            return;
        }
        final CriteriaCollection cc = new CriteriaCollection("AND");
        if (DicomMatchUtils.isDateRange(dateValue)) {
            final int dashIndex = dateValue.indexOf('-');
            final String start = dateValue.substring(0, dashIndex);
            final String end = dateValue.substring(dashIndex + 1);
            if (!start.isEmpty()) {
                cc.addClause(FLD_DATE, ">=", DicomMatchUtils.dicomDateToIso(start));
            }
            if (!end.isEmpty()) {
                cc.addClause(FLD_DATE, "<=", DicomMatchUtils.dicomDateToIso(end));
            }
        } else {
            cc.addClause(FLD_DATE, DicomMatchUtils.dicomDateToIso(dateValue));
        }
        qo.addWhere(cc);
    }

    /**
     * Adds an Accession Number filter (mapped to XNAT session ID).
     * Supports wildcards.
     */
    private void addAccessionNumberClause(final QueryOrganizer qo,
                                          final Attributes keys) {
        addStringClause(qo, keys, Tag.AccessionNumber, FLD_ID);
    }

    /**
     * Adds a Patient ID filter (mapped to XNAT subject label).
     * Supports wildcards.
     */
    private void addPatientIdClause(final QueryOrganizer qo,
                                    final Attributes keys) {
        addStringClause(qo, keys, Tag.PatientID, FLD_SUBJ_LABEL);
    }

    /**
     * Adds a Patient Name filter (mapped to XNAT session label).
     * Supports wildcards.
     */
    private void addPatientNameClause(final QueryOrganizer qo,
                                      final Attributes keys) {
        addStringClause(qo, keys, Tag.PatientName, FLD_LABEL);
    }

    /**
     * Adds a string-valued filter clause, using LIKE for wildcard
     * patterns and exact match otherwise.
     */
    private void addStringClause(final QueryOrganizer qo,
                                 final Attributes keys,
                                 final int tag,
                                 final String xftField) {
        final String value = keys.getString(tag);
        if (StringUtils.isEmpty(value)) {
            return;
        }
        final CriteriaCollection cc = new CriteriaCollection("AND");
        if (DicomMatchUtils.containsWildcard(value)) {
            cc.addClause(xftField, "LIKE", DicomMatchUtils.toSqlLike(value));
        } else {
            cc.addClause(xftField, value);
        }
        qo.addWhere(cc);
    }

    /**
     * Returns a lazy stream that converts XFTTable rows to DICOM
     * Attributes on demand. Each element is augmented with
     * scan-derived and subject-derived fields and populated with
     * all requested return keys.
     */
    private Stream<Attributes> mapResults(final XFTTable table,
                                          final QueryOrganizer qo,
                                          final Attributes keys,
                                          final UserI user) throws Exception {
        final Integer idCol   = columnIndex(table, qo, FLD_ID);
        final Integer uidCol  = columnIndex(table, qo, FLD_UID);
        final Integer dateCol = columnIndex(table, qo, FLD_DATE);
        final Integer timeCol = columnIndex(table, qo, FLD_TIME);
        final Integer lblCol  = columnIndex(table, qo, FLD_LABEL);
        final Integer noteCol = columnIndex(table, qo, FLD_NOTE);
        final Integer subjCol = columnIndex(table, qo, FLD_SUBJ_LABEL);

        table.resetRowCursor();
        return table.rows().stream()
                .map(row -> rowToAttributes(row, keys, user,
                        idCol, uidCol, dateCol, timeCol, lblCol, noteCol, subjCol));
    }

    /**
     * Converts a single XFTTable row to a DICOM Attributes object,
     * augmented with scan-derived and subject-derived fields.
     */
    private Attributes rowToAttributes(final Object[] row,
                                       final Attributes keys,
                                       final UserI user,
                                       final Integer idCol,
                                       final Integer uidCol,
                                       final Integer dateCol,
                                       final Integer timeCol,
                                       final Integer lblCol,
                                       final Integer noteCol,
                                       final Integer subjCol) {
        final String sessionId    = str(row, idCol);
        final String studyUid     = str(row, uidCol);
        final String dateStr      = formatDate(row, dateCol);
        final String timeStr      = formatTime(row, timeCol);
        final String sessionLabel = str(row, lblCol);
        final String note         = str(row, noteCol);
        final String subjectLabel = str(row, subjCol);

        final Attributes attrs = new Attributes();
        attrs.setString(Tag.QueryRetrieveLevel, VR.CS, "STUDY");
        attrs.setString(Tag.StudyInstanceUID, VR.UI, nvl(studyUid));
        attrs.setString(Tag.StudyDate, VR.DA, nvl(dateStr));
        attrs.setString(Tag.StudyTime, VR.TM, nvl(timeStr));
        attrs.setString(Tag.AccessionNumber, VR.SH, nvl(sessionId));
        attrs.setString(Tag.StudyID, VR.SH, nvl(sessionId));
        attrs.setString(Tag.PatientName, VR.PN, nvl(sessionLabel));
        attrs.setString(Tag.PatientID, VR.LO, nvl(subjectLabel));
        attrs.setString(Tag.StudyDescription, VR.LO, nvl(note));

        augmentFromSession(attrs, sessionId, user);
        copyReturnKeys(keys, attrs);

        return attrs;
    }

    /**
     * Extract the modality from the provided scan and convert to DICOM notation.
     * @param scan scan object
     * @return corrected DICOM modality, or null if scan modality is null or empty
     */
    @Nullable
    private String dicomModality(final XnatImagescandataI scan) {
        final String m = scan.getModality();
        if (null == m || m.isEmpty()) {
            return null;
        } else if ("PET".equals(m)) {
            return "PT";
        } else if (m.length() > 16) {
            return m.substring(0, 16);
        } else {
            return m;
        }

    }
    /**
     * Loads the full session object to derive scan-level and
     * subject-level attributes.
     */
    private void augmentFromSession(final Attributes attrs,
                                    final String sessionId,
                                    final UserI user) {
        if (sessionId == null) {
            return;
        }
        try {
            final XnatImagesessiondata session =
                    XnatImagesessiondata.getXnatImagesessiondatasById(sessionId, user, false);
            if (session == null) {
                return;
            }

            final List<? extends XnatImagescandataI> scans = session.getScans_scan();

            // Modalities in Study
            final Set<String> modalities = scans.stream()
                    .map(this::dicomModality)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            if (!modalities.isEmpty()) {
                attrs.setString(Tag.ModalitiesInStudy, VR.CS, modalities.toArray(new String[0]));
            }

            // Number of Study Related Series
            attrs.setInt(Tag.NumberOfStudyRelatedSeries, VR.IS, scans.size());

            // Number of Study Related Instances
            final int instanceCount = scans.stream()
                    .flatMap(scan -> scan.getFile().stream())
                    .filter(resource -> !"SNAPSHOTS".equals(resource.getLabel()))
                    .map(XnatAbstractresourceI::getFileCount)
                    .filter(Objects::nonNull)
                    .reduce(0, Integer::sum);
            attrs.setInt(Tag.NumberOfStudyRelatedInstances, VR.IS, instanceCount);

            // Subject demographics
            final XnatSubjectdata subject = session.getSubjectData();
            if (subject != null) {
                genderToDicomSex(subject.getGender())
                        .ifPresent(sex -> attrs.setString(Tag.PatientSex, VR.CS, sex));
                final Date dob = subject.getDOB();
                if (dob != null) {
                    synchronized (DICOM_DATE_FORMAT) {
                        attrs.setString(Tag.PatientBirthDate, VR.DA,
                                DICOM_DATE_FORMAT.format(dob));
                    }
                }
            }
        } catch (final Exception e) {
            log.warn("Failed to augment session '{}' with scan data", sessionId, e);
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

        final List<XnatSubjectdata> subjects =
                XnatSubjectdata.getXnatSubjectdatasByField(
                        "xnat:subjectData/project", projectId, user, false);

        if (subjects == null || subjects.isEmpty()) {
            log.debug("No subjects found in project '{}'", projectId);
            return Stream.empty();
        }

        log.debug("Found {} subjects in project '{}'", subjects.size(), projectId);

        return subjects.stream()
                .map(subject -> subjectToAttributes(subject, keys))
                .filter(DicomMatchUtils.matchesQuery(keys));
    }

    /**
     * Converts an XNAT subject to patient-level DICOM Attributes.
     */
    private Attributes subjectToAttributes(final XnatSubjectdata subject,
                                           final Attributes keys) {
        final Attributes attrs = new Attributes();
        attrs.setString(Tag.QueryRetrieveLevel, VR.CS, "PATIENT");

        final String label = subject.getLabel();
        attrs.setString(Tag.PatientID, VR.LO, nvl(label));
        attrs.setString(Tag.PatientName, VR.PN, nvl(label));

        genderToDicomSex(subject.getGender())
                .ifPresent(sex -> attrs.setString(Tag.PatientSex, VR.CS, sex));

        final Date dob = subject.getDOB();
        if (dob != null) {
            synchronized (DICOM_DATE_FORMAT) {
                attrs.setString(Tag.PatientBirthDate, VR.DA,
                        DICOM_DATE_FORMAT.format(dob));
            }
        }

        augmentPatientCounts(attrs, subject);
        copyReturnKeys(keys, attrs);
        return attrs;
    }

    /**
     * Adds Number of Patient Related Studies/Series/Instances to the
     * attributes by counting the subject's imaging sessions and scans.
     */
    private void augmentPatientCounts(final Attributes attrs,
                                      final XnatSubjectdata subject) {
        try {
            final List<XnatImagesessiondata> sessions =
                    subject.getExperiments_experiment().stream()
                            .filter(XnatImagesessiondata.class::isInstance)
                            .map(XnatImagesessiondata.class::cast)
                            .collect(Collectors.toList());

            attrs.setInt(Tag.NumberOfPatientRelatedStudies, VR.IS, sessions.size());

            int seriesCount = 0;
            int instanceCount = 0;
            for (final XnatImagesessiondata session : sessions) {
                final List<? extends XnatImagescandataI> scans = session.getScans_scan();
                seriesCount += scans.size();
                instanceCount += scans.stream()
                        .flatMap(scan -> scan.getFile().stream())
                        .filter(resource -> !"SNAPSHOTS".equals(resource.getLabel()))
                        .map(XnatAbstractresourceI::getFileCount)
                        .filter(Objects::nonNull)
                        .reduce(0, Integer::sum);
            }
            attrs.setInt(Tag.NumberOfPatientRelatedSeries, VR.IS, seriesCount);
            attrs.setInt(Tag.NumberOfPatientRelatedInstances, VR.IS, instanceCount);
        } catch (final Exception e) {
            log.warn("Failed to compute patient counts for subject '{}'",
                    subject.getLabel(), e);
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

        // Find sessions matching the Study Instance UID in this project
        final List<XnatImagesessiondata> sessions =
                XnatImagesessiondata.getXnatImagesessiondatasByField(
                        "xnat:imageSessionData/UID", studyUid, user, false);

        return sessions.stream()
                .filter(s -> projectId.equals(s.getProject()))
                .flatMap(session -> scanStream(session, studyUid, keys))
                .filter(DicomMatchUtils.matchesQuery(keys));
    }

    /**
     * Converts the scans of a session into a stream of series-level
     * DICOM Attributes.
     */
    private Stream<Attributes> scanStream(final XnatImagesessiondata session,
                                          final String studyUid,
                                          final Attributes keys) {
        final List<? extends XnatImagescandataI> scans = session.getScans_scan();
        return scans.stream().map(scan -> scanToAttributes(scan, studyUid, keys));
    }

    /**
     * Converts a single XNAT scan to series-level DICOM Attributes.
     */
    private Attributes scanToAttributes(final XnatImagescandataI scan,
                                        final String studyUid,
                                        final Attributes keys) {
        final Attributes attrs = new Attributes();
        attrs.setString(Tag.QueryRetrieveLevel, VR.CS, "SERIES");
        attrs.setString(Tag.StudyInstanceUID, VR.UI, studyUid);
        attrs.setString(Tag.SeriesInstanceUID, VR.UI, nvl(scan.getUid()));
        attrs.setString(Tag.Modality, VR.CS, nvl(dicomModality(scan)));
        attrs.setString(Tag.SeriesNumber, VR.IS, nvl(scan.getId()));
        attrs.setString(Tag.SeriesDescription, VR.LO, nvl(scan.getSeriesDescription()));
        attrs.setInt(Tag.NumberOfSeriesRelatedInstances, VR.IS, scanFileCount(scan));

        copyReturnKeys(keys, attrs);
        return attrs;
    }

    /**
     * Counts DICOM files for a scan, excluding SNAPSHOTS resources.
     */
    private static int scanFileCount(final XnatImagescandataI scan) {
        return scan.getFile().stream()
                .filter(resource -> !"SNAPSHOTS".equals(resource.getLabel()))
                .map(XnatAbstractresourceI::getFileCount)
                .filter(Objects::nonNull)
                .reduce(0, Integer::sum);
    }

    /**
     * {@inheritDoc}
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
        if (StringUtils.isEmpty(seriesUid )) {
            log.warn("IMAGE query missing Series Instance UID for project '{}'", projectId);
            return Stream.empty();
        }

        log.debug("Querying instances in project '{}', study '{}', series '{}'",
                projectId, studyUid, seriesUid);

        final List<XnatImagesessiondata> sessions =
                XnatImagesessiondata.getXnatImagesessiondatasByField(
                        "xnat:imageSessionData/UID", studyUid, user, false);

        return sessions.stream()
                .filter(s -> projectId.equals(s.getProject()))
                .flatMap(session -> session.getScans_scan().stream())
                .filter(scan -> seriesUid.equals(scan.getUid()))
                .flatMap(scan -> CatalogEntryUtils.scanEntries(scan, projectId))
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
        copyReturnKeys(keys, attrs);
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

    /**
     * Ensures all tags present in the query keys are also present
     * in the result Attributes (as empty strings if not already set).
     * This satisfies PS3.4 C.2.2.1 return key requirements.
     */
    private void copyReturnKeys(final Attributes keys, final Attributes attrs) {
        for (final int tag : keys.tags()) {
            if (!attrs.contains(tag)) {
                final VR vr = keys.getVR(tag);
                attrs.setNull(tag, vr != null ? vr : VR.LO);
            }
        }
    }

    /**
     * Gets the column index for an XFT field in the result table.
     */
    private Integer columnIndex(final XFTTable table,
                                final QueryOrganizer qo,
                                final String xftField) throws Exception {
        final String alias = qo.getFieldAlias(xftField);
        return alias != null ? table.getColumnIndex(alias.toLowerCase()) : null;
    }

    /**
     * Extracts a string from a result row column.
     */
    private static String str(final Object[] row, final Integer col) {
        if (col == null || col >= row.length) {
            return null;
        }
        final Object val = row[col];
        return val != null ? val.toString() : null;
    }

    /**
     * Formats a date column value as a DICOM DA string (YYYYMMDD).
     */
    private static String formatDate(final Object[] row, final Integer col) {
        if (col == null || col >= row.length || row[col] == null) {
            return null;
        }
        final Object val = row[col];
        if (val instanceof Date) {
            synchronized (DICOM_DATE_FORMAT) {
                return DICOM_DATE_FORMAT.format((Date) val);
            }
        }
        // Try parsing if it's an ISO date string
        final String s = val.toString();
        return s.replace("-", "");
    }

    /**
     * Formats a time column value as a DICOM TM string (HHMMSS).
     */
    private static String formatTime(final Object[] row, final Integer col) {
        if (col == null || col >= row.length || row[col] == null) {
            return null;
        }
        final Object val = row[col];
        if (val instanceof Date) {
            synchronized (DICOM_TIME_FORMAT) {
                return DICOM_TIME_FORMAT.format((Date) val);
            }
        }
        // Strip colons if present
        return val.toString().replace(":", "");
    }

    /**
     * Converts an XNAT gender string to a DICOM Patient Sex value.
     *
     * @param gender the XNAT gender (e.g. "male", "m", "female", "f")
     * @return "M", "F", or "O"; empty if gender is null/empty
     */
    private static Optional<String> genderToDicomSex(final String gender) {
        if (StringUtils.isBlank(gender)) {
            return Optional.empty();
        }
        if ("m".equalsIgnoreCase(gender) || "male".equalsIgnoreCase(gender)) {
            return Optional.of("M");
        } else if ("f".equalsIgnoreCase(gender) || "female".equalsIgnoreCase(gender)) {
            return Optional.of("F");
        } else {
            return Optional.of("O");
        }
    }

    /**
     * Returns the value or empty string if null.
     */
    private static String nvl(final String value) {
        return value != null ? value : "";
    }
}
