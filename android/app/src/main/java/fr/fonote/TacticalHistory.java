package fr.fonote;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** History of an open edit. One entry groups the drawing and the annotations.
 * The caller commits the history only once the save transaction has succeeded. */
final class TacticalHistory {
    private static final int LIMIT = 40;
    private final List<String> past = new ArrayList<>(), future = new ArrayList<>();
    private String current = "";
    String current() { return current; }
    boolean canUndo() { return !past.isEmpty(); }
    boolean canRedo() { return !future.isEmpty(); }
    void reset(String snapshot) { current = snapshot; past.clear(); future.clear(); }
    void record(String snapshot) {
        if (snapshot.equals(current)) return;
        push(past, current); current = snapshot; future.clear();
    }
    String target(boolean redo) {
        List<String> source = redo ? future : past;
        return source.isEmpty() ? current : source.get(source.size()-1);
    }
    void accept(boolean redo) {
        List<String> source = redo ? future : past, destination = redo ? past : future;
        if (source.isEmpty()) return;
        push(destination,current); current = source.remove(source.size()-1);
    }
    private void push(List<String> list, String snapshot) {
        if (list.size() >= LIMIT) list.remove(0);
        list.add(snapshot);
    }
    static String snapshot(Diagram diagram, Map<String,String> entries) {
        try { return new JSONObject().put("diagram",diagram.toJson()).put("entries",new JSONObject(entries)).toString(); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    static Diagram restore(String snapshot, Map<String,String> entries) {
        try {
            JSONObject state = new JSONObject(snapshot);
            Diagram diagram = Diagram.from(state.getJSONObject("diagram"));
            JSONObject saved = state.getJSONObject("entries");
            entries.clear(); java.util.Iterator<String> names = saved.keys();
            while (names.hasNext()) { String id = names.next(); entries.put(id,saved.getString(id)); }
            return diagram;
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
