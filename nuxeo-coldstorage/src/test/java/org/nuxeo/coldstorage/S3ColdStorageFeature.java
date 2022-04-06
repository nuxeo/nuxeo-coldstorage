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

import static com.amazonaws.SDKGlobalConfiguration.AWS_SESSION_TOKEN_ENV_VAR;
import static org.apache.commons.lang3.StringUtils.defaultIfBlank;
import static org.nuxeo.ecm.blob.s3.S3BlobStoreConfiguration.AWS_ID_PROPERTY;
import static org.nuxeo.ecm.blob.s3.S3BlobStoreConfiguration.AWS_SECRET_PROPERTY;
import static org.nuxeo.ecm.blob.s3.S3BlobStoreConfiguration.AWS_SESSION_TOKEN_PROPERTY;
import static org.nuxeo.ecm.blob.s3.S3BlobStoreConfiguration.BUCKET_NAME_PROPERTY;
import static org.nuxeo.ecm.blob.s3.S3BlobStoreConfiguration.BUCKET_PREFIX_PROPERTY;
import static org.nuxeo.ecm.blob.s3.S3BlobStoreConfiguration.BUCKET_REGION_PROPERTY;
import static org.nuxeo.ecm.blob.s3.S3BlobStoreConfiguration.SYSTEM_PROPERTY_PREFIX;

import org.nuxeo.ecm.core.test.CoreFeature;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.RunnerFeature;

import com.amazonaws.auth.AWSCredentials;

@Features({ S3ColdStorageFeature.SetPropertiesFeature.class, CoreFeature.class })
@Deploy("org.nuxeo.coldstorage.test:OSGI-INF/test-s3-coldstorage-contrib.xml")
public class S3ColdStorageFeature extends ColdStorageFeature {

    public static class SetPropertiesFeature implements RunnerFeature {

        protected S3TestHelper s3TestHelper = S3TestHelper.getInstance();

        @Override
        public void start(FeaturesRunner runner) {
            AWSCredentials credentials = s3TestHelper.getCredentials();
            setProperty(AWS_ID_PROPERTY, credentials.getAWSAccessKeyId());
            setProperty(AWS_SECRET_PROPERTY, credentials.getAWSSecretKey());
            String envSessionToken = defaultIfBlank(System.getenv(AWS_SESSION_TOKEN_ENV_VAR), "");
            setProperty(AWS_SESSION_TOKEN_PROPERTY, envSessionToken);
            setProperty(BUCKET_REGION_PROPERTY, s3TestHelper.getRegion());
            setProperty(BUCKET_NAME_PROPERTY, s3TestHelper.getMainBucket());
            setProperty(BUCKET_PREFIX_PROPERTY, s3TestHelper.getBucketPrefix());
        }

        public static void setProperty(String key, String value) {
            System.getProperties().put(SYSTEM_PROPERTY_PREFIX + '.' + key, value);
        }

        @Override
        public void afterTeardown(FeaturesRunner runner) throws Exception {
            s3TestHelper.clear();
        }
    }
}