package com.miniftp.common;

/** Giao thức TCP tùy chỉnh; đây không phải FTP theo RFC 959. */
public final class Protocol {
    private Protocol() { }

    public static final int DEFAULT_PORT = 5000;
    public static final long MAX_FILE_SIZE = 1024L * 1024 * 1024; // 1 GiB

    public static final String LOGIN = "LOGIN";
    public static final String REGISTER = "REGISTER";
    public static final String RENAME = "RENAME";
    public static final String PING = "PING";
    public static final String LIST = "LIST";
    public static final String UPLOAD = "UPLOAD";
    public static final String DOWNLOAD = "DOWNLOAD";
    public static final String MKDIR = "MKDIR";
    public static final String DELETE = "DELETE";
    public static final String QUIT = "QUIT";

    public static final String OK = "OK";
    public static final String READY = "READY";
    public static final String ERROR = "ERROR";
}
