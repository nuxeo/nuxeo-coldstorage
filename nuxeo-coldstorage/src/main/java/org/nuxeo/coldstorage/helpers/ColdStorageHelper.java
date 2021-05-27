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
 *     Nuno Cunha <ncunha@nuxeo.com>
 */

package org.nuxeo.coldstorage.helpers;

import static javax.servlet.http.HttpServletResponse.SC_CONFLICT;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

import java.io.IOException;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.blob.BlobManager;
import org.nuxeo.ecm.core.blob.BlobStatus;
import org.nuxeo.ecm.core.blob.BlobUpdateContext;
import org.nuxeo.ecm.core.blob.ManagedBlob;
import org.nuxeo.ecm.core.event.EventService;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.ecm.core.io.download.DownloadService;
import org.nuxeo.runtime.api.Framework;

/**
 * Manages the cold storage of the main content of a {@link DocumentModel}.
 *
 * @since 11.1
 */
public class ColdStorageHelper {

    private static final Logger log = LogManager.getLogger(ColdStorageHelper.class);

    public static final String COLD_STORAGE_FACET_NAME = "ColdStorage";

    public static final String FILE_CONTENT_PROPERTY = "file:content";

    public static final String COLD_STORAGE_CONTENT_PROPERTY = "coldstorage:coldContent";

    public static final String COLD_STORAGE_BEING_RETRIEVED_PROPERTY = "coldstorage:beingRetrieved";

    /** @since 10.10 **/
    public static final String COLD_STORAGE_TO_BE_RESTORED_PROPERTY = "coldstorage:toBeRestored";

    public static final String GET_DOCUMENTS_TO_CHECK_QUERY = String.format(
            "SELECT * FROM Document, Relation WHERE %s = 1", COLD_STORAGE_BEING_RETRIEVED_PROPERTY);

    /** @since 10.10 **/
    public static final String COLD_STORAGE_CONTENT_RESTORED_EVENT_NAME = "coldStorageContentRestored";

    /** @since 10.10 **/
    public static final String COLD_STORAGE_CONTENT_TO_RESTORE_EVENT_NAME = "coldStorageContentToRestore";

    public static final String COLD_STORAGE_CONTENT_AVAILABLE_EVENT_NAME = "coldStorageContentAvailable";

    public static final String COLD_STORAGE_CONTENT_AVAILABLE_UNTIL_MAIL_TEMPLATE_KEY = "coldStorageAvailableUntil";

    public static final String COLD_STORAGE_CONTENT_AVAILABLE_NOTIFICATION_NAME = "ColdStorageContentAvailable";

    /** @since 10.10 **/
    public static final String COLD_STORAGE_CONTENT_RESTORED_NOTIFICATION_NAME = "ColdStorageContentRestored";

    public static final String COLD_STORAGE_CONTENT_ARCHIVE_LOCATION_MAIL_TEMPLATE_KEY = "archiveLocation";

    /** @since 10.10 **/
    public static final String COLD_STORAGE_NUMBER_OF_DAYS_OF_AVAILABILITY_PROPERTY_NAME = "nuxeo.coldstorage.numberOfDaysOfAvailability.value.default";

