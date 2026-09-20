package com.meshx.android;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Capacitor boundary for Android native cookie-based authentication. */
@CapacitorPlugin(name = "MeshXAuth")
public class MeshXAuthPlugin extends Plugin {
    private MeshXAuthClient client;
    private ExecutorService executor;

    @Override
    public void load() {
        client = new MeshXAuthClient(new EncryptedOriginSessionStore(getContext()));
        // Blocking OkHttp calls (up to the 12s call timeout) must stay off
        // Capacitor's shared plugin thread. A single-thread executor also
        // serializes requests, so the client's in-flight guard keeps working.
        executor = Executors.newSingleThreadExecutor();
    }

    @Override
    protected void handleOnDestroy() {
        if (executor != null) executor.shutdownNow();
    }

    @PluginMethod
    public void login(PluginCall call) {
        executeExclusive(call, () -> {
            MeshXAuthClient.AuthSession session = client.login(
                    call.getString("origin"),
                    call.getString("apiBasePath", MeshXAuthClient.API_BASE_PATH),
                    call.getString("username"),
                    call.getString("password"),
                    call.getString("deviceName", "MeshX Android"));
            call.resolve(sessionObject(session));
        });
    }

    @PluginMethod
    public void refresh(PluginCall call) {
        executeExclusive(call, () -> {
            MeshXAuthClient.AuthSession session = client.refresh(
                    call.getString("origin"),
                    call.getString("apiBasePath", MeshXAuthClient.API_BASE_PATH),
                    call.getString("deviceName", "MeshX Android"));
            call.resolve(sessionObject(session));
        });
    }

    @PluginMethod
    public void logout(PluginCall call) {
        executeExclusive(call, () -> {
            client.logout(
                    call.getString("origin"),
                    call.getString("apiBasePath", MeshXAuthClient.API_BASE_PATH),
                    call.getString("accessToken"));
            call.resolve();
        });
    }

    @PluginMethod
    public void clearNodeSession(PluginCall call) {
        execute(call, () -> {
            client.clearNodeSession(call.getString("origin"));
            call.resolve();
        });
    }

    /**
     * The single-thread executor queues submissions, so the client's
     * requireIdle guard can never observe a busy state from a call routed
     * through it: a second request would silently wait behind an in-flight
     * one (up to the 12s call timeout) instead of failing fast. Capacitor
     * dispatches plugin methods from a single thread, so this check-then-submit
     * cannot race; requireIdle stays in the client as the defensive invariant.
     */
    private void executeExclusive(PluginCall call, AuthAction action) {
        if (client.isBusy(call.getString("origin"))) {
            call.reject("a native authentication request is already in progress", "AUTH_BUSY");
            return;
        }
        execute(call, action);
    }

    private void execute(PluginCall call, AuthAction action) {
        try {
            executor.execute(() -> {
                try {
                    action.run();
                } catch (Exception exception) {
                    reject(call, exception);
                }
            });
        } catch (RejectedExecutionException exception) {
            reject(call, exception);
        }
    }

    private static JSObject sessionObject(MeshXAuthClient.AuthSession session) {
        JSObject result = new JSObject();
        result.put("userId", session.userId());
        result.put("username", session.username());
        result.put("nickname", session.nickname());
        if (session.avatar() != null) result.put("avatar", session.avatar());
        result.put("token", session.token());
        result.put("expiresIn", session.expiresIn());
        return result;
    }

    private static void reject(PluginCall call, Exception exception) {
        String message = exception.getMessage();
        String text = message == null || message.isBlank()
                ? "Android native authentication failed"
                : message;
        if (exception instanceof MeshXAuthClient.BusyException) {
            call.reject(text, "AUTH_BUSY");
        } else {
            call.reject(text);
        }
    }

    @FunctionalInterface
    private interface AuthAction {
        void run() throws Exception;
    }
}
