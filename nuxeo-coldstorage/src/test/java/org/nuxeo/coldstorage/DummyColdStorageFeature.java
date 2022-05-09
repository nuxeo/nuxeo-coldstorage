package org.nuxeo.coldstorage;

import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.RunnerFeature;

/**
 * @since 2021.0.0
 */
@Deploy("org.nuxeo.coldstorage.test:OSGI-INF/test-dummy-coldstorage-contrib.xml")
@Features(ColdStorageFeature.class)
public class DummyColdStorageFeature implements RunnerFeature {
}
