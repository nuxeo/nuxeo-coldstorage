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

package org.nuxeo.coldstorage.operations;

import java.time.Duration;

import org.nuxeo.coldstorage.service.ColdStorageService;
import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.automation.core.annotations.Param;
import org.nuxeo.ecm.automation.core.collectors.DocumentModelCollector;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;

/**
 * Requests a retrieval from cold storage of the content associated with the input {@link DocumentModel}. This operation
 * will initiate a restoration request, calling the {@link Blob#getStream()} during this process doesn't mean you will
 * get the blob's content.
 *
 * @since 2021.0.0
 */
@Operation(id = RequestRetrievalFromColdStorage.ID, category = Constants.CAT_BLOB, label = "Request retrieval from cold storage", description = "Request a retrieval from cold storage of the content associated with the document.")
public class RequestRetrievalFromColdStorage {

    public static final String ID = "Document.RequestRetrievalFromColdStorage";

    @Param(name = "numberOfDaysOfAvailability", description = "The number of days that you want your cold storage content to be accessible.", required = false)
    protected int numberOfDaysOfAvailability;

    @Context
    protected CoreSession session;

    @Context
    protected ColdStorageService service;

    @OperationMethod(collector = DocumentModelCollector.class)
    public DocumentModel run(DocumentModel doc) {
        Duration duration;
        if (numberOfDaysOfAvailability == 0) {
            duration = service.getAvailabilityDuration();
        } else {
            duration = Duration.ofDays(numberOfDaysOfAvailability);
        }

        return service.retrieveFromColdStorage(session, doc.getRef(), duration);
    }

}
