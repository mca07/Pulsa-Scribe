package de.mca.pulsascribe.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

public class AiTest {

    /** Platzhalter, der nicht wie ein echter Schlüssel aussieht (Secret-Scanning). */
    private static String fake(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append('x');
        return b.toString();
    }

    @Test
    public void providersAndKeys() {
        assertEquals(Ai.GEMINI, Ai.clean("evil"));
        assertEquals(Ai.CLAUDE, Ai.clean("claude"));
        assertTrue(Ai.validKey(Ai.CLAUDE, "sk-" + "ant-" + fake(40)));
        assertFalse(Ai.validKey(Ai.CLAUDE, "sk-abc"));
        assertTrue(Ai.validKey(Ai.OPENAI, "sk-" + fake(40)));
        assertFalse(Ai.validKey(Ai.OPENAI, "sk-proj abc\n"));
        assertTrue(Ai.validKey(Ai.MISTRAL, fake(32)));
        assertFalse(Ai.canAudio(Ai.CLAUDE));
        assertTrue(Ai.canAudio(Ai.OPENAI));
        assertFalse(Ai.canPdf(Ai.MISTRAL));
        assertFalse(Ai.canWeb(Ai.MISTRAL));
        assertTrue(Ai.canSpeak(Ai.OPENAI));
        assertFalse(Ai.canSpeak(Ai.CLAUDE));
        assertEquals("api.anthropic.com", Ai.host(Ai.CLAUDE));
    }

    @Test
    public void schemaIsConvertedForStrictOutput() throws Exception {
        Map<String, Object> s = Json.obj(Json.parse(Ai.jsonSchema(Gemini.summarySchema())));
        assertEquals("object", Json.str(s, "type"));
        assertEquals(Boolean.FALSE, s.get("additionalProperties"));
        List<Object> req = Json.arr(s, "required");
        assertEquals(Json.obj(s, "properties").size(), req.size());
        assertEquals("overview", req.get(0));
        Map<String, Object> tasks = Json.obj(Json.obj(s, "properties"), "tasks");
        assertEquals("array", Json.str(tasks, "type"));
        Map<String, Object> item = Json.obj(tasks, "items");
        assertEquals("object", Json.str(item, "type"));
        assertEquals(Boolean.FALSE, item.get("additionalProperties"));
        assertFalse(Ai.jsonSchema(Gemini.summarySchema()).contains("propertyOrdering"));
        assertFalse(Ai.jsonSchema(Gemini.transcriptSchema()).contains("STRING"));
    }

    @Test
    public void requestsArePerProvider() throws Exception {
        String prompt = "Fasse \"das\" zusammen";
        for (String p : new String[]{Ai.CLAUDE, Ai.OPENAI, Ai.MISTRAL}) {
            Ai.Body b = Ai.request(p, Ai.models(p)[0], prompt, Gemini.summarySchema(), null, false);
            assertEquals("", b.suffix);
            Map<String, Object> m = Json.obj(Json.parse(b.prefix));
            assertEquals(Ai.models(p)[0], Json.str(m, "model"));
            assertTrue(b.prefix.contains("Fasse \\\"das\\\" zusammen"));
            // mit Bild: Base64 dazwischen ergibt gültiges JSON
            Ai.Body img = Ai.request(p, "m", prompt, Gemini.ocrSchema(), Ai.JPEG, false);
            String full = img.prefix + "QUJD" + img.suffix;
            assertNotNull(Json.parse(full));
            assertTrue(full.contains("QUJD"));
        }
        String claude = Ai.request(Ai.CLAUDE, "m", "p", Gemini.summarySchema(), null, false).prefix;
        assertTrue(claude.contains("\"output_config\":{\"format\":{\"type\":\"json_schema\""));
        assertTrue(claude.contains("\"max_tokens\""));
        String claudeWeb = Ai.request(Ai.CLAUDE, "m", "p", null, null, true).prefix;
        assertTrue(claudeWeb.contains("web_search_20250305"));
        assertFalse(claudeWeb.contains("output_config"));
        String claudePdf = Ai.request(Ai.CLAUDE, "m", "p", Gemini.ocrSchema(), Ai.PDF, false).prefix;
        assertTrue(claudePdf.contains("\"type\":\"document\""));
        String oa = Ai.request(Ai.OPENAI, "m", "p", Gemini.summarySchema(), null, false).prefix;
        assertTrue(oa.contains("\"store\":false"));
        assertTrue(oa.contains("\"strict\":true"));
        assertTrue(Ai.request(Ai.OPENAI, "m", "p", null, null, true).prefix.contains("\"type\":\"web_search\""));
        assertTrue(Ai.request(Ai.OPENAI, "m", "p", Gemini.ocrSchema(), Ai.PDF, false).prefix.contains("data:application/pdf;base64,"));
        String mi = Ai.request(Ai.MISTRAL, "m", "p", Gemini.summarySchema(), null, false).prefix;
        assertTrue(mi.contains("\"response_format\":{\"type\":\"json_schema\""));
        assertTrue(mi.contains("\"random_seed\":" + Gemini.SEED));
        try { Ai.request(Ai.MISTRAL, "m", "p", null, Ai.PDF, false); throw new AssertionError("pdf"); } catch (IllegalArgumentException expected) { }
    }

