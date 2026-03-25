package org.nrg.xnat.dimse.scp;

import lombok.extern.slf4j.Slf4j;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.DataWriter;
import org.dcm4che3.net.DataWriterAdapter;
import org.dcm4che3.net.Dimse;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.BasicCGetSCP;
import org.dcm4che3.net.service.BasicRetrieveTask;
import org.dcm4che3.net.service.DicomServiceException;
import org.dcm4che3.net.service.InstanceLocator;
import org.dcm4che3.net.service.RetrieveTask;
import org.nrg.action.ServerException;
import org.nrg.dcm.FileInstanceLocator;
import org.nrg.xdat.bean.CatDcmentryBean;
import org.nrg.xdat.model.XnatImagescandataI;
import org.nrg.xdat.om.XnatImagesessiondata;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.om.XnatSubjectdata;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.dimse.CatalogEntryUtils;
import org.nrg.xnat.utils.CatalogUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * DICOM C-GET SCP for the Study Root and Patient Root
 * Query/Retrieve Information Models.
 *
 * <p>Handles incoming C-GET requests by resolving the Called AE Title
 * to an XNAT project, locating matching DICOM instances in the
 * archive, and sending them back to the SCU via C-STORE
 * sub-operations on the same association.</p>
 */
@Slf4j
public class XnatCGetScp extends BasicCGetSCP {

    private final ProjectAeResolver _projectAeResolver;

