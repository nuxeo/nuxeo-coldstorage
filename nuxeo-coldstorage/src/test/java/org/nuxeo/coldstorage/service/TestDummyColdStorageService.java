/*
 * (C) Copyright 2021 Nuxeo (http://nuxeo.com/) and others.
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
 *     Abdoul BA<aba@nuxeo.com>
 */

package org.nuxeo.coldstorage.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_BEING_RETRIEVED_PROPERTY;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CHECK_CONTENT_AVAILABILITY_EVENT_NAME;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_ARCHIVE_LOCATION_MAIL_TEMPLATE_KEY;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_AVAILABLE_EVENT_NAME;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_AVAILABLE_UNTIL_MAIL_TEMPLATE_KEY;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_DOWNLOADABLE_UNTIL;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_DOWNLOAD_EVENT_NAME;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_PROPERTY;
import static org.nuxeo.coldstorage.ColdStorageConstants.FILE_CONTENT_PROPERTY;
import static org.nuxeo.coldstorage.ColdStorageConstants.GET_DOCUMENTS_TO_CHECK_QUERY;
import static org.nuxeo.ecm.core.api.security.SecurityConstants.READ;
import static org.nuxeo.ecm.core.api.security.SecurityConstants.WRITE;
import static org.nuxeo.ecm.core.api.security.SecurityConstants.WRITE_COLD_STORAGE;
import static org.nuxeo.ecm.core.api.versioning.VersioningService.VERSIONING_OPTION;

import java.io.IOException;
import java.io.Serializable;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;

import org.apache.commons.lang3.time.DateUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.Test;
import org.nuxeo.coldstorage.ColdStorageHelper;
import org.nuxeo.coldstorage.DummyColdStorageFeature;
import org.nuxeo.coldstorage.action.MoveToColdStorageContentAction;
import org.nuxeo.ecm.core.DummyBlobProvider;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.VersioningOption;
import org.nuxeo.ecm.core.api.event.CoreEventConstants;
import org.nuxeo.ecm.core.api.security.ACE;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.api.thumbnail.ThumbnailAdapter;
import org.nuxeo.ecm.core.blob.BlobStatus;
import org.nuxeo.ecm.core.blob.BlobUpdateContext;
import org.nuxeo.ecm.core.blob.ManagedBlob;
import org.nuxeo.ecm.core.blob.SimpleManagedBlob;
import org.nuxeo.ecm.core.bulk.BulkService;
import org.nuxeo.ecm.core.bulk.message.BulkCommand;
import org.nuxeo.ecm.core.bulk.message.BulkStatus;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventService;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.ecm.core.event.impl.EventContextImpl;
import org.nuxeo.ecm.core.event.test.CapturingEventListener;
import org.nuxeo.ecm.core.io.download.DownloadService;
import org.nuxeo.ecm.platform.ec.notification.service.NotificationServiceHelper;
import org.nuxeo.lib.stream.computation.AbstractComputation;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.LogCaptureFeature;

@Features(DummyColdStorageFeature.class)
public class TestDummyColdStorageService extends AbstractTestColdStorageService {

    @Inject
    protected EventService eventService;

    @Inject
    protected LogCaptureFeature.Result logResult;

