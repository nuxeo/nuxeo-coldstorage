/*
 * (C) Copyright 2020 Nuxeo (http://nuxeo.com/) and others.
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
 *     Salem Aouana
 *     Nuno Cunha <ncunha@nuxeo.com>
 *
 */

package org.nuxeo.coldstorage.operations;

import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static javax.servlet.http.HttpServletResponse.SC_PRECONDITION_FAILED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_THUMBNAIL_PREVIEW_REQUIRED_PROPERTY_NAME;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;

import org.junit.Test;
import org.nuxeo.coldstorage.ColdStorageConstants;
import org.nuxeo.coldstorage.DummyColdStorageFeature;
import org.nuxeo.ecm.automation.OperationException;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.VersioningOption;
import org.nuxeo.ecm.core.api.security.ACE;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.api.thumbnail.ThumbnailService;
import org.nuxeo.ecm.core.api.versioning.VersioningService;
import org.nuxeo.ecm.platform.thumbnail.ThumbnailConstants;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.WithFrameworkProperty;
import org.nuxeo.runtime.transaction.TransactionHelper;

/**
 * @since 2021.0.0
 */
@Features(DummyColdStorageFeature.class)
public class MoveToColdStorageTest extends AbstractTestColdStorageOperation {
    @Inject
    protected CoreSession session;

    @Inject
    protected ThumbnailService thumbnailService;

    @Test
    public void shouldFailWithoutRightPermissions() throws OperationException, IOException {
        ACE[] aces = { new ACE("john", SecurityConstants.READ, true) };
        DocumentModel documentModel = createFileDocument(session, true, aces);

        try {
            CoreSession userSession = CoreInstance.getCoreSession(documentModel.getRepositoryName(), "john");
            moveContentToColdStorage(userSession, documentModel);
            fail("Should fail because the user does not have permissions to move document to cold storage");
        } catch (NuxeoException e) {
            assertEquals(SC_FORBIDDEN, e.getStatusCode());
        }
    }

    @Test
    public void shouldMoveToColdStorage() throws OperationException, IOException {
        // with regular user with "WriteColdStorage" permission
        ACE[] aces = { new ACE("john", SecurityConstants.READ, true), //
                new ACE("john", SecurityConstants.WRITE, true), //
                new ACE("john", SecurityConstants.WRITE_COLD_STORAGE, true) };
        DocumentModel documentModel = createFileDocument(session, true, aces);

        CoreSession userSession = CoreInstance.getCoreSession(documentModel.getRepositoryName(), "john");
        moveContentToColdStorage(userSession, documentModel);

        // with Administrator
        documentModel = createFileDocument(session, true);
        moveContentToColdStorage(session, documentModel);
    }

    @Test
    public void shouldMoveDocsToColdStorage() throws OperationException, IOException {
        // with regular user with "WriteColdStorage" permission
        ACE[] aces = { new ACE("linda", SecurityConstants.READ, true), //
                new ACE("linda", SecurityConstants.WRITE, true), //
                new ACE("linda", SecurityConstants.WRITE_COLD_STORAGE, true) };

        List<DocumentModel> documents = List.of(createFileDocument(session, "MyFile1", true, aces), //
                createFileDocument(session, "MyFile2", true, aces), //
                createFileDocument(session, "MyFile3", true, aces));

        CoreSession userSession = CoreInstance.getCoreSession(session.getRepositoryName(), "linda");
        moveContentToColdStorage(userSession, documents);

        // with Administrator
        documents = List.of(createFileDocument(session, "MyFile4", true), //
                createFileDocument(session, "MyFile5", true), //
                createFileDocument(session, "MyFile6", true));

        moveContentToColdStorage(session, documents);
    }

    @Test
    public void shouldFailMoveDocsToColdStorage() throws OperationException, IOException {
        DocumentModel underLegalHold = createFileDocument(session, "MyFile1", true);
        session.makeRecord(underLegalHold.getRef());
        session.setLegalHold(underLegalHold.getRef(), true, "any comment");
        List<DocumentModel> documents = List.of(createFileDocument(session, "MyFile2", true), //
                underLegalHold, //
                createFileDocument(session, "MyFile3", true));
        transactionalFeature.nextTransaction();
        try {
            moveContentToColdStorage(session, documents);
            fail("Should fail because the document under legal hold should prevent from completing the operation");
        } catch (NuxeoException e) {
            // expected, let's check none were sent to cold storage
            TransactionHelper.commitOrRollbackTransaction();
            TransactionHelper.startTransaction();
            documents.stream().forEach(doc -> {
                doc = session.getDocument(doc.getRef());
                assertFalse(session.getDocument(doc.getRef()).hasFacet(ColdStorageConstants.COLD_STORAGE_FACET_NAME));
            });
        }
    }