    /**
     * Creates a new C-GET SCP for Study Root and Patient Root queries.
     *
     * @param projectAeResolver resolves Called AE Title to XNAT project
     */
    public XnatCGetScp(final ProjectAeResolver projectAeResolver) {
        super(UID.StudyRootQueryRetrieveInformationModelGet,
              UID.PatientRootQueryRetrieveInformationModelGet);
        _projectAeResolver = projectAeResolver;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected RetrieveTask calculateMatches(final Association as,
                                            final PresentationContext pc,
                                            final Attributes rq,
                                            final Attributes keys)
            throws DicomServiceException {
        final String calledAet = as.getCalledAET();
        final String callingAet = as.getCallingAET();
        final String queryLevel = keys.getString(Tag.QueryRetrieveLevel);

        log.debug("C-GET from {} to AE '{}', level={}", callingAet, calledAet, queryLevel);

        final XnatProjectdata project = _projectAeResolver.resolve(calledAet)
            .orElseThrow(() -> {
                log.debug("C-GET rejected: no project for AE title '{}'", calledAet);
                return new DicomServiceException(Status.UnableToProcess, "Unknown AE title: " + calledAet);
            });

        log.debug("C-GET retrieving from project '{}' at level {}", project.getId(), queryLevel);

        final List<InstanceLocator> matches = getInstances(project.getId(), keys);

        return new BasicRetrieveTask<InstanceLocator>(Dimse.C_GET_RQ, as, pc, rq, matches, as) {
            @Override
            protected String selectTransferSyntaxFor(final Association storeas, final InstanceLocator inst) {
                return negotiatedTransferSyntax(storeas, inst);
            }

            @Override
            protected DataWriter createDataWriter(final InstanceLocator inst, final String tsuid) throws Exception {
                if (!tsuid.equals(inst.tsuid) && isUncompressed(tsuid) && isUncompressed(inst.tsuid)) {
                    log.debug("Transcoding {} from {} to {}", inst.iuid, inst.tsuid, tsuid);
                    try (DicomInputStream din = new DicomInputStream(inst.getFile())) {
                        din.readFileMetaInformation();
                        return new DataWriterAdapter(din.readDataset(-1));
                    }
                }
                return super.createDataWriter(inst, tsuid);
            }
        };
    }

    /**
     * Locates all DICOM instances matching the C-GET identifier keys.
     *
     * @param projectId the XNAT project scoped by the Called AE Title
     * @param keys      the C-GET Identifier (retrieval keys)
     * @return locators for matching DICOM instances on disk
     * @throws DicomServiceException if required keys are missing
     */
    private List<InstanceLocator> getInstances(final String projectId, final Attributes keys)
            throws DicomServiceException {
        final UserI user = _projectAeResolver.getUser();
        final List<XnatImagesessiondata> sessions = findSessions(projectId, keys, user);

        final Set<String> seriesUidKeys = getStringsAsSet(keys, Tag.SeriesInstanceUID);
        final Predicate<? super XnatImagescandataI> matchesSeriesKeys =
                scan -> seriesUidKeys.isEmpty() || seriesUidKeys.contains(scan.getUid());

        final Set<String> instanceUidKeys = getStringsAsSet(keys, Tag.SOPInstanceUID);
        final Predicate<CatDcmentryBean> matchesInstanceKeys =
                entry -> instanceUidKeys.isEmpty() || instanceUidKeys.contains(entry.getUid());

        final List<InstanceLocator> instanceLocators = new ArrayList<>();
        for (final XnatImagesessiondata session : sessions) {
            for (final XnatImagescandataI scan : session.getScans_scan()) {
                if (!matchesSeriesKeys.test(scan)) {
                    continue;
                }
                CatalogEntryUtils.dicomCatalogs(scan).forEach(resourceCatalog -> {
                    try {
                        CatalogUtils.CatalogData.get(resourceCatalog, projectId)
                                .ifPresent(catalogData -> catalogData.catBean.getEntries_entry().stream()
                                        .filter(CatDcmentryBean.class::isInstance)
                                        .map(CatDcmentryBean.class::cast)
                                        .filter(matchesInstanceKeys)
                                        .map(e -> FileInstanceLocator.forEntry(projectId, catalogData.catPath, e))
                                        .filter(Objects::nonNull)
                                        .forEach(instanceLocators::add));
                    } catch (ServerException e) {
                        log.error("unable to get catalog data for {}:{}", projectId, resourceCatalog, e);
                    }
                });
            }
        }
        return instanceLocators;
    }

    /**
     * Finds imaging sessions matching the C-GET identifier. Supports
     * lookup by Study Instance UID (Study Root or Patient Root at
     * STUDY level and below) and by Patient ID (Patient Root at
     * PATIENT level).
     *
     * @param projectId the XNAT project
     * @param keys      the C-GET Identifier
     * @param user      the service account for data access
     * @return matching sessions within the project
     * @throws DicomServiceException if no usable keys are present
     */
    private List<XnatImagesessiondata> findSessions(final String projectId,
                                                    final Attributes keys,
                                                    final UserI user)
            throws DicomServiceException {
        final Set<String> studyUids = getStringsAsSet(keys, Tag.StudyInstanceUID);
        if (!studyUids.isEmpty()) {
            return studyUids.stream()
                    .flatMap(uid -> XnatImagesessiondata
                            .getXnatImagesessiondatasByField(
                                    "xnat:imageSessionData/UID", uid, user, false)
                            .stream())
                    .filter(s -> projectId.equals(s.getProject()))
                    .collect(Collectors.toList());
        }

        final String patientId = keys.getString(Tag.PatientID);
        if (patientId != null && !patientId.isEmpty()) {
            return sessionsBySubject(projectId, patientId, user);
        }

        throw new DicomServiceException(Status.IdentifierDoesNotMatchSOPClass,
                "C-GET identifier must include Study Instance UID or Patient ID");
    }

    /**
     * Finds all imaging sessions for a subject within a project.
     *
     * @param projectId    the XNAT project ID
     * @param subjectLabel the subject label (mapped from DICOM Patient ID)
     * @param user         the service account for data access
     * @return imaging sessions for the subject, or empty list if subject not found
     */
    private static List<XnatImagesessiondata> sessionsBySubject(final String projectId,
                                                                final String subjectLabel,
                                                                final UserI user) {
        final XnatSubjectdata subject =
                XnatSubjectdata.GetSubjectByProjectIdentifier(projectId, subjectLabel, user, false);
        if (subject == null) {
            log.debug("No subject '{}' in project '{}'", subjectLabel, projectId);
            return Collections.emptyList();
        }
        return subject.getExperiments_experiment().stream()
                .filter(XnatImagesessiondata.class::isInstance)
                .map(XnatImagesessiondata.class::cast)
                .collect(Collectors.toList());
    }

    private static final Set<String> UNCOMPRESSED_TRANSFER_SYNTAXES = new HashSet<>(Arrays.asList(
        UID.ImplicitVRLittleEndian,
        UID.ExplicitVRLittleEndian,
        UID.ExplicitVRBigEndian
    ));

    private static final String[] FALLBACK_TRANSFER_SYNTAXES = {
        UID.ExplicitVRLittleEndian,
        UID.ImplicitVRLittleEndian,
        UID.ExplicitVRBigEndian,
    };

    /**
     * Tests whether a transfer syntax is uncompressed.
     *
     * @param tsuid the transfer syntax UID
     * @return true if the TS is IVR LE, EVR LE, or EVR BE
     */
    static boolean isUncompressed(final String tsuid) {
        return UNCOMPRESSED_TRANSFER_SYNTAXES.contains(tsuid);
    }

    /**
     * Selects a transfer syntax for the C-STORE sub-operation that
     * was actually negotiated on the association. Prefers the file's
     * native transfer syntax (avoiding transcoding); falls back to
     * common uncompressed syntaxes if the native TS was not negotiated.
     *
     * @param storeas the association used for C-STORE sub-operations
     * @param inst    the instance to be sent
     * @return a negotiated transfer syntax, or the native TS if none match
     */
    static String negotiatedTransferSyntax(final Association storeas,
                                                  final InstanceLocator inst) {
        final Set<String> negotiated = storeas.getTransferSyntaxesFor(inst.cuid);
        if (negotiated.contains(inst.tsuid)) {
            return inst.tsuid;
        }
        for (final String ts : FALLBACK_TRANSFER_SYNTAXES) {
            if (negotiated.contains(ts)) {
                log.debug("Native TS {} not negotiated for {}; falling back to {}",
                        inst.tsuid, inst.cuid, ts);
                return ts;
            }
        }
        log.warn("No negotiated transfer syntax for SOP class {}; "
                + "sub-operation will fail", inst.cuid);
        return inst.tsuid;
    }

    private static Set<String> getStringsAsSet(final Attributes attrs, final int tag) {
        return Optional.ofNullable(attrs.getStrings(tag))
                .map(Arrays::stream)
                .orElse(Stream.empty())
                .collect(Collectors.toSet());
    }
}