    @Test
    public void repliesAreRead() {
        Gemini.Reply c = Ai.reply(Ai.CLAUDE, "{\"model\":\"claude-sonnet-5-5\",\"stop_reason\":\"end_turn\",\"content\":["
                + "{\"type\":\"server_tool_use\",\"id\":\"x\"},{\"type\":\"text\",\"text\":\"Teil 1 \"},"
                + "{\"type\":\"text\",\"text\":\"Teil 2\",\"citations\":[{\"type\":\"web_search_result_location\",\"url\":\"https://a.de/x\",\"title\":\"A\"},"
                + "{\"url\":\"javascript:x\",\"title\":\"b\"}]}]}");
        assertEquals("Teil 1 Teil 2", c.text);
        assertEquals(1, c.sources.size());
        assertEquals("STOP", c.finishReason);
        assertEquals("MAX_TOKENS", Ai.reply(Ai.CLAUDE, "{\"stop_reason\":\"max_tokens\",\"content\":[]}").finishReason);
        assertEquals("REFUSAL", Ai.reply(Ai.CLAUDE, "{\"stop_reason\":\"refusal\",\"content\":[]}").blockReason);
        Gemini.Reply o = Ai.reply(Ai.OPENAI, "{\"status\":\"completed\",\"output\":[{\"type\":\"web_search_call\"},"
                + "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"{\\\"a\\\":1}\",\"annotations\":["
                + "{\"type\":\"url_citation\",\"url\":\"https://b.de\",\"title\":\"B\"}]}]}]}");
        assertEquals("{\"a\":1}", o.text);
        assertEquals("https://b.de/", o.sources.get(0)[1]);
        assertEquals("MAX_TOKENS", Ai.reply(Ai.OPENAI, "{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output\":[]}").finishReason);
        Gemini.Reply mi = Ai.reply(Ai.MISTRAL, "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"Hallo\"}}]}");
        assertEquals("Hallo", mi.text);
        assertEquals("MAX_TOKENS", Ai.reply(Ai.MISTRAL, "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"x\"}]}}]}").finishReason);
        assertNull(Ai.reply(Ai.OPENAI, "kein json"));
        assertEquals("overloaded_error", Ai.errorStatus(Ai.CLAUDE, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"x\"}}"));
    }

    @Test
    public void transcriptionsAreParsed() {
        java.util.Map<String, String> known = new java.util.LinkedHashMap<>();
        String oa = "{\"text\":\"x\",\"segments\":[{\"speaker\":\"A\",\"start\":0.0,\"end\":2.5,\"text\":\"Hallo\"},"
                + "{\"speaker\":\"B\",\"start\":3.2,\"end\":5,\"text\":\"Guten Tag\"},{\"speaker\":\"A\",\"start\":6.0,\"text\":\"Gut\"}]}";
        Gemini.Segments s = Ai.parseTranscription(oa, 600_000, 60_000, true, known);
        assertEquals(3, s.list.size());
        assertEquals(60_000, s.list.get(0).startMs);
        assertEquals("Sprecher 1", s.list.get(0).speaker);
        assertEquals("Sprecher 2", s.list.get(1).speaker);
        assertEquals("Sprecher 1", s.list.get(2).speaker);
        assertEquals(63_200, s.list.get(1).startMs);
        // nächster Teil: gleiche Zuordnung
        String mi = "{\"model\":\"v\",\"text\":\"x\",\"segments\":[{\"type\":\"transcription_segment\",\"text\":\"Weiter\",\"start\":1.0,\"end\":2.0,\"speaker_id\":\"B\"}]}";
        Gemini.Segments s2 = Ai.parseTranscription(mi, 600_000, 0, true, known);
        assertEquals("Sprecher 2", s2.list.get(0).speaker);
        Gemini.Segments plain = Ai.parseTranscription("{\"text\":\"Nur Text\"}", 1000, 0, true, known);
        assertEquals("Nur Text", plain.list.get(0).text);
        Gemini.Segments noDia = Ai.parseTranscription(oa, 600_000, 0, false, new java.util.HashMap<>());
        assertEquals("", noDia.list.get(0).speaker);
        assertNull(Ai.parseTranscription("<html>", 1, 0, true, known));
        boolean hasLang = false;
        for (String[] f : Ai.transcriptionFields(Ai.MISTRAL, "voxtral-mini-latest", "de", true, "Myfactory, nerpcon")) {
            if (f[0].equals("language")) hasLang = true;
        }
        assertFalse(hasLang); // Mistral: Zeitstempel nicht zusammen mit Sprache
        int bias = 0;
        for (String[] f : Ai.transcriptionFields(Ai.MISTRAL, "m", "de", true, "Myfactory, nerpcon")) if (f[0].equals("context_bias")) bias++;
        assertEquals(2, bias);
        assertTrue(Ai.speechRequest("Hallo", "evil").contains("\"voice\":\"marin\""));
    }
}
