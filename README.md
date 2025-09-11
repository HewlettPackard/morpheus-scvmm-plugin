## Morpheus SCVMM Plugin

This plugin integrates Microsoft SCVMM with Morpheus, enabling cloud sync, provisioning, and backup operations. It provides a `CloudProvider` for syncing cloud objects, a `ProvisionProvider` for VM provisioning, and a `BackupProvider` for VM snapshots and backups.

## 📑 Table of Contents

- [Features](#features)
- [Requirements](#requirements)
- [Repository structure](#repository-structure)
- [Building the plugin](#building-the-plugin)
- [License](#license)
- [Installing](#installing)
- [Detailed Usage Steps](#detailed-usage-steps)
  - [Syncing SCVMM Resources](#syncing-scvmm-resources)
  - [Provisioning Virtual Machines](#provisioning-virtual-machines)
  - [VM Snapshots and Backups](#vm-snapshots-and-backups)
---

## Features

- Syncs hosts, networks, and virtual machines from SCVMM to Morpheus
- Provisions virtual machines from Morpheus into SCVMM
- Supports VM snapshot creation and restore for backup/restore operations

---

## Requirements

- Java 17 or greater
- Gradle
- Microsoft SCVMM version 2016 or greater
- Morpheus appliance version compatible with plugin integration
- SCVMM credentials (username/password), host, WINRM port, working path, VM path, disk path

---

## Repository structure

- `src/main/groovy/`
  - `com/` — Main plugin source code (providers, integration logic)
    - `cloud/` — Cloud integration and management
    - `provision/` — VM provisioning logic
    - `backup/` — VM snapshot and backup logic
    - `sync/` — Resource sync tasks and helpers
    - `util/` — Utility classes and helpers
- `src/assets/`
  - `images/` — Plugin icons and SVG assets
- `src/main/resources/`
  - `i18n/` — Localization and translation files
  - `scribe/` — Scribe manifests and configuration
  - `META-INF/` — Plugin metadata and notices
- `build.gradle` — Gradle build configuration
- `gradle.properties` — Gradle properties
- `settings.gradle` — Gradle project settings
- `README.md` — Project documentation
- `LICENSE` — License file
- `NOTICE` — Notice file
- `functional_tests/` — Python-based functional test suite
  - `common/` — Shared test utilities and configs
  - `tests/` — Test cases for plugin functionality
- `build/` — Build output (generated JARs, assets, classes)
- `gradle/` — Gradle wrapper files and configuration
---

## Building the plugin

Run the following command to compile and package the plugin jar:

```bash
./gradlew clean build
```
---

## License

This project is licensed under the Apache 2.0 License. See the [LICENSE](./LICENSE) file for details.

---

## Installing

1. Build the plugin using the provided Gradle command.
2. Locate the generated `morpheus-scvmm-plugin-x.x.x.jar` in the `build/libs/` directory.
3. Upload the jar file to your Morpheus appliance plugin directory.
4. Restart the Morpheus appliance or reload plugins as required.

---

## Detailed Usage Steps

### Syncing SCVMM Resources

- After adding the SCVMM cloud in Morpheus, resources such as hosts, networks, and VMs will be automatically synced.
- Navigate to **Infrastructure > Clouds** and select your SCVMM cloud to view synced resources.

### Provisioning Virtual Machines

- Go to **Provisioning > Instances > + Add**.
- Select your SCVMM cloud and choose a deployment flavor/template.
- Fill in the required VM details and submit the provisioning request.

### VM Snapshots and Backups

- Select a VM managed by the SCVMM plugin.
- Use the **Backup** or **Snapshot** actions to create or restore VM snapshots.

For more detailed instructions and screenshots, refer to the official Morpheus documentation.