/*
 * (C) Copyright 2026 Nuxeo (http://nuxeo.com/) and others.
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
 *     Guillaume Renard
 */
package org.nuxeo.coldstorage;

import static com.amazonaws.services.s3.model.StorageClass.Glacier;
import static com.amazonaws.services.s3.model.StorageClass.Standard;

import java.io.IOException;

import org.nuxeo.ecm.core.DummyBlobProvider;
import org.nuxeo.ecm.core.blob.BlobStatus;
import org.nuxeo.ecm.core.blob.BlobUpdateContext;

/**
 * Extends {@link DummyBlobProvider} to mock S3 storage classes and restore behavior.
 *
 * @since 2025.1
 */
public class MockS3BlobProvider extends DummyBlobProvider {

    @Override
    public void updateBlob(BlobUpdateContext blobUpdateContext) throws IOException {
        if (blobUpdateContext != null) {
            BlobStatus status = blobsStatus.getOrDefault(blobUpdateContext.key, new BlobStatus());
            if (blobUpdateContext.coldStorageClass != null) {
                status.withStorageClass(
                        blobUpdateContext.coldStorageClass.inColdStorage ? Glacier.toString() : Standard.toString());
            }
            if (blobUpdateContext.restoreForDuration != null) {
                if (Standard.toString().equals(status.getStorageClass())) {
                    throw new IllegalStateException("Cannot restore a blob with default storage class");
                }
            }
            this.blobsStatus.put(blobUpdateContext.key, status);
            super.updateBlob(blobUpdateContext);
        }
    }
}
