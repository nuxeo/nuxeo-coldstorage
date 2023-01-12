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

package org.nuxeo.coldstorage.service;

import java.time.Duration;

import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.blob.ManagedBlob;

/**
 * @since 2021.0.0
 */
public interface ColdStorageService {

    /**
     * Gets the number of days where the document's blob is available, once it's retrieved from cold storage.
     *
     * @return the number of days of availability if property
     *         {@value org.nuxeo.coldstorage.ColdStorageConstants#COLD_STORAGE_NUMBER_OF_DAYS_OF_AVAILABILITY_PROPERTY_NAME}
     *         is configured, {@code 1} otherwise.
     */
    Duration getAvailabilityDuration();

    /**
     * Return the ColStorage rendition blob for the given {@link DocumentModel}.
     * <p>
     * A rendition blob is returned if found.
     * @param documentModel the document to render
     *
     * @return the {@link Blob} object
     * @throws NuxeoException if the rendition doesn't exist.
     */
    Blob getRendition(CoreSession session, DocumentModel documentModel);

    /**
     * Moves the main content associated with the document of the given {@link DocumentRef} to a cold storage.
     * <p/>
     * The permission {@value org.nuxeo.ecm.core.api.security.SecurityConstants#WRITE_COLD_STORAGE} is required.
     * <p/>
     * All documents referencing the same main content will also be moved to cold storage.
     *
     * @implSpec moves the main content to ColdStorage and fires an
     *           {@value org.nuxeo.coldstorage.ColdStorageConstants#COLD_STORAGE_CONTENT_MOVED_EVENT_NAME} event.
     * @return the updated document model if the move succeeds
     * @throws NuxeoException if the main content is already in the cold storage, if there is no main content associated
     *             with the given document, or if the user does not have the permissions needed to perform the action.
     */
    DocumentModel moveToColdStorage(CoreSession session, DocumentRef documentRef);

    /**
     * Requests a retrieval of the cold storage content associated with the document of the given {@link DocumentRef}.
     *
     * @param session the core session
     * @param documentRef the document reference
     * @param restoreDuration the duration that you want your cold storage content to be accessible after restoring it
     * @apiNote This method will initiate a restoration request, calling the {@link Blob#getStream()} during this
     *          process doesn't mean you will get the blob's content.
     * @return the updated document model if the retrieve succeeds
     * @throws NullPointerException if the {@code restoreDuration} parameter is {@code null}
     * @throws NuxeoException if there is no cold storage content associated with the given document, or if it is being
     *             retrieved
     */
    DocumentModel retrieveFromColdStorage(CoreSession session, DocumentRef documentRef, Duration restoreDuration);

    /**
     * Restores the cold content associated with the document of the given {@link DocumentRef} into its main storage.
     * <p/>
     * The permission {@value org.nuxeo.ecm.core.api.security.SecurityConstants#WRITE_COLD_STORAGE} is required.
     *
     * @implSpec This method will rely on the {@link org.nuxeo.ecm.core.blob.BlobProvider#getStatus(ManagedBlob)} to
     *           check if the restore can be done, otherwise it will request a retrieval
     *           {@link #retrieveFromColdStorage(CoreSession, DocumentRef, Duration)}
     * @return the updated document model if the restore succeeds
     * @throws NuxeoException if the cold content is already in the main storage, if there is no cold content associated
     *             with the given document, or if the user does not have the permissions needed to perform the action.
     */
    DocumentModel restoreFromColdStorage(CoreSession session, DocumentRef documentRef);

    /**
     * Checks if the main content is ready for download.
     *
     * @implSpec: Checks document with a cold storage content which is being retrieved, meaning
     *            {@value org.nuxeo.coldstorage.ColdStorageConstants#COLD_STORAGE_BEING_RETRIEVED_PROPERTY} is
     *            {@code true}, and it checks if it is available for download. In which case its fires a
     *            {@value org.nuxeo.coldstorage.ColdStorageConstants#COLD_STORAGE_CONTENT_AVAILABLE_EVENT_NAME} event.
     * @see #retrieveFromColdStorage(CoreSession, DocumentRef, Duration)
     */
    boolean checkIsRetrieved(CoreSession session, DocumentModel documentModel);

    /**
     * Internal use.
     */
    DocumentModel proceedRestoreMainContent(CoreSession session, DocumentModel documentModel, boolean notify);

    /**
     * Internal use.
     */
    DocumentModel proceedRestoreMainContent(CoreSession session, DocumentModel documentModel, boolean notify,
            boolean propagate);

    /**
     * Internal use.
     */
    void checkDocToBeRetrieved(CoreSession session);

    /**
     * Internal use.
     */
    DocumentModel proceedMoveToColdStorage(CoreSession session, DocumentRef documentRef);

}
