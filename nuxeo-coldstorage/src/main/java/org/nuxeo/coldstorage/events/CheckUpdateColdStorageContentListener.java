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
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentSecurityException;
import org.nuxeo.ecm.core.api.event.CoreEventConstants;
import org.nuxeo.ecm.core.api.model.Property;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventContext;
import org.nuxeo.ecm.core.event.EventListener;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
/**
 * @since 2021.20
 */
public class CheckUpdateColdStorageContentListener implements EventListener {

    private static final Logger log = LogManager.getLogger(CheckUpdateColdStorageContentListener.class);

    public static final String DISABLE_COLD_STORAGE_CHECK_UPDATE_COLDSTORAGE_CONTENT_LISTENER = "disableColdStorageCheckUpdateContentListener";

    @Override
    public void handleEvent(Event event) {
        EventContext eventContext = event.getContext();
        if (!(eventContext instanceof DocumentEventContext)) {
            return;
        }

        DocumentEventContext docCtx = (DocumentEventContext) eventContext;
        DocumentModel doc = docCtx.getSourceDocument();
        if (Boolean.TRUE.equals(docCtx.getProperty(DISABLE_COLD_STORAGE_CHECK_UPDATE_COLDSTORAGE_CONTENT_LISTENER))) {
            log.trace("Checking the blob replacement in cold storage is disabled for document {}", doc::getId);
            return;
        }

        DocumentModel previousDocument = (DocumentModel) eventContext.getProperty(
                CoreEventConstants.PREVIOUS_DOCUMENT_MODEL);
        if (previousDocument.hasFacet(ColdStorageConstants.COLD_STORAGE_FACET_NAME)) {
            // Prevent replacing the cold storage content of a document who is stored in S3 Glacier
            if (doc.hasFacet(ColdStorageConstants.COLD_STORAGE_FACET_NAME)
                    && previousDocument.getPropertyValue(ColdStorageConstants.COLD_STORAGE_CONTENT_PROPERTY) != null) {
                Property coldContent = doc.getProperty(ColdStorageConstants.COLD_STORAGE_CONTENT_PROPERTY);
                if (coldContent != null && coldContent.isDirty()) {
                    // mark the event to bubble the exception, which results on a TX rollback
                    event.markBubbleException();
                    throw new DocumentSecurityException(
                            String.format("The Document %s content cannot be updated.", doc));
                }
            }
            // Prevent replacing the cold storage facet of a document whose is stored in S3 Glacier
            if (Boolean.FALSE.equals(doc.hasFacet(ColdStorageConstants.COLD_STORAGE_FACET_NAME))) {
                // mark the event to bubble the exception, which results on a TX rollback
                event.markBubbleException();
                throw new DocumentSecurityException(String.format("The Document %s facet cannot be updated.", doc));
            }
        }
    }

}
