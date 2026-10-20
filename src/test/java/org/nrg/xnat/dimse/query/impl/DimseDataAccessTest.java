package org.nrg.xnat.dimse.query.impl;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.sql.ResultSet;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DimseDataAccess} helper methods and SQL filter construction.
 */
public class DimseDataAccessTest {

    // --- genderToDicomSex ---

    @Test
    public void maleGenderReturnsM() {
        assertEquals(Optional.of("M"), DimseDataAccess.genderToDicomSex("male"));
        assertEquals(Optional.of("M"), DimseDataAccess.genderToDicomSex("m"));
        assertEquals(Optional.of("M"), DimseDataAccess.genderToDicomSex("Male"));
        assertEquals(Optional.of("M"), DimseDataAccess.genderToDicomSex("M"));
    }

    @Test
    public void femaleGenderReturnsF() {
        assertEquals(Optional.of("F"), DimseDataAccess.genderToDicomSex("female"));
        assertEquals(Optional.of("F"), DimseDataAccess.genderToDicomSex("f"));
        assertEquals(Optional.of("F"), DimseDataAccess.genderToDicomSex("Female"));
        assertEquals(Optional.of("F"), DimseDataAccess.genderToDicomSex("F"));
    }

    @Test
    public void otherGenderReturnsO() {
        assertEquals(Optional.of("O"), DimseDataAccess.genderToDicomSex("other"));
        assertEquals(Optional.of("O"), DimseDataAccess.genderToDicomSex("unknown"));
    }

    @Test
    public void nullBlankGenderReturnsEmpty() {
        assertFalse(DimseDataAccess.genderToDicomSex(null).isPresent());
        assertFalse(DimseDataAccess.genderToDicomSex("").isPresent());
        assertFalse(DimseDataAccess.genderToDicomSex("  ").isPresent());
    }

    // --- dicomModality ---

    @Test
    public void petConvertsToPt() {
        assertEquals("PT", DimseDataAccess.dicomModality("PET"));
    }

    @Test
    public void normalModalityPassesThrough() {
        assertEquals("CT", DimseDataAccess.dicomModality("CT"));
        assertEquals("MR", DimseDataAccess.dicomModality("MR"));
    }

    @Test
    public void longModalityTruncated() {
        assertEquals("1234567890123456",
                DimseDataAccess.dicomModality("12345678901234567"));
    }

    @Test
    public void nullEmptyModalityReturnsNull() {
        assertNull(DimseDataAccess.dicomModality(null));
        assertNull(DimseDataAccess.dicomModality(""));
    }

    // --- copyReturnKeys ---

    @Test
    public void missingKeysAddedAsNull() {
        final Attributes keys = new Attributes();
        keys.setString(Tag.PatientName, VR.PN, "");
        keys.setString(Tag.StudyDate, VR.DA, "");
        keys.setString(Tag.StudyDescription, VR.LO, "");

        final Attributes attrs = new Attributes();
        attrs.setString(Tag.PatientName, VR.PN, "SMITH");
        attrs.setString(Tag.StudyDate, VR.DA, "20200101");

        DimseDataAccess.copyReturnKeys(keys, attrs);

        assertEquals("SMITH", attrs.getString(Tag.PatientName));
        assertEquals("20200101", attrs.getString(Tag.StudyDate));
        assertTrue(attrs.contains(Tag.StudyDescription));
    }

    @Test
    public void existingKeysNotOverwritten() {
        final Attributes keys = new Attributes();
        keys.setString(Tag.PatientName, VR.PN, "JONES");

        final Attributes attrs = new Attributes();
        attrs.setString(Tag.PatientName, VR.PN, "SMITH");

        DimseDataAccess.copyReturnKeys(keys, attrs);

        assertEquals("SMITH", attrs.getString(Tag.PatientName));
    }

    // --- mapStudyRow (R1: Study Description = project ID) ---

