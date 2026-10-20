package org.nrg.xnat.dimse.query.impl;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.service.DicomServiceException;
import org.junit.Before;
import org.junit.Test;
import org.nrg.xdat.om.XnatProjectdata;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests that {@link XnatDicomQueryService} reports a failed database
 * query as C000H, not as an empty result.
 */
public class XnatDicomQueryServiceTest {

    private static final String PROJECT_ID = "TestProject";
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
