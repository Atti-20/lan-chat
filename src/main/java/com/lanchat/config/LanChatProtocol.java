package com.lanchat.config;

/**
 * Stable public protocol contract shared by node handshakes and DNS-SD TXT records.
 */
public final class LanChatProtocol {

    public static final int PROTOCOL_VERSION = 1;
    public static final String API_BASE_PATH = "/api/v1";
    public static final String WEB_SOCKET_PATH = "/ws/chat";
    public static final String HEALTH_PATH = "/api/v1/node/health";
    public static final String APP_PATH = "/app/";
    public static final boolean DESKTOP_AUTH_SUPPORTED = true;
    public static final String REFRESH_TRANSPORT = "HTTP_ONLY_COOKIE";

    private LanChatProtocol() {
    }
}
