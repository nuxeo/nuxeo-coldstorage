package org.nuxeo.coldstorage.operations;

import static javax.servlet.http.HttpServletResponse.SC_CONFLICT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.List;

import org.junit.Test;
import org.nuxeo.coldstorage.S3ColdStorageFeature;
import org.nuxeo.coldstorage.S3TestHelper;
import org.nuxeo.coldstorage.helpers.ColdStorageHelper;
import org.nuxeo.coldstorage.thumbnail.DummyThumbnailFactory;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.OperationException;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.platform.ec.notification.NotificationConstants;
import org.nuxeo.runtime.test.runner.Features;

@Features(S3ColdStorageFeature.class)
public class S3RestoreFromColdStorageTest extends AbstractTestRestoreFromColdStorageOperation {

    protected S3TestHelper s3TestHelper = S3TestHelper.getInstance();

    @Test
    public void shouldBeRestoredByRetrieve() throws IOException, OperationException {
        DocumentModel documentModel = createFileDocument(session, true);
        // first make the move to cold storage
        moveContentToColdStorage(session, documentModel);
        // Mock AWS Lifecycle rule
        s3TestHelper.moveBlobContentToGlacier(session.getDocument(documentModel.getRef()));
        try (OperationContext context = new OperationContext(session)) {
            context.setInput(documentModel);
            DocumentModel updatedDocument = (DocumentModel) automationService.run(context, RestoreFromColdStorage.ID);

            // check document
            assertTrue(updatedDocument.hasFacet(ColdStorageHelper.COLD_STORAGE_FACET_NAME));

            // check blobs
            Blob coldStorageContent = (Blob) updatedDocument.getPropertyValue(
                    ColdStorageHelper.COLD_STORAGE_CONTENT_PROPERTY);
            Blob fileContent = (Blob) updatedDocument.getPropertyValue(ColdStorageHelper.FILE_CONTENT_PROPERTY);
            assertEquals(DummyThumbnailFactory.DUMMY_THUMBNAIL_CONTENT, fileContent.getString());
            assertEquals(FILE_CONTENT, coldStorageContent.getString());

            // should being retrieved
            assertEquals(Boolean.TRUE,
                    updatedDocument.getPropertyValue(ColdStorageHelper.COLD_STORAGE_BEING_RETRIEVED_PROPERTY));

            // check the restore notification
            String username = NotificationConstants.USER_PREFIX + session.getPrincipal().getName();
            List<String> subscriptions = notificationManager.getSubscriptionsForUserOnDocument(username,
                    updatedDocument);
            assertTrue(subscriptions.contains(ColdStorageHelper.COLD_STORAGE_CONTENT_RESTORED_NOTIFICATION_NAME));
            assertFalse(subscriptions.contains(ColdStorageHelper.COLD_STORAGE_CONTENT_AVAILABLE_NOTIFICATION_NAME));
        }
    }

    @Test
    public void shouldFailBeingRestored() throws IOException, OperationException {
        DocumentModel documentModel = createFileDocument(session, true);

        // first make the move to cold storage
        moveContentToColdStorage(session, documentModel);
        restoreContentFromColdStorage(documentModel);
        // request a retrieval for a second time
        try {
            restoreContentFromColdStorage(documentModel);
            fail("Should fail because the cold storage content is being restored.");
        } catch (NuxeoException e) {
            assertEquals(SC_CONFLICT, e.getStatusCode());
        }
    }

}
