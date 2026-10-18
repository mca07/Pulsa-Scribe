package de.mca.pulsascribe.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/** Tests für Ordner, Schlagwörter, Vorlagen, Bild lesen, Fragen, Übersetzen, Ordner-Auswertung. */
public class FeatureTest {

    private static String id(int n) {
        return String.format("folder%04d", n);
    }

    // ------------------------------------------------------------------ Ordner

    @Test
    public void foldersNestAndLimitDepth() {
        Folders f = new Folders();
        assertEquals(Folders.OK, f.add(id(1), "Kunden", Folders.ROOT, null));
        assertEquals(Folders.OK, f.add(id(2), "Müller GmbH", id(1), null));
        assertEquals(Folders.OK, f.add(id(3), "2026", id(2), null));
        assertEquals(Folders.OK, f.add(id(4), "Oktober", id(3), null));
        assertEquals(Folders.ERR_DEPTH, f.add(id(5), "zu tief", id(4), null));
        assertEquals(4, f.depth(id(4)));
        assertEquals("Kunden › Müller GmbH › 2026 › Oktober", f.pathText(id(4)));
        assertEquals(Folders.ERR_DUPLICATE, f.add(id(6), "kunden", Folders.ROOT, null));
        assertEquals(Folders.ERR_NAME, f.add(id(7), "  \u202E ", Folders.ROOT, null));
        assertEquals(Folders.ERR_MISSING, f.add(id(8), "x", "folder9999", null));
        assertEquals(Folders.ERR_MISSING, f.add("../x", "x", Folders.ROOT, null));
    }

    @Test
    public void foldersMoveRejectsCyclesAndDepth() {
        Folders f = new Folders();
        f.add(id(1), "A", "", null);
        f.add(id(2), "B", id(1), null);
        f.add(id(3), "C", id(2), null);
        f.add(id(4), "D", "", null);
        f.add(id(5), "E", id(4), null);
        f.add(id(6), "F", id(5), null);
        assertEquals(Folders.ERR_CYCLE, f.move(id(1), id(3)));
        assertEquals(Folders.ERR_CYCLE, f.move(id(1), id(1)));
        assertEquals(Folders.ERR_DEPTH, f.move(id(1), id(5))); // 2 + 3 > 4
        assertEquals(Folders.OK, f.move(id(3), id(4)));
        assertEquals(id(4), f.get(id(3)).parent);
        assertEquals(2, f.height(id(1)));
    }

    @Test
    public void deleteFolderMovesChildrenUp() {
        Folders f = new Folders();
        f.add(id(1), "A", "", null);
        f.add(id(2), "B", id(1), null);
        f.add(id(3), "B", "", null); // gleicher Name oben
        assertEquals(Folders.ROOT, f.delete(id(1)));
        assertEquals(Folders.ROOT, f.get(id(2)).parent);
        assertEquals("B (2)", f.get(id(2)).name);
        assertNull(f.delete("folder9999"));
    }

    @Test
    public void subtreeAndTreeOrder() {
        Folders f = new Folders();
        f.add(id(1), "Zeta", "", null);
        f.add(id(2), "Alpha", "", null);
        f.add(id(3), "Kind", id(2), null);
        List<Folders.Folder> t = f.tree();
        assertEquals("Alpha", t.get(0).name);
        assertEquals("Kind", t.get(1).name);
        assertEquals("Zeta", t.get(2).name);
        assertTrue(f.subtree(id(2)).contains(id(3)));
        assertFalse(f.subtree(id(2)).contains(id(1)));
    }

    @Test
    public void manipulatedFoldersAreRepaired() {
        String json = "{\"folders\":["
                + "{\"id\":\"" + id(1) + "\",\"name\":\"A\",\"parent\":\"" + id(2) + "\"},"
                + "{\"id\":\"" + id(2) + "\",\"name\":\"B\",\"parent\":\"" + id(1) + "\"},"   // Schleife
                + "{\"id\":\"" + id(3) + "\",\"name\":\"C\",\"parent\":\"folder9999\"},"        // Eltern fehlen
                + "{\"id\":\"../x\",\"name\":\"X\",\"parent\":\"\"},"                           // falsche ID
                + "{\"id\":\"" + id(4) + "\",\"name\":\"\",\"parent\":\"\"},"                    // leerer Name
                + "{\"id\":\"" + id(5) + "\",\"name\":\"C\",\"parent\":\"\"}"                    // doppelt
                + "]}";
        Folders f = Folders.fromJson(json);
        assertEquals(4, f.size());
        assertEquals(Folders.ROOT, f.get(id(3)).parent);
        // Schleife aufgelöst: einer der beiden hängt oben
        assertTrue(Folders.ROOT.equals(f.get(id(1)).parent) || Folders.ROOT.equals(f.get(id(2)).parent));
        assertTrue(f.depth(id(1)) <= Folders.MAX_DEPTH && f.depth(id(2)) <= Folders.MAX_DEPTH);
        assertFalse(f.get(id(3)).name.equals(f.get(id(5)).name));
        assertEquals(0, Folders.fromJson("kaputt").size());
        Folders back = Folders.fromJson(f.toJson());
        assertEquals(f.size(), back.size());
    }

