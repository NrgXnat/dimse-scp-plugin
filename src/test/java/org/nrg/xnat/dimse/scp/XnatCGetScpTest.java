package org.nrg.xnat.dimse.scp;

import org.dcm4che3.data.UID;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.service.InstanceLocator;
import org.junit.Test;
import org.nrg.xdat.model.XnatExperimentdataShareI;
import org.nrg.xdat.om.XnatImagesessiondata;
import org.nrg.xdat.om.XnatSubjectassessordata;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link XnatCGetScp} project scoping, transfer syntax
 * negotiation and transcoding decisions.
 */
public class XnatCGetScpTest {

    private static final String CT_SOP_CLASS = UID.CTImageStorage;

    // --- isUncompressed ---

    @Test
    public void implicitVrLeIsUncompressed() {
        assertTrue(XnatCGetScp.isUncompressed(UID.ImplicitVRLittleEndian));
    }

    @Test
    public void explicitVrLeIsUncompressed() {
        assertTrue(XnatCGetScp.isUncompressed(UID.ExplicitVRLittleEndian));
    }

    @Test
    public void explicitVrBeIsUncompressed() {
        assertTrue(XnatCGetScp.isUncompressed(UID.ExplicitVRBigEndian));
    }

    @Test
    public void jpeg2000IsCompressed() {
        assertFalse(XnatCGetScp.isUncompressed(UID.JPEG2000));
    }

    @Test
    public void jpegBaselineIsCompressed() {
        assertFalse(XnatCGetScp.isUncompressed(UID.JPEGBaseline8Bit));
    }

    @Test
    public void jpegLsIsCompressed() {
        assertFalse(XnatCGetScp.isUncompressed(UID.JPEGLSLossless));
    }

    @Test
    public void rleIsCompressed() {
        assertFalse(XnatCGetScp.isUncompressed(UID.RLELossless));
    }

    @Test
    public void nullIsNotUncompressed() {
        assertFalse(XnatCGetScp.isUncompressed(null));
    }

    // --- sessionsInProject ---

    private static final String PROJECT_A = "PROJ_A";
    private static final String PROJECT_B = "PROJ_B";

    @Test
    public void keepsSessionOwnedByProject() {
        final XnatImagesessiondata owned = session(PROJECT_B);

        assertEquals(Collections.singletonList(owned),
                XnatCGetScp.sessionsInProject(PROJECT_B, Stream.of(owned)));
    }

    @Test
    public void keepsSessionSharedIntoProject() {
        final XnatImagesessiondata shared = session(PROJECT_A, PROJECT_B);

        assertEquals(Collections.singletonList(shared),
                XnatCGetScp.sessionsInProject(PROJECT_B, Stream.of(shared)));
    }

    @Test
    public void dropsSessionOwnedByAnotherProjectAndNotShared() {
        final XnatImagesessiondata unshared = session(PROJECT_A);

        assertTrue(XnatCGetScp.sessionsInProject(PROJECT_B, Stream.of(unshared)).isEmpty());
    }

    @Test
    public void dropsSessionSharedOnlyIntoOtherProjects() {
        final XnatImagesessiondata elsewhere = session(PROJECT_A, "PROJ_C");

        assertTrue(XnatCGetScp.sessionsInProject(PROJECT_B, Stream.of(elsewhere)).isEmpty());
    }

    /**
     * Regression for the shared-subject case: a subject owned by A is
     * shared into B along with one of its sessions. Retrieval through
     * B's AE must not return the session that stayed private to A.
     */
    @Test
    public void sharedSubjectReturnsOnlySharedSessions() {
        final XnatImagesessiondata sharedSession = session(PROJECT_A, PROJECT_B);
        final XnatImagesessiondata privateSession = session(PROJECT_A);

        final List<XnatImagesessiondata> result = XnatCGetScp.sessionsInProject(
                PROJECT_B, Stream.of(sharedSession, privateSession));

        assertEquals(Collections.singletonList(sharedSession), result);
    }

    @Test
    public void dropsNonImagingExperiments() {
        final XnatSubjectassessordata assessor = mock(XnatSubjectassessordata.class);

        assertTrue(XnatCGetScp.sessionsInProject(PROJECT_B, Stream.of(assessor)).isEmpty());
    }

