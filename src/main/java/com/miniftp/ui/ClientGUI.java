package com.miniftp.ui;

import com.miniftp.client.FTPClient;
import com.miniftp.client.TransferProgress;
import com.miniftp.common.FileInfo;
import com.miniftp.common.Protocol;
import javafx.application.Application;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.concurrent.Task;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Giao diện JavaFX; mọi tác vụ mạng và truyền tệp chạy ngoài JavaFX Application Thread. */
public final class ClientGUI extends Application {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "miniftp-client-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final BooleanProperty atRoot = new SimpleBooleanProperty(true);
    private final ObservableList<FileInfo> entries = FXCollections.observableArrayList();
    private final FilteredList<FileInfo> filtered = new FilteredList<>(entries);
    private final SortedList<FileInfo> sorted = new SortedList<>(filtered);
    private final ObservableList<TransferEntry> transfers = FXCollections.observableArrayList();
    private final TextField hostField = new TextField("127.0.0.1");
    private final TextField portField = new TextField(String.valueOf(Protocol.DEFAULT_PORT));
    private final TextField usernameField = new TextField();
    private final PasswordField passwordField = new PasswordField();
    private final PasswordField confirmField = new PasswordField();
    private final Label status = new Label("Sẵn sàng kết nối với máy chủ của bạn.");
    private final Label connectionStatus = new Label("●  Chưa kết nối");
    private final Label directorySummary = new Label();
    private final Label accountLabel = new Label("Phiên chưa đăng nhập");
    private final Label progressText = new Label("Chưa có tác vụ truyền tệp");
    private final ProgressBar progress = new ProgressBar(0);
    private final HBox breadcrumbs = new HBox(7);
    private final BorderPane content = new BorderPane();
    private final TableView<FileInfo> table = new TableView<>();
    private final TextField search = new TextField();
    private Stage stage;
    private FTPClient client;
    private FTPClient pendingClient;
    private String directory = "";
    private String account = "";
    private VBox workspace;
    private VBox historyView;
    private Button filesNav;
    private Button transfersNav;
    private Button logoutButton;
    private boolean registerMode;

