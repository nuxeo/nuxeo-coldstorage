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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.junit.runner.RunWith;
import org.nuxeo.coldstorage.ColdStorageConstants;
import org.nuxeo.coldstorage.ColdStorageFeature;
import org.nuxeo.coldstorage.ColdStorageHelper;
import org.nuxeo.ecm.automation.AutomationService;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.OperationException;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.security.ACE;
import org.nuxeo.ecm.core.api.security.ACL;
import org.nuxeo.ecm.core.api.security.ACP;
import org.nuxeo.ecm.core.blob.ManagedBlob;
import org.nuxeo.ecm.core.test.CoreFeature;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;

/**
 * @since 2021.0.0
 */
@RunWith(FeaturesRunner.class)
@Features(ColdStorageFeature.class)
@Deploy("org.nuxeo.ecm.automation.core")
@Deploy("org.nuxeo.ecm.automation.features")
public abstract class AbstractTestColdStorageOperation {

    protected static final String FILE_CONTENT = "foo and boo";

    @Inject
    protected AutomationService automationService;

    @Inject
    protected CoreSession session;

    @Inject
    protected CoreFeature coreFeature;

    @Inject
    protected TransactionalFeature transactionalFeature;

    protected DocumentModel moveContentToColdStorage(CoreSession session, DocumentModel documentModel)
            throws OperationException, IOException {
        try (OperationContext context = new OperationContext(session)) {
            context.setInput(documentModel);
            documentModel = (DocumentModel) automationService.run(context, MoveToColdStorage.ID);
            checkMoveContent(documentModel);
            return documentModel;
        }
    }

    protected void moveContentToColdStorage(CoreSession session, List<DocumentModel> documents)
            throws OperationException, IOException {
        try (OperationContext context = new OperationContext(session)) {
            context.setInput(documents);
            checkMoveContent(documents, (DocumentModelList) automationService.run(context, MoveToColdStorage.ID));
        }
    }

    protected void checkMoveContent(DocumentModel doc) throws IOException {
        // check document
        assertTrue(doc.hasFacet(ColdStorageConstants.COLD_STORAGE_FACET_NAME));

        // check blob
        Blob coldStorageContent = (Blob) doc.getPropertyValue(ColdStorageConstants.COLD_STORAGE_CONTENT_PROPERTY);
        assertNotNull(coldStorageContent);
        ColdStorageHelper.isInColdStorage((ManagedBlob) coldStorageContent);
    }

    protected void checkMoveContent(List<DocumentModel> expectedDocs, List<DocumentModel> actualDocs)
            throws IOException {
        assertEquals(expectedDocs.size(), actualDocs.size());
        List<String> expectedDocIds = expectedDocs.stream().map(DocumentModel::getId).collect(Collectors.toList());
        for (DocumentModel updatedDoc : actualDocs) {
            assertTrue(expectedDocIds.contains(updatedDoc.getId()));
            checkMoveContent(updatedDoc);
        }
    }

    protected DocumentModel createFileDocument(CoreSession session, boolean withBlobContent, ACE... aces) {
        return this.createFileDocument(session, "MyFile", withBlobContent, aces);
    }

    protected DocumentModel createFileDocument(CoreSession session, String name, boolean withBlobContent, ACE... aces) {
        DocumentModel documentModel = session.createDocumentModel("/", name, "File");
        if (withBlobContent) {
            Blob blob = Blobs.createBlob(FILE_CONTENT);
            blob.setDigest(UUID.randomUUID().toString());
            documentModel.setPropertyValue(ColdStorageConstants.FILE_CONTENT_PROPERTY, (Serializable) blob);
        }
        DocumentModel document = session.createDocument(documentModel);
        if (aces.length > 0) {
            ACP acp = documentModel.getACP();
            ACL acl = acp.getOrCreateACL();
            acl.addAll(List.of(aces));
            document.setACP(acp, true);
        }
        return document;
    }

}
