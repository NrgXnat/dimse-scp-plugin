package org.nrg.xnat.dimse.query.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.nrg.xdat.XDAT;
import org.nrg.xnat.dimse.query.DicomMatchUtils;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Data access layer for DIMSE C-FIND queries, using explicit SQL
 * with {@link NamedParameterJdbcTemplate} instead of XFT ORM.
 */
@Slf4j
@Component
public class DimseDataAccess {
    private static final SimpleDateFormat DICOM_DATE_FORMAT = new SimpleDateFormat("yyyyMMdd");
    private static final SimpleDateFormat DICOM_TIME_FORMAT = new SimpleDateFormat("HHmmss");

    private final String studySql;
    private final String seriesSql;
    private final String patientSql;

    /**
     * Creates the data access layer, loading SQL templates from resources.
     */
    public DimseDataAccess() {
        studySql = loadSql("sql/search-studies.sql");
        seriesSql = loadSql("sql/search-series.sql");
        patientSql = loadSql("sql/search-patients.sql");
    }

    /**
     * Searches for studies matching the given DICOM query keys.
     *
     * @param projectId the XNAT project ID
     * @param keys      the DICOM C-FIND query keys
     * @return matching studies as DICOM Attributes
     */
    public Stream<Attributes> searchStudies(final String projectId,
                                            final Attributes keys) {
        final MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("projectId", projectId);

        final StringBuilder sql = new StringBuilder(studySql);
        addStudyFilters(sql, params, keys);
        sql.append(" ORDER BY e.date, e.time, e.id");

        log.trace("Study query SQL: {}", sql);

        return getTemplate().query(sql.toString(), params,
                (rs, rowNum) -> mapStudyRow(rs, projectId, keys)).stream();
    }

    /**
     * Maps a study-level result row to DICOM Attributes.
     *
     * <p>Study Description (0008,1030) is set to the XNAT project ID to
     * match the mapping used by the XNAT DICOMweb plugin; XNAT does not
     * model a directly queryable clinical study description.</p>
     *
     * @param rs        the result set positioned on the row to map
     * @param projectId the XNAT project ID (the Called AE Title scope)
     * @param keys      the C-FIND query keys, for return-key copying
     * @return the row as DICOM Attributes
     * @throws SQLException if a column cannot be read
     */
    static Attributes mapStudyRow(final ResultSet rs, final String projectId,
                                  final Attributes keys) throws SQLException {
        final Attributes attrs = new Attributes();
        attrs.setString(Tag.QueryRetrieveLevel, VR.CS, "STUDY");
        attrs.setString(Tag.StudyInstanceUID, VR.UI, nvl(rs.getString("uid")));
        attrs.setString(Tag.StudyDate, VR.DA, formatDate(rs.getDate("date")));
        attrs.setString(Tag.StudyTime, VR.TM, formatTime(rs.getTimestamp("time")));
        attrs.setString(Tag.AccessionNumber, VR.SH, nvl(rs.getString("exptid")));
        attrs.setString(Tag.StudyID, VR.SH, nvl(rs.getString("exptid")));
        attrs.setString(Tag.PatientName, VR.PN, nvl(rs.getString("experiment_label")));
        attrs.setString(Tag.PatientID, VR.LO, nvl(rs.getString("subject_label")));
        attrs.setString(Tag.StudyDescription, VR.LO, projectId);

        final String modalities = rs.getString("modalities");
        if (modalities != null && !modalities.isEmpty()) {
            attrs.setString(Tag.ModalitiesInStudy, VR.CS,
                    modalities.split("\\\\"));
        }
        attrs.setInt(Tag.NumberOfStudyRelatedSeries, VR.IS, rs.getInt("scan_count"));
        attrs.setInt(Tag.NumberOfStudyRelatedInstances, VR.IS, rs.getInt("file_count"));

        genderToDicomSex(rs.getString("subject_gender"))
                .ifPresent(sex -> attrs.setString(Tag.PatientSex, VR.CS, sex));
        final java.sql.Date dob = rs.getDate("subject_dob");
        if (dob != null) {
            attrs.setString(Tag.PatientBirthDate, VR.DA, formatDate(dob));
        }

        copyReturnKeys(keys, attrs);
        return attrs;
    }

