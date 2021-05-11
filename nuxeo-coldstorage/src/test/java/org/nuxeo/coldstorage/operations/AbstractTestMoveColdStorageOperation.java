package org.nuxeo.coldstorage.operations;

import static javax.servlet.http.HttpServletResponse.SC_CONFLICT;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;

import org.junit.Test;
import org.nuxeo.coldstorage.helpers.ColdStorageHelper;
import org.nuxeo.ecm.automation.OperationException;
import org.nuxeo.ecm.core.api.CloseableCoreSession;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.security.ACE;
import org.nuxeo.ecm.core.api.security.SecurityConstants;

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

        List<DocumentModel> documents = List.of(createFileDocument(session, true, aces), //
                createFileDocument(session, true, aces), //
                createFileDocument(session, true, aces));

        CoreSession userSession = CoreInstance.getCoreSession(session.getRepositoryName(), "linda");
        moveContentToColdStorage(userSession, documents);

        // with Administrator
        documents = List.of(createFileDocument(session, true), //
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
}
