/*
 * (C) Copyright 2020 Nuxeo (http://nuxeo.com/) and others.
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
 *     Salem Aouana
 */

package org.nuxeo.coldstorage.service;

import static javax.servlet.http.HttpServletResponse.SC_CONFLICT;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_BEING_RESTORED_PROPERTY;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_BEING_RETRIEVED_PROPERTY;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_AVAILABLE_IN_COLDSTORAGE;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_DOWNLOADABLE_UNTIL;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_PROPERTY;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_STORAGE_CLASS_TO_UPDATED;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_TO_BE_RESTORED_PROPERTY;
import static org.nuxeo.coldstorage.events.CheckUpdateColdStorageContentListener.DISABLE_COLD_STORAGE_CHECK_UPDATE_COLDSTORAGE_CONTENT_LISTENER;
import static org.nuxeo.coldstorage.events.CheckUpdateMainContentInColdStorageListener.DISABLE_COLD_STORAGE_CHECK_UPDATE_MAIN_CONTENT_LISTENER;

import java.io.IOException;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.commons.collections.CollectionUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.coldstorage.ColdStorageConstants;
import org.nuxeo.coldstorage.ColdStorageConstants.ColdStorageContentStatus;
import org.nuxeo.coldstorage.action.MoveToColdStorageContentAction;
import org.nuxeo.coldstorage.action.PropagateMoveToColdStorageContentAction;
import org.nuxeo.ecm.core.DummyThumbnailFactory;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.security.ACE;
import org.nuxeo.ecm.core.api.security.ACL;
import org.nuxeo.ecm.core.api.security.ACP;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.api.thumbnail.ThumbnailService;
import org.nuxeo.ecm.core.blob.BlobManager;
import org.nuxeo.ecm.core.blob.ManagedBlob;
import org.nuxeo.ecm.core.blob.SimpleManagedBlob;
import org.nuxeo.ecm.core.bulk.BulkService;
import org.nuxeo.ecm.core.bulk.message.BulkCommand;
import org.nuxeo.ecm.core.bulk.message.BulkStatus;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.ecm.core.event.test.CapturingEventListener;
import org.nuxeo.ecm.core.io.download.DownloadService;
import org.nuxeo.ecm.core.test.CoreFeature;
import org.nuxeo.ecm.core.test.StorageConfiguration;
import org.nuxeo.ecm.platform.ec.notification.service.NotificationServiceHelper;
import org.nuxeo.ecm.platform.thumbnail.ThumbnailConstants;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;

/**
 * @since 11.1
 */
@RunWith(FeaturesRunner.class)
public abstract class AbstractTestColdStorageService {

    protected static final String FILE_CONTENT = "foo";

    protected static final Duration RESTORE_DURATION = Duration.ofDays(5);

    protected static final String DEFAULT_DOC_NAME = "anyFile";

    @Inject
    protected CoreSession session;

    @Inject
    protected TransactionalFeature transactionalFeature;

    @Inject
    protected CoreFeature coreFeature;

    @Inject
    protected BlobManager blobManager;

    @Inject
    protected DownloadService downloadService;

    @Inject
    protected ThumbnailService thumbnailService;

    @Inject
    protected ColdStorageService service;

    protected abstract String getBlobProviderName();

    @Test
    public void shouldMoveToColdStorage() throws IOException {
        // with regular user with "WriteColdStorage" permission
        ACE[] aces = { new ACE("john", SecurityConstants.READ, true), //
                new ACE("john", SecurityConstants.WRITE, true), //
                new ACE("john", SecurityConstants.WRITE_COLD_STORAGE, true) };
        DocumentModel documentModel = createFileDocument(DEFAULT_DOC_NAME, true, aces);

        CoreSession userSession = CoreInstance.getCoreSession(documentModel.getRepositoryName(), "john");
        moveAndVerifyContent(userSession, documentModel.getRef());

        // with Administrator
        documentModel = createFileDocument(DEFAULT_DOC_NAME, true);
        moveAndVerifyContent(session, documentModel.getRef());
    }

