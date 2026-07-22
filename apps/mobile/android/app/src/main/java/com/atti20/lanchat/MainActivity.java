package com.atti20.lanchat;

import android.os.Bundle;
import android.webkit.WebSettings;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // BridgeActivity builds its plugin registry during super.onCreate(), so
        // custom plugins must be registered before it creates the bridge.
        registerPlugin(MeshXDiscoveryPlugin.class);
        registerPlugin(MeshXFilesPlugin.class);
        super.onCreate(savedInstanceState);
        // The LAN flavor is deliberately debug-only operational tooling for
        // administrator-confirmed HTTP nodes. Release builds keep WebView mixed
        // content disabled and require HTTPS/WSS.
        if (BuildConfig.DEBUG && "lan".equals(BuildConfig.FLAVOR) && getBridge() != null) {
            getBridge().getWebView().getSettings().setMixedContentMode(
                    WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
    }
}
