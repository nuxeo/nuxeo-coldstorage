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

import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.junit.Test;
import org.nuxeo.coldstorage.ColdStorageConstants;
import org.nuxeo.coldstorage.ColdStorageFeature;
import org.nuxeo.common.utils.FileUtils;
import org.nuxeo.ecm.automation.OperationException;
import org.nuxeo.ecm.blob.s3.S3BlobProviderFeature;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.platform.picture.api.ImagingDocumentConstants;
import org.nuxeo.ecm.platform.rendition.Rendition;
import org.nuxeo.ecm.platform.rendition.service.RenditionService;
import org.nuxeo.ecm.platform.video.VideoConstants;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;

/**
 * @since 2021.0.0
 */
@Features({ ColdStorageFeature.class, S3BlobProviderFeature.class})
public class TestColdStorageRendition extends AbstractTestColdStorageOperation {

    @Inject
    protected RenditionService renditionService;

    @Test
    public void shouldSelectDefaultRendition() throws IOException, OperationException {
        DocumentModel document = createFileDocument(session, true);

        transactionalFeature.nextTransaction();

        // Retrieve the expected rendition
        Rendition rendition = renditionService.getRendition(document, "thumbnail");
        Blob expectedBlob = rendition.getBlob();

        // Check if the FILE_CONTENT_PROPERTY and the rendition are NOT equal
        Blob mainContent = (Blob) document.getPropertyValue(ColdStorageConstants.FILE_CONTENT_PROPERTY);
        assertNotEquals(expectedBlob.getDigest(), mainContent.getDigest());

        document = moveContentToColdStorage(session, document);

        // Check if the FILE_CONTENT_PROPERTY and the rendition are equal
        mainContent = (Blob) document.getPropertyValue(ColdStorageConstants.FILE_CONTENT_PROPERTY);
        assertEquals(expectedBlob.getString(), mainContent.getString());
    }

    @Test
    public void shouldSelectSmallRendition() throws IOException, OperationException {
        DocumentModel document = createCustomDocument(session, "images/devops.png", "devops",
                ImagingDocumentConstants.PICTURE_TYPE_NAME, "image/png");

        transactionalFeature.nextTransaction();

        // Retrieve the expected rendition blob
        Rendition rendition = renditionService.getRendition(document, "Small");
        Blob expectedBlob = rendition.getBlob();

        // Check if the FILE_CONTENT_PROPERTY and the rendition are NOT equal
        Blob mainContent = (Blob) document.getPropertyValue(ColdStorageConstants.FILE_CONTENT_PROPERTY);
        assertNotEquals(expectedBlob.getDigest(), mainContent.getDigest());

        document = moveContentToColdStorage(session, document);

        // Check if the FILE_CONTENT_PROPERTY and the rendition are equal
        mainContent = (Blob) document.getPropertyValue(ColdStorageConstants.FILE_CONTENT_PROPERTY);
        assertEquals(expectedBlob.getDigest(), mainContent.getDigest());
    }

    @Test
    @Deploy("org.nuxeo.coldstorage.test:OSGI-INF/test-coldstorage-rendition-contrib.xml")
    public void shouldFailRenditionNotExist() throws IOException, OperationException {
        DocumentModel document = createCustomDocument(session, "images/devops.png", "devops",
                ImagingDocumentConstants.PICTURE_TYPE_NAME, "image/png");

        transactionalFeature.nextTransaction();

        try {
            moveContentToColdStorage(session, document);
        } catch (NuxeoException e) {
            assertEquals(SC_NOT_FOUND, e.getStatusCode());
        }
    }

    @Test
    public void shouldSelectDefaultVideoRendition() throws IOException, OperationException {
        DocumentModel document = createCustomDocument(session, "images/sample.mpg", "sample.mpg",
                VideoConstants.VIDEO_TYPE, "video/mp4");

        transactionalFeature.nextTransaction();

        // Retrieve the expected rendition
        Rendition rendition = renditionService.getRendition(document, "MP4 480p");
        Blob expectedBlob = rendition.getBlob();

        // Check if the FILE_CONTENT_PROPERTY and the rendition are NOT equal
        Blob mainContent = (Blob) document.getPropertyValue(ColdStorageConstants.FILE_CONTENT_PROPERTY);
        assertNotEquals(expectedBlob.getDigest(), mainContent.getDigest());

        document = moveContentToColdStorage(session, document);

        // Check if the FILE_CONTENT_PROPERTY and the rendition are equal
        mainContent = (Blob) document.getPropertyValue(ColdStorageConstants.FILE_CONTENT_PROPERTY);
        assertEquals(expectedBlob.getDigest(), mainContent.getDigest());
    }

    private DocumentModel createCustomDocument(CoreSession session, String path, String name, String typeName,
            String mimeType) throws IOException {
        DocumentModel document = session.createDocumentModel("/", name, typeName);
        Blob blob = Blobs.createBlob(FileUtils.getResourceFileFromContext(path), mimeType,
                StandardCharsets.UTF_8.name(), name);
        document.setPropertyValue(ColdStorageConstants.FILE_CONTENT_PROPERTY, (Serializable) blob);
        document = session.createDocument(document);
        session.save();
        return document;
    }
    @Override
    protected void checkMoveContent(List<DocumentModel> expectedDocs, List<DocumentModel> actualDocs) {
        assertEquals(expectedDocs.size(), actualDocs.size());
        List<String> expectedDocIds = expectedDocs.stream().map(DocumentModel::getId).collect(Collectors.toList());
        for (DocumentModel updatedDoc : actualDocs) {
            // check document
            assertTrue(expectedDocIds.contains(updatedDoc.getId()));
            assertTrue(updatedDoc.hasFacet(ColdStorageConstants.COLD_STORAGE_FACET_NAME));
        }
    }

}