    @Test
    public void shouldMoveToColdStorageAfterRestore() throws IOException {
        // with regular user with "WriteColdStorage" permission
        ACE[] aces = { new ACE("john", SecurityConstants.READ, true), //
                new ACE("john", SecurityConstants.WRITE, true), //
                new ACE("john", SecurityConstants.WRITE_COLD_STORAGE, true) };
        DocumentModel documentModel = createFileDocument(DEFAULT_DOC_NAME, true, aces);

        CoreSession userSession = CoreInstance.getCoreSession(documentModel.getRepositoryName(), "john");
        documentModel = moveAndRestore(documentModel);
        userSession.saveDocument(documentModel);
        coreFeature.waitForAsyncCompletion();
        documentModel = verifyRestore(documentModel.getRef(), FILE_CONTENT);
        moveAndVerifyContent(userSession, documentModel.getRef());
    }

    @Test
    public void shouldFailWithoutRightPermissions() {
        ACE[] aces = { new ACE("john", SecurityConstants.READ, true) };
        DocumentModel documentModel = createFileDocument(DEFAULT_DOC_NAME, true, aces);

        try {
            CoreSession userSession = CoreInstance.getCoreSession(documentModel.getRepositoryName(), "john");
            service.moveContentToColdStorage(userSession, documentModel.getRef());
            fail("Should fail because the user does not have permissions to move document to cold storage");
        } catch (NuxeoException e) {
            assertEquals(SC_FORBIDDEN, e.getStatusCode());
        }
    }

    @Test
    public void shouldFailMoveAlreadyInColdStorage() {
        DocumentModel documentModel = createFileDocument(DEFAULT_DOC_NAME, true);

        // move for the first time
        documentModel = moveContentToColdStorage(session, documentModel.getRef());

        // try to make another move
        try {
            moveContentToColdStorage(session, documentModel.getRef());
            fail("Should fail because the content is already in cold storage");
        } catch (NuxeoException e) {
            assertEquals(SC_CONFLICT, e.getStatusCode());
            assertEquals(String.format("The main content for document: %s is already in cold storage.", documentModel),
                    e.getMessage());
        }
    }

    @Test
    public void shouldFailMoveToColdStorageNoContent() {
        DocumentModel documentModel = createFileDocument(DEFAULT_DOC_NAME, false);
        try {
            moveContentToColdStorage(session, documentModel.getRef());
            fail("Should fail because there is no main content associated with the document");
        } catch (NuxeoException e) {
            assertEquals(SC_NOT_FOUND, e.getStatusCode());
            assertEquals(String.format("There is no main content for document: %s.", documentModel), e.getMessage());
        }
    }

    @Test
    public void shouldFailMoveToColdStorageUnderLegalHold() {
        DocumentModel documentModel = createFileDocument(DEFAULT_DOC_NAME, true);
        session.makeRecord(documentModel.getRef());
        session.setLegalHold(documentModel.getRef(), true, "any comment");
        try {
            moveContentToColdStorage(session, documentModel.getRef());
            fail("Should fail because the document is under legal hold");
        } catch (NuxeoException e) {
            assertEquals(SC_FORBIDDEN, e.getStatusCode());
            assertEquals(String.format(
                    "The document %s is under retention or legal hold and cannot be moved to cold storage",
                    documentModel.getId()), e.getMessage());
        }
    }

    @Test
    public void shouldFailMoveToColdStorageUnderRetention() {
        DocumentModel documentModel = createFileDocument(DEFAULT_DOC_NAME, true);
        session.makeRecord(documentModel.getRef());
        Calendar retainUntil = Calendar.getInstance();
        retainUntil.add(Calendar.DAY_OF_MONTH, 5);
        session.setRetainUntil(documentModel.getRef(), retainUntil, "any comment");
        try {
            moveContentToColdStorage(session, documentModel.getRef());
            fail("Should fail because the document is under retention");
        } catch (NuxeoException e) {
            assertEquals(SC_FORBIDDEN, e.getStatusCode());
            assertEquals(String.format(
                    "The document %s is under retention or legal hold and cannot be moved to cold storage",
                    documentModel.getId()), e.getMessage());
        }
    }

