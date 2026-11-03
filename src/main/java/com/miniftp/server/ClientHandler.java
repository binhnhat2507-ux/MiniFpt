package com.miniftp.server;

import com.miniftp.common.FileInfo;
import com.miniftp.common.Protocol;
import com.miniftp.services.FileManager;
import com.miniftp.services.AuthManager;
import java.util.Arrays;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

final class ClientHandler implements Runnable {
    private final Socket socket;
    private final FileManager files;
    private final AuthManager accounts;
    private String username;
    private final Consumer<String> log;
    private final Runnable onClose;

    ClientHandler(Socket socket, FileManager files, AuthManager accounts, Consumer<String> log, Runnable onClose) {
        this.socket = socket;
        this.files = files;
        this.accounts = accounts;
        this.log = log;
        this.onClose = onClose;
    }

    /**
     * Lỗi xảy ra khi đang truyền dữ liệu file: luồng lệnh giữa Client và Server không còn
     * đồng bộ nên KHÔNG thể tiếp tục nhận lệnh trên kết nối này, chỉ có thể đóng kết nối.
     */
    private static final class AbortConnection extends IOException {
        private static final long serialVersionUID = 1L;
        private final boolean canReply;

        AbortConnection(String message, boolean canReply, Throwable cause) {
            super(message, cause);
            this.canReply = canReply;
        }
    }

    @Override
    public void run() {
        try (Socket connection = socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(connection.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(connection.getOutputStream()))) {
            boolean open = true;
            while (open && !connection.isClosed()) {
                String command;
                try { command = in.readUTF(); }
                catch (EOFException ex) { break; }
                if (Protocol.QUIT.equals(command)) break;
                try {
                    handle(command, in, out);
                    out.flush();
                } catch (AbortConnection ex) {
                    log.accept("Đóng kết nối: " + ex.getMessage());
                    if (ex.canReply) sendError(out, ex.getMessage());
                    open = false;
                } catch (IOException ex) {
                    sendError(out, describe(ex));
                    log.accept("Yêu cầu thất bại: " + ex.getMessage());
                } catch (RuntimeException ex) {
                    // Lỗi bất ngờ không được làm chết kết nối mà không báo cho Client.
                    sendError(out, "Lỗi nội bộ trên Server");
                    log.accept("Lỗi nội bộ: " + ex);
                }
            }
        } catch (IOException ex) {
            log.accept("Client ngắt: " + ex.getMessage());
        } finally {
            onClose.run();
        }
    }

    private void handle(String command, DataInputStream in, DataOutputStream out) throws IOException {
        if (!Protocol.PING.equals(command) && !Protocol.LOGIN.equals(command)
                && !Protocol.REGISTER.equals(command) && username == null) {
            // Arguments may already be buffered: do not interpret them as new commands.
            throw new AbortConnection("Vui lòng đăng nhập trước khi thao tác file", true, null);
        }
        switch (command) {
            case Protocol.LOGIN -> authenticate(in, out, false);
            case Protocol.REGISTER -> authenticate(in, out, true);
            case Protocol.PING -> out.writeUTF(Protocol.OK);
            case Protocol.LIST -> listFiles(in, out);
            case Protocol.UPLOAD -> upload(in, out);
            case Protocol.DOWNLOAD -> download(in, out);
            case Protocol.MKDIR -> {
                String name = in.readUTF();
                files.createDirectory(name);
                out.writeUTF(Protocol.OK);
                log.accept("Tạo thư mục: " + name);
            }
            case Protocol.RENAME -> {
                String name = in.readUTF();
                String newName = in.readUTF();
                files.rename(name, newName);
                out.writeUTF(Protocol.OK);
                log.accept("[" + username + "] Đổi tên: " + name + " → " + newName);
            }
            case Protocol.DELETE -> {
                String name = in.readUTF();
                files.delete(name);
                out.writeUTF(Protocol.OK);
                log.accept("Xóa: " + name);
            }
            default -> throw new IOException("Lệnh không hỗ trợ: " + abbreviate(command));
        }
    }

