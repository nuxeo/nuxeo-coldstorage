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

package org.nuxeo.coldstorage.operations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.nuxeo.ecm.core.DummyThumbnailFactory.DUMMY_THUMBNAIL_CONTENT;

import java.io.IOException;

import org.nuxeo.coldstorage.S3ColdStorageFeature;
import org.nuxeo.coldstorage.S3TestHelper;
import org.nuxeo.coldstorage.helpers.ColdStorageHelper;
import org.nuxeo.ecm.automation.OperationException;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.runtime.test.runner.Features;

@Features(S3ColdStorageFeature.class)
public class S3RestoreFromColdStorageTest extends AbstractTestRestoreFromColdStorageOperation {

    protected S3TestHelper s3TestHelper = S3TestHelper.getInstance();

    @Override
    protected void moveContentToColdStorage(CoreSession session, DocumentModel documentModel)
            throws IOException, OperationException {
        super.moveContentToColdStorage(session, documentModel);
        // Mock AWS Lifecycle rule
        s3TestHelper.moveBlobContentToGlacier(session.getDocument(documentModel.getRef()));
    }

    @Override
    protected void checkRestoreContent(DocumentModel documentModel) throws IOException {
        // The storage class for the main blob is Glacier, the restore should be done by retrieve
        // re-check document
        assertTrue(documentModel.hasFacet(ColdStorageHelper.COLD_STORAGE_FACET_NAME));

        // should being restored by retrieve
        assertEquals(Boolean.TRUE,
                documentModel.getPropertyValue(ColdStorageHelper.COLD_STORAGE_BEING_RETRIEVED_PROPERTY));
        assertEquals(Boolean.TRUE,
                documentModel.getPropertyValue(ColdStorageHelper.COLD_STORAGE_TO_BE_RESTORED_PROPERTY));

        // check main blobs
        Blob fileContent = (Blob) documentModel.getPropertyValue(ColdStorageHelper.FILE_CONTENT_PROPERTY);
        assertEquals(DUMMY_THUMBNAIL_CONTENT, fileContent.getString());
    }
}
