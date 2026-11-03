package com.miniftp.client;

import com.miniftp.common.FileInfo;
import com.miniftp.common.Protocol;
import com.miniftp.services.FileManager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Mỗi thao tác sử dụng một kết nối ngắn; phù hợp với tác vụ nền JavaFX. */
public final class FTPClient implements AutoCloseable {
    private final String host;
    private final int port;
    private final String username;
    private final char[] password;
    private volatile boolean closed;
    private final java.util.Set<Socket> activeSockets = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public FTPClient(String host, int port) {
        this(host, port, null, null);
    }

    public FTPClient(String host, int port, String username, char[] password) {
        if (host == null || host.isBlank() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("IP hoặc Port không hợp lệ");
        }
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password == null ? null : password.clone();
    }

    @FunctionalInterface
    private interface Session<T> {
        T run(Connection connection) throws IOException;
    }

    /** Mở kết nối, chạy một thao tác rồi đóng; đổi lỗi mạng thành thông báo tiếng Việt dễ hiểu. */
    private <T> T withConnection(Session<T> session) throws IOException {
        return withConnection(true, session);
    }

    private <T> T withConnection(boolean authenticate, Session<T> session) throws IOException {
        try (Connection c = connect()) {
            if (authenticate && username != null && password != null) {
                c.out.writeUTF(Protocol.LOGIN);
                c.out.writeUTF(username);
                c.out.writeUTF(new String(password));
                c.out.flush();
                check(c.in, Protocol.OK);
            }
            return session.run(c);
        } catch (EOFException ex) {
            throw new IOException("Server đóng kết nối đột ngột", ex);
        } catch (SocketTimeoutException ex) {
            throw new IOException("Server không phản hồi (quá thời gian chờ)", ex);
        }
    }

    private Connection connect() throws IOException {
        if (closed) throw new IOException("Phiên kết nối đã đóng");
        Socket socket = new Socket();
        activeSockets.add(socket);
        try {
            if (closed) throw new IOException("Phiên kết nối đã đóng");
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(30_000);
            return new Connection(socket);
        } catch (IOException ex) {
            try { socket.close(); } catch (IOException ignored) { }
            activeSockets.remove(socket);
            throw describeConnectError(ex);
        }
    }

    private IOException describeConnectError(IOException ex) {
        String target = host + ":" + port;
        if (ex instanceof UnknownHostException) {
            return new IOException("Không tìm thấy máy chủ: " + host, ex);
        }
        if (ex instanceof SocketTimeoutException) {
            return new IOException("Kết nối tới " + target + " quá thời gian chờ", ex);
        }
        if (ex instanceof ConnectException) {
            return new IOException("Không kết nối được tới " + target
                    + " (Server chưa bật, sai IP/Port hoặc bị tường lửa chặn)", ex);
        }
        if (ex instanceof NoRouteToHostException) {
            return new IOException("Không có đường mạng tới " + target, ex);
        }
        return ex;
    }

    private static void check(DataInputStream in, String expected) throws IOException {
        String response = in.readUTF();
        if (Protocol.ERROR.equals(response)) throw new IOException(in.readUTF());
        if (!expected.equals(response)) throw new IOException("Phản hồi sai giao thức: " + response);
    }

    public void login() throws IOException {
        if (username == null || password == null) throw new IOException("Cần nhập tài khoản và mật khẩu");
        withConnection(c -> null);
    }

    public void register(String username, char[] password) throws IOException {
        if (username == null || password == null) throw new IOException("Cần nhập tài khoản và mật khẩu");
        withConnection(false, c -> {
            c.out.writeUTF(Protocol.REGISTER);
            c.out.writeUTF(username);
            c.out.writeUTF(new String(password));
            c.out.flush();
            check(c.in, Protocol.OK);
            return null;
        });
    }

    public void ping() throws IOException {
        withConnection(c -> {
            c.out.writeUTF(Protocol.PING);
            c.out.flush();
            check(c.in, Protocol.OK);
            return null;
        });
    }

    public List<FileInfo> listFiles() throws IOException {
        return listFiles("");
    }

