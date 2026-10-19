package org.nrg.xnat.dimse.query;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link DicomMatchUtils}.
 */
public class DicomMatchUtilsTest {

    // --- Universal match ---

    @Test
    public void emptyQueryKeyMatchesAnything() {
        assertTrue(DicomMatchUtils.matchesValue(Tag.PatientName, VR.PN, "", "Smith"));
        assertTrue(DicomMatchUtils.matchesValue(Tag.PatientName, VR.PN, null, "Smith"));
    }

    // --- Single value exact match ---

    @Test
    public void exactMatchSucceeds() {
        assertTrue(DicomMatchUtils.matchesValue(
                Tag.AccessionNumber, VR.SH, "ACC001", "ACC001"));
    }

    @Test
    public void exactMatchFailsOnMismatch() {
        assertFalse(DicomMatchUtils.matchesValue(
                Tag.AccessionNumber, VR.SH, "ACC001", "ACC002"));
    }

    @Test
    public void exactMatchFailsOnNull() {
        assertFalse(DicomMatchUtils.matchesValue(
                Tag.AccessionNumber, VR.SH, "ACC001", null));
    }

    @Test
    public void patientNameMatchIsCaseInsensitive() {
        assertTrue(DicomMatchUtils.matchesValue(
                Tag.PatientName, VR.PN, "SMITH", "smith"));
        assertTrue(DicomMatchUtils.matchesValue(
                Tag.PatientName, VR.PN, "Smith", "SMITH"));
    }

    @Test
    public void exactMatchIsCaseInsensitiveForAllVrs() {
        // R4: matching is case-insensitive throughout, not just for PN.
        assertTrue(DicomMatchUtils.matchesValue(
                Tag.AccessionNumber, VR.SH, "acc001", "ACC001"));
        assertTrue(DicomMatchUtils.matchesValue(
                Tag.Modality, VR.CS, "ct", "CT"));
        assertTrue(DicomMatchUtils.matchesValue(
                Tag.PatientID, VR.LO, "SUBJECT01", "subject01"));
    }

    // --- Wildcard matching ---

    @Test
    public void wildcardStarMatchesAny() {
        assertTrue(DicomMatchUtils.matchesValue(
                Tag.PatientName, VR.PN, "SM*", "SMITH"));
        assertTrue(DicomMatchUtils.matchesValue(
                Tag.PatientName, VR.PN, "*ITH", "SMITH"));
        assertTrue(DicomMatchUtils.matchesValue(
                Tag.PatientName, VR.PN, "*MIT*", "SMITH"));
    }

    @Test
    public void wildcardStarAloneMatchesAll() {
        assertTrue(DicomMatchUtils.matchesValue(
                Tag.PatientName, VR.PN, "*", "anything"));
    }

    @Test
    public void wildcardQuestionMatchesSingleChar() {
        assertTrue(DicomMatchUtils.matchesValue(
                Tag.PatientName, VR.PN, "SMI?H", "SMITH"));
        assertFalse(DicomMatchUtils.matchesValue(
                Tag.PatientName, VR.PN, "SMI?", "SMITH"));
    }

    @Test
    public void wildcardIsCaseInsensitiveForPN() {
        assertTrue(DicomMatchUtils.matchesValue(
                Tag.PatientName, VR.PN, "sm*", "SMITH"));
    }

    @Test
    public void wildcardIsCaseInsensitiveForAllVrs() {
        // R4: wildcard matching is case-insensitive for every VR.
        assertTrue(DicomMatchUtils.matchesValue(
                Tag.PatientID, VR.LO, "sub*", "SUBJECT01"));
        assertTrue(DicomMatchUtils.matchesValue(
                Tag.SeriesNumber, VR.IS, "*", "5"));
    }

    @Test
    public void wildcardNoMatchReturns_false() {
        assertFalse(DicomMatchUtils.matchesValue(
                Tag.PatientName, VR.PN, "JO*", "SMITH"));
    }

    // --- Date range matching ---

    @Test
    public void dateRangeBothBounds() {
        assertTrue(DicomMatchUtils.matchesValue(
                Tag.StudyDate, VR.DA, "20200101-20201231", "20200615"));
        assertFalse(DicomMatchUtils.matchesValue(
                Tag.StudyDate, VR.DA, "20200101-20201231", "20210101"));
    }

    @Test
    public void dateRangeIncludesBounds() {
        assertTrue(DicomMatchUtils.matchesValue(
                Tag.StudyDate, VR.DA, "20200101-20201231", "20200101"));
        assertTrue(DicomMatchUtils.matchesValue(
                Tag.StudyDate, VR.DA, "20200101-20201231", "20201231"));
    }

