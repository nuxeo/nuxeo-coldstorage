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

package org.nuxeo.coldstorage.helpers;

import static javax.servlet.http.HttpServletResponse.SC_CONFLICT;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.commons.collections.CollectionUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CloseableCoreSession;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.security.ACE;
import org.nuxeo.ecm.core.api.security.ACL;
import org.nuxeo.ecm.core.api.security.ACP;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.blob.BlobManager;
import org.nuxeo.ecm.core.blob.ManagedBlob;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.ecm.core.event.test.CapturingEventListener;
import org.nuxeo.ecm.core.io.download.DownloadService;
import org.nuxeo.ecm.core.test.CoreFeature;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;

/**
 * @since 11.1
 */
@RunWith(FeaturesRunner.class)
public abstract class AbstractTestColdStorageHelper {

    protected static final String FILE_CONTENT = "foo";

    protected static final Duration RESTORE_DURATION = Duration.ofDays(5);

    protected static final String DEFAULT_DOC_NAME = "anyFile";

    @Inject
    protected CoreSession session;

    @Inject
    protected TransactionalFeature transactionalFeature;

    @Inject
    protected BlobManager blobManager;

    @Inject
    protected DownloadService downloadService;

    @Inject
    protected CoreFeature coreFeature;

    protected abstract String getBlobProviderName();

    @Test
    public void shouldMoveToColdStorage() throws IOException {
        // with regular user with "WriteColdStorage" permission
        ACE[] aces = { new ACE("john", SecurityConstants.READ, true), //
                new ACE("john", SecurityConstants.WRITE, true), //
                new ACE("john", ColdStorageHelper.WRITE_COLD_STORAGE, true) };
        DocumentModel documentModel = createFileDocument(DEFAULT_DOC_NAME, true, aces);

        try (CloseableCoreSession userSession = coreFeature.openCoreSession("john")) {
            moveAndVerifyContent(userSession, documentModel);
        }

        // with Administrator
        documentModel = createFileDocument(DEFAULT_DOC_NAME, true);
        moveAndVerifyContent(session, documentModel);
    }

