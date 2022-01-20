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
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_AVAILABLE_IN_COLDSTORAGE;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_AVAILABLE_NOTIFICATION_NAME;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_AVAILABLE_UNTIL_MAIL_TEMPLATE_KEY;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_DOWNLOADABLE_UNTIL;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_MOVED_EVENT_NAME;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_PROPERTY;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_RESTORED_NOTIFICATION_NAME;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_STORAGE_CLASS_TO_UPDATED;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_TO_RESTORE_EVENT_NAME;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_CONTENT_TO_RETRIEVE_EVENT_NAME;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_FACET_NAME;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_THUMBNAIL_PREVIEW_REQUIRED_PROPERTY_NAME;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_TO_BE_RESTORED_PROPERTY;
import static org.nuxeo.coldstorage.ColdStorageConstants.COLD_STORAGE_BEING_RESTORED_PROPERTY;
import static org.nuxeo.coldstorage.ColdStorageConstants.FILE_CONTENT_PROPERTY;
import static org.nuxeo.coldstorage.ColdStorageConstants.GET_COLDSTORAGE_DOCUMENTS_TO_CHECK_QUERY;
import static org.nuxeo.coldstorage.ColdStorageConstants.GET_DOCUMENTS_TO_CHECK_QUERY;
import static org.nuxeo.coldstorage.ColdStorageConstants.WRITE_COLD_STORAGE;

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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.coldstorage.ColdStorageConstants;
import org.nuxeo.coldstorage.ColdStorageConstants.ColdStorageContentStatus;
import org.nuxeo.coldstorage.ColdStorageRenditionDescriptor;
import org.nuxeo.coldstorage.action.DeduplicationColdStorageContentActions;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.core.api.event.CoreEventConstants;
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
 * @since 10.10
 */
public class ColdStorageServiceImpl extends DefaultComponent implements ColdStorageService {

    public static final String COLDSTORAGE_RENDITION_EP = "coldStorageRendition";
    protected static final List<String> COLD_STORAGE_DISABLED_RECOMPUTATION_LISTENERS = Arrays.asList(
            UpdateThumbnailListener.THUMBNAIL_UPDATED, ThumbnailConstants.DISABLE_THUMBNAIL_COMPUTATION,
            VideoChangedListener.DISABLE_VIDEO_CONVERSIONS_GENERATION_LISTENER,
            PictureViewsGenerationListener.DISABLE_PICTURE_VIEWS_GENERATION_LISTENER);

    private static final Logger log = LogManager.getLogger(ColdStorageServiceImpl.class);
    protected static String defaultRendition;

    protected static Map<String, String> renditionByDocType = new HashMap<>();

    protected static Map<String, String> renditionByFacets = new HashMap<>();

    public ColdStorageServiceImpl() {
        // no instance allowed
    }

    public static String getContentBlobKey(Blob coldContent) {
        String key = ((ManagedBlob) coldContent).getKey();
        int colon = key.indexOf(':');
        if (colon >= 0) {
            key = key.substring(colon + 1);
        }
        return key;
    }

