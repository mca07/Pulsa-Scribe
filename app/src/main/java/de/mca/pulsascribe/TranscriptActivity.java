package de.mca.pulsascribe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.mca.pulsascribe.core.Export;
import de.mca.pulsascribe.core.Text;
import de.mca.pulsascribe.core.Times;
import de.mca.pulsascribe.core.Transcript;

/** Ein Transkript: lesen, anhören (Tippen springt zur Stelle), bearbeiten, zusammenfassen, exportieren. */
public final class TranscriptActivity extends Activity {
    private static final int REQ_SAVE = 5;

    private String id;
    private Transcript t;
    private final List<Integer> rows = new ArrayList<>(); // Indizes in t.segments (gefiltert)
    private List<String> speakerOrder = new ArrayList<>();
    private boolean seekMaxSet;
    private SegAdapter adapter;
    private ListView list;
    private ScrollView summaryScroll;
    private LinearLayout summaryBox;
    private TextView title, meta, note, tabText, tabSummary, time, searchCount;
    private View retry, searchBar;
    private EditText searchInput;
    private ImageButton play;
    private SeekBar seek;
    private boolean showSummary;
    private String query = "";

    private MediaPlayer player;
    private boolean playerReady;
    private int currentRow = -1;
    private long lastUserScroll;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::tick;
    private final Runnable onChange = this::reload;
    private String themeName;

    // Export, der auf die Speicherortwahl wartet
    private byte[] pendingExport;

    @Override
    protected void onCreate(Bundle state) {
        Design.apply(this);
        themeName = Design.get(this);
        super.onCreate(state);
        setContentView(R.layout.activity_transcript);
        id = getIntent().getStringExtra(TranscribeService.EXTRA_ID);
        if (!Text.isId(id) || Store.get(this, id) == null) {
            Toast.makeText(this, R.string.not_found, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        title = findViewById(R.id.tr_title);
        meta = findViewById(R.id.tr_meta);
        note = findViewById(R.id.tr_note);
        retry = findViewById(R.id.tr_retry);
        tabText = findViewById(R.id.tr_tab_text);
        tabSummary = findViewById(R.id.tr_tab_summary);
        list = findViewById(R.id.tr_list);
        summaryScroll = findViewById(R.id.tr_summary_scroll);
        summaryBox = findViewById(R.id.tr_summary);
        play = findViewById(R.id.tr_play);
        seek = findViewById(R.id.tr_seek);
        time = findViewById(R.id.tr_time);
        searchBar = findViewById(R.id.tr_search_bar);
        searchInput = findViewById(R.id.tr_search_input);
        searchCount = findViewById(R.id.tr_search_count);

        adapter = new SegAdapter();
        list.setAdapter(adapter);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView v, int s) {
                if (s != SCROLL_STATE_IDLE) lastUserScroll = SystemClock.elapsedRealtime();
            }

            @Override
            public void onScroll(AbsListView v, int first, int visible, int total) { }
        });

