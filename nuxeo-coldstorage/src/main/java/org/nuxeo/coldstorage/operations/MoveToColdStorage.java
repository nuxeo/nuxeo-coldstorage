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

import org.nuxeo.coldstorage.service.ColdStorageService;
import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.impl.DocumentModelListImpl;

/**
 * Moves the main content associated with the input {@link DocumentModel} or {@link DocumentModelList} to the cold
 * storage.
 * 
 * @since 2021.0.0
 */
@Operation(id = MoveToColdStorage.ID, category = Constants.CAT_BLOB, label = "Move to Cold Storage", description = "Move the main document content to the cold storage.")
public class MoveToColdStorage {

    public static final String ID = "Document.MoveToColdStorage";

    @Context
    protected CoreSession session;

    @Context
    protected ColdStorageService service;

    @OperationMethod
    public DocumentModel run(DocumentModel document) {
        return service.moveToColdStorage(session, document.getRef());
    }

    @OperationMethod
    public DocumentModelList run(DocumentModelList documents) {
        DocumentModelList result = new DocumentModelListImpl();
        for (DocumentModel documentModel : documents) {
            result.add(run(documentModel));
        }
        return result;
    }

}