    public static BlobStatus getBlobStatus(DocumentModel doc) {
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
                if (!dirSession.hasEntry(ColdStorageConstants.EVENT_CATEGORY)) {
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("id", ColdStorageConstants.EVENT_CATEGORY);
                    entry.put("label", ColdStorageConstants.EVENT_CATEGORY_LABEL);
                    entry.put("obsolete", 0);
                    entry.put("ordering", 5);
                    dirSession.createEntry(entry);
                }
            }
        });
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
    public Blob getRendition(DocumentModel doc, CoreSession session) {
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
        DocumentModel documentModel = session.getDocument(documentRef);
        // retrieve the rendition which will be used to replace the content, once the move done
        Blob renditionBlob = getRendition(documentModel, session);
        // make the move
        documentModel = moveContentToColdStorage(session, documentModel.getRef());

        // replace the file content document by the rendition
        documentModel.setPropertyValue(FILE_CONTENT_PROPERTY, (Serializable) renditionBlob);

        // FIXME
        if (documentModel.hasFacet("Picture")) {
            // re-set the picture views so that they are dirty and won't be updated
            documentModel.setPropertyValue("picture:views", documentModel.getPropertyValue("picture:views"));
        }

        fireEvent(documentModel, session, COLD_STORAGE_CONTENT_MOVED_EVENT_NAME);

        return documentModel;
    }

    @Override
    public DocumentModel moveContentToColdStorage(CoreSession session, DocumentRef documentRef) {
        DocumentModel documentModel = session.getDocument(documentRef);
        log.debug("Move to cold storage the main content of document: {}", documentModel);

        if (!session.hasPermission(documentRef, WRITE_COLD_STORAGE)) {
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
        documentModel.setPropertyValue(COLD_STORAGE_CONTENT_STORAGE_CLASS_TO_UPDATED, true);
        // THUMBNAIL_UPDATED: disabling is needed otherwise as the content is now `null` the thumbnail will be also
        // `null` See CheckBlobUpdateListener#handleEvent
        COLD_STORAGE_DISABLED_RECOMPUTATION_LISTENERS.forEach(name -> documentModel.putContextData(name, true));

        return documentModel;
    }

    @Override
    public DocumentModel requestRetrievalFromColdStorage(CoreSession session, DocumentRef documentRef,
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
        fireEvent(documentModel, session, COLD_STORAGE_CONTENT_TO_RETRIEVE_EVENT_NAME);
        return documentModel;
    }

    @Override
    public DocumentModel retrieveFromColdStorage(CoreSession session, DocumentModel doc, Duration restoreDuration) {
        DocumentModel documentModel = requestRetrievalFromColdStorage(session, doc.getRef(), restoreDuration);

        // auto-subscribe the user, this way they will receive the mail notification when the content is available
        NuxeoPrincipal principal = session.getPrincipal();
        String username = NotificationConstants.USER_PREFIX + principal.getName();
        NotificationManager notificationManager = Framework.getService(NotificationManager.class);
        notificationManager.addSubscription(username, COLD_STORAGE_CONTENT_AVAILABLE_NOTIFICATION_NAME, documentModel,
                false, principal, COLD_STORAGE_CONTENT_AVAILABLE_NOTIFICATION_NAME);
        return documentModel;
    }

    @Override
    public DocumentModel restoreFromColdStorage(CoreSession session, DocumentRef documentRef) {
        DocumentModel document = session.getDocument(documentRef);
        // auto-subscribe the user, this way he will receive the mail notification when the content is available
        NuxeoPrincipal principal = session.getPrincipal();
        String username = NotificationConstants.USER_PREFIX + principal.getName();
        NotificationManager notificationManager = Framework.getService(NotificationManager.class);
        notificationManager.addSubscription(username, COLD_STORAGE_CONTENT_RESTORED_NOTIFICATION_NAME, document, false,
                principal, COLD_STORAGE_CONTENT_RESTORED_NOTIFICATION_NAME);
        return restoreContentFromColdStorage(session, document.getRef());
    }

    @Override
    public DocumentModel restoreContentFromColdStorage(CoreSession session, DocumentRef documentRef) {
        DocumentModel documentModel = session.getDocument(documentRef);
        log.debug("Restore from cold storage the main content of document: {}", documentModel);

        if (!session.hasPermission(documentRef, WRITE_COLD_STORAGE)) {
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
        documentModel = session.saveDocument(documentModel);

        BlobStatus blobStatus = getBlobStatus(documentModel);
        // FIXME waiting for NXP-30419 to be done
        boolean downloadable = blobStatus.getStorageClass() == null ? blobStatus.isDownloadable()
                : (blobStatus.isDownloadable() && blobStatus.getDownloadableUntil() != null);
        if (downloadable) {
            documentModel.setPropertyValue(COLD_STORAGE_TO_BE_RESTORED_PROPERTY, false);
            documentModel.setPropertyValue(COLD_STORAGE_BEING_RESTORED_PROPERTY, true);
            documentModel = session.saveDocument(documentModel);
            restoreMainContent(documentModel);
        } else {
            documentModel = requestRetrievalFromColdStorage(session, documentModel.getRef(),
                    getDurationAvailability());
        }
        return documentModel;
    }

    @Override
    public void restoreMainContent(DocumentModel documentModel) {
        fireEvent(documentModel, documentModel.getCoreSession(), COLD_STORAGE_CONTENT_TO_RESTORE_EVENT_NAME);
    }

    @Override
    public void moveDuplicatedBlobToColdStorage(CoreSession session, DocumentModel documentModel) {
        String blobDigest = ((Blob) documentModel.getPropertyValue(COLD_STORAGE_CONTENT_PROPERTY)).getDigest();
        String documentId = documentModel.getId();
        String query = String.format(
                "SELECT * FROM Document WHERE ecm:uuid != '%s' AND (ecm:isVersion = 0 AND file:content/digest = '%s' "
                        + "OR ecm:isVersion = 1 AND ecm:versionVersionableId = '%s' AND file:content/digest = '%s')",
                documentModel.getId(), blobDigest, documentModel.getId(), blobDigest);

        BulkService bulkService = Framework.getService(BulkService.class);
        BulkCommand command = new BulkCommand //
                .Builder(DeduplicationColdStorageContentActions.ACTION_NAME, query) //
                                                                                   .user(SecurityConstants.SYSTEM_USERNAME)
                                                                                   .repository(
                                                                                           session.getRepositoryName())
                                                                                   .build();

        String commandId = bulkService.submit(command);
        BulkStatus status = bulkService.getStatus(commandId);
        if (status == null) {
            log.error("Unable to move duplicated blob: {} to cold storage", documentId);
        } else {
            log.debug("Moving duplicated blob for document: {} status {}", documentId, status.getState());
        }

    }

    @Override
    public ColdStorageContentStatus checkColdStorageContentAvailability(CoreSession session) {
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
                    doc.setPropertyValue(COLD_STORAGE_TO_BE_RESTORED_PROPERTY, false);
                    session.saveDocument(doc);
                } else {
                    doc.setPropertyValue(COLD_STORAGE_BEING_RETRIEVED_PROPERTY, false);
                    session.saveDocument(doc);

                    DocumentEventContext ctx = new DocumentEventContext(session, session.getPrincipal(), doc);
                    Instant downloadableUntil = blobStatus.getDownloadableUntil();
                    if (downloadableUntil != null) {
                        ctx.getProperties()
                           .put(COLD_STORAGE_CONTENT_AVAILABLE_UNTIL_MAIL_TEMPLATE_KEY, downloadableUntil.toString());
                        doc.setPropertyValue(COLD_STORAGE_CONTENT_DOWNLOADABLE_UNTIL, Date.from(downloadableUntil));
                        session.saveDocument(doc);
                    }
                    String fileName = ((Blob) doc.getProperty(COLD_STORAGE_CONTENT_PROPERTY).getValue()).getFilename();
                    String serverUrl = NotificationServiceHelper.getNotificationService().getServerUrlPrefix();
                    String downloadUrl = serverUrl
                            + downloadService.getDownloadUrl(doc, COLD_STORAGE_CONTENT_PROPERTY, fileName);
                    ctx.getProperties().put(COLD_STORAGE_CONTENT_ARCHIVE_LOCATION_MAIL_TEMPLATE_KEY, downloadUrl);
                    eventService.fireEvent(ctx.newEvent(COLD_STORAGE_CONTENT_AVAILABLE_EVENT_NAME));
                }
            } else if(!blobStatus.isOngoingRestore()) {
                // the blob was probably retrieved and it already went back to cold storage
                // Let's flag it as not being retrieved
                log.debug("Document {} is flagged as being retrieved but not its blob", doc::getPath);
                beingRetrieved--;
                doc.setPropertyValue(COLD_STORAGE_BEING_RETRIEVED_PROPERTY, false);
                session.saveDocument(doc);
            }
        }

        log.debug(
                "End checking the available cold storage content for repository: {}, beingRetrieved: {}, available: {}",
                session.getRepositoryName(), beingRetrieved, available);

        return new ColdStorageContentStatus(beingRetrieved, available);
    }

    @Override
    public void checkColdStorageClass(CoreSession session) {

        log.debug("Start checking the class storage for repository: {}", session::getRepositoryName);

        int limit = 100;
        long offset = 0;
        long total = 0;
        do {
            DocumentModelList documents = session.query(GET_COLDSTORAGE_DOCUMENTS_TO_CHECK_QUERY, null, limit, offset,
                    true);
            for (DocumentModel documentModel : documents) {
                BlobStatus blobStatus = getBlobStatus(documentModel);
                if (blobStatus == null) {
                    continue;
                }

                if (!blobStatus.isDownloadable()) {
                    documentModel.setPropertyValue(COLD_STORAGE_CONTENT_STORAGE_CLASS_TO_UPDATED, false);
                    documentModel.setPropertyValue(COLD_STORAGE_CONTENT_AVAILABLE_IN_COLDSTORAGE, true);
                    session.saveDocument(documentModel);
                }
            }
            offset += limit;
            if (total == 0) {
                total = documents.totalSize();
            }
            session.save();
        } while (offset < total);

        log.debug("End checking the class storage for repository: {}", session::getRepositoryName);

    }

    @Override
    public Duration getDurationAvailability() {
        String value = Framework.getProperty(
                ColdStorageConstants.COLD_STORAGE_NUMBER_OF_DAYS_OF_AVAILABILITY_PROPERTY_NAME, "1");
        return Duration.ofDays(Integer.parseInt(value));
    }

    protected void fireEvent(DocumentModel doc, CoreSession session, String eventName) {
        EventService eventService = Framework.getService(EventService.class);
        DocumentEventContext ctx = new DocumentEventContext(session, session.getPrincipal(), doc);
        ctx.setProperty(CoreEventConstants.REPOSITORY_NAME, session.getRepositoryName());
        ctx.setProperty("category", ColdStorageConstants.EVENT_CATEGORY);
        Event event = ctx.newEvent(eventName);
        eventService.fireEvent(event);
    }

}
