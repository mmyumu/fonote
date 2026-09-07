package fr.fonote;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable operations: retries never duplicate notes; deletions remain tombstones. */
final class Store extends SQLiteOpenHelper {
    Store(Context context) { this(context, "fonote.sqlite3"); }
    Store(Context context, String databaseName) { super(context, databaseName, null, 2); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE operations (id TEXT PRIMARY KEY, payload TEXT NOT NULL, seq INTEGER)");
        createCache(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) createCache(db);
    }
    private static void createCache(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE downloads (path TEXT PRIMARY KEY, payload TEXT NOT NULL)");
        db.execSQL("CREATE TABLE fixtures (id TEXT PRIMARY KEY, payload TEXT NOT NULL)");
    }
    synchronized String downloaded(String path) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT payload FROM downloads WHERE path=?", new String[]{path})) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }
    synchronized String download(String path, String payload) throws Exception {
        JSONObject data = new JSONObject(payload);
        // An upstream outage may arrive as HTTP 200 with no lineup. Keep the usable snapshot.
        if (path.startsWith("/v1/football/matches/") && !"available".equals(data.optString("lineup_status"))) {
            String previous = downloaded(path);
            if (previous != null && "available".equals(new JSONObject(previous).optString("lineup_status")))
                return previous;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            // Lists are merged by fixture id below; do not retain overlapping date-range copies.
            if (data.optJSONArray("matches") == null) {
                values.put("path", path); values.put("payload", payload);
                db.insertWithOnConflict("downloads", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            JSONArray fixtures = data.optJSONArray("matches");
            if (path.startsWith("/v1/football/matches/")) fixtures = new JSONArray().put(data);
            for (int i = 0; fixtures != null && i < fixtures.length(); i++) {
                JSONObject fixture = fixtures.getJSONObject(i);
                values.clear(); values.put("id", fixture.getString("id"));
                values.put("payload", fixture.toString());
                db.insertWithOnConflict("fixtures", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return payload;
    }
    synchronized JSONArray fixtures() throws Exception {
        JSONArray result = new JSONArray();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT payload FROM fixtures", null)) {
            while (cursor.moveToNext()) result.put(new JSONObject(cursor.getString(0)));
        }
        return result;
    }
    synchronized void add(JSONObject op) {
        ContentValues values = new ContentValues();
        values.put("id", op.optString("id"));
        values.put("payload", op.toString());
        getWritableDatabase().insertOrThrow("operations", null, values);
    }
    synchronized void accept(JSONObject item) throws Exception {
        JSONObject op = item.getJSONObject("operation");
        ContentValues values = new ContentValues();
        values.put("seq", item.getLong("seq"));
        SQLiteDatabase db = getWritableDatabase();
        if (db.update("operations", values, "id=?", new String[]{op.getString("id")}) == 0) {
            values.put("id", op.getString("id"));
            values.put("payload", op.toString());
            db.insertOrThrow("operations", null, values);
        }
    }
    synchronized JSONArray operations(boolean pendingOnly) throws Exception {
        JSONArray result = new JSONArray();
        String where = pendingOnly ? " WHERE seq IS NULL" : "";
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT payload FROM operations" + where + " ORDER BY seq IS NULL, seq, rowid", null)) {
            while (cursor.moveToNext()) result.put(new JSONObject(cursor.getString(0)));
        }
        return result;
    }
}
