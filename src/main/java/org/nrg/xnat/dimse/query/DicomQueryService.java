package org.nrg.xnat.dimse.query;

import org.dcm4che3.data.Attributes;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xft.security.UserI;

import java.util.stream.Stream;

/**
 * Translates DICOM C-FIND query keys into XNAT data queries
 * and returns results as DICOM Attributes.
 */
public interface DicomQueryService {

    /**
     * Finds studies (imaging sessions) matching the given DICOM
     * query keys within a project.
     *
     * <p>The returned stream is lazy: row conversion and
     * augmentation happen only as elements are consumed.</p>
     *
     * @param project the XNAT project to search
     * @param keys    the DICOM C-FIND Identifier (query keys)
     * @param user    the user context for data access
     * @return a stream of matching studies as DICOM Attributes
     */
    Stream<Attributes> findStudies(XnatProjectdata project,
                                   Attributes keys,
                                   UserI user);

    /**
     * Finds series (scans) matching the given DICOM query keys
     * within a project.
     *
     * <p>The query keys must include a Study Instance UID to
     * scope the search to a specific study, per the Study Root
     * Query/Retrieve Information Model (PS3.4 C.3.2).</p>
     *
     * <p>The returned stream is lazy: scan conversion happens
     * only as elements are consumed.</p>
     *
     * @param project the XNAT project to search
     * @param keys    the DICOM C-FIND Identifier (query keys)
     * @param user    the user context for data access
     * @return a stream of matching series as DICOM Attributes
     */
    Stream<Attributes> findSeries(XnatProjectdata project,
                                  Attributes keys,
                                  UserI user);

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
     */
    Stream<Attributes> findInstances(XnatProjectdata project,
                                     Attributes keys,
                                     UserI user);

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
     */
    Stream<Attributes> findPatients(XnatProjectdata project,
                                    Attributes keys,
                                    UserI user);
}
