# DIMSE Conformance — Developer Annotations

Supplement to `CONFORMANCE.md`. The conformance statement itself
describes plugin behavior in behavioral and specification terms
only; this document pairs each behavioral claim with the
implementing code so a reviewer or a maintainer can go from
"the plugin does X" to "here is where and how it does X" without
needing to grep the codebase.

Section numbering mirrors `CONFORMANCE.md` exactly: an item in
§5.7 of the conformance statement is annotated in §5.7 here.
Sections without any implementation notes are omitted.

Line numbers reflect the source tree as of the date at the foot
of this document; they will drift as the code changes and should
be treated as pointers rather than durable citations. All file
paths below are relative to the repository root.

---

## 0. Important Warnings

Each warning is implemented (or, for absences, not-implemented)
in code discussed under later sections; the pointers below are
navigational only.

- **No C-MOVE** — no `BasicCMoveSCP` is registered in
  `DimseScpService.createServiceRegistry` (`scp/DimseScpService.java:295-301`).
- **Called AE Title = XNAT project ID** — resolution in
  `scp/ProjectAeResolver.java:60-80`; wildcard AE at
  `scp/DimseScpService.java:223`.
- **No compressed transfer syntaxes** — registered TS list at
  `scp/DimseScpService.java:41-45`; transcoding gate at
  `scp/XnatCGetScp.java:101`.
