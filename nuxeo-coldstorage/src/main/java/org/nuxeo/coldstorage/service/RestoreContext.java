/*
 * (C) Copyright 2026 Nuxeo (http://nuxeo.com/) and others.
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
 *     Guillaume Renard
 */
package org.nuxeo.coldstorage.service;

import javax.annotation.Nonnull;

import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;

/**
 * Context for restore operations from cold storage.
 * <p>
 * This class provides a builder pattern for configuring restore operations. Use the static factory method
 * {@link #builder(CoreSession, DocumentModel)} to create a new builder instance.
 *
 * @since 2025.2
 */
public class RestoreContext {

    protected final CoreSession coreSession;

    protected final DocumentModel documentModel;

    protected final boolean notify;

    protected final boolean propagate;

    protected final boolean storageLevel;

    protected RestoreContext(Builder builder) {
        this.coreSession = builder.coreSession;
        this.documentModel = builder.documentModel;
        this.notify = builder.notify;
        this.propagate = builder.propagate;
        this.storageLevel = builder.storageLevel;
    }

    /**
     * @return the core session
     */
    public CoreSession getCoreSession() {
        return coreSession;
    }

    /**
     * @return the document model to restore from cold storage
     */
    public DocumentModel getDocumentModel() {
        return documentModel;
    }

    /**
     * @return {@code true} if a notification event should be fired after restoration, {@code false} otherwise
     */
    public boolean isNotify() {
        return notify;
    }

    /**
     * @return {@code true} if the restore should be propagated to other documents with the same blob digest,
     *         {@code false} otherwise
     */
    public boolean isPropagate() {
        return propagate;
    }

    /**
     * @return {@code true} if the blob storage class should be updated at S3 level (e.g., from GLACIER to
     *         INTELLIGENT_TIERING), {@code false} to skip storage level changes
     */
    public boolean isStorageLevel() {
        return storageLevel;
    }

    /**
     * Creates a new builder for the specified document.
     *
     * @param coreSession the core session
     * @param documentModel the document to restore from cold storage
     * @return a new builder instance with default values (all flags set to {@code false})
     */
    public static Builder builder(@Nonnull CoreSession coreSession, @Nonnull DocumentModel documentModel) {
        return new Builder(coreSession, documentModel);
    }

    /**
     * Builder for {@link RestoreContext}.
     * <p>
     * All boolean flags default to {@code false}. Use the builder methods to override defaults.
     */
    public static class Builder {

        protected final CoreSession coreSession;

        protected final DocumentModel documentModel;

        protected boolean notify = false;

        protected boolean propagate = false;

        protected boolean storageLevel = false;

        protected Builder(CoreSession coreSession, DocumentModel documentModel) {
            this.coreSession = coreSession;
            this.documentModel = documentModel;
        }

        /**
         * Sets whether to fire a notification event after restoration.
         * <p>
         * When {@code true}, a {@code COLD_STORAGE_CONTENT_RESTORED_EVENT_NAME} event will be fired, which can trigger
         * email notifications to subscribed users.
         *
         * @param notify {@code true} to send notifications, {@code false} otherwise
         * @return this builder for method chaining
         */
        public Builder notify(boolean notify) {
            this.notify = notify;
            return this;
        }

        /**
         * Sets whether to propagate the restore operation to other documents.
         * <p>
         * When {@code true}, a bulk action will be submitted to restore all other documents that reference the same
         * blob digest. This is useful when multiple documents share the same content.
         * <p>
         * Set to {@code false} when the restore is already part of a propagation operation to avoid infinite loops.
         *
         * @param propagate {@code true} to propagate restore to related documents, {@code false} otherwise
         * @return this builder for method chaining
         */
        public Builder propagate(boolean propagate) {
            this.propagate = propagate;
            return this;
        }

        /**
         * Sets whether to update the blob storage class at S3 level.
         * <p>
         * When {@code true}, the blob's storage class will be changed from GLACIER to the default storage class (e.g.,
         * INTELLIGENT_TIERING) using the S3 API. This involves an S3 {@code updateBlob} call.
         * <p>
         * When {@code false}, only the Nuxeo document metadata will be updated without touching S3. This is useful when
         * the blob has already been restored at S3 level (e.g., via S3 Batch Operations) and only the Nuxeo document
         * state needs to be synchronized.
         * <p>
         * <strong>Performance tip:</strong> Set to {@code false} if you know the blob is already at the expected
         * storage class to avoid unnecessary S3 API calls.
         *
         * @param storageLevel {@code true} to update storage class at S3 level, {@code false} to skip S3 operations
         * @return this builder for method chaining
         */
        public Builder storageLevel(boolean storageLevel) {
            this.storageLevel = storageLevel;
            return this;
        }

        /**
         * Builds the {@link RestoreContext} with the configured values.
         *
         * @return a new immutable {@link RestoreContext} instance
         */
        public RestoreContext build() {
            return new RestoreContext(this);
        }
    }
}
