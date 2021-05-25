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

package org.nuxeo.ecm.automation.core.operations.coldstorage;

import static org.nuxeo.ecm.core.bulk.BulkServiceImpl.STATUS_STREAM;
import static org.nuxeo.lib.stream.computation.AbstractComputation.INPUT_1;
import static org.nuxeo.lib.stream.computation.AbstractComputation.OUTPUT_1;

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.nuxeo.coldstorage.helpers.ColdStorageHelper;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.thumbnail.ThumbnailService;
import org.nuxeo.ecm.core.bulk.action.computation.AbstractBulkComputation;
import org.nuxeo.lib.stream.computation.Topology;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.stream.StreamProcessorTopology;

/**
 * @since 11.1
 */
public class DeduplicationColdContentActions implements StreamProcessorTopology {

    public static final String ACTION_NAME = "deduplicationColdContent";

    protected static final List<String> VIEWS_GENERATION_LISTENER_TO_DISABLE = Arrays.asList(
            "disablePictureViewsGenerationListener", "disableVideoConversionsGenerationListener",
            "disableThumbnailComputation");

    @Override
    public Topology getTopology(Map<String, String> options) {
        return Topology.builder()
                       .addComputation(DeduplicationColdContentComputation::new, //
                               Arrays.asList(INPUT_1 + ":" + ACTION_NAME, OUTPUT_1 + ":" + STATUS_STREAM))
                       .build();
    }

    public static class DeduplicationColdContentComputation extends AbstractBulkComputation {

        public DeduplicationColdContentComputation() {
            super(ACTION_NAME);
        }

        @Override
        protected void compute(CoreSession session, List<String> ids, Map<String, Serializable> properties) {
            IdRef[] docRefs = ids.stream().map(IdRef::new).toArray(IdRef[]::new);
            DocumentModelList documents = session.getDocuments(docRefs);

            ThumbnailService thumbnailService = Framework.getService(ThumbnailService.class);
            for (DocumentModel document : documents) {
                Blob thumbnail = thumbnailService.getThumbnail(document, session);
                Serializable mainContent = document.getPropertyValue(ColdStorageHelper.FILE_CONTENT_PROPERTY);
                VIEWS_GENERATION_LISTENER_TO_DISABLE.forEach(n -> document.putContextData(n, true));

                document.addFacet(ColdStorageHelper.COLD_STORAGE_FACET_NAME);
                document.setPropertyValue(ColdStorageHelper.COLD_STORAGE_CONTENT_PROPERTY, mainContent);
                document.setPropertyValue(ColdStorageHelper.FILE_CONTENT_PROPERTY, (Serializable) thumbnail);

                session.saveDocument(document);
            }
        }
    }

}
