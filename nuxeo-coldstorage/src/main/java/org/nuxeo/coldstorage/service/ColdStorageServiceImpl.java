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
 *     BA Abdoul <abdoul.ba@nuxeo.com>
 */

package org.nuxeo.coldstorage.service;

import static javax.servlet.http.HttpServletResponse.SC_CONFLICT;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static javax.servlet.http.HttpServletResponse.SC_PRECONDITION_FAILED;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_BEING_RETRIEVED_PROPERTY;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_ARCHIVE_LOCATION_MAIL_TEMPLATE_KEY;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_AVAILABLE_EVENT_NAME;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_AVAILABLE_NOTIFICATION_NAME;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_AVAILABLE_UNTIL_MAIL_TEMPLATE_KEY;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_DOWNLOADABLE_UNTIL;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_MOVED_EVENT_NAME;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_PROPERTY;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_RESTORED_EVENT_NAME;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_RESTORED_NOTIFICATION_NAME;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_TO_RESTORE_EVENT_NAME;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_TO_RETRIEVE_EVENT_NAME;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_FACET_NAME;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_NUMBER_OF_DAYS_OF_AVAILABILITY_PROPERTY_NAME;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_THUMBNAIL_PREVIEW_REQUIRED_PROPERTY_NAME;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_TO_BE_RESTORED_PROPERTY;
import static org.nuxeo.coldstorage.ColdStorageConstants.EVENT_CATEGORY;
import static org.nuxeo.coldstorage.ColdStorageConstants.EVENT_CATEGORY_LABEL;
import static org.nuxeo.coldstorage.ColdStorageConstants.FILE_CONTENT_PROPERTY;
import static org.nuxeo.coldstorage.ColdStorageConstants.GET_DOCUMENTS_TO_CHECK_QUERY;
import static org.nuxeo.coldstorage.events.CheckAlreadyInColdStorageListener.DISABLE_CHECK_ALREADY_IN_COLD_STORAGE_LISTENER;
import static org.nuxeo.coldstorage.events.PreventColdStorageUpdateListener.DISABLE_PREVENT_COLD_STORAGE_UPDATE_LISTENER;
import static org.nuxeo.ecm.core.api.CoreSession.ALLOW_VERSION_WRITE;
import static org.nuxeo.ecm.core.api.versioning.VersioningService.DISABLE_AUTOMATIC_VERSIONING;

import java.io.IOException;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.coldstorage.ColdStorageHelper;
import org.nuxeo.coldstorage.ColdStorageRenditionDescriptor;
import org.nuxeo.coldstorage.action.CheckColdStorageAvailabilityAction;
import org.nuxeo.coldstorage.action.PropagateMoveToColdStorageContentAction;
import org.nuxeo.coldstorage.action.PropagateRestoreFromColdStorageContentAction;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.DocumentSecurityException;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.core.api.event.CoreEventConstants;
import org.nuxeo.ecm.core.api.impl.DownloadBlobGuard;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.blob.BlobManager;
import org.nuxeo.ecm.core.blob.BlobStatus;
import org.nuxeo.ecm.core.blob.BlobUpdateContext;
import org.nuxeo.ecm.core.blob.ManagedBlob;
import org.nuxeo.ecm.core.bulk.BulkService;
import org.nuxeo.ecm.core.bulk.message.BulkCommand;
import org.nuxeo.ecm.core.bulk.message.BulkStatus;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventService;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.ecm.core.io.download.DownloadService;
import org.nuxeo.ecm.directory.Session;
import org.nuxeo.ecm.directory.api.DirectoryService;
import org.nuxeo.ecm.platform.ec.notification.NotificationConstants;
import org.nuxeo.ecm.platform.ec.notification.service.NotificationServiceHelper;
import org.nuxeo.ecm.platform.notification.api.NotificationManager;
import org.nuxeo.ecm.platform.picture.listener.PictureViewsGenerationListener;
import org.nuxeo.ecm.platform.rendition.Rendition;
import org.nuxeo.ecm.platform.rendition.service.RenditionService;
import org.nuxeo.ecm.platform.thumbnail.ThumbnailConstants;
import org.nuxeo.ecm.platform.thumbnail.listener.UpdateThumbnailListener;
import org.nuxeo.ecm.platform.video.listener.VideoChangedListener;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.model.ComponentContext;
import org.nuxeo.runtime.model.DefaultComponent;