    @Override public void start(Stage primaryStage) {
        stage = primaryStage;
        sorted.comparatorProperty().bind(table.comparatorProperty());
        hostField.setId("client-host");
        portField.setId("client-port");
        usernameField.setId("client-username");
        passwordField.setId("client-password");
        confirmField.setId("client-password-confirm");
        table.setId("remote-files");
        status.setId("client-status");
        search.textProperty().addListener((observable, oldValue, value) -> {
            String query = value.toLowerCase(Locale.ROOT).trim();
            filtered.setPredicate(item -> item.name().toLowerCase(Locale.ROOT).contains(query));
            updateSummary();
        });
        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setLeft(buildSidebar());
        root.setCenter(content);
        content.setPadding(new Insets(28, 32, 20, 32));
        content.setTop(buildHeader());
        BorderPane.setMargin(content.getTop(), new Insets(0, 0, 24, 0));
        content.setBottom(buildStatus());
        showAuthentication();
        Scene scene = new Scene(root, 1240, 820);
        var stylesheet = getClass().getResource("/com/miniftp/ui/theme.css");
        if (stylesheet != null) scene.getStylesheets().add(stylesheet.toExternalForm());
        scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.F5 && client != null && !busy.get()) refresh();
        });
        stage.setTitle("MiniFTP — Không gian chia sẻ tệp");
        stage.setMinWidth(1000);
        stage.setMinHeight(760);
        stage.setScene(scene);
        stage.show();
    }

    private Node buildSidebar() {
        Label mark = new Label("M");
        mark.setStyle("-fx-background-color: #2dd4bf; -fx-text-fill: #102d37; -fx-background-radius: 12; -fx-font-size: 24; -fx-font-weight: bold; -fx-padding: 9 15;");
        Label brand = label("MiniFTP", "brand");
        Label tagline = label("CHIA SẺ KHÔNG GIỚI HẠN", "brand-subtitle");
        VBox wordmark = new VBox(4, brand, tagline);
        HBox logo = new HBox(10, mark, wordmark);
        logo.setAlignment(Pos.CENTER_LEFT);
        Label section = label("KHÔNG GIAN LÀM VIỆC", "sidebar-caption");
        VBox.setMargin(section, new Insets(36, 0, 10, 0));
        filesNav = button("▦    Tệp của máy chủ", "nav-item", this::showFiles);
        transfersNav = button("⇅    Lịch sử truyền tệp", "nav-item", this::showHistory);
        filesNav.setMaxWidth(Double.MAX_VALUE);
        transfersNav.setMaxWidth(Double.MAX_VALUE);
        filesNav.disableProperty().bind(busy.or(connectionUnavailable()));
        transfersNav.disableProperty().bind(busy.or(connectionUnavailable()));
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        Label infoTitle = label("Kết nối trực tiếp", "card-title");
        infoTitle.setStyle("-fx-text-fill: #d9e8f1;");
        Label info = label("TCP Socket · Đa luồng\nTệp được lưu trên máy chủ\nTối đa 1 GiB mỗi tệp", "brand-subtitle");
        info.setWrapText(true);
        info.setStyle("-fx-line-spacing: 6; -fx-font-size: 12;");
        VBox note = new VBox(10, infoTitle, info);
        note.setStyle("-fx-background-color: #21364b; -fx-background-radius: 12; -fx-padding: 16;");
        accountLabel.getStyleClass().add("brand-subtitle");
        accountLabel.setWrapText(true);
        logoutButton = button("Đăng xuất", "secondary-button", this::logout);
        logoutButton.setId("client-logout");
        logoutButton.disableProperty().bind(busy.or(connectionUnavailable()));
        logoutButton.setMaxWidth(Double.MAX_VALUE);
        VBox sidebar = new VBox(12, logo, section, filesNav, transfersNav, spacer, note, accountLabel, logoutButton);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPadding(new Insets(30, 20, 24, 20));
        sidebar.setPrefWidth(250);
        sidebar.setStyle("-fx-pref-width: 250; -fx-min-width: 250;");
        return sidebar;
    }

    private final BooleanProperty disconnected = new SimpleBooleanProperty(true);
    private BooleanProperty connectionUnavailable() { return disconnected; }

    private Node buildHeader() {
        VBox title = new VBox(6, label("Không gian chia sẻ", "page-title"),
                label("Truy cập, sắp xếp và trao đổi tệp trong một nơi.", "subtitle"));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        connectionStatus.getStyleClass().add("status-offline");
        HBox header = new HBox(20, title, spacer, connectionStatus);
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    private Node buildStatus() {
        status.getStyleClass().add("muted");
        status.setWrapText(true);
        status.setMaxWidth(Double.MAX_VALUE);
        VBox box = new VBox(status);
        box.setPadding(new Insets(16, 0, 0, 0));
        return box;
    }

    private void showAuthentication() {
        registerMode = false;
        VBox welcome = new VBox(18);
        welcome.setPadding(new Insets(34));
        welcome.setPrefWidth(380);
        welcome.setStyle("-fx-background-color: #e8f5f1; -fx-background-radius: 18 0 0 18;");
        Label eyebrow = label("TỆP CỦA BẠN. KẾT NỐI CỦA BẠN.", "field-label");
        eyebrow.setStyle("-fx-text-fill: #13796c; -fx-font-size: 11;");
        Label heading = label("Chia sẻ dễ dàng.\nLàm việc liền mạch.", "page-title");
        heading.setStyle("-fx-font-size: 29; -fx-text-fill: #123e38;");
        heading.setWrapText(true);
        Label intro = label("Kết nối với máy chủ MiniFTP để quản lý tệp trong không gian chung của nhóm.", "subtitle");
        intro.setWrapText(true);
        intro.setStyle("-fx-text-fill: #4a6d65; -fx-line-spacing: 4;");
        Region space = new Region();
        VBox.setVgrow(space, Priority.ALWAYS);
        welcome.getChildren().addAll(eyebrow, heading, intro, space,
                feature("01", "Truyền tệp trực tiếp", "Tải lên, tải xuống và theo dõi tiến trình."),
                feature("02", "Thư mục ngăn nắp", "Duyệt thư mục, đổi tên và tìm tệp nhanh."),
                feature("03", "Tài khoản riêng", "Đăng nhập trước khi truy cập kho chung."));

        Label formTitle = label("Chào mừng trở lại", "page-title");
        Label description = label("Nhập địa chỉ máy chủ và tài khoản để bắt đầu.", "subtitle");
        description.setWrapText(true);
        hostField.setPromptText("Địa chỉ IP hoặc tên máy chủ");
        portField.setPromptText("5000");
        usernameField.setPromptText("Tên đăng nhập");
        usernameField.setTooltip(new Tooltip("3–32 ký tự: chữ, số, _, . hoặc -; bắt đầu bằng chữ hoặc số"));
        passwordField.setPromptText("Mật khẩu (8–128 ký tự)");
        confirmField.setPromptText("Nhập lại mật khẩu");
        for (Control field : List.of(hostField, portField, usernameField, passwordField, confirmField)) {
            field.setMaxWidth(Double.MAX_VALUE);
            field.disableProperty().bind(busy);
        }
        HBox address = new HBox(12, field("Máy chủ", hostField), field("Cổng", portField));
        HBox.setHgrow(address.getChildren().get(0), Priority.ALWAYS);
        portField.setPrefWidth(90);
        VBox confirmBox = field("Xác nhận mật khẩu", confirmField);
        confirmBox.setVisible(false);
        confirmBox.setManaged(false);
        Button submit = button("Đăng nhập  →", "primary-button", () -> authenticate(registerMode));
        submit.setId("client-login");
        submit.setMaxWidth(Double.MAX_VALUE);
        submit.disableProperty().bind(busy);
        Button switchMode = button("Chưa có tài khoản?  Tạo tài khoản", "secondary-button", () -> { });
        switchMode.setOnAction(event -> {
            registerMode = !registerMode;
            confirmBox.setVisible(registerMode);
            confirmBox.setManaged(registerMode);
            formTitle.setText(registerMode ? "Tạo tài khoản" : "Chào mừng trở lại");
            description.setText(registerMode ? "Tài khoản mới có quyền truy cập kho tệp chung." : "Nhập địa chỉ máy chủ và tài khoản để bắt đầu.");
            submit.setText(registerMode ? "Tạo tài khoản & đăng nhập  →" : "Đăng nhập  →");
            switchMode.setText(registerMode ? "Đã có tài khoản?  Đăng nhập" : "Chưa có tài khoản?  Tạo tài khoản");
            clearError();
        });
        switchMode.setId("client-auth-mode");
        switchMode.disableProperty().bind(busy);
        switchMode.setMaxWidth(Double.MAX_VALUE);
        passwordField.setOnAction(event -> { if (!busy.get()) authenticate(registerMode); });
        confirmField.setOnAction(event -> { if (!busy.get()) authenticate(registerMode); });
        Label localNote = label("Chạy máy chủ trước khi kết nối. Kết nối TCP dùng cho mạng tin cậy; chưa mã hóa TLS.", "muted");
        localNote.setWrapText(true);
        VBox form = new VBox(15, formTitle, description, address, field("Tên đăng nhập", usernameField),
                field("Mật khẩu", passwordField), confirmBox, submit, switchMode, localNote);
        form.setPadding(new Insets(32));
        HBox.setHgrow(form, Priority.ALWAYS);
        HBox loginCard = new HBox(welcome, form);
        loginCard.getStyleClass().add("card");
        loginCard.setPadding(Insets.EMPTY);
        loginCard.setStyle("-fx-padding: 0; -fx-spacing: 0;");
        loginCard.setMaxHeight(620);
        VBox container = new VBox(loginCard);
        container.setAlignment(Pos.CENTER);
        content.setCenter(container);
        markNavigation(null);
    }

    private Node feature(String number, String title, String text) {
        Label index = label(number, "card-title");
        index.setMinWidth(40);
        index.setStyle("-fx-text-fill: #13796c; -fx-background-color: #d3ebe3; -fx-background-radius: 10; -fx-padding: 10;");
        Label caption = label(text, "subtitle");
        caption.setWrapText(true);
        caption.setStyle("-fx-font-size: 12; -fx-text-fill: #56766e;");
        VBox words = new VBox(4, label(title, "card-title"), caption);
        HBox.setHgrow(words, Priority.ALWAYS);
        HBox row = new HBox(12, index, words);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void authenticate(boolean registering) {
        final int port;
        final String host = hostField.getText().trim();
        final String username = usernameField.getText().trim();
        if (host.isBlank() || username.isBlank() || passwordField.getText().isBlank()) {
            error("Vui lòng nhập máy chủ, tên đăng nhập và mật khẩu.");
            return;
        }
        try {
            port = Integer.parseInt(portField.getText().trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException invalid) {
            error("Cổng phải là số từ 1 đến 65535.");
            return;
        }
        if (registering && !passwordField.getText().equals(confirmField.getText())) {
            error("Mật khẩu xác nhận không khớp.");
            return;
        }
        char[] secret = passwordField.getText().toCharArray();
        FTPClient candidate = new FTPClient(host, port, username, secret);
        Arrays.fill(secret, '\0');
        final char[] registrationSecret = registering ? passwordField.getText().toCharArray() : null;
        pendingClient = candidate;
        this.<List<FileInfo>>runTask(registering ? "Đang tạo tài khoản…" : "Đang đăng nhập…", task -> {
            try {
                if (registering) {
                    try (FTPClient guest = new FTPClient(host, port)) { guest.register(username, registrationSecret); }
                }
                candidate.login();
                return candidate.listFiles("");
            } catch (Exception ex) {
                candidate.close();
                throw ex;
            } finally {
                if (registrationSecret != null) Arrays.fill(registrationSecret, '\0');
            }
        }, latest -> {
            pendingClient = null;
            client = candidate;
            account = username;
            directory = "";
            entries.setAll(latest);
            passwordField.clear();
            confirmField.clear();
            disconnected.set(false);
            accountLabel.setText("Đang đăng nhập: " + account);
            connectionStatus.setText("●  " + host + ":" + port);
            connectionStatus.getStyleClass().setAll("status-online");
            workspace = buildWorkspace();
            showFiles();
            status.setText("Đã đăng nhập. Kho tệp này được chia sẻ với những người dùng đã xác thực.");
        }, ignored -> { pendingClient = null; candidate.close(); });
    }

    private VBox buildWorkspace() {
        search.setPromptText("Tìm theo tên trong thư mục hiện tại…");
        search.setPrefWidth(300);
        search.disableProperty().bind(busy);
        Label heading = label("Tệp trên máy chủ", "card-title");
        Region space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        HBox sectionHeader = new HBox(16, heading, space, search);
        sectionHeader.setAlignment(Pos.CENTER_LEFT);
        Button upload = button("↑  Tải tệp lên", "primary-button", this::upload);
        Button mkdir = button("+  Thư mục mới", "secondary-button", this::createDirectory);
        Button refresh = button("↻  Làm mới", "secondary-button", this::refresh);
        Button up = button("↑  Lên một cấp", "secondary-button", this::parentDirectory);
        Button download = button("↓  Tải xuống", "secondary-button", this::download);
        Button rename = button("Đổi tên", "secondary-button", this::rename);
        Button delete = button("Xóa", "danger-button", this::delete);
        upload.setId("client-upload");
        mkdir.setId("client-mkdir");
        refresh.setId("client-refresh");
        up.setId("client-up");
        download.setId("client-download");
        rename.setId("client-rename");
        delete.setId("client-delete");
        for (Button button : List.of(upload, mkdir, refresh)) button.disableProperty().bind(busy);
        up.disableProperty().bind(busy.or(atRoot));
        download.disableProperty().bind(busy.or(table.getSelectionModel().selectedItemProperty().isNull()));
        rename.disableProperty().bind(busy.or(table.getSelectionModel().selectedItemProperty().isNull()));
        delete.disableProperty().bind(busy.or(table.getSelectionModel().selectedItemProperty().isNull()));
        FlowPane toolbar = new FlowPane(8, 8, upload, mkdir, refresh, up, download, rename, delete);
        toolbar.setPadding(new Insets(18, 0, 12, 0));
        breadcrumbs.setAlignment(Pos.CENTER_LEFT);
        breadcrumbs.getStyleClass().add("breadcrumb");
        breadcrumbs.disableProperty().bind(busy);
        rebuildBreadcrumbs();
        configureTable();
        directorySummary.getStyleClass().add("muted");
        HBox summary = new HBox(directorySummary);
        summary.setPadding(new Insets(10, 0, 0, 0));
        VBox fileCard = new VBox(12, sectionHeader, toolbar, breadcrumbs, table, summary);
        fileCard.getStyleClass().add("card");
        fileCard.setPadding(new Insets(22));
        VBox.setVgrow(table, Priority.ALWAYS);
        VBox.setVgrow(fileCard, Priority.ALWAYS);
        progress.setMaxWidth(Double.MAX_VALUE);
        progressText.getStyleClass().add("transfer-status");
        Label transferHeading = label("Trạng thái truyền tệp", "card-title");
        HBox progressHeader = new HBox(16, transferHeading, progressText);
        progressHeader.setAlignment(Pos.CENTER_LEFT);
        VBox progressCard = new VBox(12, progressHeader, progress,
                label("Không ghi đè tệp có sẵn · Tệp truyền dở được dọn tự động", "muted"));
        progressCard.getStyleClass().add("card");
        progressCard.setPadding(new Insets(18, 22, 18, 22));
        updateSummary();
        return new VBox(18, fileCard, progressCard);
    }

    private void configureTable() {
        table.getColumns().clear();
        TableColumn<FileInfo, String> name = new TableColumn<>("TÊN TỆP / THƯ MỤC");
        name.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().name()));
        name.setCellFactory(column -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                setGraphic(null);
                if (!empty && getTableRow() != null && getTableRow().getItem() != null) {
                    FileInfo info = getTableRow().getItem();
                    Label icon = new Label(info.directory() ? "▰" : "▤");
                    icon.setStyle("-fx-text-fill: " + (info.directory() ? "#d6a432" : "#259487") + "; -fx-font-size: 23;");
                    HBox row = new HBox(12, icon, new Label(item));
                    row.setAlignment(Pos.CENTER_LEFT);
                    setGraphic(row);
                }
            }
        });
        name.setPrefWidth(450);
        TableColumn<FileInfo, String> type = new TableColumn<>("LOẠI");
        type.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().directory() ? "Thư mục" : "Tệp"));
        type.setPrefWidth(120);
        TableColumn<FileInfo, String> size = new TableColumn<>("KÍCH THƯỚC");
        size.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().directory() ? "—" : formatSize(data.getValue().size())));
        size.setPrefWidth(140);
        table.getColumns().addAll(List.of(name, type, size));
        table.setItems(sorted);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(label("Thư mục chưa có tệp phù hợp.\nTải tệp lên hoặc tạo một thư mục để bắt đầu.", "empty-state"));
        table.disableProperty().bind(busy);
        table.setRowFactory(view -> {
            TableRow<FileInfo> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty() && !busy.get()) {
                    if (row.getItem().directory()) navigate(remotePath(row.getItem().name()));
                    else download();
                }
            });
            return row;
        });
        table.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER && !busy.get()) {
                FileInfo selected = table.getSelectionModel().getSelectedItem();
                if (selected != null && selected.directory()) navigate(remotePath(selected.name()));
            }
        });
    }

    private void showFiles() {
        if (client == null) return;
        content.setCenter(workspace);
        markNavigation(filesNav);
    }

    private void showHistory() {
        if (client == null) return;
        if (historyView == null) {
            TableView<TransferEntry> history = new TableView<>(transfers);
            TableColumn<TransferEntry, String> action = historyColumn("TÁC VỤ", entry -> entry.action);
            TableColumn<TransferEntry, String> file = historyColumn("TỆP", entry -> entry.name);
            TableColumn<TransferEntry, String> size = historyColumn("DUNG LƯỢNG", entry -> formatSize(entry.bytes));
            TableColumn<TransferEntry, String> result = historyColumn("KẾT QUẢ", entry -> entry.result);
            TableColumn<TransferEntry, String> time = historyColumn("THỜI GIAN", entry -> entry.time);
            file.setPrefWidth(280);
            result.setPrefWidth(200);
            history.getColumns().addAll(List.of(action, file, size, result, time));
            history.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            history.setPlaceholder(label("Chưa có lượt truyền tệp trong phiên này.", "empty-state"));
            historyView = new VBox(18, label("Lịch sử truyền tệp", "card-title"),
                    label("Tối đa 100 tác vụ gần đây của phiên đang đăng nhập.", "subtitle"), history);
            historyView.setPadding(new Insets(24));
            historyView.getStyleClass().add("card");
            VBox.setVgrow(history, Priority.ALWAYS);
        }
        content.setCenter(historyView);
        markNavigation(transfersNav);
    }

    private TableColumn<TransferEntry, String> historyColumn(String title, java.util.function.Function<TransferEntry, String> value) {
        TableColumn<TransferEntry, String> column = new TableColumn<>(title);
        column.setCellValueFactory(data -> new SimpleStringProperty(value.apply(data.getValue())));
        return column;
    }

    private void markNavigation(Button active) {
        for (Button item : List.of(filesNav, transfersNav)) {
            item.getStyleClass().remove("nav-item-active");
            if (item == active) item.getStyleClass().add("nav-item-active");
        }
    }

    private void rebuildBreadcrumbs() {
        atRoot.set(directory.isEmpty());
        breadcrumbs.getChildren().clear();
        Button root = button("Kho chung", "secondary-button", () -> navigate(""));
        breadcrumbs.getChildren().add(root);
        String path = "";
        if (!directory.isEmpty()) for (String part : directory.split("/")) {
            path = path.isEmpty() ? part : path + "/" + part;
            String destination = path;
            breadcrumbs.getChildren().addAll(label("/", "muted"), button(part, "secondary-button", () -> navigate(destination)));
        }
    }

    private void updateSummary() {
        long files = entries.stream().filter(entry -> !entry.directory()).count();
        long folders = entries.size() - files;
        long bytes = entries.stream().filter(entry -> !entry.directory()).mapToLong(FileInfo::size).sum();
        directorySummary.setText(folders + " thư mục   ·   " + files + " tệp   ·   " + formatSize(bytes)
                + (search.getText().isBlank() ? "" : "   ·   " + filtered.size() + " kết quả"));
    }

    private void refresh() { navigate(directory); }
    private void navigate(String destination) {
        this.<List<FileInfo>>runTask("Đang tải danh sách tệp…", task -> client.listFiles(destination), latest -> {
            directory = destination;
            entries.setAll(latest);
            table.getSelectionModel().clearSelection();
            rebuildBreadcrumbs();
            updateSummary();
            status.setText("Đã cập nhật " + (directory.isEmpty() ? "kho chung" : directory) + ".");
        });
    }
    private void parentDirectory() {
        if (directory.isEmpty()) return;
        int slash = directory.lastIndexOf('/');
        navigate(slash < 0 ? "" : directory.substring(0, slash));
    }
    private String remotePath(String name) { return directory.isEmpty() ? name : directory + "/" + name; }

    private void upload() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn tệp tải lên máy chủ");
        File selected = chooser.showOpenDialog(stage);
        if (selected == null) return;
        Path source = selected.toPath();
        String target = directory;
        String name = source.getFileName().toString();
        long[] transferred = {0};
        this.<List<FileInfo>>runTask("Đang tải lên " + name + "…", task -> {
            client.upload(source, target, tracking(task, transferred));
            return client.listFiles(target);
        }, latest -> {
            entries.setAll(latest);
            updateSummary();
            completeTransfer("Tải lên", name, transferred[0], "Hoàn tất");
        }, failure -> completeTransfer("Tải lên", name, transferred[0], "Thất bại: " + message(failure)));
    }

    private void download() {
        FileInfo item = selected();
        if (item == null) return;
        if (item.directory()) { error("Mở thư mục rồi chọn tệp cần tải xuống."); return; }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Chọn thư mục lưu " + item.name());
        File selected = chooser.showDialog(stage);
        if (selected == null) return;
        String path = remotePath(item.name());
        long[] transferred = {0};
        runTask("Đang tải xuống " + item.name() + "…", task -> {
            client.download(path, selected.toPath(), tracking(task, transferred));
            return null;
        }, ignored -> completeTransfer("Tải xuống", item.name(), transferred[0], "Hoàn tất"),
                failure -> completeTransfer("Tải xuống", item.name(), transferred[0], "Thất bại: " + message(failure)));
    }

    private TransferProgress tracking(WorkTask<?> task, long[] transferred) {
        return (bytes, total) -> { transferred[0] = bytes; task.reportProgress(bytes, total); };
    }

    private void completeTransfer(String action, String name, long bytes, String result) {
        transfers.addFirst(new TransferEntry(action, name, bytes, result,
                LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))));
        if (transfers.size() > 100) transfers.removeLast();
        if (result.equals("Hoàn tất")) {
            progressText.setText(action + " hoàn tất · " + name + " · " + formatSize(bytes));
            status.setText(action + " thành công: " + name);
        } else progressText.setText("Truyền tệp thất bại · " + name);
    }

    private void createDirectory() {
        String name = askName("Tạo thư mục", "Tên thư mục mới", "");
        if (name == null) return;
        String path = remotePath(name);
        mutate("Đang tạo thư mục…", "Đã tạo thư mục " + name, () -> client.createDirectory(path));
    }
    private void rename() {
        FileInfo item = selected();
        if (item == null) return;
        String name = askName("Đổi tên", "Tên mới cho " + item.name(), item.name());
        if (name == null || name.equals(item.name())) return;
        String path = remotePath(item.name());
        mutate("Đang đổi tên…", "Đã đổi tên thành " + name, () -> client.rename(path, name));
    }
    private void delete() {
        FileInfo item = selected();
        if (item == null) return;
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.initOwner(stage);
        dialog.setTitle("Xác nhận xóa");
        dialog.setHeaderText("Xóa “" + item.name() + "” khỏi kho chung?");
        dialog.setContentText("Thao tác không thể hoàn tác. Chỉ có thể xóa thư mục rỗng.");
        ButtonType confirm = new ButtonType("Xóa", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Hủy", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getButtonTypes().setAll(cancel, confirm);
        if (dialog.showAndWait().orElse(cancel) != confirm) return;
        String path = remotePath(item.name());
        mutate("Đang xóa…", "Đã xóa " + item.name(), () -> client.delete(path));
    }
    private void mutate(String doing, String done, IoAction action) {
        String current = directory;
        this.<List<FileInfo>>runTask(doing, task -> { action.run(); return client.listFiles(current); }, latest -> {
            entries.setAll(latest);
            table.getSelectionModel().clearSelection();
            updateSummary();
            status.setText(done);
        });
    }
    private FileInfo selected() {
        FileInfo selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) error("Hãy chọn một tệp hoặc thư mục trước.");
        return selected;
    }
    private String askName(String title, String heading, String initial) {
        TextInputDialog dialog = new TextInputDialog(initial);
        dialog.initOwner(stage);
        dialog.setTitle(title);
        dialog.setHeaderText(heading);
        dialog.setContentText("Tên:");
        return dialog.showAndWait().map(String::trim).filter(value -> !value.isEmpty()).orElse(null);
    }

    private void logout() {
        if (client != null) client.close();
        client = null;
        account = "";
        directory = "";
        entries.clear();
        transfers.clear();
        historyView = null;
        workspace = null;
        search.clear();
        progress.setProgress(0);
        progressText.setText("Chưa có tác vụ truyền tệp");
        passwordField.clear();
        confirmField.clear();
        disconnected.set(true);
        accountLabel.setText("Phiên chưa đăng nhập");
        connectionStatus.setText("●  Chưa kết nối");
        connectionStatus.getStyleClass().setAll("status-offline");
        showAuthentication();
        clearError();
        status.setText("Đã đăng xuất. Đăng nhập để tiếp tục.");
    }

    private <T> void runTask(String doing, Work<T> operation, Consumer<T> success) {
        runTask(doing, operation, success, ignored -> { });
    }
    private <T> void runTask(String doing, Work<T> operation, Consumer<T> success, Consumer<Throwable> failure) {
        if (busy.get()) return;
        busy.set(true);
        clearError();
        status.setText(doing);
        progressText.setText(doing);
        WorkTask<T> task = new WorkTask<>(operation);
        progress.progressProperty().unbind();
        progress.progressProperty().bind(task.progressProperty());
        task.setOnSucceeded(event -> {
            busy.set(false);
            progress.progressProperty().unbind();
            progress.setProgress(task.getProgress() < 0 ? 0 : 1);
            progressText.setText("Sẵn sàng truyền tệp");
            success.accept(task.getValue());
        });
        task.setOnFailed(event -> {
            busy.set(false);
            progress.progressProperty().unbind();
            progress.setProgress(0);
            progressText.setText("Tác vụ thất bại");
            failure.accept(task.getException());
            error(message(task.getException()));
        });
        executor.execute(task);
    }
    private void clearError() { status.setStyle(""); }
    private void error(String text) {
        status.setText("Không thể hoàn tất: " + text);
        status.setStyle("-fx-text-fill: #b44242;");
    }
    private static String message(Throwable failure) {
        if (failure == null) return "Lỗi không xác định";
        String text = failure.getMessage();
        return text == null || text.isBlank() ? failure.getClass().getSimpleName() : text;
    }
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        return label;
    }
    private static Button button(String text, String style, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add(style);
        button.setOnAction(event -> action.run());
        return button;
    }
    private static VBox field(String caption, Control control) {
        return new VBox(7, label(caption, "field-label"), control);
    }
    @Override public void stop() {
        if (client != null) client.close();
        if (pendingClient != null) pendingClient.close();
        executor.shutdownNow();
    }
    @FunctionalInterface private interface IoAction { void run() throws Exception; }
    @FunctionalInterface private interface Work<T> { T run(WorkTask<T> task) throws Exception; }
    private static final class WorkTask<T> extends Task<T> {
        private final Work<T> work;
        WorkTask(Work<T> work) { this.work = work; }
        @Override protected T call() throws Exception { return work.run(this); }
        void reportProgress(long bytes, long total) { updateProgress(total == 0 ? 1 : bytes, total == 0 ? 1 : total); }
    }
    private record TransferEntry(String action, String name, long bytes, String result, String time) { }
}
