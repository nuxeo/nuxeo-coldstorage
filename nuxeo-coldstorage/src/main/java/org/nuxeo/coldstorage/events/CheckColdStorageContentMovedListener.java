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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.coldstorage.ColdStorageConstants;
import org.nuxeo.coldstorage.service.ColdStorageService;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventContext;
import org.nuxeo.ecm.core.event.EventListener;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.runtime.api.Framework;

/**
 * A synchronous listener that is in charge of moving the main content to Cold Storage for all versions and duplicated
 * documents.
 *
 * @apiNote: This listener is designed to be called after
 *           {@value org.nuxeo.coldstorage.ColdStorageConstants#COLD_STORAGE_CONTENT_MOVED_EVENT_NAME} event was
 *           raised.
 * @Since 10.10
 */
public class CheckColdStorageContentMovedListener implements EventListener {

    private static final Logger log = LogManager.getLogger(CheckColdStorageContentMovedListener.class);

    @Override
    public void handleEvent(Event event) {
        EventContext eventContext = event.getContext();
        if (!(eventContext instanceof DocumentEventContext)) {
            return;
        }

        DocumentEventContext docCtx = (DocumentEventContext) event.getContext();
        DocumentModel documentModel = docCtx.getSourceDocument();
        CoreSession coreSession = documentModel.getCoreSession();
        ColdStorageService service = Framework.getService(ColdStorageService.class);
        if (documentModel.hasFacet(ColdStorageConstants.COLD_STORAGE_FACET_NAME)) {
            log.debug("Start moving to ColdStorage all versions and duplicated blobs for document {}",
                    documentModel::getId);
            service.moveDuplicatedBlobToColdStorage(coreSession, documentModel);
            log.debug("End moving to ColdStorage all versions and duplicated blobs for document {}",
                    documentModel::getId);
        }
    }
}
