# XNAT DIMSE Plugin

An XNAT plugin that provides a DICOM DIMSE Service Class Provider
(SCP), allowing external DICOM nodes to query and retrieve imaging
data stored in XNAT using standard DICOM network services.

## Supported Services

- **C-ECHO** — Verification (ping)
- **C-FIND** — Query at PATIENT, STUDY, SERIES, and IMAGE levels
- **C-GET** — Retrieve DICOM instances with on-the-fly transcoding
  between uncompressed transfer syntaxes

Both the **Study Root** and **Patient Root** Query/Retrieve
Information Models are supported.

The SCP listens on port 9104 by default. The Called AE Title on
an incoming association selects the XNAT project to query: if the
AE title matches a project ID or alias, that project's data is
searched.

## Configuration

The SCP can be enabled or disabled sitewide via **Administer >
Plugin Settings > DIMSE SCP**. Individual projects can override
the sitewide default in **Project Settings > DICOM Q/R**.

See [CONFORMANCE.md](CONFORMANCE.md) for detailed DICOM
conformance information, including the full REST API reference.

## Building

Requires Java 8 and Gradle.

```sh
./gradlew xnatPluginJar
```

The plugin JAR is written to `build/libs/xnat-dimse-<version>-xpl.jar`.

## Testing

```sh
./gradlew test
```

## Installation

Copy the `-xpl.jar` file into your XNAT installation's `plugins/`
directory and restart the application.
