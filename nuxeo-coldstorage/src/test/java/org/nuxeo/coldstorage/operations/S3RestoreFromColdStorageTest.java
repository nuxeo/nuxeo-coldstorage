package org.nuxeo.coldstorage.operations;

import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;

import org.junit.Test;
import org.nuxeo.coldstorage.S3ColdStorageFeature;
import org.nuxeo.coldstorage.S3TestHelper;
import org.nuxeo.coldstorage.helpers.ColdStorageHelper;
import org.nuxeo.ecm.automation.OperationException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.runtime.test.runner.Features;

@Features(S3ColdStorageFeature.class)
public class S3RestoreFromColdStorageTest extends AbstractTestRestoreFromColdStorageOperation {
    protected S3TestHelper s3TestHelper = S3TestHelper.getInstance();

    @Test
    public void shouldFailBeingRestored() throws IOException, OperationException {
        DocumentModel documentModel = createFileDocument(session, true);

        // first make the move to cold storage
        moveContentToColdStorage(session, documentModel);
        session.saveDocument(documentModel);
        restoreContentFromColdStorage(documentModel);
        // request a retrieval for a second time
        try {
            restoreContentFromColdStorage(documentModel);
            fail("Should fail because the cold storage content is being restored.");
        } catch (NuxeoException e) {
            assertEquals(SC_FORBIDDEN, e.getStatusCode());
        }
    }

    @Override
    protected void moveContentToColdStorage(CoreSession session, DocumentModel documentModel)
            throws IOException, OperationException {
        super.moveContentToColdStorage(session, documentModel);
        // Mock AWS Lifecycle rule
        s3TestHelper.moveBlobContentToGlacier(session.getDocument(documentModel.getRef()));
    }

    protected void restoreContentFromColdStorage(DocumentModel documentModel, int numberOfAvailabilityDays)
            throws OperationException {
        super.restoreContentFromColdStorage(documentModel, numberOfAvailabilityDays);
        documentModel.refresh();
        assertEquals(Boolean.TRUE,
                documentModel.getPropertyValue(ColdStorageHelper.COLD_STORAGE_UNDO_MOVE_PROPERTY));
    }
}
