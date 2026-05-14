package org.nrg.xnat.dimse.query;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.dcm4che3.net.service.DicomServiceException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

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

    private static Attributes keysWithLevel(final String level) {
        final Attributes keys = new Attributes();
        keys.setString(Tag.QueryRetrieveLevel, VR.CS, level);
        return keys;
    }
}