/**
 * Default implementation of {@link ColdStorageService}.
 *
 * @since 2021.0.0
 */
public class ColdStorageServiceImpl extends DefaultComponent implements ColdStorageService {

    private static final Logger log = LogManager.getLogger(ColdStorageServiceImpl.class);

    protected static final List<String> COLD_STORAGE_DISABLED_RECOMPUTATION_LISTENERS = Arrays.asList(
            UpdateThumbnailListener.THUMBNAIL_UPDATED, ThumbnailConstants.DISABLE_THUMBNAIL_COMPUTATION,
            VideoChangedListener.DISABLE_VIDEO_CONVERSIONS_GENERATION_LISTENER,
            PictureViewsGenerationListener.DISABLE_PICTURE_VIEWS_GENERATION_LISTENER);

    public static final String COLDSTORAGE_RENDITION_EP = "coldStorageRendition";

    protected String defaultRendition;

    protected Map<String, String> renditionByDocType;

    protected Map<String, String> renditionByFacets;

    public ColdStorageServiceImpl() {
        // no instance allowed
    }

    @Override
    public void start(ComponentContext context) {
        // init rendition by doc/facet
        renditionByDocType = new HashMap<>();
        renditionByFacets = new HashMap<>();
        List<ColdStorageRenditionDescriptor> descriptors = getDescriptors(COLDSTORAGE_RENDITION_EP);
        descriptors.forEach(descriptor -> {
            String docType = descriptor.getDocType();
            String renditionName = descriptor.getRenditionName();
            if (docType != null) {
                renditionByDocType.put(docType, renditionName);
            }
            String facet = descriptor.getFacet();
            if (facet != null) {
                renditionByFacets.put(facet, renditionName);
            }
            if (docType == null && facet == null) {
                defaultRendition = renditionName;
                if (defaultRendition == null) {
                    throw new NuxeoException(
                            String.format("Please contribute a default rendition name: %s", descriptor.getName()));
                }
            }
        });
        // Let's add cold storage event category in appropriate directory.
        Framework.doPrivileged(() -> {
            DirectoryService directoryService = Framework.getService(DirectoryService.class);
            if (directoryService == null) {
                // don't bother in test ctx
                return;
            }
            try (Session dirSession = directoryService.getDirectory("eventCategories").getSession()) {
                if (!dirSession.hasEntry(EVENT_CATEGORY)) {
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("id", EVENT_CATEGORY);
                    entry.put("label", EVENT_CATEGORY_LABEL);
                    entry.put("obsolete", 0);
                    entry.put("ordering", 5);
                    dirSession.createEntry(entry);
                }
            }
        });
    }

    @Override
    public void stop(ComponentContext context) throws InterruptedException {
        defaultRendition = null;
        renditionByDocType = null;
        renditionByFacets = null;
    }

    public String getRenditionName(DocumentModel doc) {
        String docType = doc.getType();
        if (renditionByDocType.containsKey(docType)) {
            return renditionByDocType.get(docType);
        }
        for (Map.Entry<String, String> entry : renditionByFacets.entrySet()) {
            if (doc.hasFacet(entry.getKey())) {
                return entry.getValue();
            }
        }

        if (defaultRendition == null) {
            throw new NuxeoException(
                    String.format("Please contribute a default rendition name for document docType %s and facets %s",
                            docType, doc.getFacets()));
        }
        return defaultRendition;
    }