    // ------------------------------------------------------------------ Transkript-Felder

    @Test
    public void newFieldsRoundTripAndAreChecked() {
        Transcript t = new Transcript();
        t.id = "abcdefgh12";
        t.kind = Transcript.KIND_IMAGE;
        t.folder = id(1);
        t.tags.addAll(Arrays.asList("Vertrieb", "KI"));
        t.template = Templates.MEETING;
        t.segments.add(new Transcript.Segment(0, "", "Hallo"));
        Transcript.Answer a = new Transcript.Answer();
        a.question = "Wer?";
        a.answer = "Anna";
        a.refs.add(1000L);
        t.answers.add(a);
        Transcript.Translation tr = new Transcript.Translation();
        tr.segments.add("Hello");
        t.translations.put("en", tr);
        Transcript r = Transcript.fromJson(t.toJson());
        assertEquals(Transcript.KIND_IMAGE, r.kind);
        assertEquals(id(1), r.folder);
        assertEquals(2, r.tags.size());
        assertEquals(Templates.MEETING, r.template);
        assertEquals("Anna", r.answers.get(0).answer);
        assertEquals("Hello", r.translations.get("en").segments.get(0));

        String evil = "{\"id\":\"abcdefgh12\",\"kind\":\"exe\",\"folder\":\"../../x\",\"template\":\"evil\","
                + "\"tags\":[\"#a\",\"A\",5,\"" + "x".repeat(80) + "\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\",\"j\",\"k\"],"
                + "\"segments\":[{\"t\":0,\"x\":\"eins\"}],"
                + "\"qa\":[{\"q\":\"\",\"a\":\"x\"},{\"q\":\"F\",\"a\":\"A\",\"refs\":[-5,99999999999,\"00:10\",\"kaputt\"]}],"
                + "\"translations\":{\"xx\":{\"segments\":[\"a\"]},\"en\":{\"segments\":[\"a\",\"b\"]},\"de\":{\"segments\":[\"eins\"]}}}";
        Transcript e = Transcript.fromJson(evil);
        assertNotNull(e);
        assertEquals(Transcript.KIND_AUDIO, e.kind);
        assertEquals("", e.folder);
        assertEquals(Templates.STANDARD, e.template);
        assertEquals(Transcript.MAX_TAGS, e.tags.size());
        assertEquals("a", e.tags.get(0));
        for (String tag : e.tags) assertTrue(tag.length() <= Transcript.MAX_TAG);
        assertEquals(1, e.answers.size());
        assertEquals(1, e.answers.get(0).refs.size());
        assertEquals(10_000L, (long) e.answers.get(0).refs.get(0));
        assertEquals(1, e.translations.size()); // xx unbekannt, en falsche Anzahl
        assertTrue(e.translations.containsKey("de"));
    }

    // ------------------------------------------------------------------ Vorlagen und Prompts

    @Test
    public void templatesArePositiveList() {
        assertEquals(Templates.STANDARD, Templates.clean("<x>"));
        assertEquals(5, Templates.headings(Templates.LESSON).length);
        assertTrue(Templates.fields(Templates.LESSON).contains("exam-style"));
        Transcript t = new Transcript();
        t.id = "abcdefgh12";
        t.template = Templates.LESSON;
        t.kind = Transcript.KIND_TEXT;
        t.segments.add(new Transcript.Segment(0, "", "Absatz eins."));
        String p = Gemini.summaryPrompt(t, "de");
        assertTrue(p.contains("exam-style"));
        assertTrue(p.contains("TEXT:\nAbsatz eins."));
        assertFalse(p.contains("[00:00]"));
        assertTrue(p.contains("tags ="));
    }