    /**
     * Searches for series matching the given DICOM query keys.
     *
     * @param projectId the XNAT project ID
     * @param studyUid  the Study Instance UID
     * @param keys      the DICOM C-FIND query keys
     * @return matching series as DICOM Attributes
     */
    public Stream<Attributes> searchSeries(final String projectId,
                                           final String studyUid,
                                           final Attributes keys) {
        final MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("projectId", projectId);
        params.addValue("studyUid", studyUid);

        final StringBuilder sql = new StringBuilder(seriesSql);
        addSeriesFilters(sql, params, keys);

        log.trace("Series query SQL: {}", sql);

        return getTemplate().query(sql.toString(), params, (rs, rowNum) -> {
            final Attributes attrs = new Attributes();
            attrs.setString(Tag.QueryRetrieveLevel, VR.CS, "SERIES");
            attrs.setString(Tag.StudyInstanceUID, VR.UI, studyUid);
            attrs.setString(Tag.SeriesInstanceUID, VR.UI, nvl(rs.getString("series_uid")));
            attrs.setString(Tag.Modality, VR.CS, nvl(dicomModality(rs.getString("modality"))));
            attrs.setString(Tag.SeriesNumber, VR.IS, nvl(rs.getString("scan_id")));
            attrs.setString(Tag.SeriesDescription, VR.LO, nvl(rs.getString("series_description")));
            attrs.setInt(Tag.NumberOfSeriesRelatedInstances, VR.IS, rs.getInt("file_count"));

            copyReturnKeys(keys, attrs);
            return attrs;
        }).stream();
    }

    /**
     * Searches for patients (subjects) matching the given DICOM query keys.
     *
     * @param projectId the XNAT project ID
     * @param keys      the DICOM C-FIND query keys
     * @return matching patients as DICOM Attributes
     */
    public Stream<Attributes> searchPatients(final String projectId,
                                             final Attributes keys) {
        final MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("projectId", projectId);

        final StringBuilder sql = new StringBuilder(patientSql);
        addPatientFilters(sql, params, keys);

        log.trace("Patient query SQL: {}", sql);

        return getTemplate().query(sql.toString(), params, (rs, rowNum) -> {
            final Attributes attrs = new Attributes();
            attrs.setString(Tag.QueryRetrieveLevel, VR.CS, "PATIENT");
            final String label = nvl(rs.getString("subject_label"));
            attrs.setString(Tag.PatientID, VR.LO, label);
            attrs.setString(Tag.PatientName, VR.PN, label);

            genderToDicomSex(rs.getString("gender"))
                    .ifPresent(sex -> attrs.setString(Tag.PatientSex, VR.CS, sex));
            final java.sql.Date dob = rs.getDate("dob");
            if (dob != null) {
                attrs.setString(Tag.PatientBirthDate, VR.DA, formatDate(dob));
            }

            attrs.setInt(Tag.NumberOfPatientRelatedStudies, VR.IS, rs.getInt("study_count"));
            attrs.setInt(Tag.NumberOfPatientRelatedSeries, VR.IS, rs.getInt("series_count"));
            attrs.setInt(Tag.NumberOfPatientRelatedInstances, VR.IS, rs.getInt("instance_count"));

            copyReturnKeys(keys, attrs);
            return attrs;
        }).stream();
    }

    /**
     * Resolves scan metadata for IMAGE-level queries without loading
     * full ORM objects.
     *
     * @param projectId the XNAT project ID
     * @param studyUid  the Study Instance UID
     * @param seriesUid the Series Instance UID
     * @return scan references with primary keys and IDs
     */
    public List<ScanRef> findScans(final String projectId,
                                   final String studyUid,
                                   final String seriesUid) {
        final String sql =
                "SELECT sc.xnat_imagescandata_id, sc.id AS scan_id, "
                + "e.id AS experiment_id "
                + "FROM xnat_imagescandata sc "
                + "JOIN xnat_imagesessiondata i ON sc.image_session_id = i.id "
                + "JOIN xnat_experimentdata e ON e.id = i.id "
                + "WHERE i.uid = :studyUid AND sc.uid = :seriesUid "
                + "AND (e.project = :projectId "
                + "     OR EXISTS ("
                + "         SELECT 1 FROM xnat_experimentdata_share es "
                + "         WHERE es.sharing_share_xnat_experimentda_id = e.id "
                + "           AND es.project = :projectId))";

        final MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("projectId", projectId);
        params.addValue("studyUid", studyUid);
        params.addValue("seriesUid", seriesUid);

        return getTemplate().query(sql, params, (rs, rowNum) ->
                new ScanRef(rs.getInt("xnat_imagescandata_id"),
                            rs.getString("scan_id"),
                            rs.getString("experiment_id")));
    }