    public List<FileInfo> listFiles(String directory) throws IOException {
        FileManager.validatePath(directory, true);
        return withConnection(c -> {
            c.out.writeUTF(Protocol.LIST);
            c.out.writeUTF(directory);
            c.out.flush();
            check(c.in, Protocol.OK);
            int count = c.in.readInt();
            if (count < 0 || count > 100_000) throw new IOException("Danh sách file bất hợp lệ");
            List<FileInfo> files = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                files.add(new FileInfo(c.in.readUTF(), c.in.readLong(), c.in.readBoolean()));
            }
            return files;
        });
    }

    public void createDirectory(String name) throws IOException {
        FileManager.validatePath(name, false);
        simpleCommand(Protocol.MKDIR, name);
    }

    public void delete(String name) throws IOException {
        FileManager.validatePath(name, false);
        simpleCommand(Protocol.DELETE, name);
    }

    public void rename(String relativePath, String newName) throws IOException {
        FileManager.validatePath(relativePath, false);
        FileManager.validateName(newName);
        withConnection(c -> {
            c.out.writeUTF(Protocol.RENAME);
            c.out.writeUTF(relativePath);
            c.out.writeUTF(newName);
            c.out.flush();
            check(c.in, Protocol.OK);
            return null;
        });
    }

    private void simpleCommand(String command, String argument) throws IOException {
        withConnection(c -> {
            c.out.writeUTF(command);
            c.out.writeUTF(argument);
            c.out.flush();
            check(c.in, Protocol.OK);
            return null;
        });
    }

    public void upload(Path source, TransferProgress progress) throws IOException {
        upload(source, "", progress);
    }

    public void upload(Path source, String directory, TransferProgress progress) throws IOException {
        FileManager.validatePath(directory, true);
        if (!Files.isRegularFile(source)) throw new IOException("Không tìm thấy file nguồn");
        String leaf = source.getFileName().toString();
        FileManager.validateName(leaf);
        String name = directory.isEmpty() ? leaf : directory + "/" + leaf;
        long size = Files.size(source);
        if (size > Protocol.MAX_FILE_SIZE) throw new IOException("File quá lớn (tối đa 1 GiB)");
        withConnection(c -> {
            try (InputStream input = Files.newInputStream(source)) {
                c.out.writeUTF(Protocol.UPLOAD);
                c.out.writeUTF(name);
                c.out.writeLong(size);
                c.out.flush();
                check(c.in, Protocol.READY);
                copyExact(input, c.out, size, progress);
                c.out.flush();
                check(c.in, Protocol.OK);
            }
            return null;
        });
    }

    public void download(String name, Path targetDirectory, TransferProgress progress) throws IOException {
        FileManager.validatePath(name, false);
        Files.createDirectories(targetDirectory);
        String leaf = name.substring(name.lastIndexOf('/') + 1);
        Path destination = targetDirectory.resolve(leaf);
        if (Files.exists(destination)) throw new IOException("File đã tồn tại trên máy: " + name);

        withConnection(c -> {
            c.out.writeUTF(Protocol.DOWNLOAD);
            c.out.writeUTF(name);
            c.out.flush();
            check(c.in, Protocol.OK);
            long size = c.in.readLong();
            if (size < 0 || size > Protocol.MAX_FILE_SIZE) throw new IOException("Kích thước file sai");

            Path temp = Files.createTempFile(targetDirectory, ".miniftp-", ".part");
            try {
                try (OutputStream output = Files.newOutputStream(temp)) {
                    copyExact(c.in, output, size, progress);
                }
                Files.move(temp, destination);
            } finally {
                Files.deleteIfExists(temp);
            }
            return null;
        });
    }

    private static void copyExact(InputStream input, OutputStream output, long size,
                                  TransferProgress progress) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long copied = 0;
        if (progress != null) progress.onProgress(0, size);
        while (copied < size) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, size - copied));
            if (read < 0) throw new IOException("Dữ liệu bị ngắt giữa chừng");
            output.write(buffer, 0, read);
            copied += read;
            if (progress != null) progress.onProgress(copied, size);
        }
    }

    /** Close active transfers and discard this client's retained password. */
    @Override public void close() {
        closed = true;
        if (password != null) java.util.Arrays.fill(password, '\0');
        for (Socket socket : activeSockets) {
            try { socket.close(); } catch (IOException ignored) { }
        }
        activeSockets.clear();
    }

    private final class Connection implements AutoCloseable {
        private final Socket socket;
        private final DataInputStream in;
        private final DataOutputStream out;

        Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }

        @Override public void close() throws IOException {
            activeSockets.remove(socket);
            socket.close();
        }
    }
}
