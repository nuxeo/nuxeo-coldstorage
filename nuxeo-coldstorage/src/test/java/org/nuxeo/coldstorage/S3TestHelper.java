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

import java.io.IOException;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.blob.BlobManager;
import org.nuxeo.ecm.core.blob.BlobProvider;
import org.nuxeo.ecm.core.blob.BlobStore;
import org.nuxeo.ecm.core.blob.BlobStoreBlobProvider;
import org.nuxeo.runtime.api.Framework;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;

public class S3TestHelper {

    private static final Logger log = LogManager.getLogger(S3TestHelper.class);

    protected static S3TestHelper INSTANCE;

    protected static final String AWS_MAIN_BUCKET_NAME_ENV_VAR = "COLDSTORAGE_AWS_MAIN_BUCKET_NAME";

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
        this.bucketPrefix = Objects.requireNonNull(getEnvValue(AWS_BUCKET_PREFIX_ENV_VAR), "Bucket prefix is required");
        this.region = Objects.requireNonNull(getEnvValue(AWS_REGION_ENV_VAR), "Region is required");
    }

    public AWSCredentials getCredentials() {
        return credentials;
    }

    public void clear() throws IOException {
        BlobProvider blobProvider = Framework.getService(BlobManager.class).getBlobProvider("coldStorage");
        BlobStore blobStore = ((BlobStoreBlobProvider) blobProvider).store;
        blobStore.clear();
    }

    public String getMainBucket() {
        return mainBucket;
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

}