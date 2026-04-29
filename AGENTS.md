# Nuxeo Cold Storage - Agent Development Guide

## Project Overview

**Nuxeo Cold Storage** is a Nuxeo addon that enables cold storage of document main content for archiving, compliance, and cost optimization. This is a Maven multi-module Java project hosted at `github.com/nuxeo/nuxeo-coldstorage` on branch `lts-2025`.

The addon extends Nuxeo Platform and provides both backend (Java) and frontend (Web Components) contributions, packaged as a Nuxeo Marketplace package.

### Module Hierarchy

```
nuxeo-coldstorage-parent (root)
├── nuxeo-coldstorage/         # Backend: Core addon module with services, operations, events, actions
├── nuxeo-coldstorage-web/     # Frontend: Web Components for Nuxeo Web UI integration
├── nuxeo-coldstorage-package/ # Marketplace package builder (produces .zip)
└── ci/                        # Jenkins pipelines, Helm charts, CI scripts
```

### POM Hierarchy

```
nuxeo-coldstorage-parent (root, org.nuxeo.coldstorage)   # Parent POM, dependency management
├── Parent: nuxeo-parent (org.nuxeo)                     # Inherits from Nuxeo's re-exportable parent
└── Modules:
      ├── nuxeo-coldstorage                              # Core addon (JAR)
      ├── nuxeo-coldstorage-web                          # Web UI contributions (JAR with ES modules)
      └── nuxeo-coldstorage-package                      # Marketplace package (ZIP)
```

The root POM manages **internal module versions** only (`nuxeo-coldstorage`, `nuxeo-coldstorage-web`, `nuxeo-coldstorage-package`). **All Nuxeo dependencies** (runtime, core, platform) are inherited **without `<version>` tags** from `nuxeo-parent` and its transitive BOM.

### Build System

- **Maven** multi-module, inheriting from `nuxeo-parent`
- **Java 21** with `<release>21</release>` (inherited from nuxeo-parent)
- **Maven 3.6.3+** required
- **Node.js v22.17.0** required for web module
- Default build: `mvn clean install`
- Skip tests: `mvn clean install -DskipTests`
- Code formatting: `mvn spotless:check` / `mvn spotless:apply` (Eclipse formatter, ratcheted from `origin/lts-2025`)
- NPM registry configuration required in `$HOME/.npmrc`:
  ```
  @nuxeo:registry=https://packages.nuxeo.com/repository/npm-public/
  ```

**Maven Build Tips**:
- Use `-nsu` (or `--no-snapshot-updates`) to suppress SNAPSHOT dependency checks for faster repeated builds:
  ```bash
  mvn test -pl nuxeo-coldstorage -nsu
  ```
- Combine with `-ntp` (no transfer progress) for cleaner output:
  ```bash
  mvn test -pl nuxeo-coldstorage -nsu -ntp
  ```
- For single test execution:
  ```bash
  mvn test -Dtest=MoveToColdStorageTest -pl nuxeo-coldstorage -nsu
  ```

---

## Nuxeo Platform Development Conventions

This addon follows standard Nuxeo Platform development conventions. For comprehensive guidance on:

- Java conventions and modern Java features
- Component model and dependency injection
- Configuration, persistence, and document model APIs
- REST API, JSON marshalling, and async work patterns
- Testing framework and patterns
- Third-party libraries and Git conventions

**See the [Nuxeo Platform Agent Development Guide](https://github.com/nuxeo/nuxeo/blob/lts-2025/AGENTS.md)**

---

## Cold Storage Addon Architecture

### Core Components

The Cold Storage addon is organized in the `org.nuxeo.coldstorage` package with the following key components:

**Service Layer** (`org.nuxeo.coldstorage.service`):
- `ColdStorageService`: Main service interface for cold storage operations
- `ColdStorageServiceImpl`: Component implementation providing the service

**Bulk Actions** (`org.nuxeo.coldstorage.action`):
- `MoveToColdStorageContentAction`: Moves document main content to cold storage
- `PropagateMoveToColdStorageContentAction`: Propagates move operation to related documents
- `CheckColdStorageAvailabilityAction`: Checks retrieval status via scheduled execution
- `PropagateRestoreFromColdStorageContentAction`: Propagates restore operation to related documents

**Operations** (`org.nuxeo.coldstorage.operations`):
- Automation operations exposed via REST API for cold storage actions

**Event Listeners** (`org.nuxeo.coldstorage.events`):
- Event handlers for document lifecycle and content changes

**JSON Marshalling** (`org.nuxeo.coldstorage.io`):
- JSON enrichers for cold storage metadata

### Schema

The addon contributes a `coldstorage` schema (XSD at `schemas/coldstorage.xsd`) with fields including:
- `coldContent`: Reference to the archived blob in cold storage
- `beingRetrieved`: Flag indicating retrieval in progress
- Additional metadata for tracking cold storage state

### Extension Points

**`coldStorageRendition`** (contributed by `ColdStorageService`):
Configures which rendition to use as a placeholder when content is moved to cold storage. Can be configured by document type or facet:

```xml
<extension target="org.nuxeo.coldstorage.service.ColdStorageService" point="coldStorageRendition">
  <coldStorageRendition name="defaultRendition" renditionName="Thumbnail" />
  <coldStorageRendition name="pictureRendition" docType="Picture" facet="Picture" renditionName="Small" />
</extension>
```

### Migrations

#### Cold Storage Restore Migration

**ID**: `restore-from-cold-storage-migration`
**Since**: 2025.2
**Purpose**: Automatically restores documents from cold storage when the `nuxeo.coldstorage.migration.restore.enabled` property is enabled.

**States**:
- `disabled`: Move to cold storage is not blocked (default state)
- `enabled`: Move to cold storage is blocked, and there are documents in cold storage that need restoration
- `done`: All documents have been restored from cold storage (no documents remaining with non-downloadable blobs)

**Migration Step**: `enabled-to-done`

**Behavior**:
1. When `nuxeo.coldstorage.migration.restore.enabled` is set to `true`, the migration automatically probes for its state
2. The migration queries for all documents with the ColdStorage facet (mixinType)
3. If documents with the ColdStorage facet exist, the state becomes `enabled`
4. Running the migration step processes all documents with the ColdStorage facet and attempts to restore them
5. The migrator assumes all blobs are downloadable and attempts restoration
6. If a blob is not downloadable (e.g., still in GLACIER storage class), the migrator:
   - Logs a **WARNING** message indicating the document cannot be restored
   - Skips the document (document keeps its ColdStorage facet) and continues processing
7. After migration completes, the migration probes again:
   - If ALL documents were successfully restored (no documents with ColdStorage facet remain) → state transitions to `done`
   - If ANY documents still have the ColdStorage facet (not downloadable) → state remains `enabled`
8. The migration uses the bulk action framework to restore documents in batches

**Usage**:
```bash
# Check migration status
curl -u Administrator:Administrator \
  http://localhost:8080/nuxeo/site/management/migration/restore-from-cold-storage-migration

# Run the migration (if in 'enabled' state)
curl -u Administrator:Administrator -X POST \
  http://localhost:8080/nuxeo/site/management/migration/restore-from-cold-storage-migration/enabled-to-done
```

**Implementation**: `org.nuxeo.coldstorage.migrator.RestoreFromColdStorageMigrator`

---

## Configuration and Deployment

For configuration properties, database indexes, and operational procedures (including exiting cold storage), see the [README](README.md).
