package org.nuxeo.coldstorage.blob.providers;

import static java.lang.Boolean.TRUE;
import static org.nuxeo.ecm.core.blob.KeyStrategy.VER_SEP;

import java.io.IOException;
import java.time.Instant;
import java.util.Date;

import org.nuxeo.ecm.blob.s3.S3BlobProvider;
import org.nuxeo.ecm.core.blob.BlobStatus;
import org.nuxeo.ecm.core.blob.ManagedBlob;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.model.GetObjectMetadataRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.StorageClass;

public class DummyS3BlobProvider extends S3BlobProvider {

    @Override
    public BlobStatus getStatus(ManagedBlob blob) throws IOException {
        String key = stripBlobKeyPrefix(blob.getKey());
        String objectKey;
        String versionId;
        int seppos = key.indexOf(VER_SEP);
        if (seppos < 0) {
            objectKey = key;
            versionId = null;
        } else {
            objectKey = key.substring(0, seppos);
            versionId = key.substring(seppos + 1);
        }
        String bucketKey = config.bucketPrefix + objectKey;
        GetObjectMetadataRequest request = new GetObjectMetadataRequest(config.bucketName, bucketKey, versionId);
        ObjectMetadata metadata;
        try {
            metadata = config.amazonS3.getObjectMetadata(request);
        } catch (AmazonServiceException e) {
            // if (S3BlobStore.isMissingKey(e)) {
            // // don't crash for a missing blob, even though it means the storage is corrupted
            // log.error("Failed to get information on blob: {}", key, e);
            // return new BlobStatus().withDownloadable(false);
            // }
            throw new IOException(e);
        }
        // storage class is null for STANDARD
        String storageClass = metadata.getStorageClass();
        if (StorageClass.Standard.toString().equals(storageClass)) {
            storageClass = null;
        }
        // x-amz-restore absent
        // x-amz-restore: ongoing-request="true"
        // x-amz-restore: ongoing-request="false", expiry-date="Fri, 23 Dec 2012 00:00:00 GMT"
        Boolean ongoingRestore = metadata.getOngoingRestore();
        boolean downloadable = storageClass == null ? !TRUE.equals(ongoingRestore)
                : (ongoingRestore != null && !TRUE.equals(ongoingRestore)
                        && metadata.getRestoreExpirationTime() != null);
        Date date = metadata.getRestoreExpirationTime();
        Instant downloadableUntil = date == null ? null : date.toInstant();
        return new BlobStatus().withStorageClass(storageClass)
                               .withDownloadable(downloadable)
                               .withDownloadableUntil(downloadableUntil);
    }
}
