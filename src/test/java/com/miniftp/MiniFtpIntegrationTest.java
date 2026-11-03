package com.miniftp;

import com.miniftp.client.FTPClient;
import com.miniftp.common.FileInfo;
import com.miniftp.server.FTPServer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Không cần JUnit/Maven để chạy test; chạy main() trong IntelliJ.
 * Phần 1: luồng chính. Phần 2: các test hồi quy cho lỗi đã sửa (gửi lệnh thô qua socket
 * để kiểm tra chính Server, không đi qua bước kiểm tra của FTPClient).
 */
public final class MiniFtpIntegrationTest {
    private static final String TEST_USER = "regression-user";
    private static char[] password() { return "Regression-password-2026".toCharArray(); }

    private static FTPClient authenticatedClient(int port) {
        return new FTPClient("127.0.0.1", port, TEST_USER, password());
    }

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("miniftp-test-");
        List<String> serverLog = Collections.synchronizedList(new ArrayList<>());
        try (FTPServer server = new FTPServer(0, work.resolve("server"), serverLog::add)) {
            server.start();
            Path storage = work.resolve("server");
            int port = server.getPort();
            try (FTPClient guest = new FTPClient("127.0.0.1", port)) {
                guest.register(TEST_USER, password());
            }
            try (FTPClient client = authenticatedClient(port)) {
                client.login();
                mainFlow(work, client, port);
                System.out.println("[PASS] Luồng chính: Ping / List / Upload / Download / Mkdir / Delete / 4 client song song");

                errorMessages(work, client);
                System.out.println("[PASS] Thông báo lỗi rõ ràng, không lộ đường dẫn Server");

                serverSideValidation(work, storage, port);
                System.out.println("[PASS] Server tự kiểm tra tên/đường dẫn và giữ kết nối sống sau lỗi");

                partialUploadLeavesNothing(storage, port);
                System.out.println("[PASS] Upload bị ngắt giữa chừng không để lại file rác");

                sameNameUploadRace(work, storage, port);
                System.out.println("[PASS] 4 client upload cùng một tên: chỉ 1 thành công");

                downloadTruncatedMidway(storage, port);
                System.out.println("[PASS] File bị cắt giữa lúc download: Server đóng kết nối, Client không bị treo");

                connectionRefusedMessage();
                System.out.println("[PASS] Server chưa bật: Client báo lỗi tiếng Việt dễ hiểu");

                System.out.println("[PASS] MiniFTP: toàn bộ test thành công");
            }
        } catch (Throwable failure) {
            System.out.println("---- Nhật ký Server ----");
            synchronized (serverLog) { serverLog.forEach(System.out::println); }
            throw failure;
        } finally {
            deleteTestDirectory(work);
        }
    }

    // ------------------------------------------------------------------ Phần 1

    private static void mainFlow(Path work, FTPClient client, int port) throws Exception {
        client.ping();
        assertTrue(client.listFiles().isEmpty(), "Thư mục mới phải rỗng");

        // Hệ điều hành chạy test có thể không biểu diễn được tiếng Việt trong tên file (locale POSIX).
        String folder = supportsVietnameseNames(work) ? "Tài liệu" : "Tai lieu";
        client.createDirectory(folder);
        assertTrue(client.listFiles().stream().anyMatch(FileInfo::directory), "MKDIR");

        byte[] content = new byte[260_000];
        for (int i = 0; i < content.length; i++) content[i] = (byte) (i * 37);
        Path source = work.resolve("hinh-anh.bin");
        Files.write(source, content);
        client.upload(source, null);
        List<FileInfo> listed = client.listFiles();
        assertTrue(listed.stream().anyMatch(f -> f.name().equals("hinh-anh.bin") &&
                f.size() == content.length), "LIST sau upload");

        Path downloadDir = work.resolve("downloads");
        client.download("hinh-anh.bin", downloadDir, null);
        assertTrue(Arrays.equals(content, Files.readAllBytes(downloadDir.resolve("hinh-anh.bin"))),
                "DOWNLOAD đúng từng byte");

        boolean duplicateRejected = false;
        try { client.upload(source, null); }
        catch (IOException expected) { duplicateRejected = true; }
        assertTrue(duplicateRejected, "Không ghi đè file trùng tên");

        boolean traversalRejected = false;
        try { client.delete("../bad.txt"); }
        catch (IOException expected) { traversalRejected = true; }
        assertTrue(traversalRejected, "Chặn path traversal");

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> tasks = IntStream.range(0, 4)
                    .<Callable<Void>>mapToObj(i -> () -> {
                        try (FTPClient another = authenticatedClient(port)) {
                            Path file = work.resolve("parallel-" + i + ".txt");
                            Files.writeString(file, "client-" + i);
                            another.upload(file, null);
                        }
                        return null;
                    }).toList();
            for (Future<Void> result : pool.invokeAll(tasks)) result.get();
        } finally {
            pool.shutdownNow();
        }
        assertTrue(client.listFiles().size() == 6, "4 client upload đồng thời");

        client.delete("hinh-anh.bin");
        client.delete(folder); // Thư mục rỗng
        for (int i = 0; i < 4; i++) client.delete("parallel-" + i + ".txt");
        assertTrue(client.listFiles().isEmpty(), "DELETE");
    }

    // ------------------------------------------------------------------ Phần 2

    /** Trước đây Client nhận về đường dẫn tuyệt đối của Server thay cho thông báo lỗi. */
    private static void errorMessages(Path work, FTPClient client) throws Exception {
        client.createDirectory("abc");
        Path file = work.resolve("trong-thu-muc.txt");
        Files.writeString(file, "x");
        client.upload(file, null);

        String serverPath = work.toString();
        expectError(() -> client.createDirectory("abc"), "Đã tồn tại", serverPath, "MKDIR trùng tên");
        expectError(() -> client.delete("khong-ton-tai"), "Không tìm thấy", serverPath, "DELETE không tồn tại");
        expectError(() -> client.upload(file, null), "Tên file đã tồn tại", serverPath, "UPLOAD trùng tên");
        expectError(() -> client.download("khong-ton-tai", work.resolve("dl"), null),
                "Không tìm thấy", serverPath, "DOWNLOAD không tồn tại");
        expectError(() -> client.download("abc", work.resolve("dl"), null),
                "Không tìm thấy", serverPath, "DOWNLOAD một thư mục");

        client.delete("trong-thu-muc.txt");
        client.delete("abc");
    }

    /** Gửi lệnh thô để chứng minh Server tự bảo vệ, không phụ thuộc kiểm tra phía Client. */
    private static void serverSideValidation(Path work, Path storage, int port) throws Exception {
        // Path traversal
        try (Raw raw = new Raw(port)) {
            assertTrue(raw.command("MKDIR", "../evil").startsWith("ERROR:"), "MKDIR ../evil bị từ chối");
            assertTrue(!Files.exists(work.resolve("evil")), "Không tạo thư mục ngoài vùng lưu trữ");
            assertTrue(raw.command("DELETE", "..").startsWith("ERROR:"), "DELETE .. bị từ chối");
            assertTrue(raw.command("DOWNLOAD", "../../etc/passwd").startsWith("ERROR:"),
                    "DOWNLOAD ngoài vùng lưu trữ bị từ chối");
            raw.out.writeUTF("UPLOAD"); raw.out.writeUTF("../evil.txt"); raw.out.writeLong(3); raw.out.flush();
            assertTrue(raw.reply().startsWith("ERROR:"), "UPLOAD ../evil.txt bị từ chối trước khi nhận dữ liệu");
            // Sau tất cả các lỗi trên, CÙNG kết nối vẫn dùng được.
            assertTrue(raw.command("PING").equals("OK"), "Kết nối vẫn sống sau lỗi");
        }
        // Tên chứa ký tự cấm của Windows: trước đây có thể làm Path ném InvalidPathException,
        // Server cắt kết nối không báo lỗi và GUI hiện "Lỗi: null".
        try (Raw raw = new Raw(port)) {
            for (String bad : new String[] { "a?b", "a*b", "a\"b", "a<b", "a>b", "a|b", "a:b",
                    "CON", "nul.txt", "COM1", "ten-cuoi-cham.", "ten-cuoi-cach ", ".an", "" }) {
                String reply = raw.command("MKDIR", bad);
                assertTrue(reply.startsWith("ERROR:") && reply.length() > "ERROR:".length()
                                && !reply.contains(work.toString()),
                        "MKDIR \"" + bad + "\" phải bị từ chối bằng ERROR, nhận được: " + reply);
            }
            assertTrue(raw.command("PING").equals("OK"), "Kết nối vẫn sống sau tên không hợp lệ");
        }
        // Lệnh lạ và yêu cầu hỏng không làm đứt kết nối / lệch luồng
        try (Raw raw = new Raw(port)) {
            assertTrue(raw.command("HACK").startsWith("ERROR:"), "Lệnh lạ trả ERROR");
            assertTrue(raw.command("DOWNLOAD", "khong-ton-tai").startsWith("ERROR:"), "DOWNLOAD thiếu file trả ERROR");
            assertTrue(raw.command("PING").equals("OK"), "Luồng vẫn đồng bộ sau DOWNLOAD lỗi");
        }
        assertTrue(listNames(storage).isEmpty(), "Các yêu cầu sai không để lại gì trong vùng lưu trữ: " + listNames(storage));
    }

    /** Client chết giữa lúc upload: không được để lại file đích hay file tạm .part. */
    private static void partialUploadLeavesNothing(Path storage, int port) throws Exception {
        try (Raw raw = new Raw(port)) {
            raw.out.writeUTF("UPLOAD"); raw.out.writeUTF("do-dang.bin"); raw.out.writeLong(100_000); raw.out.flush();
            assertTrue(raw.reply().equals("READY"), "Server sẵn sàng nhận");
            raw.out.write(new byte[1000]); // chỉ gửi 1% rồi ngắt
            raw.out.flush();
        }
        // Server xử lý bất đồng bộ: chờ tối đa 3 giây cho nó dọn dẹp.
        long deadline = System.currentTimeMillis() + 3000;
        List<String> leftovers;
        do {
            Thread.sleep(50);
            leftovers = listNames(storage);
        } while (!leftovers.isEmpty() && System.currentTimeMillis() < deadline);
        assertTrue(leftovers.isEmpty(), "Không còn file rác sau upload dở: " + leftovers);
    }

    /** Nhiều Client cùng upload một tên: đúng một người thắng, người khác nhận lỗi rõ ràng. */
    private static void sameNameUploadRace(Path work, Path storage, int port) throws Exception {
        Path file = work.resolve("cung-ten.bin");
        Files.write(file, new byte[2_000_000]);
        int clients = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try (FTPClient client = authenticatedClient(port)) {
                        client.upload(file, null);
                        return "OK";
                    } catch (IOException ex) {
                        return ex.getMessage();
                    }
                }));
            }
            start.countDown();
            int success = 0;
            for (Future<String> result : results) {
                String message = result.get();
                if (message.equals("OK")) success++;
                else assertTrue(message.startsWith("Tên file đã tồn tại") && !message.contains(work.toString()),
                        "Người đến sau phải nhận thông báo trùng tên, nhận được: " + message);
            }
            assertTrue(success == 1, "Chỉ 1 client upload thành công, thực tế: " + success);
        } finally {
            pool.shutdownNow();
        }
        assertTrue(listNames(storage).equals(List.of("cung-ten.bin")), "Chỉ còn đúng một file: " + listNames(storage));
        Files.delete(storage.resolve("cung-ten.bin"));
    }

    /**
     * File bị rút ngắn giữa lúc đang download: Server đã báo kích thước từ đầu nên không thể gửi ERROR;
     * nó phải đóng kết nối để Client biết ngay (trước đây Client chờ hết 30 giây rồi mới báo lỗi timeout).
     */
    private static void downloadTruncatedMidway(Path storage, int port) throws Exception {
        long size = 64L * 1024 * 1024; // lớn hơn nhiều so với bộ đệm socket nên Server bị chặn giữa chừng
        Path big = storage.resolve("lon.bin");
        try (RandomAccessFile f = new RandomAccessFile(big.toFile(), "rw")) { f.setLength(size); }
        try (Raw raw = new Raw(port)) {
            raw.out.writeUTF("DOWNLOAD"); raw.out.writeUTF("lon.bin"); raw.out.flush();
            assertTrue(raw.reply().equals("OK"), "Bắt đầu download");
            assertTrue(raw.in.readLong() == size, "Kích thước file");
            raw.in.readNBytes(1024 * 1024); // đọc 1 MiB rồi dừng, Server sẽ bị chặn khi ghi tiếp
            try (RandomAccessFile f = new RandomAccessFile(big.toFile(), "rw")) { f.setLength(0); }
            long received = 1024 * 1024;
            byte[] buffer = new byte[64 * 1024];
            long started = System.currentTimeMillis();
            int n;
            while ((n = raw.in.read(buffer)) >= 0) received += n; // SocketTimeoutException (5s) nếu bị treo
            assertTrue(received < size, "Chỉ nhận được một phần file");
            assertTrue(System.currentTimeMillis() - started < 4000, "Client được báo ngay bằng việc đóng kết nối");
        } finally {
            Files.deleteIfExists(big);
        }
    }

    private static void connectionRefusedMessage() throws Exception {
        int freePort;
        try (ServerSocket probe = new ServerSocket(0)) { freePort = probe.getLocalPort(); }
        try {
            new FTPClient("127.0.0.1", freePort).ping();
            throw new AssertionError("Phải báo lỗi khi Server chưa bật");
        } catch (IOException ex) {
            assertTrue(ex.getMessage() != null && ex.getMessage().startsWith("Không kết nối được tới"),
                    "Thông báo lỗi kết nối: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ Tiện ích

    /** Kết nối TCP thô, nói đúng giao thức của MiniFTP, để thử Server không qua FTPClient. */
    private static final class Raw implements AutoCloseable {
        final Socket socket;
        final DataInputStream in;
        final DataOutputStream out;

        Raw(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(5000);
            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());
            try {
                String login = command("LOGIN", TEST_USER, new String(password()));
                if (!login.equals("OK")) throw new IOException("Đăng nhập thử nghiệm thất bại: " + login);
            } catch (IOException ex) {
                socket.close();
                throw ex;
            }
        }

        /** Gửi lệnh + các tham số UTF rồi đọc phản hồi một dòng ("OK", "READY" hoặc "ERROR:thông báo"). */
        String command(String command, String... arguments) throws IOException {
            out.writeUTF(command);
            for (String argument : arguments) out.writeUTF(argument);
            out.flush();
            return reply();
        }

        String reply() throws IOException {
            String response = in.readUTF();
            return response.equals("ERROR") ? "ERROR:" + in.readUTF() : response;
        }

        @Override public void close() throws IOException { socket.close(); }
    }

    @FunctionalInterface private interface IoAction { void run() throws Exception; }

    private static void expectError(IoAction action, String messagePrefix, String forbidden, String what) {
        try {
            action.run();
        } catch (IOException ex) {
            String message = ex.getMessage();
            assertTrue(message != null && message.startsWith(messagePrefix),
                    what + ": thông báo phải bắt đầu bằng \"" + messagePrefix + "\", nhận được: " + message);
            assertTrue(!message.contains(forbidden), what + ": thông báo lộ đường dẫn Server: " + message);
            return;
        } catch (Exception ex) {
            throw new AssertionError("Thất bại: " + what + " ném " + ex, ex);
        }
        throw new AssertionError("Thất bại: " + what + " phải báo lỗi");
    }

    private static List<String> listNames(Path directory) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            // Account persistence is expected; staging .part files must still be detected.
            return stream.map(p -> p.getFileName().toString())
                    .filter(name -> !name.equals(".accounts.properties")).sorted().toList();
        }
    }

    private static boolean supportsVietnameseNames(Path directory) {
        try { directory.resolve("Tài liệu"); return true; }
        catch (InvalidPathException ex) { return false; }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError("Thất bại: " + message);
    }

    private static void deleteTestDirectory(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path file : stream.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }
}
