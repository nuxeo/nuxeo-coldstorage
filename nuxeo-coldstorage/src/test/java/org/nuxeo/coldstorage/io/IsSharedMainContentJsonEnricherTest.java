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

import java.io.Serializable;
import java.util.List;

import jakarta.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.VersioningOption;
import org.nuxeo.ecm.core.api.security.ACE;
import org.nuxeo.ecm.core.api.security.ACL;
import org.nuxeo.ecm.core.api.security.ACP;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.api.versioning.VersioningService;
import org.nuxeo.ecm.core.io.marshallers.json.AbstractJsonWriterTest;
import org.nuxeo.ecm.core.io.marshallers.json.JsonAssert;
import org.nuxeo.ecm.core.io.marshallers.json.document.DocumentModelJsonWriter;
import org.nuxeo.ecm.core.io.registry.context.RenderingContext.CtxBuilder;
import org.nuxeo.ecm.core.test.CoreFeature;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;

/**
 * @since 2023.4
 */
@RunWith(FeaturesRunner.class)
@Features(CoreFeature.class)
@Deploy("org.nuxeo.coldstorage:OSGI-INF/coldstorage-enrichers-contrib.xml")
public class IsSharedMainContentJsonEnricherTest
        extends AbstractJsonWriterTest.Local<DocumentModelJsonWriter, DocumentModel> {

    @Inject
    private CoreSession session;

    public IsSharedMainContentJsonEnricherTest() {
        super(DocumentModelJsonWriter.class, DocumentModel.class);
    }

    public DocumentModel createDocument(String content, ACE... aces) {
        DocumentModel doc = session.createDocumentModel("/", "doc-" + System.currentTimeMillis(), "File");
        doc.setPropertyValue("file:content", (Serializable) Blobs.createBlob(content));
        DocumentModel document = session.createDocument(doc);
        if (aces.length > 0) {
            ACP acp = doc.getACP();
            ACL acl = acp.getOrCreateACL();
            acl.addAll(List.of(aces));
            document.setACP(acp, true);
        }
        return document;
    }

    @Test
    public void testEnricher() throws Exception {
        DocumentModel doc1 = createDocument("content1");
        DocumentModel doc2 = createDocument("content1", new ACE("john", SecurityConstants.READ, true));
        DocumentModel doc3 = createDocument("content2");
        DocumentModel doc4 = createDocument("content3");
        doc4.setPropertyValue("dc:title", "doc4Version");
        doc4.putContextData(VersioningService.VERSIONING_OPTION, VersioningOption.valueOf("MINOR"));
        doc4 = session.saveDocument(doc4);
        session.save();

        JsonAssert json = jsonAssert(doc1, CtxBuilder.enrichDoc(IsSharedMainContentJsonEnricher.NAME).get());
        json = json.has("contextParameters").isObject();
        json.properties(1);
        json = json.has(IsSharedMainContentJsonEnricher.NAME).isBool();
        // content is referenced by doc2 too
        json.isEquals(true);

        // Let's use another user who can't see doc1
        CoreSession johnSession = CoreInstance.getCoreSession(doc2.getRepositoryName(), "john");
        doc2 = johnSession.getDocument(doc2.getRef());
        json = jsonAssert(doc2, CtxBuilder.enrichDoc(IsSharedMainContentJsonEnricher.NAME).get());
        json = json.has("contextParameters").isObject();
        json.properties(1);
        json = json.has(IsSharedMainContentJsonEnricher.NAME).isBool();
        // content is referenced by doc1 too
        json.isEquals(true);

        json = jsonAssert(doc3, CtxBuilder.enrichDoc(IsSharedMainContentJsonEnricher.NAME).get());
        json = json.has("contextParameters").isObject();
        json.properties(1);
        json = json.has(IsSharedMainContentJsonEnricher.NAME).isBool();
        // content is referenced by doc3 only
        json.isEquals(false);

        json = jsonAssert(doc4, CtxBuilder.enrichDoc(IsSharedMainContentJsonEnricher.NAME).get());
        json = json.has("contextParameters").isObject();
        json.properties(1);
        json = json.has(IsSharedMainContentJsonEnricher.NAME).isBool();
        // content is referenced by doc4 versions only
        json.isEquals(false);
    }

}