    @Test
    public void shouldRequestRetrieval() {
        DocumentModel documentModel = createFileDocument(DEFAULT_DOC_NAME, true);

        // move the blob to cold storage
        documentModel = moveContentToColdStorage(session, documentModel.getRef());
        session.saveDocument(documentModel);
        // request a retrieval from the cold storage
        documentModel = service.requestRetrievalFromColdStorage(session, documentModel.getRef(), RESTORE_DURATION);
        session.saveDocument(documentModel);
        transactionalFeature.nextTransaction();
        documentModel.refresh();

        assertEquals(Boolean.TRUE,
                documentModel.getPropertyValue(ColdStorageConstants.COLD_STORAGE_BEING_RETRIEVED_PROPERTY));
    }

    @Test
    public void shouldFailRequestRetrievalBeingRetrieved() {
        DocumentModel documentModel = createFileDocument(DEFAULT_DOC_NAME, true);

        // move the blob to cold storage
        documentModel = moveContentToColdStorage(session, documentModel.getRef());
        session.saveDocument(documentModel);
        // request a retrieval from the cold storage
        documentModel = service.requestRetrievalFromColdStorage(session, documentModel.getRef(), RESTORE_DURATION);
        session.saveDocument(documentModel);

        // try to request a retrieval for a second time
        try {
            service.requestRetrievalFromColdStorage(session, documentModel.getRef(), RESTORE_DURATION);
            fail("Should fail because the cold storage content is being retrieved.");
        } catch (NuxeoException e) {
            assertEquals(SC_FORBIDDEN, e.getStatusCode());
            assertEquals(String.format("The cold storage content associated with the document: %s is being retrieved.",
                    documentModel), e.getMessage());
        }
    }

    @Test
    public void shouldFailRequestRetrievalNoContent() {
        DocumentModel documentModel = createFileDocument(DEFAULT_DOC_NAME, true);
        try {
            // try a request retrieval from the cold storage where the blob is not stored in it
            service.requestRetrievalFromColdStorage(session, documentModel.getRef(), RESTORE_DURATION);
            fail("Should fail because there no cold storage content associated to this document.");
        } catch (NuxeoException e) {
            assertEquals(SC_NOT_FOUND, e.getStatusCode());
            assertEquals(String.format("No cold storage content defined for document: %s.", documentModel),
                    e.getMessage());
        }
    }

    @Test
    public void shouldFailUpdateMainContentAlreadyInColdStorage() throws IOException {
        // move the main content into the cold storage
        DocumentModel documentModel = createFileDocument(DEFAULT_DOC_NAME, true);
        moveAndVerifyContent(session, documentModel.getRef());

        // we cannot update the main content as it is already in cold storage
        documentModel.refresh();
        documentModel.setPropertyValue(ColdStorageConstants.FILE_CONTENT_PROPERTY,
                (Serializable) Blobs.createBlob(FILE_CONTENT));
        try {
            session.saveDocument(documentModel);
            fail("Should fail because a main content document in cold storage cannot be updated.");
        } catch (NuxeoException e) {
            assertEquals(SC_FORBIDDEN, e.getStatusCode());
            assertEquals(
                    String.format("The main content of document: %s cannot be updated. It's already in cold storage.",
                            documentModel),
                    e.getMessage());
        }

        // but we should be able to update the other properties, even the attachments
        documentModel.refresh();
        documentModel.setPropertyValue("dc:title", "I update the title");
        documentModel.setPropertyValue("dc:description", "I add a description");
        var attachments = List.of(Map.of("file", Blobs.createBlob("bar", "text/plain")));
        documentModel.setPropertyValue("files:files", (Serializable) attachments);

        documentModel = session.saveDocument(documentModel);
        assertEquals("I update the title", documentModel.getPropertyValue("dc:title"));
        assertEquals("I add a description", documentModel.getPropertyValue("dc:description"));
        var actualAttachments = (List<Map<String, Blob>>) documentModel.getPropertyValue("files:files");
        assertTrue(CollectionUtils.isEqualCollection(attachments, actualAttachments));
    }

