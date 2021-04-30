package org.nuxeo.coldstorage.operations;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.coldstorage.helpers.ColdStorageHelper;
import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.automation.core.annotations.Param;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.core.api.impl.DocumentModelListImpl;
import org.nuxeo.ecm.platform.ec.notification.NotificationConstants;
import org.nuxeo.ecm.platform.notification.api.NotificationManager;
import org.nuxeo.runtime.api.Framework;

/**
 * Restores the cold storage content associated with the input {@link DocumentModel} or {@link DocumentModelList} to the
 * main storage.
 *
 * @since 10.10
 */
@Operation(id = RestoreFromColdStorage.ID, category = Constants.CAT_BLOB, label = "Restore from Cold Storage", description = "Restore document under cold storage content to the main storage.")
public class RestoreFromColdStorage {

    private static final Logger log = LogManager.getLogger(RestoreFromColdStorage.class);

    public static final String ID = "Document.RestoreFromColdStorage";

    @Context
    protected CoreSession session;

    @Param(name = "save", required = false, values = "true")
    protected boolean save = true;

    @OperationMethod
    public DocumentModel run(DocumentModel document) {
        // auto-subscribe the user, this way he will receive the mail notification when the content is available
        NuxeoPrincipal principal = session.getPrincipal();
        String username = NotificationConstants.USER_PREFIX + principal.getName();
        NotificationManager notificationManager = Framework.getService(NotificationManager.class);
        notificationManager.addSubscription(username, ColdStorageHelper.COLD_STORAGE_CONTENT_RESTORED_NOTIFICATION_NAME,
                document, false, principal, ColdStorageHelper.COLD_STORAGE_CONTENT_RESTORED_NOTIFICATION_NAME);
        DocumentModel documentModel = ColdStorageHelper.restoreContentFromColdStorage(session, document.getRef());
        if (save) {
            documentModel = session.saveDocument(documentModel);
        }

        return documentModel;
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
