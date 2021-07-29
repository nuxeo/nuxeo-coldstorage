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

/**
 * @since 10.10
 */
public class ColdStorageConstants {

    public static final String COLD_STORAGE_FACET_NAME = "ColdStorage";

    public static final String FILE_CONTENT_PROPERTY = "file:content";

    public static final String COLD_STORAGE_CONTENT_PROPERTY = "coldstorage:coldContent";

    public static final String COLD_STORAGE_BEING_RETRIEVED_PROPERTY = "coldstorage:beingRetrieved";

    public static final String COLD_STORAGE_TO_BE_RESTORED_PROPERTY = "coldstorage:toBeRestored";

    public static final String GET_DOCUMENTS_TO_CHECK_QUERY = String.format(
            "SELECT * FROM Document, Relation WHERE %s = 1", COLD_STORAGE_BEING_RETRIEVED_PROPERTY);

    public static final String COLD_STORAGE_CONTENT_RESTORED_EVENT_NAME = "coldStorageContentRestored";

    public static final String COLD_STORAGE_CONTENT_TO_RESTORE_EVENT_NAME = "coldStorageContentToRestore";

    public static final String COLD_STORAGE_CONTENT_AVAILABLE_EVENT_NAME = "coldStorageContentAvailable";

    public static final String COLD_STORAGE_CONTENT_MOVED_EVENT_NAME = "coldStorageContentMoved";

    public static final String COLD_STORAGE_CONTENT_AVAILABLE_UNTIL_MAIL_TEMPLATE_KEY = "coldStorageAvailableUntil";

    public static final String COLD_STORAGE_CONTENT_AVAILABLE_NOTIFICATION_NAME = "ColdStorageContentAvailable";

    public static final String COLD_STORAGE_CONTENT_RESTORED_NOTIFICATION_NAME = "ColdStorageContentRestored";

    public static final String COLD_STORAGE_CONTENT_ARCHIVE_LOCATION_MAIL_TEMPLATE_KEY = "archiveLocation";

    public static final String COLD_STORAGE_NUMBER_OF_DAYS_OF_AVAILABILITY_PROPERTY_NAME = "nuxeo.coldstorage.numberOfDaysOfAvailability.value.default";

    // FIXME we have to define the versioning policy for this addon
    public static final String WRITE_COLD_STORAGE = "WriteColdStorage";

    public static final String COLD_STORAGE_CONTENT_DOWNLOADABLE_UNTIL = "coldstorage:downloadableUntil";

    public static final String COLD_STORAGE_CONTENT_STORAGE_CLASS_TO_UPDATED = "coldstorage:classStorageToUpdate";

    public static final String GET_COLDSTORAGE_DOCUMENTS_TO_CHECK_QUERY = String.format(
            "SELECT * FROM Document, Relation WHERE %s = 1", COLD_STORAGE_CONTENT_STORAGE_CLASS_TO_UPDATED);

    public static final String COLD_STORAGE_CONTENT_AVAILABLE_IN_COLDSTORAGE = "coldstorage:availableOnColdstorage";

    /**
     * Status about the cold storage content being retrieved or available.
     */
    public static class ColdStorageContentStatus {

        protected final int totalBeingRetrieved;

        protected final int totalAvailable;

        public ColdStorageContentStatus(int totalBeingRetrieved, int totalAvailable) {
            this.totalBeingRetrieved = totalBeingRetrieved;
            this.totalAvailable = totalAvailable;
        }

        public int getTotalBeingRetrieved() {
            return totalBeingRetrieved;
        }

        public int getTotalAvailable() {
            return totalAvailable;
        }
    }

}
