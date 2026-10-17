package de.mca.pulsascribe.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ordner mit Unterordnern (Baum). "" ist die oberste Ebene. Geprüft wird beim Laden und bei jeder
 * Änderung: gültige IDs, Namen, keine Schleifen, höchstens {@link #MAX_DEPTH} Ebenen.
 */
public final class Folders {
    public static final String ROOT = "";
    public static final int MAX_DEPTH = 4;      // Ordner › Unterordner › Unterordner › Unterordner
    public static final int MAX_FOLDERS = 300;
    public static final int MAX_NAME = 40;

    public static final class Folder {
        public final String id;
        public String name;
        public String parent;

        Folder(String id, String name, String parent) {
            this.id = id;
            this.name = name;
            this.parent = parent;
        }
    }

    private final Map<String, Folder> map = new LinkedHashMap<>();

    public boolean exists(String id) {
        return id != null && map.containsKey(id);
    }

    /** Ordner-ID oder ROOT, wenn es den Ordner nicht (mehr) gibt. */
    public String clean(String id) {
        return exists(id) ? id : ROOT;
    }

    public Folder get(String id) {
        return id == null ? null : map.get(id);
    }

    public int size() {
        return map.size();
    }

    /** Ebene: ROOT = 0, Ordner der obersten Ebene = 1. */
    public int depth(String id) {
        int d = 0;
        String cur = id;
        while (exists(cur) && d <= MAX_FOLDERS) {
            d++;
            cur = map.get(cur).parent;
        }
        return d;
    }

    /** Wie viele Ebenen unterhalb (einschließlich) dieses Ordners (Blatt = 1). */
    public int height(String id) {
        int h = 0;
        for (Folder c : children(id)) h = Math.max(h, height(c.id));
        return h + 1;
    }

    /** Direkte Unterordner, nach Namen sortiert. */
    public List<Folder> children(String parent) {
        List<Folder> out = new ArrayList<>();
        String p = parent == null ? ROOT : parent;
        for (Folder f : map.values()) if (f.parent.equals(p)) out.add(f);
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    /** Der Ordner selbst und alle Unterordner. */
    public Set<String> subtree(String id) {
        Set<String> out = new HashSet<>();
        if (ROOT.equals(id)) {
            out.add(ROOT);
            out.addAll(map.keySet());
            return out;
        }
        if (!exists(id)) return out;
        List<String> todo = new ArrayList<>();
        todo.add(id);
        while (!todo.isEmpty()) {
            String cur = todo.remove(todo.size() - 1);
            if (!out.add(cur)) continue;
            for (Folder c : children(cur)) todo.add(c.id);
        }
        return out;
    }

    /** Pfad von oben nach unten (ohne ROOT). */
    public List<Folder> path(String id) {
        List<Folder> out = new ArrayList<>();
        String cur = id;
        int guard = 0;
        while (exists(cur) && guard++ <= MAX_DEPTH + 1) {
            Folder f = map.get(cur);
            out.add(0, f);
            cur = f.parent;
        }
        return out;
    }

    public String pathText(String id) {
        StringBuilder b = new StringBuilder();
        for (Folder f : path(id)) {
            if (b.length() > 0) b.append(" › ");
            b.append(f.name);
        }
        return b.toString();
    }

    /** Alle Ordner in Baumreihenfolge (für Auswahllisten), mit Ebene. */
    public List<Folder> tree() {
        List<Folder> out = new ArrayList<>();
        addTree(out, ROOT);
        return out;
    }

    private void addTree(List<Folder> out, String parent) {
        for (Folder c : children(parent)) {
            out.add(c);
            addTree(out, c.id);
        }
    }

    // ------------------------------------------------------------------ Ändern

    public static String cleanName(String n) {
        return Text.line(n, MAX_NAME);
    }

    private boolean nameTaken(String parent, String name, String exceptId) {
        for (Folder f : children(parent)) {
            if (!f.id.equals(exceptId) && f.name.toLowerCase(Locale.GERMANY).equals(name.toLowerCase(Locale.GERMANY))) return true;
        }
        return false;
    }

    public static final int OK = 0, ERR_NAME = 1, ERR_DUPLICATE = 2, ERR_DEPTH = 3, ERR_LIMIT = 4, ERR_CYCLE = 5, ERR_MISSING = 6;

    /** Legt einen Ordner an. Rückgabe: Fehlercode; bei OK steht die neue ID in out[0]. */
    public int add(String id, String name, String parent, String[] out) {
        String n = cleanName(name);
        String p = parent == null ? ROOT : parent;
        if (n.isEmpty()) return ERR_NAME;
        if (!Text.isId(id) || map.containsKey(id)) return ERR_MISSING;
        if (!ROOT.equals(p) && !exists(p)) return ERR_MISSING;
        if (map.size() >= MAX_FOLDERS) return ERR_LIMIT;
        if (depth(p) + 1 > MAX_DEPTH) return ERR_DEPTH;
        if (nameTaken(p, n, null)) return ERR_DUPLICATE;
        map.put(id, new Folder(id, n, p));
        if (out != null && out.length > 0) out[0] = id;
        return OK;
    }

    public int rename(String id, String name) {
        Folder f = get(id);
        if (f == null) return ERR_MISSING;
        String n = cleanName(name);
        if (n.isEmpty()) return ERR_NAME;
        if (nameTaken(f.parent, n, id)) return ERR_DUPLICATE;
        f.name = n;
        return OK;
    }

    /** Darf der Ordner nach newParent verschoben werden? */
    public int canMove(String id, String newParent) {
        Folder f = get(id);
        String p = newParent == null ? ROOT : newParent;
        if (f == null || (!ROOT.equals(p) && !exists(p))) return ERR_MISSING;
        if (subtree(id).contains(p)) return ERR_CYCLE;
        if (depth(p) + height(id) > MAX_DEPTH) return ERR_DEPTH;
        if (nameTaken(p, f.name, id)) return ERR_DUPLICATE;
        return OK;
    }

    public int move(String id, String newParent) {
        int r = canMove(id, newParent);
        if (r == OK) map.get(id).parent = newParent == null ? ROOT : newParent;
        return r;
    }

    /**
     * Löscht nur den Ordner: Unterordner wandern eine Ebene nach oben (bei gleichem Namen mit Zusatz).
     * Rückgabe: Elternordner, in den der Inhalt verschoben werden soll; null wenn unbekannt.
     */
    public String delete(String id) {
        Folder f = map.remove(id);
        if (f == null) return null;
        for (Folder c : new ArrayList<>(map.values())) {
            if (!c.parent.equals(id)) continue;
            c.parent = f.parent;
            String base = c.name;
            int k = 2;
            while (nameTaken(c.parent, c.name, c.id) && k < 100) {
                String suffix = " (" + k++ + ")";
                c.name = base.length() + suffix.length() > MAX_NAME ? base.substring(0, MAX_NAME - suffix.length()) + suffix : base + suffix;
            }
        }
        return f.parent;
    }

    // ------------------------------------------------------------------ Speichern / Laden

    public String toJson() {
        List<Object> l = new ArrayList<>();
        for (Folder f : map.values()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", f.id);
            o.put("name", f.name);
            o.put("parent", f.parent);
            l.add(o);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("v", 1);
        m.put("folders", l);
        return Json.write(m);
    }

    /** Lädt und repariert: Unbekannte Eltern, Schleifen und zu tiefe Ordner landen oben. */
    public static Folders fromJson(String json) {
        Folders out = new Folders();
        if (json == null) return out;
        List<Object> list;
        try {
            list = Json.arr(Json.obj(Json.parse(json)), "folders");
        } catch (Json.ParseException e) {
            return out;
        }
        if (list == null) return out;
        for (Object o : list) {
            if (out.map.size() >= MAX_FOLDERS) break;
            Map<String, Object> m = Json.obj(o);
            String id = Json.str(m, "id");
            String name = cleanName(Json.str(m, "name"));
            String parent = Json.str(m, "parent");
            if (!Text.isId(id) || name.isEmpty() || out.map.containsKey(id)) continue;
            out.map.put(id, new Folder(id, name, parent != null && Text.isId(parent) ? parent : ROOT));
        }
        // Eltern prüfen, Schleifen und Tiefe reparieren
        for (Folder f : out.map.values()) {
            if (!ROOT.equals(f.parent) && !out.map.containsKey(f.parent)) f.parent = ROOT;
        }
        for (Folder f : out.map.values()) {
            Set<String> seen = new HashSet<>();
            String cur = f.id;
            int d = 0;
            boolean broken = false;
            while (!ROOT.equals(cur)) {
                if (!seen.add(cur) || ++d > MAX_DEPTH) { broken = true; break; }
                cur = out.map.get(cur).parent;
            }
            if (broken) f.parent = ROOT;
        }
        // Doppelte Namen unter demselben Elternordner eindeutig machen
        for (Folder f : out.map.values()) {
            String base = f.name;
            int k = 2;
            while (out.nameTaken(f.parent, f.name, f.id) && k < 100) {
                String suffix = " (" + k++ + ")";
                f.name = base.length() + suffix.length() > MAX_NAME ? base.substring(0, MAX_NAME - suffix.length()) + suffix : base + suffix;
            }
        }
        return out;
    }

    public Folders copy() {
        return fromJson(toJson());
    }
}
