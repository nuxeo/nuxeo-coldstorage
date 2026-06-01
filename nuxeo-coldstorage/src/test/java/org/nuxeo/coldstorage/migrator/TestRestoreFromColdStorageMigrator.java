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

import static org.awaitility.Awaitility.await;
import static org.awaitility.Duration.ONE_MINUTE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_PROPERTY;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_FACET_NAME;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_RESTORE_MIGRATION_ENABLED_PROPERTY_NAME;
import static org.nuxeo.coldstorage.ColdStorageConstants.FILE_CONTENT_PROPERTY;
import static org.nuxeo.coldstorage.MockS3BlobProvider.waitForRestore;
import static org.nuxeo.coldstorage.migrator.RestoreFromColdStorageMigrator.MIGRATION_DISABLED_STATE;
import static org.nuxeo.coldstorage.migrator.RestoreFromColdStorageMigrator.MIGRATION_DONE_STATE;
import static org.nuxeo.coldstorage.migrator.RestoreFromColdStorageMigrator.MIGRATION_ENABLED_STATE;
import static org.nuxeo.coldstorage.migrator.RestoreFromColdStorageMigrator.MIGRATION_ID;
import static org.nuxeo.ecm.core.api.security.SecurityConstants.SYSTEM_USERNAME;
import static org.nuxeo.ecm.core.migrator.AbstractBulkMigrator.PARAM_MIGRATION_ID;

import java.io.IOException;
import java.io.Serializable;
import java.time.Duration;
import java.util.UUID;

import javax.inject.Inject;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.coldstorage.ColdStorageHelper;
import org.nuxeo.coldstorage.DummyColdStorageFeature;
import org.nuxeo.coldstorage.MockS3BlobProvider;
import org.nuxeo.coldstorage.service.ColdStorageService;
import org.nuxeo.coldstorage.service.ColdStorageServiceImpl;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.blob.BlobManager;
import org.nuxeo.ecm.core.blob.BlobUpdateContext;
import org.nuxeo.ecm.core.blob.ManagedBlob;
import org.nuxeo.ecm.core.bulk.BulkService;
import org.nuxeo.ecm.core.bulk.message.BulkStatus;
import org.nuxeo.ecm.core.migrator.AbstractBulkMigrator;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.migration.MigrationService;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;

/**
 * Tests for the restore from cold storage migration.
 *
 * @since 2025.2
 */
@RunWith(FeaturesRunner.class)
@Features(DummyColdStorageFeature.class)
public class TestRestoreFromColdStorageMigrator {

    protected static final String FILE_CONTENT = "foo and boo";

    @Inject
    protected CoreSession session;

    @Inject
    protected TransactionalFeature transactionalFeature;

    @Inject
    protected MigrationService migrationService;

    @Inject
    protected BulkService bulkService;

    @Inject
    protected ColdStorageService coldStorageService;

    @After
    public void tearDown() {
        // Reset the framework property after each test
        Framework.getProperties().remove(COLD_STORAGE_RESTORE_MIGRATION_ENABLED_PROPERTY_NAME);
    }

    @Test
    public void testMigrationDisabledByDefault() {
        var status = migrationService.getStatus(MIGRATION_ID);
        assertNotNull("Migration should be registered", status);
        assertEquals("Migration should be in disabled state by default", MIGRATION_DISABLED_STATE, status.getState());
    }

    @Test
    public void testMigrationWithDocumentsInColdStorage() {
        // Create a document and move it to cold storage
        var doc = createFileDocument(session);
        session.save();
        transactionalFeature.nextTransaction();

        doc = moveContentToColdStorage(session, doc);
        session.save();
        transactionalFeature.nextTransaction();

        // Verify document is in cold storage
        doc = session.getDocument(doc.getRef());
        checkMoveContent(doc);

        // Now enable the blocking property
        Framework.getProperties().setProperty(COLD_STORAGE_RESTORE_MIGRATION_ENABLED_PROPERTY_NAME, "true");

        // Probe migration state - should be enabled since doc is in cold storage
        var state = migrationService.probeAndSetState(MIGRATION_ID);
        assertEquals("Migration should be in enabled state when documents are in cold storage", MIGRATION_ENABLED_STATE,
                state);

        // Verify status
        var status = migrationService.getStatus(MIGRATION_ID);
        assertEquals("Migration status should be enabled", MIGRATION_ENABLED_STATE, status.getState());
    }

