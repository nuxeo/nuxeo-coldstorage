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
package org.nuxeo.coldstorage;

import static com.amazonaws.services.s3.model.StorageClass.Glacier;
import static com.amazonaws.services.s3.model.StorageClass.Standard;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.IOUtils;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.blob.AbstractBlobProvider;
import org.nuxeo.ecm.core.blob.BlobInfo;
import org.nuxeo.ecm.core.blob.BlobStatus;
import org.nuxeo.ecm.core.blob.BlobUpdateContext;
import org.nuxeo.ecm.core.blob.ManagedBlob;
import org.nuxeo.ecm.core.blob.SimpleManagedBlob;

/**
 * Mock S3 blob provider for testing cold storage functionality.
 * <p>
 * This provider simulates S3 storage classes (Standard, Glacier) and restore behavior. Keys are based on blob digests.
 *
 * @since 2025.1
 */
public class MockS3BlobProvider extends AbstractBlobProvider {

    protected static final Duration RESTORE_DELAY = Duration.ofSeconds(1);

    protected Map<String, byte[]> blobs;

    protected Map<String, BlobStatus> blobsStatus;

    @Override
    public void initialize(String blobProviderId, Map<String, String> properties) throws IOException {
        super.initialize(blobProviderId, properties);
        blobs = new ConcurrentHashMap<>();
        blobsStatus = new ConcurrentHashMap<>();
    }

    @Override
    public void close() {
        blobs.clear();
        blobsStatus.clear();
    }

    /**
     * Adds a status for a blob. This method is used by tests to manually set blob status.
     *
     * @param blob the managed blob
     * @param status the status to set
     */
    public void addStatus(ManagedBlob blob, BlobStatus status) {
        blobsStatus.put(getBlobKey(blob), status);
    }

    /**
     * Extracts the blob key from a managed blob, stripping the provider prefix if present.
     *
     * @param blob the managed blob
     * @return the blob key (digest)
     */
    protected String getBlobKey(ManagedBlob blob) {
        String key = blob.getKey();
        int colon = key.indexOf(':');
        return colon < 0 ? key : key.substring(colon + 1);
    }

    @Override
    public BlobStatus getStatus(ManagedBlob blob) {
        return blobsStatus.getOrDefault(getBlobKey(blob), new BlobStatus());
    }

    @Override
    public Blob readBlob(BlobInfo blobInfo) {
        return new SimpleManagedBlob(blobProviderId, blobInfo) {
            @Serial
            private static final long serialVersionUID = 1L;

            @Override
            public InputStream getStream() throws IOException {
                if (!getStatus(this).isDownloadable()) {
                    throw new IOException(String.format("Blob %s is not downloadable", key));
                }
                String k = getBlobKey(this);
                byte[] bytes = blobs.get(k);
                if (bytes == null) {
                    throw new IOException(String.format("Blob %s not found", key));
                }
                return new ByteArrayInputStream(bytes);
            }
        };
    }

    @Override
    public void updateBlob(BlobUpdateContext blobUpdateContext) {
        if (blobUpdateContext == null) {
            return;
        }

        // Handle restore requests - requires special async handling
        if (blobUpdateContext.restoreForDuration != null) {
            blobsStatus.compute(blobUpdateContext.key, (key, existingStatus) -> {
                BlobStatus status = existingStatus != null ? existingStatus : new BlobStatus();
                if (Standard.toString().equals(status.getStorageClass())) {
                    throw new IllegalStateException("Cannot restore a blob with default storage class");
                }
                return status.withOngoingRestore(true);
            });

            // Simulate asynchronous restore
            Runnable restoreCmd = () -> blobsStatus.compute(blobUpdateContext.key, (key, existingStatus) -> {
                if (existingStatus == null) {
                    return null;
                }
                return existingStatus.withDownloadable(true)
                                     .withOngoingRestore(false)
                                     .withDownloadableUntil(
                                             Instant.now().plus(blobUpdateContext.restoreForDuration.duration));
            });
            CompletableFuture.delayedExecutor(RESTORE_DELAY.toMillis(), TimeUnit.MILLISECONDS).execute(restoreCmd);
            return;
        }

        // Handle cold storage class changes
        if (blobUpdateContext.coldStorageClass != null) {
            blobsStatus.compute(blobUpdateContext.key, (key, existingStatus) -> {
                BlobStatus status = existingStatus != null ? existingStatus : new BlobStatus();
                String storageClass = blobUpdateContext.coldStorageClass.inColdStorage ? Glacier.toString()
                        : Standard.toString();
                return status.withStorageClass(storageClass)
                             .withDownloadable(!blobUpdateContext.coldStorageClass.inColdStorage);
            });
        }
    }

    /**
     * Test helper to wait for asynchronous blob restore operations to complete.
     * <p>
     * Waits for {@link #RESTORE_DELAY} plus a 200ms buffer to ensure the async restore executor has finished updating
     * blob status. The buffer accounts for thread scheduling delays and ensures deterministic test behavior.
     *
     * @throws RuntimeException if the thread is interrupted while waiting
     */
    public static void waitForRestore() {
        try {
            Thread.sleep(MockS3BlobProvider.RESTORE_DELAY.plusMillis(200).toMillis());
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Writes a blob to storage using digest as the key.
     * <p>
     * Simulates blob deduplication based on digest. If the blob doesn't have a digest, one is computed from the blob
     * content.
     *
     * @param blob the blob to write
     * @return the digest key
     */
    @Override
    public String writeBlob(Blob blob) throws IOException {
        String digest = blob.getDigest();

        // If no digest is set, compute one from the blob content
        if (digest == null) {
            byte[] bytes;
            try (InputStream in = blob.getStream()) {
                bytes = IOUtils.toByteArray(in);
            }
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                digest = Hex.encodeHexString(md.digest(bytes));
            } catch (NoSuchAlgorithmException e) {
                throw new NuxeoException("MD5 algorithm not available", e);
            }
            // Store the blob with computed digest
            blobs.putIfAbsent(digest, bytes);
        } else {
            // Use computeIfAbsent for thread-safe deduplication
            blobs.computeIfAbsent(digest, k -> {
                try (InputStream in = blob.getStream()) {
                    return IOUtils.toByteArray(in);
                } catch (IOException e) {
                    throw new NuxeoException("Failed to read blob stream", e);
                }
            });
        }

        return digest;
    }

}
