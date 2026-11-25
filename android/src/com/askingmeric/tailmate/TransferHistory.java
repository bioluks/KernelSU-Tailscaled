package com.askingmeric.tailmate;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Bounded, newest-first log of sent and received Taildrop files, stored on this device only. */
final class TransferHistory {
    static final String PREFS = "history";
    /** Companion preference: record history (default on). */
    static final String ENABLED = "historyEnabled";
    static final int LIMIT = 100;
    private static final String KEY = "entries";

    enum Kind { SENT, RECEIVED, FAILED, CANCELLED }

    static final class Entry {
        final Kind kind;
        final String file;
        final String peer;
        final String detail;
        final long time;

        Entry(Kind kind, String file, String peer, String detail, long time) {
            this.kind = kind; this.file = file; this.peer = peer; this.detail = detail; this.time = time;
        }
    }

    private TransferHistory() { }

    static boolean enabled(Context context) {
        return context.getSharedPreferences("companion", Context.MODE_PRIVATE).getBoolean(ENABLED, true);
    }

    /** Appends one entry unless history is turned off; {@code peer}/{@code detail} may be null. */
    static synchronized void add(Context context, Kind kind, String file, String peer, String detail) {
        if (!enabled(context)) return;
        SharedPreferences store = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray old = parse(store.getString(KEY, "[]"));
        JSONArray next = new JSONArray();
        try {
            next.put(new JSONObject().put("kind", kind.name()).put("file", file)
                    .put("peer", peer == null ? "" : peer).put("detail", detail == null ? "" : detail)
                    .put("time", System.currentTimeMillis()));
            for (int i = 0; i < old.length() && next.length() < LIMIT; i++) next.put(old.get(i));
        } catch (JSONException e) { return; }
        store.edit().putString(KEY, next.toString()).apply();
    }

    static List<Entry> load(Context context) {
        JSONArray raw = parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"));
        List<Entry> entries = new ArrayList<>(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item == null) continue;
            Kind kind;
            try { kind = Kind.valueOf(item.optString("kind")); }
            catch (IllegalArgumentException e) { continue; }
            entries.add(new Entry(kind, item.optString("file"), emptyToNull(item.optString("peer")),
                    emptyToNull(item.optString("detail")), item.optLong("time")));
        }
        return Collections.unmodifiableList(entries);
    }

    static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }

    private static JSONArray parse(String value) {
        try { return new JSONArray(value); } catch (JSONException e) { return new JSONArray(); }
    }

    private static String emptyToNull(String value) { return value == null || value.isEmpty() ? null : value; }
}