    @Test
    public void testMigrationRestoreWithDownloadableBlob() {
        // Create a document and move it to cold storage
        var doc = createFileDocument(session);
        session.save();
        transactionalFeature.nextTransaction();

        doc = moveContentToColdStorage(session, doc);
        session.save();
        transactionalFeature.nextTransaction();

        // Verify document is in cold storage
        doc = session.getDocument(doc.getRef());
        checkMoveContent(doc);
        assertTrue("Document should have ColdStorage facet", doc.hasFacet(COLD_STORAGE_FACET_NAME));

        // Simulate blob retrieval at provider level (like when requestRetrieve is called externally)
        var coldContent = (ManagedBlob) doc.getPropertyValue(COLD_STORAGE_CONTENT_PROPERTY);
        var blobManager = Framework.getService(BlobManager.class);
        var blobProvider = (MockS3BlobProvider) blobManager.getBlobProvider(coldContent.getProviderId());
        var key = ColdStorageServiceImpl.getContentBlobKey(coldContent);
        var updateContext = new BlobUpdateContext(key).withRestoreForDuration(Duration.ofDays(1));
        blobProvider.updateBlob(updateContext);

        // Wait for the restore to complete (MockS3BlobProvider simulates async restore)
        waitForRestore();
        transactionalFeature.nextTransaction();

        // Enable blocking property
        Framework.getProperties().setProperty(COLD_STORAGE_RESTORE_MIGRATION_ENABLED_PROPERTY_NAME, "true");

        // Probe migration state - should be enabled
        var state = migrationService.probeAndSetState(MIGRATION_ID);
        assertEquals("Migration should be in enabled state", MIGRATION_ENABLED_STATE, state);

        // Run the migration
        migrationService.runStep(MIGRATION_ID, MIGRATION_ENABLED_STATE + "-to-" + MIGRATION_DONE_STATE);

        // Wait for migration to complete
        await().atMost(ONE_MINUTE).until(() -> !migrationService.getStatus(MIGRATION_ID).isRunning());
        assertBulkStatus(1, 1, 0, 0);
        transactionalFeature.nextTransaction();

        // Verify document was restored and no longer has ColdStorage facet
        doc = session.getDocument(doc.getRef());
        assertFalse("Document should not have ColdStorage facet after migration",
                doc.hasFacet(COLD_STORAGE_FACET_NAME));
        assertNotNull("Document should have main content restored", doc.getPropertyValue("file:content"));

        // Probe state again - should be done since no documents remain in cold storage
        state = migrationService.probeAndSetState(MIGRATION_ID);
        assertEquals("Migration should be in done state after successful restore", MIGRATION_DONE_STATE, state);
    }

