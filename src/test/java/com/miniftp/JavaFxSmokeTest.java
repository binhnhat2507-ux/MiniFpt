package com.miniftp;

import com.miniftp.common.FileInfo;
import com.miniftp.ui.ClientGUI;
import com.miniftp.ui.ServerGUI;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real JavaFX windows, registration/login against the dashboard's real TCP server. */
@EnabledIfSystemProperty(named = "miniftp.uiSmoke", matches = "true")
class JavaFxSmokeTest {
    @TempDir Path temporary;

    @Test @Timeout(60)
    void serverAndClientRenderAndAuthenticateWithoutBlockingFxThread() throws Exception {
        Platform.startup(() -> Platform.setImplicitExit(false));
        int port;
        try (ServerSocket available = new ServerSocket(0)) { port = available.getLocalPort(); }
        Path storage = Files.createDirectory(temporary.resolve("shared"));
        Files.createDirectory(storage.resolve("Tai lieu"));
        Files.writeString(storage.resolve("hello.txt"), "MiniFTP JavaFX smoke fixture");
        ServerGUI server = fx(ServerGUI::new);
        ClientGUI client = fx(ClientGUI::new);
        Stage serverStage = fx(Stage::new);
        Stage clientStage = fx(Stage::new);
        try {
            fx(() -> {
                server.start(serverStage);
                field(serverStage, "server-port").setText(Integer.toString(port));
                field(serverStage, "server-storage").setText(storage.toString());
                button(serverStage, "server-start").fire();
                return null;
            });
            await(() -> !button(serverStage, "server-stop").isDisabled());
            fx(() -> { screenshot(serverStage, "server"); client.start(clientStage); screenshot(clientStage, "login"); return null; });
            fx(() -> {
                field(clientStage, "client-port").setText(Integer.toString(port));
                field(clientStage, "client-username").setText("smoke-user");
                field(clientStage, "client-password").setText("SmokeTest-Only-42");
                button(clientStage, "client-auth-mode").fire();
                field(clientStage, "client-password-confirm").setText("SmokeTest-Only-42");
                button(clientStage, "client-login").fire();
                return null;
            });
            await(() -> clientStage.getScene().lookup("#remote-files") != null);
            fx(() -> {
                TableView<?> table = (TableView<?>) clientStage.getScene().lookup("#remote-files");
                assertEquals(2, table.getItems().size());
                assertTrue(table.getItems().stream().map(FileInfo.class::cast).anyMatch(f -> f.name().equals("hello.txt")));
                assertFalse(button(clientStage, "client-logout").isDisabled());
                screenshot(clientStage, "workspace");
                button(clientStage, "client-logout").fire();
                assertNotNull(clientStage.getScene().lookup("#client-login"));
                field(clientStage, "client-password").setText("wrong-password");
                button(clientStage, "client-login").fire();
                return null;
            });
            await(() -> !button(clientStage, "client-login").isDisabled());
            fx(() -> {
                assertNull(clientStage.getScene().lookup("#remote-files"));
                Label status = (Label) clientStage.getScene().lookup("#client-status");
                assertTrue(status.getText().contains("không đúng"), status.getText());
                field(clientStage, "client-password").setText("SmokeTest-Only-42");
                button(clientStage, "client-login").fire();
                return null;
            });
            await(() -> clientStage.getScene().lookup("#remote-files") != null);
            Files.writeString(storage.resolve("new-file.txt"), "refresh fixture");
            fx(() -> { button(clientStage, "client-refresh").fire(); return null; });
            await(() -> ((TableView<?>)clientStage.getScene().lookup("#remote-files")).getItems().size() == 3);
            fx(() -> { button(serverStage, "server-stop").fire(); return null; });
            await(() -> !button(serverStage, "server-start").isDisabled());
            assertThrows(java.io.IOException.class, () -> new java.net.Socket("127.0.0.1", port));
        } finally {
            fx(() -> { client.stop(); server.stop(); clientStage.hide(); serverStage.hide(); return null; });
            Platform.exit();
        }
    }

    private static TextInputControl field(Stage stage, String id) {
        return (TextInputControl)stage.getScene().lookup("#" + id);
    }
    private static Button button(Stage stage, String id) {
        return (Button)stage.getScene().lookup("#" + id);
    }
    private static <T> T fx(Callable<T> operation) throws Exception {
        FutureTask<T> task = new FutureTask<>(operation);
        Platform.runLater(task);
        return task.get(8, TimeUnit.SECONDS);
    }
    private static void await(Callable<Boolean> ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (fx(ready)) return;
            Thread.sleep(40);
        }
        fail("Timed out waiting for JavaFX state");
    }
    private static void screenshot(Stage stage, String name) throws Exception {
        Scene scene = stage.getScene();
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        WritableImage image = scene.snapshot(null);
        BufferedImage png = new BufferedImage((int)image.getWidth(), (int)image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++)
            png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        Path directory = Files.createDirectories(Path.of("target", "screenshots"));
        ImageIO.write(png, "png", directory.resolve(name + ".png").toFile());
    }
}