- **Single service account** — `scp/ProjectAeResolver.java:49-51`
  (`getUser()` returns the injected `XnatUserProvider`'s user).
- **C-FIND results ≠ C-GET file contents** — see §5.7.

(TLS is not a warning; the no-TLS code fact is under §3.1.)

---

## 1. Overview

### 1.2 Supported SOP Classes

SCP presentation contexts are attached to the wildcard AE in
`scp/DimseScpService.java:226-232` (Verification, Study Root
Q/R FIND/GET, Patient Root Q/R FIND/GET). The SCU-role Storage
SOP Classes for C-GET sub-operations are in the
`STORAGE_SOP_CLASSES` array at `scp/DimseScpService.java:59-175`
and attached at `:233-235`.

The service registry at `scp/DimseScpService.java:295-301` binds
`BasicCEchoSCP`, `XnatCFindScp`, and `XnatCGetScp`. Any SOP class
not registered here (C-MOVE, C-STORE SCP, MWL, etc.) is
implicitly unsupported.

---

## 2. Implementation Model

### 2.2 Functional Definition

Called-AE-Title-to-project resolution: `scp/ProjectAeResolver.java:60-80`.

- Empty / null AE → `Optional.empty()` (`:61-63`).
- Lookup by ID or alias via
  `XnatProjectdata.getProjectByIDorAlias` (`:69`).
- An additional `isProjectEnabled(project)` check after
  resolution rejects projects with DIMSE disabled (`:74-77`; see
  `DimsePreferences.isProjectEnabled` in §8.4).

Rejection paths:

- Unknown AE → `DicomServiceException(Status.UnableToProcess)`
  from the calling SCP (`scp/XnatCFindScp.java:66-71`,
  `scp/XnatCGetScp.java:83-87`).

### 2.3 Sequencing Constraints

`scp/DimseScpService.java:215` — `Executors.newCachedThreadPool()`
handles per-association dcm4che processing; there is no bounded
queue or maximum-associations setting.

---

## 3. Association Establishment

### 3.1 General Parameters

- Device name (`XNAT_DIMSE`) and default port (9104):
  `scp/DimseScpService.java:38-39`.
- Connection object: `scp/DimseScpService.java:218-219` (no PDU
  size, TLS, or role-negotiation attributes are set — dcm4che
  defaults apply).
- Bind: `scp/DimseScpService.java:244` (`_device.bindConnections()`).

### 3.2 Called AE Title Handling

Wildcard AE registration: `scp/DimseScpService.java:223`
(`new ApplicationEntity("*")`).

At request time, the SCPs read the Called AE Title from the
association and resolve it:
- C-FIND: `scp/XnatCFindScp.java:58` + `:66-71`.
- C-GET: `scp/XnatCGetScp.java:76` + `:83-87`.

The 16-character AE title limit is a DICOM protocol constraint
(imposed by the dcm4che PDU parser); no code in this plugin
truncates or rejects longer names — projects longer than 16
characters are simply unaddressable.

### 3.3 Calling AE Title Handling

Logged but not otherwise used: `scp/XnatCFindScp.java:59-60`,
`scp/XnatCGetScp.java:77`. No validation code path exists.

### 3.4 Presentation Contexts — SCP Role

The three accepted transfer syntaxes are defined once, at
`scp/DimseScpService.java:41-45`, and reused for both SCP and
SCU transfer capabilities via
`makeTransferCapability` (`:197-199`). Registration:
`scp/DimseScpService.java:226-232`.

### 3.5 Presentation Contexts — SCU Role (C-GET Sub-operations)

`STORAGE_SOP_CLASSES`: `scp/DimseScpService.java:59-175`.
Registration as SCU-role transfer capabilities:
`scp/DimseScpService.java:233-235`. The array is grouped by
modality/category using inline comments; individual UIDs are
imported from `org.dcm4che3.data.UID`.

Fallback / transcoding behavior for SCU sub-operations is not
part of §3 — see §6.4.

---

## 4. C-ECHO SCP

`scp/DimseScpService.java:297` — the plugin registers stock
`new BasicCEchoSCP()` with no subclass, so all C-ECHO handling
is inherited from dcm4che.

---

## 5. C-FIND SCP

The C-FIND SCP is a thin dispatcher; per-level logic is in
`query/impl/XnatDicomQueryService.java` and
`query/impl/DimseDataAccess.java`.

### 5.1 Query Levels

Dispatch: `scp/XnatCFindScp.java:78-94`.
`QueryLevel` enum: `query/QueryLevel.java:16-20`; each constant
carries its level's Unique Key tag (`getUniqueKey`, `:33-35`).

### 5.2 Query/Retrieve Level Matching

`query/QueryLevel.fromKeys` at `query/QueryLevel.java:80-93`:

- Missing `QueryRetrieveLevel` → `IdentifierDoesNotMatchSOPClass`
  (`:82-85`).
- Uppercased, trimmed comparison (`:86`).
- `INSTANCE` mapped to `IMAGE` (`:88`).
- Unknown value → `IdentifierDoesNotMatchSOPClass` (`:89-92`).

Then `QueryLevel.requireSingleUidKeysAbove`
(`query/QueryLevel.java:55-71`, called at
`scp/XnatCFindScp.java:63`) rejects any Identifier whose UID Unique
Key for a level *above* the query level (Study Instance UID at
SERIES/IMAGE, Series Instance UID at IMAGE) has more than one
value. PS3.4 C.4.1.2.1 requires a single value there; only the key
at the query level may use List of UID Matching. Rejecting is
deliberate: the per-level lookups (`findSeries`, `findInstances`)
read those keys with `getString` and would otherwise silently
answer for the first listed UID only. Patient ID (LO) is not
checked.

The status is `UnableToProcess` (C000H) with Offending Element
(0000,0901) set to the multi-valued tag and a short Error Comment
(`:63-70`), not A900H: the Identifier is valid for the SOP Class —
the same values would be legal at a lower level — so "Data Set does
not match SOP Class" would mislead. Table C.4-1 lists Offending
Element as a related field of Cxxx. Keep the message under 64
characters; dcm4che truncates the LO Error Comment.

Next, `DateTimeKeys.requireValid` (`query/DateTimeKeys.java:63-73`,
called at `scp/XnatCFindScp.java:64`) rejects a malformed value in
any DA or TM key, the same way: C000H, Offending Element naming the
key, Error Comment "`<Keyword>` is not a valid DA/TM query value".
`findMalformed` (`:81-86`) checks every top-level key, not only
documented matching keys, because `matchesQuery` post-filters on
every key with a value. Each non-empty value of a multi-valued key
must pass; zero-length values are universal and not checked.

- DA: `DicomMatchUtils.parseDateRange` must succeed (§5.5).
- TM: `isValidTimeQueryValue` (`:115-128`). Each bound must match
  `TIME` (`:46-47`), `HH[MM[SS[.F{1,6}]]]` (PS3.5 Table 6.2-1); a
  range needs at least one bound, and its bounds
  are compared after `fullPrecision` (`:145-151`) pads them to
  `HHMMSS.FFFFFF`, so `1030-10` is reversed but `10-1030` is not.
- TM leap seconds: PS3.5 allows SS = 60 "only for a leap second".
  `TIME` accepts it at any hour but only in minute 59 (`HH5960`),
  because leap seconds fall at 23:59:60 UTC and a whole-hour offset
  shifts only the hour. This is the rule the XNAT DICOMweb plugin
  uses (its CONFORMANCE §0.12); keep the two in step. Not handled:
  fractional-hour zones (`HH:29:60` at +05:30, `HH:44:60` at
  +05:45 / +12:45) are rejected, and the queried date is not
  checked against the leap-second table. No stored time has
  second 60 (`xs:time`), so in matching a `235960` upper bound
  covers second 59 and a lower bound or exact value matches
  nothing (`leapSecondBoundAgainstStoredTimes`).
- Rejected for both: wildcards (C.2.2.2.4 does not list DA or TM),
  including a lone `*`; a lone `-`; reversed ranges (C.2.2.2.5
  requires `<v1>` ≤ `<v2>`); and `""`, which means Empty Value
  Matching only after Extended Negotiation (C.2.2.2.7).
- dcm4che strips the trailing SPACE padding PS3.5 allows before
  the values reach the check (`trailingSpaceIsAccepted`).

### 5.3 Matching Keys and Return Keys

The four levels' return-attribute assembly is spread across
`DimseDataAccess` row mappers and one dispatcher in
`XnatDicomQueryService.findInstances`.

#### 5.3.1 PATIENT Level

Row mapper: `query/impl/DimseDataAccess.searchPatients`
(`query/impl/DimseDataAccess.java:206-235`).

- `PatientID` and `PatientName` both set from the same
  `subject_label` column (`:218-219`) — see §5.7.
- Counts (studies / series / instances):
  `query/impl/DimseDataAccess.java:228-230`.
- Gender / DOB: `:221-225` (see §5.8).

SQL: `src/main/resources/sql/search-patients.sql`. The
per-subject sub-query `study_counts` (`search-patients.sql:24-54`)
computes all three counts. It inner-joins `xnat_imagesessiondata`
(`:33-34`), so only image sessions count as studies (other subject
assessors are excluded), and sums each scan's DICOM resource files
in a `LATERAL` sub-query (`:37-45`; §5.10).

#### 5.3.2 STUDY Level

Row mapper: `query/impl/DimseDataAccess.mapStudyRow`
(`query/impl/DimseDataAccess.java:134-164`), invoked by
`searchStudies` (`:61-71`).

Notable attribute mappings:

| Conformance attribute | Source column               | Line    |
|-----------------------|-----------------------------|---------|
| Study Instance UID    | `uid`                       | `:138`  |
| Study Date            | `date`                      | `:139`  |
| Study Time            | `time`                      | `:140`  |
| Accession Number      | `exptid` (session ID)       | `:141`  |
| Study ID              | `exptid`                    | `:142`  |
| Patient Name          | `experiment_label`          | `:143`  |
| Patient ID            | `subject_label`             | `:144`  |
| Study Description     | project ID                  | `:145`  |
| Modalities in Study   | `modalities` split on `\`   | `:147-150` |
| # Related Series      | `scan_count`                | `:152`  |
| # Related Instances   | `file_count`                | `:153`  |

Modalities are aggregated in SQL (`string_agg` with `E'\\'`
separator) at `sql/search-studies.sql:38-51` and split on the
Java side using `modalities.split("\\\\")` at
`query/impl/DimseDataAccess.java:150`.

Shared-into-project sessions are included by the
`project_experiments` CTE
(`sql/search-studies.sql:5-15`), which unions
`xnat_experimentdata.project = :projectId` and
`xnat_experimentdata_share`.

#### 5.3.3 SERIES Level

Row mapper: `query/impl/DimseDataAccess.searchSeries`
(`query/impl/DimseDataAccess.java:174-197`).

SQL: `sql/search-series.sql`. The join scopes results to the
provided Study UID plus the requested project (or a share into
it) at `sql/search-series.sql:23-29`. File count is a `LATERAL`
sub-query correlated with the scan (`:14-22`), so it reads only this
study's resources (§5.10).

`Modality` is passed through `dicomModality` (see §5.9).

The dispatcher rejects a missing Study Instance UID before
calling the DAO: `query/impl/XnatDicomQueryService.java:73-77`.

#### 5.3.4 IMAGE Level

Instance metadata is not stored in a queryable database column
in XNAT — it lives in per-scan resource-catalog XML files. So
IMAGE-level results are assembled by walking the catalog rather
than by SQL:

- Dispatcher and requirements check:
  `query/impl/XnatDicomQueryService.findInstances`
  (`query/impl/XnatDicomQueryService.java:117-149`).
- Session lookup by Study UID: `:136-138` (still uses XFT ORM).
- Project scope: `session.hasProject(projectId)` (`:143`) — the
  XNAT core owned-or-shared rule
  (`BaseXnatExperimentdata.hasProject`), consistent with the
  STUDY/SERIES SQL, so every study listed at STUDY level can be
  queried at IMAGE level.
- Scan enumeration and catalog walk: `:144-146`. Catalogs are
  resolved against the session's owning project
  (`session.getProject()`), not the Called-AE project.
- Attribute assembly per catalog entry:
  `query/impl/XnatDicomQueryService.catalogEntryToAttributes`
  (`:154-172`); SOP Class UID pulled from the
  `SOP_Class_UID` metafield (`:177-183`).
- Catalog entry streaming: `CatalogEntryUtils.scanEntries`
  (`CatalogEntryUtils.java:57-61`) — delegates to
  `dicomCatalogs` + `catalogEntries`.

### 5.4 Return Key Handling

`DimseDataAccess.copyReturnKeys` at
`query/impl/DimseDataAccess.java:526-533`: iterates the query
tags and adds a `setNull` for any tag not already present in the
result, using the VR from the query (or `LO` as fallback). Called
from every row mapper (`:162`, `:194`, `:232`) and from
`XnatDicomQueryService.catalogEntryToAttributes`
(`query/impl/XnatDicomQueryService.java:170`).

**Why empty and not a placeholder.** The zero-length values
produced here (and the always-empty Referring Physician Name, the
omitted Patient Sex when gender is blank, etc.) are the
*conformant* no-value representation, not an oversight. Do not
"fix" an unpopulated key — e.g. an absent Patient Name or Patient
ID — by substituting a sentinel string such as `UNKNOWN`.

- The C-FIND response is governed by the Query/Retrieve Service
  Class, which defines its own key model — Unique / Required /
  Optional keys (PS3.4 §C.2.2.1) — **not** the storage IOD
  attribute types (PS3.3 Type 1/2/3). The Type 2 "present but may
  be empty" rule for the Patient Module (PS3.3 §C.7.1.1) applies
  to stored composite objects, not to query Identifiers.
- PS3.4 §C.4.1.1.3.2 (Response Identifier Structure), Note 2:
  "Required Keys and supported Optional Keys in the Response
  Identifier will have zero length if the SCP has no value to
  send; i.e., there is no requirement that the SCP have a value
  for these, or create a dummy value."
- Zero length is therefore correct, and the standard explicitly
  says not to invent a dummy value.
- QIDO-RS reuses this matching model by reference (PS3.18 §10.6 →
  §8.3.4.1). The sibling `xnat-dicomweb-plugin` emits a literal
  `UNKNOWN` for absent patient identifiers (its CONFORMANCE
  §0.7); that is a documented deviation, and the divergence
  between the two plugins here is intentional — see
  `CONFORMANCE-RECONCILIATION-PLAN.md` R2.

Verified against dicom.nema.org/medical/dicom/current/output/ on
2026-07-23 (PS3.4 §C.2.2.1, §C.4.1.1.3.2; PS3.18 §10.6,
§8.3.4.1). Re-verify if this text is edited.

### 5.5 Matching Semantics

Matching happens on two sides: indexed keys are pushed to SQL by
`DimseDataAccess`, non-indexed keys are post-filtered in Java by
`DicomMatchUtils`. Both sides match case-insensitively (R4).

**Java side** — `query/DicomMatchUtils.java`:

- `matchesQuery` (`:40-48`) iterates the query's tags,
  skipping `QueryRetrieveLevel` and `SpecificCharacterSet`,
  and requires every remaining key to match. Every key is read with
  `getStrings`, so all query values are used: UI-VR keys go to
  `matchesUidList`, every other VR to `matchesAnyValue`.
- `matchesUidList` (`:253-265`) implements List of UID Matching
  (PS3.4 C.2.2.2.2): the candidate matches if any of its values
  exactly equals any query UID; an absent or
  all-empty query is universal. dcm4che has already split the
  backslash-delimited list. Applies to every UI key, so the SQL
  `IN (:uidList)` push-down and the post-filter agree.
- `matchesAnyValue` (`:75-88`) is match-any in both directions:
  the candidate matches if any query value matches any of the
  candidate's values (`candidate.getStrings`). One query value
  against a multi-valued attribute — Modalities in Study
  (0008,0061) — matches when the study includes that value; a
  multi-valued query key (`MR\CT`) matches either value, so value
  order cannot change the result. PS3.4 C.2.2.2.8 Note 4 leaves
  multiple query values undefined without Extended Negotiation,
  which we do not offer; negotiated Multiple Value Matching would
  be match-all, so do not "upgrade" this without negotiating it.
  Query values come from `nonEmptyValues` (`:98-103`), which drops
  zero-length values; if none remain, or any value is a lone `*`,
  the key is universal and matches even an absent candidate
  (`:79-81`).
- `matchesValue` (`:115-145`) dispatches by VR:
  - Empty query value, or one made only of `*` (checked by
    `isUniversalWildcard`, `:169-171`) → universal match
    (`:118-120`). PS3.4 C.2.2.2.4: `*` matches any sequence
    including a zero-length value, so a lone `*` must also match
    empty and absent values; it is tested before the
    empty-candidate rejection below. A lone `*` never reaches
    here for DA or TM keys: `DateTimeKeys` rejects it (§5.2).
  - Empty candidate against any other non-empty query → no match
    (`:121-123`).
  - `VR.UI` → exact single-UID comparison
    (`:125-127`); `matchesQuery` never reaches this for UI keys,
    which use `matchesUidList`.
  - `VR.DA` → `parseDateRange(...).contains` (`:128-133`), exact
    dates included. A malformed value throws
    `IllegalArgumentException`: it can only arrive if a caller
    skipped `DateTimeKeys.requireValid`.
  - `VR.DT` / `VR.TM` → `matchesDateRange` (string comparison)
    when a `-` is present (`:134-138`, `:230-239`). TM values are
    validated (§5.2) but still matched as strings, so an exact TM
    query of lower precision than the stored value (`10` against
    `103000`) does not match. Study Time, the only TM key in the
    conformance tables, is not listed as a matching key.
  - Wildcards → `matchesWildcard` (`:139-141`, `:188-192`) via a
    regex from `wildcardToRegex` (`:200-223`), compiled with
    `CASE_INSENSITIVE`.
  - Otherwise a case-insensitive exact match
    (`equalsIgnoreCase`, `:142-144`) — for every VR, not only PN
    (R4).
- `parseDateRange` (`:341-358`) is the single definition of a valid
  DA query value, used by `DateTimeKeys` and by both matching
  layers. It returns a `DateRange` record (`:313-326`) of lower
  and upper `YYYYMMDD` bounds, at least one present (an exact date
  has equal bounds), and an `exact` flag; or empty if a bound fails
  `isValidDicomDate` (`:368-378`), no bound is given, or the range
  is reversed. `contains` compares a candidate with each present
  bound as a string, which orders correctly for `YYYYMMDD`.

**SQL side** — `query/impl/DimseDataAccess.java`. Filters are
appended as ` AND …` by `buildStudyQuery` (`:82-88`),
`buildSeriesQuery` (`:98-103`) and `buildPatientQuery`
(`:114-119`) and bind to a top-level `WHERE` in each base query:
`search-studies.sql` ends with a `WHERE TRUE` anchor and
`search-patients.sql` parenthesizes its project-scope disjunction,
so the filters restrict rows rather than attaching to a
`LEFT JOIN … ON`. (`search-series.sql` was already correct.)

- `addStudyFilters` (`:239-247`): StudyInstanceUID, StudyDate,
  AccessionNumber, PatientID, PatientName.
- `addSeriesFilters` (`:251-261`): SeriesInstanceUID, Modality
  (each value reverse-mapped PT→PET via `dicomToXnatModality`;
  see §5.9), SeriesNumber.
- `addPatientFilters` (`:265-272`): PatientID, PatientName.
- `addUidFilter` (`:280-298`): single UID → `= :uid`; list →
  `IN (:uidList)` (UIDs matched exactly).
- `addStringFilter` — `:307-315` (by tag) passes all non-empty
  values to the list overload `:326-344`, which emits
  `ILIKE :param` per value with the value routed through
  `DicomMatchUtils.toSqlLike` (`:275-298`) (R4). A value with no
  wildcards is a case-insensitive exact match; the overload lets
  `addSeriesFilters` pass the reverse-mapped Modality values. If
  any value is made only of `*`, no predicate is added
  (`:334-336`): `ILIKE '%'` would drop rows where the column is
  NULL (for example `psl.subject_label`), contradicting universal
  matching.
- `addDateFilter` (`:352-362`): one predicate per value, from
  `datePredicate` (`:378-400`), which takes its bounds from
  `DicomMatchUtils.parseDateRange` so the post-filter applies the
  same constraint. Values are already valid (§5.2); a malformed
  one throws `IllegalArgumentException`.
  An exact date becomes `=`, a range `>=` / `<=`. `dateComparison`
  (`:407-414`) converts each bound to `YYYY-MM-DD`
  (`dicomDateToIso`, `:388-395` in `DicomMatchUtils`) and compares
  as `CAST(:param AS DATE)` so the `date` column is not compared
  against a bound string.
- Multi-valued keys: both filters hand their per-value predicates
  to `appendAnyOf` (`:431-443`). One predicate is appended as is,
  with the plain parameter names (`:name`, `:dateStart`); several
  are parenthesized and ORed, with an index suffix on each name
  (`indexSuffix`, `:449-451`; `:modality0`, `:modality1`). If any
  value restricts nothing (a lone `*` in a string key), the whole
  key is universal and nothing is appended.

> **Regression hazard.** The SQL push-down must return a superset
> of what `matchesAnyValue` accepts, because the post-filter can
> only remove rows. Reading only the first value of a key in SQL
> (`keys.getString(tag)`) would silently drop entities that match a
> later value. Covered by `stringFilterMultipleValuesOrderIndependent`,
> `seriesQueryMapsEachModalityValue` and `dateFilterMultipleValuesOred`
> in `DimseDataAccessTest`, and the `multiValuedQuery*` cases in
> `DicomMatchUtilsTest`.

> **Regression hazard.** Neither matching layer may skip a bad
> bound or treat a malformed DA value as universal: if one layer
> did, the two layers could disagree, and the SCU would get results
> for a query other than the one it sent. Validation belongs in
> `DateTimeKeys.requireValid`, before the query runs; the layers
> parse only through `DicomMatchUtils.parseDateRange` and throw on
> anything it rejects. Covered by `DateTimeKeysTest`,
> `parseDateRangeRejectsMalformed` and
> `matchesValueThrowsOnUnvalidatedMalformedDate` in
> `DicomMatchUtilsTest`, `dateFilterThrowsOnUnvalidatedMalformedDate`
> in `DimseDataAccessTest`, and `malformedStudyDateIsRejected` in
> `XnatCFindScpTest`.

Note the two-layer arrangement: even for SQL-pushed keys, the
Java `matchesQuery` filter runs afterwards
(`query/impl/XnatDicomQueryService.java:58, 83, 102`). Both
layers now match case-insensitively, so the Java pass re-checks
non-pushed keys and the VR-specific semantics SQL does not model
(date/time ranges, UID lists) rather than correcting case.

### 5.6 C-FIND Status Codes

- Success / Pending / final-response are emitted by dcm4che's
  `BasicCFindSCP` / `BasicQueryTask` — no code in this plugin
  writes them explicitly.
- `Status.UnableToProcess` (C000H) on unknown AE:
  `scp/XnatCFindScp.java:66-71`.
- `Status.UnableToProcess` (C000H) with Offending Element on a
  UID list in a Unique Key above the query level:
  `query/QueryLevel.java:63-70` (§5.2).
- `Status.UnableToProcess` (C000H) with Offending Element on a
  malformed DA or TM value: `query/DateTimeKeys.java:68-71` (§5.2).
- `Status.IdentifierDoesNotMatchSOPClass` (A900H) on bad level:
  `scp/XnatCFindScp.java:91-93` and
  `query/QueryLevel.java:82-85, :89-92`.

### 5.7 Non-standard Attribute Mappings

All handled inside the SQL row mappers, with no additional
translation layer:

- **Patient Name @ STUDY** = `experiment_label` (session label):
  `query/impl/DimseDataAccess.java:143`.
- **Patient Name @ PATIENT** = `subject_label` (same column as
  Patient ID): `query/impl/DimseDataAccess.java:218-219`.
- **Patient ID @ STUDY** = subject label within the queried
  project, from the `project_experiments` / subject-label
  sub-select in `sql/search-studies.sql:5-15, 72-82`; column
  mapped at `query/impl/DimseDataAccess.java:144`.
- **Accession Number** = `exptid` (XNAT session ID):
  `query/impl/DimseDataAccess.java:141`.
- **Study ID** = `exptid`: `query/impl/DimseDataAccess.java:142`.
- **Study Description** = the XNAT project ID:
  `query/impl/DimseDataAccess.java:145` sets it from the
  `projectId` parameter of `mapStudyRow`. This matches the
  XNAT DICOMweb plugin (`XnatDicomServiceImpl`, Study
  Description = project ID). Reconciled under R1
  (`CONFORMANCE-RECONCILIATION-PLAN.md`) on 2026-07-23; prior
  to that the row mapper emitted a literal empty string and the
  conformance statement described it as the session note.
- **Referring Physician Name** = always empty — not set by the
  row mapper, so `copyReturnKeys`
  (`query/impl/DimseDataAccess.java:526-533`) emits a zero-length
  value if the SCU requested it.

### 5.8 Gender Mapping

`query/impl/DimseDataAccess.genderToDicomSex`
(`query/impl/DimseDataAccess.java:481-492`).

- Blank / null → `Optional.empty()` (not included in response).
- `m` / `male` (case-insensitive) → `M`.
- `f` / `female` → `F`.
- Any other non-blank → `O`.

### 5.9 Modality Mapping

- On the row-mapper side, `dicomModality`
  (`query/impl/DimseDataAccess.java:497-507`) maps `PET` → `PT`
  and truncates values longer than 16 characters. Used by
  `searchSeries` at `:189`.
- On the SQL side (STUDY level aggregation), the same `PET`→`PT`
  and length-16 truncation appears inside
  `sql/search-studies.sql:38-51` (in the `scan_modalities`
  CTE) before the values are `string_agg`ed and returned as the
  `modalities` column.
- **Reverse mapping for matching (R3).** `dicomToXnatModality`
  (`query/impl/DimseDataAccess.java:518-520`) maps a query value
  of `PT` back to `PET`; `addSeriesFilters` (`:251-261`) applies
  it before building the SERIES `Modality` filter, so a query
  using the DICOM code `PT` matches scans whose XNAT modality is
  `PET`. Other values (and wildcards) pass through unchanged.

### 5.10 File Count Semantics

Every file count uses the same resource rule as C-GET,
`CatalogEntryUtils.isDicomResource` (`CatalogEntryUtils.java:28-32`,
`:81-87`): a resource counts if its label, format or content
contains `dicom` or `secondary`, case-insensitive. In SQL the rule
is one predicate, written identically in all three files:

```sql
LOWER(CONCAT_WS(' ', ar.label, r.format, r.content)) LIKE ANY (ARRAY['%dicom%', '%secondary%'])
```

`format` and `content` belong to `xnat:resource` (`xnat.xsd`), so
they live in `xnat_resource`, joined `LEFT` on
`xnat_abstractresource_id` (core XNAT joins it the same way, e.g.
`ResourceSurveyXftItemEventHandlerMethod`). `CONCAT_WS` skips NULLs,
and the space separator cannot create a false match because neither
keyword contains a space.

- STUDY: `sql/search-studies.sql:35`, in the `scan_file_counts`
  CTE (`:24-37`), already limited to the project's sessions.
- SERIES: `sql/search-series.sql:21`, in a `LATERAL` sub-query
  (`:14-22`) correlated with `sc.xnat_imagescandata_id`.
- PATIENT: `sql/search-patients.sql:44`, in a `LATERAL` sub-query
  (`:37-45`) per scan.

`everyCountUsesTheCGetResourceRule` in `DimseDataAccessTest` checks
that all three files contain the predicate verbatim.

> **Regression hazard.** If `CatalogEntryUtils.isDicomResource`
> changes, change the SQL predicate in all three files and
> `DICOM_RESOURCE_RULE` in the test together; otherwise C-FIND
> counts and C-GET disagree about which resources hold instances.
> Do not count files with an uncorrelated aggregate over
> `xnat_abstractresource`: that reads every resource on the site
> for each query.

Remaining differences between counts and what C-GET sends:

- C-GET also requires the resource to be a catalog resource
  (`CatalogEntryUtils.java:43-47`); the SQL counts any matching
  resource. Scan DICOM resources are catalogs in practice.
- Counts sum `ar.file_count`, every file in the resource; C-GET
  sends only the catalog's DICOM entries (`CatDcmentryBean`,
  `:70-72`) and skips files it cannot read (§6.3).

The unit tests check this SQL only as text; they do not execute it.

---

## 6. C-GET SCP

### 6.1 Retrieval Scope

Scope is enforced by the same `ProjectAeResolver` used by C-FIND
(`scp/XnatCGetScp.java:83-87`) plus `XnatCGetScp.sessionsInProject`
(`scp/XnatCGetScp.java:228-235`), applied to both lookup paths. It
keeps imaging sessions for which `hasProject(projectId)` holds —
XNAT core's owned-or-shared rule — so it matches the C-FIND SQL
scope.

This filter is the only project boundary on the C-GET path: the
DIMSE service account (`ProjectAeResolver.getUser`) is the primary
admin and can read every project, and DIMSE associations carry no
user identity.

> **Regression hazard.** Any new session lookup on the C-GET or
> IMAGE path must go through `sessionsInProject` (or apply
> `hasProject`). In particular, do not return
> `XnatSubjectdata.getExperiments_experiment()` unfiltered: a
> subject shared into the project still lists its sessions in
> every other project, so an unfiltered list would let any AE that
> knows the in-project subject label retrieve sessions never
> shared into the project. Likewise, filtering on
> `projectId.equals(session.getProject())` alone is too narrow —
> it drops sessions shared into the project that C-FIND lists.
> Covered by the `sessionsInProject` cases in `XnatCGetScpTest`.

### 6.2 Instance Identification

`scp/XnatCGetScp.findSessions` (`scp/XnatCGetScp.java:174-194`):

- `StudyInstanceUID` values (space-separated; dcm4che returns
  multi-values as an array) drive
  `XnatImagesessiondata.getXnatImagesessiondatasByField`
  (`:181-184`), filtered by `sessionsInProject` (`:180`).
- Falls back to `PatientID` if no Study UIDs are present
  (`:187-190`) — subject lookup via
  `XnatSubjectdata.GetSubjectByProjectIdentifier`, then the
  subject's experiments filtered by `sessionsInProject`
  (`scp/XnatCGetScp.java:207-217`). A subject shared into the
  project contributes only its sessions owned by or shared into
  the project.
- Neither key present → `A900H` (`:192-193`).

Optional narrowing keys are applied later in `getInstances`:
- `SeriesInstanceUID` filter: `scp/XnatCGetScp.java:126-128`.
- `SOPInstanceUID` filter: `scp/XnatCGetScp.java:130-132`.

### 6.3 Instance Location

`scp/XnatCGetScp.getInstances` at
`scp/XnatCGetScp.java:121-160` implements the five-step traversal
from the conformance statement:

1. `findSessions(projectId, keys, user)` — `:124`.
2. Per-session iteration over `scans_scan`, applying the series
   filter — `:135-142`. Catalogs and files are resolved against
   the session's owning project (`:138`), which differs from the
   Called-AE project for a shared session.
3. DICOM catalog selection —
   `CatalogEntryUtils.dicomCatalogs(scan)` at `:143`. The
   "contains `dicom` or `secondary` (case-insensitive)" rule
   is in `CatalogEntryUtils.matchesDicomDescriptor`
   (`CatalogEntryUtils.java:81-87`); it is applied to the
   label, format, and content descriptors by
   `isDicomResource` (`CatalogEntryUtils.java:28-32`).
4. `CatDcmentryBean` filtering (applying the SOP Instance UID
   filter): `scp/XnatCGetScp.java:145-152` (SOP filter at `:149`).
5. Locator resolution — `FileInstanceLocator.forEntry`
   (`FileInstanceLocator.java:42-57`) opens each file with
   `DicomInputStream`, reads only the File Meta Information,
   and captures SOP Class UID + Transfer Syntax UID
   (`FileInstanceLocator.java:49-52`).

Silent skips:
- File missing on disk → `null` locator at
  `FileInstanceLocator.java:44-47`, filtered out at
  `scp/XnatCGetScp.java:151`.
- I/O failure reading the file meta info → `null` locator at
  `FileInstanceLocator.java:53-56`, likewise filtered.
- Catalog load failure → logged and skipped at
  `scp/XnatCGetScp.java:153-155`.

### 6.4 Transfer Syntax Selection and Transcoding

Selection: `scp/XnatCGetScp.negotiatedTransferSyntax`
(`scp/XnatCGetScp.java:269-285`).

- Prefer the file's native TS if it was negotiated for the SOP
  class (`:271-274`).
- Fall back through
  `FALLBACK_TRANSFER_SYNTAXES = {EVR LE, IVR LE, EVR BE}`
  (`scp/XnatCGetScp.java:243-247`) — the first negotiated one
  wins (`:275-280`).
- No match → return the native TS (`:284`) and log a warning
  (`:282-283`); dcm4che will then fail the sub-operation.

Transcoding: `scp/XnatCGetScp.createDataWriter`
(`scp/XnatCGetScp.java:100-109`).

- Guard: only if `tsuid != inst.tsuid` and both are uncompressed
  (`:101`). `isUncompressed` is a set lookup against
  `UNCOMPRESSED_TRANSFER_SYNTAXES` at `:218-222` (implemented at
  `:236-238`).
- Actual transcoding is a full parse-and-reserialize: read
  metadata + dataset via `DicomInputStream`
  (`:102-106`) and hand it to `DataWriterAdapter`, which
  serializes in whatever TS dcm4che was told to write. There is
  no streaming path.
- Any other combination (compressed source, compressed target,
  or matching TS) delegates to `super.createDataWriter`
  (`:108`).

### 6.5 C-GET Status Codes

- Success / Pending / Warning / final-response are all emitted by
  dcm4che's `BasicRetrieveTask` framework.
- `UnableToProcess` (C000H) on unknown AE:
  `scp/XnatCGetScp.java:83-87`.
- `IdentifierDoesNotMatchSOPClass` (A900H) when neither Study UID
  nor Patient ID is present: `scp/XnatCGetScp.java:192-193`.
- `A701H` (out of resources) and `A702H` (unable to perform
  sub-operations) are surfaced only via dcm4che defaults; no
  application code emits them.

---

## 7. Security

### 7.1 Authentication

No code path validates the Calling AE Title. Service-account
identity is delegated to `XnatUserProvider` via
`scp/ProjectAeResolver.java:49-51` and passed into query/retrieve
methods (`scp/XnatCFindScp.java:73`,
`scp/XnatCGetScp.java:123`).

### 7.2 Authorization

Access is gated at two points:

- Project resolution: `scp/ProjectAeResolver.java:60-80`. Unknown
  projects → `Optional.empty()` (translated to `UnableToProcess`
  by the SCP).
- `ProjectAeResolver.resolve` additionally calls
  `_preferences.isProjectEnabled(project)` before returning the
  project (`scp/ProjectAeResolver.java:74-77`; logic in
  `DimsePreferences.isProjectEnabled` — §8.4).

There is no per-user permission filtering: the service account can
read every project. After the project gate, each path restricts
results to data owned by or shared into the project — C-FIND in
SQL (`sql/search-*.sql`), IMAGE level and C-GET via
`hasProject` (§5.3.4, §6.1). That project-scope filter is the
only data boundary between projects.

### 7.4 Audit

SLF4J only. `@Slf4j` classes:
- `scp/DimseScpService.java:35` — start / stop at INFO
  (`:245, :275`).
- `scp/XnatCFindScp.java:30` — request logging at DEBUG
  (`:59, :73, :103`), reject at WARN (`:66`).
- `scp/XnatCGetScp.java:51` — DEBUG on request / transcoding
  (`:80, :89, :102`), WARN on missing negotiation (`:263-264`).
- `scp/ProjectAeResolver.java:25` — DEBUG on resolution
  results (`:71, :75, :78`).
- Query implementation: `query/impl/XnatDicomQueryService.java`
  and `query/impl/DimseDataAccess.java` log query bodies at
  TRACE and errors at ERROR.

There is no PS3.15 A.5 audit-trail record emitter.

---

## 8. Configuration

### 8.1 Network

- Default port and property name:
  `config/DimsePreferences.java:68-71` (`@NrgPreference(defaultValue = "9104")`).
- Applied on plugin construction:
  `DimsePlugin.java:43-51` — `scpService.start(preferences.getPort())`.
- Changes take effect on next SCP restart because the port is
  read only when `DimseScpService.start(int)` is called; the
  `applyEnabledState` toggle in `DimsePrefsApi.java:202-212`
  uses the port at call time.

### 8.2 AE Title

There is no configuration for AE title — see the wildcard AE in
`scp/DimseScpService.java:223`.

### 8.3 Service Account

The user identity comes from an `XnatUserProvider` autowired
into `ProjectAeResolver` (`scp/ProjectAeResolver.java:37-42`)
and exposed via `getUser()` (`:49-51`). The constructor takes
`XnatUserProvider` with no `@Qualifier`, so Spring injects XNAT
core's `@Primary` bean — `primaryAdminUserProvider` (defined in
`xnat-web` `ApplicationConfig`) — which resolves to the site's
primary administrator account. No `DimsePreferences` property
controls this, and there is no plugin-level selection of a
different or dedicated service account.

### 8.4 Access Control Settings

`DimsePreferences`:

- Sitewide enable: `isEnabled()` (`config/DimsePreferences.java:45-48`)
  / `setEnabled(boolean)` (`:55-61`). Default `true`.
- Per-project default: `isEnabledByDefault()` (`:91-94`) /
  `setEnabledByDefault(boolean)` (`:101-107`). Default `true`.
- Per-project override: `isProjectEnabled(String)` (`:118-122`),
  `setProjectEnabled(String, boolean)` (`:130-138`),
  `deleteProjectEnabled(String)` (`:145-152`),
  `hasProjectOverride(String)` (`:160-162`). Backed by
  `Scope.Project`-scoped preference reads / writes.
- Resolver hook: the `isProjectEnabled` gate inside `resolve` —
  `scp/ProjectAeResolver.java:74-77`.

#### 8.4.1 REST API — `DimsePrefsApi`

All handlers are annotated with `@XapiRequestMapping`, which
supplies the `/xapi/` prefix.

- `GET /dicom-qr-scp/prefs` (Admin) — `xapi/DimsePrefsApi.java:65-75`.
- `POST /dicom-qr-scp/prefs` (Admin) — `xapi/DimsePrefsApi.java:85-109`.
  On `enabled` change, `applyEnabledState`
  (`xapi/DimsePrefsApi.java:202-212`) starts or stops the SCP
  live.
- `GET /dicom-qr-scp/{projectId}` (Authenticated, admin or owner)
  — `xapi/DimsePrefsApi.java:123-138`. Emits the three-valued
  `enabled` string (`"default"`, `"true"`, `"false"`).
- `POST /dicom-qr-scp/{projectId}` — `xapi/DimsePrefsApi.java:154-175`.
  `"default"` deletes the override; other values call
  `setProjectEnabled`.
- Owner check: `requireProjectOwner`
  (`xapi/DimsePrefsApi.java:185-195`).

---

## 9. Known Limitations

The nine numbered limitations in the conformance statement are
consequences of the code paths already annotated above:

1. No C-MOVE — `scp/DimseScpService.java:295-301`.
2. No compressed transfer syntaxes — §6.4.
3. 16-character AE title — dcm4che PDU constraint (no plugin
   code).
4. C-FIND / C-GET attribute mismatch — §5.7.
5. No Specific Character Set — not exercised anywhere; strings
   are handled as Java `String` throughout.
6. PATIENT-level counts — `sql/search-patients.sql:24-54`.
7. No partial retrieval by query level — `scp/XnatCGetScp.java`
   does not read `QueryRetrieveLevel` before applying key
   filters (the value is only logged, at `:78-80`).
8. File count vs UI — see §5.10.
9. Shared sessions — union in `sql/search-studies.sql:5-15`;
   subject-label subquery joins both `xnat_subjectdata` and
   `xnat_projectparticipant` at `sql/search-studies.sql:72-82`.

---

## Verification Pointers

The following source files were verified against the conformance
statement at the date below. Any subsequent re-verification pass
should start here.

- `src/main/java/org/nrg/xnat/dimse/DimsePlugin.java` (the
  preference-aware constructor at `:43-51`)
- `src/main/java/org/nrg/xnat/dimse/scp/DimseScpService.java`
  (focused on `:41-45` transfer syntaxes, `:59-175`
  storage SOP classes, `:208-246` startup, `:295-301`
  service registry)
- `src/main/java/org/nrg/xnat/dimse/scp/ProjectAeResolver.java`
  (focused on `:60-80` resolve and the `:74-77` enable gate)
- `src/main/java/org/nrg/xnat/dimse/scp/XnatCFindScp.java`
- `src/main/java/org/nrg/xnat/dimse/scp/XnatCGetScp.java`
  (focused on `:71-111` retrieve task, `:121-157` catalog
  walk, `:171-193` session lookup, `:218-266` TS
  selection/transcoding)
- `src/main/java/org/nrg/xnat/dimse/query/QueryLevel.java`
- `src/main/java/org/nrg/xnat/dimse/query/DicomMatchUtils.java`
- `src/main/java/org/nrg/xnat/dimse/query/DicomQueryService.java`
- `src/main/java/org/nrg/xnat/dimse/query/impl/XnatDicomQueryService.java`
- `src/main/java/org/nrg/xnat/dimse/query/impl/DimseDataAccess.java`
  (focused on `:117-147, :157-182, :191-220` row mappers;
  `:260-398` filter builders; `:453-492` gender/modality
  mapping; `:498-505` `copyReturnKeys`)
- `src/main/java/org/nrg/xnat/dimse/CatalogEntryUtils.java`
- `src/main/java/org/nrg/dcm/FileInstanceLocator.java`
- `src/main/java/org/nrg/xnat/dimse/config/DimsePreferences.java`
- `src/main/java/org/nrg/xnat/dimse/xapi/DimsePrefsApi.java`
- `src/main/resources/sql/search-studies.sql`
- `src/main/resources/sql/search-series.sql`
- `src/main/resources/sql/search-patients.sql`

---

## Standard Verification

DICOM standard references in the conformance statement have
**not** been re-verified against
`dicom.nema.org/medical/dicom/current/output/html/` as part of
this annotation pass. Spec-level claims — SOP Class UIDs, VR
rules, matching semantics per PS3.4 C.2.2.2, PATIENT/STUDY/SERIES
information models per PS3.4 C.6.x — are inherited from the
conformance statement itself and should be verified against the
standard before the next release.

**Exception (2026-07-23).** The return-key / no-value semantics
cited in §5.4 *were* verified this pass against
dicom.nema.org/medical/dicom/current/output/: PS3.4 §C.2.2.1
(Q/R Unique / Required / Optional key types) and §C.4.1.1.3.2
Note 2 (zero-length no-value in the Response Identifier), plus
PS3.18 §10.6 → §8.3.4.1 (QIDO reuses the PS3.4 matching model).
All other spec-level claims remain unverified as above.

Previously-known deviation, now resolved:

- **Study Description** — earlier drafts emitted a literal empty
  string while §5.7 claimed the XNAT session note. Reconciled
  under R1 (`CONFORMANCE-RECONCILIATION-PLAN.md`) on 2026-07-23:
  `query/impl/DimseDataAccess.java:145` now sets the project ID,
  and §0 / §5.3.2 / §5.7 of the conformance statement were
  updated to match.

---

**Date:** 2026-07-23
</content>
