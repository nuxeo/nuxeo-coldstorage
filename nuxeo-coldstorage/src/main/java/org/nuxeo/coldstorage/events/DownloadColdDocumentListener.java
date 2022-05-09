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
 *     Guillaume RENARD
 */

package org.nuxeo.coldstorage.events;

import java.io.Serializable;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.coldstorage.ColdStorageConstants;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventContext;
import org.nuxeo.ecm.core.event.EventListener;
import org.nuxeo.ecm.core.event.EventService;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.ecm.core.io.download.DownloadService;
import org.nuxeo.runtime.api.Framework;

/**
 * A synchronous listener that intercepts the download event in order to change the event id in the audit if the
 * targetted blob is the cold one.
 *
 * @since 2021.0.0
 */
public class DownloadColdDocumentListener implements EventListener {

    private static final Logger log = LogManager.getLogger(DownloadColdDocumentListener.class);

    @Override
    @SuppressWarnings("unchecked")
    public void handleEvent(Event event) {
        if (!DownloadService.EVENT_NAME.equals(event.getName())) {
            return;
        }
        EventContext eventContext = event.getContext();
        if (!(eventContext instanceof DocumentEventContext)) {
            return;
        }
        if (!eventContext.hasProperty("extendedInfos")) {
            return;
        }
        Map<String, Serializable> extendedInfos = (Map<String, Serializable>) eventContext.getProperty("extendedInfos");
        if (!extendedInfos.containsKey("blobXPath")) {
            return;
        }
        String xpath = (String) extendedInfos.get("blobXPath");
        if (ColdStorageConstants.COLD_STORAGE_CONTENT_PROPERTY.equals(xpath)) {
            EventService eventService = Framework.getService(EventService.class);
            // we don't want the regular AuditEventListener to listen to this event
            log.debug("Cancelling original download event for cold document download");
            event.cancel();
            eventContext.setProperty("category", ColdStorageConstants.EVENT_CATEGORY);
            Event newEvent = eventContext.newEvent(ColdStorageConstants.COLD_STORAGE_CONTENT_DOWNLOAD_EVENT_NAME);
            eventService.fireEvent(newEvent);
        }
    }

}
