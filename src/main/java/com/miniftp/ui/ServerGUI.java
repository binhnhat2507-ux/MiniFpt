package com.miniftp.ui;

import com.miniftp.common.Protocol;
import com.miniftp.server.FTPServer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.net.BindException;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** JavaFX dashboard. Socket operations and server lifecycle never run on the FX thread. */
public final class ServerGUI extends Application {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_LOG_LINES = 500;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "miniftp-server-dashboard");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentLinkedQueue<String> incomingLogs = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queuedLogs = new AtomicInteger();
    private final ArrayDeque<String> displayedLogs = new ArrayDeque<>();
    private final TextField portField = new TextField(Integer.toString(Protocol.DEFAULT_PORT));
    private final TextField folderField = new TextField(Path.of("server_storage").toAbsolutePath().normalize().toString());
    private final Button startButton = button("Khởi động máy chủ", "primary-button");
    private final Button stopButton = button("Dừng máy chủ", "danger-button");
    private final Button browseButton = button("Chọn thư mục", "secondary-button");
    private final TextArea logs = new TextArea();
    private final Label status = new Label("●  Đã dừng");
    private final Label message = new Label("Chọn thư mục chia sẻ, sau đó khởi động máy chủ.");
    private final Label endpoint = new Label("Chưa mở cổng kết nối");
    private final Label address = new Label("Đang xác định địa chỉ mạng…");
    private final Label uploadCount = new Label("0");
    private final Label downloadCount = new Label("0");
    private final Label eventCount = new Label("0");
    private volatile FTPServer server;
    private volatile boolean closing;
    private boolean busy;
    private long uploads;
    private long downloads;
    private long events;
    private Stage stage;
    private Timeline logTimer;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.setTitle("MiniFTP — Bảng điều khiển máy chủ");
        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setLeft(sidebar());
        root.setCenter(content());
        Scene scene = new Scene(root, 1200, 820);
        var css = ServerGUI.class.getResource("/com/miniftp/ui/theme.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        stage.setScene(scene);
        stage.setMinWidth(1020);
        stage.setMinHeight(720);
        stage.setOnCloseRequest(event -> {
            event.consume();
            closeApplication();
        });
        portField.setId("server-port");
        folderField.setId("server-storage");
        startButton.setId("server-start");
        stopButton.setId("server-stop");
        startButton.setOnAction(event -> startServer());
        stopButton.setOnAction(event -> stopServer());
        browseButton.setOnAction(event -> chooseFolder());
        updateControls();
        logTimer = new Timeline(new KeyFrame(Duration.millis(150), event -> flushLogs()));
        logTimer.setCycleCount(Timeline.INDEFINITE);
        logTimer.play();
        discoverAddresses();
        stage.show();
    }

    private VBox sidebar() {
        Label brand = styled("MiniFTP", "brand");
        Label subtitle = styled("KHÔNG GIAN CHIA SẺ FILE", "brand-subtitle");
        VBox mark = new VBox(7, brand, subtitle);
        mark.setPadding(new Insets(8, 0, 30, 0));
        Label section = styled("MÁY CHỦ", "sidebar-caption");
        Label overview = styled("▦   Bảng điều khiển", "nav-item", "nav-item-active");
        overview.setMaxWidth(Double.MAX_VALUE);
        Label hint = styled("TCP SOCKET  ·  MULTI CLIENT", "sidebar-caption");
        hint.setWrapText(true);
        Region space = new Region();
        VBox.setVgrow(space, Priority.ALWAYS);
        Label note = styled("Một nơi lưu trữ.\nNhiều kết nối.\nChia sẻ dễ dàng.", "brand-subtitle");
        note.setStyle("-fx-line-spacing: 7;");
        VBox box = new VBox(16, mark, section, overview, space, note, new Separator(), hint);
        box.getStyleClass().add("sidebar");
        box.setPrefWidth(224);
        box.setMinWidth(224);
        box.setPadding(new Insets(28, 20, 25, 20));
        return box;
    }

    private ScrollPane content() {
        Label eyebrow = styled("MINIFTP / SERVER", "eyebrow", "muted");
        Label title = styled("Bảng điều khiển máy chủ", "page-title");
        Label description = styled("Quản lý không gian lưu trữ và theo dõi hoạt động chia sẻ file.", "subtitle");
        description.setWrapText(true);
        VBox heading = new VBox(8, eyebrow, title, description);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        status.getStyleClass().add("status-offline");
        HBox header = new HBox(18, heading, spacer, status);
        header.setAlignment(Pos.CENTER_LEFT);

        HBox metrics = new HBox(16, metric("Tải lên thành công", uploadCount, "Client → Máy chủ"),
                metric("Tải xuống thành công", downloadCount, "Máy chủ → Client"),
                metric("Sự kiện đã ghi nhận", eventCount, "Trong phiên ứng dụng này"));
        for (var node : metrics.getChildren()) HBox.setHgrow(node, Priority.ALWAYS);

        VBox config = configurationCard();
        VBox connection = connectionCard();
        HBox settings = new HBox(18, config, connection);
        HBox.setHgrow(config, Priority.ALWAYS);
        config.setMaxWidth(Double.MAX_VALUE);
        connection.setPrefWidth(285);
        connection.setMinWidth(250);

        VBox logCard = logCard();
        message.getStyleClass().add("muted");
        message.setWrapText(true);
        message.setId("server-message");
        VBox body = new VBox(24, header, metrics, settings, logCard, message);
        body.setPadding(new Insets(32));
        body.setFillWidth(true);
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        return scroll;
    }

    private VBox metric(String title, Label value, String caption) {
        value.getStyleClass().add("metric-value");
        value.setStyle("-fx-font-size: 30px; -fx-font-weight: bold; -fx-text-fill: #0f766e;");
        Label label = styled(title, "metric-label");
        Label detail = styled(caption, "muted");
        detail.setStyle("-fx-font-size: 11px;");
        VBox card = new VBox(6, label, value, detail);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(18, 20, 18, 20));
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private VBox configurationCard() {
        Label title = styled("Cấu hình máy chủ", "card-title");
        Label hint = styled("Thiết lập trước khi nhận kết nối từ client.", "muted");
        hint.setWrapText(true);
        portField.setPrefWidth(110);
        portField.setMaxWidth(150);
        VBox port = new VBox(7, styled("CỔNG TCP", "field-label"), portField);
        folderField.setPromptText("Đường dẫn thư mục chia sẻ");
        HBox storageInput = new HBox(8, folderField, browseButton);
        HBox.setHgrow(folderField, Priority.ALWAYS);
        VBox storage = new VBox(7, styled("THƯ MỤC CHIA SẺ", "field-label"), storageInput);
        HBox actions = new HBox(10, startButton, stopButton);
        actions.setPadding(new Insets(3, 0, 0, 0));
        VBox card = new VBox(13, title, hint, port, storage, actions);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(22));
        return card;
    }

    private VBox connectionCard() {
        endpoint.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #0f766e;");
        endpoint.setWrapText(true);
        address.setWrapText(true);
        address.getStyleClass().add("muted");
        Label instructions = styled("1. Khởi động máy chủ.\n2. Mở MiniFTP Client.\n3. Nhập IP, cổng và tạo tài khoản.", "muted");
        instructions.setWrapText(true);
        instructions.setStyle("-fx-line-spacing: 5;");
        Label sharing = styled("Các tài khoản đã đăng nhập dùng chung thư mục chia sẻ.", "muted");
        sharing.setWrapText(true);
        VBox card = new VBox(12, styled("Thông tin kết nối", "card-title"), endpoint, address,
                new Separator(), instructions, sharing);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(22));
        return card;
    }

    private VBox logCard() {
        Button clear = button("Xóa nhật ký hiển thị", "secondary-button");
        clear.setOnAction(event -> {
            displayedLogs.clear();
            logs.clear();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(12, styled("Nhật ký hoạt động", "card-title"), spacer, clear);
        top.setAlignment(Pos.CENTER_LEFT);
        logs.setId("server-logs");
        logs.setEditable(false);
        logs.setWrapText(false);
        logs.setPrefRowCount(10);
        logs.setMinHeight(200);
        logs.getStyleClass().add("server-log");
        logs.setStyle("-fx-font-family: 'Monospaced'; -fx-font-size: 12px;");
        Label note = styled("TCP tùy chỉnh • Dùng trong mạng LAN tin cậy • Nhật ký hiển thị tối đa 500 dòng", "muted");
        note.setWrapText(true);
        VBox card = new VBox(13, top, logs, note);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(22));
        return card;
    }

    private void chooseFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Chọn thư mục chia sẻ trên máy chủ");
        try {
            Path path = Path.of(folderField.getText().trim());
            if (path.toFile().isDirectory()) chooser.setInitialDirectory(path.toFile());
        } catch (InvalidPathException ignored) { }
        var selected = chooser.showDialog(stage);
        if (selected != null) folderField.setText(selected.getAbsolutePath());
    }

    private void startServer() {
        if (busy || server != null || closing) return;
        final int port;
        final Path storage;
        try {
            port = Integer.parseInt(portField.getText().trim());
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Cổng phải từ 1 đến 65535.");
            if (folderField.getText().isBlank()) throw new IllegalArgumentException("Vui lòng chọn thư mục chia sẻ.");
            storage = Path.of(folderField.getText().trim()).toAbsolutePath().normalize();
        } catch (IllegalArgumentException ex) {
            showError(ex instanceof NumberFormatException ? "Cổng phải là một số nguyên từ 1 đến 65535." : ex.getMessage());
            return;
        }
        busy = true;
        message.setText("Đang khởi động máy chủ…");
        message.setStyle("");
        updateControls();
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                FTPServer candidate = new FTPServer(port, storage, ServerGUI.this::appendLog);
                try {
                    candidate.start();
                    if (closing) candidate.close();
                    else server = candidate;
                } catch (Exception ex) {
                    candidate.close();
                    throw ex;
                }
                return null;
            }
        };
        task.setOnSucceeded(event -> {
            busy = false;
            if (closing) return;
            folderField.setText(storage.toString());
            message.setText("Máy chủ sẵn sàng. Client có thể đăng ký hoặc đăng nhập để chia sẻ file.");
            updateControls();
        });
        task.setOnFailed(event -> {
            busy = false;
            if (closing) return;
            Throwable ex = task.getException();
            showError(ex instanceof BindException
                    ? "Cổng " + port + " đang được sử dụng. Hãy chọn một cổng khác."
                    : "Không thể khởi động: " + readable(ex));
            updateControls();
        });
        worker.execute(task);
    }

    private void stopServer() {
        if (busy || server == null || closing) return;
        busy = true;
        message.setText("Đang đóng các kết nối và dừng máy chủ…");
        message.setStyle("");
        updateControls();
        Task<Void> task = new Task<>() {
            @Override protected Void call() {
                closeCurrentServer();
                return null;
            }
        };
        task.setOnSucceeded(event -> {
            busy = false;
            message.setText("Máy chủ đã dừng. Dữ liệu và tài khoản được giữ lại trong thư mục lưu trữ.");
            updateControls();
        });
        task.setOnFailed(event -> {
            busy = false;
            showError("Không thể dừng máy chủ: " + readable(task.getException()));
            updateControls();
        });
        worker.execute(task);
    }

    private void updateControls() {
        boolean running = server != null;
        startButton.setDisable(busy || running || closing);
        stopButton.setDisable(busy || !running || closing);
        portField.setDisable(busy || running || closing);
        folderField.setDisable(busy || running || closing);
        browseButton.setDisable(busy || running || closing);
        status.setText(busy ? "●  Đang xử lý" : running ? "●  Đang hoạt động" : "●  Đã dừng");
        status.getStyleClass().removeAll("status-online", "status-offline");
        status.getStyleClass().add(running ? "status-online" : "status-offline");
        endpoint.setText(running ? "Cổng TCP  " + server.getPort() : "Chưa mở cổng kết nối");
    }

    private void appendLog(String message) {
        if (queuedLogs.incrementAndGet() <= 2000) {
            incomingLogs.add("[" + TIME.format(LocalTime.now()) + "] " + message);
        } else {
            queuedLogs.decrementAndGet();
        }
    }

    private void flushLogs() {
        String line;
        boolean changed = false;
        while ((line = incomingLogs.poll()) != null) {
            queuedLogs.decrementAndGet();
            displayedLogs.addLast(line);
            while (displayedLogs.size() > MAX_LOG_LINES) displayedLogs.removeFirst();
            events++;
            if (line.contains("] Upload:")) uploads++;
            if (line.contains("] Download:")) downloads++;
            changed = true;
        }
        if (changed) {
            logs.setText(String.join("\n", displayedLogs) + "\n");
            logs.positionCaret(logs.getLength());
            uploadCount.setText(Long.toString(uploads));
            downloadCount.setText(Long.toString(downloads));
            eventCount.setText(Long.toString(events));
        }
    }

    private void discoverAddresses() {
        worker.execute(() -> {
            List<String> found = new ArrayList<>();
            try {
                for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!network.isUp() || network.isLoopback()) continue;
                    for (var ip : Collections.list(network.getInetAddresses())) {
                        if (ip instanceof Inet4Address && !ip.isLoopbackAddress()) found.add(ip.getHostAddress());
                    }
                }
            } catch (Exception ignored) { }
            String text = "Trên máy này: 127.0.0.1\n"
                    + (found.isEmpty() ? "IP LAN: kiểm tra cấu hình mạng của máy chủ."
                    : "IP mạng: " + String.join(", ", found));
            Platform.runLater(() -> { if (!closing) address.setText(text); });
        });
    }

    private void showError(String text) {
        message.setText(text);
        message.setStyle("-fx-text-fill: #b91c1c;");
    }

    private void closeCurrentServer() {
        FTPServer current = server;
        if (current != null) {
            current.close();
            server = null;
        }
    }

    private void closeApplication() {
        if (closing) return;
        closing = true;
        stage.getScene().getRoot().setDisable(true);
        message.setText("Đang dừng máy chủ và đóng ứng dụng…");
        // A single worker orders this cleanup after a possible in-flight start.
        worker.execute(() -> {
            closeCurrentServer();
            Platform.runLater(Platform::exit);
        });
        worker.shutdown();
    }

    @Override public void stop() {
        closing = true;
        if (logTimer != null) logTimer.stop();
        // Fallback for programmatic Platform.exit(), which skips the stage close request.
        if (!worker.isShutdown()) {
            worker.execute(this::closeCurrentServer);
            worker.shutdown();
        }
    }

    private static String readable(Throwable error) {
        return error == null || error.getMessage() == null ? "Lỗi không xác định" : error.getMessage();
    }

    private static Label styled(String text, String... classes) {
        Label label = new Label(text);
        label.getStyleClass().addAll(classes);
        return label;
    }

    private static Button button(String text, String cssClass) {
        Button button = new Button(text);
        button.getStyleClass().add(cssClass);
        return button;
    }
}
