package org.nuxeo.coldstorage.thumbnail;

import java.util.Random;

import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.platform.thumbnail.ThumbnailConstants;

/**
 * Generate random thumbnail each time is computed.
 *
 * @since 10.10
 */
public class DummyRandomThumbnailFactory extends DummyThumbnailFactory {

    /** @since 10.10 **/
    public static final String RANDOM_THUMBNAIL_BLOB_NAME = "randomlyComputed";

    @Override
    public Blob getThumbnail(DocumentModel doc, CoreSession session) {
        if (doc != null) {
            return (Blob) doc.getPropertyValue(ThumbnailConstants.THUMBNAIL_PROPERTY_NAME);
        }

        return null;
    }

    @Override
    public Blob computeThumbnail(DocumentModel doc, CoreSession session) {
        // Let recompute a random thumbnail for testing purposes
        byte[] bytes = new byte[new Random().nextInt(50) + 20];
        new Random().nextBytes(bytes);
        Blob blob = Blobs.createBlob(bytes);
        blob.setFilename(RANDOM_THUMBNAIL_BLOB_NAME);
        return blob;
    }
}
