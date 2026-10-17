package de.mca.pulsascribe;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import de.mca.pulsascribe.core.Folders;
import de.mca.pulsascribe.core.Gemini;
import de.mca.pulsascribe.core.Search;
import de.mca.pulsascribe.core.Text;
import de.mca.pulsascribe.core.Transcript;

/** Ordner und Transkripte, Datei wählen, Suche über alles, Teilen-Ziel. */
public final class MainActivity extends Activity {
    private static final int REQ_PICK = 1, REQ_NOTIF = 2;
    private static final String STATE_PENDING = "pending_uri", STATE_FOLDER = "folder";
    static final String EXTRA_FOLDER = "folder";

    /** Zeile der Liste: Ordner oder Transkript (mit optionalem Suchtreffer). */
    private static final class Row {
        Folders.Folder folder;
        Transcript t;
        Search.Hit hit;
    }

    private final List<Row> rows = new ArrayList<>();
    private Adapter adapter;
    private TextView empty;
    private LinearLayout crumbs;
    private HorizontalScrollView crumbsScroll;
    private View searchBar, crumbsRow;
    private EditText searchInput;
    private String folder = Folders.ROOT;
    private String query = "";
    /** Mehrfachauswahl: IDs der markierten Transkripte (leer = keine Auswahl aktiv). */
    private final java.util.LinkedHashSet<String> selected = new java.util.LinkedHashSet<>();
    private View selectBar;
    private Uri pendingUri; // Datei wartet auf Hinweis/Schlüssel
    private boolean persistable;
    private String themeName;
    private final Runnable onChange = this::refresh;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable recTick = new Runnable() {
        @Override
        public void run() {
            if (RecordService.isRecording()) {
                adapter.notifyDataSetChanged();
                handler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        Design.apply(this);
        themeName = Design.get(this);
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        ListView list = findViewById(R.id.main_list);
        empty = findViewById(R.id.main_empty);
        crumbs = findViewById(R.id.main_crumbs);
        crumbsScroll = findViewById(R.id.main_crumbs_scroll);
        crumbsRow = findViewById(R.id.main_crumbs_row);
        selectBar = findViewById(R.id.main_select_bar);
        findViewById(R.id.sel_close).setOnClickListener(v -> clearSelection());
        findViewById(R.id.sel_all).setOnClickListener(v -> toggleAll());
        findViewById(R.id.sel_delete).setOnClickListener(v -> deleteSelected());
        findViewById(R.id.sel_move).setOnClickListener(v -> moveSelected());
        searchBar = findViewById(R.id.main_search_bar);
        searchInput = findViewById(R.id.main_search_input);
        adapter = new Adapter();
        list.setAdapter(adapter);
        findViewById(R.id.main_settings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.main_pick).setOnClickListener(v -> pick());
        findViewById(R.id.main_record).setOnClickListener(v ->
                startActivity(new Intent(this, RecordActivity.class).putExtra(EXTRA_FOLDER, folder)));
        findViewById(R.id.main_new_folder).setOnClickListener(v -> FolderPicker.create(this, folder, id -> refresh()));
        findViewById(R.id.main_folder_more).setOnClickListener(this::folderHeaderMenu);
        findViewById(R.id.main_search).setOnClickListener(v -> openSearch());
        findViewById(R.id.main_search_close).setOnClickListener(v -> closeSearch());
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                query = Search.normalize(s.toString());
                refresh();
            }
        });
        if (state != null) {
            String u = state.getString(STATE_PENDING);
            if (u != null) pendingUri = Uri.parse(u);
            String f = state.getString(STATE_FOLDER);
            if (f != null) folder = f;
        } else {
            handleShare(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!themeName.equals(Design.get(this))) { recreate(); return; }
        Store.addListener(onChange);
        refresh();
        startPendingRecording(this);
        handler.removeCallbacks(recTick);
        handler.postDelayed(recTick, 1000);
    }

    /** Fertige Aufnahme, die noch auf den Start der Transkription wartet (z. B. automatisch gestoppt). */
    static void startPendingRecording(Activity a) {
        String id = RecordService.Pending.take();
        if (id == null) return;
        Transcript t = Store.get(a, id);
        if (t == null || !Transcript.QUEUED.equals(t.status)) return;
        try {
            TranscribeService.start(a, id, null);
        } catch (RuntimeException e) {
            RecordService.Pending.set(id);
        }
    }

