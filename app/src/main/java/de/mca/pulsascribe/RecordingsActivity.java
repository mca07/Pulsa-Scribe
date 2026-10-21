package de.mca.pulsascribe;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.mca.pulsascribe.core.Text;
import de.mca.pulsascribe.core.Times;
import de.mca.pulsascribe.core.Transcript;

/**
 * Aufnahmen aus dem freigegebenen Ordner (z. B. CubeCallRecorder) vorhören und auswählen.
 * Ergebnis: eine oder mehrere Adressen an MainActivity (wie bei der Android-Dateiauswahl).
 */
public final class RecordingsActivity extends Activity {
    static final String RESULT_OTHER = "other";
    private static final int REQ_TREE = 31;

    private final List<CallImport.Rec> all = new ArrayList<>(), shown = new ArrayList<>();
    private final Set<Uri> selected = new LinkedHashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Adapter adapter;
    private TextView empty, go, nowTitle, nowTime;
    private View playerBar;
    private ImageButton nowPlay;
    private SeekBar seek;
    private MediaPlayer player;
    private Uri playing;
    private boolean prepared;
    private String filter = "";
    private String themeName;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (player != null && prepared) {
                try {
                    int pos = player.getCurrentPosition(), dur = player.getDuration();
                    seek.setMax(Math.max(1, dur));
                    seek.setProgress(pos);
                    nowTime.setText(Times.clock(pos) + " / " + Times.clock(dur));
                } catch (IllegalStateException ignored) { }
            }
            handler.postDelayed(this, 300);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        Design.apply(this);
        themeName = Design.get(this);
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(Design.color(this, R.attr.pl_bg));

