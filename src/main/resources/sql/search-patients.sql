-- Search for patients (subjects) in a project (DIMSE C-FIND at PATIENT level).
-- Parameter: :projectId
SELECT s.id AS subject_id,
       COALESCE(psl.subject_label, s.label) AS subject_label,
       ad.gender,
       ad.dob,
       COALESCE(study_counts.study_count, 0) AS study_count,
       COALESCE(study_counts.series_count, 0) AS series_count,
       COALESCE(study_counts.instance_count, 0) AS instance_count
FROM xnat_subjectdata s
LEFT JOIN (
    SELECT s2.id AS subject_id,
           s2.label AS subject_label
    FROM xnat_subjectdata s2
    WHERE s2.project = :projectId
    UNION
    SELECT pp.subject_id,
           pp.label
    FROM xnat_projectparticipant pp
    WHERE pp.project = :projectId
) psl ON psl.subject_id = s.id
LEFT JOIN xnat_demographicdata ad
  ON s.demographics_xnat_abstractdemographicdata_id = ad.xnat_abstractdemographicdata_id
LEFT JOIN (
    SELECT sa.subject_id,
           COUNT(DISTINCT e.id) AS study_count,
           COUNT(DISTINCT sc.xnat_imagescandata_id) AS series_count,
           COALESCE(SUM(scan_files.file_count), 0) AS instance_count
    FROM xnat_subjectassessordata sa
    JOIN xnat_experimentdata e
      ON e.id = sa.id
    -- Only image sessions are studies; other subject assessors are not.
    JOIN xnat_imagesessiondata i
      ON i.id = e.id
    LEFT JOIN xnat_imagescandata sc
      ON sc.image_session_id = e.id
    LEFT JOIN LATERAL (
        SELECT SUM(ar.file_count) AS file_count
        FROM xnat_abstractresource ar
        LEFT JOIN xnat_resource r
          ON r.xnat_abstractresource_id = ar.xnat_abstractresource_id
        WHERE ar.xnat_imagescandata_xnat_imagescandata_id = sc.xnat_imagescandata_id
          -- DICOM resource rule: keep in step with CatalogEntryUtils.isDicomResource
          AND LOWER(CONCAT_WS(' ', ar.label, r.format, r.content)) LIKE ANY (ARRAY['%dicom%', '%secondary%'])
    ) scan_files ON TRUE
    WHERE e.project = :projectId
       OR EXISTS (
           SELECT 1
           FROM xnat_experimentdata_share es
           WHERE es.sharing_share_xnat_experimentda_id = e.id
             AND es.project = :projectId)
    GROUP BY sa.subject_id
) study_counts
  ON study_counts.subject_id = s.id
-- Parenthesized so filters appended by DimseDataAccess.addPatientFilters
-- ("AND ...") apply to the whole project-scope predicate, not just the
-- EXISTS branch.
WHERE (s.project = :projectId
   OR EXISTS (
       SELECT 1
       FROM xnat_projectparticipant pp
       WHERE pp.subject_id = s.id
         AND pp.project = :projectId))