    private void authenticate(DataInputStream in, DataOutputStream out, boolean register) throws IOException {
        String user = in.readUTF();
        char[] password = in.readUTF().toCharArray();
        try {
            if (register) {
                accounts.register(user, password);
                log.accept("Đăng ký tài khoản: " + user);
            } else {
                username = null;
                if (!accounts.verifyCredentials(user, password)) {
                    throw new AbortConnection("Tài khoản hoặc mật khẩu không đúng", true, null);
                }
                username = user;
            }
            out.writeUTF(Protocol.OK);
        } finally { Arrays.fill(password, '\0'); }
    }

    private static void sendError(DataOutputStream out, String message) throws IOException {
        out.writeUTF(Protocol.ERROR);
        out.writeUTF(message);
        out.flush();
    }

    /**
     * Thông báo lỗi gửi cho Client. Ngoại lệ của hệ thống file mang đường dẫn tuyệt đối
     * của Server trong getMessage() nên không được gửi đi nguyên văn.
     */
    private static String describe(IOException ex) {
        if (ex instanceof NoSuchFileException) return "Không tìm thấy file/thư mục";
        if (ex instanceof AccessDeniedException) return "Server không có quyền truy cập file/thư mục này";
        if (ex instanceof FileAlreadyExistsException) return "File/thư mục đã tồn tại";
        if (ex instanceof DirectoryNotEmptyException) return "Thư mục chưa rỗng";
        if (ex instanceof FileSystemException) return "Lỗi hệ thống file trên Server";
        String message = ex.getMessage();
        return message == null || message.isBlank() ? "Lỗi I/O trên Server" : abbreviate(message);
    }

    /** writeUTF giới hạn 65535 byte; cắt ngắn để thông báo lỗi luôn gửi được. */
    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    private void listFiles(DataInputStream in, DataOutputStream out) throws IOException {
        List<FileInfo> entries = files.list(in.readUTF());
        out.writeUTF(Protocol.OK);
        out.writeInt(entries.size());
        for (FileInfo entry : entries) {
            out.writeUTF(entry.name());
            out.writeLong(entry.size());
            out.writeBoolean(entry.directory());
        }
    }

    private void upload(DataInputStream in, DataOutputStream out) throws IOException {
        String name = in.readUTF();
        long size = in.readLong();
        Path target = files.prepareUpload(name, size); // lỗi ở đây: Client chưa gửi dữ liệu, trả ERROR là an toàn
        out.writeUTF(Protocol.READY);
        out.flush();
        try {
            files.receiveFile(target, in, size);
        } catch (IOException ex) {
            // Có thể còn dữ liệu file chưa đọc hết trong luồng => báo lỗi rồi đóng kết nối.
            throw new AbortConnection(describe(ex), true, ex);
        }
        out.writeUTF(Protocol.OK);
        log.accept("Upload: " + name + " (" + size + " bytes)");
    }

    private void download(DataInputStream in, DataOutputStream out) throws IOException {
        String name = in.readUTF();
        // Mở file TRƯỚC khi ghi bất cứ thứ gì: nếu mở lỗi thì trả ERROR sạch sẽ,
        // không để "OK + kích thước" nằm lại trong bộ đệm rồi bị ERROR nối vào sau.
        try (java.nio.channels.SeekableByteChannel channel = files.openDownload(name);
             InputStream input = java.nio.channels.Channels.newInputStream(channel)) {
            long size = channel.size();
            if (size < 0 || size > Protocol.MAX_FILE_SIZE) {
                throw new IOException("File quá lớn (tối đa 1 GiB)");
            }
            out.writeUTF(Protocol.OK);
            out.writeLong(size);
            try {
                byte[] buffer = new byte[64 * 1024];
                long remaining = size;
                while (remaining > 0) {
                    int n = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (n < 0) throw new IOException("File bị thay đổi trong lúc download");
                    out.write(buffer, 0, n);
                    remaining -= n;
                }
            } catch (IOException ex) {
                // Đã gửi tiêu đề kích thước nên không thể gửi ERROR; đóng kết nối để Client biết ngay
                // thay vì chờ hết thời gian chờ 30 giây.
                throw new AbortConnection(describe(ex), false, ex);
            }
        }
        log.accept("Download: " + name);
    }
}
