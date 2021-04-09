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

package org.nuxeo.coldstorage.thumbnail;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.thumbnail.ThumbnailFactory;

public class DummyThumbnailFactory implements ThumbnailFactory {

    public static final String DUMMY_THUMBNAIL_CONTENT = "Lorem Ipsum is simply dummy text of the printing and typesetting industry.";

    @Override
    public Blob getThumbnail(final DocumentModel doc, final CoreSession session) {
        return Blobs.createBlob(DUMMY_THUMBNAIL_CONTENT);
    }

    @Override
    public Blob computeThumbnail(final DocumentModel doc, final CoreSession session) {
        return null;
    }
}