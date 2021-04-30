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

package org.nuxeo.coldstorage.operations;

import java.io.IOException;

import org.nuxeo.coldstorage.S3ColdStorageFeature;
import org.nuxeo.coldstorage.S3TestHelper;
import org.nuxeo.ecm.automation.OperationException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.runtime.test.runner.Features;

@Features(S3ColdStorageFeature.class)
public class S3RequestRetrievalFromColdStorageTest extends AbstractTestRequestRetrievalColdStorageOperation {

    protected S3TestHelper s3TestHelper = S3TestHelper.getInstance();

    @Override
    protected void moveContentToColdStorage(CoreSession session, DocumentModel documentModel)
            throws IOException, OperationException {
        super.moveContentToColdStorage(session, documentModel);
        // Mock AWS Lifecycle rule
        s3TestHelper.moveBlobContentToGlacier(session.getDocument(documentModel.getRef()));
    }
}
