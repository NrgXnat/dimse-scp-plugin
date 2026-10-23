package org.nrg.xnat.dimse.query.impl;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.service.DicomServiceException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.nrg.xdat.om.XnatProjectdata;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link XnatDicomQueryService}: a failed database query is
 * reported as C000H, not as an empty result, and a Patient Root query
 * below STUDY level matches only under the patient it names.
 */
public class XnatDicomQueryServiceTest {

    private static final String PROJECT_ID = "TestProject";
    private static final String STUDY_UID = "1.2.3";
    private static final String SERIES_UID = "1.2.3.4";
    private static final DataAccessResourceFailureException CAUSE =
            new DataAccessResourceFailureException("connection refused");

    private DimseDataAccess dataAccess;
    private XnatDicomQueryService service;
    private XnatProjectdata project;

    @Before
    public void setUp() {
        dataAccess = mock(DimseDataAccess.class);
        service = new XnatDicomQueryService(dataAccess);
        project = mock(XnatProjectdata.class);
        when(project.getId()).thenReturn(PROJECT_ID);
    }

    @Test
    public void studyQueryFailureIsUnableToProcess() {
        when(dataAccess.searchStudies(eq(PROJECT_ID), any())).thenThrow(CAUSE);
        assertUnableToProcess(() -> service.findStudies(project, new Attributes(), null));
    }

    @Test
    public void seriesQueryFailureIsUnableToProcess() {
        when(dataAccess.searchSeries(eq(PROJECT_ID), anyString(), any())).thenThrow(CAUSE);
        final Attributes keys = new Attributes();
        keys.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3");
        assertUnableToProcess(() -> service.findSeries(project, keys, null));
    }

    @Test
    public void patientQueryFailureIsUnableToProcess() {
        when(dataAccess.searchPatients(eq(PROJECT_ID), any())).thenThrow(CAUSE);
        assertUnableToProcess(() -> service.findPatients(project, new Attributes(), null));
    }

    @Test
    public void patientRootSeriesReturnsStudysPatientId() throws DicomServiceException {
        when(dataAccess.searchStudies(eq(PROJECT_ID), any())).thenReturn(Stream.of(study("SUBJ001")));
        when(dataAccess.searchSeries(eq(PROJECT_ID), eq(STUDY_UID), any())).thenReturn(Stream.of(series()));

        final List<Attributes> matches = service.findSeries(project, seriesKeys("SUBJ*"), null).toList();

        assertEquals(1, matches.size());
        assertEquals("SUBJ001", matches.get(0).getString(Tag.PatientID));
        final ArgumentCaptor<Attributes> studyKeys = ArgumentCaptor.forClass(Attributes.class);
        verify(dataAccess).searchStudies(eq(PROJECT_ID), studyKeys.capture());
        assertEquals(STUDY_UID, studyKeys.getValue().getString(Tag.StudyInstanceUID));
        assertEquals("SUBJ*", studyKeys.getValue().getString(Tag.PatientID));
    }

    @Test
    public void patientRootSeriesOfOtherPatientMatchesNothing() throws DicomServiceException {
        when(dataAccess.searchStudies(eq(PROJECT_ID), any())).thenReturn(Stream.empty());

        assertEquals(0, service.findSeries(project, seriesKeys("OTHER"), null).count());
        verify(dataAccess, never()).searchSeries(any(), any(), any());
    }

    @Test
    public void patientRootImageOfOtherPatientMatchesNothing() throws DicomServiceException {
        when(dataAccess.searchStudies(eq(PROJECT_ID), any())).thenReturn(Stream.empty());
        final Attributes keys = seriesKeys("OTHER");
        keys.setString(Tag.SeriesInstanceUID, VR.UI, SERIES_UID);

        // Returns before the session lookup, which needs a running XNAT.
        assertEquals(0, service.findInstances(project, keys, null).count());
    }

    @Test
    public void seriesWithoutPatientIdSkipsStudyLookup() throws DicomServiceException {
        when(dataAccess.searchSeries(eq(PROJECT_ID), eq(STUDY_UID), any())).thenReturn(Stream.of(series()));
        final Attributes keys = new Attributes();
        keys.setString(Tag.StudyInstanceUID, VR.UI, STUDY_UID);

        assertEquals(1, service.findSeries(project, keys, null).count());
        verify(dataAccess, never()).searchStudies(any(), any());
    }

    @Test
    public void patientLookupFailureIsUnableToProcess() {
        when(dataAccess.searchStudies(eq(PROJECT_ID), any())).thenThrow(CAUSE);
        assertUnableToProcess(() -> service.findSeries(project, seriesKeys("SUBJ001"), null));
    }

    private static Attributes seriesKeys(final String patientId) {
        final Attributes keys = new Attributes();
        keys.setString(Tag.PatientID, VR.LO, patientId);
        keys.setString(Tag.StudyInstanceUID, VR.UI, STUDY_UID);
        return keys;
    }

    private static Attributes study(final String patientId) {
        final Attributes study = new Attributes();
        study.setString(Tag.StudyInstanceUID, VR.UI, STUDY_UID);
        study.setString(Tag.PatientID, VR.LO, patientId);
        return study;
    }

    private static Attributes series() {
        final Attributes series = new Attributes();
        series.setString(Tag.StudyInstanceUID, VR.UI, STUDY_UID);
        series.setString(Tag.SeriesInstanceUID, VR.UI, SERIES_UID);
        return series;
    }

    private interface Find {
        Stream<Attributes> run() throws DicomServiceException;
    }

    private static void assertUnableToProcess(final Find find) {
        try {
            find.run();
            fail("expected DicomServiceException");
        } catch (final DicomServiceException e) {
            assertEquals(Status.UnableToProcess, e.getStatus());
            assertSame(CAUSE, e.getCause());
            // The cause is logged, not sent to the SCU.
            assertFalse(e.getMessage().contains("connection refused"));
        }
    }
}
