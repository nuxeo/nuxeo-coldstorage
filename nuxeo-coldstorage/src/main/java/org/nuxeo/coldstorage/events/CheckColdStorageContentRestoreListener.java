/*
 * (C) Copyright 2021 Nuxeo (http://nuxeo.com/) and others.
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
 *     Abdoul BA<aba@nuxeo.com>
 */

package org.nuxeo.coldstorage.events;

import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_BEING_RESTORED_PROPERTY;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_BEING_RETRIEVED_PROPERTY;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_AVAILABLE_IN_COLDSTORAGE;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_DOWNLOADABLE_UNTIL;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_PROPERTY;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_RESTORED_EVENT_NAME;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_STORAGE_CLASS_TO_UPDATED;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_FACET_NAME;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_TO_BE_RESTORED_PROPERTY;
import static org.nuxeo.coldstorage.ColdStorageConstants.FILE_CONTENT_PROPERTY;
import static org.nuxeo.coldstorage.events.CheckUpdateColdStorageContentListener.DISABLE_COLD_STORAGE_CHECK_UPDATE_COLDSTORAGE_CONTENT_LISTENER;
import static org.nuxeo.coldstorage.events.CheckUpdateMainContentInColdStorageListener.DISABLE_COLD_STORAGE_CHECK_UPDATE_MAIN_CONTENT_LISTENER;

import java.io.Serializable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.event.EventBundle;
import org.nuxeo.ecm.core.event.EventService;
import org.nuxeo.ecm.core.event.PostCommitEventListener;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.runtime.api.Framework;

/**
 * An asynchronous listener that is in charge of restoring the main content from Cold Storage.
 *
 * @apiNote: This listener is designed to be called from the helper(the event can be dispatched from anywhere).
 * @Since 10.10
 */
public class CheckColdStorageContentRestoreListener implements PostCommitEventListener {

    private static final Logger log = LogManager.getLogger(CheckColdStorageContentRestoreListener.class);

    @Override
    public void handleEvent(EventBundle events) {
        if (events.isEmpty()) {
            return;
        }

        events.forEach(e -> {
            if (e.getContext() instanceof DocumentEventContext) {
                DocumentEventContext docCtx = (DocumentEventContext) e.getContext();
                DocumentModel documentModel = docCtx.getSourceDocument();
                log.debug("Start completing ColdStorage restore content for document {}", documentModel::getId);

                // Restore the main content
                CoreSession session = e.getContext().getCoreSession();
                Serializable coldContent = documentModel.getPropertyValue(COLD_STORAGE_CONTENT_PROPERTY);
                documentModel = removeColdStorageFacet(session, documentModel);

                documentModel.setPropertyValue(FILE_CONTENT_PROPERTY, coldContent);
                documentModel = saveDocument(session, documentModel);

                // Send notification
                DocumentEventContext ctx = new DocumentEventContext(session, session.getPrincipal(), documentModel);
                EventService eventService = Framework.getService(EventService.class);
                ctx.setProperty(COLD_STORAGE_CONTENT_RESTORED_EVENT_NAME, "true");
                eventService.fireEvent(ctx.newEvent(COLD_STORAGE_CONTENT_RESTORED_EVENT_NAME));
                log.debug("End completing ColdStorage restore content for document {}", documentModel::getId);
            }

        });
    }

    protected DocumentModel removeColdStorageFacet(CoreSession session, DocumentModel documentModel) {
        // We must reset all properties of the Cold Storage facet before removing it, otherwise the properties will
        // still have the old values if we add back the facet (i.e. send back to cold storage)
        documentModel.setPropertyValue(COLD_STORAGE_CONTENT_PROPERTY, null);
        documentModel.setPropertyValue(COLD_STORAGE_BEING_RESTORED_PROPERTY, null);
        documentModel.setPropertyValue(COLD_STORAGE_BEING_RETRIEVED_PROPERTY, null);
        documentModel.setPropertyValue(COLD_STORAGE_TO_BE_RESTORED_PROPERTY, null);
        documentModel.setPropertyValue(COLD_STORAGE_CONTENT_AVAILABLE_IN_COLDSTORAGE, null);
        documentModel.setPropertyValue(COLD_STORAGE_CONTENT_STORAGE_CLASS_TO_UPDATED, null);
        documentModel.setPropertyValue(COLD_STORAGE_CONTENT_DOWNLOADABLE_UNTIL, null);
        documentModel = saveDocument(session, documentModel);
        documentModel.removeFacet(COLD_STORAGE_FACET_NAME);
        return documentModel;
    }

    /**
     * Specific save method to disable specific listeners.
     */
    protected DocumentModel saveDocument(CoreSession session, DocumentModel documentModel) {
        // Disable main and ColdStorage storage contents check otherwise, the restore action won't be allowed
        documentModel.putContextData(DISABLE_COLD_STORAGE_CHECK_UPDATE_MAIN_CONTENT_LISTENER, true);
        documentModel.putContextData(DISABLE_COLD_STORAGE_CHECK_UPDATE_COLDSTORAGE_CONTENT_LISTENER, true);
        return session.saveDocument(documentModel);
    }
}
