-- Search for series in a project (DIMSE C-FIND at SERIES level).
-- Parameters: :projectId, :studyUid
SELECT sc.uid AS series_uid,
       sc.modality,
       sc.id AS scan_id,
       sc.series_description,
       COALESCE(ar_counts.file_count, 0) AS file_count
FROM xnat_imagescandata sc
JOIN xnat_imagesessiondata i
  ON sc.image_session_id = i.id
JOIN xnat_experimentdata e
  ON e.id = i.id
-- Per-scan count, correlated with sc so only this study's scans are read.
LEFT JOIN LATERAL (
    SELECT SUM(ar.file_count) AS file_count
    FROM xnat_abstractresource ar
    LEFT JOIN xnat_resource r
      ON r.xnat_abstractresource_id = ar.xnat_abstractresource_id
    WHERE ar.xnat_imagescandata_xnat_imagescandata_id = sc.xnat_imagescandata_id
      -- DICOM resource rule: keep in step with CatalogEntryUtils.isDicomResource
      AND LOWER(CONCAT_WS(' ', ar.label, r.format, r.content)) LIKE ANY (ARRAY['%dicom%', '%secondary%'])
) ar_counts ON TRUE
WHERE i.uid = :studyUid
  AND (e.project = :projectId
       OR EXISTS (
           SELECT 1
           FROM xnat_experimentdata_share es
           WHERE es.sharing_share_xnat_experimentda_id = e.id
             AND es.project = :projectId))
