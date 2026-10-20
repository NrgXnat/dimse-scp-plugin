# DICOM Conformance Statement

## XNAT DIMSE Plugin

**Product:** XNAT DIMSE Plugin  
**Version:** 0.1.0-SNAPSHOT  
**DICOM Standard Version:** PS3.x-2024c  
**Date:** 2026-05-04

---

## 0. Important Warnings

This section highlights behaviors that may surprise experienced
DICOM users. Read this section before deploying.

1. **No C-MOVE.** Only C-GET retrieval is supported. SCUs that
   default to C-MOVE (e.g., many PACS workstations) will fail
   unless explicitly configured for C-GET.

2. **Called AE Title = XNAT project ID.** The Called AE Title
   is interpreted as an XNAT project identifier or alias, not
   a preconfigured AE title string. Any string that matches an
   XNAT project with DIMSE access enabled is accepted.
   Associations addressed to a non-existent project or a
   project with DIMSE access disabled are rejected.

3. **No compressed transfer syntaxes.** Only uncompressed
   transfer syntaxes are registered. The SCP transcodes
   between uncompressed syntaxes (e.g., Implicit VR LE to
   Explicit VR LE), but cannot transcode to or from
   compressed syntaxes. Files stored with a compressed
   transfer syntax are not retrievable unless the SCU
   negotiates that exact syntax.

4. **Single service account.** All associations operate under a
   single XNAT service account configured at the application
   level. There is no per-association authentication. The
   Calling AE Title is logged but not validated.

5. **C-FIND results do not match C-GET file contents.** C-FIND
   responses are populated from XNAT's data model, not from
   the original DICOM headers. The DICOM files returned by
   C-GET retain their original header values. This means
   attribute values in C-FIND results will generally differ
   from the corresponding values in retrieved files. For
   example, Accession Number in C-FIND is the XNAT session
   ID, but the retrieved file will contain whatever Accession
   Number was in the original DICOM data. Expected
   non-standard mappings in C-FIND include:
   - Patient Name = XNAT session label (at STUDY level) or
     subject label (at PATIENT level)
   - Patient ID = XNAT subject label within the project
   - Accession Number = XNAT session ID
   - Study ID = XNAT session ID
   - Study Description = XNAT project ID
   Furthermore, any fields returned from C-FIND may differ
   from the corresponding values in the DICOM storage
   instances if post-archival modifications are made to the
   session or files.

---

## 1. Overview

### 1.1 Application Description

The XNAT DIMSE Plugin adds a DICOM Query/Retrieve SCP to an
XNAT installation. It allows DICOM clients to discover and
retrieve imaging data archived in XNAT using standard DICOM
network operations.

The plugin scopes all operations to a single XNAT project,
identified by the Called AE Title of the incoming association.

### 1.2 Supported SOP Classes

#### 1.2.1 Verification

| SOP Class          | UID                | Role |
|--------------------|--------------------|------|
| Verification       | 1.2.840.10008.1.1  | SCP  |

#### 1.2.2 Query/Retrieve

| SOP Class                                        | UID                              | Role |
|--------------------------------------------------|----------------------------------|------|
| Study Root Query/Retrieve Information Model FIND  | 1.2.840.10008.5.1.4.1.2.2.1     | SCP  |
| Study Root Query/Retrieve Information Model GET   | 1.2.840.10008.5.1.4.1.2.2.3     | SCP  |
| Patient Root Query/Retrieve Information Model FIND| 1.2.840.10008.5.1.4.1.2.1.1     | SCP  |
| Patient Root Query/Retrieve Information Model GET | 1.2.840.10008.5.1.4.1.2.1.3     | SCP  |

#### 1.2.3 Not Supported

The following commonly expected services are **not**
implemented:

- C-MOVE (neither Study Root nor Patient Root)
- C-STORE SCP (the plugin does not receive images)
- Modality Worklist
- Storage Commitment

### 1.3 Specific Character Set

No Specific Character Set negotiation is performed. Query
results are populated from XNAT's internal database, which
stores text as UTF-8. No character set conversion is applied
to query results. DICOM files returned via C-GET preserve
their original character set encoding.

