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
 *     Guillaume Renard<grenard@nuxeo.com>
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
 * Bulk move documents to Cold Storage.
 */
public class MoveToColdStorageContentAction implements StreamProcessorTopology {

    private static final Logger log = LogManager.getLogger(MoveToColdStorageContentAction.class);

    public static final String ACTION_NAME = "moveToColdStorage";

    public static final String ACTION_FULL_NAME = "bulk/" + ACTION_NAME;

    @Override
    public Topology getTopology(Map<String, String> options) {
        return Topology.builder()
                       .addComputation(MoveToColdStorageContentComputation::new, //
                               List.of(INPUT_1 + ":" + ACTION_FULL_NAME, OUTPUT_1 + ":" + STATUS_STREAM))
                       .build();
    }

    public static class MoveToColdStorageContentComputation extends AbstractBulkComputation {

        public MoveToColdStorageContentComputation() {
            super(ACTION_FULL_NAME);
        }

        @Override
        protected void compute(CoreSession session, List<String> ids, Map<String, Serializable> properties) {
            log.debug("Start computing documents to be sent to ColdStorage {}", ids);
            DocumentModelList documents = loadDocuments(session, ids);

            ColdStorageService service = Framework.getService(ColdStorageService.class);

            long errorCount = 0;
            for (DocumentModel document : documents) {
                try {
                    service.moveToColdStorage(session, document.getRef());
                } catch (NuxeoException e) {
                    errorCount++;
                    var message = String.format("Cannot move document %s to cold storage: %s", document.getId(),
                            e.getMessage());
                    delta.inError(message);
                    log.warn(message, e);
                }
            }
            delta.setErrorCount(errorCount);
            log.debug("End computing documents to be sent to ColdStorage");
        }
    }

}