    @Test
    public void studyRowSetsStudyDescriptionToProjectId() throws Exception {
        final ResultSet rs = mock(ResultSet.class);
        when(rs.getString("exptid")).thenReturn("XNAT_E00001");
        when(rs.getString("experiment_label")).thenReturn("SESSION_A");
        when(rs.getString("subject_label")).thenReturn("SUBJ_1");

        final Attributes attrs =
                DimseDataAccess.mapStudyRow(rs, "MYPROJECT", new Attributes());

        // R1: Study Description is the project ID, matching DICOMweb.
        assertEquals("MYPROJECT", attrs.getString(Tag.StudyDescription));

        // Sibling mappings, documenting the study row shape.
        assertEquals("XNAT_E00001", attrs.getString(Tag.AccessionNumber));
        assertEquals("XNAT_E00001", attrs.getString(Tag.StudyID));
        assertEquals("SESSION_A", attrs.getString(Tag.PatientName));
        assertEquals("SUBJ_1", attrs.getString(Tag.PatientID));
    }

    // --- assembled query: filters bind to a real WHERE clause ---

    @Test
    public void studyQueryBindsFilterToWhereAnchor() {
        final Attributes keys = new Attributes();
        keys.setString(Tag.PatientName, VR.PN, "SMITH");
        final MapSqlParameterSource params = new MapSqlParameterSource();

        final String sql = newAccess().buildStudyQuery(keys, params);

        // The filter must extend the WHERE TRUE anchor, not the trailing
        // LEFT JOIN ... ON (which would not restrict study rows).
        assertTrue(sql.contains(
                "WHERE TRUE AND pe.experiment_label ILIKE :patientName"));
        assertTrue(sql.indexOf("WHERE TRUE") < sql.indexOf("ORDER BY"));
    }

    @Test
    public void studyQueryWithNoKeysHasWhereAnchor() {
        final String sql = newAccess()
                .buildStudyQuery(new Attributes(), new MapSqlParameterSource());

        assertTrue(sql.contains("WHERE TRUE"));
        assertTrue(sql.contains("ORDER BY"));
    }

    @Test
    public void patientQueryGroupsProjectDisjunction() {
        final Attributes keys = new Attributes();
        keys.setString(Tag.PatientID, VR.LO, "SUBJ1");
        final MapSqlParameterSource params = new MapSqlParameterSource();

        final String sql = newAccess().buildPatientQuery(keys, params);

        // The project-scope disjunction is parenthesized so the appended
        // AND applies to the whole predicate, not just the EXISTS branch.
        assertTrue(sql.contains("WHERE (s.project = :projectId"));
        assertTrue(sql.contains(
                "AND COALESCE(psl.subject_label, s.label) ILIKE :patientId"));
    }

    // --- addUidFilter ---

    @Test
    public void uidFilterEmptyValueNoOp() {
        final StringBuilder sql = new StringBuilder("SELECT 1");
        final MapSqlParameterSource params = new MapSqlParameterSource();
        final Attributes keys = new Attributes();
        keys.setString(Tag.StudyInstanceUID, VR.UI, "");

        newAccess().addUidFilter(sql, params, keys, Tag.StudyInstanceUID, "i.uid");

        assertEquals("SELECT 1", sql.toString());
        assertFalse(params.hasValue("uid"));
    }