    @Test
    public void shouldMakeRestoreImmediately() throws IOException {
        DocumentModel documentModel = createFileDocument(DEFAULT_DOC_NAME, true);
        documentModel = moveAndRestore(documentModel);
        session.saveDocument(documentModel);
        coreFeature.waitForAsyncCompletion();
        verifyRestore(documentModel.getRef(), FILE_CONTENT);
    }

    protected DocumentModel verifyRestore(DocumentRef documentRef, String expectedContent) throws IOException {
        DocumentModel documentModel = session.getDocument(documentRef);

        // check main blobs
        Blob fileContent = (Blob) documentModel.getPropertyValue(ColdStorageConstants.FILE_CONTENT_PROPERTY);
        assertEquals(expectedContent, fileContent.getString());

        // we shouldn't have any ColdStorage content
        assertFalse(documentModel.hasFacet(ColdStorageConstants.COLD_STORAGE_FACET_NAME));
        return documentModel;
    }

    @Test
    @Deploy("org.nuxeo.coldstorage.test:OSGI-INF/test-thumbnail-recomputation-contrib.xml")
    public void shouldNotRecomputeThumbnail() throws IOException {
        DocumentModel documentModel = session.createDocumentModel("/", DEFAULT_DOC_NAME, "MyCustomFile");
        documentModel.setPropertyValue("file:content", (Serializable) Blobs.createBlob(FILE_CONTENT));
        documentModel = session.createDocument(documentModel);
        documentModel = session.saveDocument(documentModel);
        coreFeature.waitForAsyncCompletion();

        SimpleManagedBlob originalThumbnail = (SimpleManagedBlob) thumbnailService.getThumbnail(documentModel, session);
        assertNotNull(originalThumbnail);

        documentModel = moveAndVerifyContent(session, documentModel.getRef());

        SimpleManagedBlob thumbnailUpdateOne = (SimpleManagedBlob) thumbnailService.getThumbnail(documentModel,
                session);
        assertNotNull(thumbnailUpdateOne);
        assertEquals(originalThumbnail.getKey(), thumbnailUpdateOne.getKey());

        // Emulate the case where the content is updated
        documentModel.setPropertyValue(ColdStorageConstants.FILE_CONTENT_PROPERTY, (Serializable) thumbnailUpdateOne);

        // shouldn't recompute the thumbnail
        documentModel.putContextData(ThumbnailConstants.DISABLE_THUMBNAIL_COMPUTATION, true);

        // Only for tests purposes
        documentModel.putContextData(DISABLE_COLD_STORAGE_CHECK_UPDATE_MAIN_CONTENT_LISTENER, true);
        documentModel.putContextData(DISABLE_COLD_STORAGE_CHECK_UPDATE_COLDSTORAGE_CONTENT_LISTENER, true);
        documentModel = session.saveDocument(documentModel);

        transactionalFeature.nextTransaction();
        documentModel.refresh();

        SimpleManagedBlob lastThumbnail = (SimpleManagedBlob) thumbnailService.getThumbnail(documentModel, session);
        assertNotNull(lastThumbnail);
        assertEquals(originalThumbnail.getKey(), lastThumbnail.getKey());
    }

    @Test
    public void shouldNotRecomputeFullText() {
        StorageConfiguration storageConfiguration = coreFeature.getStorageConfiguration();
        assumeTrue("fulltext search not supported", storageConfiguration.supportsFulltextSearch());
        // XXX following line to be removed when https://jira.nuxeo.com/browse/NXP-30931 done
        assumeTrue("Waiting for NXP-30931", !coreFeature.getStorageConfiguration().isVCS());

        // Create a doc with 'foo' text as main content
        DocumentModel documentModel = createFileDocument(DEFAULT_DOC_NAME, true);
        transactionalFeature.nextTransaction();
        DocumentModelList res = session.query("SELECT * FROM Document WHERE ecm:fulltext = 'foo'");
        assertEquals(1, res.size());

        documentModel = moveContentToColdStorage(session, documentModel.getRef());

        transactionalFeature.nextTransaction();
        coreFeature.getStorageConfiguration().waitForFulltextIndexing();

        // Assert binary text has not been erased after doc sent to cold storage
        res = session.query("SELECT * FROM Document WHERE ecm:fulltext = 'foo'");
        assertEquals(1, res.size());
    }

