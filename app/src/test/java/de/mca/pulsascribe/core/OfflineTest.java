package de.mca.pulsascribe.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

public class OfflineTest {

    @Test
    public void modelsHaveValidHashesAndHttpsUrls() {
        for (Offline.Model m : Offline.MODELS) {
            assertTrue(Offline.validHash(m.sha256));
            assertTrue(m.url().startsWith("https://huggingface.co/"));
            assertTrue(m.size > 10_000_000L);
        }
        assertNull(Offline.model("../x"));
        assertNotNull(Offline.model("small"));
    }

    @Test
    public void audioSpecificConfigMatchesKnownValues() {
        // AAC-LC, 44,1 kHz, Stereo = 0x12 0x10; 16 kHz Mono = 0x14 0x08
        byte[] a = Offline.audioSpecificConfig(4, 2);
        assertEquals(0x12, a[0] & 0xFF);
        assertEquals(0x10, a[1] & 0xFF);
        byte[] b = Offline.audioSpecificConfig(8, 1);
        assertEquals(0x14, b[0] & 0xFF);
        assertEquals(0x08, b[1] & 0xFF);
    }

    @Test
    public void resampleLength() {
        float[] in = new float[48000];
        for (int i = 0; i < in.length; i++) in[i] = (float) Math.sin(i / 10.0);
        assertEquals(16000, Offline.resample(in, in.length, 48000).length);
        assertEquals(100, Offline.resample(in, 100, 16000).length);
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void outputIsCleanedAndMerged() {
        String json = "[{\"t\":0,\"x\":\" Hallo zusammen.\"},{\"t\":250,\"x\":\"[Musik]\"},{\"t\":300,\"x\":\"(Applaus)\"},"
                + "{\"t\":400,\"x\":\"Heute geht es um Myfactory.\"},{\"t\":3600,\"x\":\"Danke.\"},{\"t\":3700,\"x\":\"Danke.\"},"
                + "{\"t\":3800,\"x\":\"Danke.\"},{\"t\":99999999,\"x\":\"zu spät\"},{\"t\":-1,\"x\":\"ohne Zeit\"},\"kaputt\"]";
        List<Transcript.Segment> s = Offline.parse(b(json), 60_000, 60_000);
        assertNotNull(s);
        assertTrue(s.size() >= 2);
        assertEquals(60_000, s.get(0).startMs);
        String all = "";
        for (Transcript.Segment x : s) all += x.text + "|";
        assertTrue(all.contains("Hallo zusammen. Heute geht es um Myfactory."));
        assertTrue(!all.contains("[Musik]") && !all.contains("Applaus"));
        assertEquals(2, all.split("Danke\\.", -1).length - 1);
        long prev = 0;
        for (Transcript.Segment x : s) { assertTrue(x.startMs >= prev); prev = x.startMs; assertTrue(x.startMs <= 60_000 + 65_000); }
        assertNull(Offline.parse(b("kaputt"), 0, 1000));
        assertTrue(Offline.parse(b("[]"), 0, 1000).isEmpty());
    }
}