---

## 2. Implementation Model

### 2.1 Application Data Flow

```
                       DICOM Network
                            |
                    +-------v--------+
                    |  DIMSE SCP     |
                    |  (C-ECHO,      |
                    |   C-FIND,      |
                    |   C-GET)       |
                    +-------+--------+
                            |
                   Called AE Title
                     resolved to
                    XNAT project
                            |
                    +-------v--------+
                    |  XNAT Archive  |
                    |  (database +   |
                    |   file system) |
                    +----------------+
```

### 2.2 Functional Definition

The SCP accepts associations addressed to any Called AE Title.
At request time, the Called AE Title is resolved to an XNAT
project. If no matching project exists or the project has
DIMSE access disabled (see section 8.4), the request is
rejected.

### 2.3 Sequencing Constraints

The SCP processes requests sequentially within each
association. Multiple concurrent associations are supported,
with no fixed limit on the number of simultaneous associations.

---

## 3. Association Establishment

### 3.1 General Parameters

| Parameter                   | Value                          |
|-----------------------------|--------------------------------|
| TCP port (default)          | 9104                           |
| Maximum PDU size            | dcm4che default (16 KB)        |
| Implementation Class UID    | dcm4che default                |
| Implementation Version Name | dcm4che default                |
| TLS                         | Not supported                  |
| SCP/SCU Role Negotiation    | Not supported                  |
| Asynchronous Operations     | Not supported                  |

### 3.2 Called AE Title Handling

The SCP does not maintain a list of configured AE titles.
Instead, the Called AE Title from the A-ASSOCIATE-RQ is
treated as an XNAT project identifier (ID or alias) and
looked up at association time. This means:

- Any XNAT project ID or project alias is a valid Called AE
  Title.
- AE titles that do not match a project are rejected at
  request time (the association itself is accepted; the
  C-FIND or C-GET request returns status C000H Unable to
  Process).
- DICOM AE titles are limited to 16 characters. XNAT project
  IDs longer than 16 characters cannot be addressed. A
  project alias of 16 characters or fewer can be used as a
  workaround.

### 3.3 Calling AE Title Handling

The Calling AE Title is logged but not validated. Any Calling
AE Title is accepted.

### 3.4 Presentation Contexts — SCP Role

The following Abstract Syntaxes are accepted as SCP:

| Abstract Syntax                               | Transfer Syntaxes Accepted                  |
|-----------------------------------------------|---------------------------------------------|
| Verification (1.2.840.10008.1.1)              | IVR LE, EVR LE, EVR BE                      |
| Study Root Q/R FIND (1.2.840.10008.5.1.4.1.2.2.1)  | IVR LE, EVR LE, EVR BE                |
| Study Root Q/R GET (1.2.840.10008.5.1.4.1.2.2.3)   | IVR LE, EVR LE, EVR BE                |
| Patient Root Q/R FIND (1.2.840.10008.5.1.4.1.2.1.1) | IVR LE, EVR LE, EVR BE               |
| Patient Root Q/R GET (1.2.840.10008.5.1.4.1.2.1.3)  | IVR LE, EVR LE, EVR BE               |

Transfer Syntax UIDs:

| Abbreviation | Transfer Syntax UID       |
|--------------|---------------------------|
| IVR LE       | 1.2.840.10008.1.2         |
| EVR LE       | 1.2.840.10008.1.2.1       |
| EVR BE       | 1.2.840.10008.1.2.2       |

No compressed transfer syntaxes are accepted or proposed.

### 3.5 Presentation Contexts — SCU Role (C-GET Sub-operations)

For C-GET retrieval, the SCP sends C-STORE sub-operations to
the SCU over the same association. The SCU must propose
Storage SOP Class presentation contexts for the data it
wishes to receive. The SCP accepts the following Storage SOP
Classes as SCU (i.e., it will send C-STORE for these classes):

**CR / DX / Mammography / Intra-oral:**
Computed Radiography, Digital X-Ray (Presentation and
Processing), Digital Mammography X-Ray (Presentation and
Processing), Digital Intra-oral X-Ray (Presentation and
Processing)

