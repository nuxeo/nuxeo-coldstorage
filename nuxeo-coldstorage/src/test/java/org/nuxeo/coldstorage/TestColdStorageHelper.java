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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static software.amazon.awssdk.services.s3.model.StorageClass.GLACIER;
import static software.amazon.awssdk.services.s3.model.StorageClass.STANDARD;

import java.io.Serializable;

import jakarta.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.coldstorage.service.ColdStorageService;
import org.nuxeo.ecm.blob.s3.S3BlobProviderFeature;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.blob.BlobStatus;
import org.nuxeo.ecm.core.blob.ManagedBlob;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;

/**
 * @since 2025.1
 */
@RunWith(FeaturesRunner.class)
@Features({ ColdStorageFeature.class, S3BlobProviderFeature.class })
public class TestColdStorageHelper {

    @Inject
    protected CoreSession session;

    @Inject
    protected ColdStorageService service;

    @Test
    public void testDownloadableBeforeAndAfterMove() {
        var doc = session.createDocumentModel("/", "dummy", "File");
        doc.setPropertyValue("file:content", (Serializable) Blobs.createBlob("dummy"));
        doc = session.createDocument(doc);

        var status = ColdStorageHelper.getStatus((ManagedBlob) doc.getPropertyValue("file:content"));
        assertNotNull(status);
        assertFalse(ColdStorageHelper.isInColdStorage(status));
        assertTrue(ColdStorageHelper.isDownloadable(status));

        service.moveToColdStorage(session, doc.getRef());
        doc = session.getDocument(doc.getRef());
        status = ColdStorageHelper.getBlobStatus(doc);
        assertTrue(ColdStorageHelper.isInColdStorage(status));
        assertFalse(ColdStorageHelper.isDownloadable(status));
    }

    @Test
    public void testStandardClassBlobIsDownloadable() {
        var status = new BlobStatus().withStorageClass(STANDARD.toString());
        assertFalse(ColdStorageHelper.isInColdStorage(status));
        assertTrue(ColdStorageHelper.isDownloadable(status));
    }

    @Test
    public void testRestoredGlacierBlobIsDownloadable() {
        var status = new BlobStatus().withStorageClass(GLACIER.toString()).withDownloadable(true);
        assertTrue(ColdStorageHelper.isInColdStorage(status));
        assertTrue(ColdStorageHelper.isDownloadable(status));
    }
}