    @Override
    public Blob getRendition(CoreSession session, DocumentModel doc) {
        String renditionName = getRenditionName(doc);
        if (Framework.isBooleanPropertyTrue(COLD_STORAGE_THUMBNAIL_PREVIEW_REQUIRED_PROPERTY_NAME)
                && "thumbnail".equals(renditionName)) {
            if (!doc.hasFacet(ThumbnailConstants.THUMBNAIL_FACET)
                    || doc.getPropertyValue(ThumbnailConstants.THUMBNAIL_PROPERTY_NAME) == null) {
                // We don't want to fall back on the default icon thumbnail
                throw new NuxeoException(
                        String.format("No available thumbnail rendition for document %s.", doc.getPath()),
                        SC_PRECONDITION_FAILED);
            }
        }
        try {
            RenditionService renditionService = Framework.getService(RenditionService.class);
            Rendition rendition = renditionService.getRendition(doc, renditionName);
            return rendition.getBlob();
        } catch (NuxeoException e) {
            throw new NuxeoException(String.format("Cannot retrieve the rendition for document %s.", doc), e,
                    SC_NOT_FOUND);
        }
    }

    @Override
    public DocumentModel moveToColdStorage(CoreSession session, DocumentRef documentRef) {
        DocumentModel documentModel = proceedMoveToColdStorage(session, documentRef);
        // Submit Bulk action to update documents referencing the same blob
        String blobDigest = ((Blob) documentModel.getPropertyValue(COLD_STORAGE_CONTENT_PROPERTY)).getDigest();
        propagateMoveToColdStorage(session, blobDigest);
        return session.saveDocument(documentModel);
    }

    @Override
    public DocumentModel proceedMoveToColdStorage(CoreSession session, DocumentRef documentRef) {
        DocumentModel documentModel = session.getDocument(documentRef);
        if (session.isUnderRetentionOrLegalHold(documentRef)) {
            log.debug("The document {} is under retention or legal hold and cannot be moved to cold storage",
                    () -> documentRef);
            throw new DocumentSecurityException(String.format(
                    "The document %s is under retention or legal hold and cannot be moved to cold storage",
                    documentRef));
        }
        if (!session.hasPermission(documentRef, SecurityConstants.WRITE_COLD_STORAGE)) {
            log.debug("User: {} is not authorized to move doc: {} to cold storage", session::getPrincipal,
                    () -> documentModel);
            throw new DocumentSecurityException(String.format(
                    "User: %s is not authorized to move doc: %s to cold storage", session.getPrincipal(), documentRef));
        }

        if (documentModel.hasFacet(COLD_STORAGE_FACET_NAME)
                && documentModel.getPropertyValue(COLD_STORAGE_CONTENT_PROPERTY) != null) {
            log.info("The main content for document: {} is already in cold storage.", documentModel::getId);
            return documentModel;
        }

        Serializable mainContent = documentModel.getPropertyValue(FILE_CONTENT_PROPERTY);
        if (mainContent == null) {
            throw new NuxeoException(String.format("There is no main content for document: %s.", documentModel),
                    SC_NOT_FOUND);
        }

        // retrieve the rendition which will be used to replace the content, once the move done
        Blob renditionBlob = getRendition(session, documentModel);

        documentModel.addFacet(COLD_STORAGE_FACET_NAME);
        documentModel.setPropertyValue(COLD_STORAGE_CONTENT_PROPERTY, mainContent);
        documentModel.setPropertyValue(FILE_CONTENT_PROPERTY, null);

        try {
            Blob coldContent = (Blob) mainContent;
            BlobStatus oldStatus = ColdStorageHelper.getStatus((ManagedBlob) coldContent);
            if (!ColdStorageHelper.isInColdStorage(oldStatus)) {
                // No need to update the class
                // To be re-factored when we support more storage class
                String key = getContentBlobKey(coldContent);
                BlobUpdateContext updateContext = new BlobUpdateContext(key).withColdStorageClass(true);
                Framework.getService(BlobManager.class).getBlobProvider(coldContent).updateBlob(updateContext);
            } else {
                log.warn("Main blob {} for document {} is already in cold storage with storage class {}",
                        coldContent::getDigest, documentModel::getId, oldStatus::getStorageClass);
            }
        } catch (IOException e) {
            throw new NuxeoException(e);
        }

        // THUMBNAIL_UPDATED: disabling is needed otherwise as the content is now `null` the thumbnail will be also
        // `null` See CheckBlobUpdateListener#handleEvent
        COLD_STORAGE_DISABLED_RECOMPUTATION_LISTENERS.forEach(name -> documentModel.putContextData(name, true));
        documentModel.putContextData(DISABLE_AUTOMATIC_VERSIONING, true);

        // replace the file content document by the rendition
        documentModel.setPropertyValue(FILE_CONTENT_PROPERTY, (Serializable) renditionBlob);

        // FIXME
        if (documentModel.hasFacet("Picture")) {
            // re-set the picture views so that they are dirty and won't be updated
            documentModel.setPropertyValue("picture:views", documentModel.getPropertyValue("picture:views"));
        }

        // For audit purpose
        fireEvent(documentModel, session, COLD_STORAGE_CONTENT_MOVED_EVENT_NAME);
        return documentModel;
    }

