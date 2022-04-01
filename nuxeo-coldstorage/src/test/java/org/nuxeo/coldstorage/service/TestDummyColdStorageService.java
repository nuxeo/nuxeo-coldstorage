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
import static org.junit.Assert.assertTrue;
import static org.nuxeo.ecm.core.api.security.SecurityConstants.READ;
import static org.nuxeo.ecm.core.api.security.SecurityConstants.WRITE;
import static org.nuxeo.coldstorage.ColdStorageConstants.WRITE_COLD_STORAGE;

import java.io.IOException;
import java.io.Serializable;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;

import org.junit.Test;
import org.nuxeo.coldstorage.ColdStorageConstants;
import org.nuxeo.coldstorage.DummyColdStorageFeature;
import org.nuxeo.coldstorage.blob.providers.DummyBlobProvider;
import org.nuxeo.coldstorage.action.PropagateMoveToColdStorageContentAction;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CloseableCoreSession;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.event.CoreEventConstants;
import org.nuxeo.ecm.core.blob.BlobStatus;
import org.nuxeo.ecm.core.blob.ManagedBlob;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventService;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.ecm.core.event.test.CapturingEventListener;
import org.nuxeo.ecm.core.io.download.DownloadService;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.LogCaptureFeature;

@Features(DummyColdStorageFeature.class)
public class TestDummyColdStorageService extends AbstractTestColdStorageService {

    @Inject
    protected EventService eventService;
    
    @Inject
    protected LogCaptureFeature.Result logResult;

    @Override
    protected String getBlobProviderName() {
        return "dummy";
    }

    @Test
    public void shouldFireDedicatedDownloadEvent() {
        DocumentModel doc = moveAndRequestRetrievalFromColdStorage(DEFAULT_DOC_NAME);
        Instant downloadableUntil = Instant.now().plus(7, ChronoUnit.DAYS);
        transactionalFeature.nextTransaction();

        // Create a blob of which retrieval was requested and that is retrieved
        BlobStatus coldContentStatusOfFile = new BlobStatus().withDownloadable(true)
                                                             .withDownloadableUntil(downloadableUntil);
        addColdStorageContentBlobStatus(doc.getId(), coldContentStatusOfFile);
        try (CapturingEventListener listener = new CapturingEventListener(DownloadService.EVENT_NAME,
                ColdStorageConstants.COLD_STORAGE_CONTENT_DOWNLOAD_EVENT_NAME)) {
            // let's mimic a download
            Map<String, Serializable> map = new HashMap<>();
            map.put("blobXPath", ColdStorageConstants.COLD_STORAGE_CONTENT_PROPERTY);
            DocumentEventContext ctx = new DocumentEventContext(session, session.getPrincipal(), doc);
            ctx.setProperty(CoreEventConstants.REPOSITORY_NAME, doc.getRepositoryName());
            ctx.setProperty("extendedInfos", (Serializable) map);
            Event event = ctx.newEvent(DownloadService.EVENT_NAME);
            eventService.fireEvent(event);

            // and assert it is cancelled and a cold storage download event is fired instead
            assertEquals(2, listener.streamCapturedEvents().count());
            assertTrue(listener.findFirstCapturedEvent(DownloadService.EVENT_NAME).get().isCanceled());
            assertTrue(listener.findFirstCapturedEvent(ColdStorageConstants.COLD_STORAGE_CONTENT_DOWNLOAD_EVENT_NAME)
                               .isPresent());
        }
    }