    protected DocumentModel moveAndRestore(DocumentModel documentModel) throws IOException {
        // move the blob to cold storage and verify the content
        moveAndVerifyContent(session, documentModel.getRef());
        // undo move from the cold storage
        return service.restoreContentFromColdStorage(session, documentModel.getRef());
    }

    protected DocumentModel moveAndVerifyContent(CoreSession session, DocumentRef ref) throws IOException {
        DocumentModel documentModel = moveContentToColdStorage(session, ref);
        session.saveDocument(documentModel);
        transactionalFeature.nextTransaction();
        return verifyContent(session, documentModel.getRef(), false, FILE_CONTENT);
    }

    protected DocumentModel verifyContent(CoreSession session, DocumentRef documentRef, boolean withThumbnail,
            String expectedContent) throws IOException {
        DocumentModel documentModel = session.getDocument(documentRef);

        assertTrue(documentModel.hasFacet(ColdStorageConstants.COLD_STORAGE_FACET_NAME));

        if (withThumbnail) {
            Blob fileContent = (Blob) documentModel.getPropertyValue(ColdStorageConstants.FILE_CONTENT_PROPERTY);
            assertEquals(DummyThumbnailFactory.DUMMY_THUMBNAIL_CONTENT, fileContent.getString());
        } else {
            assertNull(documentModel.getPropertyValue(ColdStorageConstants.FILE_CONTENT_PROPERTY));
        }

        // check if the `coldstorage:coldContent` property contains the original file content
        Blob content = (Blob) documentModel.getPropertyValue(COLD_STORAGE_CONTENT_PROPERTY);
        assertNotNull(content);
        assertEquals(expectedContent, content.getString());
        assertEquals(getBlobProviderName(), ((ManagedBlob) content).getProviderId());
        assertNull(documentModel.getPropertyValue(COLD_STORAGE_BEING_RESTORED_PROPERTY));
        assertNull(documentModel.getPropertyValue(COLD_STORAGE_BEING_RETRIEVED_PROPERTY));
        assertNull(documentModel.getPropertyValue(COLD_STORAGE_TO_BE_RESTORED_PROPERTY));
        assertNull(documentModel.getPropertyValue(COLD_STORAGE_CONTENT_AVAILABLE_IN_COLDSTORAGE));
        assertTrue(Boolean.TRUE.equals(documentModel.getPropertyValue(COLD_STORAGE_CONTENT_STORAGE_CLASS_TO_UPDATED)));
        assertNull(documentModel.getPropertyValue(COLD_STORAGE_CONTENT_DOWNLOADABLE_UNTIL));
        return documentModel;
    }

    protected DocumentModel moveAndRequestRetrievalFromColdStorage(String documentName) {
        DocumentModel documentModel = createFileDocument(documentName, true);
        documentModel = moveContentToColdStorage(session, documentModel.getRef());
        session.saveDocument(documentModel);
        // request a retrieval from the cold storage
        documentModel = service.requestRetrievalFromColdStorage(session, documentModel.getRef(), RESTORE_DURATION);
        return session.saveDocument(documentModel);
    }