    @Test
    @LogCaptureFeature.FilterWith(ColdStorageActionsLogFilter.class)
    public void shouldBulkMoveToColdStorage() throws IOException {
        final String fileContent = FILE_CONTENT + System.currentTimeMillis();
        List<DocumentModel> docs = new ArrayList<DocumentModel>();
        int nbDocs = 10;
        for (int i = 0; i < nbDocs; i++) {
            docs.add(createFileDocument(DEFAULT_DOC_NAME + i, fileContent + i));
        }
        // Set legal hold on one of the doc to make it impossible to move to cold storage
        DocumentRef legalHoldRef = docs.remove(nbDocs / 2).getRef();
        session.makeRecord(legalHoldRef);
        session.setLegalHold(legalHoldRef, true, null);

        transactionalFeature.nextTransaction();
        String query = "SELECT * FROM File";

        BulkService bulkService = Framework.getService(BulkService.class);
        String username = SecurityConstants.SYSTEM_USERNAME;
        String commandId = bulkService.submitTransactional(
                new BulkCommand.Builder(MoveToColdStorageContentAction.ACTION_NAME, query, username).build());
        coreFeature.waitForAsyncCompletion();

        // The BAF in charge of moving the documents should produce a warn for the doc under legal hold
        List<String> caughtEvents = logResult.getCaughtEventMessages();
        assertEquals(1, caughtEvents.size());
        assertEquals(String.format(
                "Cannot move document %s to cold storage: The document %s is under retention or legal hold and cannot be moved to cold storage",
                legalHoldRef, legalHoldRef), caughtEvents.get(0));

        BulkStatus status = bulkService.getStatus(commandId);
        assertTrue(status.isCompleted());
        assertEquals(1, status.getErrorCount());
        for (DocumentModel doc : docs) {
            assertSentToColdStorage(session, doc.getRef());
        }
    }

    @Test
    public void shouldFireDedicatedDownloadEvent() {
        DocumentModel doc = moveAndRequestRetrievalFromColdStorage(DEFAULT_DOC_NAME);
        Instant downloadableUntil = Instant.now().plus(7, ChronoUnit.DAYS);
        transactionalFeature.nextTransaction();

        // Create a blob of which retrieval was requested and that is retrieved
        BlobStatus coldContentStatusOfFile = new BlobStatus().withDownloadable(true)
                                                             .withDownloadableUntil(downloadableUntil);
        addColdStorageContentBlobStatus(doc.getRef(), coldContentStatusOfFile);
        try (CapturingEventListener listener = new CapturingEventListener(DownloadService.EVENT_NAME,
                COLD_STORAGE_CONTENT_DOWNLOAD_EVENT_NAME)) {
            // let's mimic a download
            Map<String, Serializable> map = new HashMap<>();
            map.put("blobXPath", COLD_STORAGE_CONTENT_PROPERTY);
            DocumentEventContext ctx = new DocumentEventContext(session, session.getPrincipal(), doc);
            ctx.setProperty(CoreEventConstants.REPOSITORY_NAME, doc.getRepositoryName());
            ctx.setProperty("extendedInfos", (Serializable) map);
            Event event = ctx.newEvent(DownloadService.EVENT_NAME);
            eventService.fireEvent(event);

            // and assert it is cancelled and a cold storage download event is fired instead
            assertEquals(2, listener.streamCapturedEvents().count());
            assertTrue(listener.findFirstCapturedEvent(DownloadService.EVENT_NAME).get().isCanceled());
            assertTrue(listener.findFirstCapturedEvent(COLD_STORAGE_CONTENT_DOWNLOAD_EVENT_NAME).isPresent());
        }
    }

