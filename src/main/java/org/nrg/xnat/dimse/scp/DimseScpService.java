package org.nrg.xnat.dimse.scp;

import lombok.extern.slf4j.Slf4j;
import org.dcm4che3.data.UID;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.TransferCapability;
import org.dcm4che3.net.service.BasicCEchoSCP;
import org.dcm4che3.net.service.DicomServiceRegistry;
import org.nrg.xnat.dimse.query.DicomQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Manages the lifecycle of the DIMSE SCP (C-ECHO, C-FIND, C-GET).
 *
 * <p>The SCP registers a single wildcard {@link ApplicationEntity}
 * (AE title {@code "*"}) so that incoming associations addressed to
 * any Called AE Title are accepted. The actual Called AE Title is
 * resolved to an XNAT project at request time by
 * {@link ProjectAeResolver}.</p>
 */
@Slf4j
@Service
public class DimseScpService {
    private static final String DEVICE_NAME = "XNAT_DIMSE";
    private static final int    DEFAULT_PORT = 9104;
    private static final String[] TRANSFER_SYNTAXES = {
        UID.ImplicitVRLittleEndian,
        UID.ExplicitVRLittleEndian,
        UID.ExplicitVRBigEndian,
    };

    /**
     * Storage SOP Class UIDs for C-GET sub-operation negotiation.
     *
     * <p>When an SCU opens a C-GET association, it proposes Storage
     * presentation contexts so the SCP can send instances back via
     * C-STORE sub-operations. The SCP must have transfer capabilities
     * registered for these SOP classes to accept the proposals.</p>
     *
     * <p>This list matches the SOP classes accepted by XNAT's
     * C-STORE SCP ({@code org.nrg.dcm.scp.CStoreService}), so any
     * data XNAT can archive can also be retrieved via C-GET.</p>
     */
    private static final String[] STORAGE_SOP_CLASSES = {
        // CR / DX / Mammography / Intra-oral
        UID.ComputedRadiographyImageStorage,
        UID.DigitalXRayImageStorageForPresentation,
        UID.DigitalXRayImageStorageForProcessing,
        UID.DigitalMammographyXRayImageStorageForPresentation,
        UID.DigitalMammographyXRayImageStorageForProcessing,
        UID.DigitalIntraOralXRayImageStorageForPresentation,
        UID.DigitalIntraOralXRayImageStorageForProcessing,

        // CT
        UID.CTImageStorage,
        UID.EnhancedCTImageStorage,
        UID.LegacyConvertedEnhancedCTImageStorage,

        // MR
        UID.MRImageStorage,
        UID.EnhancedMRImageStorage,
        UID.MRSpectroscopyStorage,
        UID.EnhancedMRColorImageStorage,
        UID.LegacyConvertedEnhancedMRImageStorage,

        // US
        UID.UltrasoundImageStorage,
        UID.UltrasoundMultiFrameImageStorage,
        UID.EnhancedUSVolumeStorage,

        // NM / PET
        UID.NuclearMedicineImageStorage,
        UID.PositronEmissionTomographyImageStorage,
        UID.EnhancedPETImageStorage,
        UID.LegacyConvertedEnhancedPETImageStorage,

        // XA / XRF
        UID.XRayAngiographicImageStorage,
        UID.EnhancedXAImageStorage,
        UID.XRayRadiofluoroscopicImageStorage,
        UID.EnhancedXRFImageStorage,
        UID.XRay3DAngiographicImageStorage,
        UID.XRay3DCraniofacialImageStorage,
        UID.BreastTomosynthesisImageStorage,

        // Secondary Capture
        UID.SecondaryCaptureImageStorage,
        UID.MultiFrameSingleBitSecondaryCaptureImageStorage,
        UID.MultiFrameGrayscaleByteSecondaryCaptureImageStorage,
        UID.MultiFrameGrayscaleWordSecondaryCaptureImageStorage,
        UID.MultiFrameTrueColorSecondaryCaptureImageStorage,

        // VL / Microscopy
        UID.VLEndoscopicImageStorage,
        UID.VideoEndoscopicImageStorage,
        UID.VLMicroscopicImageStorage,
        UID.VideoMicroscopicImageStorage,
        UID.VLSlideCoordinatesMicroscopicImageStorage,
        UID.VLPhotographicImageStorage,
        UID.VideoPhotographicImageStorage,
        UID.VLWholeSlideMicroscopyImageStorage,

        // Ophthalmology
        UID.OphthalmicPhotography8BitImageStorage,
        UID.OphthalmicPhotography16BitImageStorage,
        UID.OphthalmicTomographyImageStorage,
        UID.StereometricRelationshipStorage,

        // Waveforms
        UID.TwelveLeadECGWaveformStorage,
        UID.GeneralECGWaveformStorage,
        UID.AmbulatoryECGWaveformStorage,
        UID.HemodynamicWaveformStorage,
        UID.CardiacElectrophysiologyWaveformStorage,
        UID.BasicVoiceAudioWaveformStorage,

        // Presentation State
        UID.GrayscaleSoftcopyPresentationStateStorage,
        UID.ColorSoftcopyPresentationStateStorage,
        UID.PseudoColorSoftcopyPresentationStateStorage,
        UID.BlendingSoftcopyPresentationStateStorage,

        // Structured Reports
        UID.BasicTextSRStorage,
        UID.EnhancedSRStorage,
        UID.ComprehensiveSRStorage,
        UID.Comprehensive3DSRStorage,
        UID.ProcedureLogStorage,
        UID.MammographyCADSRStorage,
        UID.KeyObjectSelectionDocumentStorage,
        UID.ChestCADSRStorage,
        UID.XRayRadiationDoseSRStorage,

        // RT
        UID.RTImageStorage,
        UID.RTDoseStorage,
        UID.RTStructureSetStorage,
        UID.RTBeamsTreatmentRecordStorage,
        UID.RTPlanStorage,
        UID.RTBrachyTreatmentRecordStorage,
        UID.RTTreatmentSummaryRecordStorage,
        UID.RTIonPlanStorage,
        UID.RTIonBeamsTreatmentRecordStorage,

        // Segmentation / Registration / Mapping
        UID.SegmentationStorage,
        UID.SpatialRegistrationStorage,
        UID.SpatialFiducialsStorage,
        UID.DeformableSpatialRegistrationStorage,
        UID.RealWorldValueMappingStorage,
        UID.ParametricMapStorage,

        // Other
        UID.RawDataStorage,
        UID.EncapsulatedPDFStorage,
        UID.EncapsulatedCDAStorage,

        // Vendor-specific (commonly seen in XNAT archives)
        UID.PrivateSiemensCSANonImageStorage,
    };

