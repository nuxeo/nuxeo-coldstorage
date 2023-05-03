/*
 * (C) Copyright 2020 Nuxeo (http://nuxeo.com/) and others.
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
 *     Salem Aouana
 */

package org.nuxeo.coldstorage;

import org.nuxeo.ecm.core.test.CoreFeature;
import org.nuxeo.ecm.platform.picture.core.ImagingFeature;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.LogCaptureFeature;
import org.nuxeo.runtime.test.runner.RunnerFeature;

/**
 * @since 2021.0.0
 */
@Features({CoreFeature.class, ImagingFeature.class, LogCaptureFeature.class})
@Deploy("org.nuxeo.ecm.platform.notification")
@Deploy("org.nuxeo.ecm.automation.core")
@Deploy("org.nuxeo.ecm.core.management")
@Deploy("org.nuxeo.ecm.platform.video")
@Deploy("org.nuxeo.ecm.platform.commandline.executor")
@Deploy("org.nuxeo.ecm.actions")
@Deploy("org.nuxeo.ecm.platform.tag")
@Deploy("org.nuxeo.ecm.platform.convert")
@Deploy("org.nuxeo.ecm.platform.thumbnail")
@Deploy("org.nuxeo.ecm.platform.rendition.core")
@Deploy("org.nuxeo.ecm.platform.convert")
@Deploy("org.nuxeo.coldstorage")
@Deploy("org.nuxeo.coldstorage.test")
public class ColdStorageFeature implements RunnerFeature {
}