    @Test
    public void dateRangeOpenStart() {
        assertTrue(DicomMatchUtils.matchesValue(
                Tag.StudyDate, VR.DA, "-20201231", "20190101"));
        assertFalse(DicomMatchUtils.matchesValue(
                Tag.StudyDate, VR.DA, "-20201231", "20210101"));
    }

    @Test
    public void dateRangeOpenEnd() {
        assertTrue(DicomMatchUtils.matchesValue(
                Tag.StudyDate, VR.DA, "20200101-", "20250101"));
        assertFalse(DicomMatchUtils.matchesValue(
                Tag.StudyDate, VR.DA, "20200101-", "20190101"));
    }

    // --- UID list matching ---

    @Test
    public void singleUidMatch() {
        assertTrue(DicomMatchUtils.matchesValue(
                Tag.StudyInstanceUID, VR.UI, "1.2.3", "1.2.3"));
        assertFalse(DicomMatchUtils.matchesValue(
                Tag.StudyInstanceUID, VR.UI, "1.2.3", "4.5.6"));
    }

    @Test
    public void uidListMatchesEveryListedUid() {
        // PS3.4 C.2.2.2.2: each UID in the list may generate a match,
        // independent of list order.
        final Attributes forward = uidQuery("1.2.3", "4.5.6");
        final Attributes reverse = uidQuery("4.5.6", "1.2.3");
        for (final Attributes query : Arrays.asList(forward, reverse)) {
            assertTrue(DicomMatchUtils.matchesQuery(query).test(study("1.2.3")));
            assertTrue(DicomMatchUtils.matchesQuery(query).test(study("4.5.6")));
            assertFalse(DicomMatchUtils.matchesQuery(query).test(study("7.8.9")));
        }
    }

    @Test
    public void singleUidQueryMatchesExactly() {
        final Attributes query = uidQuery("1.2.3");
        assertTrue(DicomMatchUtils.matchesQuery(query).test(study("1.2.3")));
        assertFalse(DicomMatchUtils.matchesQuery(query).test(study("1.2.30")));
    }

    @Test
    public void emptyUidQueryIsUniversal() {
        final Attributes query = new Attributes();
        query.setNull(Tag.StudyInstanceUID, VR.UI);
        assertTrue(DicomMatchUtils.matchesQuery(query).test(study("1.2.3")));
    }

    @Test
    public void uidListDoesNotMatchAbsentCandidate() {
        assertFalse(DicomMatchUtils.matchesUidList(
                new String[] {"1.2.3", "4.5.6"}, null));
    }

    private static Attributes uidQuery(final String... uids) {
        final Attributes query = new Attributes();
        query.setString(Tag.QueryRetrieveLevel, VR.CS, "STUDY");
        query.setString(Tag.StudyInstanceUID, VR.UI, uids);
        return query;
    }

    private static Attributes study(final String uid) {
        final Attributes candidate = new Attributes();
        candidate.setString(Tag.StudyInstanceUID, VR.UI, uid);
        return candidate;
    }

    // --- Multi-valued attribute matching (Modalities in Study) ---

    @Test
    public void multiValuedCandidateMatchesAnyValue() {
        assertTrue(DicomMatchUtils.matchesAnyValue(
                Tag.ModalitiesInStudy, VR.CS, "PT", new String[]{"CT", "PT"}));
        assertTrue(DicomMatchUtils.matchesAnyValue(
                Tag.ModalitiesInStudy, VR.CS, "CT", new String[]{"CT", "PT"}));
        // case-insensitive, per R4
        assertTrue(DicomMatchUtils.matchesAnyValue(
                Tag.ModalitiesInStudy, VR.CS, "pt", new String[]{"CT", "PT"}));
    }

    @Test
    public void multiValuedCandidateRejectsAbsentAndEmpty() {
        assertFalse(DicomMatchUtils.matchesAnyValue(
                Tag.ModalitiesInStudy, VR.CS, "MR", new String[]{"CT", "PT"}));
        assertFalse(DicomMatchUtils.matchesAnyValue(
                Tag.ModalitiesInStudy, VR.CS, "CT", null));
        // universal query matches regardless
        assertTrue(DicomMatchUtils.matchesAnyValue(
                Tag.ModalitiesInStudy, VR.CS, "", new String[]{"CT"}));
    }

    @Test
    public void queryMatchesStudyContainingModality() {
        final Attributes query = new Attributes();
        query.setString(Tag.QueryRetrieveLevel, VR.CS, "STUDY");
        query.setString(Tag.ModalitiesInStudy, VR.CS, "PT");

        final Attributes candidate = new Attributes();
        // multi-valued, as built from string_agg + split
        candidate.setString(Tag.ModalitiesInStudy, VR.CS, "CT", "PT");

        assertTrue(DicomMatchUtils.matchesQuery(query).test(candidate));
    }

