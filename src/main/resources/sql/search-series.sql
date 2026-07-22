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
LEFT JOIN (
    SELECT ar.xnat_imagescandata_xnat_imagescandata_id AS scan_pk,
           COALESCE(SUM(ar.file_count), 0) AS file_count
    FROM xnat_abstractresource ar
    WHERE ar.label IN ('secondary', 'DICOM')
    GROUP BY ar.xnat_imagescandata_xnat_imagescandata_id
) ar_counts
  ON ar_counts.scan_pk = sc.xnat_imagescandata_id
WHERE i.uid = :studyUid
  AND (e.project = :projectId
       OR EXISTS (
           SELECT 1
           FROM xnat_experimentdata_share es
           WHERE es.sharing_share_xnat_experimentda_id = e.id
             AND es.project = :projectId))
