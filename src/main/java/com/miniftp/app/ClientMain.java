package com.miniftp.app;

import com.miniftp.ui.ClientGUI;
import javafx.application.Application;

/** Điểm vào cho ứng dụng JavaFX Client. */
public final class ClientMain {
    private ClientMain() { }
    public static void main(String[] args) { Application.launch(ClientGUI.class, args); }
}