    @Test
    public void uidFilterSingleUidExactMatch() {
        final StringBuilder sql = new StringBuilder("SELECT 1");
        final MapSqlParameterSource params = new MapSqlParameterSource();
        final Attributes keys = new Attributes();
        keys.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3.4");

        newAccess().addUidFilter(sql, params, keys, Tag.StudyInstanceUID, "i.uid");

        assertTrue(sql.toString().contains("AND i.uid = :uid"));
        assertEquals("1.2.3.4", params.getValue("uid"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void uidFilterMultipleUidsInClause() {
        final StringBuilder sql = new StringBuilder("SELECT 1");
        final MapSqlParameterSource params = new MapSqlParameterSource();
        final Attributes keys = new Attributes();
        keys.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3", "4.5.6", "7.8.9");

        newAccess().addUidFilter(sql, params, keys, Tag.StudyInstanceUID, "i.uid");

        assertTrue(sql.toString().contains("AND i.uid IN (:uidList)"));
        final List<String> uidList = (List<String>) params.getValue("uidList");
        assertEquals(Arrays.asList("1.2.3", "4.5.6", "7.8.9"), uidList);
    }

    // --- addStringFilter ---

    @Test
    public void stringFilterEmptyValueNoOp() {
        final StringBuilder sql = new StringBuilder("SELECT 1");
        final MapSqlParameterSource params = new MapSqlParameterSource();
        final Attributes keys = new Attributes();
        keys.setString(Tag.PatientName, VR.PN, "");

        newAccess().addStringFilter(sql, params, keys,
                Tag.PatientName, "e.label", "name");

        assertEquals("SELECT 1", sql.toString());
    }

    @Test
    public void stringFilterLoneStarNoOp() {
        // A lone * is universal matching; ILIKE '%' would drop NULL rows.
        for (final String value : Arrays.asList("*", "**")) {
            final StringBuilder sql = new StringBuilder("SELECT 1");
            final MapSqlParameterSource params = new MapSqlParameterSource();
            final Attributes keys = new Attributes();
            keys.setString(Tag.PatientID, VR.LO, value);

            newAccess().addStringFilter(sql, params, keys,
                    Tag.PatientID, "psl.subject_label", "patientId");

            assertEquals("SELECT 1", sql.toString());
            assertFalse(params.hasValue("patientId"));
        }
    }

    @Test
    public void stringFilterExactMatchUsesIlike() {
        final StringBuilder sql = new StringBuilder("SELECT 1");
        final MapSqlParameterSource params = new MapSqlParameterSource();
        final Attributes keys = new Attributes();
        keys.setString(Tag.PatientName, VR.PN, "SMITH");

        newAccess().addStringFilter(sql, params, keys,
                Tag.PatientName, "e.label", "name");

        // R4: case-insensitive matching pushed to SQL via ILIKE. A
        // value with no wildcards is a case-insensitive exact match.
        assertTrue(sql.toString().contains("AND e.label ILIKE :name"));
        assertEquals("SMITH", params.getValue("name"));
    }

    @Test
    public void stringFilterWildcardUsesIlike() {
        final StringBuilder sql = new StringBuilder("SELECT 1");
        final MapSqlParameterSource params = new MapSqlParameterSource();
        final Attributes keys = new Attributes();
        keys.setString(Tag.PatientName, VR.PN, "SM*");

        newAccess().addStringFilter(sql, params, keys,
                Tag.PatientName, "e.label", "name");

        assertTrue(sql.toString().contains("AND e.label ILIKE :name"));
        assertEquals("SM%", params.getValue("name"));
    }

    @Test
    public void stringFilterQuestionMarkWildcard() {
        final StringBuilder sql = new StringBuilder("SELECT 1");
        final MapSqlParameterSource params = new MapSqlParameterSource();
        final Attributes keys = new Attributes();
        keys.setString(Tag.PatientName, VR.PN, "SM?TH");

        newAccess().addStringFilter(sql, params, keys,
                Tag.PatientName, "e.label", "name");

        assertTrue(sql.toString().contains("ILIKE :name"));
        assertEquals("SM_TH", params.getValue("name"));
    }

    @Test
    public void stringFilterMultipleValuesOrderIndependent() {
        final MapSqlParameterSource forward = new MapSqlParameterSource();
        final MapSqlParameterSource reverse = new MapSqlParameterSource();
        final String sqlForward = stringFilterSql(forward, "MR", "CT");
        final String sqlReverse = stringFilterSql(reverse, "CT", "MR");

        assertEquals("SELECT 1 AND ((sc.modality ILIKE :modality0)"
                + " OR (sc.modality ILIKE :modality1))", sqlForward);
        assertEquals(sqlForward, sqlReverse);
        assertEquals(new HashSet<>(Arrays.asList("MR", "CT")),
                new HashSet<>(Arrays.asList(forward.getValue("modality0"),
                        forward.getValue("modality1"))));
        assertEquals(new HashSet<>(Arrays.asList("MR", "CT")),
                new HashSet<>(Arrays.asList(reverse.getValue("modality0"),
                        reverse.getValue("modality1"))));
    }

    @Test
    public void stringFilterMultipleValuesWithLoneStarNoOp() {
        final MapSqlParameterSource params = new MapSqlParameterSource();
        assertEquals("SELECT 1", stringFilterSql(params, "MR", "*"));
        assertFalse(params.hasValue("modality0"));
    }

    @Test
    public void stringFilterIgnoresEmptyValues() {
        final MapSqlParameterSource params = new MapSqlParameterSource();
        assertEquals("SELECT 1 AND sc.modality ILIKE :modality",
                stringFilterSql(params, "", "MR"));
        assertEquals("MR", params.getValue("modality"));
    }

    @Test
    public void seriesQueryMapsEachModalityValue() {
        final Attributes keys = new Attributes();
        keys.setString(Tag.Modality, VR.CS, "CT", "PT");
        final MapSqlParameterSource params = new MapSqlParameterSource();

        final String sql = newAccess().buildSeriesQuery(keys, params);

        assertTrue(sql.endsWith(" AND ((sc.modality ILIKE :modality0)"
                + " OR (sc.modality ILIKE :modality1))"));
        assertEquals("CT", params.getValue("modality0"));
        assertEquals("PET", params.getValue("modality1"));
    }

    private static String stringFilterSql(final MapSqlParameterSource params,
                                          final String... modalities) {
        final StringBuilder sql = new StringBuilder("SELECT 1");
        final Attributes keys = new Attributes();
        keys.setString(Tag.Modality, VR.CS, modalities);
        newAccess().addStringFilter(sql, params, keys,
                Tag.Modality, "sc.modality", "modality");
        return sql.toString();
    }

    // --- Modality reverse mapping (R3: query PT matches XNAT PET) ---

    @Test
    public void modalityPtQueryReverseMapsToPet() {
        assertEquals("PET", DimseDataAccess.dicomToXnatModality("PT"));
        assertEquals("PET", DimseDataAccess.dicomToXnatModality("pt"));
    }

    @Test
    public void modalityOtherValuesPassThrough() {
        assertEquals("CT", DimseDataAccess.dicomToXnatModality("CT"));
        assertEquals("MR*", DimseDataAccess.dicomToXnatModality("MR*"));
        assertNull(DimseDataAccess.dicomToXnatModality(null));
    }

    // --- addDateFilter ---

    @Test
    public void dateFilterEmptyValueNoOp() {
        final StringBuilder sql = new StringBuilder("SELECT 1");
        final MapSqlParameterSource params = new MapSqlParameterSource();
        final Attributes keys = new Attributes();
        keys.setString(Tag.StudyDate, VR.DA, "");

        newAccess().addDateFilter(sql, params, keys, Tag.StudyDate, "e.date");

        assertEquals("SELECT 1", sql.toString());
    }

    @Test
    public void dateFilterExactDate() {
        final StringBuilder sql = new StringBuilder("SELECT 1");
        final MapSqlParameterSource params = new MapSqlParameterSource();
        final Attributes keys = new Attributes();
        keys.setString(Tag.StudyDate, VR.DA, "20200615");

        newAccess().addDateFilter(sql, params, keys, Tag.StudyDate, "e.date");

        assertTrue(sql.toString().contains("AND e.date = CAST(:dateExact AS DATE)"));
        assertEquals("2020-06-15", params.getValue("dateExact"));
    }

    @Test
    public void dateFilterBoundedRange() {
        final StringBuilder sql = new StringBuilder("SELECT 1");
        final MapSqlParameterSource params = new MapSqlParameterSource();
        final Attributes keys = new Attributes();
        keys.setString(Tag.StudyDate, VR.DA, "20200101-20201231");

        newAccess().addDateFilter(sql, params, keys, Tag.StudyDate, "e.date");

        final String result = sql.toString();
        assertTrue(result.contains("AND e.date >= CAST(:dateStart AS DATE)"));
        assertTrue(result.contains("AND e.date <= CAST(:dateEnd AS DATE)"));
        assertEquals("2020-01-01", params.getValue("dateStart"));
        assertEquals("2020-12-31", params.getValue("dateEnd"));
    }

    @Test
    public void dateFilterOpenStartRange() {
        final StringBuilder sql = new StringBuilder("SELECT 1");
        final MapSqlParameterSource params = new MapSqlParameterSource();
        final Attributes keys = new Attributes();
        keys.setString(Tag.StudyDate, VR.DA, "-20201231");

        newAccess().addDateFilter(sql, params, keys, Tag.StudyDate, "e.date");

        final String result = sql.toString();
        assertFalse(result.contains("dateStart"));
        assertTrue(result.contains("AND e.date <= CAST(:dateEnd AS DATE)"));
        assertEquals("2020-12-31", params.getValue("dateEnd"));
    }

    @Test
    public void dateFilterOpenEndRange() {
        final StringBuilder sql = new StringBuilder("SELECT 1");
        final MapSqlParameterSource params = new MapSqlParameterSource();
        final Attributes keys = new Attributes();
        keys.setString(Tag.StudyDate, VR.DA, "20200101-");

        newAccess().addDateFilter(sql, params, keys, Tag.StudyDate, "e.date");

        final String result = sql.toString();
        assertTrue(result.contains("AND e.date >= CAST(:dateStart AS DATE)"));
        assertFalse(result.contains("dateEnd"));
        assertEquals("2020-01-01", params.getValue("dateStart"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void dateFilterThrowsOnUnvalidatedMalformedDate() {
        // DateTimeKeys rejects malformed values before the query runs;
        // reaching SQL with one is a programming error, not a no-op.
        final Attributes keys = new Attributes();
        keys.setString(Tag.StudyDate, VR.DA, "20200615", "20200732");

        newAccess().addDateFilter(new StringBuilder("SELECT 1"),
                new MapSqlParameterSource(), keys, Tag.StudyDate, "e.date");
    }

    @Test
    public void dateFilterMultipleValuesOred() {
        final StringBuilder sql = new StringBuilder("SELECT 1");
        final MapSqlParameterSource params = new MapSqlParameterSource();
        final Attributes keys = new Attributes();
        keys.setString(Tag.StudyDate, VR.DA, "20200615", "20210101-20210131");

        newAccess().addDateFilter(sql, params, keys, Tag.StudyDate, "e.date");

        assertEquals("SELECT 1 AND ((e.date = CAST(:dateExact0 AS DATE))"
                + " OR (e.date >= CAST(:dateStart1 AS DATE)"
                + " AND e.date <= CAST(:dateEnd1 AS DATE)))", sql.toString());
        assertEquals("2020-06-15", params.getValue("dateExact0"));
        assertEquals("2021-01-01", params.getValue("dateStart1"));
        assertEquals("2021-01-31", params.getValue("dateEnd1"));
    }

    // --- Count SQL (text checks; C7 integration tests run it) ---

    /** SQL form of CatalogEntryUtils.isDicomResource, used by every count. */
    private static final String DICOM_RESOURCE_RULE =
            "LOWER(CONCAT_WS(' ', ar.label, r.format, r.content))"
            + " LIKE ANY (ARRAY['%dicom%', '%secondary%'])";

    @Test
    public void everyCountUsesTheCGetResourceRule() {
        final DimseDataAccess access = newAccess();
        for (final String sql : Arrays.asList(
                access.buildStudyQuery(new Attributes(), new MapSqlParameterSource()),
                access.buildSeriesQuery(new Attributes(), new MapSqlParameterSource()),
                access.buildPatientQuery(new Attributes(), new MapSqlParameterSource()))) {
            assertTrue(sql.contains(DICOM_RESOURCE_RULE));
            assertTrue(sql.contains("LEFT JOIN xnat_resource r"));
            assertTrue(sql.contains(
                    "ON r.xnat_abstractresource_id = ar.xnat_abstractresource_id"));
            assertFalse(sql.contains("label IN"));
        }
    }

    @Test
    public void seriesFileCountIsPerScan() {
        final String sql = newAccess().buildSeriesQuery(
                new Attributes(), new MapSqlParameterSource());
        assertTrue(sql.contains("LEFT JOIN LATERAL"));
        assertTrue(sql.contains("WHERE ar.xnat_imagescandata_xnat_imagescandata_id"
                + " = sc.xnat_imagescandata_id"));
        assertFalse(sql.contains("GROUP BY"));
    }

    @Test
    public void patientCountsOnlyImageSessions() {
        final String sql = newAccess().buildPatientQuery(
                new Attributes(), new MapSqlParameterSource());
        assertTrue(sql.contains("JOIN xnat_imagesessiondata i\n      ON i.id = e.id"));
    }

    /**
     * Creates a DimseDataAccess without loading SQL resources,
     * for testing filter methods only.
     */
    private static DimseDataAccess newAccess() {
        return new DimseDataAccess();
    }
}
