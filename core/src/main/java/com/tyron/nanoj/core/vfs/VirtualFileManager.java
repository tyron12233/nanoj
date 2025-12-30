package com.tyron.nanoj.core.vfs;

/**
 * Backward-compatible shim.
 * <p>
 * Prefer using the API interface {@link com.tyron.nanoj.api.vfs.VirtualFileManager}.
 */
@Deprecated
public final class VirtualFileManager {

    private VirtualFileManager() {
    }

    public static com.tyron.nanoj.api.vfs.VirtualFileManager getInstance() {
        return com.tyron.nanoj.api.vfs.VirtualFileManager.getInstance();
    }
}