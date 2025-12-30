package com.tyron.nanoj.api.tasks;

/**
 * Controls how task inputs/outputs are fingerprinted for up-to-date checks.
 */
public enum TaskFingerprintMode {
    /**
     * Uses metadata only (exists, folder flag, lastModified, length).
     */
    METADATA,

    /**
     * Uses metadata and a SHA-256 content hash for file entries.
     * <p>
     * Directory entries are still metadata-only.
     */
    CONTENT_HASH
}
