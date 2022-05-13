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
 *     Guillaume RENARD<grenard@nuxeo.com>
 */

package org.nuxeo.coldstorage.events;

import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.coldstorage.ColdStorageConstants;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.event.CoreEventConstants;
import org.nuxeo.ecm.core.api.impl.DownloadBlobGuard;
import org.nuxeo.ecm.core.api.model.Property;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventContext;
import org.nuxeo.ecm.core.event.EventListener;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;

/**
 * Listener on beforeDocumentModification event to prevent from removing Cold Storage facet or edit main and cold
 * storage content.
 *
 * @since 2021.0.0
 */
public class PreventColdStorageUpdateListener implements EventListener {

    private static final Logger log = LogManager.getLogger(PreventColdStorageUpdateListener.class);

    public static final String DISABLE_PREVENT_COLD_STORAGE_UPDATE_LISTENER = "disablePreventColdStorageContentUpdateListener";

    @Override
    public void handleEvent(Event event) {
        EventContext eventContext = event.getContext();
        if (!(eventContext instanceof DocumentEventContext)) {
            return;
        }

        DocumentEventContext docCtx = (DocumentEventContext) eventContext;
        DocumentModel doc = docCtx.getSourceDocument();
        if (Boolean.TRUE.equals(docCtx.getProperty(DISABLE_PREVENT_COLD_STORAGE_UPDATE_LISTENER))) {
            log.trace("Checking the blob replacement in cold storage is disabled for document {}", doc::getId);
            return;
        }

        DocumentModel previousDocument = (DocumentModel) eventContext.getProperty(
                CoreEventConstants.PREVIOUS_DOCUMENT_MODEL);
        boolean docHasFacet = doc.hasFacet(ColdStorageConstants.COLD_STORAGE_FACET_NAME);
        boolean previousDocumentHasFacet = previousDocument.hasFacet(ColdStorageConstants.COLD_STORAGE_FACET_NAME);
        if (previousDocumentHasFacet) {
            // Prevent removing the cold storage facet of a document whose is stored in S3 Glacier
            if (!doc.hasFacet(ColdStorageConstants.COLD_STORAGE_FACET_NAME)) {
                // mark the event to bubble the exception, which results on a TX rollback
                event.markBubbleException();
                throw new NuxeoException(String.format("Cannot remove cold storage facet from document %s", doc),
                        SC_FORBIDDEN);
            }
            // Prevent replacing the cold storage content of a document who is stored in S3 Glacier
            Property coldContent = doc.getProperty(ColdStorageConstants.COLD_STORAGE_CONTENT_PROPERTY);
            Property mainContent = doc.getProperty(ColdStorageConstants.FILE_CONTENT_PROPERTY);
            if ((coldContent != null && coldContent.isDirty() || (mainContent != null && mainContent.isDirty()))) {
                // mark the event to bubble the exception, which results on a TX rollback
                event.markBubbleException();
                throw new NuxeoException(String.format("Cannot edit content of cold storage document %s", doc),
                        SC_FORBIDDEN);
            }
        }

        if (docHasFacet || previousDocumentHasFacet) {
            // Do not trigger a full text re-indexing since the main blob was sent to cold storage
            log.trace("Document is in cold storage, skip fulltext reindex", doc::getId);
            DownloadBlobGuard.enable();
        }
    }

}
