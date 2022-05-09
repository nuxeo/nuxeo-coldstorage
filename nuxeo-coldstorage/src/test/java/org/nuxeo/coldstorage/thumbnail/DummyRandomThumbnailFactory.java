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

package org.nuxeo.coldstorage.thumbnail;

import java.util.Random;

import org.nuxeo.ecm.core.DummyThumbnailFactory;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.platform.thumbnail.ThumbnailConstants;

/**
 * Generate random thumbnail each time is computed. The main purpose is for thumbnail recomputation testing (we don't
 * need to deploy more Nuxeo components than needed)
 *
 * @since 2021.0.0
 */
public class DummyRandomThumbnailFactory extends DummyThumbnailFactory  {

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