    @Override
    public DocumentModel retrieveFromColdStorage(CoreSession session, DocumentRef documentRef,
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
        BlobStatus blobStatus = ColdStorageHelper.getBlobStatus(documentModel);
        Function<DocumentModel, Boolean> doNotify;
        DocumentModel docResult = null;
        Blob coldContent = (Blob) documentModel.getPropertyValue(COLD_STORAGE_CONTENT_PROPERTY);
        String key = getContentBlobKey(coldContent);
        if (ColdStorageHelper.isDownloadable(blobStatus)) {
            Instant downloadableUntil = blobStatus.getDownloadableUntil();
            if (downloadableUntil == null) {
                // Blob is restored but the doc is not, maybe restore propagation is in progress
                // Let's restore the doc in a consistent state
                log.warn(
                        "Cold content blob: {} of document: {} is already restored. Restoring the Nuxeo document instead of retrieving.",
                        key, documentRef);
                DocumentModel restored = proceedRestoreMainContent(session, documentModel, false, false);
                // Fire event for audit purpose
                fireEvent(restored, restored.getCoreSession(), COLD_STORAGE_CONTENT_TO_RESTORE_EVENT_NAME);
                return restored;
            }
            documentModel.setPropertyValue(COLD_STORAGE_CONTENT_DOWNLOADABLE_UNTIL,
                    Date.from(blobStatus.getDownloadableUntil()));
            doNotify = doc -> false;
        } else if (blobStatus.isOngoingRestore()) {
            documentModel.setPropertyValue(COLD_STORAGE_BEING_RETRIEVED_PROPERTY, true);
            doNotify = doc -> true;
        } else {
            try {
                BlobUpdateContext updateContext = new BlobUpdateContext(key).withRestoreForDuration(restoreDuration);
                Framework.getService(BlobManager.class).getBlobProvider(coldContent).updateBlob(updateContext);
            } catch (IOException e) {
                log.error("Could not retrieve document {} for duration {} seconds", documentModel::getId,
                        restoreDuration::getSeconds);
                throw new NuxeoException(e);
            }
            documentModel.setPropertyValue(COLD_STORAGE_BEING_RETRIEVED_PROPERTY, true);
            doNotify = doc -> CoreInstance.doPrivileged(session, s -> {
                // The check retrieval may need to modify metadata of document too
                return !checkIsRetrieved(s, doc);
            });
        }
        docResult = CoreInstance.doPrivileged(session, s -> {
            // The retrieval is allowed for users with only READ access.
            // It requires an unrestricted session to update ColdStorage metadata on the document
            documentModel.putContextData(DISABLE_AUTOMATIC_VERSIONING, true);
            return s.saveDocument(documentModel);
        });

        // Fire event for audit purpose
        fireEvent(docResult, session, COLD_STORAGE_CONTENT_TO_RETRIEVE_EVENT_NAME);
        Serializable beingRestored = docResult.getPropertyValue(COLD_STORAGE_TO_BE_RESTORED_PROPERTY);
        if (!Boolean.TRUE.equals(beingRestored) && doNotify.apply(docResult)) {
            // auto-subscribe the user, this way they will receive the mail notification when the content is
            // available
            NuxeoPrincipal principal = session.getPrincipal();
            String username = NotificationConstants.USER_PREFIX + principal.getName();
            NotificationManager notificationManager = Framework.getService(NotificationManager.class);
            notificationManager.addSubscription(username, COLD_STORAGE_CONTENT_AVAILABLE_NOTIFICATION_NAME, docResult,
                    false, principal, COLD_STORAGE_CONTENT_AVAILABLE_NOTIFICATION_NAME);
        }
        return docResult;
    }