**CT:**
CT Image, Enhanced CT, Legacy Converted Enhanced CT

**MR:**
MR Image, Enhanced MR, MR Spectroscopy, Enhanced MR Color,
Legacy Converted Enhanced MR

**US:**
Ultrasound Image, Ultrasound Multi-frame, Enhanced US Volume

**NM / PET:**
Nuclear Medicine Image, Positron Emission Tomography Image,
Enhanced PET, Legacy Converted Enhanced PET

**XA / XRF:**
X-Ray Angiographic, Enhanced XA, X-Ray Radiofluoroscopic,
Enhanced XRF, X-Ray 3D Angiographic, X-Ray 3D Craniofacial,
Breast Tomosynthesis

**Secondary Capture:**
Secondary Capture, Multi-frame Single Bit SC, Multi-frame
Grayscale Byte SC, Multi-frame Grayscale Word SC, Multi-frame
True Color SC

**VL / Microscopy:**
VL Endoscopic, Video Endoscopic, VL Microscopic, Video
Microscopic, VL Slide Coordinates Microscopic, VL
Photographic, Video Photographic, VL Whole Slide Microscopy

**Ophthalmology:**
Ophthalmic Photography 8-Bit, Ophthalmic Photography 16-Bit,
Ophthalmic Tomography, Stereometric Relationship

**Waveforms:**
12-Lead ECG, General ECG, Ambulatory ECG, Hemodynamic,
Cardiac Electrophysiology, Basic Voice Audio

**Presentation State:**
Grayscale Softcopy PS, Color Softcopy PS, Pseudo-Color
Softcopy PS, Blending Softcopy PS

**Structured Reports:**
Basic Text SR, Enhanced SR, Comprehensive SR, Comprehensive
3D SR, Procedure Log, Mammography CAD SR, Key Object
Selection, Chest CAD SR, X-Ray Radiation Dose SR

**Radiation Therapy:**
RT Image, RT Dose, RT Structure Set, RT Beams Treatment
Record, RT Plan, RT Brachy Treatment Record, RT Treatment
Summary Record, RT Ion Plan, RT Ion Beams Treatment Record

**Segmentation / Registration:**
Segmentation, Spatial Registration, Spatial Fiducials,
Deformable Spatial Registration, Real World Value Mapping,
Parametric Map

**Other:**
Raw Data, Encapsulated PDF, Encapsulated CDA

**Vendor-specific:**
Siemens CSA Non-Image Storage (1.3.12.2.1107.5.9.1)

All Storage SOP Classes use the same three uncompressed
transfer syntaxes (IVR LE, EVR LE, EVR BE).

**Note:** If the SCU proposes only compressed transfer
syntaxes for a Storage SOP Class, the SCP will be unable to
send instances of that class. The SCP transcodes between
uncompressed syntaxes but cannot decompress or compress.
If the file's native transfer syntax was not negotiated,
the SCP falls back to another negotiated uncompressed
syntax (preferring EVR LE, then IVR LE, then EVR BE) and
transcodes on the fly. If no uncompressed syntax was
negotiated, the sub-operation will fail.

**Note:** Data archived in XNAT whose SOP Class is not in
this list cannot be retrieved via C-GET, because no
presentation context will be available for the C-STORE
sub-operation.

---

## 4. C-ECHO SCP

The Verification SOP Class (C-ECHO) is supported using dcm4che's
standard implementation, with no application-specific processing;
the SCP responds with status Success (0000H) if the association
was accepted.

---

## 5. C-FIND SCP

### 5.1 Query Levels

Both Study Root and Patient Root Information Models are
supported. The following query levels are available:

| Level   | Patient Root | Study Root |
|---------|:----------:|:--------:|
| PATIENT | Yes        | —        |
| STUDY   | Yes        | Yes      |
| SERIES  | Yes        | Yes      |
| IMAGE   | Yes        | Yes      |

### 5.2 Query/Retrieve Level Matching

The QueryRetrieveLevel attribute (0008,0052) is required in
all C-FIND requests. The value "INSTANCE" is accepted as a
synonym for "IMAGE". Matching is case-insensitive.

