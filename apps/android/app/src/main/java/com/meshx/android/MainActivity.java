package com.meshx.android;

import android.content.res.Configuration;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;

/** Thin native host: all visible product UI comes from frontend/dist-mobile. */
public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(MeshXDiscoveryPlugin.class);
        registerPlugin(MeshXFilesPlugin.class);
        registerPlugin(MeshXAuthPlugin.class);
        if (BuildConfig.DEBUG) {
            // Debug-only: enables on-device WebView inspection for the LAN test build.
            // Release and secure production artifacts never expose a DevTools endpoint.
            WebView.setWebContentsDebuggingEnabled(true);
        }
        super.onCreate(savedInstanceState);

        // The explicit LAN debug flavor connects to HTTP nodes from https://localhost.
        // Compatibility mode still blocks fetch/WebSocket; secure and release keep defaults.
        if (BuildConfig.DEBUG && "lan".equals(BuildConfig.FLAVOR) && getBridge() != null) {
            getBridge().getWebView().getSettings().setMixedContentMode(
                    WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        applySystemFontScale();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applySystemFontScale();
    }

    private void applySystemFontScale() {
        if (getBridge() == null || getBridge().getWebView() == null) return;
        getBridge().getWebView().getSettings().setTextZoom(
                Math.round(getResources().getConfiguration().fontScale * 100));
    }
}
