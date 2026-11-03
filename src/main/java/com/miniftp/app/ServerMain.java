package com.miniftp.app;

import com.miniftp.ui.ServerGUI;
import javafx.application.Application;

/** JavaFX launcher kept separate from Application for classpath-based packaging. */
public final class ServerMain {
    private ServerMain() { }

    public static void main(String[] args) {
        Application.launch(ServerGUI.class, args);
    }
}