    // -- Study filters --

    private void addStudyFilters(final StringBuilder sql,
                                 final MapSqlParameterSource params,
                                 final Attributes keys) {
        addUidFilter(sql, params, keys, Tag.StudyInstanceUID, "i.uid");
        addDateFilter(sql, params, keys, Tag.StudyDate, "e.date");
        addStringFilter(sql, params, keys, Tag.AccessionNumber, "e.id", "accession");
        addStringFilter(sql, params, keys, Tag.PatientID, "psl.subject_label", "patientId");
        addStringFilter(sql, params, keys, Tag.PatientName, "pe.experiment_label", "patientName");
    }

    // -- Series filters --

    private void addSeriesFilters(final StringBuilder sql,
                                  final MapSqlParameterSource params,
                                  final Attributes keys) {
        addUidFilter(sql, params, keys, Tag.SeriesInstanceUID, "sc.uid");
        addStringFilter(sql, params,
                dicomToXnatModality(keys.getString(Tag.Modality)),
                "sc.modality", "modality");
        addStringFilter(sql, params, keys, Tag.SeriesNumber, "sc.id", "seriesNumber");
    }

    // -- Patient filters --

    private void addPatientFilters(final StringBuilder sql,
                                   final MapSqlParameterSource params,
                                   final Attributes keys) {
        addStringFilter(sql, params, keys, Tag.PatientID,
                "COALESCE(psl.subject_label, s.label)", "patientId");
        addStringFilter(sql, params, keys, Tag.PatientName,
                "COALESCE(psl.subject_label, s.label)", "patientName");
    }

    // -- Generic filter helpers --

    /**
     * Adds a UID-based filter. Supports single UID exact match and
     * backslash-separated UID lists (PS3.4 C.2.2.2).
     */
    void addUidFilter(final StringBuilder sql,
                              final MapSqlParameterSource params,
                              final Attributes keys,
                              final int tag,
                              final String column) {
        final String[] values = keys.getStrings(tag);
        if (values == null || values.length == 0
                || (values.length == 1 && StringUtils.isEmpty(values[0]))) {
            return;
        }
        if (values.length == 1) {
            sql.append(" AND ").append(column).append(" = :uid");
            params.addValue("uid", values[0]);
        } else {
            final List<String> uids = Arrays.asList(values);
            sql.append(" AND ").append(column).append(" IN (:uidList)");
            params.addValue("uidList", uids);
        }
    }

    /**
     * Adds a case-insensitive string filter for the given tag,
     * translating DICOM wildcards ({@code *}, {@code ?}) to a SQL
     * {@code ILIKE} pattern. A value with no wildcards becomes a
     * case-insensitive exact match.
     */
    void addStringFilter(final StringBuilder sql,
                                 final MapSqlParameterSource params,
                                 final Attributes keys,
                                 final int tag,
                                 final String column,
                                 final String paramName) {
        addStringFilter(sql, params, keys.getString(tag), column, paramName);
    }

    /**
     * Adds a case-insensitive string filter for an explicit value.
     * Used where the query value needs preprocessing before matching
     * (e.g. the {@code PT} to {@code PET} Modality reverse mapping in
     * {@link #addSeriesFilters}).
     */
    void addStringFilter(final StringBuilder sql,
                                 final MapSqlParameterSource params,
                                 final String value,
                                 final String column,
                                 final String paramName) {
        if (StringUtils.isEmpty(value)) {
            return;
        }
        sql.append(" AND ").append(column)
                .append(" ILIKE :").append(paramName);
        params.addValue(paramName, DicomMatchUtils.toSqlLike(value));
    }