    @Test
    @Deploy("org.nuxeo.coldstorage.test:OSGI-INF/test-coldstorage-bulk-contrib.xml")
    @LogCaptureFeature.FilterWith(ColdStorageActionsLogFilter.class)
    public void shouldMoveManyDocsWithSameBlobToColdStorage() throws IOException, InterruptedException {
        // with regular user with "WriteColdStorage" permission
        // Let's create 2 lists of documents all sharing the same blob
        final String fileContent = FILE_CONTENT + System.currentTimeMillis();
        Blob blob1 = Blobs.createBlob(fileContent);
        blob1.setDigest(UUID.randomUUID().toString());
        Blob blob2 = Blobs.createBlob(fileContent + fileContent);
        blob2.setDigest(UUID.randomUUID().toString());
        List<DocumentModel> list1 = createSameBlobFileDocuments(DEFAULT_DOC_NAME + "1", 10, blob1, "john", READ, WRITE,
                WRITE_COLD_STORAGE);
        // and another one with a different blob
        List<DocumentModel> list2 = createSameBlobFileDocuments(DEFAULT_DOC_NAME + "2", 10, blob2, "john", READ, WRITE,
                WRITE_COLD_STORAGE);
        coreFeature.waitForAsyncCompletion(); // for thumbnail generation
        List<DocumentModel> documentModels = new ArrayList<DocumentModel>();
        documentModels = Stream.concat(list1.stream(), list2.stream()).collect(Collectors.toList());
        CoreSession userSession = CoreInstance.getCoreSession(documentModels.get(0).getRepositoryName(), "john");

        // Move only the 2 first doc
        service.moveToColdStorage(userSession, list1.get(0).getRef());
        service.moveToColdStorage(userSession, list2.get(0).getRef());
        coreFeature.waitForAsyncCompletion();

        // Moving the 2 first document to cold storage should moved all the other ones
        for (DocumentModel doc : list1) {
            assertSentToColdStorage(userSession, doc.getRef());
        }
        for (DocumentModel doc : list2) {
            assertSentToColdStorage(userSession, doc.getRef());
        }

        // Restore only the 2 first doc
        service.restoreFromColdStorage(session, list1.get(0).getRef());
        service.restoreFromColdStorage(session, list2.get(0).getRef());
        waitForRetrieve();

        // Restoring the first document to cold storage should restore all the other ones
        coreFeature.waitForAsyncCompletion();
        for (DocumentModel doc : list1) {
            assertRestoredFromColdStorage(doc.getRef(), fileContent);
        }
        for (DocumentModel doc : list2) {
            assertRestoredFromColdStorage(doc.getRef(), fileContent + fileContent);
        }
        // The BAF in charge of moving and restoring the other documents should be silent
        List<String> caughtEvents = logResult.getCaughtEventMessages();
        assertTrue(caughtEvents.isEmpty());
    }

    public static class ColdStorageActionsLogFilter implements LogCaptureFeature.Filter {
        @Override
        public boolean accept(LogEvent event) {
            String coldStorageActionPackage = MoveToColdStorageContentAction.class.getPackageName();
            return event.getLevel().equals(Level.WARN) && (event.getLoggerName().startsWith(coldStorageActionPackage)
                    || event.getLoggerName().equals(AbstractComputation.class.getName()));
        }
    }

    @Test
    public void shouldMoveToColdStorageAfterRestore() throws IOException, InterruptedException {
        // with regular user with "WriteColdStorage" permission
        final String blobContent = FILE_CONTENT + System.currentTimeMillis();
        ACE[] aces = { new ACE("john", SecurityConstants.READ, true), //
                new ACE("john", SecurityConstants.WRITE, true), //
                new ACE("john", SecurityConstants.WRITE_COLD_STORAGE, true) };
        DocumentModel documentModel = createFileDocument(DEFAULT_DOC_NAME, blobContent, aces);

        CoreSession userSession = CoreInstance.getCoreSession(documentModel.getRepositoryName(), "john");
        documentModel = moveAndRestore(documentModel);
        waitForRetrieve();
        documentModel = assertRestoredFromColdStorage(documentModel.getRef(), blobContent);
        moveAndVerifyContent(userSession, documentModel.getRef());
    }

