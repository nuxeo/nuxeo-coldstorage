package org.nuxeo.coldstorage.operations;

import static javax.servlet.http.HttpServletResponse.SC_CONFLICT;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.nuxeo.ecm.core.bulk.message.BulkStatus.State.COMPLETED;

import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;

import org.junit.Ignore;
import org.junit.Test;
import org.nuxeo.coldstorage.helpers.ColdStorageHelper;
import org.nuxeo.ecm.automation.OperationException;
import org.nuxeo.ecm.automation.core.operations.coldstorage.DeduplicationColdContentActions;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CloseableCoreSession;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.security.ACE;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.bulk.BulkService;
import org.nuxeo.ecm.core.bulk.message.BulkCommand;
import org.nuxeo.ecm.core.bulk.message.BulkStatus;
import org.nuxeo.runtime.api.Framework;

/**
 * @since 11.0
 */
public abstract class AbstractTestMoveColdStorageOperation extends AbstractTestColdStorageOperation {

    @Inject
    protected CoreSession session;

    @Test
    public void shouldFailWithoutRightPermissions() throws OperationException, IOException {
        ACE[] aces = { new ACE("john", SecurityConstants.READ, true) };
        DocumentModel documentModel = createFileDocument(session, true, aces);

        try (CloseableCoreSession userSession = coreFeature.openCoreSession("john")) {
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
                new ACE("john", ColdStorageHelper.WRITE_COLD_STORAGE, true) };
        DocumentModel documentModel = createFileDocument(session, true, aces);
        try (CloseableCoreSession userSession = CoreInstance.openCoreSessionSystem(documentModel.getRepositoryName(),
                "john")) {
            moveContentToColdStorage(userSession, documentModel);
        }
        // with Administrator
        documentModel = createFileDocument(session, true);
        moveContentToColdStorage(session, documentModel);
    }

    @Test
    public void shouldMoveDocsToColdStorage() throws OperationException, IOException {
        // with regular user with "WriteColdStorage" permission
        ACE[] aces = { new ACE("linda", SecurityConstants.READ, true), //
                new ACE("linda", SecurityConstants.WRITE, true), //
                new ACE("linda", ColdStorageHelper.WRITE_COLD_STORAGE, true) };

        List<DocumentModel> documents = Arrays.asList(createFileDocument(session, true, aces), //
                createFileDocument(session, true, aces), //
                createFileDocument(session, true, aces));

        try (CloseableCoreSession userSession = CoreInstance.openCoreSessionSystem(session.getRepositoryName(),
                "linda")) {
            moveContentToColdStorage(userSession, documents);
        }

        // with Administrator
        documents = Arrays.asList(createFileDocument(session, true), //
                createFileDocument(session, true), //
                createFileDocument(session, true));

        moveContentToColdStorage(session, documents);
    }

    @Test
    public void shouldFailMoveAlreadyInColdStorage() throws OperationException, IOException {
        DocumentModel documentModel = createFileDocument(session, true);
        // make a move
        moveContentToColdStorage(session, documentModel);
        try {
            // try to make a second move
            moveContentToColdStorage(session, documentModel);
            fail("Should fail because the content is already in cold storage");
        } catch (NuxeoException e) {
            assertEquals(SC_CONFLICT, e.getStatusCode());
        }
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

    // WIP
    @Test
    public void shouldDeduplicationColdStorageContent() throws IOException {

        List<DocumentModel> documents = Arrays.asList(session.createDocumentModel("/", "MyFile1", "File"), //
                session.createDocumentModel("/", "MyFile010", "File"), //
                session.createDocumentModel("/", "MyFile700", "File"), //
                session.createDocumentModel("/", "MyFile800", "File"));

        Blob blob = Blobs.createBlob(FILE_CONTENT);
        blob.setDigest(UUID.randomUUID().toString());
        for (DocumentModel documentModel : documents) {
            documentModel.setPropertyValue(ColdStorageHelper.FILE_CONTENT_PROPERTY, (Serializable) blob);
            documentModel = session.createDocument(documentModel);
        }

        transactionalFeature.nextTransaction();


        // As we have the same main blob we rely on the digest, which the same for the blob
        // To check this assumption with FG and mainly since HF42 and S3 blob rework.

        // Here it's in the test content but we can submit this computation in the Move operation. To only take
        // the document with the same main blob digest
        String query = String.format("SELECT * FROM Document WHERE file:content/digest = '%s'", blob.getDigest());
        documents = session.query(query);
        assertEquals(4, documents.size());

        // see if we can use param to pass the required (coldstorage:coldcontent and main content to avoid reloading
        // them)
        BulkService bulkService = Framework.getService(BulkService.class);
        BulkCommand.Builder builder = new BulkCommand.Builder(DeduplicationColdContentActions.ACTION_NAME, query) //
                                                                                                                 .user(SecurityConstants.SYSTEM_USERNAME) //
                                                                                                                 .repository(
                                                                                                                         session.getRepositoryName())
                                                                                                                 .param("docId",
                                                                                                                         "1222"); //

        BulkCommand bulkCommand = builder.build();
        String commandId = bulkService.submit(bulkCommand);
        transactionalFeature.nextTransaction();
        BulkStatus status = bulkService.getStatus(commandId);
        assertNotNull(status);
        assertEquals(COMPLETED, status.getState());

        for (DocumentModel documentModel : documents) {
            DocumentModel document = session.getDocument(documentModel.getRef());
            Blob mainContent = (Blob) document.getPropertyValue(ColdStorageHelper.COLD_STORAGE_CONTENT_PROPERTY);
            assertEquals(blob.getDigest(), mainContent.getDigest());
            assertTrue(document.hasFacet(ColdStorageHelper.COLD_STORAGE_FACET_NAME));

            // FIXME add Thumbnail content && cold storage content check
        }
    }
}
