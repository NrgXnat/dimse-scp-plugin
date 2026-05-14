package org.nrg.xnat.dimse.scp;

import lombok.extern.slf4j.Slf4j;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.security.user.XnatUserProvider;
import org.nrg.xft.security.UserI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import javax.inject.Provider;
import java.io.IOException;
import java.util.Optional;

/**
 * Resolves a DICOM Called AE Title to an XNAT project.
 *
 * <p>The resolution strategy is: treat the Called AE Title as an
 * XNAT project ID (or alias) and look it up. If a project is
 * found and the service user has read access, the project is
 * returned.</p>
 */
@Slf4j
@Service
public class ProjectAeResolver {
    private final XnatUserProvider _userProvider;

    /**
     * Creates a new ProjectAeResolver.
     *
     * @param userProvider provides the service account user for data access
     */
    @Autowired
    public ProjectAeResolver(final XnatUserProvider userProvider) {
        _userProvider = userProvider;
    }

    /**
     * Returns the service account user for data access.
     *
     * @return the service user
     */
    public UserI getUser() {
        return _userProvider.get();
    }

    /**
     * Resolves a Called AE Title to an XNAT project.
     *
     * @param calledAeTitle the Called AE Title from the DICOM association
     * @return the matching project, or empty if no accessible project matches
     */
    @Nonnull
    public Optional<XnatProjectdata> resolve(final String calledAeTitle) {
        if (calledAeTitle == null || calledAeTitle.isEmpty()) {
            return Optional.empty();
        }
        final UserI user = _userProvider.get();
        if (user == null) {
            log.error("No user available to find project {}", calledAeTitle);
            return Optional.empty();
        }
        final XnatProjectdata project = XnatProjectdata.getProjectByIDorAlias(calledAeTitle, user, false);
        if (project == null) {
            log.debug("No project found for AE title '{}'", calledAeTitle);
            return Optional.empty();
        }
        log.debug("Resolved AE title '{}' to project '{}'", calledAeTitle, project.getId());
        return Optional.of(project);
    }
}
