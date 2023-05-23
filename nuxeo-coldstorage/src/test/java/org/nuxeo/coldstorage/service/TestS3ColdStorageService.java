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
import static org.junit.Assert.assertNull;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.io.Serializable;

import org.junit.Test;
import org.nuxeo.coldstorage.ColdStorageConstants;
import org.nuxeo.coldstorage.ColdStorageFeature;
import org.nuxeo.ecm.blob.s3.S3BlobProviderFeature;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.blob.BlobManager;
import org.nuxeo.ecm.core.blob.BlobProvider;
import org.nuxeo.ecm.core.blob.BlobStatus;
import org.nuxeo.ecm.core.blob.ManagedBlob;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.test.runner.Features;

import com.amazonaws.services.s3.model.StorageClass;

@Features({ ColdStorageFeature.class, S3BlobProviderFeature.class })
public class TestS3ColdStorageService extends AbstractTestColdStorageService {

    @Test
    public void shouldMoveToColdStorageIfBlobAlreadyIs() throws IOException {
        final String fileContent = FILE_CONTENT + System.currentTimeMillis();
        Blob blob = Blobs.createBlob(fileContent);
        // First doc with given content
        DocumentModel documentModel = createFileDocument(DEFAULT_DOC_NAME, fileContent);
        blob = (Blob) documentModel.getPropertyValue(ColdStorageConstants.FILE_CONTENT_PROPERTY);
        moveAndVerifyContent(session, documentModel.getRef());

        // Second doc with same content
        documentModel = createFileDocument(DEFAULT_DOC_NAME + "_bis", fileContent);
        transactionalFeature.nextTransaction();
        assertSentToColdStorage(session, documentModel.getRef());

        // Third doc with blob edit
        documentModel = createFileDocument(DEFAULT_DOC_NAME + "_ter", fileContent + "_ter");
        documentModel = session.getDocument(documentModel.getRef());
        assertFalse(documentModel.hasFacet(ColdStorageConstants.COLD_STORAGE_FACET_NAME));
        documentModel.setPropertyValue(ColdStorageConstants.FILE_CONTENT_PROPERTY, (Serializable) blob);
        session.saveDocument(documentModel);
        transactionalFeature.nextTransaction();
        assertSentToColdStorage(session, documentModel.getRef());
    }

    @Test
    public void shouldGCColdStorageDocumentBlob() throws IOException {
        assumeTrue("MongoDB feature only", coreFeature.getStorageConfiguration().isDBS());
        final String CONTENT = "hello world";
        DocumentModel doc = session.createDocumentModel("/", "doc", "File");
        doc.setPropertyValue("file:content", (Serializable) Blobs.createBlob(CONTENT));
        doc = session.createDocument(doc);
        session.saveDocument(doc);

        moveAndVerifyContent(session, doc.getRef());

        ManagedBlob blob = (ManagedBlob) doc.getPropertyValue("file:content");
        BlobProvider blobProvider = Framework.getService(BlobManager.class).getBlobProvider(blob.getProviderId());
        // Assert blob does exist
        assertNotNull(blobProvider.getFile(blob));

        session.removeDocument(doc.getRef());
        coreFeature.waitForAsyncCompletion();

        // Assert blob does not exist anymore
        assertNull(blobProvider.getFile(blob));
    }

    @Override
    protected void verifyColdContent(Blob content) throws IOException {
        assertNotNull(content);
        BlobStatus status = getStatus((ManagedBlob) content);
        assertFalse(status.isDownloadable());
        assertEquals(StorageClass.Glacier.toString(), status.getStorageClass());
    }

}
