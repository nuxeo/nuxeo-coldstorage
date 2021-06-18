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

package org.nuxeo.coldstorage;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.blob.s3.S3BlobProvider;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.blob.BlobManager;
import org.nuxeo.ecm.core.blob.ManagedBlob;
import org.nuxeo.runtime.api.Framework;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.CopyObjectRequest;
import com.amazonaws.services.s3.model.DeleteObjectsRequest;
import com.amazonaws.services.s3.model.DeleteObjectsResult;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.StorageClass;

public class S3TestHelper {

    private static final Logger log = LogManager.getLogger(S3TestHelper.class);

    protected static S3TestHelper INSTANCE;

    protected static final String AWS_MAIN_BUCKET_NAME_ENV_VAR = "COLDSTORAGE_AWS_MAIN_BUCKET_NAME";

    protected static final String AWS_GLACIER_BUCKET_NAME_ENV_VAR = "COLDSTORAGE_AWS_GLACIER_BUCKET_NAME";

    protected static final String AWS_BUCKET_PREFIX_ENV_VAR = "COLDSTORAGE_AWS_BUCKET_PREFIX";

    protected static final String AWS_REGION_ENV_VAR = "COLDSTORAGE_AWS_REGION";

    protected final AWSCredentialsProvider awsCredentialsProvider = new DefaultAWSCredentialsProviderChain();

    protected AWSCredentials credentials;

    protected String mainBucket;

    protected String glacierBucket;

    protected String region;

    protected String bucketPrefix;

    public static S3TestHelper getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new S3TestHelper();
        }
        return INSTANCE;
    }

    private S3TestHelper() {
        this.credentials = awsCredentialsProvider.getCredentials();

        log.info("ColdStorage integration tests will be done using AWS");

        this.mainBucket = Objects.requireNonNull(getEnvValue(AWS_MAIN_BUCKET_NAME_ENV_VAR),
                "Main bucket name is required");
        this.glacierBucket = Objects.requireNonNull(getEnvValue(AWS_GLACIER_BUCKET_NAME_ENV_VAR),
                "Glacier bucket name is required");
        this.bucketPrefix = Objects.requireNonNull(getEnvValue(AWS_BUCKET_PREFIX_ENV_VAR), "Bucket prefix is required");
        this.region = Objects.requireNonNull(getEnvValue(AWS_REGION_ENV_VAR), "Region is required");
    }

    public AWSCredentials getCredentials() {
        return credentials;
    }

    public void clear() {
        // Remove the content from the Glacier bucket
        clearBucket("glacier", getGlacierBucket(), getBucketPrefix());
        // Remove the content from the Main bucket
        clearBucket("s3", getMainBucket(), getBucketPrefix());
    }

    /**
     * Clears bucket.
     *
     * @param blobName the blob name
     * @param bucketName the bucket name
     * @param bucketPrefix the bucket prefix
     */
    public void clearBucket(String blobName, String bucketName, String bucketPrefix) {
        AmazonS3 amazonS3 = getAmazonS3(blobName);
        ObjectListing s3Objects = amazonS3.listObjects(bucketName, bucketPrefix);
        List<DeleteObjectsRequest.KeyVersion> keys = s3Objects.getObjectSummaries()
                                                              .stream()
                                                              .map(key -> new DeleteObjectsRequest.KeyVersion(
                                                                      key.getKey()))
                                                              .collect(Collectors.toList());
        if (keys.size() > 0) {
            DeleteObjectsRequest multiObjectDeleteRequest = new DeleteObjectsRequest(bucketName).withKeys(keys)
                                                                                                .withQuiet(false);
            DeleteObjectsResult deleteObjectsResult = amazonS3.deleteObjects(multiObjectDeleteRequest);
            int successfulDeletes = deleteObjectsResult.getDeletedObjects().size();
            log.info(successfulDeletes + " objects successfully deleted.");
        }
    }

    public AmazonS3 getAmazonS3(String provider) {
        S3BlobProvider s3BlobProvider = (S3BlobProvider) Framework.getService(BlobManager.class)
                                                                  .getBlobProvider(provider);
        return s3BlobProvider.config.amazonS3;
    }

    public String getMainBucket() {
        return mainBucket;
    }

    public String getGlacierBucket() {
        return glacierBucket;
    }

    public String getRegion() {
        return region;
    }

    public String getBucketPrefix() {
        return bucketPrefix;
    }

    protected static String getEnvValue(String key) {
        return StringUtils.trim(System.getenv(key));
    }

    /**
     * Moves the document's blob content to Glacier
     * <p/>
     * In a real use case we are relying on {@code AWS life cycle transition} to change the storage class from
     * {@link StorageClass#Standard} to {@link StorageClass#Glacier}, once the document is moved. However, this
     * transition might takes more than 24 hours to be achieved. Therefore and for testing purpose we make this
     * transition manually by changing the storage class
     *
     * @see <a href=
     *      "https://docs.aws.amazon.com/AmazonS3/latest/userguide/lifecycle-transition-general-considerations.html">Transitioning
     *      objects using Amazon S3 Lifecycle</a>
     * @param document the document model
     */
    public void moveBlobContentToGlacier(DocumentModel document) {
        AmazonS3 amazonS3 = getAmazonS3("glacier");
        String blobKey = getBlobKey(document, ColdStorageConstants.COLD_STORAGE_CONTENT_PROPERTY);
        log.info("ColdStorage Blob key {}", blobKey);
        CopyObjectRequest copyObjectRequest = new CopyObjectRequest(glacierBucket, blobKey, getGlacierBucket(),
                blobKey).withStorageClass(StorageClass.Glacier);
        if (amazonS3.doesObjectExist(glacierBucket, blobKey)) {
            amazonS3.copyObject(copyObjectRequest);
        } else {
            log.info("ColdStorage Blob key {} doesn't exist", blobKey);
        }

    }

    /**
     * Checks if the document's S3 blob is being retrieved.
     *
     * @param document the document
     * @return {@code true} if the blob's document is being retrieved from {@code Glacier} storage, {@code false}
     *         otherwise
     */
    public boolean isBlobContentBeingRetrieved(DocumentModel document) {
        boolean restoreFlag = false;
        AmazonS3 amazonS3 = getAmazonS3("glacier");
        String blobKey = getBlobKey(document, ColdStorageConstants.COLD_STORAGE_CONTENT_PROPERTY);
        log.info("ColdStorage Blob key {}", blobKey);
        if (amazonS3.doesObjectExist(glacierBucket, blobKey)) {
            ObjectMetadata response = amazonS3.getObjectMetadata(glacierBucket, blobKey);
            restoreFlag = response.getOngoingRestore();
        } else {
            log.info("ColdStorage Blob key {} doesn't exist", blobKey);
        }
        return restoreFlag;
    }

    public String getBlobKey(DocumentModel document, String blobProperty) {
        ManagedBlob blob = (ManagedBlob) document.getPropertyValue(blobProperty);
        if (blob == null) {
            return "";
        }
        String key = blob.getKey();
        // remove blob provider id prefix (for dispatch)
        int colon = key.indexOf(':');
        if (colon >= 0) {
            key = key.substring(colon + 1);
        }
        // remove version id (when using retention)
        int ver = key.indexOf('@');
        if (ver >= 0) {
            key = key.substring(0, ver);
        }
        if (StringUtils.isNotBlank(bucketPrefix)) {
            return bucketPrefix + "/" + key;
        }
        return key;
    }
}