    @Test
    public void summaryTagsAreParsed() {
        List<String> tags = Gemini.parseTags("{\"overview\":\"x\",\"tags\":[\"#KI\",\"Vertrieb\",\"ki\",\"a\",\"b\",\"c\",\"d\"]}");
        assertEquals(5, tags.size());
        assertEquals("KI", tags.get(0));
        assertTrue(Gemini.parseTags("kaputt").isEmpty());
    }

    // ------------------------------------------------------------------ Bild und Text

    @Test
    public void ocrIsValidated() throws Exception {
        Gemini.Ocr o = Gemini.parseOcr("{\"title\":\"Post von Anna\\u202E\",\"paragraphs\":[\"Anna · 2 Std.\",\"\",5,\"Text\\u0000 hier\"]}");
        assertNotNull(o);
        assertEquals("Post von Anna", o.title);
        assertEquals(2, o.paragraphs.size());
        assertEquals("Text hier", o.paragraphs.get(1));
        assertNull(Gemini.parseOcr("{\"title\":\"x\"}"));
        String body = Gemini.imageRequestPrefix(Gemini.ocrPrompt("de")) + "QUJD" + Gemini.imageRequestSuffix();
        assertNotNull(Json.parse(body));
    }

    @Test
    public void sharedTextIsSplitIntoParagraphs() {
        List<String> p = Gemini.paragraphs("Erster Absatz.\r\n\r\nZweiter\nAbsatz.\n   \nDritter.");
        assertEquals(3, p.size());
        assertEquals("Zweiter\nAbsatz.", p.get(1));
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 300; i++) longText.append("Satz Nummer ").append(i).append(". ");
        for (String s : Gemini.paragraphs(longText.toString())) assertTrue(s.length() <= 1500);
        assertTrue(Gemini.paragraphs("x".repeat(200_000)).stream().mapToInt(String::length).sum() <= Gemini.MAX_TEXT_INPUT);
        assertTrue(Gemini.paragraphs(null).isEmpty());
    }

    // ------------------------------------------------------------------ Fragen

    @Test
    public void questionPromptAndAnswer() {
        Transcript t = new Transcript();
        t.id = "abcdefgh12";
        t.segments.add(new Transcript.Segment(65_000, "Sprecher 1", "Der Termin ist am Montag."));
        t.speakerNames.put("Sprecher 1", "Marcel");
        String p = Gemini.questionPrompt(t, "Wann ist \"der\" Termin? {x}", "same");
        assertTrue(p.contains("[01:05] Marcel: Der Termin ist am Montag."));
        assertTrue(p.contains("QUESTION: Wann ist der Termin? x"));
        Transcript.Answer a = Gemini.parseAnswer("{\"a\":\"Am Montag.\",\"refs\":[\"01:05\",\"99:00\"]}", "Wann?", 120_000);
        assertNotNull(a);
        assertEquals("Wann?", a.question);
        assertEquals(1, a.refs.size());
        assertEquals(65_000L, (long) a.refs.get(0));
        assertNull(Gemini.parseAnswer("{\"a\":\"\"}", "Wann?", 1000));
    }

    // ------------------------------------------------------------------ Übersetzen

    @Test
    public void translationNeedsExactCount() throws Exception {
        String p = Gemini.translatePrompt(Arrays.asList("Hallo", "Danke"), "en");
        assertTrue(p.contains("into English"));
        assertTrue(p.contains("1. Hallo\n2. Danke"));
        assertEquals(2, Gemini.parseLines("{\"lines\":[\"Hello\",\"Thanks\"]}", 2).size());
        assertNull(Gemini.parseLines("{\"lines\":[\"Hello\"]}", 2));
        assertNull(Gemini.parseLines("x", 2));
        Transcript.Summary s = new Transcript.Summary();
        s.overview = "Kurz";
        assertTrue(Gemini.translateSummaryPrompt(s, "ro").contains("Romanian"));
        Json.parse(Gemini.textRequest(p, Gemini.linesSchema(), 0));
    }

    // ------------------------------------------------------------------ Ordner auswerten

    @Test
    public void folderPromptUsesSummariesAndBudget() {
        List<Transcript> items = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Transcript t = new Transcript();
            t.id = "abcdefgh1" + i;
            t.title = "Lektion " + i;
            t.createdMs = 1_760_000_000_000L;
            if (i == 0) {
                t.summary = new Transcript.Summary();
                t.summary.overview = "Zusammenfassung null";
            } else {
                t.segments.add(new Transcript.Segment(0, "", "y".repeat(500_000)));
            }
            items.add(t);
        }
        String p = Gemini.folderPrompt("BZKI", items, "Prüfungsrelevantes", "de");
        assertTrue(p.contains("folder \"BZKI\""));
        assertTrue(p.contains("Zusammenfassung null"));
        assertTrue(p.contains("Focus especially on: Prüfungsrelevantes"));
        assertTrue(p.length() < Gemini.MAX_FOLDER_INPUT + 5000);
    }

    @Test
    public void speechPiecesStayShort() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 100; i++) b.append("Das ist Satz ").append(i).append(". ");
        List<String> p = Export.splitForSpeech(b.toString(), 300);
        assertTrue(p.size() > 5);
        for (String s : p) assertTrue(s.length() <= 300);
        assertTrue(p.get(0).endsWith("."));
        Transcript t = new Transcript();
        t.title = "Termin";
        t.template = Templates.MEETING;
        Transcript.Summary s = new Transcript.Summary();
        s.overview = "Kurz.";
        s.tasks.add(new Transcript.Task("Angebot", "Anna"));
        List<String> sp = Export.speechSummary(t, s);
        assertTrue(sp.contains("Aufgaben:"));
        assertTrue(sp.contains("Angebot, zuständig: Anna."));
        assertTrue(Export.speechSummary(t, null).isEmpty());
    }

    @Test
    public void textExportHasNoTimes() {
        Transcript t = new Transcript();
        t.id = "abcdefgh12";
        t.kind = Transcript.KIND_TEXT;
        t.title = "Post";
        t.segments.add(new Transcript.Segment(0, "", "Inhalt"));
        String txt = Export.txt(t, new Export.Options());
        assertFalse(txt.contains("[0:00]"));
        assertTrue(txt.contains("Inhalt"));
    }

    @Test
    public void searchFindsTitleTagsTextSummary() {
        Transcript t = new Transcript();
        t.id = "abcdefgh12";
        t.title = "Kundentermin Müller";
        t.tags.add("Vertrieb");
        t.segments.add(new Transcript.Segment(90_000, "", "Wir sprechen heute ausführlich über das neue Angebot für die Lagerverwaltung und die Preise."));
        t.summary = new Transcript.Summary();
        t.summary.overview = "Es ging um Lizenzen.";
        assertNotNull(Search.find(t, Search.normalize("MÜLLER")));
        assertNotNull(Search.find(t, "#vert"));
        assertNull(Search.find(t, "#müller"));
        Search.Hit h = Search.find(t, "lagerverwaltung");
        assertNotNull(h);
        assertEquals(90_000L, h.timeMs);
        assertTrue(h.snippet.contains("Lagerverwaltung"));
        assertTrue(h.snippet.length() <= 130);
        assertEquals(-1L, Search.find(t, "lizenzen").timeMs);
        assertNull(Search.find(t, "gibtsnicht"));
        assertNull(Search.find(t, ""));
    }

    @Test
    public void languageIsDetectedAndForced() {
        assertEquals("de", Languages.detect("Heute geht es um das Angebot und wir haben noch nicht die Preise für den Kunden."));
        assertEquals("en", Languages.detect("Today we talk about the offer and we have not seen the prices for this customer yet."));
        assertEquals("", Languages.detect("Hallo"));
        Transcript t = new Transcript();
        t.id = "abcdefgh12";
        t.language = Languages.AUTO;
        t.segments.add(new Transcript.Segment(0, "Sprecher 1", "Wir müssen das Angebot noch prüfen, und die Preise sind nicht klar."));
        t.segments.add(new Transcript.Segment(5000, "Sprecher 2", "Ich schicke dir die Unterlagen bis Freitag, das ist kein Problem."));
        String p = Gemini.summaryPrompt(t, Languages.SUM_SAME);
        assertTrue(p.contains("Write ALL text values of the JSON in German"));
        assertTrue(p.contains("Do not write in English"));
        assertTrue(Gemini.summaryPrompt(t, "en").contains("in English."));
        t.language = "ro";
        assertTrue(Gemini.summaryPrompt(t, Languages.SUM_SAME).contains("in Romanian"));
        assertTrue(Gemini.questionPrompt(t, "Wann kommen die Unterlagen und wer schickt sie?", Languages.SUM_SAME).contains("in German"));
    }

    @Test
    public void summaryLengthChangesPrompt() {
        Transcript t = new Transcript();
        t.id = "abcdefgh12";
        t.segments.add(new Transcript.Segment(0, "", "x"));
        t.summaryLength = Templates.SHORT;
        assertTrue(Gemini.summaryPrompt(t, "de").contains("LENGTH: SHORT"));
        t.summaryLength = Templates.LONG;
        assertTrue(Gemini.summaryPrompt(t, "de").contains("LENGTH: DETAILED"));
        assertEquals(Templates.MEDIUM, Templates.cleanLength("riesig"));
        Transcript r = Transcript.fromJson(t.toJson());
        assertEquals(Templates.LONG, r.summaryLength);
    }

    @Test
    public void summaryRequestIncludesTagsInSchema() throws Exception {
        Map<String, Object> m = Json.obj(Json.parse(Gemini.summaryRequest("x")));
        String schema = Json.write(Json.obj(Json.obj(m, "generationConfig"), "responseSchema"));
        assertTrue(schema.contains("\"tags\""));
    }

    @Test
    public void crmTemplateAndEntry() {
        assertEquals(Templates.CRM, Templates.clean("crm"));
        assertTrue(Templates.fields(Templates.CRM).contains("Wiedervorlage"));
        Transcript t = new Transcript();
        t.title = "Gespräch Kunde";
        t.template = Templates.CRM;
        t.createdMs = 0;
        Transcript.Summary s = new Transcript.Summary();
        s.overview = "Kunde ist zufrieden.";
        s.points.add("Betreff: Schulung myFactory");
        s.points.add("Ansprechpartner: Inhaber");
        s.tasks.add(new Transcript.Task("Infos intern verteilen", "Sprecher 2"));
        s.decisions.add("Anfragen laufen über Sprecher 2");
        s.questions.add("Ehemaliger Ansprechpartner ausgeschieden");
        String e = Export.crmEntry(t, s);
        assertTrue(e.startsWith("Betreff: Schulung myFactory\n"));
        assertTrue(e.contains("Ansprechpartner: Inhaber\n"));
        assertEquals(1, e.split("Betreff:", -1).length - 1);
        assertTrue(e.contains("- Infos intern verteilen (Zuständig: Sprecher 2)"));
        assertTrue(e.contains("Offen / im CRM aktualisieren:\n- Ehemaliger"));
        // Andere Vorlage: Titel als Betreff, Punkte als Liste
        t.template = Templates.STANDARD;
        String g = Export.crmEntry(t, s);
        assertTrue(g.startsWith("Betreff: Gespräch Kunde\n"));
        assertTrue(g.contains("Kernpunkte:\n- Betreff: Schulung"));
        assertEquals("", Export.crmEntry(t, null));
    }

    @Test
    public void taskStateRoundTripAndCarry() {
        Transcript t = new Transcript();
        t.id = "abcdefgh12";
        t.summary = new Transcript.Summary();
        Transcript.Task a = new Transcript.Task("Angebot schicken", "Marian");
        a.done = true;
        a.remote = "6X7rM8997g3RQmvh";
        a.remoteDone = true;
        t.summary.tasks.add(a);
        t.summary.tasks.add(new Transcript.Task("Rückruf", ""));
        Transcript r = Transcript.fromJson(t.toJson());
        assertTrue(r.summary.tasks.get(0).done);
        assertEquals("6X7rM8997g3RQmvh", r.summary.tasks.get(0).remote);
        assertTrue(r.summary.tasks.get(0).remoteDone);
        assertFalse(r.summary.tasks.get(1).done);
        assertEquals("", r.summary.tasks.get(1).remote);
        // Ungültige ID wird verworfen
        String evil = t.toJson().replace("6X7rM8997g3RQmvh", "../x?y");
        assertEquals("", Transcript.fromJson(evil).summary.tasks.get(0).remote);
        // Neue Zusammenfassung übernimmt Haken bei gleichem Text
        Transcript.Summary n = new Transcript.Summary();
        n.tasks.add(new Transcript.Task("Neu", ""));
        n.tasks.add(new Transcript.Task("Angebot schicken", "Marian"));
        n.carryTaskState(r.summary);
        assertFalse(n.tasks.get(0).done);
        assertTrue(n.tasks.get(1).done);
        assertEquals("6X7rM8997g3RQmvh", n.tasks.get(1).remote);
        // Übersicht
        java.util.List<Transcript> all = java.util.Collections.singletonList(r);
        assertEquals(1, Todoist.openCount(all));
        assertEquals(1, Todoist.tasks(all, false).size());
        assertEquals(2, Todoist.tasks(all, true).size());
    }

    @Test
    public void todoistRequestsAndReplies() {
        assertTrue(Todoist.validToken("0123456789abcdef0123456789abcdef01234567"));
        assertFalse(Todoist.validToken("kurz"));
        assertFalse(Todoist.validToken("0123456789abcdef0123456789abcdef0123456\n"));
        assertEquals("tasks/123/close", Todoist.taskPath("123", "close"));
        try { Todoist.taskPath("../projects", null); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals(Todoist.requestId("a", "b"), Todoist.requestId("a", "b"));
        assertNotEquals(Todoist.requestId("a", "b"), Todoist.requestId("a", "c"));
        Transcript t = new Transcript();
        t.title = "Kunde [Link](http://x)";
        t.createdMs = 0;
        Transcript.Task k = new Transcript.Task("Mail **senden** [hier](http://evil)", "Sprecher 2");
        String body = Todoist.taskBody(k, t, "p1");
        assertFalse(body.contains("["));
        assertFalse(body.contains("**"));
        assertTrue(body.contains("\"project_id\":\"p1\""));
        assertTrue(body.contains("Zuständig: Sprecher 2"));
        assertFalse(Todoist.taskBody(k, t, "").contains("project_id"));
        Todoist.State s = Todoist.parseTask("{\"id\":\"6X7\",\"checked\":true,\"is_deleted\":false,\"content\":\"x\"}");
        assertEquals("6X7", s.id);
        assertTrue(s.checked);
        assertFalse(s.deleted);
        assertNull(Todoist.parseTask("{\"id\":\"a/b\"}"));
        assertNull(Todoist.parseTask("kein json"));
        String page = "{\"results\":[{\"id\":\"1\",\"name\":\"Inbox\"},{\"id\":\"9\",\"name\":\"Pulsa Scribe\",\"is_archived\":true},"
                + "{\"id\":\"7\",\"name\":\"Pulsa Scribe\"}],\"next_cursor\":\"abc\"}";
        assertEquals("7", Todoist.findProject(page));
        assertEquals("abc", Todoist.nextCursor(page));
        assertEquals("", Todoist.nextCursor("{\"next_cursor\":null}"));
        assertEquals("", Todoist.findProject("{\"results\":[]}"));
    }

    @Test
    public void webUrlsAreCheckedAndCleaned() {
        assertEquals("https://heise.de/news?x=1", WebPage.cleanUrl("http://heise.de/news?x=1#frag"));
        assertEquals("https://example.com/", WebPage.cleanUrl("https://Example.com"));
        assertNull(WebPage.cleanUrl("javascript:alert(1)"));
        assertNull(WebPage.cleanUrl("file:///etc/passwd"));
        assertNull(WebPage.cleanUrl("https://user:pw@example.com/"));
        assertNull(WebPage.cleanUrl("https://localhost/x"));
        assertNull(WebPage.cleanUrl("https://192.168.0.1:8080/"));
        assertNull(WebPage.cleanUrl("https://router/"));
        assertNull(WebPage.cleanUrl("https://exa mple.com/"));
        assertEquals("https://www.spiegel.de/a.html", WebPage.findUrl("Schau mal: https://www.spiegel.de/a.html."));
        assertNull(WebPage.findUrl("kein Link hier"));
        assertEquals("dQw4w9WgXcQ", WebPage.youTubeId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10s"));
        assertEquals("dQw4w9WgXcQ", WebPage.youTubeId("https://youtu.be/dQw4w9WgXcQ?si=abc"));
        assertEquals("dQw4w9WgXcQ", WebPage.youTubeId("https://m.youtube.com/shorts/dQw4w9WgXcQ"));
        assertEquals("", WebPage.youTubeId("https://evil-youtube.com/watch?v=dQw4w9WgXcQ"));
        assertEquals("", WebPage.youTubeId("https://www.youtube.com/watch?v=../../x"));
        assertEquals("spiegel.de", WebPage.host("https://www.spiegel.de/a"));
        // URL wird beim Laden geprüft
        Transcript t = new Transcript();
        t.id = "abcdefgh12";
        t.kind = Transcript.KIND_WEB;
        t.url = "https://heise.de/";
        Transcript r = Transcript.fromJson(t.toJson());
        assertEquals(Transcript.KIND_WEB, r.kind);
        assertEquals("https://heise.de/", r.url);
        Transcript bad = Transcript.fromJson(t.toJson().replace("https://heise.de/", "javascript:x"));
        assertEquals("", bad.url);
    }

    @Test
    public void htmlMainTextIsExtracted() {
        StringBuilder art = new StringBuilder();
        for (int i = 0; i < 6; i++) art.append("<p>Dies ist Absatz Nummer ").append(i).append(" mit genug Text, damit er als Inhalt z&auml;hlt &amp; bleibt.</p>");
        String html = "<html><head><title>Fallback</title><meta property=\"og:title\" content=\"Gro&szlig;e Nachricht\">"
                + "<script>alert('x')</script><style>p{}</style></head><body>"
                + "<nav><a href=/>Startseite</a> <a>Login</a></nav>"
                + "<div class=cookie><p>Wir verwenden Cookies. Bitte akzeptieren Sie alle Cookies für die beste Erfahrung.</p></div>"
                + "<article><h1>Große Nachricht</h1>" + art + "<ul><li>Erster wichtiger Punkt der Liste</li></ul></article>"
                + "<footer>Impressum Datenschutz Kontakt und noch mehr Text im Fuß</footer></body></html>";
        WebPage.Page p = WebPage.extract(html);
        assertEquals("Große Nachricht", p.title);
        assertEquals(7, p.paragraphs.size());
        assertTrue(p.paragraphs.get(0).startsWith("Dies ist Absatz Nummer 0"));
        assertTrue(p.paragraphs.get(0).contains("zählt & bleibt"));
        assertEquals("• Erster wichtiger Punkt der Liste", p.paragraphs.get(6));
        for (String x : p.paragraphs) {
            assertFalse(x.contains("alert"));
            assertFalse(x.contains("Cookies"));
            assertFalse(x.contains("Impressum"));
            assertFalse(x.contains("<"));
        }
        assertTrue(p.length() >= WebPage.MIN_TEXT);
        // Nur Cookie-Wand → zu wenig Text
        assertTrue(WebPage.extract("<body><p>Bitte akzeptieren Sie Cookies, um fortzufahren und die Seite zu sehen.</p></body>").length() < WebPage.MIN_TEXT);
        assertEquals("ISO-8859-1", WebPage.charset("text/html; charset=ISO-8859-1", null));
        assertEquals("windows-1252", WebPage.charset(null, "<meta charset=\"windows-1252\">".getBytes()));
        assertEquals("UTF-8", WebPage.charset("text/html; charset=bogus", null));
    }

    @Test
    public void geminiUrlReplyNeedsRetrievalAndText() {
        assertNull(WebPage.parseUrlReply("NO_ACCESS"));
        assertNull(WebPage.parseUrlReply("  "));
        WebPage.Page p = WebPage.parseUrlReply("TITLE: Test Seite\n\nErster Absatz.\n\nZweiter Absatz.");
        assertEquals("Test Seite", p.title);
        assertEquals(2, p.paragraphs.size());
        String ok = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"x\"}]},\"urlContextMetadata\":{\"urlMetadata\":"
                + "[{\"retrievedUrl\":\"https://a.de\",\"urlRetrievalStatus\":\"URL_RETRIEVAL_STATUS_SUCCESS\"}]}}]}";
        assertTrue(Gemini.reply(ok).urlRetrieved);
        assertFalse(Gemini.reply(ok.replace("STATUS_SUCCESS", "STATUS_ERROR")).urlRetrieved);
        assertFalse(Gemini.reply("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"x\"}]}}]}").urlRetrieved);
        String body = WebPage.urlRequest(WebPage.urlPrompt("https://a.de/", "de"));
        assertTrue(body.contains("\"tools\":[{\"url_context\":{}}]"));
        String yt = WebPage.youTubeRequest("p", WebPage.youTubeUrl("dQw4w9WgXcQ"));
        assertTrue(yt.contains("\"fileUri\":\"https://www.youtube.com/watch?v=dQw4w9WgXcQ\""));
    }
}