    @Test
    public void testMigrationWithNonDownloadableBlob() {
        // Create a document and move it to cold storage
        var doc = createFileDocument(session);
        session.save();
        transactionalFeature.nextTransaction();

        doc = moveContentToColdStorage(session, doc);
        session.save();
        transactionalFeature.nextTransaction();

        // Verify document is in cold storage
        doc = session.getDocument(doc.getRef());
        checkMoveContent(doc);
        assertTrue("Document should have ColdStorage facet", doc.hasFacet(COLD_STORAGE_FACET_NAME));

        // Do NOT restore the blob - leave it in cold storage (non-downloadable)

        // Enable blocking property
        Framework.getProperties().setProperty(COLD_STORAGE_RESTORE_MIGRATION_ENABLED_PROPERTY_NAME, "true");

        // Probe migration state - should be enabled
        var state = migrationService.probeAndSetState(MIGRATION_ID);
        assertEquals("Migration should be in enabled state", MIGRATION_ENABLED_STATE, state);

        // Run the migration
        migrationService.runStep(MIGRATION_ID, MIGRATION_ENABLED_STATE + "-to-" + MIGRATION_DONE_STATE);

        // Wait for migration to complete
        await().atMost(ONE_MINUTE).until(() -> !migrationService.getStatus(MIGRATION_ID).isRunning());

        assertBulkStatus(1, 1, 1, 0);

        // Advance transaction to avoid stale reads
        transactionalFeature.nextTransaction();

        // Verify document still has ColdStorage facet (restore was skipped)
        doc = session.getDocument(doc.getRef());
        assertTrue("Document should still have ColdStorage facet when blob is not downloadable",
                doc.hasFacet(COLD_STORAGE_FACET_NAME));

        // Probe state again - should still be enabled since document remains in cold storage
        state = migrationService.probeAndSetState(MIGRATION_ID);
        assertEquals("Migration should remain in enabled state when documents cannot be restored",
                MIGRATION_ENABLED_STATE, state);
    }

    @Test
    public void testMigrationDoneWhenNoDocuments() {
        // Enable blocking without any documents in cold storage
        Framework.getProperties().setProperty(COLD_STORAGE_RESTORE_MIGRATION_ENABLED_PROPERTY_NAME, "true");

        // Probe migration state - should be done since no docs in cold storage
        var state = migrationService.probeAndSetState(MIGRATION_ID);
        assertEquals("Migration should be in done state when no documents in cold storage", MIGRATION_DONE_STATE,
                state);
    }

