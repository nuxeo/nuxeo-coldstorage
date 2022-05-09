package org.nuxeo.coldstorage.operations;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.coldstorage.service.ColdStorageService;
import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.impl.DocumentModelListImpl;

/**
 * Restores the cold storage content associated with the input {@link DocumentModel} or {@link DocumentModelList} to the
 * main storage.
 *
 * @since 2021.0.0
 */
@Operation(id = RestoreFromColdStorage.ID, category = Constants.CAT_BLOB, label = "Restore from Cold Storage", description = "Restore document under cold storage content to the main storage.")
public class RestoreFromColdStorage {

    private static final Logger log = LogManager.getLogger(RestoreFromColdStorage.class);

    public static final String ID = "Document.RestoreFromColdStorage";

    @Context
    protected CoreSession session;

    @Context
    protected ColdStorageService service;

    @OperationMethod
    public DocumentModel run(DocumentModel document) {
        return service.restoreFromColdStorage(session, document.getRef());
    }

    @OperationMethod
    public DocumentModelList run(DocumentModelList documents) {
        DocumentModelList result = new DocumentModelListImpl();
        for (DocumentModel documentModel : documents) {
            try {
                result.add(run(documentModel));
            } catch (NuxeoException e) {
                log.error("Unable to restore document: {} from cold storage", documentModel.getId(), e);
            }
        }
        return result;
    }
}
