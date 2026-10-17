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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import de.mca.pulsascribe.core.Languages;
import de.mca.pulsascribe.core.Text;
import de.mca.pulsascribe.core.Transcript;

/** Liste der Transkripte, Datei wählen, Teilen-Ziel. */
public final class MainActivity extends Activity {
    private static final int REQ_PICK = 1, REQ_NOTIF = 2;
    private static final String STATE_PENDING = "pending_uri";

    private final List<Transcript> items = new ArrayList<>();
    private Adapter adapter;
    private TextView empty;
    private Uri pendingUri; // Datei wartet auf Hinweis/Schlüssel
    private boolean persistable;
    private String themeName;
    private final Runnable onChange = this::refresh;

    @Override
    protected void onCreate(Bundle state) {
        Design.apply(this);
        themeName = Design.get(this);
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        ListView list = findViewById(R.id.main_list);
        empty = findViewById(R.id.main_empty);
        adapter = new Adapter();
        list.setAdapter(adapter);
        findViewById(R.id.main_settings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.main_pick).setOnClickListener(v -> pick());
        if (state != null) {
            String u = state.getString(STATE_PENDING);
            if (u != null) pendingUri = Uri.parse(u);
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
    }

    @Override
    protected void onPause() {
        Store.removeListener(onChange);
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (pendingUri != null) out.putString(STATE_PENDING, pendingUri.toString());
    }

    private void refresh() {
        items.clear();
        items.addAll(Store.all(this));
        adapter.notifyDataSetChanged();
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ Datei wählen / teilen

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/*"});
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
        begin(uri);
    }

    private void handleShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null && intent.getClipData() != null && intent.getClipData().getItemCount() > 0) {
            uri = intent.getClipData().getItemAt(0).getUri();
        }
        intent.setAction(null); // nur einmal auswerten
        persistable = false;
        if (uri == null || !"content".equals(uri.getScheme())) {
            Toast.makeText(this, R.string.start_file_unreadable, Toast.LENGTH_LONG).show();
            return;
        }
        begin(uri);
    }

    /** Erst Hinweis, dann Schlüssel, dann Startdialog. */
    private void begin(Uri uri) {
        pendingUri = uri;
        if (!Settings.hintAccepted(this)) {
            Ui.dialog(this).setTitle(R.string.hint_title).setMessage(R.string.hint_text)
                    .setPositiveButton(R.string.hint_ok, (d, w) -> {
                        Settings.setHintAccepted(this);
                        begin(uri);
                    })
                    .setNegativeButton(R.string.cancel, (d, w) -> pendingUri = null)
                    .setCancelable(false)
                    .show();
            return;
        }
        if (!GeminiKey.has(this)) {
            Ui.dialog(this).setTitle(R.string.key_title).setMessage(R.string.start_need_key)
                    .setPositiveButton(R.string.start_set_key, (d, w) -> KeyDialog.show(this, () -> {
                        if (GeminiKey.has(this) && pendingUri != null) begin(pendingUri);
                    }))
                    .setNegativeButton(R.string.cancel, (d, w) -> pendingUri = null)
                    .show();
            return;
        }
        showStart(uri);
    }

    private void showStart(Uri uri) {
        String name = "", mime = null;
        try {
            mime = getContentResolver().getType(uri);
            try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst() && !c.isNull(0)) name = c.getString(0);
            }
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
        String sourceName = Text.line(name, 120);
        LinearLayout col = Ui.column(this);
        Ui.add(col, Ui.label(this, R.string.start_name));
        EditText title = Ui.input(this, Text.titleFromFileName(sourceName), Transcript.MAX_TITLE, false);
        Ui.add(col, title);
        Ui.add(col, Ui.label(this, R.string.start_lang));
        final String[] lang = {Settings.language(this)};
        TextView langRow = Ui.choiceRow(this, Languages.label(lang[0]));
        langRow.setOnClickListener(v -> Ui.dialog(this).setTitle(R.string.start_lang)
                .setSingleChoiceItems(Languages.LABELS, Languages.index(lang[0]), (d, w) -> {
                    lang[0] = Languages.CODES[w];
                    langRow.setText(Languages.LABELS[w]);
                    d.dismiss();
                }).show());
        Ui.add(col, langRow);
        Switch diarize = Ui.switchRow(this, R.string.start_diarize, Settings.diarize(this));
        Switch summary = Ui.switchRow(this, R.string.start_summary, Settings.summarize(this));
        Switch clean = Ui.switchRow(this, R.string.start_clean, Settings.cleanFillers(this));
        View gap = new View(this);
        gap.setMinimumHeight(Ui.dp(this, 6));
        Ui.add(col, gap);
        Ui.add(col, diarize);
        Ui.add(col, summary);
        Ui.add(col, clean);
        Ui.add(col, Ui.label(this, R.string.start_terms));
        EditText terms = Ui.input(this, "", Transcript.MAX_TERMS, false);
        terms.setHint(R.string.start_terms_hint);
        Ui.add(col, terms);
        TextView info = Ui.body(this, getString(R.string.start_info));
        info.setTextColor(Design.color(this, R.attr.pl_muted));
        info.setTextSize(13);
        Ui.add(col, info);
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(col);

        Ui.dialog(this).setTitle(R.string.start_title).setView(sc)
                .setPositiveButton(R.string.start_go, (d, w) -> {
                    Transcript t = new Transcript();
                    t.id = Store.newId();
                    String tt = Text.line(title.getText().toString(), Transcript.MAX_TITLE);
                    t.title = tt.isEmpty() ? Text.titleFromFileName(sourceName) : tt;
                    t.createdMs = System.currentTimeMillis();
                    t.source = sourceName;
                    if (persistable) t.sourceUri = Transcript.cleanUri(uri.toString());
                    t.language = Languages.clean(lang[0]);
                    t.diarize = diarize.isChecked();
                    t.summarize = summary.isChecked();
                    t.cleanFillers = clean.isChecked();
                    t.terms = Text.line(terms.getText().toString(), Transcript.MAX_TERMS);
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

    private void openItem(Transcript t) {
        startActivity(new Intent(this, TranscriptActivity.class).putExtra(TranscribeService.EXTRA_ID, t.id));
    }

    private void itemMenu(View anchor, Transcript t) {
        PopupMenu m = new PopupMenu(this, anchor);
        if (t.isWorking()) m.getMenu().add(0, 1, 0, R.string.cancel);
        m.getMenu().add(0, 2, 0, R.string.m_rename);
        m.getMenu().add(0, 3, 0, R.string.m_delete);
        m.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1: TranscribeService.cancel(this, t.id); return true;
                case 2: rename(this, t.id); return true;
                case 3: confirmDelete(this, t.id, null); return true;
                default: return false;
            }
        });
        m.show();
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
                    Store.delete(a, id);
                    if (after != null) after.run();
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int p) { return items.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_transcript, parent, false);
            Transcript t = items.get(pos);
            ((TextView) v.findViewById(R.id.it_title)).setText(t.title);
            ((TextView) v.findViewById(R.id.it_meta)).setText(Ui.meta(MainActivity.this, t));
            TextView preview = v.findViewById(R.id.it_preview);
            String pv = "";
            if (t.summary != null && !t.summary.overview.isEmpty()) pv = t.summary.overview;
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
            retry.setVisibility(failed ? View.VISIBLE : View.GONE);
            retry.setOnClickListener(x -> {
                askNotifications();
                TranscribeService.start(MainActivity.this, t.id, null);
            });
            v.findViewById(R.id.it_card).setOnClickListener(x -> openItem(t));
            v.findViewById(R.id.it_card).setOnLongClickListener(x -> { itemMenu(x, t); return true; });
            View action = v.findViewById(R.id.it_action);
            action.setOnClickListener(x -> itemMenu(x, t));
            return v;
        }
    }

    /** Für den Hinweis in den Einstellungen. */
    static AlertDialog showHint(Activity a) {
        return Ui.dialog(a).setTitle(R.string.hint_title).setMessage(R.string.hint_text)
                .setPositiveButton(R.string.ok, null).show();
    }
}