    @Test
    public void shouldCheckAvailability() throws InterruptedException, IOException {
        // Create 3 docs on which retrieval was requested
        DocumentRef docRef1 = moveAndRequestRetrievalFromColdStorage(DEFAULT_DOC_NAME + "1").getRef();
        DocumentRef docRef2 = moveAndRequestRetrievalFromColdStorage(DEFAULT_DOC_NAME + "2").getRef();
        DocumentRef docRef3 = moveAndRequestRetrievalFromColdStorage(DEFAULT_DOC_NAME + "3").getRef();
        transactionalFeature.nextTransaction();

        List<DocumentModel> beingRetrievedDocs = session.query(GET_DOCUMENTS_TO_CHECK_QUERY);
        assertEquals(3, beingRetrievedDocs.size());

        Thread.sleep(DummyBlobProvider.RESTORE_DELAY_MILLISECONDS + 200);
        // Tweek blob status to have a doc on which retrieval was requested and that is still being retrieved
        BlobStatus coldContentStatusOfFile2 = new BlobStatus().withDownloadable(false).withOngoingRestore(true);
        addColdStorageContentBlobStatus(docRef2, coldContentStatusOfFile2);
        // and another one on which retrieval was requested but is no longer retrieved because retrieval time expired
        BlobStatus coldContentStatusOfFile3 = new BlobStatus().withDownloadable(false).withOngoingRestore(false);
        addColdStorageContentBlobStatus(docRef3, coldContentStatusOfFile3);
        coreFeature.waitForAsyncCompletion();
        beingRetrievedDocs = session.query(GET_DOCUMENTS_TO_CHECK_QUERY);
        assertEquals(3, beingRetrievedDocs.size());

        try (CapturingEventListener listener = new CapturingEventListener(COLD_STORAGE_CONTENT_AVAILABLE_EVENT_NAME)) {
            service.checkDocToBeRetrieved(session);
            coreFeature.waitForAsyncCompletion();

            beingRetrievedDocs = session.query(GET_DOCUMENTS_TO_CHECK_QUERY);
            assertEquals(1, beingRetrievedDocs.size());
            assertEquals(docRef2, beingRetrievedDocs.get(0).getRef());

            DocumentModel doc1 = session.getDocument(docRef1);
            String serverUrl = NotificationServiceHelper.getNotificationService().getServerUrlPrefix();
            String expectedDownloadUrl = serverUrl + downloadService.getDownloadUrl(session.getRepositoryName(),
                    doc1.getId(), COLD_STORAGE_CONTENT_PROPERTY, null, null);
            Serializable du = doc1.getPropertyValue(COLD_STORAGE_CONTENT_DOWNLOADABLE_UNTIL);
            assertNotNull(du);
            Date downloadableUntil = ((Calendar) session.getDocument(
                    docRef1).getPropertyValue(COLD_STORAGE_CONTENT_DOWNLOADABLE_UNTIL)).getTime();
            Instant expectedDownloadableUntilInstant = getColdContentStatus(docRef1).getDownloadableUntil();
            Date expectedDownloadableUntil = Date.from(expectedDownloadableUntilInstant);
            assertTrue(DateUtils.isSameDay(expectedDownloadableUntil, downloadableUntil));

            listener.streamCapturedEvents().forEach(event -> {
                DocumentEventContext docCtx = (DocumentEventContext) event.getContext();
                Map<String, Serializable> properties = docCtx.getProperties();

                assertEquals(String.format("An unexpected deadline for cold storage of document: %s", doc1), //
                        expectedDownloadableUntilInstant.toString(),
                        properties.get(COLD_STORAGE_CONTENT_AVAILABLE_UNTIL_MAIL_TEMPLATE_KEY));

                assertEquals(String.format("An unexpected downloadable url for document: %s", doc1), //
                        expectedDownloadUrl, properties.get(COLD_STORAGE_CONTENT_ARCHIVE_LOCATION_MAIL_TEMPLATE_KEY));
            });
        }

    }

    @Test
    public void shouldMakeRestoreImmediately() throws IOException, InterruptedException {
        final String fileContent = FILE_CONTENT + System.currentTimeMillis();
        DocumentModel documentModel = createFileDocument(DEFAULT_DOC_NAME, fileContent);
        documentModel = moveAndRestore(documentModel);
        waitForRetrieve();
        assertRestoredFromColdStorage(documentModel.getRef(), fileContent);
    }