    /**
     * Moves the main content associated with the document of the given {@link DocumentRef} to a cold storage.
     * <p/>
     * The permission {@value org.nuxeo.ecm.core.api.security.SecurityConstants#WRITE_COLD_STORAGE} is required.
     *
     * @return the updated document model if the move succeeds
     * @throws NuxeoException if the main content is already in the cold storage, if there is no main content
     *             associated with the given document, or if the user does not have the permissions needed to
     *             perform the action.
     */
    public static DocumentModel moveContentToColdStorage(CoreSession session, DocumentRef documentRef) {
        DocumentModel documentModel = session.getDocument(documentRef);
        log.debug("Move to cold storage the main content of document: {}", documentModel);

        if (!session.hasPermission(documentRef, SecurityConstants.WRITE_COLD_STORAGE)) {
            log.debug("The user {} does not have the right permissions to move the content of document {}",
                    session::getPrincipal, () -> documentModel);
            throw new NuxeoException(String.format("The document: %s cannot be moved to cold storage", documentRef),
                    SC_FORBIDDEN);
        }

        if (documentModel.hasFacet(COLD_STORAGE_FACET_NAME)
                && documentModel.getPropertyValue(COLD_STORAGE_CONTENT_PROPERTY) != null) {
            throw new NuxeoException(
                    String.format("The main content for document: %s is already in cold storage.", documentModel),
                    SC_CONFLICT);
        }

        Serializable mainContent = documentModel.getPropertyValue(FILE_CONTENT_PROPERTY);
        if (mainContent == null) {
            throw new NuxeoException(String.format("There is no main content for document: %s.", documentModel),
                    SC_NOT_FOUND);
        }

        documentModel.addFacet(COLD_STORAGE_FACET_NAME);
        documentModel.setPropertyValue(COLD_STORAGE_CONTENT_PROPERTY, mainContent);
        documentModel.setPropertyValue(FILE_CONTENT_PROPERTY, null);
        return documentModel;
    }

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
    public static DocumentModel requestRetrievalFromColdStorage(CoreSession session, DocumentRef documentRef,
            Duration restoreDuration) {
        Objects.requireNonNull(restoreDuration, "Restore duration is required");
        DocumentModel documentModel = session.getDocument(documentRef);
        log.debug("Retrieve from cold storage the content of document: {} for a duration: {}", documentModel,
                restoreDuration);

        if (!documentModel.hasFacet(COLD_STORAGE_FACET_NAME)
                || documentModel.getPropertyValue(COLD_STORAGE_CONTENT_PROPERTY) == null) {
            throw new NuxeoException(String.format("No cold storage content defined for document: %s.", documentModel),
                    SC_NOT_FOUND);
        }

        Serializable beingRetrieved = documentModel.getPropertyValue(COLD_STORAGE_BEING_RETRIEVED_PROPERTY);
        if (Boolean.TRUE.equals(beingRetrieved)) {
            throw new NuxeoException(
                    String.format("The cold storage content associated with the document: %s is being retrieved.",
                            documentModel),
                    SC_FORBIDDEN);
        }

        try {
            Blob coldContent = (Blob) documentModel.getPropertyValue(COLD_STORAGE_CONTENT_PROPERTY);
            String key = getContentBlobKey(coldContent);
            BlobUpdateContext updateContext = new BlobUpdateContext(key).withRestoreForDuration(restoreDuration);
            Framework.getService(BlobManager.class).getBlobProvider(coldContent).updateBlob(updateContext);
        } catch (IOException e) {
            throw new NuxeoException(e);
        }
        documentModel.setPropertyValue(COLD_STORAGE_BEING_RETRIEVED_PROPERTY, true);
        return documentModel;
    }

    /**
     * Restores the cold content associated with the document of the given {@link DocumentRef} into its main storage.
     * <p/>
     * The permission {@value org.nuxeo.ecm.core.api.security.SecurityConstants#WRITE_COLD_STORAGE} is required.
     *
     * @implSpec This method will rely on the {@link org.nuxeo.ecm.core.blob.BlobProvider#getStatus(ManagedBlob)} to
     *           check if the restore can be done, otherwise it will request a retrieval
     *           {@link #requestRetrievalFromColdStorage(CoreSession, DocumentRef, Duration)}
     * @return the updated document model if the restore succeeds
     * @throws NuxeoException if the cold content is already in the main storage, if there is no cold content associated
     *             with the given document, or if the user does not have the permissions needed to perform the action.
     * @since 10.10
     */
    public static DocumentModel restoreContentFromColdStorage(CoreSession session, DocumentRef documentRef) {
        DocumentModel documentModel = session.getDocument(documentRef);
        log.debug("Restore from cold storage the main content of document: {}", documentModel);

        if (!session.hasPermission(documentRef, SecurityConstants.WRITE_COLD_STORAGE)) {
            log.debug("The user {} does not have the right permissions to move the content of document",
                    session::getPrincipal);
            throw new NuxeoException(
                    String.format("The document: %s cannot be restored from cold storage", documentRef), SC_FORBIDDEN);
        }

        if (!documentModel.hasFacet(COLD_STORAGE_FACET_NAME)
                || documentModel.getPropertyValue(COLD_STORAGE_CONTENT_PROPERTY) == null) {
            throw new NuxeoException(
                    String.format("The cold content for document: %s isn't under cold storage.", documentModel),
                    SC_CONFLICT);
        }

        Serializable coldContent = documentModel.getPropertyValue(COLD_STORAGE_CONTENT_PROPERTY);
        if (coldContent == null) {
            throw new NuxeoException(String.format("There is no cold storage content for document: %s.", documentModel),
                    SC_NOT_FOUND);
        }

        Serializable beingRestore = documentModel.getPropertyValue(COLD_STORAGE_TO_BE_RESTORED_PROPERTY);
        if (Boolean.TRUE.equals(beingRestore)) {
            throw new NuxeoException(
                    String.format("The cold storage content associated with the document: %s is being restored.",
                            documentModel),
                    SC_CONFLICT);
        }

        documentModel.setPropertyValue(COLD_STORAGE_TO_BE_RESTORED_PROPERTY, true);
        session.saveDocument(documentModel);
        BlobStatus blobStatus = ColdStorageHelper.getBlobStatus(documentModel);
        if (blobStatus.isDownloadable()) {
            restoreMainContent(documentModel);
        } else {
            documentModel = requestRetrievalFromColdStorage(session, documentModel.getRef(),
                    Duration.ofDays(getNumberOfDaysOfAvailability()));
        }
        return documentModel;
    }