    @Test
    public void shouldNotFailMoveAlreadyInColdStorage() throws OperationException, IOException {
        DocumentModel documentModel = createFileDocument(session, true);
        // make a move
        moveContentToColdStorage(session, documentModel);
        // make a 2nd move
        moveContentToColdStorage(session, documentModel);
    }

    @Test
    @WithFrameworkProperty(name = COLD_STORAGE_THUMBNAIL_PREVIEW_REQUIRED_PROPERTY_NAME, value = "true")
    public void shouldFailMoveToColdStorageNoThumbnail() throws OperationException, IOException {
        DocumentModel documentModel = createFileDocument(session, true);
        try {
            moveContentToColdStorage(session, documentModel);
            fail("Should fail because there is no suitable thumbnail to replace document preview");
        } catch (NuxeoException e) {
            assertEquals(SC_PRECONDITION_FAILED, e.getStatusCode());
        }
        // let's fake the document never had the thumbnail facet
        documentModel.removeFacet(ThumbnailConstants.THUMBNAIL_FACET);
        documentModel = session.saveDocument(documentModel);
        try {
            moveContentToColdStorage(session, documentModel);
            fail("Should fail because there is no suitable thumbnail to replace document preview");
        } catch (NuxeoException e) {
            assertEquals(SC_PRECONDITION_FAILED, e.getStatusCode());
        }
    }

    @Test
    @WithFrameworkProperty(name = COLD_STORAGE_THUMBNAIL_PREVIEW_REQUIRED_PROPERTY_NAME, value = "false")
    public void shouldNotFailMoveToColdStorageNoThumbnail() throws OperationException, IOException {
        DocumentModel documentModel = createFileDocument(session, true);
        moveContentToColdStorage(session, documentModel);
    }

    @Test
    public void shouldFailMoveToColdStorageNoContent() throws OperationException, IOException {
        DocumentModel documentModel = createFileDocument(session, false);
        try {
            moveContentToColdStorage(session, documentModel);
            fail("Should fail because there is no main content associated with the document");
        } catch (NuxeoException e) {
            assertEquals(SC_NOT_FOUND, e.getStatusCode());
        }
    }

    @Test
    @Deploy("org.nuxeo.coldstorage.test:OSGI-INF/test-thumbnail-recomputation-contrib.xml")
    @Deploy("org.nuxeo.ecm.platform.thumbnail:OSGI-INF/thumbnail-listener-contrib.xml")
    @Deploy("org.nuxeo.ecm.platform.thumbnail:OSGI-INF/thumbnail-core-types-contrib.xml")
    @Deploy("org.nuxeo.ecm.platform.types")
    public void shouldNotRecomputeThumbnail() throws IOException, OperationException {
        DocumentModel documentModel = createFileDocument(session, true);
        Blob originalThumbnail = thumbnailService.getThumbnail(documentModel, session);
        assertNotNull(originalThumbnail);

        moveContentToColdStorage(session, documentModel);

        transactionalFeature.nextTransaction();
        documentModel.refresh();

        Blob thumbnailUpdateOne = thumbnailService.getThumbnail(documentModel, session);
        assertNotNull(thumbnailUpdateOne);
        assertEquals(originalThumbnail.getString(), thumbnailUpdateOne.getString());
    }

    @Test
    public void shouldNotReplaceColdStorageContent() throws IOException, OperationException {
        DocumentModel documentModel = createFileDocument(session, true);
        moveContentToColdStorage(session, documentModel);

        transactionalFeature.nextTransaction();
        documentModel.refresh();

        try {
            Blob BloThumbnail = thumbnailService.getThumbnail(documentModel, session);
            documentModel.setPropertyValue(ColdStorageConstants.COLD_STORAGE_CONTENT_PROPERTY,
                    (Serializable) BloThumbnail);
            session.saveDocument(documentModel);
            fail("Should fail because the document content can't be updated");
        } catch (NuxeoException e) {
            assertEquals(SC_FORBIDDEN, e.getStatusCode());
        }
    }

    @Test
    public void shouldNotDeleteColdStorageFacet() throws IOException, OperationException {
        DocumentModel documentModel = createFileDocument(session, true);
        moveContentToColdStorage(session, documentModel);

        transactionalFeature.nextTransaction();
        documentModel.refresh();

        try {
            documentModel.removeFacet(ColdStorageConstants.COLD_STORAGE_FACET_NAME);
            session.saveDocument(documentModel);
            fail("Should fail because the document content can't be updated");
        } catch (NuxeoException e) {
            assertEquals(SC_FORBIDDEN, e.getStatusCode());
        }
    }