    private final ProjectAeResolver  _projectAeResolver;
    private final DicomQueryService  _queryService;
    private Device                   _device;
    private ExecutorService          _executor;
    private ScheduledExecutorService _scheduledExecutor;

    /**
     * Creates the DIMSE SCP service.
     *
     * @param projectAeResolver resolves Called AE Title to XNAT project
     * @param queryService      executes DICOM queries against XNAT data
     */
    @Autowired
    public DimseScpService(final ProjectAeResolver projectAeResolver,
                           final DicomQueryService queryService) {
        _projectAeResolver = projectAeResolver;
        _queryService = queryService;
    }

    private Function<String, TransferCapability> makeTransferCapability(final TransferCapability.Role role) {
        return sopClassUid -> new TransferCapability(null, sopClassUid, role, TRANSFER_SYNTAXES);
    }

    /**
     * Starts the DIMSE SCP on the given port.
     *
     * @param port the TCP port to listen on
     * @throws IOException              if the port cannot be bound
     * @throws GeneralSecurityException if TLS setup fails
     */
    public synchronized void start(final int port)
            throws IOException, GeneralSecurityException {
        if (_device != null) {
            log.warn("DIMSE SCP is already running; stop it first");
            return;
        }

        _executor = Executors.newCachedThreadPool();
        _scheduledExecutor = Executors.newSingleThreadScheduledExecutor();

        final Connection conn = new Connection();
        conn.setPort(port);

        final DicomServiceRegistry serviceRegistry = createServiceRegistry();

        final ApplicationEntity ae = new ApplicationEntity("*");
        ae.setAssociationAcceptor(true);
        ae.addConnection(conn);
        Stream.of(UID.Verification,
                        UID.StudyRootQueryRetrieveInformationModelFind,
                        UID.StudyRootQueryRetrieveInformationModelGet,
                        UID.PatientRootQueryRetrieveInformationModelFind,
                        UID.PatientRootQueryRetrieveInformationModelGet)
                .map(makeTransferCapability(TransferCapability.Role.SCP))
                .forEach(ae::addTransferCapability);
        Arrays.stream(STORAGE_SOP_CLASSES)
                .map(makeTransferCapability(TransferCapability.Role.SCU))
                .forEach(ae::addTransferCapability);

        _device = new Device(DEVICE_NAME);
        _device.addConnection(conn);
        _device.addApplicationEntity(ae);
        _device.setDimseRQHandler(serviceRegistry);
        _device.setExecutor(_executor);
        _device.setScheduledExecutor(_scheduledExecutor);

        _device.bindConnections();
        log.info("DIMSE SCP started on port {} (wildcard AE)", port);
    }

    /**
     * Starts the DIMSE SCP on the default port.
     *
     * @throws IOException              if the port cannot be bound
     * @throws GeneralSecurityException if TLS setup fails
     */
    public void start() throws IOException, GeneralSecurityException {
        start(DEFAULT_PORT);
    }

    /**
     * Stops the DIMSE SCP and releases resources.
     */
    public synchronized void stop() {
        if (_device == null) {
            log.debug("DIMSE SCP is not running");
            return;
        }
        _device.unbindConnections();
        _device = null;

        _executor.shutdownNow();
        _executor = null;

        _scheduledExecutor.shutdownNow();
        _scheduledExecutor = null;

        log.info("DIMSE SCP stopped");
    }

    /**
     * Returns whether the SCP is currently running.
     *
     * @return true if the SCP is listening for associations
     */
    public synchronized boolean isRunning() {
        return _device != null;
    }

    /**
     * Ensures the SCP is stopped when the Spring context is destroyed.
     */
    @PreDestroy
    public void destroy() {
        stop();
    }

    private DicomServiceRegistry createServiceRegistry() {
        final DicomServiceRegistry registry = new DicomServiceRegistry();
        registry.addDicomService(new BasicCEchoSCP());
        registry.addDicomService(new XnatCFindScp(_projectAeResolver, _queryService));
        registry.addDicomService(new XnatCGetScp(_projectAeResolver));
        return registry;
    }
}