### 5.3 Matching Keys and Return Keys

#### 5.3.1 PATIENT Level

The PATIENT level is only available through the Patient Root
Information Model. It returns XNAT subjects within the
project.

| DICOM Attribute                    | Tag          | Matching | Return | XNAT Source                |
|------------------------------------|--------------|----------|--------|----------------------------|
| Patient ID                         | (0010,0020)  | S, W     | R      | Subject label in project   |
| Patient Name                       | (0010,0010)  | S, W     | R      | Subject label              |
| Patient Sex                        | (0010,0040)  | —        | O      | Subject gender (see 5.8)   |
| Patient Birth Date                 | (0010,0030)  | —        | O      | Subject DOB                |
| Number of Patient Related Studies  | (0020,1200)  | —        | O      | Count of imaging sessions  |
| Number of Patient Related Series   | (0020,1202)  | —        | O      | Sum of scans across sessions|
| Number of Patient Related Instances| (0020,1204)  | —        | O      | Sum of files across scans  |

#### 5.3.2 STUDY Level

| DICOM Attribute                    | Tag          | Matching | Return | XNAT Source                |
|------------------------------------|--------------|----------|--------|----------------------------|
| Study Instance UID                 | (0020,000D)  | S, L     | R      | Session UID                |
| Study Date                         | (0008,0020)  | S, R     | R      | Session date               |
| Study Time                         | (0008,0030)  | —        | O      | Session time               |
| Accession Number                   | (0008,0050)  | S, W     | R      | **Session ID** (see 5.7)   |
| Study ID                           | (0020,0010)  | —        | O      | Session ID                 |
| Study Description                  | (0008,1030)  | —        | O      | Project ID (see 5.7)       |
| Patient Name                       | (0010,0010)  | S, W     | R      | **Session label** (see 5.7)|
| Patient ID                         | (0010,0020)  | S, W     | R      | Subject label              |
| Patient Sex                        | (0010,0040)  | —        | O      | Subject gender (see 5.8)   |
| Patient Birth Date                 | (0010,0030)  | —        | O      | Subject DOB                |
| Modalities in Study                | (0008,0061)  | S        | O      | Derived from scans (see 5.5)|
| Number of Study Related Series     | (0020,1206)  | —        | O      | Scan count                 |
| Number of Study Related Instances  | (0020,1208)  | —        | O      | File count (see 5.10)      |
| Referring Physician Name           | (0008,0090)  | —        | O      | Always empty               |

#### 5.3.3 SERIES Level

The query must include a Study Instance UID.

| DICOM Attribute                      | Tag          | Matching | Return | XNAT Source            |
|--------------------------------------|--------------|----------|--------|------------------------|
| Study Instance UID                   | (0020,000D)  | S        | R      | From query key         |
| Series Instance UID                  | (0020,000E)  | S        | R      | Scan UID               |
| Modality                             | (0008,0060)  | S        | R      | Scan modality (see 5.9)|
| Series Number                        | (0020,0011)  | —        | O      | Scan ID                |
| Series Description                   | (0008,103E)  | —        | O      | Scan series description|
| Number of Series Related Instances   | (0020,1209)  | —        | O      | File count (see 5.10)  |

#### 5.3.4 IMAGE Level

The query must include a Study Instance UID and a Series
Instance UID.

| DICOM Attribute     | Tag          | Matching | Return | XNAT Source                  |
|---------------------|--------------|----------|--------|------------------------------|
| Study Instance UID  | (0020,000D)  | S        | R      | From query key               |
| Series Instance UID | (0020,000E)  | S        | R      | From query key               |
| SOP Instance UID    | (0008,0018)  | S        | R      | Catalog entry UID            |
| SOP Class UID       | (0008,0016)  | —        | O      | Catalog entry metafield      |
| Instance Number     | (0020,0013)  | —        | O      | Catalog entry instance number|

**Key for Matching column:**

| Code | Meaning                                      |
|------|----------------------------------------------|
| S    | Single Value Matching (exact match)           |
| W    | Wildcard Matching (* and ?)                   |
| R    | Range Matching (date/time ranges)             |
| L    | UID List Matching (backslash-separated UIDs)  |
| —    | Universal matching only (not filterable)       |