    /**
     * Gets the number of the days where the document's blob is available, once it's retrieved from cold storage.
     *
     * @return the number of days of availability if property
     *         {@value COLD_STORAGE_NUMBER_OF_DAYS_OF_AVAILABILITY_PROPERTY_NAME} is configured, {@code 1} otherwise.
     * @since 10.10
     */
    public static int getNumberOfDaysOfAvailability() {
        String value = Framework.getProperties()
                                .getProperty(COLD_STORAGE_NUMBER_OF_DAYS_OF_AVAILABILITY_PROPERTY_NAME, "1");
        return Integer.parseInt(value);
    }

    /**
     * Checks if the retrieved cold storage contents are available for download.
     *
     * @implSpec: Queries all documents with a cold storage content which are being retrieved, meaning
     *            {@value COLD_STORAGE_BEING_RETRIEVED_PROPERTY} is {@code true}, and it checks if it is available for
     *            download. In which case its fires a {@value COLD_STORAGE_CONTENT_AVAILABLE_EVENT_NAME} event.
     * @see #requestRetrievalFromColdStorage(CoreSession, DocumentRef, Duration)
     */
    public static ColdStorageContentStatus checkColdStorageContentAvailability(CoreSession session) {
        log.debug("Start checking the available cold storage content for repository: {}", session::getRepositoryName);

        // as the volume of result will be small, we don't use BAF
        DocumentModelList documents = session.query(GET_DOCUMENTS_TO_CHECK_QUERY);

        // for every available content we will fire an event
        int beingRetrieved = documents.size();
        int available = 0;
        EventService eventService = Framework.getService(EventService.class);
        DownloadService downloadService = Framework.getService(DownloadService.class);
        for (DocumentModel doc : documents) {
            BlobStatus blobStatus = getBlobStatus(doc);
            if (blobStatus == null) {
                continue;
            }

            if (blobStatus.isDownloadable()) {
                available++;
                beingRetrieved--;

                // Check if the Document should be restored definitively
                Serializable undoMove = doc.getPropertyValue(COLD_STORAGE_TO_BE_RESTORED_PROPERTY);
                if (Boolean.TRUE.equals(undoMove)) {
                    restoreMainContent(doc);
                } else {
                    doc.setPropertyValue(COLD_STORAGE_BEING_RETRIEVED_PROPERTY, false);
                    session.saveDocument(doc);

                    DocumentEventContext ctx = new DocumentEventContext(session, session.getPrincipal(), doc);
                    Instant downloadableUntil = blobStatus.getDownloadableUntil();
                    if (downloadableUntil != null) {
                        ctx.getProperties()
                           .put(COLD_STORAGE_CONTENT_AVAILABLE_UNTIL_MAIL_TEMPLATE_KEY, downloadableUntil.toString());
                    }
                    String downloadUrl = downloadService.getDownloadUrl(doc, COLD_STORAGE_CONTENT_PROPERTY, null);
                    ctx.getProperties().put(COLD_STORAGE_CONTENT_ARCHIVE_LOCATION_MAIL_TEMPLATE_KEY, downloadUrl);
                    eventService.fireEvent(ctx.newEvent(COLD_STORAGE_CONTENT_AVAILABLE_EVENT_NAME));
                }
            }
        }

        log.debug(
                "End checking the available cold storage content for repository: {}, beingRetrieved: {}, available: {}",
                session.getRepositoryName(), beingRetrieved, available);

        return new ColdStorageContentStatus(beingRetrieved, available);
    }

    /**
     * Restores the main content from ColdStorage.
     *
     * @implSpec: fires a {@value COLD_STORAGE_CONTENT_TO_RESTORE_EVENT_NAME} event.
     * @see #restoreContentFromColdStorage(CoreSession, DocumentRef)
     */
    protected static void restoreMainContent(DocumentModel documentModel) {
        CoreSession session = documentModel.getCoreSession();
        DocumentEventContext ctx = new DocumentEventContext(session, session.getPrincipal(), documentModel);
        EventService eventService = Framework.getService(EventService.class);
        eventService.fireEvent(ctx.newEvent(ColdStorageHelper.COLD_STORAGE_CONTENT_TO_RESTORE_EVENT_NAME));
    }

    protected static BlobStatus getBlobStatus(DocumentModel doc) {
        try {
            Blob coldContent = (Blob) doc.getPropertyValue(COLD_STORAGE_CONTENT_PROPERTY);
            return Framework.getService(BlobManager.class)
                                  .getBlobProvider(coldContent)
                                  .getStatus((ManagedBlob) coldContent);
        } catch (IOException e) {
            log.error("Unable to get the cold storage blob status for document: {}", doc, e);
            return null;
        }
    }

    protected static String getContentBlobKey(Blob coldContent) {
        String key = ((ManagedBlob) coldContent).getKey();
        int colon = key.indexOf(':');
        if (colon >= 0) {
            key = key.substring(colon + 1);
        }
        return key;
    }

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

    private ColdStorageHelper() {
        // no instance allowed
    }

}
