package org.nrg.xnat.dimse.query;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.service.DicomServiceException;

/**
 * DICOM Query/Retrieve levels for the Patient Root and Study Root models.
 */
public enum QueryLevel {
    PATIENT, STUDY, SERIES, IMAGE;

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
