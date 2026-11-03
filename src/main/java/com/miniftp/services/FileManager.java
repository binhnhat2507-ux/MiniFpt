package com.miniftp.services;

import com.miniftp.common.FileInfo;
import com.miniftp.common.Protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Chỉ thao tác trên một thư mục gốc, không cho truy cập đường dẫn tùy ý.
 * Mọi lỗi ném ra đều có thông báo tiếng Việt, KHÔNG chứa đường dẫn tuyệt đối của Server
 * (thông báo này được gửi nguyên văn cho Client).
 */
public final class FileManager {
    private static final int MAX_NAME_CHARS = 120;
    private static final int MAX_NAME_BYTES = 255; // giới hạn tên file của hầu hết hệ thống file
    private static final String ILLEGAL_CHARS = "<>:\"/\\|?*"; // cấm trên Windows (và / \ ở mọi nơi)
    /** Tên thiết bị mà Windows dành riêng (CON, NUL, COM1...), kể cả khi có đuôi như con.txt. */
    private static final Set<String> RESERVED_NAMES = Stream.concat(
                    Stream.of("CON", "PRN", "AUX", "NUL"),
                    IntStream.rangeClosed(1, 9).boxed().flatMap(i -> Stream.of("COM" + i, "LPT" + i)))
            .collect(Collectors.toUnmodifiableSet());

    private final Path root;
    /** Active staging directories cannot be renamed while a transfer is in progress. */
    private final Map<Path, Integer> activeUploads = new HashMap<>();

    public FileManager(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        rejectRootSymlinks();
        Files.createDirectories(this.root);
        if (Files.isSymbolicLink(this.root) || !Files.isDirectory(this.root)) {
            throw new IOException("Thư mục lưu trữ không hợp lệ");
        }
    }

    private void rejectRootSymlinks() throws IOException {
        Path ancestor = root.getRoot();
        for (Path segment : root) {
            ancestor = ancestor.resolve(segment);
            if (Files.isSymbolicLink(ancestor)) throw new IOException("Thư mục lưu trữ không được chứa liên kết tượng trưng");
        }
    }

    public Path root() {
        return root;
    }

    public static void validateName(String name) throws IOException {
        if (name == null || name.isBlank()) {
            throw invalidName("không được để trống");
        }
        if (name.length() > MAX_NAME_CHARS || name.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES) {
            throw invalidName("quá dài (tối đa " + MAX_NAME_CHARS + " ký tự)");
        }
        if (name.startsWith(".")) {
            throw invalidName("không được bắt đầu bằng dấu chấm");
        }
        if (name.endsWith(".") || name.endsWith(" ")) {
            throw invalidName("không được kết thúc bằng dấu chấm hoặc khoảng trắng");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (ILLEGAL_CHARS.indexOf(c) >= 0 || Character.isISOControl(c)) {
                throw invalidName("chứa ký tự không được phép (< > : \" / \\ | ? * hoặc ký tự điều khiển)");
            }
        }
        int dot = name.indexOf('.');
        String baseName = (dot < 0 ? name : name.substring(0, dot)).trim();
        if (RESERVED_NAMES.contains(baseName.toUpperCase(Locale.ROOT))) {
            throw invalidName("trùng tên thiết bị dành riêng của Windows (" + baseName + ")");
        }
    }

    private static IOException invalidName(String reason) {
        return new IOException("Tên file/thư mục không hợp lệ: " + reason);
    }

    /** Slash-separated relative paths; the empty string denotes the shared root only. */
    public static void validatePath(String path, boolean allowRoot) throws IOException {
        if (path == null || path.length() > 4096) throw new IOException("Đường dẫn không hợp lệ");
        if (path.isEmpty() && allowRoot) return;
        if (path.isEmpty() || path.startsWith("/") || path.contains("\\")) {
            throw new IOException("Đường dẫn không được phép");
        }
        for (String part : path.split("/", -1)) validateName(part);
    }

