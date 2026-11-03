package com.miniftp.app;

import com.miniftp.server.FTPServer;

/** Headless server: optional arguments are TCP port and shared storage directory. */
public final class ServerConsoleMain {
    private ServerConsoleMain() { }

    public static void main(String[] args) throws Exception {
        FTPServer.main(args);
    }
}
