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

import static org.nuxeo.coldstorage.ColdStorageConstants.FILE_CONTENT_PROPERTY;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.coldstorage.service.ColdStorageService;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventBundle;
import org.nuxeo.ecm.core.event.PostCommitEventListener;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.runtime.api.Framework;

/**
 * An asynchronous listener that is in charge of restoring the main content from Cold Storage.
 *
 * @apiNote: This listener is designed to be called from the helper (the event can be dispatched from anywhere).
 * @Since 10.10
 */
public class CheckColdStorageContentRestoreListener implements PostCommitEventListener {

    private static final Logger log = LogManager.getLogger(CheckColdStorageContentRestoreListener.class);

    @Override
    public void handleEvent(EventBundle events) {
        if (events.isEmpty()) {
            return;
        }
        ColdStorageService service = Framework.getService(ColdStorageService.class);
        List<String> blobDigests = new ArrayList<String>();
        Iterator<Event> it = events.iterator();
        while (it.hasNext()) {
            Event e = it.next();
            DocumentEventContext docCtx = (DocumentEventContext) e.getContext();
            CoreSession session = docCtx.getCoreSession();
            DocumentModel documentModel = docCtx.getSourceDocument();
            log.debug("Start completing ColdStorage restore content for document {}", documentModel::getId);
            String coldContentDigest = ((Blob) documentModel.getPropertyValue(FILE_CONTENT_PROPERTY)).getDigest();
            blobDigests.add(coldContentDigest);

            // Proceed with all documents referencing the same blob
            log.debug("End completing ColdStorage restore content for document {}", documentModel::getId);
            if (!it.hasNext() && !blobDigests.isEmpty()) {
                service.propagateRestoreFromColdStorage(session, blobDigests);
            }
        }
    }

}
