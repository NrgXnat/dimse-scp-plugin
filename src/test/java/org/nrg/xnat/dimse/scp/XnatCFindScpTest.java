package org.nrg.xnat.dimse.scp;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.DicomServiceException;
import org.dcm4che3.net.service.QueryTask;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.dimse.query.DicomQueryService;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link XnatCFindScp} query level routing.
 */
public class XnatCFindScpTest {

    private static final String PROJECT_ID = "TestProject";
    private static final String CALLED_AET = PROJECT_ID;

    private ProjectAeResolver projectAeResolver;
    private DicomQueryService queryService;
    private XnatCFindScp scp;

    private Association association;
    private PresentationContext pc;
    private Attributes rq;
    private XnatProjectdata project;
    private UserI user;

    @Before
    public void setUp() {
        projectAeResolver = mock(ProjectAeResolver.class);
        queryService = mock(DicomQueryService.class);
        scp = new XnatCFindScp(projectAeResolver, queryService);

        association = mock(Association.class);
        when(association.getCalledAET()).thenReturn(CALLED_AET);
        when(association.getCallingAET()).thenReturn("TEST_SCU");

        pc = new PresentationContext(1,
                UID.PatientRootQueryRetrieveInformationModelFind,
                UID.ImplicitVRLittleEndian);
        rq = new Attributes();

        project = mock(XnatProjectdata.class);
        when(project.getId()).thenReturn(PROJECT_ID);

        user = mock(UserI.class);

        when(projectAeResolver.resolve(CALLED_AET)).thenReturn(Optional.of(project));
        when(projectAeResolver.getUser()).thenReturn(user);
    }

    @Test
    public void patientLevelRoutesFindPatients() throws DicomServiceException {
        final Attributes result = new Attributes();
        result.setString(Tag.PatientID, VR.LO, "SUBJ001");
        when(queryService.findPatients(eq(project), any(), eq(user)))
                .thenReturn(Stream.of(result));

        final Attributes keys = keysWithLevel("PATIENT");
        keys.setString(Tag.PatientID, VR.LO, "");

        final QueryTask task = scp.calculateMatches(association, pc, rq, keys);
        assertNotNull(task);

        verify(queryService).findPatients(eq(project), any(), eq(user));
    }

    @Test
    public void studyLevelRoutesFindStudies() throws DicomServiceException {
        when(queryService.findStudies(eq(project), any(), eq(user)))
                .thenReturn(Stream.empty());

        final QueryTask task = scp.calculateMatches(association, pc, rq,
                keysWithLevel("STUDY"));
        assertNotNull(task);

        verify(queryService).findStudies(eq(project), any(), eq(user));
    }

    @Test
    public void seriesLevelRoutesFindSeries() throws DicomServiceException {
        when(queryService.findSeries(eq(project), any(), eq(user)))
                .thenReturn(Stream.empty());

        final QueryTask task = scp.calculateMatches(association, pc, rq,
                keysWithLevel("SERIES"));
        assertNotNull(task);

        verify(queryService).findSeries(eq(project), any(), eq(user));
    }

    @Test
    public void imageLevelRoutesFindInstances() throws DicomServiceException {
        when(queryService.findInstances(eq(project), any(), eq(user)))
                .thenReturn(Stream.empty());

        final QueryTask task = scp.calculateMatches(association, pc, rq,
                keysWithLevel("IMAGE"));
        assertNotNull(task);

        verify(queryService).findInstances(eq(project), any(), eq(user));
    }

    @Test(expected = DicomServiceException.class)
    public void unknownAeTitleThrows() throws DicomServiceException {
        when(projectAeResolver.resolve("UNKNOWN")).thenReturn(Optional.empty());
        when(association.getCalledAET()).thenReturn("UNKNOWN");

        scp.calculateMatches(association, pc, rq, keysWithLevel("PATIENT"));
    }

    @Test(expected = DicomServiceException.class)
    public void missingQueryLevelThrows() throws DicomServiceException {
        scp.calculateMatches(association, pc, rq, new Attributes());
    }

    @Test
    public void studyUidListAboveSeriesLevelIsRejected() throws DicomServiceException {
        final Attributes keys = keysWithLevel("SERIES");
        keys.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3", "4.5.6");
        try {
            scp.calculateMatches(association, pc, rq, keys);
            fail("expected DicomServiceException");
        } catch (final DicomServiceException e) {
            assertEquals(Status.UnableToProcess, e.getStatus());
        }
        verify(queryService, never()).findSeries(any(), any(), any());
    }

    @Test
    public void malformedStudyDateIsRejected() throws DicomServiceException {
        final Attributes keys = keysWithLevel("STUDY");
        keys.setString(Tag.StudyDate, VR.DA, "202001");
        try {
            scp.calculateMatches(association, pc, rq, keys);
            fail("expected DicomServiceException");
        } catch (final DicomServiceException e) {
            assertEquals(Status.UnableToProcess, e.getStatus());
        }
        verify(queryService, never()).findStudies(any(), any(), any());
    }

    @Test
    public void queryFailureIsPassedOn() throws DicomServiceException {
        final DicomServiceException failure =
                new DicomServiceException(Status.UnableToProcess, "Query failed");
        when(queryService.findStudies(eq(project), any(), eq(user))).thenThrow(failure);
        try {
            scp.calculateMatches(association, pc, rq, keysWithLevel("STUDY"));
            fail("expected DicomServiceException");
        } catch (final DicomServiceException e) {
            assertSame(failure, e);
        }
    }

    @Test
    public void unexpectedFailureIsUnableToProcess() throws DicomServiceException {
        when(queryService.findStudies(eq(project), any(), eq(user)))
                .thenThrow(new IllegalStateException("bug detail"));
        try {
            scp.calculateMatches(association, pc, rq, keysWithLevel("STUDY"));
            fail("expected DicomServiceException");
        } catch (final DicomServiceException e) {
            assertEquals(Status.UnableToProcess, e.getStatus());
            // The cause is logged, not sent to the SCU.
            assertFalse(e.getMessage().contains("bug detail"));
        }
    }

    @Test
    public void unexpectedFailureWhileIteratingIsUnableToProcess() throws Exception {
        final Attributes first = new Attributes();
        first.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3");
        when(queryService.findStudies(eq(project), any(), eq(user)))
                .thenReturn(Stream.concat(Stream.of(first), Stream.generate(() -> {
                    throw new IllegalStateException("bug detail");
                })));

        scp.calculateMatches(association, pc, rq, keysWithLevel("STUDY")).run();

        final ArgumentCaptor<Attributes> cmd = ArgumentCaptor.forClass(Attributes.class);
        verify(association, atLeastOnce()).writeDimseRSP(eq(pc), cmd.capture(), any());
        final List<Attributes> rsps = cmd.getAllValues();
        assertEquals(2, rsps.size());
        assertEquals(Status.Pending, rsps.get(0).getInt(Tag.Status, -1));
        assertEquals(Status.UnableToProcess, rsps.get(1).getInt(Tag.Status, -1));
    }

    private static Attributes keysWithLevel(final String level) {
        final Attributes keys = new Attributes();
        keys.setString(Tag.QueryRetrieveLevel, VR.CS, level);
        return keys;
    }
}
