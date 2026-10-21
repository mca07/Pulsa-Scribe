package de.mca.pulsascribe;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

import de.mca.pulsascribe.core.Ai;

/**
 * HTTPS zu Claude, OpenAI oder Mistral: fester Host je Anbieter, keine Weiterleitungen, Schlüssel nur im Header,
 * Anhänge (Bild/PDF/Ton) werden direkt aus der Datei gestreamt. Abbrechbar.
 */
final class AiClient {
    private final String provider, key;
    private volatile HttpsURLConnection current;
    private volatile boolean canceled;

    AiClient(String provider, String key) {
        this.provider = Ai.clean(provider);
        if (Ai.GEMINI.equals(this.provider) || !Ai.validKey(this.provider, key)) throw new IllegalArgumentException("Schlüssel");
        this.key = key;
    }

    String provider() { return provider; }

    void cancel() {
        canceled = true;
        HttpsURLConnection c = current;
        if (c != null) c.disconnect();
    }

    /** JSON-Anfrage ohne Anhang. */
    GeminiClient.Response json(String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        return send(Ai.endpoint(provider), "application/json; charset=utf-8", b.length, os -> os.write(b));
    }

    /** JSON-Anfrage mit Anhang aus Datei (Base64 zwischen prefix und suffix). */
    GeminiClient.Response withFile(Ai.Body body, File file) throws IOException {
        long len = file.length();
        if (len <= 0 || len > GeminiClient.MAX_AUDIO_BYTES) throw new IOException("Datei zu groß");
        byte[] pre = body.prefix.getBytes(StandardCharsets.UTF_8), suf = body.suffix.getBytes(StandardCharsets.UTF_8);
        long total = pre.length + 4 * ((len + 2) / 3) + suf.length;
        return send(Ai.endpoint(provider), "application/json; charset=utf-8", total, os -> {
            os.write(pre);
            OutputStream enc = Base64.getEncoder().wrap(new NonClosing(os));
            copy(file, enc);
            enc.close();
            os.write(suf);
        });
    }

    /** Transkription (multipart/form-data) mit Felder-Liste und Tondatei. */
    GeminiClient.Response transcribe(List<String[]> fields, File audio, String fileName, String mime) throws IOException {
        String boundary = "----pulsa" + Long.toHexString(new SecureRandom().nextLong());
        StringBuilder head = new StringBuilder();
        for (String[] f : fields) {
            head.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"").append(f[0]).append("\"\r\n\r\n")
                    .append(f[1]).append("\r\n");
        }
        head.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(fileName).append("\"\r\n")
                .append("Content-Type: ").append(mime).append("\r\n\r\n");
        byte[] pre = head.toString().getBytes(StandardCharsets.UTF_8);
        byte[] suf = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        long len = audio.length();
        if (len <= 0 || len > 24L * 1024 * 1024) throw new IOException("Datei zu groß");
        return send(Ai.transcriptionEndpoint(provider), "multipart/form-data; boundary=" + boundary,
                pre.length + len + suf.length, os -> {
                    os.write(pre);
                    copy(audio, os);
                    os.write(suf);
                });
    }

    /** Vorlesen (nur OpenAI): rohes PCM 24 kHz. Liefert Bytes oder null bei Fehler; code in out[0]. */
    byte[] speech(String body, int[] codeOut) throws IOException {
        if (!Ai.OPENAI.equals(provider)) throw new IOException("speech");
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        URL url = new URL("https://api.openai.com/v1/audio/speech");
        HttpsURLConnection c = open(url, "application/json; charset=utf-8", b.length);
        try {
            try (OutputStream os = c.getOutputStream()) { os.write(b); }
            int code = c.getResponseCode();
            codeOut[0] = code;
            if (code != 200) { drain(c.getErrorStream()); return null; }
            return readBytes(c.getInputStream(), 12 * 1024 * 1024);
        } finally {
            current = null;
            c.disconnect();
        }
    }

    private interface Writer {
        void write(OutputStream os) throws IOException;
    }

    private GeminiClient.Response send(String endpoint, String type, long length, Writer w) throws IOException {
        HttpsURLConnection c = open(new URL(endpoint), type, length);
        try {
            try (OutputStream os = c.getOutputStream()) { w.write(os); }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : new String(readBytes(in, GeminiClient.MAX_RESPONSE), StandardCharsets.UTF_8);
            return new GeminiClient.Response(code, text);
        } finally {
            current = null;
            c.disconnect();
        }
    }

    private HttpsURLConnection open(URL url, String type, long length) throws IOException {
        if (canceled) throw new IOException("canceled");
        if (!"https".equals(url.getProtocol()) || !Ai.host(provider).equals(url.getHost())) throw new IOException("Host");
        HttpsURLConnection c = (HttpsURLConnection) url.openConnection();
        current = c;
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(GeminiClient.CONNECT_TIMEOUT);
        c.setReadTimeout(GeminiClient.READ_TIMEOUT);
        c.setUseCaches(false);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", type);
        c.setRequestProperty("Accept", "application/json");
        if (Ai.CLAUDE.equals(provider)) {
            c.setRequestProperty("x-api-key", key);
            c.setRequestProperty("anthropic-version", "2023-06-01");
        } else {
            c.setRequestProperty("Authorization", "Bearer " + key);
        }
        c.setFixedLengthStreamingMode(length);
        return c;
    }

    private void copy(File f, OutputStream os) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[48 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (canceled) throw new IOException("canceled");
                os.write(buf, 0, n);
            }
        }
    }

    private static byte[] readBytes(InputStream in, int max) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) {
                if (bos.size() + n > max) throw new IOException("Antwort zu groß");
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    private static void drain(InputStream in) {
        if (in == null) return;
        try (InputStream is = in) {
            byte[] buf = new byte[4096];
            int total = 0, n;
            while ((n = is.read(buf)) > 0 && total < 65536) total += n;
        } catch (IOException ignored) { }
    }

    private static final class NonClosing extends FilterOutputStream {
        NonClosing(OutputStream out) { super(out); }

        @Override
        public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }

        @Override
        public void close() throws IOException { flush(); }
    }
}