    @Test
    public void shouldDeduplicationColdStorageContent() throws IOException, OperationException {
        List<DocumentModel> documents = List.of(session.createDocumentModel("/", "MyFile1", "File"), //
                session.createDocumentModel("/", "MyFile010", "File"), //
                session.createDocumentModel("/", "MyFile700", "File"), //
                session.createDocumentModel("/", "MyFile800", "File"));

        Blob blob = Blobs.createBlob(FILE_CONTENT);
        blob.setDigest(UUID.randomUUID().toString());
        for (DocumentModel documentModel : documents) {
            documentModel.setPropertyValue(ColdStorageConstants.FILE_CONTENT_PROPERTY, (Serializable) blob);
            session.createDocument(documentModel);
            session.saveDocument(documentModel);
        }

        transactionalFeature.nextTransaction();

        // Check if we have the expected duplicated blobs
        String query = String.format("SELECT * FROM Document WHERE file:content/digest = '%s'", blob.getDigest());
        documents = session.query(query);
        assertEquals(4, documents.size());

        DocumentModel documentModel = documents.get(0);

        // first make the move to cold storage
        documentModel = moveContentToColdStorage(session, documentModel);
        coreFeature.waitForAsyncCompletion();

        // No duplicated documents after moving the main document to ColdStorage
        documents = session.query(query);
        assertEquals(0, documents.size());

        // Check the ColdStorage content for each duplicated document
        query = String.format("SELECT * FROM Document WHERE coldstorage:coldContent/digest = '%s'", blob.getDigest());
        documents = session.query(query);
        assertNotEquals(0, documents.size());
        checkMoveContents(documentModel, documents);
    }

    @Test
    public void shouldMovedAllVersionsToColdStorage() throws IOException, OperationException {
        List<String> docTitles = List.of("AAA", "BBB", "CCC", "DDD");
        DocumentModel documentModel = createFileDocument(session, true);
        String blobDigest = ((Blob) documentModel.getPropertyValue(
                ColdStorageConstants.FILE_CONTENT_PROPERTY)).getDigest();

        // Create 4 versions
        for (String docTitle : docTitles) {
            documentModel.setPropertyValue("dc:title", docTitle);
            documentModel.putContextData(VersioningService.VERSIONING_OPTION, VersioningOption.valueOf("MINOR"));
            documentModel = session.saveDocument(documentModel);
            transactionalFeature.nextTransaction();
            documentModel.refresh();
        }

        assertEquals("0.4", documentModel.getVersionLabel());
        transactionalFeature.nextTransaction();

        // Check if we have the expected number of versions
        String query = String.format(
                "SELECT * FROM Document WHERE  ecm:isVersion = 1 AND "
                        + "ecm:versionVersionableId = '%s' AND file:content/digest = '%s'",
                documentModel.getId(), blobDigest);
        List<DocumentModel> documents = session.query(query);
        assertEquals(4, documents.size());

        // first make the move to cold storage
        documentModel = moveContentToColdStorage(session, documentModel);

        coreFeature.waitForAsyncCompletion();

        // Check if all the versions have been moved to ColdStorage
        documents = session.query(query);
        assertEquals(0, documents.size());

        // Check the ColdStorage content for each version
        query = String.format("SELECT * FROM Document WHERE  ecm:isVersion = 1 AND ecm:versionVersionableId = '%s' "
                + "AND coldstorage:coldContent/digest = '%s'", documentModel.getId(), blobDigest);
        documents = session.query(query);
        assertNotEquals(0, documents.size());
        checkMoveContents(documentModel, documents);
    }

    public void checkMoveContents(DocumentModel documentModel, List<DocumentModel> documents) throws IOException {
        Blob blob = (Blob) documentModel.getPropertyValue(ColdStorageConstants.COLD_STORAGE_CONTENT_PROPERTY);
        Blob originalThumbnail = thumbnailService.getThumbnail(documentModel, session);
        for (DocumentModel docModel : documents) {
            DocumentModel document = session.getDocument(docModel.getRef());
            Blob coldContent = (Blob) document.getPropertyValue(ColdStorageConstants.COLD_STORAGE_CONTENT_PROPERTY);
            Blob thumbnailUpdateOne = thumbnailService.getThumbnail(document, session);
            assertEquals(blob.getDigest(), coldContent.getDigest());
            assertTrue(document.hasFacet(ColdStorageConstants.COLD_STORAGE_FACET_NAME));

            // Check the Thumbnail value
            assertEquals(originalThumbnail.getString(), thumbnailUpdateOne.getString());

            // Check ColdStorage content
            try {
                coldContent.getString();
                fail("Cold content should not be available");
            } catch (IOException e) {
                // expected
            }
        }
    }
}
