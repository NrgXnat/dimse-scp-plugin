package org.nrg.xnat.dimse.query;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.ElementDictionary;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.service.DicomServiceException;

import java.util.Arrays;
import java.util.Optional;

/**
 * DICOM Query/Retrieve levels for the Patient Root and Study Root models.
 */
public enum QueryLevel {
    PATIENT(Tag.PatientID),
    STUDY(Tag.StudyInstanceUID),
    SERIES(Tag.SeriesInstanceUID),
    IMAGE(Tag.SOPInstanceUID);

    private final int uniqueKey;

    QueryLevel(final int uniqueKey) {
        this.uniqueKey = uniqueKey;
    }

    /**
     * Returns the Unique Key attribute for this level.
     *
     * @return the DICOM tag of the level's Unique Key
     */
    public int getUniqueKey() {
        return uniqueKey;
    }

    /**
     * Rejects an Identifier that gives a list of UIDs in the Unique Key
     * of a level above this one. PS3.4 C.4.1.2.1 requires a single value
     * in the Unique Key for each level above the Query/Retrieve level;
     * only the key at the Query/Retrieve level may use List of UID
     * Matching. Rejecting the request avoids silently answering for
     * only one of the listed entities.
     *
     * <p>The Identifier is valid for the SOP Class (the same values
     * could be legal at a lower level), so this is reported as
     * C000H Unable to Process with the Offending Element (0000,0901)
     * naming the multi-valued key, not as A900H.</p>
     *
     * @param keys the C-FIND Identifier
     * @throws DicomServiceException with status C000H (Unable to
     *         Process) if a UID Unique Key above this level has more
     *         than one value
     */
    public void requireSingleUidKeysAbove(final Attributes keys) throws DicomServiceException {
        final Optional<QueryLevel> listed = Arrays.stream(values())
                .filter(higher -> higher.ordinal() < ordinal())
                .filter(higher -> ElementDictionary.vrOf(higher.uniqueKey, null) == VR.UI)
                .filter(higher -> Optional.ofNullable(keys.getStrings(higher.uniqueKey))
                        .map(values -> values.length > 1)
                        .orElse(false))
                .findFirst();
        if (listed.isPresent()) {
            final int tag = listed.get().uniqueKey;
            // Error Comment is LO: dcm4che truncates it to 64 characters.
            throw new DicomServiceException(Status.UnableToProcess,
                    ElementDictionary.keywordOf(tag, null) + " must be single-valued at "
                    + this + " level")
                    .setOffendingElements(tag);
        }
    }

    /**
     * Parses the QueryRetrieveLevel attribute from DICOM query keys.
     *
     * @param keys the DICOM Identifier containing the QueryRetrieveLevel tag
     * @return the corresponding QueryLevel
     * @throws DicomServiceException if the level is missing or unrecognized
     */
    public static QueryLevel fromKeys(final Attributes keys) throws DicomServiceException {
        final String level = keys.getString(Tag.QueryRetrieveLevel);
        if (level == null) {
            throw new DicomServiceException(Status.IdentifierDoesNotMatchSOPClass,
                    "Missing QueryRetrieveLevel");
        }
        final String normalized = level.trim().toUpperCase();
        try {
            return valueOf("INSTANCE".equals(normalized) ? "IMAGE" : normalized);
        } catch (IllegalArgumentException e) {
            throw new DicomServiceException(Status.IdentifierDoesNotMatchSOPClass,
                    "Unsupported QueryRetrieveLevel: " + level);
        }
    }
}
