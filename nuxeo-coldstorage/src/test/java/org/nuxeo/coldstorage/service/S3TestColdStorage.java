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

import org.junit.Test;
import org.nuxeo.coldstorage.ColdStorageConstants;
import org.nuxeo.coldstorage.S3ColdStorageFeature;
import org.nuxeo.coldstorage.S3TestHelper;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.runtime.test.runner.Features;

@Features(S3ColdStorageFeature.class)
public class S3TestColdStorage extends AbstractTestColdStorageService {

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
        documentModel = service.requestRetrievalFromColdStorage(session, documentModel.getRef(),
                RESTORE_DURATION);
        session.saveDocument(documentModel);
        transactionalFeature.nextTransaction();
        documentModel.refresh();

        assertEquals(Boolean.TRUE,
                documentModel.getPropertyValue(ColdStorageConstants.COLD_STORAGE_BEING_RETRIEVED_PROPERTY));

        assertEquals(Boolean.TRUE, ColdStorageServiceImpl.getBlobStatus(documentModel).isOngoingRestore());
    }

    @Override
    protected DocumentModel moveAndRestore(DocumentModel documentModel) {
        // move the blob to cold storage
        moveContentToColdStorage(session, documentModel.getRef(), false);
        // undo move from the cold storage
        return service.restoreContentFromColdStorage(session, documentModel.getRef());
    }

    @Override
    protected DocumentModel moveContentToColdStorage(CoreSession session, DocumentRef documentRef) {
        return moveContentToColdStorage(session, documentRef, true);
    }

    protected DocumentModel moveContentToColdStorage(CoreSession session, DocumentRef documentRef,
            boolean changeStorageClass) {
        DocumentModel documentModel = super.moveContentToColdStorage(session, documentRef);
        session.saveDocument(documentModel);
        // Mock AWS Lifecycle rule
        if (changeStorageClass) {
            s3TestHelper.moveBlobContentToGlacier(session.getDocument(documentRef));
        }
        return documentModel;
    }
}
