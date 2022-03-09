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

import java.io.Serializable;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.junit.Test;
import org.nuxeo.coldstorage.ColdStorageConstants;
import org.nuxeo.coldstorage.DummyColdStorageFeature;
import org.nuxeo.coldstorage.blob.providers.DummyBlobProvider;
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
import org.nuxeo.runtime.test.runner.Features;

@Features(DummyColdStorageFeature.class)
public class TestDummyColdStorageService extends AbstractTestColdStorageService {

    @Inject
    protected EventService eventService;

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
        try (CapturingEventListener listener = new CapturingEventListener(
                DownloadService.EVENT_NAME,
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
            assertTrue(listener.findFirstCapturedEvent(ColdStorageConstants.COLD_STORAGE_CONTENT_DOWNLOAD_EVENT_NAME).isPresent());
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