**Key for Return column:**

| Code | Meaning                                       |
|------|-----------------------------------------------|
| R    | Always returned                                |
| O    | Returned if available                          |

### 5.4 Return Key Handling

All attributes present in the C-FIND Identifier are included
in each result, per PS3.4 C.2.2.1. Attributes not populated
by the SCP are returned with zero length.

### 5.5 Matching Semantics

- **Single Value Matching:** Case-insensitive for all string
  attributes.
- **Wildcard Matching:** `*` matches any sequence of
  characters, including none; `?` matches a single character.
  Available for string VRs (not Date or Time, see below), and
  case-insensitive. A value of
  `*` alone is equivalent to Universal Matching (PS3.4
  C.2.2.2.4): it also matches entities whose value for that
  attribute is empty.
- **Date Range Matching:** Format `YYYYMMDD-YYYYMMDD`. Either
  bound, but not both, may be omitted for an open-ended range
  (PS3.4 C.2.2.2.5).
- **Date and Time values are validated.** Every non-empty value
  of every Date (DA) or Time (TM) key in the Identifier must be
  an exact value or a range, `<v1>-<v2>`, `-<v1>` or `<v1>-`,
  with `<v1>` not later than `<v2>`. A Date must be `YYYYMMDD`
  naming a real calendar date; a Time must be
  `HH[MM[SS[.FFFFFF]]]` (PS3.5 Table 6.2-1), where seconds may
  be `60` (a leap second) only in minute 59, at any hour:
  `235960` and `115960` are accepted, `103060` is not. Leap
  seconds are inserted at 23:59:60 UTC, which a whole-hour offset
  from UTC renders in minute 59; a time zone with a fractional-hour
  offset (for example +05:30, `HH:29:60`) cannot query its leap
  second, and the date is not checked. Anything else is
  rejected with status C000H, with Offending Element (0000,0901)
  identifying the key: for example `202001`, `20201301-`,
  `103060`, a reversed range, `-`, any wildcard including a lone `*`, and
  `""` (Empty Value Matching is not supported). A zero-length
  value is still Universal Matching.
- **UID List Matching:** Backslash-separated UID values per
  PS3.4 C.2.2.2.2; an entity matches if its UID exactly equals
  any UID in the list. A list is accepted only in the Unique Key
  of the Query/Retrieve level itself (PS3.4 C.4.1.2.1). A request with
  more than one value in a Unique Key *above* that level — Study
  Instance UID at SERIES or IMAGE level, or Series Instance UID
  at IMAGE level — is rejected with status C000H, with Offending
  Element (0000,0901) identifying the multi-valued attribute.
- **Universal Matching:** A zero-length query value matches all
  candidates.
- **Multi-valued Attribute Matching:** For an attribute that may
  hold multiple values — notably Modalities in Study (0008,0061)
  — a single query value matches if any of the study's values
  matches it (for example, a `PT` query matches a study that
  contains both CT and PT).
- **Multiple values in a query key:** Extended Negotiation of
  Multiple Value Matching (PS3.4 C.2.2.2.8) is not supported,
  and without it the semantics of multiple values in a Key
  Attribute are undefined (C.2.2.2.8 Note 4). This SCP matches
  an entity if *any* query value matches any of its values, so
  `MR\CT` and `CT\MR` return the same entities: series of either
  modality. Each value is matched with the rules above (for
  example, `SM*\JONES` or two date ranges). Zero-length values
  in the list are ignored; a value of `*` makes the key
  universal. This differs from negotiated Multiple Value
  Matching, which would require every query value to match.
  Lists of UIDs follow UID List Matching above.
- **Relational queries** are not supported.

### 5.6 C-FIND Status Codes

| Status     | Code  | Meaning                              |
|------------|-------|--------------------------------------|
| Success    | 0000H | Query completed                      |
| Pending    | FF00H | Matches remaining                    |
| Failure    | C000H | Unable to Process (e.g., unknown AE) |
| Failure    | A900H | Identifier does not match SOP class  |

