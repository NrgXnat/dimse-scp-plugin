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
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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

        final String sql = buildStudyQuery(keys, params);
        log.trace("Study query SQL: {}", sql);

        return getTemplate().query(sql, params,
                (rs, rowNum) -> mapStudyRow(rs, projectId, keys)).stream();
    }

    /**
     * Assembles the STUDY-level query: base SQL plus the pushed filters
     * and ordering. Package-private so tests can assert that filters
     * bind to the query's {@code WHERE} clause.
     *
     * @param keys   the C-FIND query keys
     * @param params parameter source populated with the bound values
     * @return the assembled SQL
     */
    String buildStudyQuery(final Attributes keys,
                           final MapSqlParameterSource params) {
        final StringBuilder sql = new StringBuilder(studySql);
        addStudyFilters(sql, params, keys);
        sql.append(" ORDER BY e.date, e.time, e.id");
        return sql.toString();
    }

    /**
     * Assembles the SERIES-level query: base SQL plus the pushed
     * filters. Package-private so tests can assert the filters.
     *
     * @param keys   the C-FIND query keys
     * @param params parameter source populated with the bound values
     * @return the assembled SQL
     */
    String buildSeriesQuery(final Attributes keys,
                            final MapSqlParameterSource params) {
        final StringBuilder sql = new StringBuilder(seriesSql);
        addSeriesFilters(sql, params, keys);
        return sql.toString();
    }

    /**
     * Assembles the PATIENT-level query: base SQL plus the pushed
     * filters. Package-private so tests can assert that the project
     * scope predicate is grouped and filters bind to it.
     *
     * @param keys   the C-FIND query keys
     * @param params parameter source populated with the bound values
     * @return the assembled SQL
     */
    String buildPatientQuery(final Attributes keys,
                             final MapSqlParameterSource params) {
        final StringBuilder sql = new StringBuilder(patientSql);
        addPatientFilters(sql, params, keys);
        return sql.toString();
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

        final String sql = buildSeriesQuery(keys, params);
        log.trace("Series query SQL: {}", sql);

        return getTemplate().query(sql, params, (rs, rowNum) -> {
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

        final String sql = buildPatientQuery(keys, params);
        log.trace("Patient query SQL: {}", sql);

        return getTemplate().query(sql, params, (rs, rowNum) -> {
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
                DicomMatchUtils.nonEmptyValues(keys.getStrings(Tag.Modality)).stream()
                        .map(DimseDataAccess::dicomToXnatModality)
                        .toList(),
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
     * case-insensitive exact match. A multi-valued key matches if any
     * value matches, as in {@link DicomMatchUtils#matchesAnyValue}.
     */
    void addStringFilter(final StringBuilder sql,
                                 final MapSqlParameterSource params,
                                 final Attributes keys,
                                 final int tag,
                                 final String column,
                                 final String paramName) {
        addStringFilter(sql, params,
                DicomMatchUtils.nonEmptyValues(keys.getStrings(tag)), column, paramName);
    }

    /**
     * Adds a case-insensitive string filter for explicit values. Used
     * where the query values need preprocessing before matching (e.g.
     * the {@code PT} to {@code PET} Modality reverse mapping in
     * {@link #addSeriesFilters}). Several values are ORed. Empty values
     * are ignored; if none remain, or any value is only {@code *}, no
     * predicate is added: that is universal matching, and
     * {@code ILIKE '%'} would wrongly drop rows where the column is NULL.
     */
    void addStringFilter(final StringBuilder sql,
                                 final MapSqlParameterSource params,
                                 final List<String> values,
                                 final String column,
                                 final String paramName) {
        final List<String> patterns = values.stream()
                .filter(StringUtils::isNotEmpty)
                .toList();
        if (patterns.stream().anyMatch(DicomMatchUtils::isUniversalWildcard)) {
            return;
        }
        appendAnyOf(sql, params, IntStream.range(0, patterns.size())
                .mapToObj(i -> {
                    final String name = paramName + indexSuffix(i, patterns.size());
                    return Optional.of(new SqlPredicate(column + " ILIKE :" + name,
                            Map.of(name, DicomMatchUtils.toSqlLike(patterns.get(i)))));
                })
                .toList());
    }

    /**
     * Adds a date-valued filter. Supports exact date, date ranges
     * (YYYYMMDD-YYYYMMDD), and open-ended ranges. A multi-valued key
     * matches if any value matches. Values must already be valid (see
     * {@link DicomMatchUtils#parseDateRange}).
     */
    void addDateFilter(final StringBuilder sql,
                               final MapSqlParameterSource params,
                               final Attributes keys,
                               final int tag,
                               final String column) {
        final List<String> values = DicomMatchUtils.nonEmptyValues(keys.getStrings(tag));
        appendAnyOf(sql, params, IntStream.range(0, values.size())
                .mapToObj(i -> datePredicate(values.get(i), tag, column,
                        indexSuffix(i, values.size())))
                .toList());
    }

    /**
     * Builds the predicate for one DA query value, from the bounds
     * {@link DicomMatchUtils#parseDateRange} finds, so the post-filter
     * applies the same constraint. The value must already be valid:
     * {@code DateTimeKeys.requireValid} rejects malformed values before
     * the query runs.
     *
     * @param value  a non-empty, valid DA query value (exact or range)
     * @param tag    the DICOM tag, for the error message
     * @param column the SQL column
     * @param suffix appended to parameter names to keep them unique
     * @return the predicate
     * @throws IllegalArgumentException if the value is malformed
     */
    private static Optional<SqlPredicate> datePredicate(final String value,
                                                        final int tag,
                                                        final String column,
                                                        final String suffix) {
        final DicomMatchUtils.DateRange range = DicomMatchUtils.parseDateRange(value)
                .orElseThrow(() -> new IllegalArgumentException("Malformed DA query value '"
                        + value + "' for tag " + Integer.toHexString(tag)));
        if (range.exact()) {
            return Optional.of(dateComparison(column, "=", "dateExact" + suffix,
                    range.lower().orElseThrow()));
        }
        final List<SqlPredicate> bounds = Stream.of(
                        range.lower().map(bound ->
                                dateComparison(column, ">=", "dateStart" + suffix, bound)),
                        range.upper().map(bound ->
                                dateComparison(column, "<=", "dateEnd" + suffix, bound)))
                .flatMap(Optional::stream)
                .toList();
        return Optional.of(new SqlPredicate(
                bounds.stream().map(SqlPredicate::sql).collect(Collectors.joining(" AND ")),
                bounds.stream().flatMap(b -> b.params().entrySet().stream())
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))));
    }

    /**
     * Builds a comparison of a {@code date} column with a valid DA
     * value, bound as an ISO date so the column is not compared
     * against a string.
     */
    private static SqlPredicate dateComparison(final String column,
                                               final String operator,
                                               final String name,
                                               final String dicomDate) {
        return new SqlPredicate(
                column + " " + operator + " CAST(:" + name + " AS DATE)",
                Map.of(name, DicomMatchUtils.dicomDateToIso(dicomDate)));
    }

    /**
     * A SQL predicate fragment and the named parameters it binds.
     *
     * @param sql    the predicate, without a leading {@code AND}
     * @param params the parameter values, by name
     */
    private record SqlPredicate(String sql, Map<String, Object> params) {}

    /**
     * Appends the disjunction of one predicate per query value. A
     * single predicate is appended as is; several are parenthesized and
     * ORed. If there are no predicates, or any value places no
     * restriction (an empty element), nothing is appended: the key is
     * universal matching.
     */
    private static void appendAnyOf(final StringBuilder sql,
                                    final MapSqlParameterSource params,
                                    final List<Optional<SqlPredicate>> predicates) {
        if (predicates.isEmpty() || predicates.stream().anyMatch(Optional::isEmpty)) {
            return;
        }
        final List<SqlPredicate> present = predicates.stream().map(Optional::get).toList();
        sql.append(" AND ").append(present.size() == 1
                ? present.get(0).sql()
                : present.stream().map(p -> "(" + p.sql() + ")")
                        .collect(Collectors.joining(" OR ", "(", ")")));
        present.forEach(p -> params.addValues(p.params()));
    }

    /**
     * Parameter-name suffix for the value at {@code index}: none for a
     * single value, so single-valued queries keep their plain names.
     */
    private static String indexSuffix(final int index, final int count) {
        return count == 1 ? "" : String.valueOf(index);
    }

    // -- Helpers --

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
