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

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.coldstorage.service.ColdStorageService;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.event.EventBundle;
import org.nuxeo.ecm.core.event.PostCommitEventListener;
import org.nuxeo.ecm.core.repository.RepositoryService;
import org.nuxeo.runtime.api.Framework;

/**
 * An asynchronous listener that checks if the storage class has been updated.
 *
 * @apiNote This listener is designed to be called from a scheduler.
 * @since 10.10
 */
public class CheckColdStorageClassUpdatedListener implements PostCommitEventListener {

    private static final Logger log = LogManager.getLogger(CheckColdStorageClassUpdatedListener.class);

    @Override
    public void handleEvent(EventBundle eventBundle) {
        log.debug("Start checking storage class for cold storage content");
        List<String> repositoryNames = Framework.getService(RepositoryService.class).getRepositoryNames();
        ColdStorageService service = Framework.getService(ColdStorageService.class);
        for (String repository : repositoryNames) {
            CoreSession coreSession = CoreInstance.getCoreSessionSystem(repository);
            service.checkColdStorageClass(coreSession);
        }
        log.debug("End checking storage class for cold storage content");
    }
}