        // Kopf
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundResource(R.drawable.header_bg);
        head.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 9));
        head.addView(headerButton(R.drawable.ic_back, R.string.back, v -> finish()));
        TextView title = new TextView(this);
        title.setText(R.string.recs_title);
        title.setTextSize(20);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Design.color(this, R.attr.pl_header_text));
        title.setPadding(Ui.dp(this, 6), 0, 0, 0);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(headerButton(R.drawable.ic_more, R.string.more, this::menu));
        root.addView(head);

        EditText search = Ui.input(this, "", 60, false);
        search.setHint(R.string.recs_search);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable e) { filter = e.toString().trim().toLowerCase(Locale.ROOT); applyFilter(); }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), 0);
        root.addView(search, slp);

        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 8));
        list.setClipToPadding(false);
        adapter = new Adapter();
        list.setAdapter(adapter);
        box.addView(list);
        empty = Ui.body(this, "");
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(Ui.dp(this, 32), 0, Ui.dp(this, 32), 0);
        empty.setTextColor(Design.color(this, R.attr.pl_muted));
        box.addView(empty, new android.widget.FrameLayout.LayoutParams(-1, -1));
        root.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Abspielleiste
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(Design.color(this, R.attr.pl_surface));
        bar.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 4));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        nowPlay = new ImageButton(this);
        nowPlay.setBackgroundResource(R.drawable.btn_secondary);
        nowPlay.setImageResource(R.drawable.ic_pause);
        nowPlay.setColorFilter(Design.color(this, R.attr.pl_accent));
        nowPlay.setContentDescription(getString(R.string.pause));
        nowPlay.setOnClickListener(v -> togglePause());
        row.addView(nowPlay, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 10), 0, 0, 0);
        nowTitle = new TextView(this);
        nowTitle.setTextColor(Design.color(this, R.attr.pl_text));
        nowTitle.setSingleLine(true);
        nowTitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        nowTime = new TextView(this);
        nowTime.setTextSize(12);
        nowTime.setTextColor(Design.color(this, R.attr.pl_muted));
        col.addView(nowTitle);
        col.addView(nowTime);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(row);
        seek = new SeekBar(this);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user && player != null && prepared) try { player.seekTo(p); } catch (IllegalStateException ignored) { }
            }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        bar.addView(seek);
        playerBar = bar;
        playerBar.setVisibility(View.GONE);
        root.addView(bar);

        // Übernehmen
        LinearLayout bottom = new LinearLayout(this);
        bottom.setBackgroundColor(Design.color(this, R.attr.pl_surface));
        bottom.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 12));
        go = new TextView(this);
        go.setGravity(Gravity.CENTER);
        go.setTextSize(16);
        go.setMinHeight(Ui.dp(this, 52));
        go.setBackgroundResource(R.drawable.btn_primary);
        go.setTextColor(Design.color(this, R.attr.pl_on_accent));
        go.setOnClickListener(v -> deliver());
        bottom.addView(go, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(bottom);
        setContentView(root);
        updateGo();
        load();
    }

    private ImageButton headerButton(int icon, int desc, View.OnClickListener l) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setBackgroundResource(android.R.color.transparent);
        b.setColorFilter(Design.color(this, R.attr.pl_header_text));
        b.setContentDescription(getString(desc));
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        return b;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!themeName.equals(Design.get(this))) { recreate(); return; }
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        if (player != null && prepared && player.isPlaying()) {
            player.pause();
            nowPlay.setImageResource(R.drawable.ic_play);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopPlayer();
        super.onDestroy();
    }

    private void load() {
        if (!CallImport.enabled(this)) {
            empty.setText(R.string.recs_no_folder);
            empty.setVisibility(View.VISIBLE);
            empty.setOnClickListener(v -> pickTree());
            return;
        }
        empty.setText(R.string.recs_loading);
        empty.setVisibility(View.VISIBLE);
        Executors.newSingleThreadExecutor().execute(() -> {
            List<CallImport.Rec> r = CallImport.listAll(getApplicationContext());
            handler.post(() -> {
                if (isFinishing()) return;
                all.clear();
                all.addAll(r);
                applyFilter();
            });
        });
    }

    private void applyFilter() {
        shown.clear();
        for (CallImport.Rec r : all) {
            if (filter.isEmpty() || r.name.toLowerCase(Locale.ROOT).contains(filter) || label(r).toLowerCase(Locale.ROOT).contains(filter)) shown.add(r);
        }
        adapter.notifyDataSetChanged();
        empty.setOnClickListener(null);
        empty.setText(all.isEmpty() ? R.string.recs_empty : R.string.search_none);
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void menu(View anchor) {
        android.widget.PopupMenu m = new android.widget.PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, R.string.recs_other);
        m.getMenu().add(0, 2, 0, R.string.recs_change_folder);
        m.getMenu().add(0, 3, 0, R.string.recs_reload);
        m.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1: setResult(RESULT_OK, new Intent().putExtra(RESULT_OTHER, true)); finish(); return true;
                case 2: pickTree(); return true;
                case 3: load(); return true;
                default: return false;
            }
        });
        m.show();
    }

    private void pickTree() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_TREE);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_TREE && res == RESULT_OK && data != null && data.getData() != null) {
            if (CallImport.setTree(this, data.getData())) {
                Toast.makeText(this, R.string.call_enabled, Toast.LENGTH_LONG).show();
                load();
            } else {
                Toast.makeText(this, R.string.call_failed, Toast.LENGTH_LONG).show();
            }
        }
    }

    // ------------------------------------------------------------------ Vorhören

    private void play(CallImport.Rec r) {
        if (r.uri.equals(playing) && player != null) { togglePause(); return; }
        stopPlayer();
        playing = r.uri;
        prepared = false;
        playerBar.setVisibility(View.VISIBLE);
        nowTitle.setText(label(r));
        nowTime.setText(R.string.recs_loading);
        MediaPlayer p = new MediaPlayer();
        player = p;
        try {
            p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            p.setDataSource(this, r.uri);
            p.setOnPreparedListener(mp -> {
                if (player != mp) return;
                prepared = true;
                mp.start();
                nowPlay.setImageResource(R.drawable.ic_pause);
                adapter.notifyDataSetChanged();
            });
            p.setOnCompletionListener(mp -> {
                nowPlay.setImageResource(R.drawable.ic_play);
                adapter.notifyDataSetChanged();
            });
            p.setOnErrorListener((mp, w, e) -> {
                Toast.makeText(this, R.string.recs_cannot_play, Toast.LENGTH_SHORT).show();
                stopPlayer();
                return true;
            });
            p.prepareAsync();
        } catch (Exception e) {
            Toast.makeText(this, R.string.recs_cannot_play, Toast.LENGTH_SHORT).show();
            stopPlayer();
        }
        adapter.notifyDataSetChanged();
    }

    private void togglePause() {
        if (player == null || !prepared) return;
        if (player.isPlaying()) {
            player.pause();
            nowPlay.setImageResource(R.drawable.ic_play);
        } else {
            player.start();
            nowPlay.setImageResource(R.drawable.ic_pause);
        }
        adapter.notifyDataSetChanged();
    }

    private void stopPlayer() {
        if (player != null) {
            try { player.release(); } catch (RuntimeException ignored) { }
            player = null;
        }
        prepared = false;
        playing = null;
        if (playerBar != null) playerBar.setVisibility(View.GONE);
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private boolean isPlaying(Uri u) {
        try {
            return u.equals(playing) && player != null && prepared && player.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ Anzeige

    private static final Pattern STAMP = Pattern.compile("(20\\d{2})(\\d{2})(\\d{2})[-_](\\d{2})(\\d{2})(\\d{2})");

    /** „phone_20260806-105739__4917…“ → „06.08.2026, 10:57 · 4917… · Telefon“ */
    String label(CallImport.Rec r) {
        String n = r.name;
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        String kind = "";
        String l = n.toLowerCase(Locale.ROOT);
        if (l.startsWith("whatsapp")) kind = "WhatsApp";
        else if (l.startsWith("phone")) kind = getString(R.string.recs_phone);
        Matcher m = STAMP.matcher(n);
        String when, who = "";
        if (m.find()) {
            when = m.group(3) + "." + m.group(2) + "." + m.group(1) + ", " + m.group(4) + ":" + m.group(5);
            who = n.substring(m.end()).replaceAll("^[_\\-\\s]+", "").replace('_', ' ').trim();
        } else {
            when = new SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.GERMANY).format(new Date(r.modified));
            who = n.replace('_', ' ');
        }
        StringBuilder b = new StringBuilder(when);
        if (!who.isEmpty()) b.append(" · ").append(Text.line(who, 60));
        if (!kind.isEmpty()) b.append(" · ").append(kind);
        return b.toString();
    }

    private void updateGo() {
        int n = selected.size();
        go.setText(n == 0 ? getString(R.string.recs_pick_hint) : getResources().getQuantityString(R.plurals.recs_go, n, n));
        go.setEnabled(n > 0);
        go.setAlpha(n > 0 ? 1f : 0.5f);
    }

    private void deliver() {
        if (selected.isEmpty()) return;
        stopPlayer();
        List<Uri> l = new ArrayList<>(selected);
        Intent data = new Intent();
        ClipData cd = ClipData.newRawUri("audio", l.get(0));
        for (int i = 1; i < l.size(); i++) cd.addItem(new ClipData.Item(l.get(i)));
        data.setClipData(cd);
        if (l.size() == 1) data.setData(l.get(0));
        setResult(RESULT_OK, data);
        finish();
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int p) { return shown.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            Holder h;
            if (v == null) {
                LinearLayout row = new LinearLayout(RecordingsActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackgroundResource(R.drawable.card_select);
                row.setPadding(Ui.dp(RecordingsActivity.this, 4), Ui.dp(RecordingsActivity.this, 6), Ui.dp(RecordingsActivity.this, 10), Ui.dp(RecordingsActivity.this, 6));
                h = new Holder();
                h.check = new CheckBox(RecordingsActivity.this);
                row.addView(h.check, new LinearLayout.LayoutParams(Ui.dp(RecordingsActivity.this, 48), Ui.dp(RecordingsActivity.this, 48)));
                LinearLayout col = new LinearLayout(RecordingsActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                h.title = new TextView(RecordingsActivity.this);
                h.title.setTextSize(15);
                h.title.setTextColor(Design.color(RecordingsActivity.this, R.attr.pl_text));
                h.sub = new TextView(RecordingsActivity.this);
                h.sub.setTextSize(12);
                h.sub.setTextColor(Design.color(RecordingsActivity.this, R.attr.pl_muted));
                h.sub.setSingleLine(true);
                h.sub.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                col.addView(h.title);
                col.addView(h.sub);
                row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                h.play = new ImageButton(RecordingsActivity.this);
                h.play.setBackgroundResource(R.drawable.btn_secondary);
                h.play.setColorFilter(Design.color(RecordingsActivity.this, R.attr.pl_accent));
                row.addView(h.play, new LinearLayout.LayoutParams(Ui.dp(RecordingsActivity.this, 48), Ui.dp(RecordingsActivity.this, 48)));
                LinearLayout wrap = new LinearLayout(RecordingsActivity.this);
                wrap.setPadding(0, Ui.dp(RecordingsActivity.this, 3), 0, Ui.dp(RecordingsActivity.this, 3));
                wrap.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                h.row = row;
                v = wrap;
                v.setTag(h);
            } else {
                h = (Holder) v.getTag();
            }
            CallImport.Rec r = shown.get(pos);
            boolean sel = selected.contains(r.uri);
            h.title.setText(label(r));
            h.sub.setText(Formatter.formatShortFileSize(RecordingsActivity.this, r.size) + " · " + r.name);
            h.check.setOnCheckedChangeListener(null);
            h.check.setChecked(sel);
            h.check.setContentDescription(getString(R.string.recs_select, label(r)));
            h.check.setOnCheckedChangeListener((b, on) -> toggle(r, on));
            h.row.setActivated(sel);
            h.row.setOnClickListener(x -> toggle(r, !selected.contains(r.uri)));
            boolean pl = isPlaying(r.uri);
            h.play.setImageResource(pl ? R.drawable.ic_pause : R.drawable.ic_play);
            h.play.setContentDescription(getString(pl ? R.string.pause : R.string.recs_listen));
            h.play.setOnClickListener(x -> play(r));
            return v;
        }
    }

    private void toggle(CallImport.Rec r, boolean on) {
        if (on) {
            if (selected.size() >= Transcript.MAX_PARTS) {
                Toast.makeText(this, getString(R.string.recs_max, Transcript.MAX_PARTS), Toast.LENGTH_SHORT).show();
            } else {
                selected.add(r.uri);
            }
        } else {
            selected.remove(r.uri);
        }
        adapter.notifyDataSetChanged();
        updateGo();
    }

    private static final class Holder {
        View row;
        CheckBox check;
        TextView title, sub;
        ImageButton play;
    }
}
