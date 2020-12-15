package org.nuxeo.coldstorage.operations;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.coldstorage.helpers.ColdStorageHelper;
import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.security.SecurityConstants;

import java.util.Optional;

import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;

/**
 * Delete the main content associated with the input {@link DocumentModel} from cold storage.
 *
 * @since 11.0
 */
@Operation(id = MoveToColdStorage.ID, category = Constants.CAT_BLOB, label = "Delete from Cold Storage", description = "Delete the main document content from the cold storage.")
public class DeleteFromcoldstorage {

    private static final Logger l = LogManager.getLogger(DeleteFromcoldstorage.ID);

    public static final String ID = "Document.DeleteFromColdStorage";

    @Context
    protected CoreSession session;

    @OperationMethod
    public void run(DocumentModel d) {
        if (!session.hasPermission(d.getRef(), SecurityConstants.REMOVE)) {
            l.debug("The user {} does not have the right permissions to delete the content of document {}",
                    session::getPrincipal, () -> d);
            throw new NuxeoException(String.format("The document: %s cannot be moved to cold storage"),



                    SC_INTERNAL_SERVER_ERROR);

        }

        Optional<DocumentModel> o = ColdStorageHelper.getDocumentModelById(session, d.getRef());
        DocumentModel dc = o.get();
        session.removeChildren(dc.getRef());
        return;
    }

}