    @Override
    public DocumentModel restoreFromColdStorage(CoreSession session, DocumentRef documentRef) {
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
        BlobStatus blobStatus = ColdStorageHelper.getBlobStatus(documentModel);
        if (ColdStorageHelper.isDownloadable(blobStatus)) {
            // Restore the main content synchronously no need to notify.
            documentModel = proceedRestoreMainContent(session, documentModel, false);
        } else {
            // Need to retrieve the doc first, restoration will happen asynchronously once the doc will be retrieved
            // Subscribe the user to receive a mail notification once the content is restored
            NuxeoPrincipal principal = session.getPrincipal();
            String username = NotificationConstants.USER_PREFIX + principal.getName();
            NotificationManager notificationManager = Framework.getService(NotificationManager.class);
            notificationManager.addSubscription(username, COLD_STORAGE_CONTENT_RESTORED_NOTIFICATION_NAME,
                    documentModel, false, principal, COLD_STORAGE_CONTENT_RESTORED_NOTIFICATION_NAME);

            // flag the retrieval as restore purpose
            documentModel.setPropertyValue(COLD_STORAGE_TO_BE_RESTORED_PROPERTY, true);
            documentModel.putContextData(DISABLE_AUTOMATIC_VERSIONING, true);
            documentModel = session.saveDocument(documentModel);
            documentModel = retrieveFromColdStorage(session, documentModel.getRef(), getAvailabilityDuration());
        }
        // for audit purpose
        fireEvent(documentModel, documentModel.getCoreSession(), COLD_STORAGE_CONTENT_TO_RESTORE_EVENT_NAME);
        return documentModel;
    }

    @Override
    public DocumentModel proceedRestoreMainContent(CoreSession session, DocumentModel documentModel, boolean notify) {
        return proceedRestoreMainContent(session, documentModel, notify, true);
    }

