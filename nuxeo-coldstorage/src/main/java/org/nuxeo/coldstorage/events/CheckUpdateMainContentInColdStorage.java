/*
 * (C) Copyright 2020 Nuxeo (http://nuxeo.com/) and others.
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
 *     Salem Aouana
 */

package org.nuxeo.coldstorage.events;

import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.coldstorage.helpers.ColdStorageHelper;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.event.CoreEventConstants;
import org.nuxeo.ecm.core.api.model.Property;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventContext;
import org.nuxeo.ecm.core.event.EventListener;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;

/**
 * A synchronous listener that prevents any main content replacement when it's stored in cold storage.
 * 
 * @since 11.1
 */
public class CheckUpdateMainContentInColdStorage implements EventListener {

    /** @since 10.10 **/
    private static final Logger log = LogManager.getLogger(CheckUpdateMainContentInColdStorage.class);

    /** @since 10.10 **/
    public static final String DISABLE_COLD_STORAGE_CHECK_UPDATE_MAIN_CONTENT_LISTENER = "disableColdStorageCheckUpdateMainContentListener";

    @Override
    public void handleEvent(Event event) {
        EventContext eventContext = event.getContext();
        if (!(eventContext instanceof DocumentEventContext)) {
            return;
        }

        DocumentEventContext docCtx = (DocumentEventContext) eventContext;
        DocumentModel doc = docCtx.getSourceDocument();
        if (Boolean.TRUE.equals(docCtx.getProperty(DISABLE_COLD_STORAGE_CHECK_UPDATE_MAIN_CONTENT_LISTENER))) {
            log.trace("Checking the main blob replacement in cold storage is disabled for document {}", doc::getId);
            return;
        }

        DocumentModel previousDocument = (DocumentModel) eventContext.getProperty(
                CoreEventConstants.PREVIOUS_DOCUMENT_MODEL);
        if (previousDocument.hasFacet(ColdStorageHelper.COLD_STORAGE_FACET_NAME)
                && previousDocument.getPropertyValue(ColdStorageHelper.COLD_STORAGE_CONTENT_PROPERTY) != null) {
            DocumentModel document = ((DocumentEventContext) eventContext).getSourceDocument();
            Property mainContent = document.getProperty(ColdStorageHelper.FILE_CONTENT_PROPERTY);
            if (mainContent.isDirty()) {
                // mark the event to bubble the exception, which results on a TX rollback
                event.markBubbleException();
                throw new NuxeoException(String.format(
                        "The main content of document: %s cannot be updated. It's already in cold storage.", document),
                        SC_FORBIDDEN);
            }

        }
    }

}
