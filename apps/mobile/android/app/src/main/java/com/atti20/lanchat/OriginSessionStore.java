package com.atti20.lanchat;

import java.util.List;
import okhttp3.Cookie;

/** Stores refresh cookies for exactly one canonical node origin at a time. */
interface OriginSessionStore {
    List<Cookie> load(String origin);

    void save(String origin, List<Cookie> cookies);

    void remove(String origin);
}