    @Override
    public DocumentModel proceedRestoreMainContent(CoreSession session, DocumentModel documentModel, boolean notify,
            boolean propagate) {
        Blob coldContent = (Blob) documentModel.getPropertyValue(COLD_STORAGE_CONTENT_PROPERTY);
        if (coldContent == null) {
            throw new NuxeoException(String.format("Cold content is null for document: %s", documentModel.getId()));
        }
        try {
            String key = getContentBlobKey(coldContent);
            BlobUpdateContext updateContext = new BlobUpdateContext(key).withColdStorageClass(false);
            Framework.getService(BlobManager.class).getBlobProvider(coldContent).updateBlob(updateContext);
        } catch (IOException e) {
            log.error("Could not restore document {}", documentModel::getId);
            throw new NuxeoException(e);
        }
        // We must reset all properties of the Cold Storage facet before removing it, otherwise the properties will
        // still have the old values if we add back the facet (i.e. send back to cold storage)
        documentModel.setPropertyValue(COLD_STORAGE_CONTENT_PROPERTY, null);
        documentModel.setPropertyValue(COLD_STORAGE_BEING_RETRIEVED_PROPERTY, null);
        documentModel.setPropertyValue(COLD_STORAGE_TO_BE_RESTORED_PROPERTY, null);
        documentModel.setPropertyValue(COLD_STORAGE_CONTENT_DOWNLOADABLE_UNTIL, null);

        // Disable main and ColdStorage storage contents check otherwise, the restore action won't be allowed
        documentModel.putContextData(DISABLE_PREVENT_COLD_STORAGE_UPDATE_LISTENER, true);
        if (documentModel.isVersion()) {
            documentModel.putContextData(ALLOW_VERSION_WRITE, true);
        }
        documentModel = session.saveDocument(documentModel);
        documentModel.removeFacet(COLD_STORAGE_FACET_NAME);

        documentModel.setPropertyValue(FILE_CONTENT_PROPERTY, (Serializable) coldContent);
        // Disable main and ColdStorage storage contents check otherwise, the restore action won't be allowed
        documentModel.putContextData(DISABLE_PREVENT_COLD_STORAGE_UPDATE_LISTENER, true);
        documentModel.putContextData(DISABLE_CHECK_ALREADY_IN_COLD_STORAGE_LISTENER, true);
        // Disable recompute listeners
        for (String listener : COLD_STORAGE_DISABLED_RECOMPUTATION_LISTENERS) {
            documentModel.putContextData(listener, true);
        }
        // Disable fulltext reindex
        DownloadBlobGuard.enable();
        documentModel.putContextData(DISABLE_AUTOMATIC_VERSIONING, true);
        if (documentModel.isVersion()) {
            documentModel.putContextData(ALLOW_VERSION_WRITE, true);
        }
        documentModel = session.saveDocument(documentModel);

        if (propagate) {
            // Submit Bulk action to update documents referencing the same blob
            propagateRestoreFromColdStorage(session, coldContent.getDigest());
        }

        // Send notification
        if (notify) {
            DocumentEventContext ctx = new DocumentEventContext(session, session.getPrincipal(), documentModel);
            EventService eventService = Framework.getService(EventService.class);
            ctx.setProperty(COLD_STORAGE_CONTENT_RESTORED_EVENT_NAME, "true");
            eventService.fireEvent(ctx.newEvent(COLD_STORAGE_CONTENT_RESTORED_EVENT_NAME));
        }
        return documentModel;
    }

    public void propagateMoveToColdStorage(CoreSession session, String blobDigest) {
        String query = String.format(
                "SELECT * FROM Document WHERE ecm:mixinType <> '%s' AND file:content/digest = '%s'",
                COLD_STORAGE_FACET_NAME, blobDigest);

        BulkService bulkService = Framework.getService(BulkService.class);
        String username = SecurityConstants.SYSTEM_USERNAME;
        String commandId = bulkService.submitTransactional(
                new BulkCommand.Builder(PropagateMoveToColdStorageContentAction.ACTION_NAME, query, username).build());

        log.debug("Moving documents referencing blob: {}, status: {}", () -> blobDigest,
                () -> bulkService.getStatus(commandId));
    }

    /**
     * Restore from ColdStorage all documents referencing the given blob digests as main content.
     *
     * @param session the session
     * @param blobDigests the blob digests
     */
    public void propagateRestoreFromColdStorage(CoreSession session, String blobDigest) {
        String query = String.format("SELECT * FROM Document WHERE %s/digest = '%s'", COLD_STORAGE_CONTENT_PROPERTY,
                blobDigest);

        BulkService bulkService = Framework.getService(BulkService.class);
        String username = SecurityConstants.SYSTEM_USERNAME;
        String commandId = bulkService.submitTransactional(new BulkCommand.Builder(
                PropagateRestoreFromColdStorageContentAction.ACTION_NAME, query, username).build());
        log.debug("Restoring documents referencing blob: {}, status: {}", () -> blobDigest,
                () -> bulkService.getStatus(commandId));
    }

    @Override
    public void checkDocToBeRetrieved(CoreSession session) {
        BulkService bulkService = Framework.getService(BulkService.class);
        String username = SecurityConstants.SYSTEM_USERNAME;
        String commandId = bulkService.submit(new BulkCommand.Builder(CheckColdStorageAvailabilityAction.ACTION_NAME,
                GET_DOCUMENTS_TO_CHECK_QUERY, username).build());

        BulkStatus status = bulkService.getStatus(commandId);
        if (status == null) {
            log.error("Unable to check documents to be retrieved");
        } else {
            log.debug("Checking documents to be retrieved");
        }
    }

