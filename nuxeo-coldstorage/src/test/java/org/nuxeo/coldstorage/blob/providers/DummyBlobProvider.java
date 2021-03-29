package org.nuxeo.coldstorage.blob.providers;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.nuxeo.ecm.core.blob.BlobStatus;
import org.nuxeo.ecm.core.blob.ManagedBlob;

public class DummyBlobProvider extends org.nuxeo.ecm.core.DummyBlobProvider {

    /** @since 11.1 **/
    protected Map<String, BlobStatus> blobsStatus;

    @Override
    public void initialize(String blobProviderId, Map<String, String> properties) throws IOException {
        super.initialize(blobProviderId, properties);
        blobsStatus = new HashMap<>();
    }

    /** @since 11.1 **/
    @Override
    public BlobStatus getStatus(ManagedBlob blob) throws IOException {
        return blobsStatus.getOrDefault(getBlobKey(blob), super.getStatus(blob));
    }

    /** @since 11.1 **/
    public void addStatus(ManagedBlob blob, BlobStatus status) {
        blobsStatus.put(getBlobKey(blob), status);
    }

    /** @since 11.1 **/
    protected String getBlobKey(ManagedBlob blob) {
        int colon = blob.getKey().indexOf(':');
        return colon < 0 ? blob.getKey() : blob.getKey().substring(colon + 1);
    }
}