    @Test
    public void queryRejectsStudyWithoutModality() {
        final Attributes query = new Attributes();
        query.setString(Tag.QueryRetrieveLevel, VR.CS, "STUDY");
        query.setString(Tag.ModalitiesInStudy, VR.CS, "MR");

        final Attributes candidate = new Attributes();
        candidate.setString(Tag.ModalitiesInStudy, VR.CS, "CT", "PT");

        assertFalse(DicomMatchUtils.matchesQuery(query).test(candidate));
    }

    // --- Full Attributes matching ---

    @Test
    public void matchesAllKeys() {
        final Attributes query = new Attributes();
        query.setString(Tag.QueryRetrieveLevel, VR.CS, "STUDY");
        query.setString(Tag.PatientName, VR.PN, "SM*");
        query.setString(Tag.StudyDate, VR.DA, ""); // universal

        final Attributes candidate = new Attributes();
        candidate.setString(Tag.PatientName, VR.PN, "SMITH");
        candidate.setString(Tag.StudyDate, VR.DA, "20200615");

        assertTrue(DicomMatchUtils.matchesQuery(query).test(candidate));
    }

    @Test
    public void matchesRejectsOnAnyKeyMismatch() {
        final Attributes query = new Attributes();
        query.setString(Tag.QueryRetrieveLevel, VR.CS, "STUDY");
        query.setString(Tag.PatientName, VR.PN, "JONES");
        query.setString(Tag.StudyDate, VR.DA, "20200615");

        final Attributes candidate = new Attributes();
        candidate.setString(Tag.PatientName, VR.PN, "SMITH");
        candidate.setString(Tag.StudyDate, VR.DA, "20200615");

        assertFalse(DicomMatchUtils.matchesQuery(query).test(candidate));
    }

    // --- SQL LIKE translation ---

    @Test
    public void toSqlLikeTranslatesDicomWildcards() {
        assertEquals("%", DicomMatchUtils.toSqlLike("*"));
        assertEquals("SM%", DicomMatchUtils.toSqlLike("SM*"));
        assertEquals("_", DicomMatchUtils.toSqlLike("?"));
        assertEquals("SM_TH", DicomMatchUtils.toSqlLike("SM?TH"));
    }

    @Test
    public void toSqlLikeEscapesSqlWildcards() {
        assertEquals("100\\%", DicomMatchUtils.toSqlLike("100%"));
        assertEquals("a\\_b", DicomMatchUtils.toSqlLike("a_b"));
    }

    // --- DICOM date validation ---

    @Test
    public void isValidDicomDateAcceptsRealDates() {
        assertTrue(DicomMatchUtils.isValidDicomDate("20061214"));
        assertTrue(DicomMatchUtils.isValidDicomDate("20200229")); // leap year
    }

    @Test
    public void isValidDicomDateRejectsBadDates() {
        assertFalse(DicomMatchUtils.isValidDicomDate("20160732")); // no day 32
        assertFalse(DicomMatchUtils.isValidDicomDate("20210229")); // not leap
        assertFalse(DicomMatchUtils.isValidDicomDate("20201301")); // no month 13
        assertFalse(DicomMatchUtils.isValidDicomDate("2020123"));  // 7 chars
        assertFalse(DicomMatchUtils.isValidDicomDate("abcd1234"));
        assertFalse(DicomMatchUtils.isValidDicomDate(""));
        assertFalse(DicomMatchUtils.isValidDicomDate(null));
    }

    // --- DICOM date to ISO conversion ---

    @Test
    public void dicomDateToIsoFormat() {
        assertEquals("2020-01-15", DicomMatchUtils.dicomDateToIso("20200115"));
    }

    @Test
    public void dicomDateToIsoPassesThroughInvalid() {
        assertEquals("invalid", DicomMatchUtils.dicomDateToIso("invalid"));
        assertEquals(null, DicomMatchUtils.dicomDateToIso(null));
    }

    // --- Wildcard detection ---

    @Test
    public void containsWildcardDetectsStarAndQuestion() {
        assertTrue(DicomMatchUtils.containsWildcard("SM*"));
        assertTrue(DicomMatchUtils.containsWildcard("SM?TH"));
        assertFalse(DicomMatchUtils.containsWildcard("SMITH"));
    }

    // --- Date range detection ---

    @Test
    public void isDateRangeDetectsHyphen() {
        assertTrue(DicomMatchUtils.isDateRange("20200101-20201231"));
        assertTrue(DicomMatchUtils.isDateRange("-20201231"));
        assertTrue(DicomMatchUtils.isDateRange("20200101-"));
        assertFalse(DicomMatchUtils.isDateRange("20200101"));
    }
}
