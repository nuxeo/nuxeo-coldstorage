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

import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.nuxeo.ecm.core.api.security.SecurityConstants.READ;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.security.auth.login.LoginException;

import org.junit.Test;
import org.nuxeo.coldstorage.ColdStorageConstants;
import org.nuxeo.coldstorage.DummyColdStorageFeature;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.OperationException;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.security.ACE;
import org.nuxeo.ecm.core.api.security.ACL;
import org.nuxeo.ecm.core.api.security.ACP;
import org.nuxeo.ecm.platform.ec.notification.NotificationConstants;
import org.nuxeo.ecm.platform.notification.api.NotificationManager;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.api.login.NuxeoLoginContext;
import org.nuxeo.runtime.test.runner.Features;

/**
 * @since 2021.0.0
 */
@Features(DummyColdStorageFeature.class)
public class RequestRetrievalFromColdStorageTest extends AbstractTestColdStorageOperation {

    protected static final int NUMBER_OF_DAYS_OF_AVAILABILITY = 5;

    public static final String READ_USER = "ReadUser";

    @Inject
    protected CoreSession session;

    @Inject
    protected NotificationManager notificationManager;

    @Test
    public void shouldRequestRetrieval() throws OperationException, IOException {
        DocumentModel documentModel = createFileDocument(session, true);
        // first make the move to cold storage
        documentModel = moveContentToColdStorage(session, documentModel);
        // request a retrieval from the cold storage
        requestRetrievalContentFromColdStorage(documentModel, session);
    }

    @Test
    public void shouldRequestRetrievalWithReadAccess() throws OperationException, IOException, LoginException {
        DocumentModel documentModel = createFileDocument(session, true);
        // first make the move to cold storage
        documentModel = moveContentToColdStorage(session, documentModel);
        // Let's give READ access to a user
        ACP acp = documentModel.getACP();
        ACL acl = acp.getOrCreateACL();
        acl.add(new ACE(READ_USER, READ, true));
        session.setACP(documentModel.getRef(), acp, false);
        session.save();
        // request a retrieval from the cold storage with READ access
        try (NuxeoLoginContext ignored = Framework.loginUser(READ_USER)) {
            CoreSession readSession = CoreInstance.getCoreSession(session.getRepositoryName());
            requestRetrievalContentFromColdStorage(documentModel, readSession);
        }
    }

    @Test
    public void shouldRequestRetrievalWithDefaultValue() throws OperationException, IOException {
        DocumentModel documentModel = createFileDocument(session, true);
        // first make the move to cold storage
        documentModel = moveContentToColdStorage(session, documentModel);
        // request a retrieval from the cold storage
        requestRetrievalContentFromColdStorage(documentModel, session);
    }

    @Test
    public void shouldFailRequestRetrievalBeingRetrieved() throws IOException, OperationException {
        DocumentModel documentModel = createFileDocument(session, true);

        // move the blob to cold storage
        documentModel = moveContentToColdStorage(session, documentModel);
        // request a retrieval from the cold storage
        documentModel = requestRetrievalContentFromColdStorage(documentModel, session, NUMBER_OF_DAYS_OF_AVAILABILITY);

        // request a retrieval for a second time
        try {
            requestRetrievalContentFromColdStorage(documentModel, session, NUMBER_OF_DAYS_OF_AVAILABILITY);
            fail("Should fail because the cold storage content is being retrieved.");
        } catch (NuxeoException e) {
            assertEquals(SC_FORBIDDEN, e.getStatusCode());
        }
    }

    @Test
    public void shouldFailRequestRetrievalNoContent() throws OperationException {
        DocumentModel documentModel = createFileDocument(session, true);
        try {
            // request a retrieval from the cold storage
            requestRetrievalContentFromColdStorage(documentModel, session, NUMBER_OF_DAYS_OF_AVAILABILITY);
            fail("Should fail because there no cold storage content associated to this document.");
        } catch (NuxeoException e) {
            assertEquals(SC_NOT_FOUND, e.getStatusCode());
        }
    }

    protected DocumentModel requestRetrievalContentFromColdStorage(DocumentModel documentModel, CoreSession userSession)
            throws OperationException {
        return requestRetrievalContentFromColdStorage(documentModel, userSession, 0);
    }

    protected DocumentModel requestRetrievalContentFromColdStorage(DocumentModel documentModel, CoreSession userSession,
            int numberOfAvailabilityDays) throws OperationException {
        try (OperationContext context = new OperationContext(userSession)) {
            context.setInput(documentModel);
            Map<String, Integer> params = new HashMap<>();
            params.put("numberOfDaysOfAvailability", numberOfAvailabilityDays);
            DocumentModel updatedDocument = (DocumentModel) automationService.run(context,
                    RequestRetrievalFromColdStorage.ID, params);
            assertEquals(documentModel.getRef(), updatedDocument.getRef());
            assertEquals(Boolean.TRUE,
                    updatedDocument.getPropertyValue(ColdStorageConstants.COLD_STORAGE_BEING_RETRIEVED_PROPERTY));
            String username = NotificationConstants.USER_PREFIX + userSession.getPrincipal().getName();
            List<String> subscriptions = notificationManager.getSubscriptionsForUserOnDocument(username,
                    updatedDocument);
            assertTrue(subscriptions.contains(ColdStorageConstants.COLD_STORAGE_CONTENT_AVAILABLE_NOTIFICATION_NAME));
            return updatedDocument;
        }
    }
}
