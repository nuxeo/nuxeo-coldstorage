/*
 * (C) Copyright 2026 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Guillaume Renard
 */
package org.nuxeo.coldstorage.migrator;

import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_FACET_NAME;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.coldstorage.ColdStorageConstants;
import org.nuxeo.coldstorage.ColdStorageHelper;
import org.nuxeo.coldstorage.service.ColdStorageService;
import org.nuxeo.coldstorage.service.RestoreContext;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.migrator.AbstractBulkMigrator;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.migration.MigrationDescriptor;

/**
 * Migrator to restore documents from cold storage.
 * <p>
 * This migrator is enabled when move to cold storage is blocked via the
 * {@link ColdStorageConstants#COLD_STORAGE_RESTORE_MIGRATION_ENABLED_PROPERTY_NAME} property.
 *
 * @since 2025.2
 */
public class RestoreFromColdStorageMigrator extends AbstractBulkMigrator {

    private static final Logger log = LogManager.getLogger(RestoreFromColdStorageMigrator.class);

    public static final String MIGRATION_ID = "restore-from-cold-storage-migration";

    public static final String MIGRATION_ENABLED_STATE = "enabled";

    public static final String MIGRATION_DISABLED_STATE = "disabled";

    public static final String MIGRATION_DONE_STATE = "done";

    public RestoreFromColdStorageMigrator(MigrationDescriptor descriptor) {
        super(descriptor);
    }

    @Override
    protected String probeSession(CoreSession session) {
        ColdStorageService service = Framework.getService(ColdStorageService.class);
        if (service.isMoveToColdStorageBlocked()) {
            // Check if there are any documents with ColdStorage facet
            // If documents remain with the facet, it means they weren't restored (migration incomplete or skipped)
            long count = session.queryProjection(
                    "SELECT ecm:uuid FROM Document WHERE ecm:mixinType = '%s'".formatted(COLD_STORAGE_FACET_NAME), 1, 0)
                                .size();
            return count > 0 ? MIGRATION_ENABLED_STATE : MIGRATION_DONE_STATE;
        }
        return MIGRATION_DISABLED_STATE;
    }

    @Override
    protected String getNXQLScrollQuery() {
        // Query for all documents with ColdStorage facet - assume all are ready to be restored
        return "SELECT * FROM Document WHERE ecm:mixinType = '%s'".formatted(COLD_STORAGE_FACET_NAME);
    }

    @Override
    public void compute(CoreSession session, List<String> ids, Map<String, Serializable> properties) {
        var service = Framework.getService(ColdStorageService.class);
        for (String id : ids) {
            var docRef = new IdRef(id);
            if (!session.exists(docRef)) {
                continue;
            }

            var doc = session.getDocument(docRef);

            // Document may have been restored by a concurrent process - skip silently
            if (!doc.hasFacet(COLD_STORAGE_FACET_NAME)) {
                log.debug("Document: {} already restored from cold storage by concurrent process, skipping", id);
                continue;
            }

            try {
                // Get blob status once and check if downloadable
                var blobStatus = ColdStorageHelper.getBlobStatus(doc);
                if (ColdStorageHelper.isDownloadable(blobStatus)) {
                    // Check if we need to restore at storage level (change storage class from GLACIER)
                    boolean needsStorageLevelRestore = ColdStorageHelper.isInColdStorage(blobStatus);
                    if (needsStorageLevelRestore) {
                        log.debug("Restoring document: {} from cold storage (storage class: {})", () -> id,
                                blobStatus::getStorageClass);
                    } else {
                        log.debug(
                                "Restoring document: {} from cold storage (blob already at expected storage class: {})",
                                () -> id, blobStatus::getStorageClass);
                    }
                    service.proceedRestoreMainContent(
                            RestoreContext.builder(session, doc).storageLevel(needsStorageLevelRestore).build());
                } else {
                    // Not downloadable - log warning and skip (document keeps ColdStorage facet)
                    log.warn("Document {} cannot be restored: blob not downloadable (storage class: {})", () -> id,
                            blobStatus::getStorageClass);
                }
            } catch (NuxeoException e) {
                log.error("Failed to restore document: {} from cold storage", id, e);
            }
        }
    }

    @Override
    public void notifyStatusChange() {
        // Nothing to do for this migrator
    }
}
