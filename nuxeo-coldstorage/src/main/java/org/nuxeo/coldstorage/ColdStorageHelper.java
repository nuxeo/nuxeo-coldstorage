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

import jakarta.annotation.Nonnull;

import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.blob.BlobManager;
import org.nuxeo.ecm.core.blob.BlobProvider;
import org.nuxeo.ecm.core.blob.BlobStatus;
import org.nuxeo.ecm.core.blob.ManagedBlob;
import org.nuxeo.runtime.api.Framework;

import software.amazon.awssdk.services.s3.model.StorageClass;

/**
 * @since 2021.0.0
 */
public class ColdStorageHelper {

    public static BlobStatus getBlobStatus(@Nonnull DocumentModel doc) {
        Blob coldContent = (Blob) doc.getPropertyValue(COLD_STORAGE_CONTENT_PROPERTY);
        if (coldContent == null) {
            throw new NuxeoException("Document: %s has no cold storage content.".formatted(doc.getId()));
        }
        return getStatus((ManagedBlob) coldContent);
    }

    public static BlobStatus getStatus(@Nonnull ManagedBlob blob) {
        try {
            BlobProvider provider = Framework.getService(BlobManager.class).getBlobProvider(blob);
            return provider.getStatus(blob);
        } catch (IOException e) {
            throw new NuxeoException("Unable to get blob status for blob: %s".formatted(blob), e);
        }
    }

    public static boolean isDownloadable(@Nonnull BlobStatus blobStatus) {
        return !isInColdStorage(blobStatus) || blobStatus.isDownloadable();
    }

    public static boolean isInColdStorage(@Nonnull ManagedBlob blob) {
        return isInColdStorage(getStatus(blob));
    }

    public static boolean isInColdStorage(@Nonnull BlobStatus status) {
        return StorageClass.GLACIER.toString().equals(status.getStorageClass());
    }

}
