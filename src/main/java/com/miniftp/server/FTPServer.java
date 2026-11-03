package com.miniftp.server;

import com.miniftp.common.Protocol;
import com.miniftp.services.FileManager;
import com.miniftp.services.AuthManager;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class FTPServer implements AutoCloseable {
    private final int requestedPort;
    private final FileManager files;
    private final AuthManager accounts;
    private final Consumer<String> logger;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final ExecutorService handlers = new ThreadPoolExecutor(16, 16, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), runnable -> {
                Thread thread = new Thread(runnable, "mini-ftp-worker");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private boolean closed;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public FTPServer(int port, Path storage, Consumer<String> logger) throws IOException {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Port không hợp lệ");
        this.requestedPort = port;
        this.files = new FileManager(storage);
        this.accounts = new AuthManager(files.root().resolve(".accounts.properties"));
        this.logger = logger;
    }

    public synchronized void start() throws IOException {
        if (closed) throw new IllegalStateException("Server đã đóng; hãy tạo phiên Server mới");
        if (serverSocket != null) throw new IllegalStateException("Server đã khởi động");
        serverSocket = new ServerSocket(requestedPort);
        log("Server đang chạy ở port " + getPort() + " | " + files.root());
        acceptThread = new Thread(this::acceptLoop, "mini-ftp-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized int getPort() {
        return serverSocket == null ? requestedPort : serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException ex) {
                if (serverSocket.isClosed()) break; // Server đang dừng: thoát bình thường
                log("Lỗi nhận kết nối: " + ex.getMessage());
                try { Thread.sleep(100); } // tránh vòng lặp báo lỗi liên tục
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                continue;
            }
            // Lỗi riêng của MỘT kết nối (vừa bị reset, pool đã đóng...) chỉ bỏ kết nối đó,
            // không được làm chết vòng lặp khiến Server ngừng nhận kết nối mới.
            try {
                socket.setSoTimeout(30_000);
                synchronized (this) {
                    if (closed) { socket.close(); break; }
                    clients.add(socket);
                }
                log("Kết nối: " + socket.getRemoteSocketAddress());
                handlers.execute(new ClientHandler(socket, files, accounts, this::log,
                        () -> clients.remove(socket)));
            } catch (IOException | RejectedExecutionException ex) {
                clients.remove(socket);
                try { socket.close(); } catch (IOException ignored) { }
                if (!serverSocket.isClosed()) log("Không xử lý được kết nối: " + ex.getMessage());
            }
        }
    }

    public int getActiveConnections() { return clients.size(); }

    public synchronized boolean isRunning() { return serverSocket != null && !serverSocket.isClosed(); }

    public Path getStorageRoot() { return files.root(); }

    private void log(String message) {
        if (logger != null) logger.accept(message);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) { }
        }
        for (Socket client : clients) {
            try { client.close(); } catch (IOException ignored) { }
        }
        handlers.shutdownNow();
        try { handlers.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        clients.clear();
        log("Server đã dừng");
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : Protocol.DEFAULT_PORT;
        Path storage = Path.of(args.length > 1 ? args[1] : "server_storage");
        FTPServer server = new FTPServer(port, storage, System.out::println);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        System.out.println("Nhấn Ctrl+C để dừng Server.");
        Thread.currentThread().join();
    }
}
