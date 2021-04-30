package org.nuxeo.coldstorage.operations;

import static javax.servlet.http.HttpServletResponse.SC_CONFLICT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;

import javax.inject.Inject;

import org.junit.Test;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.OperationException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;

public abstract class AbstractTestRestoreFromColdStorageOperation extends AbstractTestColdStorageOperation {

    @Inject
    protected CoreSession session;

    @Test
    public void shouldBeingRestore() throws IOException, OperationException {
        DocumentModel documentModel = createFileDocument(session, true);
        // first make the move to cold storage
        moveContentToColdStorage(session, documentModel);
        session.saveDocument(documentModel);
        restoreContentFromColdStorage(documentModel);
    }

    @Test
    public void shouldFailRestoreNoContent() throws OperationException {
        DocumentModel documentModel = createFileDocument(session, true);
        try {
            // request a retrieval from the cold storage
            restoreContentFromColdStorage(documentModel);
            fail("Should fail because there no cold storage content associated to this document.");
        } catch (NuxeoException e) {
            assertEquals(SC_CONFLICT, e.getStatusCode());
        }
    }

    protected void restoreContentFromColdStorage(DocumentModel documentModel) throws OperationException {
        restoreContentFromColdStorage(documentModel, 0);
    }

    protected void restoreContentFromColdStorage(DocumentModel documentModel, int numberOfAvailabilityDays)
            throws OperationException {
        try (OperationContext context = new OperationContext(session)) {
            context.setInput(documentModel);
            DocumentModel updatedDocument = (DocumentModel) automationService.run(context, RestoreFromColdStorage.ID);
            assertEquals(documentModel.getRef(), updatedDocument.getRef());
        }
    }
}
