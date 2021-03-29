package org.nuxeo.coldstorage;

import org.nuxeo.runtime.test.runner.Deploy;

/**
 * @since 10.10
 */
@Deploy("org.nuxeo.coldstorage.test:OSGI-INF/test-dummy-coldstorage-contrib.xml")
public class DummyColdStorageFeature extends ColdStorageFeature {
}