    private Path resolve(String path, boolean allowRoot) throws IOException {
        validatePath(path, allowRoot);
        Path result = root;
        rejectRootSymlinks();
        try {
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Thư mục lưu trữ không hợp lệ");
            }
            if (!path.isEmpty()) {
                for (String component : path.split("/")) {
                    result = result.resolve(component);
                    if (Files.isSymbolicLink(result)) throw new IOException("Đường dẫn không được phép");
                }
            }
        } catch (InvalidPathException ex) {
            throw invalidName("hệ điều hành của Server không chấp nhận tên này");
        }
        result = result.normalize();
        if (!result.startsWith(root)) throw new IOException("Đường dẫn không được phép");
        return result;
    }

    private Path resolve(String path) throws IOException { return resolve(path, false); }

    public List<FileInfo> list() throws IOException { return list(""); }

    public synchronized List<FileInfo> list(String directory) throws IOException {
        Path location = resolve(directory, true);
        if (!Files.isDirectory(location, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Không tìm thấy thư mục");
        }
        try (Stream<Path> files = Files.list(location)) {
            return files.filter(path -> !path.getFileName().toString().startsWith("."))
                    .map(path -> {
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class,
                                    LinkOption.NOFOLLOW_LINKS);
                            if (!attrs.isDirectory() && !attrs.isRegularFile()) return null;
                            return new FileInfo(path.getFileName().toString(),
                                    attrs.isDirectory() ? 0 : attrs.size(), attrs.isDirectory());
                        } catch (IOException ignored) { return null; }
                    })
                    .filter(info -> info != null)
                    .sorted(Comparator.comparing(FileInfo::directory).reversed()
                            .thenComparing(FileInfo::name, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }

    public synchronized void createDirectory(String name) throws IOException {
        Path directory = resolve(name);
        try {
            Files.createDirectory(directory);
        } catch (FileAlreadyExistsException ex) {
            throw new IOException("Đã tồn tại file/thư mục tên: " + name);
        }
    }

    public synchronized void delete(String name) throws IOException {
        Path target = resolve(name);
        ensureNotTransferring(target);
        try {
            // Nonempty directories must be emptied explicitly, never deleted recursively.
            Files.delete(target);
        } catch (NoSuchFileException ex) {
            throw new IOException("Không tìm thấy file/thư mục: " + name);
        } catch (DirectoryNotEmptyException ex) {
            throw new IOException("Thư mục chưa rỗng, không thể xóa: " + name);
        }
    }

    public synchronized void rename(String path, String newName) throws IOException {
        validateName(newName);
        Path source = resolve(path);
        ensureNotTransferring(source);
        String parent = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
        Path destination = resolve(parent + newName);
        if (source.equals(destination)) {
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Không tìm thấy file/thư mục");
            return;
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Tên file/thư mục đã tồn tại");
        Files.move(source, destination);
    }

    private void ensureNotTransferring(Path path) throws IOException {
        if (activeUploads.keySet().stream().anyMatch(parent -> parent.startsWith(path))) {
            throw new IOException("Thư mục đang nhận file, vui lòng thử lại khi truyền xong");
        }
    }

    public synchronized Path prepareUpload(String name, long size) throws IOException {
        validateSize(size);
        Path target = resolve(name);
        if (!Files.isDirectory(target.getParent(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Không tìm thấy thư mục đích");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Tên file đã tồn tại: " + name);
        }
        return target;
    }

    private static void validateSize(long size) throws IOException {
        if (size < 0 || size > Protocol.MAX_FILE_SIZE) throw new IOException("Kích thước file không hợp lệ (tối đa 1 GiB)");
    }

    public void receiveFile(Path target, InputStream input, long size) throws IOException {
        validateSize(size);
        Path temp;
        Path parent;
        String relative;
        synchronized (this) {
            Path normalized = target.toAbsolutePath().normalize();
            if (!normalized.startsWith(root) || normalized.equals(root)) throw new IOException("Đường dẫn không được phép");
            relative = root.relativize(normalized).toString().replace(java.io.File.separatorChar, '/');
            target = prepareUpload(relative, size);
            parent = target.getParent();
            temp = Files.createTempFile(parent, ".incoming-", ".part");
            activeUploads.merge(parent, 1, Integer::sum);
        }
        try {
            try (OutputStream output = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[64 * 1024];
                long remaining = size;
                while (remaining > 0) {
                    int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) throw new IOException("Client ngắt kết nối giữa lúc upload");
                    output.write(buffer, 0, read);
                    remaining -= read;
                }
            }
            synchronized (this) {
                // Revalidate before commit and serialize the existence check with all renames/uploads.
                target = prepareUpload(relative, size);
                Files.move(temp, target);
            }
        } finally {
            try { Files.deleteIfExists(temp); }
            finally { synchronized (this) { activeUploads.computeIfPresent(parent, (key, count) -> count > 1 ? count - 1 : null); } }
        }
    }

    /** Open while holding the same lock as rename/delete: size and bytes use one file descriptor. */
    public synchronized java.nio.channels.SeekableByteChannel openDownload(String name) throws IOException {
        Path file = getDownloadFile(name);
        return Files.newByteChannel(file, java.nio.file.StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    }

    public synchronized Path getDownloadFile(String name) throws IOException {
        Path file = resolve(name);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Không tìm thấy file: " + name);
        }
        return file;
    }
}