    @Test
    public void shouldFailWithoutRightPermissions() {
        ACE[] aces = { new ACE("john", SecurityConstants.READ, true) };
        DocumentModel documentModel = createFileDocument(DEFAULT_DOC_NAME, true, aces);

        try (CloseableCoreSession userSession = coreFeature.openCoreSession("john")) {
            moveContentToColdStorage(userSession, documentModel.getRef());
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
    public void shouldRequestRetrieval() {
        DocumentModel documentModel = createFileDocument(DEFAULT_DOC_NAME, true);

        // move the blob to cold storage
        documentModel = moveContentToColdStorage(session, documentModel.getRef());
        session.saveDocument(documentModel);
        // request a retrieval from the cold storage
        documentModel = ColdStorageHelper.requestRetrievalFromColdStorage(session, documentModel.getRef(),
                RESTORE_DURATION);
        session.saveDocument(documentModel);
        transactionalFeature.nextTransaction();
        documentModel.refresh();

        assertEquals(Boolean.TRUE,
                documentModel.getPropertyValue(ColdStorageHelper.COLD_STORAGE_BEING_RETRIEVED_PROPERTY));
    }

    @Test
    public void shouldFailRequestRetrievalBeingRetrieved() {
        DocumentModel documentModel = createFileDocument(DEFAULT_DOC_NAME, true);

        // move the blob to cold storage
        documentModel = moveContentToColdStorage(session, documentModel.getRef());
        session.saveDocument(documentModel);
        // request a retrieval from the cold storage
        documentModel = ColdStorageHelper.requestRetrievalFromColdStorage(session, documentModel.getRef(),
                RESTORE_DURATION);
        session.saveDocument(documentModel);

        // try to request a retrieval for a second time
        try {
            ColdStorageHelper.requestRetrievalFromColdStorage(session, documentModel.getRef(), RESTORE_DURATION);
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
            ColdStorageHelper.requestRetrievalFromColdStorage(session, documentModel.getRef(), RESTORE_DURATION);
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
        moveAndVerifyContent(session, documentModel);

        // we cannot update the main content as it is already in cold storage
        documentModel.refresh();
        documentModel.setPropertyValue(ColdStorageHelper.FILE_CONTENT_PROPERTY,
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
        Map<String, Serializable> blobs = new HashMap<>();
        blobs.put("file", (Serializable) Blobs.createBlob("bar", "text/plain"));
        List<Map<String, Serializable>> attachments = new ArrayList<>();
        attachments.add(blobs);
        documentModel.setPropertyValue("files:files", (Serializable) attachments);

        documentModel = session.saveDocument(documentModel);
        assertEquals("I update the title", documentModel.getPropertyValue("dc:title"));
        assertEquals("I add a description", documentModel.getPropertyValue("dc:description"));

        List<Map<String, Serializable>> actualAttachments = (List<Map<String, Serializable>>) documentModel.getPropertyValue(
                "files:files");
        assertTrue(CollectionUtils.isEqualCollection(attachments, actualAttachments));
    }

    protected void moveAndVerifyContent(CoreSession session, DocumentModel documentModel) throws IOException {
        documentModel = moveContentToColdStorage(session, documentModel.getRef());
        session.saveDocument(documentModel);
        transactionalFeature.nextTransaction();
        documentModel.refresh();

        assertTrue(documentModel.hasFacet(ColdStorageHelper.COLD_STORAGE_FACET_NAME));

        assertNull(documentModel.getPropertyValue(ColdStorageHelper.FILE_CONTENT_PROPERTY));

        // check if the `coldstorage:coldContent` property contains the original file content
        Blob content = (Blob) documentModel.getPropertyValue(ColdStorageHelper.COLD_STORAGE_CONTENT_PROPERTY);
        assertNotNull(content);
        assertEquals(FILE_CONTENT, content.getString());
        assertEquals(getBlobProviderName(), ((ManagedBlob) content).getProviderId());
    }

    protected DocumentModel moveAndRequestRetrievalFromColdStorage(String documentName) {
        DocumentModel documentModel = createFileDocument(documentName, true);
        documentModel = moveContentToColdStorage(session, documentModel.getRef());
        session.saveDocument(documentModel);
        session.saveDocument(documentModel);
        // request a retrieval from the cold storage
        documentModel = ColdStorageHelper.requestRetrievalFromColdStorage(session, documentModel.getRef(),
                RESTORE_DURATION);
        return session.saveDocument(documentModel);
    }

    protected void checkAvailabilityOfDocuments(List<String> expectedAvailableDocIds, Instant downloadableUntil,
            int totalBeingRetrieved) {
        try (CapturingEventListener listener = new CapturingEventListener(
                ColdStorageHelper.COLD_STORAGE_CONTENT_AVAILABLE_EVENT_NAME)) {
            ColdStorageHelper.ColdStorageContentStatus coldStorageContentStatus = ColdStorageHelper.checkColdStorageContentAvailability(
                    session);

            assertEquals(totalBeingRetrieved, coldStorageContentStatus.getTotalBeingRetrieved());
            int expectedSizeOfDocs = expectedAvailableDocIds.size();
            assertEquals(expectedSizeOfDocs, coldStorageContentStatus.getTotalAvailable());
            assertTrue(listener.hasBeenFired(ColdStorageHelper.COLD_STORAGE_CONTENT_AVAILABLE_EVENT_NAME));
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
                        properties.get(ColdStorageHelper.COLD_STORAGE_CONTENT_AVAILABLE_UNTIL_MAIL_TEMPLATE_KEY));

                String expectedDownloadUrl = downloadService.getDownloadUrl(documentModel,
                        ColdStorageHelper.COLD_STORAGE_CONTENT_PROPERTY, null);
                assertEquals(String.format("An unexpected downloadable url for document: %s", documentModel), //
                        expectedDownloadUrl,
                        properties.get(ColdStorageHelper.COLD_STORAGE_CONTENT_ARCHIVE_LOCATION_MAIL_TEMPLATE_KEY));
            });
        }

    }

    protected DocumentModel createFileDocument(String name, boolean addBlobContent, ACE... aces) {
        DocumentModel documentModel = session.createDocumentModel("/", name, "File");
        if (addBlobContent) {
            documentModel.setPropertyValue("file:content", (Serializable) Blobs.createBlob(FILE_CONTENT));
        }
        DocumentModel document = session.createDocument(documentModel);
        if (aces.length > 0) {
            ACP acp = documentModel.getACP();
            ACL acl = acp.getOrCreateACL();
            acl.addAll(Arrays.asList(aces));
            document.setACP(acp, true);
        }
        return document;
    }

    protected DocumentModel moveContentToColdStorage(CoreSession session, DocumentRef documentRef) {
        DocumentModel documentModel = ColdStorageHelper.moveContentToColdStorage(session, documentRef);
        session.saveDocument(documentModel);
        return documentModel;
    }

}
