package com.lanchat.config;

/** Exact browser/WebView origins supported by a default local LANChat installation. */
public final class ClientOriginDefaults {

    public static final String ALLOWED_ORIGINS =
            "https://chat.atti.cc.cd,http://localhost:8080,http://127.0.0.1:8080,"
                    + "tauri://localhost,http://tauri.localhost,https://tauri.localhost,"
                    + "http://127.0.0.1:1420,http://localhost:1420,https://localhost";

    private ClientOriginDefaults() {
    }
}
