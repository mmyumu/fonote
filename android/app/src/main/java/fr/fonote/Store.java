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
    Store(Context context) { super(context, "fonote.sqlite3", null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE operations (id TEXT PRIMARY KEY, payload TEXT NOT NULL, seq INTEGER)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Migration requise");
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
