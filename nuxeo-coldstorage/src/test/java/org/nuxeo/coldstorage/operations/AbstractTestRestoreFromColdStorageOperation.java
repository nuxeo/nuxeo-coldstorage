package org.nuxeo.coldstorage.operations;

import static javax.servlet.http.HttpServletResponse.SC_CONFLICT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.List;

import javax.inject.Inject;

import org.junit.Test;
import org.nuxeo.coldstorage.helpers.ColdStorageHelper;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.OperationException;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.platform.ec.notification.NotificationConstants;
import org.nuxeo.ecm.platform.notification.api.NotificationManager;

public abstract class AbstractTestRestoreFromColdStorageOperation extends AbstractTestColdStorageOperation {

    @Inject
    protected CoreSession session;

    @Inject
    protected NotificationManager notificationManager;

    @Test
    public void shouldBeRestoredInstantly() throws IOException, OperationException {
        DocumentModel documentModel = createFileDocument(session, true);
        // first make the move to cold storage
        moveContentToColdStorage(session, documentModel);
        session.saveDocument(documentModel);
        restoreContentFromColdStorage(documentModel);
    }

    @Test
    public void shouldFailRestoreNoColdStorageContent() throws OperationException, IOException {
        DocumentModel documentModel = createFileDocument(session, true);
        try {
            // request a restore from the cold storage
            restoreContentFromColdStorage(documentModel);
            fail("Should fail because there no cold storage content associated to this document.");
        } catch (NuxeoException e) {
            assertEquals(SC_CONFLICT, e.getStatusCode());
        }
    }

    protected void restoreContentFromColdStorage(DocumentModel documentModel) throws OperationException, IOException {
        try (OperationContext context = new OperationContext(session)) {
            context.setInput(documentModel);
            DocumentModel updatedDocument = (DocumentModel) automationService.run(context, RestoreFromColdStorage.ID);

            // check document
            assertEquals(documentModel.getRef(), updatedDocument.getRef());
            assertFalse(updatedDocument.hasFacet(ColdStorageHelper.COLD_STORAGE_FACET_NAME));

            // check blobs
            Blob fileContent = (Blob) updatedDocument.getPropertyValue(ColdStorageHelper.FILE_CONTENT_PROPERTY);
            assertEquals(FILE_CONTENT, fileContent.getString());

            // check the restore notification
            String username = NotificationConstants.USER_PREFIX + session.getPrincipal().getName();
            List<String> subscriptions = notificationManager.getSubscriptionsForUserOnDocument(username,
                    updatedDocument);
            assertTrue(subscriptions.contains(ColdStorageHelper.COLD_STORAGE_CONTENT_RESTORED_NOTIFICATION_NAME));
            assertFalse(subscriptions.contains(ColdStorageHelper.COLD_STORAGE_CONTENT_AVAILABLE_NOTIFICATION_NAME));
        }
    }
}
