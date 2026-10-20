package org.nrg.xnat.dimse.query;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.net.service.DicomServiceException;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xft.security.UserI;

import java.util.stream.Stream;

/**
 * Translates DICOM C-FIND query keys into XNAT data queries
 * and returns results as DICOM Attributes.
 *
 * <p>A failed database query is thrown by the {@code find} method as a
 * {@link DicomServiceException}, never reported as an empty stream,
 * which would mean "no matches". Database queries run before the
 * method returns; only filtering and, at IMAGE level, reading resource
 * catalogs happen as the stream is consumed. A catalog that cannot be
 * read is logged and skipped.</p>
 */
public interface DicomQueryService {

    /**
     * Finds studies (imaging sessions) matching the given DICOM
     * query keys within a project.
     *
     * <p>The database query runs before this method returns; the
     * returned stream applies the remaining match filtering as it is
     * consumed.</p>
     *
     * @param project the XNAT project to search
     * @param keys    the DICOM C-FIND Identifier (query keys)
     * @param user    the user context for data access
     * @return a stream of matching studies as DICOM Attributes
     * @throws DicomServiceException with status C000H (Unable to
     *         Process) if the query fails
     */
    Stream<Attributes> findStudies(XnatProjectdata project,
                                   Attributes keys,
                                   UserI user)
            throws DicomServiceException;

    /**
     * Finds series (scans) matching the given DICOM query keys
     * within a project.
     *
     * <p>The query keys must include a Study Instance UID to
     * scope the search to a specific study, per the Study Root
     * Query/Retrieve Information Model (PS3.4 C.3.2).</p>
     *
     * <p>The database query runs before this method returns; the
     * returned stream applies the remaining match filtering as it is
     * consumed.</p>
     *
     * @param project the XNAT project to search
     * @param keys    the DICOM C-FIND Identifier (query keys)
     * @param user    the user context for data access
     * @return a stream of matching series as DICOM Attributes
     * @throws DicomServiceException with status C000H (Unable to
     *         Process) if the query fails
     */
    Stream<Attributes> findSeries(XnatProjectdata project,
                                  Attributes keys,
                                  UserI user)
            throws DicomServiceException;

    /**
     * Finds instances (individual DICOM objects) matching the given
     * query keys within a project.
     *
     * <p>The query keys must include Study Instance UID and Series
     * Instance UID per the Study Root model (PS3.4 C.3.2).</p>
     *
     * <p>Instance metadata is read from the XNAT archive catalog
     * without opening DICOM files.</p>
     *
     * @param project the XNAT project to search
     * @param keys    the DICOM C-FIND Identifier (query keys)
     * @param user    the user context for data access
     * @return a stream of matching instances as DICOM Attributes
     * @throws DicomServiceException with status C000H (Unable to
     *         Process) if the query fails
     */
    Stream<Attributes> findInstances(XnatProjectdata project,
                                     Attributes keys,
                                     UserI user)
            throws DicomServiceException;

    /**
     * Finds patients (subjects) matching the given DICOM query keys
     * within a project.
     *
     * <p>This supports the Patient Root Query/Retrieve Information
     * Model at the PATIENT level (PS3.4 C.6.1). DICOM Patient ID
     * maps to XNAT subject label within the project.</p>
     *
     * @param project the XNAT project to search
     * @param keys    the DICOM C-FIND Identifier (query keys)
     * @param user    the user context for data access
     * @return a stream of matching patients as DICOM Attributes
     * @throws DicomServiceException with status C000H (Unable to
     *         Process) if the query fails
     */
    Stream<Attributes> findPatients(XnatProjectdata project,
                                    Attributes keys,
                                    UserI user)
            throws DicomServiceException;
}