    @Override
    public boolean checkIsRetrieved(CoreSession session, DocumentModel doc) {
        if (!doc.hasFacet(COLD_STORAGE_FACET_NAME) || doc.getPropertyValue(COLD_STORAGE_CONTENT_PROPERTY) == null) {
            log.debug("Document {} is not under cold storage", doc::getPath);
            return false;
        }

        BlobStatus blobStatus = ColdStorageHelper.getBlobStatus(doc);
        if (blobStatus.isDownloadable()) {
            // Check if the Document should be restored definitively
            Serializable undoMove = doc.getPropertyValue(COLD_STORAGE_TO_BE_RESTORED_PROPERTY);
            if (Boolean.TRUE.equals(undoMove)) {
                proceedRestoreMainContent(session, doc, true);
            } else {
                doc.setPropertyValue(COLD_STORAGE_BEING_RETRIEVED_PROPERTY, false);

                DocumentEventContext ctx = new DocumentEventContext(session, session.getPrincipal(), doc);
                Instant downloadableUntil = blobStatus.getDownloadableUntil();
                if (downloadableUntil != null) {
                    ctx.getProperties()
                       .put(COLD_STORAGE_CONTENT_AVAILABLE_UNTIL_MAIL_TEMPLATE_KEY, downloadableUntil.toString());
                    doc.setPropertyValue(COLD_STORAGE_CONTENT_DOWNLOADABLE_UNTIL, Date.from(downloadableUntil));
                }
                doc = session.saveDocument(doc);
                EventService eventService = Framework.getService(EventService.class);
                DownloadService downloadService = Framework.getService(DownloadService.class);
                String fileName = ((Blob) doc.getProperty(COLD_STORAGE_CONTENT_PROPERTY).getValue()).getFilename();
                String serverUrl = NotificationServiceHelper.getNotificationService().getServerUrlPrefix();
                String downloadUrl = serverUrl + downloadService.getDownloadUrl(session.getRepositoryName(),
                        doc.getId(), COLD_STORAGE_CONTENT_PROPERTY, fileName, null);
                ctx.getProperties().put(COLD_STORAGE_CONTENT_ARCHIVE_LOCATION_MAIL_TEMPLATE_KEY, downloadUrl);
                eventService.fireEvent(ctx.newEvent(COLD_STORAGE_CONTENT_AVAILABLE_EVENT_NAME));
            }
            return true;
        } else if (!blobStatus.isOngoingRestore()) {
            // the blob was probably retrieved and it already went back to cold storage
            // Let's flag it as not being retrieved
            log.debug("Document {} is flagged as being retrieved but not its blob", doc::getPath);
            doc.setPropertyValue(COLD_STORAGE_BEING_RETRIEVED_PROPERTY, false);
            doc = session.saveDocument(doc);
        }
        return false;
    }

    public static String getContentBlobKey(Blob coldContent) {
        String key = ((ManagedBlob) coldContent).getKey();
        int colon = key.indexOf(':');
        if (colon >= 0) {
            key = key.substring(colon + 1);
        }
        return key;
    }

    @Override
    public Duration getAvailabilityDuration() {
        String value = Framework.getProperty(COLD_STORAGE_NUMBER_OF_DAYS_OF_AVAILABILITY_PROPERTY_NAME, "1");
        return Duration.ofDays(Integer.parseInt(value));
    }

    protected void fireEvent(DocumentModel doc, CoreSession session, String eventName) {
        EventService eventService = Framework.getService(EventService.class);
        DocumentEventContext ctx = new DocumentEventContext(session, session.getPrincipal(), doc);
        ctx.setProperty(CoreEventConstants.REPOSITORY_NAME, session.getRepositoryName());
        ctx.setProperty("category", EVENT_CATEGORY);
        Event event = ctx.newEvent(eventName);
        eventService.fireEvent(event);
    }

}