    /**
     * Adds a date-valued filter. Supports exact date, date ranges
     * (YYYYMMDD-YYYYMMDD), and open-ended ranges.
     */
    void addDateFilter(final StringBuilder sql,
                               final MapSqlParameterSource params,
                               final Attributes keys,
                               final int tag,
                               final String column) {
        final String value = keys.getString(tag);
        if (StringUtils.isEmpty(value)) {
            return;
        }
        if (DicomMatchUtils.isDateRange(value)) {
            final int dash = value.indexOf('-');
            final String start = value.substring(0, dash);
            final String end = value.substring(dash + 1);
            if (!start.isEmpty()) {
                if (DicomMatchUtils.isValidDicomDate(start)) {
                    sql.append(" AND ").append(column)
                            .append(" >= CAST(:dateStart AS DATE)");
                    params.addValue("dateStart", DicomMatchUtils.dicomDateToIso(start));
                } else {
                    log.warn("Ignoring invalid lower date bound '{}' for tag {}",
                            start, Integer.toHexString(tag));
                }
            }
            if (!end.isEmpty()) {
                if (DicomMatchUtils.isValidDicomDate(end)) {
                    sql.append(" AND ").append(column)
                            .append(" <= CAST(:dateEnd AS DATE)");
                    params.addValue("dateEnd", DicomMatchUtils.dicomDateToIso(end));
                } else {
                    log.warn("Ignoring invalid upper date bound '{}' for tag {}",
                            end, Integer.toHexString(tag));
                }
            }
        } else if (DicomMatchUtils.isValidDicomDate(value)) {
            sql.append(" AND ").append(column)
                    .append(" = CAST(:dateExact AS DATE)");
            params.addValue("dateExact", DicomMatchUtils.dicomDateToIso(value));
        } else {
            log.warn("Ignoring invalid date '{}' for tag {}",
                    value, Integer.toHexString(tag));
        }
    }

    // -- Helpers --

    /**
     * Reference to a scan row for IMAGE-level queries.
     */
    public static final class ScanRef {
        private final int scanPk;
        private final String scanId;
        private final String experimentId;

        ScanRef(final int scanPk, final String scanId,
                final String experimentId) {
            this.scanPk = scanPk;
            this.scanId = scanId;
            this.experimentId = experimentId;
        }

        /** @return the xnat_imagescandata primary key */
        public int getScanPk() { return scanPk; }

        /** @return the scan ID (series number) */
        public String getScanId() { return scanId; }

        /** @return the experiment (session) ID */
        public String getExperimentId() { return experimentId; }
    }

    private NamedParameterJdbcTemplate getTemplate() {
        final DataSource ds = XDAT.getContextService().getBean(DataSource.class);
        return new NamedParameterJdbcTemplate(ds);
    }

    private static String formatDate(final java.util.Date date) {
        if (date == null) {
            return "";
        }
        synchronized (DICOM_DATE_FORMAT) {
            return DICOM_DATE_FORMAT.format(date);
        }
    }

    private static String formatTime(final java.util.Date time) {
        if (time == null) {
            return "";
        }
        synchronized (DICOM_TIME_FORMAT) {
            return DICOM_TIME_FORMAT.format(time);
        }
    }

    /**
     * Converts an XNAT gender string to a DICOM Patient Sex value.
     */
    static Optional<String> genderToDicomSex(final String gender) {
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
     * Converts modality, applying PET to PT correction per DICOM convention.
     */
    static String dicomModality(final String modality) {
        if (modality == null || modality.isEmpty()) {
            return null;
        } else if ("PET".equals(modality)) {
            return "PT";
        } else if (modality.length() > 16) {
            return modality.substring(0, 16);
        } else {
            return modality;
        }
    }

    /**
     * Reverses the {@link #dicomModality} mapping for query matching so
     * that a query using the DICOM code {@code PT} matches scans whose
     * XNAT modality is {@code PET}. Other values (including wildcards
     * and {@code null}) pass through unchanged.
     *
     * @param dicomModality the Modality value from the query
     * @return the value to match against the XNAT modality column
     */
    static String dicomToXnatModality(final String dicomModality) {
        return "PT".equalsIgnoreCase(dicomModality) ? "PET" : dicomModality;
    }

    /**
     * Ensures all tags present in the query keys are also present
     * in the result Attributes (as null values if not already set).
     */
    static void copyReturnKeys(final Attributes keys, final Attributes attrs) {
        for (final int tag : keys.tags()) {
            if (!attrs.contains(tag)) {
                final VR vr = keys.getVR(tag);
                attrs.setNull(tag, vr != null ? vr : VR.LO);
            }
        }
    }

    private static String nvl(final String value) {
        return value != null ? value : "";
    }

    private static String loadSql(final String resourcePath) {
        try (final InputStream is = DimseDataAccess.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException(
                        "SQL resource not found: " + resourcePath);
            }
            try (final BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (final IOException e) {
            throw new IllegalStateException(
                    "Failed to load SQL resource: " + resourcePath, e);
        }
    }
}
