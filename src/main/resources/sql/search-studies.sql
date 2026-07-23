-- Search for studies in a project (DIMSE C-FIND at STUDY level).
-- No per-user permission filtering: DIMSE operates as a system user
-- with project access controlled by DimsePreferences.
-- Parameter: :projectId
WITH project_experiments AS (
    SELECT e.id AS experiment_id,
           e.label AS experiment_label
    FROM xnat_experimentdata e
    WHERE e.project = :projectId
    UNION
    SELECT es.sharing_share_xnat_experimentda_id,
           es.label
    FROM xnat_experimentdata_share es
    WHERE es.project = :projectId
),
scan_counts AS (
    SELECT sc.image_session_id AS experiment_id,
           COUNT(*) AS scan_count
    FROM xnat_imagescandata sc
    JOIN project_experiments pe
      ON pe.experiment_id = sc.image_session_id
    GROUP BY sc.image_session_id
),
scan_file_counts AS (
    SELECT sc.image_session_id AS experiment_id,
           COALESCE(SUM(ar.file_count), 0) AS file_count
    FROM xnat_imagescandata sc
    JOIN project_experiments pe
      ON pe.experiment_id = sc.image_session_id
    JOIN xnat_abstractresource ar
      ON ar.xnat_imagescandata_xnat_imagescandata_id = sc.xnat_imagescandata_id
    WHERE ar.label IN ('secondary', 'DICOM')
    GROUP BY sc.image_session_id
),
scan_modalities AS (
    SELECT sc.image_session_id AS experiment_id,
           string_agg(DISTINCT
               CASE WHEN sc.modality = 'PET' THEN 'PT'
                    WHEN length(sc.modality) > 16
                        THEN substring(sc.modality FROM 1 FOR 16)
                    ELSE sc.modality
               END, E'\\') AS modalities
    FROM xnat_imagescandata sc
    JOIN project_experiments pe
      ON pe.experiment_id = sc.image_session_id
    WHERE sc.modality IS NOT NULL AND sc.modality <> ''
    GROUP BY sc.image_session_id
)
SELECT e.id AS exptid,
       pe.experiment_label,
       i.uid,
       e.date,
       e.time,
       psl.subject_label,
       COALESCE(sc.scan_count, 0) AS scan_count,
       COALESCE(sfc.file_count, 0) AS file_count,
       sm.modalities,
       ad.gender AS subject_gender,
       ad.dob AS subject_dob
FROM project_experiments pe
JOIN xnat_experimentdata e
  ON e.id = pe.experiment_id
JOIN xnat_imagesessiondata i
  ON i.id = e.id
JOIN xnat_subjectassessordata sa
  ON sa.id = e.id
JOIN xnat_subjectdata s
  ON s.id = sa.subject_id
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
LEFT JOIN scan_counts sc
  ON sc.experiment_id = e.id
LEFT JOIN scan_file_counts sfc
  ON sfc.experiment_id = e.id
LEFT JOIN scan_modalities sm
  ON sm.experiment_id = e.id
-- Anchor for the filters appended by DimseDataAccess.addStudyFilters;
-- project scoping is handled by the project_experiments CTE above.
WHERE TRUE
