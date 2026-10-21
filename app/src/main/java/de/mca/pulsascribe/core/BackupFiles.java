package de.mca.pulsascribe.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Namen und Aufbewahrung der Sicherungsdateien im gemerkten Ordner.
 * Von Hand: pulsa-scribe-sicherung-JJJJ-MM-TT.pscb (eine je Tag, wird überschrieben),
 * automatisch: pulsa-scribe-auto-JJJJ-MM-TT.pscb. Gezählt und gelöscht werden nur automatische.
 */
public final class BackupFiles {
    private BackupFiles() {}

    public static final String EXT = ".pscb";
    public static final int[] KEEP = {7, 14, 30};
    private static final Pattern NAME = Pattern.compile("pulsa-scribe-(sicherung|auto)-(\\d{4}-\\d{2}-\\d{2})(?: \\(\\d{1,3}\\))?\\.pscb");

    public static String manualName(String day) {
        if (!day.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("day");
        return "pulsa-scribe-sicherung-" + day + EXT;
    }

    public static String autoName(String day) {
        if (!day.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("day");
        return "pulsa-scribe-auto-" + day + EXT;
    }

    public static boolean isBackup(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    public static boolean isAuto(String name) {
        if (name == null) return false;
        Matcher m = NAME.matcher(name);
        return m.matches() && "auto".equals(m.group(1));
    }

    /** JJJJ-MM-TT aus dem Namen oder "". */
    public static String day(String name) {
        if (name == null) return "";
        Matcher m = NAME.matcher(name);
        return m.matches() ? m.group(2) : "";
    }

    public static int cleanKeep(int k) {
        for (int x : KEEP) if (x == k) return k;
        return 14;
    }

    /**
     * Welche automatischen Sicherungen müssen weg, damit nach einer neuen höchstens keep übrig sind?
     * names = vorhandene Dateinamen (beliebig), today = Tag der neuen Sicherung (wird ggf. überschrieben, zählt nicht doppelt).
     * Liefert die ältesten zuerst.
     */
    public static List<String> toDelete(List<String> names, int keep, String today) {
        List<String> auto = new ArrayList<>();
        for (String n : names) if (isAuto(n) && !day(n).equals(today)) auto.add(n);
        auto.sort((a, b) -> day(a).compareTo(day(b)));
        List<String> out = new ArrayList<>();
        int over = auto.size() + 1 - cleanKeep(keep);
        for (int i = 0; i < over && i < auto.size(); i++) out.add(auto.get(i));
        return out;
    }
}