C000H is returned for an unknown Called AE Title, when a
Unique Key above the Query/Retrieve level holds more than one
value, and when a Date or Time key value is malformed (see 5.5);
in the latter two cases the response includes Offending Element
(0000,0901) and an Error Comment. A900H is
returned when the Query/Retrieve Level is missing or
unsupported.

### 5.7 Non-standard Attribute Mappings

C-FIND responses are populated from XNAT's data model, not
from the original DICOM file headers. **The DICOM files
returned by C-GET retain their original header values.** This
means attribute values returned by C-FIND will often differ
from the corresponding values in retrieved instances.
SCUs that match C-FIND results against retrieved file
contents should expect mismatches in the attributes listed
below.

- **Patient Name (0010,0010) at STUDY level** is the XNAT
  session label, not the patient's name. At PATIENT level it
  is the subject label. This is because XNAT's data model
  does not store original DICOM Patient Name values in a
  directly queryable field.

- **Accession Number (0008,0050)** is the XNAT session ID
  (an internal identifier like `XNAT_E00001`), not the
  clinical accession number. Retrieved files will contain
  whatever Accession Number was in the original DICOM data.

- **Study ID (0020,0010)** is the XNAT session ID.

- **Study Description (0008,1030)** is the XNAT project ID
  (the same value used as the Called AE Title to scope the
  query), not a clinical study description.

- **Referring Physician Name (0008,0090)** is always empty.

### 5.8 Gender Mapping

XNAT gender values are mapped to DICOM Patient Sex:

| XNAT Value          | DICOM Value |
|---------------------|-------------|
| `m`, `male`         | `M`         |
| `f`, `female`       | `F`         |
| Any other non-empty | `O`         |
| Empty / null        | Not returned|

### 5.9 Modality Mapping

XNAT modality values are mapped to DICOM with two
adjustments:

- `PET` is converted to `PT` (the standard DICOM code for
  Positron Emission Tomography).
- Values longer than 16 characters are truncated to 16.

The `PET`→`PT` conversion is also reversed for matching: a
`Modality` query key of `PT` (SERIES level) matches scans whose
XNAT modality is `PET`.

### 5.10 File Count Semantics

Number of Study Related Instances, Number of Series Related
Instances, and Number of Patient Related Instances count
files in the scan resources that C-GET retrieves from (6.3):
resources whose label, format, or content contains `DICOM` or
`secondary`, ignoring case (for example `DICOM`, `DICOM_ORIG`,
`secondary`, or any resource with format `DICOM`). Other
resources, such as `SNAPSHOTS`, are excluded. Every file in a
counted resource is included, so a count can exceed the
instances a C-GET returns if a resource also holds non-DICOM
files. File counts may also differ from the total number of
files visible in the XNAT UI.

Number of Patient Related Studies counts the subject's imaging
sessions in the project; other experiments are not studies.

---

## 6. C-GET SCP

### 6.1 Retrieval Scope

C-GET retrieval is scoped to the XNAT project identified by
the Called AE Title. Only instances archived within that
project (or shared into it) are retrievable.

### 6.2 Instance Identification

The C-GET Identifier must contain at least one of:

- **Study Instance UID (0020,000D):** Retrieves all instances
  in the matching study (session). Multiple UIDs may be
  provided.
- **Patient ID (0010,0020):** Retrieves all instances for the
  matching subject (identified by subject label within the
  project). For a subject shared into the project, only that
  subject's sessions owned by or shared into the project are
  retrieved; sessions the subject has in other projects are not.

If neither is present, the SCP returns status A900H
(Identifier Does Not Match SOP Class).

Optional narrowing keys:

- **Series Instance UID (0020,000E):** If present, only
  instances from matching series (scans) are returned.
- **SOP Instance UID (0008,0018):** If present, only
  matching instances are returned.

### 6.3 Instance Location

Within the resolved project, C-GET returns the DICOM entries
of scan catalog resources whose label, format, or content
contains `DICOM` or `secondary`, ignoring case (the same rule
as the file counts in section 5.10).
Data held under other resource types is not retrievable via
C-GET, which may cause a session to return fewer instances (or
none) than its C-FIND counts suggest.