    @Test
    public void testMigrationWithDeduplicatedBlobs() throws IOException {
        // Create a blob with a specific digest
        Blob blob1 = Blobs.createBlob(FILE_CONTENT);
        blob1.setDigest("digest");

        // Create first document with the blob
        DocumentModel doc1 = session.createDocumentModel("/", "Doc1-" + UUID.randomUUID().toString().substring(0, 8),
                "File");
        doc1.setPropertyValue(FILE_CONTENT_PROPERTY, (Serializable) blob1);
        doc1 = session.createDocument(doc1);
        session.save();
        transactionalFeature.nextTransaction();

        // Create second document with the same blob (same digest)
        DocumentModel doc2 = session.createDocumentModel("/", "Doc2-" + UUID.randomUUID().toString().substring(0, 8),
                "File");
        doc2.setPropertyValue(FILE_CONTENT_PROPERTY, doc1.getPropertyValue(FILE_CONTENT_PROPERTY));
        doc2 = session.createDocument(doc2);
        session.save();
        transactionalFeature.nextTransaction();

        // Move 1st document to cold storage manually (tests migration with same content digest)
        doc1 = moveContentToColdStorage(session, doc1);
        session.save();
        transactionalFeature.nextTransaction();

        // Verify both documents are now in cold storage
        doc1 = session.getDocument(doc1.getRef());
        doc2 = session.getDocument(doc2.getRef());
        assertTrue("First document should have ColdStorage facet", doc1.hasFacet(COLD_STORAGE_FACET_NAME));
        assertTrue("Second document should have ColdStorage facet", doc2.hasFacet(COLD_STORAGE_FACET_NAME));

        // Both documents have the same cold storage blob
        var coldContent1 = (ManagedBlob) doc1.getPropertyValue(COLD_STORAGE_CONTENT_PROPERTY);
        var coldContent2 = (ManagedBlob) doc2.getPropertyValue(COLD_STORAGE_CONTENT_PROPERTY);
        assertNotNull("First document should have cold storage content", coldContent1);
        assertNotNull("Second document should have cold storage content", coldContent2);
        assertEquals(coldContent1.getKey(), coldContent2.getKey());

        // Restore first blob at provider level
        var blobManager = Framework.getService(BlobManager.class);
        var blobProvider1 = (MockS3BlobProvider) blobManager.getBlobProvider(coldContent1.getProviderId());
        var key1 = ColdStorageServiceImpl.getContentBlobKey(coldContent1);
        blobProvider1.updateBlob(new BlobUpdateContext(key1).withRestoreForDuration(Duration.ofDays(1)));

        // Wait for the restores to complete
        waitForRestore();
        transactionalFeature.nextTransaction();

        // Enable blocking property
        Framework.getProperties().setProperty(COLD_STORAGE_RESTORE_MIGRATION_ENABLED_PROPERTY_NAME, "true");

        // Probe migration state - should be enabled
        var state = migrationService.probeAndSetState(MIGRATION_ID);
        assertEquals("Migration should be in enabled state", MIGRATION_ENABLED_STATE, state);

        // Run the migration
        migrationService.runStep(MIGRATION_ID, MIGRATION_ENABLED_STATE + "-to-" + MIGRATION_DONE_STATE);

        // Wait for migration to complete
        await().atMost(ONE_MINUTE).until(() -> !migrationService.getStatus(MIGRATION_ID).isRunning());
        transactionalFeature.nextTransaction();

        // Verify both documents were restored
        doc1 = session.getDocument(doc1.getRef());
        doc2 = session.getDocument(doc2.getRef());
        assertFalse("First document should not have ColdStorage facet after migration",
                doc1.hasFacet(COLD_STORAGE_FACET_NAME));
        assertFalse("Second document should not have ColdStorage facet after migration",
                doc2.hasFacet(COLD_STORAGE_FACET_NAME));
        assertNotNull("First document should have main content restored", doc1.getPropertyValue("file:content"));
        assertNotNull("Second document should have main content restored", doc2.getPropertyValue("file:content"));

        // Verify the restored content is correct
        var restoredBlob1 = (Blob) doc1.getPropertyValue("file:content");
        var restoredBlob2 = (Blob) doc2.getPropertyValue("file:content");
        assertEquals("First document should have correct content", FILE_CONTENT, restoredBlob1.getString());
        assertEquals("Second document should have correct content", FILE_CONTENT, restoredBlob2.getString());

        // Probe state again - should be done since no documents remain in cold storage
        state = migrationService.probeAndSetState(MIGRATION_ID);
        assertEquals("Migration should be in done state after successful restore", MIGRATION_DONE_STATE, state);
    }