    @Test
    @Deploy("org.nuxeo.coldstorage.test:OSGI-INF/test-thumbnail-recomputation-contrib.xml")
    public void shouldNotRecomputeThumbnailOnMoveAndRestore() throws IOException, InterruptedException {
        final String fileContent = FILE_CONTENT + System.currentTimeMillis();
        DocumentModel documentModel = session.createDocumentModel("/", DEFAULT_DOC_NAME, "MyCustomFile");
        documentModel.setPropertyValue("file:content", (Serializable) Blobs.createBlob(fileContent));
        documentModel = session.createDocument(documentModel);
        documentModel = session.saveDocument(documentModel);
        coreFeature.waitForAsyncCompletion();

        SimpleManagedBlob originalThumbnail = (SimpleManagedBlob) documentModel.getAdapter(ThumbnailAdapter.class)
                                                                               .getThumbnail(session);
        assertNotNull(originalThumbnail);

        documentModel = service.moveToColdStorage(session, documentModel.getRef());
        documentModel = service.restoreFromColdStorage(session, documentModel.getRef());
        waitForRetrieve();
        assertRestoredFromColdStorage(documentModel.getRef(), fileContent);
        SimpleManagedBlob restoredThumbnail = (SimpleManagedBlob) documentModel.getAdapter(ThumbnailAdapter.class)
                                                                               .getThumbnail(session);
        // DummyRandomThumbnailFactory used in test generates different thumbnails each time it is called
        assertEquals(originalThumbnail.getKey(), restoredThumbnail.getKey());
    }

    @Test
    public void shouldBeingRetrieved() {
        DocumentModel documentModel = createFileDocument(DEFAULT_DOC_NAME, true);

        // move the blob to cold storage
        documentModel = service.moveToColdStorage(session, documentModel.getRef());
        session.saveDocument(documentModel);
        // request a retrieval from the cold storage
        documentModel = service.retrieveFromColdStorage(session, documentModel.getRef(), RESTORE_DURATION);
        session.saveDocument(documentModel);
        transactionalFeature.nextTransaction();
        documentModel.refresh();

        assertEquals(Boolean.TRUE, documentModel.getPropertyValue(COLD_STORAGE_BEING_RETRIEVED_PROPERTY));

        assertEquals(Boolean.TRUE, ColdStorageHelper.getBlobStatus(documentModel).isOngoingRestore());
    }

    @Test
    public void shouldMoveToColdStorageSameContent() throws IOException {
        // First doc with given content
        DocumentModel documentModel = createFileDocument(DEFAULT_DOC_NAME, true);
        moveAndVerifyContent(session, documentModel.getRef());

        // Second doc with same content
        documentModel = createFileDocument(DEFAULT_DOC_NAME, true);
        moveAndVerifyContent(session, documentModel.getRef());
    }

    // NXP-31874
    @Test
    public void shouldRestoreOnRetrieveIfBlobAlreadyRestored() throws IOException {
        final String fileContent = FILE_CONTENT + System.currentTimeMillis();
        DocumentModel documentModel = createFileDocument(DEFAULT_DOC_NAME, fileContent);

        // move the blob to cold storage
        documentModel = service.moveToColdStorage(session, documentModel.getRef());

        // Let's mock a document sent to ColdStorage but somehow its blob has been restored independently
        ManagedBlob coldContent = (ManagedBlob) documentModel.getPropertyValue(COLD_STORAGE_CONTENT_PROPERTY);
        DummyBlobProvider blobProvider = (DummyBlobProvider) blobManager.getBlobProvider(coldContent.getProviderId());
        String key = ColdStorageServiceImpl.getContentBlobKey(coldContent);
        BlobUpdateContext updateContext = new BlobUpdateContext(key).withColdStorageClass(false);
        blobProvider.updateBlob(updateContext);

        // request a retrieval from the cold storage
        service.retrieveFromColdStorage(session, documentModel.getRef(), RESTORE_DURATION);

        // The nuxeo document has been restored automagically instead
        assertRestoredFromColdStorage(documentModel.getRef(), fileContent);
    }