If an instance's file cannot be located on disk or cannot be
read, that instance is silently skipped — omitted from the
retrieval rather than reported as a failed sub-operation.

### 6.4 Transfer Syntax Selection and Transcoding

For each C-STORE sub-operation, the SCP selects a transfer
syntax as follows:

1. If the file's native transfer syntax was negotiated on the
   association, use it (no transcoding needed).
2. Otherwise, try the following in order:
   - Explicit VR Little Endian (1.2.840.10008.1.2.1)
   - Implicit VR Little Endian (1.2.840.10008.1.2)
   - Explicit VR Big Endian (1.2.840.10008.1.2.2)
3. If none of the above were negotiated for the SOP Class,
   the sub-operation will fail.

**Uncompressed transcoding.** When the selected transfer
syntax differs from the file's native syntax and both are
uncompressed (IVR LE, EVR LE, or EVR BE), the SCP transcodes
on the fly. This is transparent to the SCU.

**Compressed data.** Transcoding to or from compressed
transfer syntaxes (e.g., JPEG 2000, JPEG-LS, RLE) is not
supported. Files stored with a compressed transfer syntax
can only be retrieved if the SCU negotiates that exact
syntax.

### 6.5 C-GET Status Codes

| Status     | Code  | Meaning                              |
|------------|-------|--------------------------------------|
| Success    | 0000H | All sub-operations completed         |
| Pending    | FF00H | Sub-operations remaining             |
| Warning    | B000H | One or more sub-operations failed    |
| Failure    | C000H | Unable to Process (e.g., unknown AE) |
| Failure    | A900H | Identifier does not match SOP class  |
| Failure    | A701H | Refused: out of resources (unable to calculate number of matches) |
| Failure    | A702H | Refused: unable to perform sub-operations |

Status codes for individual C-STORE sub-operations are
determined by the SCU's C-STORE SCP and by dcm4che's retrieve
handling.

---

## 7. Security

### 7.1 Authentication

There is no DICOM-level authentication. The plugin does not
implement User Identity Negotiation (PS3.7 D.3.3.7). All
DICOM operations execute as a single XNAT user (currently the
primary administrator account; see section 8.3).

The Calling AE Title is not validated and is not used for
access control.

### 7.2 Authorization

Access control is based on the Called AE Title → XNAT project
mapping and the DIMSE access settings (see section 8.4).

DIMSE access must be enabled for the project. A project may
be explicitly enabled or disabled, or it may follow the
sitewide default. If DIMSE access is disabled for the
project, the request is rejected.

Within an accessible project, all data is visible. There is
no per-subject, per-session, or per-scan access control at
the DICOM level.

### 7.3 Transport Security

TLS is not supported. All communication is over plain TCP.

### 7.4 Audit

DICOM operations are recorded in the XNAT application logs.
C-FIND and C-GET requests are logged at DEBUG level with the
Calling AE Title, Called AE Title, and query level. SCP
lifecycle events (start, stop) and preference changes are
logged at INFO level. Lower-level association details are
available at DEBUG level from the underlying dcm4che networking
layer.

There is no DICOM Audit Trail (PS3.15 A.5).

---

## 8. Configuration

All settings are managed through the XNAT admin UI
(**Administer > Plugin Settings > DIMSE SCP**) and via the
REST API at `/xapi/dicom-qr-scp/`.

### 8.1 Network

| Parameter       | Default | Configurable |
|-----------------|---------|:----------:|
| TCP port        | 9104    | Yes (admin UI or REST API) |
| Bind address    | All interfaces | No |
| TLS             | Off     | No         |

Changing the port takes effect the next time the SCP is
restarted (toggle the enable switch off and on, or restart
XNAT).

### 8.2 AE Title

The SCP does not have a fixed AE title. It registers a
wildcard (`*`) AE and accepts associations to any Called AE
Title. The Called AE Title is resolved to an XNAT project at
request time.

### 8.3 Service Account

Because the SCP does not authenticate the connecting DICOM
client (see section 7.1), all data access is performed as a
fixed XNAT user. Currently that user is always the XNAT site's
primary administrator account. There is no plugin setting to
select a different or dedicated service account.

