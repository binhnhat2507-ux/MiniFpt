package com.miniftp;

import com.miniftp.services.FileManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FileManagerSafetyTest {
    @TempDir Path temporary;

    @Test void validatesNestedPathsAndPreservesNoOverwrite() throws Exception {
        FileManager files = new FileManager(temporary.resolve("storage"));
        files.createDirectory("Documents");
        files.createDirectory("Documents/Projects");
        byte[] body = {1, 7, 42};
        Path target = files.prepareUpload("Documents/Projects/report.bin", body.length);
        files.receiveFile(target, new ByteArrayInputStream(body), body.length);
        assertArrayEquals(body, Files.readAllBytes(files.getDownloadFile("Documents/Projects/report.bin")));
        assertEquals("report.bin", files.list("Documents/Projects").getFirst().name());
        assertThrows(IOException.class, () -> files.prepareUpload("Documents/Projects/report.bin", 1));
        files.rename("Documents/Projects/report.bin", "final.bin");
        assertEquals("final.bin", files.list("Documents/Projects").getFirst().name());
        assertThrows(IOException.class, () -> files.delete("Documents"));
        files.delete("Documents/Projects/final.bin");
        files.delete("Documents/Projects");
        files.delete("Documents");
        assertTrue(files.list().isEmpty());
    }

    @Test void openDownloadRetainsOriginalFileAcrossConcurrentRenameAndReplacement() throws Exception {
        FileManager files = new FileManager(temporary.resolve("storage"));
        byte[] original = {1, 2, 3};
        files.receiveFile(files.prepareUpload("report.bin", original.length), new ByteArrayInputStream(original), original.length);
        try (var channel = files.openDownload("report.bin")) {
            files.rename("report.bin", "archived.bin");
            files.receiveFile(files.prepareUpload("report.bin", 1), new ByteArrayInputStream(new byte[] {9}), 1);
            assertEquals(original.length, channel.size());
            var buffer = java.nio.ByteBuffer.allocate(original.length);
            while (buffer.hasRemaining()) assertTrue(channel.read(buffer) > 0);
            assertArrayEquals(original, buffer.array());
        }
        assertArrayEquals(new byte[] {9}, Files.readAllBytes(files.getDownloadFile("report.bin")));
    }

    @Test void rejectsTraversalHiddenAndAbsolutePaths() throws Exception {
        FileManager files = new FileManager(temporary.resolve("storage"));
        for (String path : new String[] {"../outside", "/outside", "folder/../secret", "folder//file",
                "folder/.accounts.properties", ".accounts.properties", "folder\\file", "C:/outside"}) {
            assertThrows(IOException.class, () -> files.prepareUpload(path, 1), path);
            assertThrows(IOException.class, () -> files.list(path), path);
        }
        assertThrows(IOException.class, () -> files.rename("folder", "../escape"));
        assertThrows(IOException.class, () -> files.delete(""));
    }

    @Test void rejectsSymlinkAtAnyDirectoryComponent() throws Exception {
        FileManager files = new FileManager(temporary.resolve("storage"));
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "private");
        files.createDirectory("Documents");
        try {
            Files.createSymbolicLink(files.root().resolve("Documents/link"), outside);
        } catch (UnsupportedOperationException | IOException unsupported) {
            assumeTrue(false, "Symbolic links unavailable on this platform");
        }
        assertThrows(IOException.class, () -> files.getDownloadFile("Documents/link/secret.txt"));
        assertThrows(IOException.class, () -> files.prepareUpload("Documents/link/stolen.txt", 1));
        assertThrows(IOException.class, () -> files.list("Documents/link"));
        assertTrue(files.list("Documents").isEmpty());
        assertEquals("private", Files.readString(outside.resolve("secret.txt")));
        Path alias = temporary.resolve("alias");
        Files.createSymbolicLink(alias, outside);
        assertThrows(IOException.class, () -> new FileManager(alias.resolve("nested")));
        assertFalse(Files.exists(outside.resolve("nested")));
    }

    @Test void truncatedNestedUploadCleansStagingFile() throws Exception {
        FileManager files = new FileManager(temporary.resolve("storage"));
        files.createDirectory("Documents");
        Path target = files.prepareUpload("Documents/incomplete.bin", 9);
        assertThrows(IOException.class, () -> files.receiveFile(target, new ByteArrayInputStream(new byte[] {1}), 9));
        try (var entries = Files.list(files.root().resolve("Documents"))) {
            assertEquals(0, entries.count());
        }
    }

    @Test @Timeout(10) void activeTransfersPreventDirectoryRenameUntilEveryUploadFinishes() throws Exception {
        FileManager files = new FileManager(temporary.resolve("storage"));
        files.createDirectory("Documents");
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InputStream slowInput = new InputStream() {
            @Override public int read() throws IOException {
                reading.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out waiting for test");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                return 42;
            }
        };
        try (var workers = Executors.newSingleThreadExecutor()) {
            var transfer = workers.submit(() -> {
                files.receiveFile(files.prepareUpload("Documents/slow.bin", 1), slowInput, 1);
                return null;
            });
            try {
                assertTrue(reading.await(5, TimeUnit.SECONDS));
                files.receiveFile(files.prepareUpload("Documents/fast.bin", 1), new ByteArrayInputStream(new byte[] {1}), 1);
                assertThrows(IOException.class, () -> files.rename("Documents", "Moved"));
            } finally { release.countDown(); }
            transfer.get(5, TimeUnit.SECONDS);
        }
        files.rename("Documents", "Moved");
        assertEquals(2, files.list("Moved").size());
    }
}