        findViewById(R.id.tr_back).setOnClickListener(v -> finish());
        title.setOnClickListener(v -> MainActivity.rename(this, id));
        findViewById(R.id.tr_more).setOnClickListener(this::menu);
        findViewById(R.id.tr_share).setOnClickListener(v -> exportDialog());
        findViewById(R.id.tr_search).setOnClickListener(v -> openSearch());
        findViewById(R.id.tr_search_close).setOnClickListener(v -> closeSearch());
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                query = Text.line(s.toString(), 60).toLowerCase(Locale.GERMANY);
                filter();
            }
        });
        tabText.setOnClickListener(v -> setTab(false));
        tabSummary.setOnClickListener(v -> setTab(true));
        retry.setOnClickListener(v -> {
            if (t.isWorking()) TranscribeService.cancel(this, id);
            else TranscribeService.start(this, id, null);
        });
        play.setOnClickListener(v -> togglePlay());
        findViewById(R.id.tr_back10).setOnClickListener(v -> {
            if (ensurePlayer()) player.seekTo(Math.max(0, player.getCurrentPosition() - 10_000));
            tick();
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && ensurePlayer()) {
                    player.seekTo(p);
                    updateTime(p);
                }
            }

            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        if (state != null) showSummary = state.getBoolean("summary", false);
        reload();
        setTab(showSummary);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!themeName.equals(Design.get(this))) { recreate(); return; }
        Store.addListener(onChange);
        reload();
    }

    @Override
    protected void onPause() {
        Store.removeListener(onChange);
        if (player != null && player.isPlaying()) {
            player.pause();
            play.setImageResource(R.drawable.ic_play);
            play.setContentDescription(getString(R.string.play));
        }
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("summary", showSummary);
    }

    // ------------------------------------------------------------------ Anzeige

    private void reload() {
        Transcript cur = Store.get(this, id);
        if (cur == null) { finish(); return; }
        t = cur;
        speakerOrder = t.speakers();
        title.setText(t.title);
        meta.setText(Ui.meta(this, t));
        String status = Ui.status(this, t);
        if (status == null && t.incomplete) status = getString(R.string.st_incomplete);
        note.setText(status == null ? "" : status);
        note.setVisibility(status == null ? View.GONE : View.VISIBLE);
        note.setTextColor(Design.color(this, Transcript.ERROR.equals(t.status) ? R.attr.pl_error : R.attr.pl_text));
        boolean failed = Transcript.ERROR.equals(t.status) || Transcript.CANCELED.equals(t.status);
        retry.setVisibility(failed || t.isWorking() ? View.VISIBLE : View.GONE);
        ((TextView) retry).setText(t.isWorking() ? R.string.cancel : R.string.retry);
        boolean hasAudio = t.audioReady && Store.audioFile(this, id).isFile();
        findViewById(R.id.tr_player).setVisibility(hasAudio && !Transcript.PREPARE.equals(t.status) ? View.VISIBLE : View.GONE);
        if (!hasAudio && player != null) {
            player.release();
            player = null;
            playerReady = false;
        }
        filter();
        if (showSummary) buildSummary();
        if (!seekMaxSet && t.durationMs > 0) {
            seek.setMax((int) Math.min(Integer.MAX_VALUE, t.durationMs));
            seekMaxSet = true;
        }
        updateTime(player != null && playerReady ? player.getCurrentPosition() : 0);
    }

    private void filter() {
        rows.clear();
        int hits = 0;
        for (int i = 0; i < t.segments.size(); i++) {
            Transcript.Segment s = t.segments.get(i);
            if (query.isEmpty() || s.text.toLowerCase(Locale.GERMANY).contains(query)
                    || t.speakerName(s.speaker).toLowerCase(Locale.GERMANY).contains(query)) {
                rows.add(i);
                if (!query.isEmpty()) hits++;
            }
        }
        searchCount.setText(query.isEmpty() ? "" : getString(R.string.search_count, hits));
        adapter.notifyDataSetChanged();
    }

    private void setTab(boolean summary) {
        showSummary = summary;
        tabText.setSelected(!summary);
        tabSummary.setSelected(summary);
        tabText.setTextColor(Design.color(this, !summary ? R.attr.pl_on_accent : R.attr.pl_text));
        tabSummary.setTextColor(Design.color(this, summary ? R.attr.pl_on_accent : R.attr.pl_text));
        list.setVisibility(summary ? View.GONE : View.VISIBLE);
        summaryScroll.setVisibility(summary ? View.VISIBLE : View.GONE);
        if (summary) {
            closeSearch();
            buildSummary();
        }
    }

    private void buildSummary() {
        summaryBox.removeAllViews();
        Transcript.Summary s = t.summary;
        if (s == null || s.isEmpty()) {
            TextView none = Ui.body(this, getString(R.string.summary_none));
            none.setTextColor(Design.color(this, R.attr.pl_muted));
            none.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 10));
            summaryBox.addView(none);
            if (!t.isWorking() && !t.segments.isEmpty()) {
                TextView b = new TextView(this);
                b.setText(R.string.summary_create);
                b.setTextSize(16);
                b.setTextColor(Design.color(this, R.attr.pl_on_accent));
                b.setBackgroundResource(R.drawable.btn_primary);
                b.setGravity(Gravity.CENTER);
                b.setMinHeight(Ui.dp(this, 48));
                int p = Ui.dp(this, 18);
                b.setPadding(p, 0, p, 0);
                b.setClickable(true);
                b.setFocusable(true);
                b.setOnClickListener(v -> TranscribeService.summary(this, id));
                summaryBox.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            return;
        }
        section(R.string.sum_overview);
        if (!s.overview.isEmpty()) summaryBox.addView(Ui.body(this, s.overview));
        bullets(R.string.sum_points, s.points);
        if (!s.tasks.isEmpty()) {
            List<String> l = new ArrayList<>();
            for (Transcript.Task k : s.tasks) l.add(k.who.isEmpty() ? k.what : k.what + " — " + k.who);
            bullets(R.string.sum_tasks, l);
        }
        bullets(R.string.sum_decisions, s.decisions);
        bullets(R.string.sum_questions, s.questions);
        TextView hint = Ui.body(this, getString(R.string.sum_hint));
        hint.setTextSize(12);
        hint.setTextColor(Design.color(this, R.attr.pl_muted));
        hint.setPadding(0, Ui.dp(this, 18), 0, 0);
        summaryBox.addView(hint);
    }

    private void section(int res) {
        TextView h = new TextView(this);
        h.setText(res);
        h.setTextSize(13);
        h.setAllCaps(true);
        h.setLetterSpacing(0.06f);
        h.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        h.setTextColor(Design.color(this, R.attr.pl_accent));
        h.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 2));
        h.setAccessibilityHeading(true);
        summaryBox.addView(h);
    }

    private void bullets(int res, List<String> items) {
        if (items.isEmpty()) return;
        section(res);
        for (String i : items) {
            TextView v = Ui.body(this, "•  " + i);
            v.setTextSize(15);
            summaryBox.addView(v);
        }
    }

    // ------------------------------------------------------------------ Suche

    private void openSearch() {
        if (showSummary) setTab(false);
        searchBar.setVisibility(View.VISIBLE);
        searchInput.requestFocus();
        InputMethodManager im = getSystemService(InputMethodManager.class);
        if (im != null) im.showSoftInput(searchInput, 0);
    }

    private void closeSearch() {
        if (searchBar.getVisibility() != View.VISIBLE) return;
        searchBar.setVisibility(View.GONE);
        searchInput.setText("");
        InputMethodManager im = getSystemService(InputMethodManager.class);
        if (im != null) im.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (searchBar.getVisibility() == View.VISIBLE) closeSearch();
        else super.onBackPressed();
    }

    // ------------------------------------------------------------------ Wiedergabe

    private boolean ensurePlayer() {
        if (player != null && playerReady) return true;
        File f = Store.audioFile(this, id);
        if (!t.audioReady || !f.isFile()) return false;
        try {
            if (player != null) player.release();
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            player.setDataSource(f.getPath());
            player.prepare();
            player.setOnCompletionListener(mp -> {
                play.setImageResource(R.drawable.ic_play);
                play.setContentDescription(getString(R.string.play));
                handler.removeCallbacks(tick);
            });
            seek.setMax(Math.max(1, player.getDuration()));
            seekMaxSet = true;
            playerReady = true;
            return true;
        } catch (IOException | RuntimeException e) {
            if (player != null) player.release();
            player = null;
            playerReady = false;
            Toast.makeText(this, R.string.audio_error, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void togglePlay() {
        if (!ensurePlayer()) return;
        if (player.isPlaying()) {
            player.pause();
            play.setImageResource(R.drawable.ic_play);
            play.setContentDescription(getString(R.string.play));
            handler.removeCallbacks(tick);
        } else {
            player.start();
            play.setImageResource(R.drawable.ic_pause);
            play.setContentDescription(getString(R.string.pause));
            lastUserScroll = 0;
            tick();
        }
    }

    private void playFrom(long ms) {
        if (!ensurePlayer()) return;
        player.seekTo(ms, MediaPlayer.SEEK_CLOSEST);
        if (!player.isPlaying()) {
            player.start();
            play.setImageResource(R.drawable.ic_pause);
            play.setContentDescription(getString(R.string.pause));
        }
        lastUserScroll = 0;
        handler.removeCallbacks(tick);
        tick();
    }

    private void tick() {
        handler.removeCallbacks(tick);
        if (player == null || !playerReady) return;
        int pos = player.getCurrentPosition();
        seek.setProgress(pos);
        updateTime(pos);
        int seg = segmentAt(pos);
        int row = rows.indexOf(seg);
        if (row != currentRow) {
            currentRow = row;
            adapter.notifyDataSetChanged();
            boolean follow = SystemClock.elapsedRealtime() - lastUserScroll > 4000;
            if (follow && row >= 0 && player.isPlaying()
                    && (row < list.getFirstVisiblePosition() || row > list.getLastVisiblePosition() - 1)) {
                list.smoothScrollToPositionFromTop(row, Ui.dp(this, 40), 250);
            }
        }
        if (player.isPlaying()) handler.postDelayed(tick, 250);
    }

    /** Letzter Abschnitt, der vor pos beginnt (binäre Suche). */
    private int segmentAt(long pos) {
        int lo = 0, hi = t.segments.size() - 1, r = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (t.segments.get(mid).startMs <= pos) { r = mid; lo = mid + 1; } else hi = mid - 1;
        }
        return r;
    }

    private void updateTime(long pos) {
        long dur = player != null && playerReady ? player.getDuration() : t.durationMs;
        time.setText(Times.clock(pos) + " / " + Times.clock(dur));
    }

    // ------------------------------------------------------------------ Bearbeiten

    private boolean editable() {
        if (t.isWorking()) {
            Toast.makeText(this, R.string.busy_no_edit, Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void editSegment(int index) {
        if (!editable() || index < 0 || index >= t.segments.size()) return;
        Transcript.Segment s = t.segments.get(index);
        LinearLayout col = Ui.column(this);
        List<String> labels = t.speakers();
        final String[] speaker = {s.speaker};
        TextView sp = null;
        if (!labels.isEmpty()) {
            Ui.add(col, Ui.label(this, R.string.edit_speaker));
            sp = Ui.choiceRow(this, t.speakerName(s.speaker));
            final TextView spRow = sp;
            spRow.setOnClickListener(v -> {
                String[] names = new String[labels.size()];
                for (int i = 0; i < names.length; i++) names[i] = t.speakerName(labels.get(i));
                Ui.dialog(this).setTitle(R.string.edit_speaker)
                        .setSingleChoiceItems(names, labels.indexOf(speaker[0]), (d, w) -> {
                            speaker[0] = labels.get(w);
                            spRow.setText(names[w]);
                            d.dismiss();
                        }).show();
            });
            Ui.add(col, sp);
        }
        Ui.add(col, Ui.label(this, R.string.edit_text));
        EditText e = Ui.input(this, s.text, Transcript.MAX_TEXT, true);
        e.setMinLines(3);
        e.setGravity(Gravity.TOP | Gravity.START);
        Ui.add(col, e);
        ScrollView sc = new ScrollView(this);
        sc.addView(col);
        Ui.dialog(this).setTitle(getString(R.string.edit_segment) + " · " + Times.clock(s.startMs)).setView(sc)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String txt = Text.block(e.getText().toString(), Transcript.MAX_TEXT);
                    Transcript n = Store.copy(t);
                    if (index >= n.segments.size()) return;
                    if (txt.isEmpty()) n.segments.remove(index);
                    else {
                        n.segments.get(index).text = txt;
                        n.segments.get(index).speaker = speaker[0];
                    }
                    Store.save(this, n);
                })
                .setNeutralButton(R.string.edit_delete_segment, (d, w) -> {
                    Transcript n = Store.copy(t);
                    if (index < n.segments.size()) n.segments.remove(index);
                    Store.save(this, n);
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void renameSpeakers() {
        if (!editable()) return;
        List<String> labels = t.speakers();
        if (labels.isEmpty()) {
            Ui.dialog(this).setMessage(R.string.speakers_none).setPositiveButton(R.string.ok, null).show();
            return;
        }
        LinearLayout col = Ui.column(this);
        List<EditText> fields = new ArrayList<>();
        for (int i = 0; i < labels.size() && i < Transcript.MAX_SPEAKERS; i++) {
            String l = labels.get(i);
            TextView lab = new TextView(this);
            lab.setText(l);
            lab.setTextSize(13);
            lab.setTextColor(Ui.speakerColor(this, i));
            lab.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            lab.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
            Ui.add(col, lab);
            String cur = t.speakerNames.get(l);
            EditText e = Ui.input(this, cur == null ? "" : cur, Transcript.MAX_SPEAKER, false);
            e.setHint(l);
            fields.add(e);
            Ui.add(col, e);
        }
        ScrollView sc = new ScrollView(this);
        sc.addView(col);
        Ui.dialog(this).setTitle(R.string.speakers_title).setView(sc)
                .setPositiveButton(R.string.save, (d, w) -> {
                    Transcript n = Store.copy(t);
                    for (int i = 0; i < fields.size(); i++) {
                        String v = Text.line(fields.get(i).getText().toString(), Transcript.MAX_SPEAKER);
                        if (v.isEmpty() || v.equals(labels.get(i))) n.speakerNames.remove(labels.get(i));
                        else n.speakerNames.put(labels.get(i), v);
                    }
                    Store.save(this, n);
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void menu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, R.string.m_export);
        m.getMenu().add(0, 2, 0, R.string.m_copy);
        m.getMenu().add(0, 3, 0, R.string.m_speakers);
        if (!t.isWorking() && !t.segments.isEmpty()) m.getMenu().add(0, 4, 0, R.string.m_resummary);
        m.getMenu().add(0, 5, 0, R.string.m_rename);
        if (t.audioReady && !t.isWorking()) m.getMenu().add(0, 6, 0, R.string.m_delete_audio);
        m.getMenu().add(0, 7, 0, R.string.m_delete);
        m.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1: exportDialog(); return true;
                case 2: copyText(); return true;
                case 3: renameSpeakers(); return true;
                case 4: TranscribeService.summary(this, id); setTab(true); return true;
                case 5: MainActivity.rename(this, id); return true;
                case 6:
                    Ui.dialog(this).setTitle(R.string.delete_audio_title).setMessage(R.string.delete_audio_text)
                            .setPositiveButton(R.string.delete, (d, w) -> {
                                if (player != null) { player.release(); player = null; playerReady = false; }
                                Store.deleteAudio(this, id);
                            })
                            .setNegativeButton(R.string.cancel, null).show();
                    return true;
                case 7: MainActivity.confirmDelete(this, id, this::finish); return true;
                default: return false;
            }
        });
        m.show();
    }

    private void copyText() {
        Export.Options o = new Export.Options();
        o.summary = false;
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(t.title, Export.txt(t, o)));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ Export

    private void exportDialog() {
        LinearLayout col = Ui.column(this);
        Ui.add(col, Ui.label(this, R.string.exp_format));
        RadioGroup g = new RadioGroup(this);
        int[] labels = {R.string.exp_pdf, R.string.exp_docx, R.string.exp_txt, R.string.exp_srt};
        for (int i = 0; i < labels.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(100 + i);
            rb.setText(labels[i]);
            rb.setTextSize(15);
            rb.setMinHeight(Ui.dp(this, 44));
            rb.setTextColor(Design.color(this, R.attr.pl_text));
            g.addView(rb);
        }
        g.check(100);
        Ui.add(col, g);
        CheckBox times = check(R.string.exp_times, true);
        CheckBox speakers = check(R.string.exp_speakers, true);
        CheckBox summary = check(R.string.exp_summary, t.summary != null);
        summary.setEnabled(t.summary != null);
        Ui.add(col, times);
        Ui.add(col, speakers);
        Ui.add(col, summary);
        g.setOnCheckedChangeListener((gr, cid) -> {
            boolean srt = cid == 103;
            times.setEnabled(!srt);
            summary.setEnabled(!srt && t.summary != null);
        });
        ScrollView sc = new ScrollView(this);
        sc.addView(col);
        Ui.dialog(this).setTitle(R.string.exp_title).setView(sc)
                .setPositiveButton(R.string.exp_share, (d, w) -> doExport(g.getCheckedRadioButtonId() - 100,
                        times.isChecked(), speakers.isChecked(), summary.isChecked(), true))
                .setNeutralButton(R.string.exp_save, (d, w) -> doExport(g.getCheckedRadioButtonId() - 100,
                        times.isChecked(), speakers.isChecked(), summary.isChecked(), false))
                .setNegativeButton(R.string.cancel, null).show();
    }

    private CheckBox check(int res, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(res);
        c.setChecked(on);
        c.setTextSize(15);
        c.setMinHeight(Ui.dp(this, 44));
        c.setTextColor(Design.color(this, R.attr.pl_text));
        return c;
    }

    private static final String[] EXT = {"pdf", "docx", "txt", "srt"};
    private static final String[] MIME = {"application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/plain", "application/x-subrip"};

    private void doExport(int format, boolean times, boolean speakers, boolean summary, boolean share) {
        if (format < 0 || format >= EXT.length) return;
        Export.Options o = new Export.Options();
        o.times = times;
        o.speakers = speakers;
        o.summary = summary && t.summary != null;
        byte[] data;
        try {
            switch (format) {
                case 0: {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    PdfExport.write(t, o, bos);
                    data = bos.toByteArray();
                    break;
                }
                case 1: data = Export.docx(t, o); break;
                case 2: data = Export.txt(t, o).getBytes(StandardCharsets.UTF_8); break;
                default: data = Export.srt(t, speakers).getBytes(StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            Toast.makeText(this, R.string.exp_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        String name = Text.fileName(t.title) + "." + EXT[format];
        if (share) {
            try {
                File root = ExportProvider.root(this);
                deleteOld(root);
                String dir = Store.newId();
                File d = new File(root, dir);
                if (!d.mkdirs()) throw new IOException("mkdir");
                try (OutputStream os = new FileOutputStream(new File(d, name))) {
                    os.write(data);
                }
                Uri uri = ExportProvider.uriFor(dir, name);
                Intent send = new Intent(Intent.ACTION_SEND).setType(MIME[format])
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_SUBJECT, t.title)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                send.setClipData(ClipData.newRawUri(name, uri));
                startActivity(Intent.createChooser(send, getString(R.string.exp_share)));
            } catch (IOException | RuntimeException e) {
                Toast.makeText(this, R.string.exp_failed, Toast.LENGTH_SHORT).show();
            }
        } else {
            pendingExport = data;
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(MIME[format]).putExtra(Intent.EXTRA_TITLE, name);
            try {
                startActivityForResult(i, REQ_SAVE);
            } catch (RuntimeException e) {
                pendingExport = null;
                Toast.makeText(this, R.string.exp_failed, Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** Alte Exporte (älter als 1 Stunde) aus dem Cache entfernen. */
    private static void deleteOld(File root) {
        File[] dirs = root.listFiles();
        if (dirs == null) return;
        long limit = System.currentTimeMillis() - 3_600_000L;
        for (File d : dirs) {
            if (d.lastModified() > limit) continue;
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
                f.delete();
            //noinspection ResultOfMethodCallIgnored
            d.delete();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SAVE) return;
        byte[] bytes = pendingExport;
        pendingExport = null;
        if (res != RESULT_OK || data == null || data.getData() == null || bytes == null) return;
        try (OutputStream os = getContentResolver().openOutputStream(data.getData(), "wt")) {
            if (os == null) throw new IOException("null");
            os.write(bytes);
            Toast.makeText(this, R.string.exp_saved, Toast.LENGTH_SHORT).show();
        } catch (IOException | RuntimeException e) {
            Toast.makeText(this, R.string.exp_failed, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ Liste

    private final class SegAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int p) { return t.segments.get(rows.get(p)); }
        @Override public long getItemId(int p) { return rows.get(p); }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(TranscriptActivity.this).inflate(R.layout.item_segment, parent, false);
            int index = rows.get(pos);
            Transcript.Segment s = t.segments.get(index);
            TextView sp = v.findViewById(R.id.sg_speaker);
            TextView tm = v.findViewById(R.id.sg_time);
            TextView tx = v.findViewById(R.id.sg_text);
            String name = t.speakerName(s.speaker);
            boolean sameAsBefore = index > 0 && query.isEmpty() && t.segments.get(index - 1).speaker.equals(s.speaker);
            sp.setVisibility(name.isEmpty() || sameAsBefore ? View.GONE : View.VISIBLE);
            sp.setText(name);
            sp.setTextColor(Ui.speakerColor(TranscriptActivity.this, speakerOrder.indexOf(s.speaker)));
            tm.setText(Times.clock(s.startMs));
            if (!query.isEmpty()) {
                SpannableString span = new SpannableString(s.text);
                String low = s.text.toLowerCase(Locale.GERMANY);
                int from = 0, at;
                int col = Design.color(TranscriptActivity.this, R.attr.pl_accent_soft);
                while ((at = low.indexOf(query, from)) >= 0 && from < low.length()) {
                    span.setSpan(new BackgroundColorSpan(col), at, at + query.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    from = at + Math.max(1, query.length());
                }
                tx.setText(span);
            } else {
                tx.setText(s.text);
            }
            View root = v.findViewById(R.id.sg_root);
            root.setBackgroundResource(pos == currentRow ? R.drawable.seg_now : 0);
            root.setOnClickListener(x -> {
                if (t.audioReady) playFrom(s.startMs);
            });
            root.setOnLongClickListener(x -> {
                editSegment(index);
                return true;
            });
            root.setContentDescription(Times.clock(s.startMs) + (name.isEmpty() ? "" : ", " + name) + ": " + s.text);
            return v;
        }
    }
}