As a result, queries and retrievals operate with full
administrative data access. The effective scope of what a
client can see or retrieve is therefore limited only by the
project access controls in section 8.4, not by any per-user
DICOM permissions (see also section 7.2).

### 8.4 Access Control Settings

The SCP can be enabled or disabled at two levels:

**Sitewide.** An administrator can enable or disable the
entire SCP. When disabled, the SCP stops listening and no
DICOM associations are accepted. This setting takes effect
immediately. Default: enabled.

**Per-project default.** An administrator can set whether
DIMSE access is enabled or disabled by default for all
projects. Default: enabled.

**Per-project override.** Each project can override the
sitewide default. Project owners and site administrators
can set a project to one of three states via **Project
Settings > DICOM Q/R** or the REST API:

| Setting        | Behavior                                   |
|----------------|--------------------------------------------|
| Site Default   | Follow the sitewide per-project default    |
| Enabled        | Always allow DIMSE access to this project  |
| Disabled       | Always deny DIMSE access to this project   |

New projects follow the sitewide default until explicitly
overridden.

#### 8.4.1 REST API

| Endpoint                              | Method | Access       | Description                       |
|---------------------------------------|--------|--------------|-----------------------------------|
| `/xapi/dicom-qr-scp/prefs`           | GET    | Admin        | Get sitewide preferences          |
| `/xapi/dicom-qr-scp/prefs`           | POST   | Admin        | Update sitewide preferences       |
| `/xapi/dicom-qr-scp/{projectId}`     | GET    | Admin/Owner  | Get project preference            |
| `/xapi/dicom-qr-scp/{projectId}`     | POST   | Admin/Owner  | Update project preference         |

The project endpoint accepts `{"enabled": "true"}`,
`{"enabled": "false"}`, or `{"enabled": "default"}` to set
or clear the project override.

---

## 9. Known Limitations

1. **No C-MOVE support.** Many PACS clients (including Horos
   and OsiriX by default) use C-MOVE for retrieval. These
   clients must be reconfigured for C-GET, if they support
   it.

2. **No compressed transfer syntax support.** The SCP
   transcodes between uncompressed transfer syntaxes but
   cannot decompress or compress. Files archived with JPEG,
   JPEG 2000, JPEG-LS, or other compressed syntaxes cannot
   be retrieved via C-GET unless the SCU negotiates the
   file's exact native compressed syntax.

3. **16-character AE title limit.** DICOM AE titles are
   limited to 16 characters. XNAT projects with IDs longer
   than 16 characters must use a shorter project alias.

4. **C-FIND / C-GET attribute mismatch.** C-FIND results
   reflect XNAT's data model; C-GET returns original DICOM
   files with their original header values. Attributes such
   as Patient Name, Accession Number, and Study Description
   will generally differ between C-FIND responses and the
   content of retrieved files. See section 5.7 for the full
   list of affected attributes.

5. **No Specific Character Set support.** Multi-byte character
   set encoding is not handled. Non-ASCII characters in XNAT
   fields may not render correctly in DICOM clients.

6. **PATIENT level counts may be slower.** At the PATIENT query
   level, Number of Patient Related Studies, Series, and
   Instances are aggregated across all of a subject's sessions
   and scans. For projects with very large numbers of subjects,
   sessions, or files, PATIENT-level queries may be noticeably
   slower than STUDY- or SERIES-level queries.

7. **No partial retrieval by query level.** The C-GET SCP
   does not enforce query level semantics beyond using the
   identifier keys to locate instances. A STUDY-level C-GET
   with both Study Instance UID and Series Instance UID will
   filter by both, regardless of level.

8. **File count may not match XNAT UI.** Instance counts
   include only resources whose label, format, or content
   contains `DICOM` or `secondary` (see 5.10), and include
   every file in those resources.

9. **Shared sessions.** Sessions shared into a project from
   another project are included in C-FIND results and are
   retrievable via C-GET. The subject label used as Patient
   ID is the label within the queried project, which may
   differ from the label in the owning project.
