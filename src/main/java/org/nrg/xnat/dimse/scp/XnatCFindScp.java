package org.nrg.xnat.dimse.scp;

import lombok.extern.slf4j.Slf4j;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.UID;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.BasicCFindSCP;
import org.dcm4che3.net.service.BasicQueryTask;
import org.dcm4che3.net.service.DicomServiceException;
import org.dcm4che3.net.service.QueryTask;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.dimse.query.DateTimeKeys;
import org.nrg.xnat.dimse.query.DicomQueryService;
import org.nrg.xnat.dimse.query.QueryLevel;

import java.util.Iterator;
import java.util.stream.Stream;

/**
 * DICOM C-FIND SCP for the Study Root and Patient Root
 * Query/Retrieve Information Models.
 *
 * <p>Handles incoming C-FIND requests by resolving the Called AE Title
 * to an XNAT project and searching for matching studies, series, or
 * instances within that project.</p>
 */
@Slf4j
public class XnatCFindScp extends BasicCFindSCP {
    private final ProjectAeResolver _projectAeResolver;
    private final DicomQueryService _queryService;

    /**
     * Creates a new C-FIND SCP for Study Root and Patient Root queries.
     *
     * @param projectAeResolver resolves Called AE Title to XNAT project
     * @param queryService      executes DICOM queries against XNAT data
     */
    public XnatCFindScp(final ProjectAeResolver projectAeResolver,
                        final DicomQueryService queryService) {
        super(UID.StudyRootQueryRetrieveInformationModelFind,
              UID.PatientRootQueryRetrieveInformationModelFind);
        _projectAeResolver = projectAeResolver;
        _queryService = queryService;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected QueryTask calculateMatches(final Association as,
                                         final PresentationContext pc,
                                         final Attributes rq,
                                         final Attributes keys)
            throws DicomServiceException {
        final String calledAet = as.getCalledAET();
        final String callingAet = as.getCallingAET();
        log.debug("C-FIND from {} to {}, keys {}", callingAet, calledAet, keys);
        final QueryLevel level = QueryLevel.fromKeys(keys);
        log.trace("query level {}", level);
        level.requireSingleUidKeysAbove(keys);
        DateTimeKeys.requireValid(keys);

        final XnatProjectdata project = _projectAeResolver.resolve(calledAet)
            .orElseThrow(() -> {
                log.warn("C-FIND rejected: no project for AE title '{}'", calledAet);
                return new DicomServiceException(Status.UnableToProcess,
                        "Unknown AE title: " + calledAet);
            });

        final UserI user = _projectAeResolver.getUser();

        log.debug("C-FIND searching project '{}' at level {}", project.getId(), level);

        final String projectId = project.getId();
        final Iterator<Attributes> iter;
        try {
            iter = find(level, project, keys, user).iterator();
        } catch (final RuntimeException e) {
            throw queryFailed(projectId, level, e);
        }
        return new BasicQueryTask(as, pc, rq, keys) {
            private int _count;

            @Override
            protected boolean hasMoreMatches() throws DicomServiceException {
                final boolean more;
                try {
                    more = iter.hasNext();
                } catch (final RuntimeException e) {
                    throw queryFailed(projectId, level, e);
                }
                if (!more) {
                    log.debug("C-FIND completed: {} matches for project '{}' at level {}", _count, projectId, level);
                }
                return more;
            }

            @Override
            protected Attributes nextMatch() throws DicomServiceException {
                _count++;
                try {
                    return iter.next();
                } catch (final RuntimeException e) {
                    throw queryFailed(projectId, level, e);
                }
            }
        };
    }

    private Stream<Attributes> find(final QueryLevel level,
                                    final XnatProjectdata project,
                                    final Attributes keys,
                                    final UserI user) throws DicomServiceException {
        switch (level) {
            case PATIENT:
                return _queryService.findPatients(project, keys, user);
            case STUDY:
                return _queryService.findStudies(project, keys, user);
            case SERIES:
                return _queryService.findSeries(project, keys, user);
            case IMAGE:
                return _queryService.findInstances(project, keys, user);
            default:
                throw new DicomServiceException(Status.IdentifierDoesNotMatchSOPClass,
                        "Unsupported query level " + level);
        }
    }

    /**
     * Reports an unexpected failure (a bug, since query failures are
     * thrown as {@link DicomServiceException} by the query service) as
     * C000H Unable to Process. dcm4che sends a
     * {@link DicomServiceException} thrown while matches are being
     * iterated as the final C-FIND response, but sends no final
     * response for any other exception. The cause is logged, not put in
     * the Error Comment, which the SCU can read.
     */
    private static DicomServiceException queryFailed(final String projectId,
                                                     final QueryLevel level,
                                                     final RuntimeException cause) {
        log.error("C-FIND failed unexpectedly for project '{}' at level {}", projectId, level, cause);
        final DicomServiceException e =
                new DicomServiceException(Status.UnableToProcess, "Query failed");
        e.initCause(cause);
        return e;
    }
}
