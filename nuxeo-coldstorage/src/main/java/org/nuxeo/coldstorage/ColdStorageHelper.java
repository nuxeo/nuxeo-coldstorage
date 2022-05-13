/*
 * (C) Copyright 2022 Nuxeo (http://nuxeo.com/) and others.
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
 *     Guillaume Renard<grenard@nuxeo.com>
 */
package org.nuxeo.coldstorage;

import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_PROPERTY;

import java.io.IOException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.blob.BlobManager;
import org.nuxeo.ecm.core.blob.BlobProvider;
import org.nuxeo.ecm.core.blob.BlobStatus;
import org.nuxeo.ecm.core.blob.ManagedBlob;
import org.nuxeo.runtime.api.Framework;

/**
 * @since 2021.0.0
 */
public class ColdStorageHelper {

    private static final Logger log = LogManager.getLogger(ColdStorageHelper.class);

    public static BlobStatus getBlobStatus(DocumentModel doc) {
        Blob coldContent = (Blob) doc.getPropertyValue(COLD_STORAGE_CONTENT_PROPERTY);
        return getStatus((ManagedBlob) coldContent);
    }

    public static BlobStatus getStatus(ManagedBlob blob) {
        try {
            BlobProvider provider = Framework.getService(BlobManager.class).getBlobProvider(blob);
            return provider.getStatus(blob);
        } catch (IOException e) {
            log.error("Unable to get blob status for blob: {}", blob, e);
            return null;
        }
    }

    public static boolean isDownloadable(BlobStatus blobStatus) {
        // XXX for now, only use case where storage class != null is cold storage
        // to be rewritten when more storage class supported
        boolean downloadable = blobStatus.getStorageClass() == null ? blobStatus.isDownloadable()
                : (blobStatus.isDownloadable() && blobStatus.getDownloadableUntil() != null);
        return downloadable;
    }

    public static boolean isInColdStorage(ManagedBlob blob) {
        return isInColdStorage(getStatus(blob));
    }

    public static boolean isInColdStorage(BlobStatus status) {
        // XXX if the storage class != null, we assume it is already in cold storage.
        return status.getStorageClass() != null;
    }
}