    // --- negotiatedTransferSyntax ---

    @Test
    public void prefersNativeTransferSyntax() {
        final Association as = mockAssociation(CT_SOP_CLASS,
                UID.ExplicitVRLittleEndian, UID.ImplicitVRLittleEndian);
        final InstanceLocator inst = new InstanceLocator(
                CT_SOP_CLASS, "1.2.3", UID.ExplicitVRLittleEndian, "file:test.dcm");

        assertEquals(UID.ExplicitVRLittleEndian,
                XnatCGetScp.negotiatedTransferSyntax(as, inst));
    }

    @Test
    public void fallsBackToExplicitVrLeFirst() {
        final Association as = mockAssociation(CT_SOP_CLASS,
                UID.ExplicitVRLittleEndian, UID.ImplicitVRLittleEndian);
        final InstanceLocator inst = new InstanceLocator(
                CT_SOP_CLASS, "1.2.3", UID.JPEG2000, "file:test.dcm");

        assertEquals(UID.ExplicitVRLittleEndian,
                XnatCGetScp.negotiatedTransferSyntax(as, inst));
    }

    @Test
    public void fallsBackToImplicitVrLeWhenExplicitNotAvailable() {
        final Association as = mockAssociation(CT_SOP_CLASS,
                UID.ImplicitVRLittleEndian);
        final InstanceLocator inst = new InstanceLocator(
                CT_SOP_CLASS, "1.2.3", UID.ExplicitVRLittleEndian, "file:test.dcm");

        assertEquals(UID.ImplicitVRLittleEndian,
                XnatCGetScp.negotiatedTransferSyntax(as, inst));
    }

    @Test
    public void fallsBackToExplicitVrBeAsLastResort() {
        final Association as = mockAssociation(CT_SOP_CLASS,
                UID.ExplicitVRBigEndian);
        final InstanceLocator inst = new InstanceLocator(
                CT_SOP_CLASS, "1.2.3", UID.JPEG2000, "file:test.dcm");

        assertEquals(UID.ExplicitVRBigEndian,
                XnatCGetScp.negotiatedTransferSyntax(as, inst));
    }

    @Test
    public void returnsNativeTsWhenNothingNegotiated() {
        final Association as = mockAssociation(CT_SOP_CLASS);
        final InstanceLocator inst = new InstanceLocator(
                CT_SOP_CLASS, "1.2.3", UID.JPEG2000, "file:test.dcm");

        assertEquals(UID.JPEG2000,
                XnatCGetScp.negotiatedTransferSyntax(as, inst));
    }

    @Test
    public void nativeCompressedPreferredOverUncompressedFallback() {
        final Association as = mockAssociation(CT_SOP_CLASS,
                UID.JPEG2000, UID.ExplicitVRLittleEndian);
        final InstanceLocator inst = new InstanceLocator(
                CT_SOP_CLASS, "1.2.3", UID.JPEG2000, "file:test.dcm");

        assertEquals(UID.JPEG2000,
                XnatCGetScp.negotiatedTransferSyntax(as, inst));
    }

    /**
     * Creates a session that runs the real
     * {@code BaseXnatExperimentdata.hasProject} scope check against the
     * given owning project and share list.
     */
    private static XnatImagesessiondata session(final String owningProject,
                                                final String... sharedInto) {
        final XnatImagesessiondata session = mock(XnatImagesessiondata.class, CALLS_REAL_METHODS);
        doReturn(owningProject).when(session).getProject();
        final List<XnatExperimentdataShareI> shares = Arrays.stream(sharedInto)
                .map(project -> {
                    final XnatExperimentdataShareI share = mock(XnatExperimentdataShareI.class);
                    when(share.getProject()).thenReturn(project);
                    return share;
                })
                .collect(Collectors.toList());
        doReturn(shares).when(session).getSharing_share();
        return session;
    }

    private static Association mockAssociation(final String sopClassUid,
                                               final String... transferSyntaxes) {
        final Set<String> tsSet = new HashSet<>();
        Collections.addAll(tsSet, transferSyntaxes);
        final Association as = mock(Association.class);
        when(as.getTransferSyntaxesFor(sopClassUid)).thenReturn(tsSet);
        return as;
    }
}
