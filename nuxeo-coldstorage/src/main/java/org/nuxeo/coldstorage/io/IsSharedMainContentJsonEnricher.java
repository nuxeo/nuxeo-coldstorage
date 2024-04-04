/*
 * (C) Copyright 2024 Nuxeo (http://nuxeo.com/) and others.
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
 *     Guillaume Renard
 */
package org.nuxeo.coldstorage.io;

import static org.nuxeo.coldstorage.ColdStorageConstants.FILE_CONTENT_PROPERTY;
import static org.nuxeo.ecm.core.io.registry.reflect.Instantiations.SINGLETON;
import static org.nuxeo.ecm.core.io.registry.reflect.Priorities.REFERENCE;

import java.io.IOException;
import java.io.Serializable;

import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.io.marshallers.json.enrichers.AbstractJsonEnricher;
import org.nuxeo.ecm.core.io.registry.context.RenderingContext.SessionWrapper;
import org.nuxeo.ecm.core.io.registry.reflect.Setup;
import org.nuxeo.ecm.core.query.sql.NXQL;

import com.fasterxml.jackson.core.JsonGenerator;

/**
 * Enricher that tells whether a document has its main content referenced by another document as main content. Versions
 * of the document referencing the same main content are not taken into account.
 *
 * @since 2023.4
 */
@Setup(mode = SINGLETON, priority = REFERENCE)
public class IsSharedMainContentJsonEnricher extends AbstractJsonEnricher<DocumentModel> {

    public static final String NAME = "isSharedMainContent";

    public IsSharedMainContentJsonEnricher() {
        super(NAME);
    }

    @Override
    public void write(JsonGenerator jg, DocumentModel document) throws IOException {
        jg.writeBooleanField(NAME, isSharedMainContent(document));
    }

    protected boolean isSharedMainContent(DocumentModel document) throws IOException {
        try (SessionWrapper wrapper = ctx.getSession(document)) {
            if (!wrapper.getSession().exists(document.getRef())) {
                return false;
            }
            Serializable mainContent = document.getPropertyValue(FILE_CONTENT_PROPERTY);
            if (mainContent == null) {
                return false;
            }
            var excapedBlobDigest = NXQL.escapeString(((Blob) mainContent).getDigest());
            var escapedDocId = NXQL.escapeString(document.getId());
            var query = String.format("SELECT * FROM Document WHERE ecm:uuid <> %s" //
                    + " AND (ecm:isVersion = 0 OR ecm:versionVersionableId <> %s)" //
                    + " AND file:content/digest = %s", escapedDocId, escapedDocId, excapedBlobDigest);
            // privilege because we may not have permission to see all documents referencing this blob
            return CoreInstance.doPrivileged(wrapper.getSession(),
                    (session) -> session.queryProjection(query, 1, 0).size() > 0);
        }
    }

}
