package com.miniftp.services;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Local account database. Only salted PBKDF2 hashes are saved; there are no default
 * accounts. Authentication grants access to the shared storage, not a private home.
 * The server keeps this database in a hidden file which its file API rejects.
 */
public final class AuthManager {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{2,31}");
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path accountFile;
    private final Map<String, Credential> accounts = new HashMap<>();

    private record Credential(int iterations, byte[] salt, byte[] hash) { }

    public AuthManager(Path accountFile) throws IOException {
        this.accountFile = accountFile.toAbsolutePath().normalize();
        Path parent = this.accountFile.getParent();
        if (parent == null) throw new IOException("Đường dẫn dữ liệu tài khoản không hợp lệ");
        rejectSymbolicLinks(this.accountFile);
        Files.createDirectories(parent);
        rejectSymbolicLinks(this.accountFile);
        if (Files.exists(this.accountFile, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(this.accountFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Dữ liệu tài khoản không hợp lệ");
            }
            Properties stored = new Properties();
            try (InputStream input = Files.newInputStream(this.accountFile, LinkOption.NOFOLLOW_LINKS)) {
                stored.load(input);
            }
            for (String username : stored.stringPropertyNames()) {
                if (!USERNAME.matcher(username).matches()) throw corruptDatabase();
                accounts.put(username, decode(stored.getProperty(username)));
            }
            restrictPermissions(this.accountFile);
        }
    }

    /** Account names are case sensitive. Passwords are never retained as text. */
    public synchronized void register(String username, char[] password) throws IOException {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new IOException("Tên đăng nhập phải có 3–32 ký tự: chữ, số, _, . hoặc -; bắt đầu bằng chữ hoặc số");
        }
        if (password == null || password.length < 8 || password.length > 128) {
            throw new IOException("Mật khẩu phải có 8–128 ký tự");
        }
        if (accounts.containsKey(username)) throw new IOException("Tên đăng nhập đã tồn tại");
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        Credential credential = new Credential(ITERATIONS, salt, derive(password, salt, ITERATIONS));
        Map<String, Credential> updated = new HashMap<>(accounts);
        updated.put(username, credential);
        save(updated);
        accounts.put(username, credential);
    }

    /** Returns false for every invalid credential without revealing which field failed. */
    public synchronized boolean verifyCredentials(String username, char[] password) throws IOException {
        if (username == null || password == null || password.length < 8 || password.length > 128
                || !USERNAME.matcher(username).matches()) return false;
        Credential credential = accounts.get(username);
        // Do the same expensive operation for an unknown user to reduce enumeration by timing.
        byte[] salt = credential == null ? new byte[SALT_BYTES] : credential.salt();
        int iterations = credential == null ? ITERATIONS : credential.iterations();
        byte[] actual = derive(password, salt, iterations);
        byte[] expected = credential == null ? new byte[HASH_BYTES] : credential.hash();
        try {
            boolean matches = MessageDigest.isEqual(expected, actual);
            return credential != null && matches;
        } finally {
            Arrays.fill(actual, (byte) 0);
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) throws IOException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, HASH_BYTES * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException ex) {
            throw new IOException("Không thể khởi tạo bộ mã hóa mật khẩu", ex);
        } finally {
            spec.clearPassword();
        }
    }

    private void save(Map<String, Credential> updated) throws IOException {
        rejectSymbolicLinks(accountFile);
        Properties stored = new Properties();
        updated.forEach((username, credential) -> stored.setProperty(username, encode(credential)));
        Path temporary = Files.createTempFile(accountFile.getParent(), ".accounts-", ".tmp");
        try {
            restrictPermissions(temporary);
            try (OutputStream output = Files.newOutputStream(temporary)) {
                stored.store(output, "MiniFTP accounts: PBKDF2 hashes only");
            }
            rejectSymbolicLinks(accountFile);
            try {
                Files.move(temporary, accountFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, accountFile, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictPermissions(accountFile);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String encode(Credential credential) {
        Base64.Encoder encoder = Base64.getEncoder();
        return "PBKDF2-SHA256$" + credential.iterations() + "$"
                + encoder.encodeToString(credential.salt()) + "$" + encoder.encodeToString(credential.hash());
    }

    private static Credential decode(String encoded) throws IOException {
        try {
            String[] parts = encoded.split("\\$", -1);
            if (parts.length != 4 || !"PBKDF2-SHA256".equals(parts[0])) throw corruptDatabase();
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] hash = Base64.getDecoder().decode(parts[3]);
            if (iterations < 120_000 || iterations > 2_000_000
                    || salt.length != SALT_BYTES || hash.length != HASH_BYTES) throw corruptDatabase();
            return new Credential(iterations, salt, hash);
        } catch (IllegalArgumentException ex) {
            throw corruptDatabase();
        }
    }

    private static IOException corruptDatabase() {
        return new IOException("Dữ liệu tài khoản bị hỏng hoặc không được hỗ trợ");
    }

    private static void rejectSymbolicLinks(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Đường dẫn dữ liệu tài khoản không được chứa liên kết tượng trưng");
        }
    }

    private static void restrictPermissions(Path path) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
    }
}