    @Override
    protected void onPause() {
        Store.removeListener(onChange);
        handler.removeCallbacks(recTick);
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (pendingUri != null) out.putString(STATE_PENDING, pendingUri.toString());
        out.putString(STATE_FOLDER, folder);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!selected.isEmpty()) { clearSelection(); return; }
        if (searchBar.getVisibility() == View.VISIBLE) { closeSearch(); return; }
        Folders f = Store.folders(this);
        if (f.exists(folder)) {
            folder = f.get(folder).parent;
            refresh();
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ Liste

    private void refresh() {
        Folders f = Store.folders(this);
        folder = f.clean(folder);
        rows.clear();
        boolean searching = searchBar.getVisibility() == View.VISIBLE && !query.isEmpty();
        if (searching) {
            for (Transcript t : Store.all(this)) {
                Search.Hit h = Search.find(t, query);
                if (h == null) continue;
                Row r = new Row();
                r.t = t;
                r.hit = h;
                rows.add(r);
            }
            empty.setText(getString(R.string.search_none));
        } else if (searchBar.getVisibility() != View.VISIBLE) {
            for (Folders.Folder x : f.children(folder)) {
                Row r = new Row();
                r.folder = x;
                rows.add(r);
            }
            for (Transcript t : Store.all(this)) {
                if (!folder.equals(f.clean(t.folder))) continue;
                Row r = new Row();
                r.t = t;
                rows.add(r);
            }
            empty.setText(Folders.ROOT.equals(folder) ? R.string.empty : R.string.folder_empty);
        }
        adapter.notifyDataSetChanged();
        empty.setVisibility(rows.isEmpty() && (searching || searchBar.getVisibility() != View.VISIBLE) ? View.VISIBLE : View.GONE);
        // Auswahl bereinigen: gelöschte oder nicht mehr sichtbare Einträge fallen heraus
        java.util.Set<String> visible = new java.util.HashSet<>();
        for (Row r : rows) if (r.t != null) visible.add(r.t.id);
        selected.retainAll(visible);
        boolean selecting = !selected.isEmpty();
        selectBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        if (selecting) {
            ((TextView) findViewById(R.id.sel_count)).setText(getString(R.string.sel_count, selected.size()));
            ((TextView) findViewById(R.id.sel_all)).setText(selected.size() == visible.size() ? R.string.sel_none : R.string.sel_all);
        }
        crumbsRow.setVisibility(searchBar.getVisibility() == View.VISIBLE || selecting ? View.GONE : View.VISIBLE);
        buildCrumbs(f);
    }

    private void buildCrumbs(Folders f) {
        crumbs.removeAllViews();
        addCrumb(getString(R.string.folder_all), Folders.ROOT, Folders.ROOT.equals(folder));
        for (Folders.Folder x : f.path(folder)) {
            TextView sep = new TextView(this);
            sep.setText("›");
            sep.setTextColor(Design.color(this, R.attr.pl_muted));
            sep.setTextSize(16);
            sep.setPadding(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
            crumbs.addView(sep);
            addCrumb(x.name, x.id, x.id.equals(folder));
        }
        crumbsScroll.post(() -> crumbsScroll.fullScroll(View.FOCUS_RIGHT));
        findViewById(R.id.main_folder_more).setVisibility(Folders.ROOT.equals(folder) ? View.GONE : View.VISIBLE);
    }

    private void addCrumb(String name, String id, boolean current) {
        TextView c = new TextView(this);
        c.setText(name);
        c.setTextSize(15);
        c.setTextColor(Design.color(this, current ? R.attr.pl_text : R.attr.pl_accent));
        if (current) c.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setMinHeight(Ui.dp(this, 44));
        int p = Ui.dp(this, 8);
        c.setPadding(p, 0, p, 0);
        c.setBackgroundResource(R.drawable.crumb_bg);
        c.setMaxWidth(Ui.dp(this, 200));
        c.setSingleLine(true);
        c.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (!current) {
            c.setClickable(true);
            c.setFocusable(true);
            c.setOnClickListener(v -> { folder = id; refresh(); });
        }
        crumbs.addView(c);
    }

    private void openSearch() {
        searchBar.setVisibility(View.VISIBLE);
        searchInput.requestFocus();
        InputMethodManager im = getSystemService(InputMethodManager.class);
        if (im != null) im.showSoftInput(searchInput, 0);
        refresh();
    }

    private void closeSearch() {
        searchBar.setVisibility(View.GONE);
        searchInput.setText("");
        InputMethodManager im = getSystemService(InputMethodManager.class);
        if (im != null) im.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        refresh();
    }

    // ------------------------------------------------------------------ Ordner-Menüs

    private void folderHeaderMenu(View anchor) {
        if (Folders.ROOT.equals(folder)) return;
        folderMenu(anchor, folder);
    }

    private void folderMenu(View anchor, String id) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, R.string.folder_summary);
        m.getMenu().add(0, 2, 0, R.string.folder_rename);
        m.getMenu().add(0, 3, 0, R.string.folder_move);
        m.getMenu().add(0, 4, 0, R.string.folder_delete);
        m.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1: folderSummary(id); return true;
                case 2: FolderPicker.rename(this, id); return true;
                case 3: FolderPicker.move(this, id); return true;
                case 4: FolderPicker.delete(this, id, null); return true;
                default: return false;
            }
        });
        m.show();
    }

    /** Ordner (mit Unterordnern) gemeinsam auswerten; Ergebnis wird ein neuer Eintrag im Ordner. */
    private void folderSummary(String id) {
        if (!GeminiKey.has(this)) {
            KeyDialog.show(this, null);
            return;
        }
        Folders f = Store.folders(this);
        String name = f.exists(id) ? f.pathText(id) : getString(R.string.folder_all);
        LinearLayout col = Ui.column(this);
        Ui.add(col, Ui.body(this, getString(R.string.folder_summary_text, name)));
        Ui.add(col, Ui.label(this, R.string.folder_summary_focus));
        EditText focus = Ui.input(this, "", 300, false);
        focus.setHint(R.string.folder_summary_focus_hint);
        Ui.add(col, focus);
        Ui.dialog(this).setTitle(R.string.folder_summary_title).setView(col)
                .setPositiveButton(R.string.folder_summary_go, (d, w) -> {
                    Transcript t = new Transcript();
                    t.id = Store.newId();
                    t.kind = Transcript.KIND_TEXT;
                    t.aggregate = true;
                    String folderName = f.exists(id) ? f.get(id).name : getString(R.string.folder_all);
                    t.title = Text.line(getString(R.string.folder_summary_item, folderName), Transcript.MAX_TITLE);
                    t.createdMs = System.currentTimeMillis();
                    t.folder = f.clean(id);
                    t.source = name;
                    t.status = Transcript.QUEUED;
                    Store.save(this, t);
                    askNotifications();
                    TranscribeService.folder(this, t.id, f.clean(id), focus.getText().toString());
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    // ------------------------------------------------------------------ Datei wählen / teilen

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/*", "image/*", "text/plain"});
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.start_file_unreadable, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        persistable = false;
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            persistable = true;
        } catch (RuntimeException ignored) {
            // nicht jede Quelle erlaubt dauerhaften Zugriff; für den Lauf reicht der vorübergehende
        }
        begin(uri, null);
    }

    private void handleShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String type = intent.getType();
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null && intent.getClipData() != null && intent.getClipData().getItemCount() > 0) {
            uri = intent.getClipData().getItemAt(0).getUri();
        }
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        intent.setAction(null); // nur einmal auswerten
        persistable = false;
        if (uri == null && text != null && "text/plain".equals(type)) {
            String s = text.toString();
            String trimmed = s.trim();
            if (trimmed.isEmpty()) { Toast.makeText(this, R.string.share_empty, Toast.LENGTH_LONG).show(); return; }
            if (trimmed.matches("(?s)https?://\\S+") || (trimmed.length() < 300 && trimmed.split("\\s+").length <= 3 && trimmed.contains("http"))) {
                Ui.dialog(this).setMessage(R.string.share_link_only).setPositiveButton(R.string.ok, null).show();
                return;
            }
            pendingText = s.length() > Gemini.MAX_TEXT_INPUT ? s.substring(0, Gemini.MAX_TEXT_INPUT) : s;
            pendingSubject = subject;
            begin(null, pendingText);
            return;
        }
        if (uri == null || !"content".equals(uri.getScheme())) {
            Toast.makeText(this, R.string.start_file_unreadable, Toast.LENGTH_LONG).show();
            return;
        }
        begin(uri, null);
    }

    private String pendingText, pendingSubject;

    /** Erst Hinweis, dann Schlüssel, dann Startdialog. */
    private void begin(Uri uri, String text) {
        pendingUri = uri;
        if (!Settings.hintAccepted(this)) {
            Ui.dialog(this).setTitle(R.string.hint_title).setMessage(R.string.hint_text)
                    .setPositiveButton(R.string.hint_ok, (d, w) -> {
                        Settings.setHintAccepted(this);
                        begin(uri, text);
                    })
                    .setNegativeButton(R.string.cancel, (d, w) -> { pendingUri = null; pendingText = null; })
                    .setCancelable(false)
                    .show();
            return;
        }
        if (!GeminiKey.has(this)) {
            Ui.dialog(this).setTitle(R.string.key_title).setMessage(R.string.start_need_key)
                    .setPositiveButton(R.string.start_set_key, (d, w) -> KeyDialog.show(this, () -> {
                        if (GeminiKey.has(this) && (pendingUri != null || pendingText != null)) begin(pendingUri, pendingText);
                    }))
                    .setNegativeButton(R.string.cancel, (d, w) -> { pendingUri = null; pendingText = null; })
                    .show();
            return;
        }
        if (text != null) {
            showShareStart(null, text);
            return;
        }
        String mime;
        try {
            mime = getContentResolver().getType(uri);
        } catch (RuntimeException e) {
            mime = null;
        }
        if (mime != null && (mime.startsWith("image/") || mime.equals("text/plain"))) showShareStart(uri, null);
        else showStart(uri);
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return Text.line(c.getString(0), 120);
        } catch (RuntimeException ignored) { }
        return "";
    }

    private void showStart(Uri uri) {
        String mime;
        try {
            mime = getContentResolver().getType(uri);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.start_file_unreadable, Toast.LENGTH_LONG).show();
            pendingUri = null;
            return;
        }
        if (mime != null && !mime.startsWith("audio/") && !mime.startsWith("video/") && !mime.equals("application/octet-stream")
                && !mime.equals("application/ogg")) {
            Toast.makeText(this, R.string.err_no_audio, Toast.LENGTH_LONG).show();
            pendingUri = null;
            return;
        }
        String sourceName = displayName(uri);
        LinearLayout col = Ui.column(this);
        OptionsForm form = new OptionsForm(this, col, Text.titleFromFileName(sourceName), folder, true);
        TextView info = Ui.body(this, getString(R.string.start_info));
        info.setTextColor(Design.color(this, R.attr.pl_muted));
        info.setTextSize(13);
        Ui.add(col, info);
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(col);
        final boolean keep = persistable;

        Ui.dialog(this).setTitle(R.string.start_title).setView(sc)
                .setPositiveButton(R.string.start_go, (d, w) -> {
                    Transcript t = form.build();
                    t.source = sourceName;
                    if (keep) t.sourceUri = Transcript.cleanUri(uri.toString());
                    t.status = Transcript.QUEUED;
                    Store.save(this, t);
                    askNotifications();
                    try {
                        TranscribeService.start(this, t.id, uri);
                    } catch (RuntimeException e) {
                        Transcript n = Store.copy(t);
                        n.status = Transcript.ERROR;
                        n.error = getString(R.string.err_unexpected, e.getClass().getSimpleName());
                        Store.save(this, n);
                    }
                    pendingUri = null;
                })
                .setNegativeButton(R.string.cancel, (d, w) -> pendingUri = null)
                .show();
    }

    /** Text oder Bild zusammenfassen (und optional vorlesen). */
    private void showShareStart(Uri uri, String text) {
        boolean image = false;
        String sourceName = "";
        if (uri != null) {
            String mime = null;
            try { mime = getContentResolver().getType(uri); } catch (RuntimeException ignored) { }
            image = mime != null && mime.startsWith("image/");
            sourceName = displayName(uri);
        }
        String date = Ui.date(System.currentTimeMillis());
        String defTitle;
        if (image) defTitle = getString(R.string.share_image_title, date);
        else if (pendingSubject != null && !Text.line(pendingSubject, 80).isEmpty()) defTitle = Text.line(pendingSubject, 80);
        else if (text != null) defTitle = firstLine(text, getString(R.string.share_text_title, date));
        else defTitle = Text.titleFromFileName(sourceName);
        LinearLayout col = Ui.column(this);
        OptionsForm form = new OptionsForm(this, col, defTitle, folder, false);
        TextView info = Ui.body(this, getString(image ? R.string.share_image_info : R.string.share_text_info));
        info.setTextColor(Design.color(this, R.attr.pl_muted));
        info.setTextSize(13);
        Ui.add(col, info);
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(col);
        final boolean isImage = image;
        final String name = sourceName;
        Ui.dialog(this).setTitle(R.string.share_title).setView(sc)
                .setPositiveButton(R.string.share_go, (d, w) -> {
                    Transcript t = form.build();
                    t.kind = isImage ? Transcript.KIND_IMAGE : Transcript.KIND_TEXT;
                    t.source = name;
                    try {
                        if (isImage) {
                            File out = Store.imageFile(this, t.id);
                            ImagePrep.toJpeg(this, uri, out);
                        } else {
                            String s = text != null ? text : ImagePrep.readText(this, uri, Gemini.MAX_TEXT_INPUT);
                            for (String p : Gemini.paragraphs(s)) t.segments.add(new Transcript.Segment(0, "", p));
                            if (t.segments.isEmpty()) {
                                Toast.makeText(this, R.string.share_empty, Toast.LENGTH_LONG).show();
                                return;
                            }
                        }
                    } catch (java.io.IOException | RuntimeException e) {
                        Toast.makeText(this, R.string.start_file_unreadable, Toast.LENGTH_LONG).show();
                        //noinspection ResultOfMethodCallIgnored
                        Store.imageFile(this, t.id).delete();
                        return;
                    }
                    t.status = Transcript.QUEUED;
                    Store.save(this, t);
                    TranscribeService.start(this, t.id, null);
                    startActivity(new Intent(this, TranscriptActivity.class).putExtra(TranscribeService.EXTRA_ID, t.id)
                            .putExtra(TranscriptActivity.EXTRA_READ_WHEN_DONE, form.readAfter()));
                    pendingUri = null;
                    pendingText = null;
                    pendingSubject = null;
                })
                .setNegativeButton(R.string.cancel, (d, w) -> { pendingUri = null; pendingText = null; pendingSubject = null; })
                .show();
    }

    private static String firstLine(String text, String fallback) {
        for (String l : text.split("\n")) {
            String s = Text.line(l, 200);
            if (s.length() >= 3) return s.length() > 60 ? s.substring(0, 60).trim() + "…" : s;
        }
        return fallback;
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT < 33 || Settings.askedNotifications(this)) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        Settings.setAskedNotifications(this);
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_NOTIF && (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, R.string.err_notif_denied, Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ Einträge

    private void openItem(Transcript t, Search.Hit hit) {
        if (Transcript.RECORDING.equals(t.status) && RecordService.isRecording()) {
            startActivity(new Intent(this, RecordActivity.class));
            return;
        }
        Intent i = new Intent(this, TranscriptActivity.class).putExtra(TranscribeService.EXTRA_ID, t.id);
        if (hit != null && !query.isEmpty() && !query.startsWith("#")) i.putExtra(TranscriptActivity.EXTRA_QUERY, query);
        startActivity(i);
    }

    private void itemMenu(View anchor, Transcript t) {
        PopupMenu m = new PopupMenu(this, anchor);
        if (t.isWorking()) m.getMenu().add(0, 1, 0, R.string.cancel);
        m.getMenu().add(0, 2, 0, R.string.m_rename);
        m.getMenu().add(0, 4, 0, R.string.folder_move);
        m.getMenu().add(0, 5, 0, R.string.m_tags);
        m.getMenu().add(0, 3, 0, R.string.m_delete);
        m.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1: TranscribeService.cancel(this, t.id); return true;
                case 2: rename(this, t.id); return true;
                case 3: confirmDelete(this, t.id, null); return true;
                case 4: moveTranscript(this, t.id); return true;
                case 5: editTags(this, t.id); return true;
                default: return false;
            }
        });
        m.show();
    }

    static void moveTranscript(Activity a, String id) {
        FolderPicker.show(a, a.getString(R.string.folder_move_to), null, target -> {
            Store.moveTranscript(a, id, target);
            Toast.makeText(a, a.getString(R.string.folder_moved, OptionsForm.folderLabel(a, Store.folders(a), target)),
                    Toast.LENGTH_SHORT).show();
        });
    }

    static void editTags(Activity a, String id) {
        Transcript t = Store.get(a, id);
        if (t == null) return;
        LinearLayout col = Ui.column(a);
        EditText e = Ui.input(a, String.join(", ", t.tags), 400, false);
        e.setHint(R.string.tags_hint);
        Ui.add(col, e);
        Ui.dialog(a).setTitle(R.string.tags_title).setView(col)
                .setPositiveButton(R.string.save, (d, w) -> {
                    Transcript cur = Store.get(a, id);
                    if (cur == null) return;
                    List<Object> in = new ArrayList<>();
                    for (String s : e.getText().toString().split(",")) in.add(s);
                    Transcript n = Store.copy(cur);
                    Transcript.setTags(n.tags, in);
                    Store.save(a, n);
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    static void rename(Activity a, String id) {
        Transcript t = Store.get(a, id);
        if (t == null) return;
        LinearLayout col = Ui.column(a);
        EditText e = Ui.input(a, t.title, Transcript.MAX_TITLE, false);
        Ui.add(col, e);
        Ui.dialog(a).setTitle(R.string.m_rename).setView(col)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String v = Text.line(e.getText().toString(), Transcript.MAX_TITLE);
                    Transcript cur = Store.get(a, id);
                    if (v.isEmpty() || cur == null) return;
                    // Titel darf sich auch während der Arbeit ändern: der Dienst liest ihn aus dem Speicher
                    Transcript n = Store.copy(cur);
                    n.title = v;
                    Store.save(a, n);
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    static void confirmDelete(Activity a, String id, Runnable after) {
        Transcript t = Store.get(a, id);
        if (t == null) return;
        Ui.dialog(a).setTitle(R.string.delete_title).setMessage(a.getString(R.string.delete_text, t.title))
                .setPositiveButton(R.string.delete, (d, w) -> {
                    if (t.isWorking()) TranscribeService.cancel(a, id);
                    if (!t.sourceUri.isEmpty()) {
                        try {
                            a.getContentResolver().releasePersistableUriPermission(Uri.parse(t.sourceUri),
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (RuntimeException ignored) { }
                    }
                    Store.delete(a, id);
                    if (after != null) after.run();
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int p) { return rows.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int p) { return rows.get(p).folder != null ? 1 : 0; }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            Row r = rows.get(pos);
            if (r.folder != null) return folderView(r.folder, v, parent);
            return transcriptView(r, v, parent);
        }

        private View folderView(Folders.Folder x, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_folder, parent, false);
            ((TextView) v.findViewById(R.id.fo_name)).setText(x.name);
            Folders f = Store.folders(MainActivity.this);
            Set<String> sub = f.subtree(x.id);
            int items = 0;
            for (Transcript t : Store.all(MainActivity.this)) if (sub.contains(t.folder)) items++;
            int subs = f.children(x.id).size();
            ((TextView) v.findViewById(R.id.fo_count)).setText(subs > 0
                    ? getString(R.string.folder_count_sub, items, subs) : getString(R.string.folder_count, items));
            View card = v.findViewById(R.id.fo_card);
            card.setOnClickListener(c -> { folder = x.id; refresh(); });
            card.setOnLongClickListener(c -> { folderMenu(c, x.id); return true; });
            v.findViewById(R.id.fo_more).setOnClickListener(c -> folderMenu(c, x.id));
            return v;
        }

        private View transcriptView(Row r, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_transcript, parent, false);
            Transcript t = r.t;
            ((TextView) v.findViewById(R.id.it_title)).setText(t.title);
            String meta = Ui.meta(MainActivity.this, t);
            if (r.hit != null && !Folders.ROOT.equals(t.folder)) {
                meta = OptionsForm.folderLabel(MainActivity.this, Store.folders(MainActivity.this), t.folder) + " · " + meta;
            }
            ((TextView) v.findViewById(R.id.it_meta)).setText(meta);
            TextView tags = v.findViewById(R.id.it_tags);
            StringBuilder tb = new StringBuilder();
            for (String tag : t.tags) tb.append(tb.length() == 0 ? "" : "  ").append('#').append(tag);
            tags.setText(tb);
            tags.setVisibility(t.tags.isEmpty() ? View.GONE : View.VISIBLE);
            TextView preview = v.findViewById(R.id.it_preview);
            String pv = "";
            if (r.hit != null && !r.hit.snippet.isEmpty()) pv = r.hit.snippet;
            else if (t.summary != null && !t.summary.overview.isEmpty()) pv = t.summary.overview;
            else if (!t.segments.isEmpty()) pv = t.segments.get(0).text;
            preview.setText(pv);
            preview.setVisibility(pv.isEmpty() || t.isWorking() ? View.GONE : View.VISIBLE);
            ProgressBar pb = v.findViewById(R.id.it_progress);
            int prog = Ui.progress(t);
            pb.setVisibility(t.isWorking() ? View.VISIBLE : View.GONE);
            pb.setIndeterminate(prog < 0);
            if (prog >= 0) pb.setProgress(prog);
            TextView st = v.findViewById(R.id.it_status);
            String status = Ui.status(MainActivity.this, t);
            st.setText(status == null ? "" : status);
            st.setVisibility(status == null ? View.GONE : View.VISIBLE);
            boolean failed = Transcript.ERROR.equals(t.status) || Transcript.CANCELED.equals(t.status);
            st.setTextColor(Design.color(MainActivity.this, Transcript.ERROR.equals(t.status) ? R.attr.pl_error : R.attr.pl_muted));
            Button retry = v.findViewById(R.id.it_retry);
            retry.setEnabled(selected.isEmpty());
            retry.setVisibility(failed ? View.VISIBLE : View.GONE);
            retry.setOnClickListener(x -> {
                askNotifications();
                if (t.aggregate) {
                    // Ordner-Auswertung erneut
                    TranscribeService.folder(MainActivity.this, t.id, t.folder, "");
                } else {
                    TranscribeService.start(MainActivity.this, t.id, null);
                }
            });
            View card = v.findViewById(R.id.it_card);
            boolean selecting = !selected.isEmpty(), isSel = selected.contains(t.id);
            card.setActivated(isSel);
            ImageButton action = v.findViewById(R.id.it_action);
            action.setImageResource(selecting ? (isSel ? R.drawable.ic_check_circle : R.drawable.ic_circle_empty) : R.drawable.ic_more);
            action.setContentDescription(getString(selecting ? R.string.sel_hint : R.string.more));
            card.setOnClickListener(x -> { if (selecting) toggle(t.id); else openItem(t, r.hit); });
            card.setOnLongClickListener(x -> { toggle(t.id); return true; });
            action.setOnClickListener(x -> { if (selecting) toggle(t.id); else itemMenu(x, t); });
            return v;
        }
    }

    // ------------------------------------------------------------------ Mehrfachauswahl

    private void toggle(String id) {
        if (!selected.remove(id)) selected.add(id);
        refresh();
    }

    private void clearSelection() {
        selected.clear();
        refresh();
    }

    /** Alle sichtbaren Transkripte auswählen bzw. – wenn schon alle markiert – keine. */
    private void toggleAll() {
        java.util.List<String> ids = new ArrayList<>();
        for (Row r : rows) if (r.t != null) ids.add(r.t.id);
        if (selected.size() == ids.size()) selected.clear();
        else selected.addAll(ids);
        refresh();
    }

    private void deleteSelected() {
        if (selected.isEmpty()) return;
        List<String> ids = new ArrayList<>(selected);
        Ui.dialog(this).setTitle(getString(R.string.sel_delete_title, ids.size())).setMessage(R.string.sel_delete_text)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    int n = 0;
                    for (String id : ids) {
                        Transcript t = Store.get(this, id);
                        if (t == null) continue;
                        if (t.isWorking()) {
                            if (Transcript.RECORDING.equals(t.status)) RecordService.command(this, RecordService.ACTION_DISCARD);
                            else TranscribeService.cancel(this, id);
                        }
                        if (!t.sourceUri.isEmpty()) {
                            try {
                                getContentResolver().releasePersistableUriPermission(Uri.parse(t.sourceUri),
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            } catch (RuntimeException ignored) { }
                        }
                        Store.delete(this, id);
                        n++;
                    }
                    selected.clear();
                    Toast.makeText(this, getString(R.string.sel_deleted, n), Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void moveSelected() {
        if (selected.isEmpty()) return;
        List<String> ids = new ArrayList<>(selected);
        FolderPicker.show(this, getString(R.string.folder_move_to), null, target -> {
            for (String id : ids) Store.moveTranscript(this, id, target);
            selected.clear();
            Toast.makeText(this, getString(R.string.sel_moved, ids.size(),
                    OptionsForm.folderLabel(this, Store.folders(this), target)), Toast.LENGTH_SHORT).show();
            refresh();
        });
    }

    /** Für den Hinweis in den Einstellungen. */
    static AlertDialog showHint(Activity a) {
        return Ui.dialog(a).setTitle(R.string.hint_title).setMessage(R.string.hint_text)
                .setPositiveButton(R.string.ok, null).show();
    }
}
