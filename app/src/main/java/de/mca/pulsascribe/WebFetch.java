package de.mca.pulsascribe;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URL;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

import de.mca.pulsascribe.core.WebPage;

/**
 * Lädt eine Webseite für die Textauswertung: nur HTTPS, Weiterleitungen einzeln geprüft (max. 5),
 * keine Adressen im eigenen Netz, keine Cookies, Größen- und Zeitlimits. Es wird nichts angezeigt oder ausgeführt.
 */
final class WebFetch {
    private WebFetch() {}

    static final int MAX_HTML = 3 * 1024 * 1024;
    static final int MAX_PDF = 15 * 1024 * 1024;
    private static final int TIMEOUT = 20_000;

    static final class Result {
        String url = "";          // endgültige Adresse nach Weiterleitungen
        String html;              // gesetzt bei HTML/Text
        File pdf;                 // gesetzt bei PDF (Datei im Cache, danach löschen)
        int code;
    }

    /** Fehler mit Text-Ressource für die Anzeige. */
    static final class Failure extends IOException {
        final int res;

        Failure(int res) {
            super("web");
            this.res = res;
        }
    }

    static Result fetch(String startUrl, File pdfTarget, Cancel cancel) throws IOException {
        String url = WebPage.cleanUrl(startUrl);
        if (url == null) throw new Failure(R.string.web_bad_url);
        for (int hop = 0; hop < 6; hop++) {
            if (cancel.canceled()) throw new IOException("canceled");
            URL u = new URL(url);
            checkHost(u.getHost());
            HttpsURLConnection c = (HttpsURLConnection) u.openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(TIMEOUT);
                c.setReadTimeout(TIMEOUT);
                c.setUseCaches(false);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36 PulsaScribe");
                c.setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,application/pdf;q=0.8,*/*;q=0.5");
                c.setRequestProperty("Accept-Language", "de-DE,de;q=0.9,en;q=0.7");
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) throw new Failure(R.string.web_failed);
                    String next = WebPage.cleanUrl(new URL(u, loc).toString());
                    if (next == null) throw new Failure(R.string.web_bad_url);
                    url = next;
                    continue;
                }
                if (code == 401 || code == 403) throw new Failure(R.string.web_blocked);
                if (code == 404 || code == 410) throw new Failure(R.string.web_not_found);
                if (code != 200) throw new Failure(R.string.web_failed);
                String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.ROOT);
                Result r = new Result();
                r.url = url;
                r.code = code;
                if (type.startsWith("application/pdf")) {
                    if (pdfTarget == null) throw new Failure(R.string.web_unsupported);
                    try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(pdfTarget)) {
                        copy(in, os, MAX_PDF, cancel);
                    } catch (IOException e) {
                        //noinspection ResultOfMethodCallIgnored
                        pdfTarget.delete();
                        throw e;
                    }
                    r.pdf = pdfTarget;
                    return r;
                }
                if (!(type.isEmpty() || type.startsWith("text/html") || type.startsWith("application/xhtml") || type.startsWith("text/plain")
                        || type.startsWith("application/json"))) {
                    throw new Failure(R.string.web_unsupported);
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    copy(in, bos, MAX_HTML, cancel);
                }
                byte[] bytes = bos.toByteArray();
                String cs = WebPage.charset(c.getContentType(), bytes);
                String text = new String(bytes, cs);
                if (type.startsWith("text/plain")) text = "<body><pre>" + text.replace("&", "&amp;").replace("<", "&lt;") + "</pre></body>";
                r.html = text;
                return r;
            } finally {
                c.disconnect();
            }
        }
        throw new Failure(R.string.web_failed);
    }

    interface Cancel {
        boolean canceled();
    }

    private static void copy(InputStream in, OutputStream os, int max, Cancel cancel) throws IOException {
        byte[] buf = new byte[16 * 1024];
        int n, total = 0;
        while ((n = in.read(buf)) > 0) {
            if (cancel.canceled()) throw new IOException("canceled");
            total += n;
            if (total > max) throw new Failure(R.string.web_too_big);
            os.write(buf, 0, n);
        }
    }

    /** Keine Adressen im Heim-/Firmennetz oder auf dem Gerät selbst. */
    private static void checkHost(String host) throws IOException {
        InetAddress[] all;
        try {
            all = InetAddress.getAllByName(host);
        } catch (IOException e) {
            throw new Failure(R.string.web_offline);
        }
        for (InetAddress a : all) {
            if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                    || a.isMulticastAddress()) throw new Failure(R.string.web_private);
            byte[] b = a.getAddress();
            if (a instanceof Inet6Address && (b[0] & 0xfe) == 0xfc) throw new Failure(R.string.web_private); // fc00::/7
            if (b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64) throw new Failure(R.string.web_private); // 100.64/10
        }
    }
}
