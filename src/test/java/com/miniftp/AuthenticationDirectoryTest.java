package com.miniftp;

import com.miniftp.client.FTPClient;
import com.miniftp.common.Protocol;
import com.miniftp.server.FTPServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real socket tests: no mocks, no fixed ports, isolated account and shared-file storage. */
class AuthenticationDirectoryTest {
    @TempDir Path work;
    private static final String USER = "student";
    private static char[] password() { return "Test-password-2026".toCharArray(); }

    private FTPClient registered(FTPServer server) throws IOException {
        try (FTPClient guest = new FTPClient("127.0.0.1", server.getPort())) { guest.register(USER, password()); }
        FTPClient client = new FTPClient("127.0.0.1", server.getPort(), USER, password());
        client.login();
        return client;
    }

    @Test void authenticationIsRequiredForEveryFileCommand() throws Exception {
        try (FTPServer server = new FTPServer(0, work.resolve("storage"), null)) {
            server.start();
            try (FTPClient guest = new FTPClient("127.0.0.1", server.getPort())) {
                guest.ping();
                assertThrows(IOException.class, guest::listFiles);
                assertThrows(IOException.class, () -> guest.createDirectory("private"));
            }
            for (String command : List.of(Protocol.LIST, Protocol.UPLOAD, Protocol.DOWNLOAD,
                    Protocol.MKDIR, Protocol.DELETE, Protocol.RENAME)) {
                try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
                    socket.setSoTimeout(3000);
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    out.writeUTF(command); out.flush();
                    assertEquals(Protocol.ERROR, in.readUTF(), command);
                    assertTrue(in.readUTF().contains("đăng nhập"));
                    assertEquals(-1, in.read());
                }
            }
            assertFalse(Files.exists(work.resolve("storage/private")));
        }
    }

    @Test void accountsPersistAndWrongCredentialsAreRejected() throws Exception {
        Path storage = work.resolve("storage");
        try (FTPServer server = new FTPServer(0, storage, null)) {
            server.start();
            try (FTPClient client = registered(server)) {
                assertTrue(client.listFiles().isEmpty(), "Account file must never appear in listing");
                assertThrows(IOException.class, () -> client.download(".accounts.properties", work.resolve("downloads"), null));
                assertThrows(IOException.class, () -> client.register(USER, password()));
            }
            try (FTPClient bad = new FTPClient("127.0.0.1", server.getPort(), USER, "wrong-password".toCharArray())) {
                assertThrows(IOException.class, bad::login);
                assertThrows(IOException.class, bad::listFiles);
            }
        }
        String accounts = Files.readString(storage.resolve(".accounts.properties"));
        assertFalse(accounts.contains(new String(password())));
        try (FTPServer reopened = new FTPServer(0, storage, null)) {
            reopened.start();
            try (FTPClient existing = new FTPClient("127.0.0.1", reopened.getPort(), USER, password())) {
                existing.login();
                existing.createDirectory("persisted");
                assertEquals("persisted", existing.listFiles().getFirst().name());
            }
        }
    }

    @Test void registrationValidatesAndDoesNotCreateDefaults() throws Exception {
        try (FTPServer server = new FTPServer(0, work.resolve("storage"), null)) {
            server.start();
            try (FTPClient guest = new FTPClient("127.0.0.1", server.getPort())) {
                assertThrows(IOException.class, () -> guest.register("../admin", password()));
                assertThrows(IOException.class, () -> guest.register("valid-user", "short".toCharArray()));
            }
            try (FTPClient missing = new FTPClient("127.0.0.1", server.getPort(), "admin", "admin123".toCharArray())) {
                assertThrows(IOException.class, missing::login);
            }
        }
    }

    @Test void nestedNavigationTransferRenameAndNonrecursiveDelete() throws Exception {
        byte[] payload = new byte[260_123];
        new Random(42).nextBytes(payload);
        Path upload = Files.write(work.resolve("bài tập.bin"), payload);
        try (FTPServer server = new FTPServer(0, work.resolve("storage"), null)) {
            server.start();
            try (FTPClient client = registered(server)) {
                client.createDirectory("Tài liệu");
                client.createDirectory("Tài liệu/Nhóm 3");
                List<Long> progress = new ArrayList<>();
                client.upload(upload, "Tài liệu/Nhóm 3", (done, total) -> {
                    assertEquals(payload.length, total); progress.add(done);
                });
                assertEquals((long) payload.length, progress.getLast().longValue());
                assertEquals("bài tập.bin", client.listFiles("Tài liệu/Nhóm 3").getFirst().name());
                assertThrows(IOException.class, () -> client.upload(upload, "Tài liệu/Nhóm 3", null));
                assertThrows(IOException.class, () -> client.delete("Tài liệu"));
                client.rename("Tài liệu/Nhóm 3/bài tập.bin", "result.bin");
                Path downloads = work.resolve("downloads");
                client.download("Tài liệu/Nhóm 3/result.bin", downloads, null);
                assertArrayEquals(payload, Files.readAllBytes(downloads.resolve("result.bin")));
                assertThrows(IOException.class, () -> client.download("Tài liệu/Nhóm 3/result.bin", downloads, null));
                client.delete("Tài liệu/Nhóm 3/result.bin");
                client.rename("Tài liệu/Nhóm 3", "Nhóm mới");
                client.delete("Tài liệu/Nhóm mới");
                client.delete("Tài liệu");
                assertTrue(client.listFiles().isEmpty());
            }
        }
    }

    @Test void rawServerRejectsTraversalAndSymlinkComponents() throws Exception {
        Path storage = work.resolve("storage");
        Path outside = Files.createDirectory(work.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "private");
        try (FTPServer server = new FTPServer(0, storage, null)) {
            server.start();
            try (FTPClient ignored = registered(server)) {
                for (String path : List.of("../outside", "/tmp", "a/../outside", "a//b", ".accounts.properties", "a\\b")) {
                    try (Raw raw = new Raw(server.getPort())) {
                        raw.login();
                        raw.out.writeUTF(Protocol.LIST); raw.out.writeUTF(path); raw.out.flush();
                        assertEquals(Protocol.ERROR, raw.in.readUTF(), path); raw.in.readUTF();
                    }
                }
                Files.createSymbolicLink(storage.resolve("linked"), outside);
                try (Raw raw = new Raw(server.getPort())) {
                    raw.login();
                    raw.out.writeUTF(Protocol.DOWNLOAD); raw.out.writeUTF("linked/secret.txt"); raw.out.flush();
                    assertEquals(Protocol.ERROR, raw.in.readUTF()); raw.in.readUTF();
                }
            }
        }
        assertEquals("private", Files.readString(outside.resolve("secret.txt")));
    }

    @Test void interruptedUploadCleansStagingAndDoesNotPublishPartialFile() throws Exception {
        Path storage = work.resolve("storage");
        try (FTPServer server = new FTPServer(0, storage, null)) {
            server.start();
            try (FTPClient client = registered(server)) {
                client.createDirectory("nested");
                try (Raw raw = new Raw(server.getPort())) {
                    raw.login();
                    raw.out.writeUTF(Protocol.UPLOAD); raw.out.writeUTF("nested/partial.bin");
                    raw.out.writeLong(100_000); raw.out.flush();
                    assertEquals(Protocol.READY, raw.in.readUTF());
                    raw.out.write(new byte[100]); raw.out.flush();
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                while (System.nanoTime() < deadline) {
                    if (server.getActiveConnections() == 0) break;
                    Thread.sleep(10);
                }
                assertEquals(0, server.getActiveConnections());
                try (var entries = Files.list(storage.resolve("nested"))) { assertEquals(0, entries.count()); }
                assertTrue(client.listFiles("nested").isEmpty());
            }
        }
    }

    @Test void independentClientsTransferConcurrentlyWithoutOverwriting() throws Exception {
        Path storage = work.resolve("storage");
        try (FTPServer server = new FTPServer(0, storage, null)) {
            server.start();
            try (FTPClient first = registered(server)) { first.createDirectory("shared"); }
            Path input = Files.write(work.resolve("same.bin"), new byte[100_000]);
            try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
                CountDownLatch gate = new CountDownLatch(1);
                List<Future<Boolean>> futures = new ArrayList<>();
                for (int i = 0; i < 4; i++) futures.add(executor.submit(() -> {
                    gate.await();
                    try (FTPClient client = new FTPClient("127.0.0.1", server.getPort(), USER, password())) {
                        client.upload(input, "shared", null);
                        return true;
                    } catch (IOException duplicate) { return false; }
                }));
                gate.countDown();
                int successes = 0;
                for (Future<Boolean> result : futures) if (result.get(20, TimeUnit.SECONDS)) successes++;
                assertEquals(1, successes);
            }
            assertEquals(100_000, Files.size(storage.resolve("shared/same.bin")));
            try (var files = Files.list(storage.resolve("shared"))) { assertEquals(1, files.count()); }
        }
    }

    @Test void oversizedLocalFileIsRejectedBeforeDownloadHeader() throws Exception {
        Path storage = work.resolve("storage");
        try (FTPServer server = new FTPServer(0, storage, null)) {
            server.start();
            try (FTPClient ignored = registered(server)) {
                try (RandomAccessFile oversized = new RandomAccessFile(storage.resolve("large.bin").toFile(), "rw")) {
                    oversized.setLength(Protocol.MAX_FILE_SIZE + 1);
                }
                try (Raw raw = new Raw(server.getPort())) {
                    raw.login();
                    raw.out.writeUTF(Protocol.DOWNLOAD); raw.out.writeUTF("large.bin"); raw.out.flush();
                    assertEquals(Protocol.ERROR, raw.in.readUTF());
                    assertTrue(raw.in.readUTF().contains("1 GiB"));
                    raw.out.writeUTF(Protocol.PING); raw.out.flush();
                    assertEquals(Protocol.OK, raw.in.readUTF(), "Rejected download must leave protocol synchronized");
                }
            }
        }
    }

    @Test void shutdownClosesActiveAndQueuedSockets() throws Exception {
        FTPServer server = new FTPServer(0, work.resolve("storage"), null);
        List<Socket> clients = new ArrayList<>();
        try {
            server.start();
            for (int i = 0; i < 24; i++) {
                Socket socket = new Socket("127.0.0.1", server.getPort());
                socket.setSoTimeout(3000); clients.add(socket);
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (server.getActiveConnections() < clients.size() && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(clients.size(), server.getActiveConnections());
            server.close();
            for (Socket socket : clients) assertEquals(-1, socket.getInputStream().read());
            assertEquals(0, server.getActiveConnections());
        } finally {
            server.close();
            for (Socket socket : clients) socket.close();
        }
    }

    @Test void closingClientRejectsNewOperationsAndServerClosesIdempotently() throws Exception {
        FTPServer server = new FTPServer(0, work.resolve("storage"), null);
        server.start();
        FTPClient client = registered(server);
        client.close();
        assertThrows(IOException.class, client::listFiles);
        server.close(); server.close();
        assertFalse(server.isRunning());
    }

    private static class Raw implements AutoCloseable {
        final Socket socket;
        final DataInputStream in;
        final DataOutputStream out;
        Raw(int port) throws IOException {
            socket = new Socket("127.0.0.1", port); socket.setSoTimeout(4000);
            in = new DataInputStream(socket.getInputStream()); out = new DataOutputStream(socket.getOutputStream());
        }
        void login() throws IOException {
            out.writeUTF(Protocol.LOGIN); out.writeUTF(USER); out.writeUTF(new String(password())); out.flush();
            assertEquals(Protocol.OK, in.readUTF());
        }
        @Override public void close() throws IOException { socket.close(); }
    }
}
