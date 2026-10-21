package de.mca.pulsascribe;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import de.mca.pulsascribe.core.Gemini;
import de.mca.pulsascribe.core.Transcript;
import de.mca.pulsascribe.core.WebPage;

/**
 * App-Browser für Seiten mit Anmeldung (z. B. Lernplattformen). Bewusste Ausnahme von „kein WebView“, abgesichert:
 * nur HTTPS, keine Datei-/Content-Zugriffe, keine JavaScript-Brücke, keine neuen Fenster, keine Downloads,
 * keine Kamera/Mikrofon/Standort, keine Dateiauswahl, Bildschirmschutz (FLAG_SECURE), Drittanbieter-Cookies aus.
 * „Seite lesen“ übernimmt nur den Text der gerade angezeigten Seite.
 */
public final class BrowserActivity extends Activity {
    static final String EXTRA_URL = "url";

    private WebView web;
    private EditText urlField;
    private ProgressBar progress;
    private boolean reading;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        Design.apply(this);
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); // Anmeldedaten/Kursinhalte nicht in Screenshots/Übersicht
        setContentView(R.layout.activity_browser);
        urlField = findViewById(R.id.br_url);
        progress = findViewById(R.id.br_progress);
        try {
            web = new WebView(this);
        } catch (RuntimeException e) {
            // WebView fehlt oder wird gerade aktualisiert
            Toast.makeText(this, R.string.br_no_webview, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        ((FrameLayout) findViewById(R.id.br_web_box)).addView(web, new FrameLayout.LayoutParams(-1, -1));

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);           // Anmeldung und Lernplattformen brauchen JavaScript
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setGeolocationEnabled(false);
        ws.setSupportMultipleWindows(false);
        ws.setJavaScriptCanOpenWindowsAutomatically(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        ws.setSafeBrowsingEnabled(true);
        ws.setSaveFormData(false);
        ws.setMediaPlaybackRequiresUserGesture(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setDownloadListener((url, ua, cd, mime, len) ->
                Toast.makeText(this, R.string.br_no_download, Toast.LENGTH_LONG).show());

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                if ("https".equals(scheme)) return false;
                if ("http".equals(scheme)) {
                    String up = WebPage.cleanUrl(u.toString()); // auf HTTPS heben
                    if (up != null) v.loadUrl(up);
                    return true;
                }
                // intent:, file:, content:, javascript:, mailto: … werden nicht geöffnet
                Toast.makeText(BrowserActivity.this, R.string.br_blocked_link, Toast.LENGTH_SHORT).show();
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                if (!urlField.hasFocus()) urlField.setText(url);
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (!urlField.hasFocus()) urlField.setText(url);
                progress.setVisibility(View.GONE);
            }
            // SSL-Fehler: Standardverhalten (abbrechen) – keine Ausnahmen
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                progress.setProgress(p);
            }

            @Override
            public void onPermissionRequest(PermissionRequest r) {
                r.deny();
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                cb.onReceiveValue(null);
                return true;
            }
        });

        findViewById(R.id.br_close).setOnClickListener(v -> finish());
        findViewById(R.id.br_back).setOnClickListener(v -> { if (web.canGoBack()) web.goBack(); else finish(); });
        findViewById(R.id.br_read).setOnClickListener(v -> readPage());
        findViewById(R.id.br_more).setOnClickListener(this::menu);
        urlField.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_GO || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                go(urlField.getText().toString());
                return true;
            }
            return false;
        });

        if (state != null) {
            web.restoreState(state);
        } else {
            String start = getIntent().getStringExtra(EXTRA_URL);
            if (start != null && WebPage.findUrl(start) != null) go(start);
            else urlField.requestFocus();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private void go(String input) {
        String u = WebPage.findUrl(input.contains("://") ? input : "https://" + input.trim());
        if (u == null) {
            urlField.setError(getString(R.string.web_bad_url));
            return;
        }
        urlField.clearFocus();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(urlField.getWindowToken(), 0);
        web.loadUrl(u);
    }

    private void menu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, R.string.br_reload);
        m.getMenu().add(0, 2, 0, R.string.br_open_external);
        m.getMenu().add(0, 3, 0, R.string.br_clear);
        m.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1: web.reload(); return true;
                case 2: {
                    String u = WebPage.cleanUrl(web.getUrl());
                    if (u != null) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)).addCategory(Intent.CATEGORY_BROWSABLE));
                        } catch (RuntimeException ignored) { }
                    }
                    return true;
                }
                case 3: clearLogins(this, () -> web.loadUrl("about:blank")); return true;
                default: return false;
            }
        });
        m.show();
    }

    /** Alle Anmeldungen des App-Browsers löschen (Cookies, Web-Speicher, Cache). */
    static void clearLogins(Activity a, Runnable after) {
        Ui.dialog(a).setTitle(R.string.br_clear).setMessage(R.string.br_clear_text)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    CookieManager.getInstance().removeAllCookies(ok -> CookieManager.getInstance().flush());
                    WebStorage.getInstance().deleteAllData();
                    try {
                        new WebView(a).clearCache(true);
                    } catch (RuntimeException ignored) { }
                    Toast.makeText(a, R.string.br_cleared, Toast.LENGTH_SHORT).show();
                    if (after != null) after.run();
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    // ------------------------------------------------------------------ Seite übernehmen

    private void readPage() {
        String url = WebPage.cleanUrl(web.getUrl());
        if (url == null || reading) {
            Toast.makeText(this, R.string.br_nothing, Toast.LENGTH_SHORT).show();
            return;
        }
        reading = true;
        web.evaluateJavascript(WebPage.BROWSER_SCRIPT, result -> {
            reading = false;
            if (isFinishing() || web == null) return;
            WebPage.Page page = WebPage.parseBrowser(result);
            if (page.paragraphs.isEmpty()) {
                Toast.makeText(this, R.string.br_nothing, Toast.LENGTH_LONG).show();
                return;
            }
            start(url, page);
        });
    }

    private void start(String url, WebPage.Page page) {
        String title = page.title.isEmpty() ? WebPage.host(url) : page.title;
        LinearLayout col = Ui.column(this);
        TextView info = Ui.body(this, getString(R.string.br_found, page.paragraphs.size(), page.length()));
        info.setTextSize(13);
        info.setTextColor(Design.color(this, R.attr.pl_muted));
        Ui.add(col, info);
        OptionsForm form = new OptionsForm(this, col, title, Settings.lastFolder(this), false);
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(col);
        Ui.dialog(this).setTitle(R.string.br_read).setView(sc)
                .setPositiveButton(R.string.web_go_summary, (d, w) -> create(url, page, form, false))
                .setNeutralButton(R.string.web_go_read, (d, w) -> create(url, page, form, true))
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void create(String url, WebPage.Page page, OptionsForm form, boolean readOnly) {
        if (!readOnly && !GeminiKey.has(this)) {
            KeyDialog.show(this, null);
            return;
        }
        Transcript t = form.build();
        t.kind = Transcript.KIND_WEB;
        t.url = url;
        t.source = WebPage.host(url);
        int total = 0;
        for (String p : page.paragraphs) {
            total += p.length();
            if (total > Gemini.MAX_TEXT_INPUT) break;
            t.segments.add(new Transcript.Segment(0, "", p));
        }
        boolean read = form.readAfter();
        if (readOnly) {
            t.summarize = false;
            read = true;
        }
        t.status = Transcript.QUEUED;
        Store.save(this, t);
        TranscribeService.start(this, t.id, null);
        // Browser bleibt offen: „Zurück“ führt zur nächsten Lektion
        startActivity(new Intent(this, TranscriptActivity.class).putExtra(TranscribeService.EXTRA_ID, t.id)
                .putExtra(TranscriptActivity.EXTRA_READ_WHEN_DONE, read));
    }
}
