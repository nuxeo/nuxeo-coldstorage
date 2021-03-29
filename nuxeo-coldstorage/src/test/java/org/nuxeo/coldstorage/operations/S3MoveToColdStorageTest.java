package org.nuxeo.coldstorage.operations;

import java.io.IOException;

import org.nuxeo.coldstorage.S3ColdStorageFeature;
import org.nuxeo.coldstorage.S3TestHelper;
import org.nuxeo.ecm.automation.OperationException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;

@Features(S3ColdStorageFeature.class)
@Deploy("org.nuxeo.coldstorage.test:OSGI-INF/test-s3-coldstorage-contrib.xml")
public class S3MoveToColdStorageTest extends DummyMoveToColdStorageTest {

    protected S3TestHelper s3TestHelper = S3TestHelper.getInstance();

    @Override
    protected void moveContentToColdStorage(CoreSession session, DocumentModel documentModel)
            throws IOException, OperationException {
        super.moveContentToColdStorage(session, documentModel);
        // Mock AWS Lifecycle rule
        s3TestHelper.moveBlobContentToGlacier(session.getDocument(documentModel.getRef()));
    }
}
