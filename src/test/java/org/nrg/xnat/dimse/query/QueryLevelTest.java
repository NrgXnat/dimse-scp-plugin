package org.nrg.xnat.dimse.query;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.dcm4che3.net.Dimse;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.service.DicomServiceException;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link QueryLevel}.
 */
public class QueryLevelTest {

    @Test
    public void parsesStudyLevel() throws DicomServiceException {
        assertEquals(QueryLevel.STUDY, QueryLevel.fromKeys(keysWithLevel("STUDY")));
    }

    @Test
    public void parsesSeriesLevel() throws DicomServiceException {
        assertEquals(QueryLevel.SERIES, QueryLevel.fromKeys(keysWithLevel("SERIES")));
    }

    @Test
    public void parsesImageLevel() throws DicomServiceException {
        assertEquals(QueryLevel.IMAGE, QueryLevel.fromKeys(keysWithLevel("IMAGE")));
    }

    @Test
    public void parsesInstanceAlias() throws DicomServiceException {
        assertEquals(QueryLevel.IMAGE, QueryLevel.fromKeys(keysWithLevel("INSTANCE")));
    }

    @Test
    public void parsesLowercaseLevel() throws DicomServiceException {
        assertEquals(QueryLevel.STUDY, QueryLevel.fromKeys(keysWithLevel("study")));
    }

    @Test(expected = DicomServiceException.class)
    public void rejectsMissingLevel() throws DicomServiceException {
        QueryLevel.fromKeys(new Attributes());
    }

    @Test
    public void parsesPatientLevel() throws DicomServiceException {
        assertEquals(QueryLevel.PATIENT, QueryLevel.fromKeys(keysWithLevel("PATIENT")));
    }

    @Test(expected = DicomServiceException.class)
    public void rejectsUnknownLevel() throws DicomServiceException {
        QueryLevel.fromKeys(keysWithLevel("BOGUS"));
    }

    // --- requireSingleUidKeysAbove (PS3.4 C.4.1.2.1) ---

    @Test
    public void allowsUidListAtQueryLevel() throws DicomServiceException {
        final Attributes keys = keysWithLevel("STUDY");
        keys.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3", "4.5.6");
        QueryLevel.STUDY.requireSingleUidKeysAbove(keys);

        final Attributes series = keysWithLevel("SERIES");
        series.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3");
        series.setString(Tag.SeriesInstanceUID, VR.UI, "1.2.3.1", "1.2.3.2");
        QueryLevel.SERIES.requireSingleUidKeysAbove(series);
    }

    @Test
    public void rejectsStudyUidListAtSeriesLevel() {
        final Attributes keys = keysWithLevel("SERIES");
        keys.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3", "4.5.6");
        assertRejected(QueryLevel.SERIES, keys, Tag.StudyInstanceUID);
    }

    @Test
    public void rejectsStudyUidListAtImageLevel() {
        final Attributes keys = keysWithLevel("IMAGE");
        keys.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3", "4.5.6");
        keys.setString(Tag.SeriesInstanceUID, VR.UI, "1.2.3.1");
        assertRejected(QueryLevel.IMAGE, keys, Tag.StudyInstanceUID);
    }

    @Test
    public void rejectsSeriesUidListAtImageLevel() {
        final Attributes keys = keysWithLevel("IMAGE");
        keys.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3");
        keys.setString(Tag.SeriesInstanceUID, VR.UI, "1.2.3.1", "1.2.3.2");
        assertRejected(QueryLevel.IMAGE, keys, Tag.SeriesInstanceUID);
    }

    @Test
    public void allowsSingleOrAbsentUidKeysAbove() throws DicomServiceException {
        final Attributes keys = keysWithLevel("IMAGE");
        keys.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3");
        QueryLevel.IMAGE.requireSingleUidKeysAbove(keys);
    }

    @Test
    public void ignoresNonUidPatientIdAbove() throws DicomServiceException {
        // Patient ID is not a UID; only UID Unique Keys are checked.
        final Attributes keys = keysWithLevel("STUDY");
        keys.setString(Tag.PatientID, VR.LO, "A", "B");
        QueryLevel.STUDY.requireSingleUidKeysAbove(keys);
    }

    private static void assertRejected(final QueryLevel level, final Attributes keys,
                                       final int offendingTag) {
        try {
            level.requireSingleUidKeysAbove(keys);
            fail("expected DicomServiceException");
        } catch (final DicomServiceException e) {
            final Attributes rsp = e.mkRSP(Dimse.C_FIND_RSP.commandField(), 1);
            assertEquals(Status.UnableToProcess, rsp.getInt(Tag.Status, -1));
            assertArrayEquals(new int[] {offendingTag}, rsp.getInts(Tag.OffendingElement));
            final String comment = rsp.getString(Tag.ErrorComment);
            assertTrue(comment, comment.length() < 64);
        }
    }

    private static Attributes keysWithLevel(final String level) {
        final Attributes keys = new Attributes();
        keys.setString(Tag.QueryRetrieveLevel, VR.CS, level);
        return keys;
    }
}