    protected void checkAvailabilityOfDocuments(List<String> expectedAvailableDocIds, Instant downloadableUntil,
            int totalBeingRetrieved) {
        try (CapturingEventListener listener = new CapturingEventListener(
                ColdStorageConstants.COLD_STORAGE_CONTENT_AVAILABLE_EVENT_NAME)) {
            ColdStorageContentStatus coldStorageContentStatus = service.checkColdStorageContentAvailability(session);

            assertEquals(totalBeingRetrieved, coldStorageContentStatus.getTotalBeingRetrieved());
            var expectedSizeOfDocs = expectedAvailableDocIds.size();
            assertEquals(expectedSizeOfDocs, coldStorageContentStatus.getTotalAvailable());
            assertTrue(listener.hasBeenFired(ColdStorageConstants.COLD_STORAGE_CONTENT_AVAILABLE_EVENT_NAME));
            assertEquals(expectedSizeOfDocs, listener.streamCapturedEvents().count());

            List<String> docEventIds = listener.streamCapturedEventContexts(DocumentEventContext.class)
                                               .map(docCtx -> docCtx.getSourceDocument().getId())
                                               .sorted()
                                               .collect(Collectors.toList());

            expectedAvailableDocIds.sort(Comparator.naturalOrder());
            assertEquals(expectedAvailableDocIds, docEventIds);

            listener.streamCapturedEvents().forEach(event -> {
                DocumentEventContext docCtx = (DocumentEventContext) event.getContext();
                Map<String, Serializable> properties = docCtx.getProperties();

                DocumentModel documentModel = session.getDocument(new IdRef(docCtx.getSourceDocument().getId()));
                assertEquals(String.format("An unexpected deadline for cold storage of document: %s", documentModel), //
                        downloadableUntil.toString(),
                        properties.get(ColdStorageConstants.COLD_STORAGE_CONTENT_AVAILABLE_UNTIL_MAIL_TEMPLATE_KEY));

                String serverUrl = NotificationServiceHelper.getNotificationService().getServerUrlPrefix();
                String expectedDownloadUrl = serverUrl + downloadService.getDownloadUrl(documentModel,
                        ColdStorageConstants.COLD_STORAGE_CONTENT_PROPERTY, null);
                assertEquals(String.format("An unexpected downloadable url for document: %s", documentModel), //
                        expectedDownloadUrl,
                        properties.get(ColdStorageConstants.COLD_STORAGE_CONTENT_ARCHIVE_LOCATION_MAIL_TEMPLATE_KEY));
            });
        }

    }

    protected DocumentModel createFileDocument(String name, boolean addBlobContent, ACE... aces) {
        return createFileDocument(name, addBlobContent ? Blobs.createBlob(FILE_CONTENT) : null, aces);
    }

    protected DocumentModel createFileDocument(String name, Blob blob, ACE... aces) {
        DocumentModel documentModel = session.createDocumentModel("/", name, "File");
        if (blob != null) {
            documentModel.setPropertyValue("file:content", (Serializable) Blobs.createBlob(FILE_CONTENT));
        }
        DocumentModel document = session.createDocument(documentModel);
        if (aces.length > 0) {
            ACP acp = documentModel.getACP();
            ACL acl = acp.getOrCreateACL();
            acl.addAll(List.of(aces));
            document.setACP(acp, true);
        }
        return document;
    }

    protected List<DocumentModel> createSameBlobFileDocuments(String name, int nbDoc, Blob blob, String username,
            String... permissions) {
        List<DocumentModel> docs = new ArrayList<DocumentModel>();
        for (int i = 0; i < nbDoc; i++) {
            DocumentModel documentModel = session.createDocumentModel("/", name + (i + 1), "File");
            documentModel.setPropertyValue("file:content", (Serializable) blob);
            DocumentModel document = session.createDocument(documentModel);
            ACP acp = document.getACP();
            ACL acl = acp.getOrCreateACL();
            for (String permission : permissions) {
                acl.add(new ACE(username, permission, true));
            }
            session.setACP(document.getRef(), acp, false);
            docs.add(document);
        }
        return docs;
    }

    protected DocumentModel moveContentToColdStorage(CoreSession session, DocumentRef documentRef) {
        DocumentModel documentModel = service.moveContentToColdStorage(session, documentRef);
        session.saveDocument(documentModel);
        return documentModel;
    }

}