    @Test
    @Deploy("org.nuxeo.coldstorage.test:OSGI-INF/test-coldstorage-bulk-contrib.xml")
    @LogCaptureFeature.FilterOn(loggerClass = PropagateMoveToColdStorageContentAction.class, logLevel = "INFO")
    public void shouldMoveManyDocsWithSameBlobToColdStorage() throws IOException {
        // with regular user with "WriteColdStorage" permission
        // Let's create a list of documents all sharing the same blob
        List<DocumentModel> list1 = createSameBlobFileDocuments(DEFAULT_DOC_NAME, 10, Blobs.createBlob(FILE_CONTENT),
                "john", READ, WRITE, WRITE_COLD_STORAGE);
        // and another one with a different blob
        List<DocumentModel> list2 = createSameBlobFileDocuments(DEFAULT_DOC_NAME, 10,
                Blobs.createBlob(FILE_CONTENT + FILE_CONTENT), "john", READ, WRITE, WRITE_COLD_STORAGE);
        coreFeature.waitForAsyncCompletion(); // for thumbnail generation
        List<DocumentModel> documentModels = new ArrayList<DocumentModel>();
        documentModels = Stream.concat(list1.stream(), list2.stream()).collect(Collectors.toList());
        try (CloseableCoreSession userSession = coreFeature.openCoreSession("john")) {
            // Move only the 2 first doc
            service.moveToColdStorage(userSession, list1.get(0).getRef());
            service.moveToColdStorage(userSession, list2.get(0).getRef());
            coreFeature.waitForAsyncCompletion();

            // Moving the 2 first document to cold storage should moved all the other ones
            for (DocumentModel doc : list1) {
                verifyContent(userSession, doc.getRef(), true, FILE_CONTENT);
            }
            for (DocumentModel doc : list2) {
                verifyContent(userSession, doc.getRef(), true, FILE_CONTENT + FILE_CONTENT);
            }
        }

        // The BAF in charge of moving the other documents should be silent
        List<String> caughtEvents = logResult.getCaughtEventMessages();
        assertTrue(caughtEvents.isEmpty());

        // Restore only the 2 first doc
        service.restoreContentFromColdStorage(session, list1.get(0).getRef());
        service.restoreContentFromColdStorage(session, list2.get(0).getRef());

        // Restoring the first document to cold storage should restore all the other ones
        coreFeature.waitForAsyncCompletion();
        for (DocumentModel doc : list1) {
            verifyRestore(doc.getRef(), FILE_CONTENT);
        }
        for (DocumentModel doc : list2) {
            verifyRestore(doc.getRef(), FILE_CONTENT + FILE_CONTENT);
        }
    }

    @Test
    public void shouldCheckAvailability() {
        List<String> documents = Arrays.asList( //
                moveAndRequestRetrievalFromColdStorage(DEFAULT_DOC_NAME).getId(),
                moveAndRequestRetrievalFromColdStorage("anyFile2").getId(),
                moveAndRequestRetrievalFromColdStorage("anyFile3").getId());

        Instant downloadableUntil = Instant.now().plus(7, ChronoUnit.DAYS);
        transactionalFeature.nextTransaction();

        // Create a blob of which retrieval was requested and that is retrieved
        BlobStatus coldContentStatusOfFile1 = new BlobStatus().withDownloadable(true)
                                                              .withDownloadableUntil(downloadableUntil);
        addColdStorageContentBlobStatus(documents.get(0), coldContentStatusOfFile1);

        // Create a blob of which retrieval was requested and that is still being retrieved
        BlobStatus coldContentStatusOfFile2 = new BlobStatus().withDownloadable(false).withOngoingRestore(true);
        addColdStorageContentBlobStatus(documents.get(1), coldContentStatusOfFile2);

        // Create a blob of which retrieval was requested but is no longer retrieved because retrieval time expired
        BlobStatus coldContentStatusOfFile3 = new BlobStatus().withDownloadable(false).withOngoingRestore(false);
        addColdStorageContentBlobStatus(documents.get(2), coldContentStatusOfFile3);

        // only cold content of 'anyFile' is available
        checkAvailabilityOfDocuments(Collections.singletonList(documents.get(0)), downloadableUntil, 1);

        transactionalFeature.nextTransaction();

        coldContentStatusOfFile2.withDownloadable(true).withDownloadableUntil(downloadableUntil);

        // the others 'anyFile2' and 'anyFile3' are now available too
        checkAvailabilityOfDocuments(Collections.singletonList(documents.get(1)), downloadableUntil, 0);
    }

    protected void addColdStorageContentBlobStatus(String docId, BlobStatus blobStatus) {
        ManagedBlob coldContent = (ManagedBlob) session.getDocument(new IdRef(docId))
                                                       .getPropertyValue(
                                                               ColdStorageConstants.COLD_STORAGE_CONTENT_PROPERTY);

        DummyBlobProvider blobProvider = (DummyBlobProvider) blobManager.getBlobProvider(coldContent.getProviderId());
        blobProvider.addStatus(coldContent, blobStatus);
    }
}
