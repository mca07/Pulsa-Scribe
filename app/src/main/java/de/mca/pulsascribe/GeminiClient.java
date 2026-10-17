package de.mca.pulsascribe;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.net.ssl.HttpsURLConnection;

import de.mca.pulsascribe.core.Gemini;

/**
 * Sendet Anfragen an Gemini. Nur https, nur an den festen Google-Host, keine Weiterleitungen,
 * Schlüssel nur im Header. Audio wird direkt aus der Datei als Base64 gestreamt (kein großer Puffer).
 */
final class GeminiClient {
    static final int CONNECT_TIMEOUT = 15_000;
    static final int READ_TIMEOUT = 300_000;   // lange Aufnahmen brauchen Zeit
    static final int MAX_RESPONSE = 6 * 1024 * 1024;
    static final long MAX_AUDIO_BYTES = 18L * 1024 * 1024; // Anfrage bleibt unter 25 MB

    static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private final String key;
    private volatile HttpsURLConnection current;
    private volatile boolean canceled;

    GeminiClient(String key) {
        if (!Gemini.validKey(key)) throw new IllegalArgumentException("Schlüssel");
        this.key = key;
    }

    /** Bricht eine laufende Anfrage sofort ab. */
    void cancel() {
        canceled = true;
        HttpsURLConnection c = current;
        if (c != null) c.disconnect();
    }

    /** Transkriptionsanfrage: Prompt + Audioausschnitt [from, to) der ADTS-Datei. */
    Response audio(String model, String prompt, File audio, long from, long to) throws IOException {
        long len = to - from;
        if (len <= 0 || len > MAX_AUDIO_BYTES) throw new IOException("Teil zu groß");
        byte[] prefix = Gemini.audioRequestPrefix(prompt, "audio/aac").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = Gemini.audioRequestSuffix().getBytes(StandardCharsets.UTF_8);
        long b64 = 4 * ((len + 2) / 3);
        long total = prefix.length + b64 + suffix.length;
        return post(model, total, os -> {
            os.write(prefix);
            OutputStream enc = Base64.getEncoder().wrap(new NonClosing(os));
            try (RandomAccessFile raf = new RandomAccessFile(audio, "r")) {
                raf.seek(from);
                byte[] buf = new byte[48 * 1024];
                long left = len;
                while (left > 0) {
                    if (canceled) throw new IOException("canceled");
                    int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) throw new IOException("Datei zu kurz");
                    enc.write(buf, 0, n);
                    left -= n;
                }
            }
            enc.close(); // schreibt das Base64-Ende, schließt aber nicht os
            os.write(suffix);
        });
    }

    Response json(String model, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        return post(model, b.length, os -> os.write(b));
    }

    private interface Body {
        void write(OutputStream os) throws IOException;
    }

    private Response post(String model, long length, Body body) throws IOException {
        if (canceled) throw new IOException("canceled");
        URL url = new URL(Gemini.endpoint(model));
        if (!"https".equals(url.getProtocol()) || !Gemini.HOST.equals(url.getHost())) throw new IOException("Host");
        HttpsURLConnection c = (HttpsURLConnection) url.openConnection();
        current = c;
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(READ_TIMEOUT);
            c.setUseCaches(false);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("x-goog-api-key", key);
            c.setFixedLengthStreamingMode(length);
            try (OutputStream os = c.getOutputStream()) {
                body.write(os);
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : read(in);
            return new Response(code, text);
        } finally {
            current = null;
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) {
                if (bos.size() + n > MAX_RESPONSE) throw new IOException("Antwort zu groß");
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        }
    }

    /** Verhindert, dass der Base64-Encoder den Netzwerkstrom schließt. */
    private static final class NonClosing extends FilterOutputStream {
        NonClosing(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            out.flush();
        }
    }
}
