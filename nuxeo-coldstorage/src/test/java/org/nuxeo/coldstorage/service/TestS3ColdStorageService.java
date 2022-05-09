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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.io.IOException;
import java.io.Serializable;

import org.junit.Test;
import org.nuxeo.coldstorage.ColdStorageConstants;
import org.nuxeo.coldstorage.S3ColdStorageFeature;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.blob.BlobStatus;
import org.nuxeo.ecm.core.blob.ManagedBlob;
import org.nuxeo.runtime.test.runner.Features;

import com.amazonaws.services.s3.model.StorageClass;

@Features(S3ColdStorageFeature.class)
public class TestS3ColdStorageService extends AbstractTestColdStorageService {

    @Test
    public void shouldMoveToColdStorageIfBlobAlreadyIs() throws IOException {
        Blob blob = Blobs.createBlob(FILE_CONTENT);
        // First doc with given content
        DocumentModel documentModel = createFileDocument(DEFAULT_DOC_NAME, blob);
        blob = (Blob) documentModel.getPropertyValue(ColdStorageConstants.FILE_CONTENT_PROPERTY);
        moveAndVerifyContent(session, documentModel.getRef());

        // Second doc with same content
        documentModel = createFileDocument(DEFAULT_DOC_NAME + "_bis", blob);
        transactionalFeature.nextTransaction();
        verifyContent(session, documentModel.getRef(), FILE_CONTENT);

        // Third doc with blob edit
        documentModel = createFileDocument(DEFAULT_DOC_NAME + "_ter", Blobs.createBlob(FILE_CONTENT + "_ter"));
        documentModel = session.getDocument(documentModel.getRef());
        assertFalse(documentModel.hasFacet(ColdStorageConstants.COLD_STORAGE_FACET_NAME));
        documentModel.setPropertyValue(ColdStorageConstants.FILE_CONTENT_PROPERTY, (Serializable) blob);
        session.saveDocument(documentModel);
        transactionalFeature.nextTransaction();
        verifyContent(session, documentModel.getRef(), FILE_CONTENT);
    }

    @Override
    protected void verifyColdContent(Blob content) throws IOException {
        assertNotNull(content);
        BlobStatus status = getStatus((ManagedBlob) content);
        assertFalse(status.isDownloadable());
        assertEquals(StorageClass.Glacier.toString(), status.getStorageClass());
    }

}
