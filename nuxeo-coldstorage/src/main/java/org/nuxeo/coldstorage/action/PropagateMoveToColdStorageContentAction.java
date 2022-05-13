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

package org.nuxeo.coldstorage.action;

import static org.nuxeo.ecm.core.bulk.BulkServiceImpl.STATUS_STREAM;
import static org.nuxeo.lib.stream.computation.AbstractComputation.INPUT_1;
import static org.nuxeo.lib.stream.computation.AbstractComputation.OUTPUT_1;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.coldstorage.ColdStorageConstants;
import org.nuxeo.coldstorage.service.ColdStorageService;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.bulk.action.computation.AbstractBulkComputation;
import org.nuxeo.lib.stream.computation.Topology;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.stream.StreamProcessorTopology;

/**
 * Bulk action in charge of moving documents referencing a blob shared by another document that was just moved to cold
 * storage.
 *
 * @since 2021.0.0
 */
public class PropagateMoveToColdStorageContentAction implements StreamProcessorTopology {

    private static final Logger log = LogManager.getLogger(PropagateMoveToColdStorageContentAction.class);

    public static final String ACTION_NAME = "propagateMoveToColdStorage";

    public static final String ACTION_FULL_NAME = "bulk/" + ACTION_NAME;

    @Override
    public Topology getTopology(Map<String, String> options) {
        return Topology.builder()
                       .addComputation(PropagateMoveToColdStorageContentComputation::new, //
                               List.of(INPUT_1 + ":" + ACTION_FULL_NAME, OUTPUT_1 + ":" + STATUS_STREAM))
                       .build();
    }

    public static class PropagateMoveToColdStorageContentComputation extends AbstractBulkComputation {

        public PropagateMoveToColdStorageContentComputation() {
            super(ACTION_FULL_NAME);
        }

        @Override
        protected void compute(CoreSession session, List<String> ids, Map<String, Serializable> properties) {
            log.debug("Start computing documents of which content has been sent to ColdStorage {}", ids);
            DocumentModelList documents = loadDocuments(session, ids);

            ColdStorageService service = Framework.getService(ColdStorageService.class);

            long errorCount = 0;
            for (DocumentModel document : documents) {
                if (document.hasFacet(ColdStorageConstants.COLD_STORAGE_FACET_NAME)) {
                    log.info("The main content is already in cold storage for document: {}", document::getId);
                    continue;
                }
                try {
                    DocumentModel documentModel = service.proceedMoveToColdStorage(session, document.getRef());
                    if (documentModel.isVersion()) {
                        documentModel.putContextData(CoreSession.ALLOW_VERSION_WRITE, true);
                    }
                    session.saveDocument(documentModel);
                } catch (NuxeoException e) {
                    errorCount++;
                    delta.inError(String.format("Cannot propagate move to cold storage for document %s: %s", document.getId(),
                            e.getMessage()));
                    log.warn("Could not propagate move to cold storage for document: {}", document::getId, () -> e);
                }
            }
            delta.setErrorCount(errorCount);
            log.debug("End computing documents of which content has been sent to ColdStorage");
        }
    }

}
