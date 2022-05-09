/*
 * (C) Copyright 2022 Nuxeo (http://nuxeo.com/) and others.
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
 *     Kevin Leturc <grenard@nuxeo.com>
 */
package org.nuxeo.coldstorage;

import static com.amazonaws.SDKGlobalConfiguration.ACCESS_KEY_ENV_VAR;
import static com.amazonaws.SDKGlobalConfiguration.ALTERNATE_ACCESS_KEY_ENV_VAR;
import static com.amazonaws.SDKGlobalConfiguration.ALTERNATE_SECRET_KEY_ENV_VAR;
import static com.amazonaws.SDKGlobalConfiguration.AWS_REGION_ENV_VAR;
import static com.amazonaws.SDKGlobalConfiguration.AWS_SESSION_TOKEN_ENV_VAR;
import static com.amazonaws.SDKGlobalConfiguration.SECRET_KEY_ENV_VAR;
import static org.apache.commons.lang3.ObjectUtils.getFirstNonNull;
import static org.apache.commons.lang3.StringUtils.isNoneBlank;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.net.URL;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.junit.runners.model.FrameworkMethod;
import org.nuxeo.ecm.blob.s3.S3BlobStoreConfiguration;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.blob.BlobManager;
import org.nuxeo.ecm.core.blob.BlobManagerFeature;
import org.nuxeo.ecm.core.blob.BlobStoreBlobProvider;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.model.URLStreamRef;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.RunnerFeature;
import org.nuxeo.runtime.test.runner.RuntimeFeature;
import org.nuxeo.runtime.test.runner.RuntimeHarness;
import org.osgi.framework.Bundle;

/**
 * TODO Remove when S3BlobProviderFeature is available in test jar.
 *
 * @since 2021.0.0
 */
@Features({ BlobManagerFeature.class, ColdStorageFeature.class })
public class S3ColdStorageBlobProviderFeature implements RunnerFeature {

    public static final String PREFIX_TEST = "nuxeo.test.s3storage.";

    public static final String PREFIX_PROVIDER_TEST = PREFIX_TEST + "provider.test.";

    // ---------------------------------
    // properties for all blob providers
    // ---------------------------------

    public static final String AWS_ID = PREFIX_TEST + S3BlobStoreConfiguration.AWS_ID_PROPERTY;

    public static final String AWS_SECRET = PREFIX_TEST + S3BlobStoreConfiguration.AWS_SECRET_PROPERTY;

    public static final String AWS_SESSION_TOKEN = PREFIX_TEST + S3BlobStoreConfiguration.AWS_SESSION_TOKEN_PROPERTY;

    public static final String BUCKET_REGION = PREFIX_TEST + S3BlobStoreConfiguration.BUCKET_REGION_PROPERTY;

    public static final String BUCKET = PREFIX_TEST + S3BlobStoreConfiguration.BUCKET_NAME_PROPERTY;

    // ----------------------------
    // properties by blob providers
    // ----------------------------

    public static final String PROVIDER_TEST_BUCKET = PREFIX_PROVIDER_TEST
            + S3BlobStoreConfiguration.BUCKET_NAME_PROPERTY;

    public static final String PROVIDER_TEST_BUCKET_PREFIX = PREFIX_PROVIDER_TEST
            + S3BlobStoreConfiguration.BUCKET_PREFIX_PROPERTY;

    public static final String DEFAULT_PROVIDER_TEST_BUCKET_PREFIX = "provider-test";

    @Override
    @SuppressWarnings("unchecked")
    public void start(FeaturesRunner runner) {
        // configure global blob provider properties
        var awsId = configureProperty(AWS_ID, sysEnv(ACCESS_KEY_ENV_VAR), sysEnv(ALTERNATE_ACCESS_KEY_ENV_VAR),
                sysProp(AWS_ID));
        var awsSecret = configureProperty(AWS_SECRET, sysEnv(SECRET_KEY_ENV_VAR), sysEnv(ALTERNATE_SECRET_KEY_ENV_VAR),
                sysProp(AWS_SECRET));
        // fall back on empty string to allow AWS credentials provider to generate credentials without session token
        configureProperty(AWS_SESSION_TOKEN, sysEnv(AWS_SESSION_TOKEN_ENV_VAR), sysProp(AWS_SESSION_TOKEN), () -> "");
        var awsRegion = configureProperty(BUCKET_REGION, sysEnv(AWS_REGION_ENV_VAR), sysProp(BUCKET_REGION));
        // configure specific blob provider properties
        var testBucket = configureProperty(PROVIDER_TEST_BUCKET, sysProp(PROVIDER_TEST_BUCKET), sysProp(BUCKET));
        configureProperty(PROVIDER_TEST_BUCKET_PREFIX, unique(sysProp(PROVIDER_TEST_BUCKET_PREFIX).get()),
                unique(DEFAULT_PROVIDER_TEST_BUCKET_PREFIX));
        // check if tests can run
        assumeTrue("AWS credentials, region and bucket are missing in test configuration",
                isNoneBlank(awsId, awsSecret, awsRegion, testBucket));
        // deploy the test bundle after the properties have been set
        try {
            RuntimeHarness harness = runner.getFeature(RuntimeFeature.class).getHarness();
            Bundle bundle = harness.getOSGiAdapter().getRegistry().getBundle("org.nuxeo.coldstorage.test");
            URL url = bundle.getEntry("OSGI-INF/test-s3-coldstorage-contrib.xml");
            harness.getContext().deploy(new URLStreamRef(url));
        } catch (IOException e) {
            throw new NuxeoException(e);
        }
    }

    protected Supplier<String> sysEnv(String key) {
        return () -> StringUtils.trimToNull(System.getenv(key));
    }

    protected Supplier<String> sysProp(String key) {
        return () -> StringUtils.trimToNull(System.getProperty(key));
    }

    protected Supplier<String> unique(String prefix) {
        return () -> prefix == null ? null : getUniqueBucketPrefix(prefix);
    }

    protected String configureProperty(String key, @SuppressWarnings("unchecked") Supplier<String>... suppliers) {
        String value = getFirstNonNull(suppliers);
        if (value != null) {
            Framework.getProperties().setProperty(key, value);
        }
        return value;
    }

    @Override
    public void beforeSetup(FeaturesRunner runner, FrameworkMethod method, Object test) {
        clearBlobStores();
    }

    @Override
    public void afterTeardown(FeaturesRunner runner, FrameworkMethod method, Object test) {
        clearBlobStores();
    }

    protected void clearBlobStores() {
        clearBlobStore("test");
    }

    protected void clearBlobStore(String blobProviderId) {
        var blobProvider = Framework.getService(BlobManager.class).getBlobProvider(blobProviderId);
        var blobStore = ((BlobStoreBlobProvider) blobProvider).store;
        blobStore.clear();
    }

    public static String getUniqueBucketPrefix(String prefix) {
        long timestamp = System.nanoTime();
        return String.format("%s-%s/", prefix, timestamp);
    }
}
