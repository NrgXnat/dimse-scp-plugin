package org.nrg.xnat.dimse.query;

import lombok.extern.slf4j.Slf4j;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.ElementDictionary;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.service.DicomServiceException;

import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * The keys this SCP supports at each level of the Patient Root and
 * Study Root Query/Retrieve Information Models, and the selection of
 * those keys from a C-FIND Identifier.
 *
 * <p>A key is supported at a level if the conformance statement lists
 * it there. A key from another level, or one this SCP does not support,
 * is ignored: it neither restricts the matches nor is returned. PS3.4
 * C.4.1.2.1 lets the Identifier hold the Unique Key of each level above
 * the Query/Retrieve level, plus the keys of that level itself; PS3.4
 * C.2.2.1.3 says an Optional Key whose existence the SCP does not
 * support "shall not be returned in C-FIND responses".</p>
 */
@Slf4j
public final class QueryKeys {

    /** Control attributes, kept at every level; neither is matched. */
    private static final int[] CONTROL = {Tag.SpecificCharacterSet, Tag.QueryRetrieveLevel};

    private static final int[] PATIENT = {
        Tag.PatientName, Tag.PatientID, Tag.PatientBirthDate, Tag.PatientSex,
        Tag.NumberOfPatientRelatedStudies, Tag.NumberOfPatientRelatedSeries,
        Tag.NumberOfPatientRelatedInstances,
    };

    private static final int[] STUDY = {
        Tag.StudyDate, Tag.StudyTime, Tag.AccessionNumber, Tag.ModalitiesInStudy,
        Tag.ReferringPhysicianName, Tag.StudyDescription, Tag.StudyInstanceUID,
        Tag.StudyID, Tag.NumberOfStudyRelatedSeries, Tag.NumberOfStudyRelatedInstances,
    };

    /**
     * Patient attributes that the Study Root model places at STUDY level
     * (PS3.4 Table C.6-5). In the Patient Root model they are PATIENT
     * level keys, and only Patient ID, the PATIENT level Unique Key, may
     * appear at lower levels.
     */
    private static final int[] STUDY_ROOT_PATIENT = {
        Tag.PatientName, Tag.PatientID, Tag.PatientBirthDate, Tag.PatientSex,
    };

    private static final int[] SERIES = {
        Tag.Modality, Tag.SeriesDescription, Tag.SeriesInstanceUID, Tag.SeriesNumber,
        Tag.NumberOfSeriesRelatedInstances,
    };

    private static final int[] IMAGE = {
        Tag.SOPClassUID, Tag.SOPInstanceUID, Tag.InstanceNumber,
    };

    private QueryKeys() {}

    /**
     * Returns the keys of an Identifier that this SCP supports at the
     * Query/Retrieve level, for the information model of the SOP Class:
     * the level's own keys, the Unique Key of each level above it in the
     * model, and Specific Character Set and Query/Retrieve Level.
     *
     * @param sopClassUid the C-FIND SOP Class (the presentation
     *                    context's abstract syntax)
     * @param level       the Query/Retrieve level of the Identifier
     * @param keys        the C-FIND Identifier
     * @return a new Identifier holding only the supported keys
     * @throws DicomServiceException with status A900H (Identifier does
     *         not match SOP Class) for a PATIENT level query in the Study
     *         Root model, which has no PATIENT level
     */
    public static Attributes select(final String sopClassUid,
                                    final QueryLevel level,
                                    final Attributes keys) throws DicomServiceException {
        final boolean patientRoot = UID.PatientRootQueryRetrieveInformationModelFind.equals(sopClassUid);
        if (!patientRoot && level == QueryLevel.PATIENT) {
            throw new DicomServiceException(Status.IdentifierDoesNotMatchSOPClass,
                    "Unsupported QueryRetrieveLevel: PATIENT");
        }
        final int[] supported = supported(patientRoot, level);
        final Attributes selected = new Attributes(keys.size());
        selected.addSelected(keys, supported);
        if (log.isDebugEnabled() && selected.size() < keys.size()) {
            log.debug("Ignoring keys not supported at {} level: {}", level,
                    IntStream.of(keys.tags())
                            .filter(tag -> Arrays.binarySearch(supported, tag) < 0)
                            .mapToObj(tag -> ElementDictionary.keywordOf(tag, null))
                            .collect(Collectors.joining(", ")));
        }
        return selected;
    }

    /**
     * Returns the sorted tags supported at a level.
     *
     * @param patientRoot true for the Patient Root model, false for Study
     *                    Root
     * @param level       the Query/Retrieve level
     * @return the supported tags, sorted
     */
    static int[] supported(final boolean patientRoot, final QueryLevel level) {
        final IntStream own = switch (level) {
            case PATIENT -> IntStream.of(PATIENT);
            case STUDY -> IntStream.concat(IntStream.of(STUDY),
                    patientRoot ? IntStream.empty() : IntStream.of(STUDY_ROOT_PATIENT));
            case SERIES -> IntStream.of(SERIES);
            case IMAGE -> IntStream.of(IMAGE);
        };
        final IntStream uniqueKeysAbove = Arrays.stream(QueryLevel.values())
                .filter(higher -> higher.ordinal() < level.ordinal())
                .filter(higher -> patientRoot || higher != QueryLevel.PATIENT)
                .mapToInt(QueryLevel::getUniqueKey);
        return IntStream.concat(IntStream.of(CONTROL), IntStream.concat(own, uniqueKeysAbove))
                .distinct()
                .sorted()
                .toArray();
    }
}
