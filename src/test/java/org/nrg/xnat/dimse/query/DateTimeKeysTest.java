package org.nrg.xnat.dimse.query;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.dcm4che3.net.Dimse;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.service.DicomServiceException;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link DateTimeKeys}.
 */
public class DateTimeKeysTest {

    // --- DA ---

    @Test
    public void validDatesAccepted() throws DicomServiceException {
        for (final String value : Arrays.asList(
                "20200615", "20200229", "20200101-20201231", "20200101-",
                "-20201231", "20200101-20200101")) {
            DateTimeKeys.requireValid(keys(Tag.StudyDate, VR.DA, value));
        }
    }

    @Test
    public void malformedDatesRejected() {
        for (final String value : Arrays.asList(
                "202001", "20201301-", "20200732", "20050701-20160732",
                "20201231-20200101", "-", "*", "2020*", "2020-01-01",
                "2020.01.01", "\"\"")) {
            assertRejected(keys(Tag.StudyDate, VR.DA, value), Tag.StudyDate);
        }
    }

    @Test
    public void everyDaKeyIsChecked() {
        // Not only the Range-matching key: Patient Birth Date too.
        assertRejected(keys(Tag.PatientBirthDate, VR.DA, "19700230"), Tag.PatientBirthDate);
    }

    // --- TM ---

    @Test
    public void validTimesAccepted() throws DicomServiceException {
        for (final String value : Arrays.asList(
                "10", "1030", "103000", "103000.5", "103000.123456",
                "000000", "080000-120000", "08-12", "0800-", "-1200",
                "10-1030", "1030-1030")) {
            DateTimeKeys.requireValid(keys(Tag.StudyTime, VR.TM, value));
        }
    }

    @Test
    public void malformedTimesRejected() {
        for (final String value : Arrays.asList(
                "24", "1060", "103061", "1", "103", "10300", "103000.",
                "103000.1234567", "10:30", "1030.5", "120000-080000",
                "1030-10", "-", "*", "10*", "\"\"")) {
            assertRejected(keys(Tag.StudyTime, VR.TM, value), Tag.StudyTime);
        }
    }

    @Test
    public void leapSecondAcceptedOnlyInMinute59() throws DicomServiceException {
        for (final String value : Arrays.asList(
                "235960", "115960", "075960", "235960.5", "235959-235960")) {
            DateTimeKeys.requireValid(keys(Tag.StudyTime, VR.TM, value));
        }
        for (final String value : Arrays.asList(
                "103060", "100060", "052960", "054460", "235961", "080000-103060")) {
            assertRejected(keys(Tag.StudyTime, VR.TM, value), Tag.StudyTime);
        }
    }

    @Test
    public void reversedTimeRangeComparesAtFullPrecision() {
        assertTrue(DateTimeKeys.isValidTimeQueryValue("10-100000.000001"));
        assertFalse(DateTimeKeys.isValidTimeQueryValue("100000.000001-10"));
    }

    // --- Shared behavior ---

    @Test
    public void emptyValuesAreUniversalNotMalformed() throws DicomServiceException {
        DateTimeKeys.requireValid(keys(Tag.StudyDate, VR.DA, ""));
        DateTimeKeys.requireValid(keys(Tag.StudyTime, VR.TM, ""));
        final Attributes absent = new Attributes();
        absent.setNull(Tag.StudyDate, VR.DA);
        DateTimeKeys.requireValid(absent);
    }

    @Test
    public void everyValueOfAMultiValuedKeyIsChecked() throws DicomServiceException {
        DateTimeKeys.requireValid(keys(Tag.StudyDate, VR.DA, "20200615", "20210101-"));
        assertRejected(keys(Tag.StudyDate, VR.DA, "20200615", "202001"), Tag.StudyDate);
    }

    @Test
    public void trailingSpaceIsAccepted() throws DicomServiceException {
        // PS3.5 allows trailing SPACE padding; dcm4che strips it.
        DateTimeKeys.requireValid(keys(Tag.StudyDate, VR.DA, "20200615 "));
        DateTimeKeys.requireValid(keys(Tag.StudyTime, VR.TM, "1030 "));
    }

    @Test
    public void otherVrsAreNotChecked() throws DicomServiceException {
        DateTimeKeys.requireValid(keys(Tag.PatientName, VR.PN, "2020*"));
        DateTimeKeys.requireValid(keys(Tag.StudyDescription, VR.LO, "-"));
    }

    @Test
    public void firstMalformedKeyIsReported() {
        final Attributes keys = keys(Tag.StudyDate, VR.DA, "20200615");
        keys.setString(Tag.StudyTime, VR.TM, "2500");
        assertRejected(keys, Tag.StudyTime);
    }

    private static Attributes keys(final int tag, final VR vr, final String... values) {
        final Attributes keys = new Attributes();
        keys.setString(Tag.QueryRetrieveLevel, VR.CS, "STUDY");
        keys.setString(tag, vr, values);
        return keys;
    }

    private static void assertRejected(final Attributes keys, final int offendingTag) {
        try {
            DateTimeKeys.requireValid(keys);
            fail("expected DicomServiceException for " + keys);
        } catch (final DicomServiceException e) {
            final Attributes rsp = e.mkRSP(Dimse.C_FIND_RSP.commandField(), 1);
            assertEquals(Status.UnableToProcess, rsp.getInt(Tag.Status, -1));
            assertArrayEquals(new int[] {offendingTag}, rsp.getInts(Tag.OffendingElement));
            final String comment = rsp.getString(Tag.ErrorComment);
            assertTrue(comment, comment.length() < 64);
        }
    }
}
