package org.nrg.dcm;

import lombok.extern.slf4j.Slf4j;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.net.service.InstanceLocator;
import org.nrg.xdat.bean.CatDcmentryBean;
import org.nrg.xnat.utils.CatalogUtils;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

/**
 * Implementation of the dcm4che5 InstanceLocator for a file referenced in an XNAT catalog.
 */
@Slf4j
public class FileInstanceLocator extends InstanceLocator {
    private final File file;

    public FileInstanceLocator(String cuid, String iuid, String tsuid, String uri, final File file) {
        super(cuid, iuid, tsuid, uri);
        this.file = file;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public File getFile() {
        return file;
    }

    /**
     * Create a FileInstanceLocator for the provided catalog entry.
     * @param projectId Project containing the resource
     * @param catalogPath File path for containing catalog
     * @param entry entry for which locator is requested
     * @return FileEntryLocator for the provided entry
     */
    public static FileInstanceLocator forEntry(String projectId, String catalogPath, CatDcmentryBean entry) {
        final File file = CatalogUtils.getFile(entry, catalogPath, projectId);
        if (null == file) {
            log.error("no file found for catalog entry {} in project {}", entry, projectId);
            return null;
        }
        try {
            try (DicomInputStream din = new DicomInputStream(file)) {
                final Attributes fmi = din.readFileMetaInformation();
                return new FileInstanceLocator(fmi.getString(Tag.MediaStorageSOPClassUID), entry.getUid(), fmi.getString(Tag.TransferSyntaxUID), entry.getUri(), file);
            }
        } catch (IOException e) {
            log.error("unable to read DICOM instance {}", file, e);
            return null;
        }
    }
}
