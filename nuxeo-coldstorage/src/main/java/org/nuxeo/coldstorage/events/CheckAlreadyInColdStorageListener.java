/*
 * (C) Copyright 2022 Nuxeo (http://nuxeo.com/) and others.
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
 *     Guillaume RENARD <grenard@nuxeo.com>
 */

package org.nuxeo.coldstorage.events;

import static org.nuxeo.coldstorage.ColdStorageConstants.FILE_CONTENT_PROPERTY;
import static org.nuxeo.ecm.core.api.event.DocumentEventTypes.DOCUMENT_CREATED;
import static org.nuxeo.ecm.core.api.event.DocumentEventTypes.DOCUMENT_UPDATED;
import static org.nuxeo.ecm.platform.thumbnail.listener.UpdateThumbnailListener.THUMBNAIL_UPDATED;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.coldstorage.ColdStorageConstants;
import org.nuxeo.coldstorage.ColdStorageHelper;
import org.nuxeo.coldstorage.service.ColdStorageService;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.blob.ManagedBlob;
import org.nuxeo.ecm.core.event.DeletedDocumentModel;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventContext;
import org.nuxeo.ecm.core.event.EventListener;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.ecm.platform.thumbnail.listener.ThumbnailHelper;
import org.nuxeo.runtime.api.Framework;

/**
 * Listener on documentModified and documentCreated event to check if new document references a blob already in Cold
 * Storage.
 *
 * @since 2021.0.0
 */
public class CheckAlreadyInColdStorageListener implements EventListener {

    private static final Logger log = LogManager.getLogger(CheckAlreadyInColdStorageListener.class);

    public static final String DISABLE_CHECK_ALREADY_IN_COLD_STORAGE_LISTENER = "disableCheckAlreadyInColdStorageListener";

    protected ThumbnailHelper thumbnailHelper = new ThumbnailHelper();

    @Override
    public void handleEvent(Event event) {
        if (!(DOCUMENT_CREATED.equals(event.getName()) || DOCUMENT_UPDATED.equals(event.getName()))) {
            return;
        }
        EventContext ec = event.getContext();
        if (Boolean.TRUE.equals(ec.getProperty(THUMBNAIL_UPDATED))) {
            return;
        }
        DocumentEventContext docCtx = (DocumentEventContext) ec;
        DocumentModel doc = docCtx.getSourceDocument();
        if (Boolean.TRUE.equals(docCtx.getProperty(DISABLE_CHECK_ALREADY_IN_COLD_STORAGE_LISTENER))) {
            log.trace("Checking the main blob is already in cold storage is disabled for document {}", doc::getId);
            return;
        }
        if (doc instanceof DeletedDocumentModel) {
            return;
        }
        if (doc.isProxy()) {
            return;
        }
        if (doc.hasFacet(ColdStorageConstants.COLD_STORAGE_FACET_NAME)) {
            return;
        }
        if (!doc.hasSchema("file")) {
            return;
        }
        Blob blob = (Blob) doc.getPropertyValue(FILE_CONTENT_PROPERTY);
        if (blob != null && blob instanceof ManagedBlob) {
            if (ColdStorageHelper.isInColdStorage((ManagedBlob) blob)) {
                log.debug("Main blob is already in cold storage, need to update document accordingly.");
                ColdStorageService service = Framework.getService(ColdStorageService.class);
                CoreSession session = docCtx.getCoreSession();
                // We need to make sure the thumbnail is available to use it as placeholder of the main blob
                thumbnailHelper.createThumbnailIfNeeded(session, doc);
                doc = service.proceedMoveToColdStorage(session, doc.getRef());
                if (doc.isVersion()) {
                    doc.putContextData(CoreSession.ALLOW_VERSION_WRITE, true);
                }
                session.saveDocument(doc);
            }
        }
    }

}
