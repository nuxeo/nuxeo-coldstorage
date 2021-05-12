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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;
import org.nuxeo.coldstorage.S3ColdStorageFeature;
import org.nuxeo.coldstorage.S3TestHelper;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.blob.ManagedBlob;
import org.nuxeo.runtime.test.runner.Features;

@Features(S3ColdStorageFeature.class)
public class S3TestColdStorage extends AbstractTestColdStorageHelper {

    protected S3TestHelper s3TestHelper = S3TestHelper.getInstance();

    @Override
    protected String getBlobProviderName() {
        return "glacier";
    }

    @Test
    public void shouldBeingRetrieved() {
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

        assertEquals(Boolean.TRUE, s3TestHelper.isBlobContentBeingRetrieved(documentModel));
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

        assertEquals(Boolean.TRUE,
                documentModel.getPropertyValue(ColdStorageHelper.COLD_STORAGE_TO_BE_RESTORED_PROPERTY));

        assertEquals(Boolean.TRUE, s3TestHelper.isBlobContentBeingRetrieved(documentModel));
    }

    @Override
    protected void moveAndVerifyContent(CoreSession session, DocumentModel documentModel) throws IOException {
        documentModel = moveContentToColdStorage(session, documentModel.getRef());
        session.saveDocument(documentModel);
        // Mock AWS Lifecycle rule
        s3TestHelper.moveBlobContentToGlacier(documentModel);
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

    @Override
    protected DocumentModel moveContentToColdStorage(CoreSession session, DocumentRef documentRef) {
        DocumentModel documentModel = super.moveContentToColdStorage(session, documentRef);
        session.saveDocument(documentModel);
        // Mock AWS Lifecycle rule
        s3TestHelper.moveBlobContentToGlacier(session.getDocument(documentRef));
        return documentModel;
    }
}