    @Test
    public void testMigrationWithMixedDownloadableBlobs() {
        // Create 10 documents in cold storage
        var docs = new java.util.ArrayList<DocumentModel>();
        for (int i = 0; i < 10; i++) {
            var doc = createFileDocument(session);
            session.save();
            transactionalFeature.nextTransaction();

            doc = moveContentToColdStorage(session, doc);
            session.save();
            transactionalFeature.nextTransaction();

            doc = session.getDocument(doc.getRef());
            checkMoveContent(doc);
            assertTrue("Document should have ColdStorage facet", doc.hasFacet(COLD_STORAGE_FACET_NAME));
            docs.add(doc);
        }

        // Restore only 6 out of 10 documents (leave 4 non-downloadable)
        var blobManager = Framework.getService(BlobManager.class);
        for (int i = 0; i < 6; i++) {
            var doc = docs.get(i);
            var coldContent = (ManagedBlob) doc.getPropertyValue(COLD_STORAGE_CONTENT_PROPERTY);
            var blobProvider = (MockS3BlobProvider) blobManager.getBlobProvider(coldContent.getProviderId());
            var key = ColdStorageServiceImpl.getContentBlobKey(coldContent);
            var updateContext = new BlobUpdateContext(key).withRestoreForDuration(Duration.ofDays(1));
            blobProvider.updateBlob(updateContext);
        }

        // Wait for the 6 restores to complete
        waitForRestore();
        transactionalFeature.nextTransaction();

        // Enable blocking property
        Framework.getProperties().setProperty(COLD_STORAGE_RESTORE_MIGRATION_ENABLED_PROPERTY_NAME, "true");

        // Probe migration state - should be enabled
        var state = migrationService.probeAndSetState(MIGRATION_ID);
        assertEquals("Migration should be in enabled state", MIGRATION_ENABLED_STATE, state);

        // Run the migration
        migrationService.runStep(MIGRATION_ID, MIGRATION_ENABLED_STATE + "-to-" + MIGRATION_DONE_STATE);

        // Wait for migration to complete
        await().atMost(ONE_MINUTE).until(() -> !migrationService.getStatus(MIGRATION_ID).isRunning());
        assertBulkStatus(10, 10, 4, 0);
        transactionalFeature.nextTransaction();

        // Verify results:
        // - 6 documents should be restored (no ColdStorage facet)
        // - 4 documents should still be in cold storage (still have facet)
        int restoredCount = 0;
        int stillInColdStorageCount = 0;

        for (DocumentModel doc : docs) {
            doc = session.getDocument(doc.getRef());
            if (doc.hasFacet(COLD_STORAGE_FACET_NAME)) {
                stillInColdStorageCount++;
            } else {
                restoredCount++;
                assertNotNull("Restored document should have main content", doc.getPropertyValue("file:content"));
            }
        }

        assertEquals("Should have restored 6 documents", 6, restoredCount);
        assertEquals("Should have 4 documents still in cold storage", 4, stillInColdStorageCount);

        // Probe state again - should still be enabled (not done) because 4 documents remain
        state = migrationService.probeAndSetState(MIGRATION_ID);
        assertEquals("Migration should remain in enabled state when some documents cannot be restored",
                MIGRATION_ENABLED_STATE, state);
    }

    // Helper methods

    protected void assertBulkStatus(long total, long processed, long skipped, long error) {
        var bulkStatus = bulkService.getStatuses(SYSTEM_USERNAME)
                                    .stream()
                                    .filter(s -> AbstractBulkMigrator.MigrationAction.ACTION_NAME.equals(s.getAction()))
                                    .filter(s -> {
                                        var cmd = bulkService.getCommand(s.getId());
                                        return RestoreFromColdStorageMigrator.MIGRATION_ID.equals(
                                                cmd.getParam(PARAM_MIGRATION_ID));
                                    })
                                    .findFirst()
                                    .orElseThrow(() -> new AssertionError(
                                            "no bulk status found for migration " + MIGRATION_ID));

        assertEquals(BulkStatus.State.COMPLETED, bulkStatus.getState());
        assertEquals(total, bulkStatus.getTotal());
        assertEquals(processed, bulkStatus.getProcessed());
        assertEquals(skipped, bulkStatus.getSkipCount());
        assertEquals(error, bulkStatus.getErrorCount());
    }

    protected DocumentModel createFileDocument(CoreSession session) {
        String uniqueName = "MyFile-" + UUID.randomUUID().toString().substring(0, 8);
        DocumentModel documentModel = session.createDocumentModel("/", uniqueName, "File");
        Blob blob = Blobs.createBlob(FILE_CONTENT);
        blob.setDigest(UUID.randomUUID().toString());
        documentModel.setPropertyValue(FILE_CONTENT_PROPERTY, (Serializable) blob);
        return session.createDocument(documentModel);
    }

    protected DocumentModel moveContentToColdStorage(CoreSession session, DocumentModel documentModel) {
        documentModel = coldStorageService.moveToColdStorage(session, documentModel.getRef());
        checkMoveContent(documentModel);
        return documentModel;
    }

    protected void checkMoveContent(DocumentModel doc) {
        // check document
        assertTrue(doc.hasFacet(COLD_STORAGE_FACET_NAME));

        // check blob
        Blob coldStorageContent = (Blob) doc.getPropertyValue(COLD_STORAGE_CONTENT_PROPERTY);
        assertNotNull(coldStorageContent);
        assertTrue(ColdStorageHelper.isInColdStorage((ManagedBlob) coldStorageContent));
    }
}
