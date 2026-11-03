package com.miniftp.client;

@FunctionalInterface
public interface TransferProgress {
    void onProgress(long bytes, long total);
}
