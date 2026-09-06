package com.lanchat.control.protocol;

/** Stable public contract for MeshX control-plane discovery and handshakes. */
public final class ControlProtocol {

    public static final int PROTOCOL_VERSION = 2;
    public static final String API_BASE_PATH = "/api/v2";
    public static final String INFO_PATH = "/api/v2/control/info";
    public static final String HEALTH_PATH = "/api/v2/control/health";

    private ControlProtocol() {
    }
}