    // NXP-31874
    @Test
    public void shouldRestoreOnRestoreIfBlobAlreadyRestored() throws IOException {
        final String fileContent = FILE_CONTENT + System.currentTimeMillis();
        DocumentModel documentModel = createFileDocument(DEFAULT_DOC_NAME, fileContent);

        // move the blob to cold storage
        documentModel = service.moveToColdStorage(session, documentModel.getRef());

        // Let's mock a document sent to ColdStorage but somehow its blob has been restored independently
        ManagedBlob coldContent = (ManagedBlob) documentModel.getPropertyValue(COLD_STORAGE_CONTENT_PROPERTY);
        DummyBlobProvider blobProvider = (DummyBlobProvider) blobManager.getBlobProvider(coldContent.getProviderId());
        String key = ColdStorageServiceImpl.getContentBlobKey(coldContent);
        BlobUpdateContext updateContext = new BlobUpdateContext(key).withColdStorageClass(false);
        blobProvider.updateBlob(updateContext);

        // request a restore from the cold storage
        service.restoreFromColdStorage(session, documentModel.getRef());
        assertRestoredFromColdStorage(documentModel.getRef(), fileContent);
    }

    // NXP-31865
    @Test
    public void shouldMoveAndRestoreVersionsWithSameColdStorageContent() throws InterruptedException, IOException {
        DocumentModel documentModel = session.createDocumentModel("/", "FileWithVersions", "File");
        String content = FILE_CONTENT + System.currentTimeMillis();
        Blob blob = Blobs.createBlob(content);
        blob.setDigest(UUID.randomUUID().toString());
        documentModel.setPropertyValue(FILE_CONTENT_PROPERTY, (Serializable) blob);
        documentModel = session.createDocument(documentModel);
        documentModel.putContextData(VERSIONING_OPTION, VersioningOption.valueOf("MINOR"));
        documentModel = session.saveDocument(documentModel);

        List<DocumentModel> versions = session.getVersions(documentModel.getRef());
        assertEquals(1, versions.size());
        DocumentModel version = versions.get(0);

        transactionalFeature.nextTransaction();

        // first make the move to cold storage
        documentModel = service.moveToColdStorage(session, documentModel.getRef());

        coreFeature.waitForAsyncCompletion();

        assertSentToColdStorage(session, documentModel.getRef());
        assertSentToColdStorage(session, version.getRef());

        documentModel = service.restoreFromColdStorage(session, documentModel.getRef());

        waitForRetrieve();

        assertRestoredFromColdStorage(documentModel.getRef(), content);
        coreFeature.waitForAsyncCompletion();
        assertRestoredFromColdStorage(version.getRef(), content);
    }

    protected void waitForRetrieve() throws InterruptedException {
        Thread.sleep(DummyBlobProvider.RESTORE_DELAY_MILLISECONDS + 200);
        EventService eventService = Framework.getService(EventService.class);
        EventContextImpl ctx = new EventContextImpl();
        eventService.fireEvent(ctx.newEvent(COLD_STORAGE_CHECK_CONTENT_AVAILABILITY_EVENT_NAME));
        coreFeature.waitForAsyncCompletion();
    }

    protected void addColdStorageContentBlobStatus(DocumentRef documentRef, BlobStatus blobStatus) {
        ManagedBlob coldContent = (ManagedBlob) session.getDocument(documentRef)
                                                       .getPropertyValue(COLD_STORAGE_CONTENT_PROPERTY);

        DummyBlobProvider blobProvider = (DummyBlobProvider) blobManager.getBlobProvider(coldContent.getProviderId());
        blobProvider.addStatus(coldContent, blobStatus);
    }

    @Override
    protected void verifyColdContent(Blob content) {
        assertNotNull(content);
        try {
            content.getString();
            fail("Cold content should not be available");
        } catch (IOException e) {
            // expected
        }
    }

}
