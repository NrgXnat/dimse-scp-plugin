package org.nrg.xnat.dimse.query;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.service.DicomServiceException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link QueryKeys}.
 */
public class QueryKeysTest {

    private static final String PATIENT_ROOT = UID.PatientRootQueryRetrieveInformationModelFind;
    private static final String STUDY_ROOT = UID.StudyRootQueryRetrieveInformationModelFind;

    @Test
    public void seriesKeyIsIgnoredAtStudyLevel() throws DicomServiceException {
        final Attributes keys = keys("STUDY");
        keys.setString(Tag.Modality, VR.CS, "CT");
        keys.setString(Tag.ModalitiesInStudy, VR.CS, "CT");

        final Attributes selected = QueryKeys.select(STUDY_ROOT, QueryLevel.STUDY, keys);

        assertFalse(selected.contains(Tag.Modality));
        assertEquals("CT", selected.getString(Tag.ModalitiesInStudy));
    }

    @Test
    public void unsupportedKeyIsIgnored() throws DicomServiceException {
        final Attributes keys = keys("STUDY");
        keys.setNull(Tag.SOPClassesInStudy, VR.UI);
        keys.setString(Tag.TimezoneOffsetFromUTC, VR.SH, "+0100");

        final Attributes selected = QueryKeys.select(STUDY_ROOT, QueryLevel.STUDY, keys);

        assertFalse(selected.contains(Tag.SOPClassesInStudy));
        assertFalse(selected.contains(Tag.TimezoneOffsetFromUTC));
    }

    @Test
    public void controlAttributesAreKept() throws DicomServiceException {
        final Attributes keys = keys("SERIES");
        keys.setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 100");

        final Attributes selected = QueryKeys.select(STUDY_ROOT, QueryLevel.SERIES, keys);

        assertEquals("SERIES", selected.getString(Tag.QueryRetrieveLevel));
        assertEquals("ISO_IR 100", selected.getString(Tag.SpecificCharacterSet));
    }

    @Test
    public void studyRootStudyLevelHasPatientKeys() throws DicomServiceException {
        final Attributes selected = QueryKeys.select(STUDY_ROOT, QueryLevel.STUDY, patientKeys("STUDY"));

        assertEquals("SMITH", selected.getString(Tag.PatientName));
        assertEquals("SUBJ001", selected.getString(Tag.PatientID));
        assertEquals("M", selected.getString(Tag.PatientSex));
    }

    @Test
    public void patientRootStudyLevelKeepsOnlyPatientId() throws DicomServiceException {
        final Attributes selected = QueryKeys.select(PATIENT_ROOT, QueryLevel.STUDY, patientKeys("STUDY"));

        assertEquals("SUBJ001", selected.getString(Tag.PatientID));
        assertFalse(selected.contains(Tag.PatientName));
        assertFalse(selected.contains(Tag.PatientSex));
    }

    @Test
    public void uniqueKeysAboveAreKept() throws DicomServiceException {
        final Attributes keys = patientKeys("IMAGE");
        keys.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3");
        keys.setString(Tag.SeriesInstanceUID, VR.UI, "1.2.3.4");
        keys.setString(Tag.StudyDate, VR.DA, "20200101");

        final Attributes selected = QueryKeys.select(PATIENT_ROOT, QueryLevel.IMAGE, keys);

        assertEquals("SUBJ001", selected.getString(Tag.PatientID));
        assertEquals("1.2.3", selected.getString(Tag.StudyInstanceUID));
        assertEquals("1.2.3.4", selected.getString(Tag.SeriesInstanceUID));
        assertFalse(selected.contains(Tag.StudyDate));
        assertFalse(selected.contains(Tag.PatientName));
    }

    @Test
    public void studyRootHasNoPatientAboveSeries() throws DicomServiceException {
        final Attributes keys = patientKeys("SERIES");
        keys.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3");

        final Attributes selected = QueryKeys.select(STUDY_ROOT, QueryLevel.SERIES, keys);

        assertTrue(selected.contains(Tag.StudyInstanceUID));
        assertFalse(selected.contains(Tag.PatientID));
    }

    @Test
    public void patientLevelIsKeptInPatientRoot() throws DicomServiceException {
        final Attributes selected = QueryKeys.select(PATIENT_ROOT, QueryLevel.PATIENT, patientKeys("PATIENT"));

        assertEquals("SMITH", selected.getString(Tag.PatientName));
        assertEquals("SUBJ001", selected.getString(Tag.PatientID));
    }

    @Test
    public void studyRootHasNoPatientLevel() {
        try {
            QueryKeys.select(STUDY_ROOT, QueryLevel.PATIENT, patientKeys("PATIENT"));
            fail("expected DicomServiceException");
        } catch (final DicomServiceException e) {
            assertEquals(Status.IdentifierDoesNotMatchSOPClass, e.getStatus());
        }
    }

    @Test
    public void supportedTagsAreSorted() {
        for (final QueryLevel level : QueryLevel.values()) {
            for (final boolean patientRoot : new boolean[] {true, false}) {
                final int[] tags = QueryKeys.supported(patientRoot, level);
                for (int i = 1; i < tags.length; i++) {
                    assertTrue(level + " tags sorted", Integer.compareUnsigned(tags[i - 1], tags[i]) < 0);
                }
            }
        }
    }

    private static Attributes keys(final String level) {
        final Attributes keys = new Attributes();
        keys.setString(Tag.QueryRetrieveLevel, VR.CS, level);
        return keys;
    }

    private static Attributes patientKeys(final String level) {
        final Attributes keys = keys(level);
        keys.setString(Tag.PatientName, VR.PN, "SMITH");
        keys.setString(Tag.PatientID, VR.LO, "SUBJ001");
        keys.setString(Tag.PatientSex, VR.CS, "M");
        return keys;
    }
}
