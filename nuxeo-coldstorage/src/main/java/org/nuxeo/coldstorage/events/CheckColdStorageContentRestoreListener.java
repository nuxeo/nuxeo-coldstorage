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

import static org.nuxeo.coldstorage.events.CheckUpdateColdStorageContentListener.DISABLE_COLD_STORAGE_CHECK_UPDATE_COLDSTORAGE_CONTENT_LISTENER;
import static org.nuxeo.coldstorage.events.CheckUpdateMainContentInColdStorageListener.DISABLE_COLD_STORAGE_CHECK_UPDATE_MAIN_CONTENT_LISTENER;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_PROPERTY;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_RESTORED_EVENT_NAME;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_FACET_NAME;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_TO_BE_RESTORED_PROPERTY;
import static org.nuxeo.coldstorage.ColdStorageConstants.FILE_CONTENT_PROPERTY;

import java.io.Serializable;

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

    @Override
    public void handleEvent(EventBundle events) {
        if (events.isEmpty()) {
            return;
        }

        events.forEach(e -> {
            if (e.getContext() instanceof DocumentEventContext) {
                DocumentEventContext docCtx = (DocumentEventContext) e.getContext();
                DocumentModel documentModel = docCtx.getSourceDocument();

                // Disable main and ColdStorage storage contents check otherwise, the restore action won't be allowed
                documentModel.putContextData(DISABLE_COLD_STORAGE_CHECK_UPDATE_MAIN_CONTENT_LISTENER, true);
                documentModel.putContextData(DISABLE_COLD_STORAGE_CHECK_UPDATE_COLDSTORAGE_CONTENT_LISTENER, true);

                // Restore the main content
                CoreSession session = e.getContext().getCoreSession();
                Serializable coldContent = documentModel.getPropertyValue(COLD_STORAGE_CONTENT_PROPERTY);
                documentModel.setPropertyValue(COLD_STORAGE_TO_BE_RESTORED_PROPERTY, false);
                documentModel.setPropertyValue(COLD_STORAGE_CONTENT_PROPERTY, null);
                documentModel.removeFacet(COLD_STORAGE_FACET_NAME);
                documentModel.setPropertyValue(FILE_CONTENT_PROPERTY, coldContent);

                // Send notification
                DocumentEventContext ctx = new DocumentEventContext(session, session.getPrincipal(), documentModel);
                EventService eventService = Framework.getService(EventService.class);
                ctx.setProperty(COLD_STORAGE_CONTENT_RESTORED_EVENT_NAME, "true");
                eventService.fireEvent(ctx.newEvent(COLD_STORAGE_CONTENT_RESTORED_EVENT_NAME));

                session.saveDocument(documentModel);
            }

        });
    }
}
