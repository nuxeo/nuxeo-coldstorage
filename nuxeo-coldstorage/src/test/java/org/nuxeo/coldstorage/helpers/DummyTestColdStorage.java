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

package org.nuxeo.coldstorage.helpers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.nuxeo.coldstorage.DummyColdStorageFeature;
import org.nuxeo.coldstorage.blob.providers.DummyBlobProvider;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.blob.BlobStatus;
import org.nuxeo.ecm.core.blob.ManagedBlob;
import org.nuxeo.runtime.test.runner.Features;

@Features(DummyColdStorageFeature.class)
public class DummyTestColdStorage extends AbstractTestColdStorageHelper {

    @Override
    protected String getBlobProviderName() {
        return "dummy";
    }

    @Test
    public void shouldRestore() {
        DocumentModel documentModel = createFileDocument(DEFAULT_DOC_NAME, true);

        // move the blob to cold storage
        documentModel = moveContentToColdStorage(session, documentModel.getRef());
        session.saveDocument(documentModel);
        // undo move from the cold storage
        documentModel = ColdStorageHelper.restoreContentFromColdStorage(session, documentModel.getRef());
        session.saveDocument(documentModel);
        transactionalFeature.nextTransaction();
        documentModel.refresh();

    }

    @Test
    public void shouldCheckAvailability() {
        List<String> documents = Arrays.asList( //
                moveAndRequestRetrievalFromColdStorage(DEFAULT_DOC_NAME).getId(),
                moveAndRequestRetrievalFromColdStorage("anyFile2").getId(),
                moveAndRequestRetrievalFromColdStorage("anyFile3").getId());

        Instant downloadableUntil = Instant.now().plus(7, ChronoUnit.DAYS);
        transactionalFeature.nextTransaction();

        BlobStatus coldContentStatusOfFile1 = new BlobStatus().withDownloadable(true)
                                                              .withDownloadableUntil(downloadableUntil);
        addColdStorageContentBlobStatus(documents.get(0), coldContentStatusOfFile1);

        BlobStatus coldContentStatusOfFile2 = new BlobStatus().withDownloadable(false);
        addColdStorageContentBlobStatus(documents.get(1), coldContentStatusOfFile2);

        BlobStatus coldContentStatusOfFile3 = new BlobStatus().withDownloadable(false);
        addColdStorageContentBlobStatus(documents.get(2), coldContentStatusOfFile3);

        // only cold content of 'anyFile' is available
        checkAvailabilityOfDocuments(Collections.singletonList(documents.get(0)), downloadableUntil, 2);

        transactionalFeature.nextTransaction();

        coldContentStatusOfFile2.withDownloadable(true).withDownloadableUntil(downloadableUntil);
        coldContentStatusOfFile3.withDownloadable(true).withDownloadableUntil(downloadableUntil);

        // the others 'anyFile2' and 'anyFile3' are now available too
        checkAvailabilityOfDocuments(Arrays.asList(documents.get(1), documents.get(2)), downloadableUntil, 0);
    }

    protected void addColdStorageContentBlobStatus(String docId, BlobStatus blobStatus) {
        ManagedBlob coldContent = (ManagedBlob) session.getDocument(new IdRef(docId))
                                                       .getPropertyValue(
                                                               ColdStorageHelper.COLD_STORAGE_CONTENT_PROPERTY);

        DummyBlobProvider blobProvider = (DummyBlobProvider) blobManager.getBlobProvider(coldContent.getProviderId());
        blobProvider.addStatus(coldContent, blobStatus);
    }
}
