package org.nrg.xnat.dimse.scp;

import org.dcm4che3.data.UID;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.service.InstanceLocator;
import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link XnatCGetScp} transfer syntax negotiation
 * and transcoding decisions.
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

    private static Association mockAssociation(final String sopClassUid,
                                               final String... transferSyntaxes) {
        final Set<String> tsSet = new HashSet<>();
        Collections.addAll(tsSet, transferSyntaxes);
        final Association as = mock(Association.class);
        when(as.getTransferSyntaxesFor(sopClassUid)).thenReturn(tsSet);
        return as;
    }
}
