package de.mca.pulsascribe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.CalendarContract;
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
import android.widget.ImageView;
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
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import de.mca.pulsascribe.core.Export;
import de.mca.pulsascribe.core.Languages;
import de.mca.pulsascribe.core.Search;
import de.mca.pulsascribe.core.Templates;
import de.mca.pulsascribe.core.Text;
import de.mca.pulsascribe.core.Times;
import de.mca.pulsascribe.core.Transcript;

/**
 * Ein Transkript: lesen, anhören (Tippen springt zur Stelle), bearbeiten, zusammenfassen, Fragen stellen,
 * übersetzen, vorlesen, exportieren, Aufgaben weitergeben.
 */
public final class TranscriptActivity extends Activity {
    static final String EXTRA_READ_WHEN_DONE = "read_when_done";
    static final String EXTRA_QUERY = "query";
    static final String EXTRA_TAB_SUMMARY = "tab_summary";
    static final String EXTRA_START_WAITING = "start_waiting";
    private static final int REQ_SAVE = 5;
    private static final int TAB_TEXT = 0, TAB_SUMMARY = 1, TAB_ASK = 2;

    private String id;
    private Transcript t;
    private final List<Integer> rows = new ArrayList<>(); // Indizes in t.segments (gefiltert)
    private List<String> speakerOrder = new ArrayList<>();
    private boolean seekMaxSet;
    private SegAdapter adapter;
    private ListView list;
    private ScrollView summaryScroll;
    private LinearLayout summaryBox, answersBox;
    private View askBox, readBar;
    private TextView title, meta, note, tabText, tabSummary, tabAsk, time, searchCount;
    private View retry, searchBar;
    private EditText searchInput, askInput;
    private ImageButton play;
    private SeekBar seek;
    private ImageView imageHeader;
    private int tab = TAB_TEXT;
    private String query = "";
    private String viewLang = null; // null = Original, sonst Sprachcode einer Übersetzung
    private String wantLang = null; // nach fertiger Übersetzung automatisch anzeigen

    private MediaPlayer player;
    private boolean playerReady;
    private int currentRow = -1;
    private long lastUserScroll;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::tick;
    private final Runnable onChange = this::reload;
    private String themeName;
    private Reader reader;
    private boolean readWhenDone;

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
        tabAsk = findViewById(R.id.tr_tab_ask);
        list = findViewById(R.id.tr_list);
        summaryScroll = findViewById(R.id.tr_summary_scroll);
        summaryBox = findViewById(R.id.tr_summary);
        askBox = findViewById(R.id.tr_ask_box);
        answersBox = findViewById(R.id.tr_answers);
        askInput = findViewById(R.id.tr_ask_input);
        readBar = findViewById(R.id.tr_read_bar);
        play = findViewById(R.id.tr_play);
        seek = findViewById(R.id.tr_seek);
        time = findViewById(R.id.tr_time);
        searchBar = findViewById(R.id.tr_search_bar);
        searchInput = findViewById(R.id.tr_search_input);
        searchCount = findViewById(R.id.tr_search_count);

        imageHeader = new ImageView(this);
        imageHeader.setAdjustViewBounds(true);
        imageHeader.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageHeader.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 8));
        imageHeader.setMaxHeight(Ui.dp(this, 320));
        imageHeader.setContentDescription(getString(R.string.kind_image));
        list.addHeaderView(imageHeader, null, false);

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

        reader = new Reader(this, new Reader.Listener() {
            @Override
            public void onState(boolean speaking, int index) {
                readBar.setVisibility(speaking ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onError(int res) {
                Toast.makeText(TranscriptActivity.this, res, Toast.LENGTH_LONG).show();
            }
        });
        findViewById(R.id.tr_read_stop).setOnClickListener(v -> reader.stop());

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
                query = Search.normalize(s.toString());
                filter();
            }
        });
        tabText.setOnClickListener(v -> setTab(TAB_TEXT));
        tabSummary.setOnClickListener(v -> setTab(TAB_SUMMARY));
        tabAsk.setOnClickListener(v -> setTab(TAB_ASK));
        findViewById(R.id.tr_ask_send).setOnClickListener(v -> sendQuestion());
        android.widget.Switch expertSw = findViewById(R.id.tr_ask_expert);
        expertSw.setChecked(Settings.askExpert(this));
        expertSw.setOnCheckedChangeListener((b, on) -> Settings.setAskExpert(this, on));
        findViewById(R.id.tr_ask_estimate).setOnClickListener(v -> askQuestion(getString(R.string.ask_estimate_q), true));
        retry.setOnClickListener(v -> {
            if (t.isWorking()) TranscribeService.cancel(this, id);
            else if (t.aggregate) TranscribeService.folder(this, id, t.folder, "");
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
        Transcript first = Store.get(this, id);
        int startTab = first.aggregate || (!first.isAudio() && first.summary != null) ? TAB_SUMMARY : TAB_TEXT;
        if (state != null) {
            startTab = state.getInt("tab", startTab);
            viewLang = state.getString("lang");
        } else {
            readWhenDone = getIntent().getBooleanExtra(EXTRA_READ_WHEN_DONE, false);
            if (!first.isAudio() && readWhenDone) startTab = first.summarize ? TAB_SUMMARY : TAB_TEXT;
            if (getIntent().getBooleanExtra(EXTRA_TAB_SUMMARY, false)) startTab = TAB_SUMMARY;
        }
        if (state == null && getIntent().getBooleanExtra(EXTRA_START_WAITING, false) && Transcript.WAITING.equals(first.status)) {
            try { TranscribeService.start(this, id, null); } catch (RuntimeException ignored) { }
        }
        reload();
        setTab(startTab);
        String q = getIntent().getStringExtra(EXTRA_QUERY);
        if (state == null && q != null && !q.isEmpty()) {
            openSearch();
            searchInput.setText(Text.line(q, 60));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!themeName.equals(Design.get(this))) { recreate(); return; }
        Store.addListener(onChange);
        reload();
        TodoistSync.pull(this, false, null);
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
        if (reader != null) reader.release();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
        if (viewLang != null) out.putString("lang", viewLang);
    }

    // ------------------------------------------------------------------ Anzeige

    private Transcript.Translation translation() {
        return viewLang == null ? null : t.translations.get(viewLang);
    }

    private String segText(int index) {
        Transcript.Translation tr = translation();
        if (tr != null && index < tr.segments.size() && !tr.segments.get(index).isEmpty()) return tr.segments.get(index);
        return t.segments.get(index).text;
    }

    private Transcript.Summary shownSummary() {
        Transcript.Translation tr = translation();
        return tr != null && tr.summary != null ? tr.summary : t.summary;
    }

    private void reload() {
        Transcript cur = Store.get(this, id);
        if (cur == null) { finish(); return; }
        t = cur;
        if (wantLang != null && t.translations.containsKey(wantLang)) { viewLang = wantLang; wantLang = null; }
        if (wantLang != null && !t.isWorking()) wantLang = null; // Übersetzung fehlgeschlagen
        if (viewLang != null && !t.translations.containsKey(viewLang)) viewLang = null;
        speakerOrder = t.speakers();
        title.setText(t.title);
        StringBuilder m = new StringBuilder(Ui.meta(this, t));
        String path = OptionsForm.folderLabel(this, Store.folders(this), t.folder);
        if (!t.folder.isEmpty()) m.insert(0, path + " · ");
        for (String tag : t.tags) m.append("  #").append(tag);
        meta.setText(m);
        String status = Ui.status(this, t);
        if (status == null && t.incomplete) status = getString(R.string.st_incomplete);
        if (status == null && t.parts.size() > 1 && viewLang == null) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < t.parts.size(); i++) {
                if (i > 0) b.append(" · ");
                Transcript.Part pt = t.parts.get(i);
                b.append(i + 1).append(") ").append(Times.clock(pt.startMs)).append(' ').append(pt.name);
            }
            status = getString(R.string.multi_parts, t.parts.size(), b.toString());
        }
        if (status == null && viewLang != null) status = getString(R.string.translated_note, Languages.label(viewLang));
        note.setText(status == null ? "" : status);
        note.setVisibility(status == null ? View.GONE : View.VISIBLE);
        note.setTextColor(Design.color(this, Transcript.ERROR.equals(t.status) ? R.attr.pl_error : R.attr.pl_text));
        boolean failed = Transcript.ERROR.equals(t.status) || Transcript.CANCELED.equals(t.status) || Transcript.WAITING.equals(t.status);
        boolean canRetry = failed && (t.isAudio() || t.aggregate || Transcript.KIND_IMAGE.equals(t.kind)
                || Transcript.KIND_WEB.equals(t.kind) || Transcript.KIND_PDF.equals(t.kind) || !t.segments.isEmpty());
        retry.setVisibility(canRetry || t.isWorking() ? View.VISIBLE : View.GONE);
        ((TextView) retry).setText(t.isWorking() ? R.string.cancel : Transcript.WAITING.equals(t.status) ? R.string.call_transcribe : R.string.retry);
        boolean hasAudio = t.isAudio() && t.audioReady && Store.audioFile(this, id).isFile();
        findViewById(R.id.tr_player).setVisibility(hasAudio && !Transcript.PREPARE.equals(t.status) ? View.VISIBLE : View.GONE);
        if (!hasAudio && player != null) {
            player.release();
            player = null;
            playerReady = false;
        }
        tabText.setText(t.isAudio() ? R.string.tab_text : R.string.kind_text);
        File img = Store.imageFile(this, id);
        if (Transcript.KIND_IMAGE.equals(t.kind) && img.isFile()) {
            if (imageHeader.getDrawable() == null) {
                Bitmap b = ImagePrep.thumb(img, 1080);
                if (b != null) imageHeader.setImageBitmap(b);
            }
            imageHeader.setVisibility(View.VISIBLE);
        } else {
            imageHeader.setVisibility(View.GONE);
        }
        filter();
        if (tab == TAB_SUMMARY) buildSummary();
        if (tab == TAB_ASK) buildAnswers();
        if (!seekMaxSet && t.durationMs > 0) {
            seek.setMax((int) Math.min(Integer.MAX_VALUE, t.durationMs));
            seekMaxSet = true;
        }
        updateTime(player != null && playerReady ? player.getCurrentPosition() : 0);
        if (readWhenDone && Transcript.DONE.equals(t.status)) {
            readWhenDone = false;
            if (t.summary != null) reader.speak(Export.speechSummary(t, t.summary), speechLang(true));
            else if (!t.segments.isEmpty()) readText(0);
        } else if (readWhenDone && Transcript.ERROR.equals(t.status)) {
            readWhenDone = false;
        }
    }

    private void filter() {
        rows.clear();
        int hits = 0;
        for (int i = 0; i < t.segments.size(); i++) {
            Transcript.Segment s = t.segments.get(i);
            if (query.isEmpty() || segText(i).toLowerCase(Locale.GERMANY).contains(query)
                    || t.speakerName(s.speaker).toLowerCase(Locale.GERMANY).contains(query)) {
                rows.add(i);
                if (!query.isEmpty()) hits++;
            }
        }
        searchCount.setText(query.isEmpty() ? "" : getString(R.string.search_count, hits));
        adapter.notifyDataSetChanged();
    }

    private void setTab(int which) {
        tab = which;
        TextView[] tabs = {tabText, tabSummary, tabAsk};
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setSelected(i == which);
            tabs[i].setTextColor(Design.color(this, i == which ? R.attr.pl_on_accent : R.attr.pl_text));
        }
        list.setVisibility(which == TAB_TEXT ? View.VISIBLE : View.GONE);
        summaryScroll.setVisibility(which == TAB_SUMMARY ? View.VISIBLE : View.GONE);
        askBox.setVisibility(which == TAB_ASK ? View.VISIBLE : View.GONE);
        if (which != TAB_TEXT) closeSearch();
        if (which == TAB_SUMMARY) buildSummary();
        if (which == TAB_ASK) buildAnswers();
    }

    // ------------------------------------------------------------------ Zusammenfassung

    private void buildSummary() {
        summaryBox.removeAllViews();
        Transcript.Summary s = shownSummary();
        if (s == null || s.isEmpty()) {
            TextView none = Ui.body(this, getString(R.string.summary_none));
            none.setTextColor(Design.color(this, R.attr.pl_muted));
            none.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 10));
            summaryBox.addView(none);
            if (!t.isWorking() && !t.segments.isEmpty()) {
                summaryBox.addView(primaryButton(R.string.summary_create, v -> resummarize()),
                        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            return;
        }
        String[] h = Templates.headings(t.template);
        TextView tpl = Ui.body(this, Templates.label(t.template));
        tpl.setTextSize(12);
        tpl.setTextColor(Design.color(this, R.attr.pl_muted));
        tpl.setPadding(0, Ui.dp(this, 8), 0, 0);
        summaryBox.addView(tpl);
        section(h[0]);
        if (!s.overview.isEmpty()) summaryBox.addView(Ui.body(this, s.overview));
        bullets(h[1], s.points);
        if (!s.tasks.isEmpty()) {
            section(h[2]);
            boolean token = TodoistToken.has(this);
            for (int i = 0; i < s.tasks.size(); i++) {
                Transcript.Task k = s.tasks.get(i);
                // Haken/Verknüpfung stehen immer im Original (Übersetzung hat dieselbe Reihenfolge)
                Transcript.Task orig = t.summary != null && i < t.summary.tasks.size() ? t.summary.tasks.get(i) : k;
                summaryBox.addView(taskRow(k, orig, i, token));
            }
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.VERTICAL);
            actions.addView(linkText(R.string.tasks_mail_all, x -> mailTasks(openTasks(s))));
            actions.addView(linkText(R.string.tasks_share_all, x -> shareTasks(openTasks(s))));
            if (token) actions.addView(linkText(R.string.td_send_all, x -> sendTodoist(-1)));
            summaryBox.addView(actions);
        }
        bullets(h[3], s.decisions);
        bullets(h[4], s.questions);
        TextView hint = Ui.body(this, getString(R.string.sum_hint)
                + (t.sumModel.isEmpty() ? "" : " " + getString(R.string.sum_model, t.sumModel)));
        hint.setTextSize(12);
        hint.setTextColor(Design.color(this, R.attr.pl_muted));
        hint.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 6));
        summaryBox.addView(hint);
        summaryBox.addView(secondaryButton(R.string.read_summary, v -> reader.speak(Export.speechSummary(t, s), speechLang(true))),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // CRM: kopieren / per E-Mail; bei anderer Vorlage zuerst als CRM-Eintrag zusammenfassen lassen
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 8);
        boolean service = Templates.SERVICE.equals(t.template);
        if (Templates.isReport(t.template)) {
            if (!t.isWorking() && !t.segments.isEmpty()) {
                summaryBox.addView(linkText(t.firstPerson ? R.string.crm_view_me : R.string.crm_view_neutral, v -> crmPerspective()));
            }
            summaryBox.addView(secondaryButton(service ? R.string.service_copy : R.string.crm_copy, v -> copyCrm(s)), lp);
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(lp);
            summaryBox.addView(secondaryButton(service ? R.string.service_mail : R.string.crm_mail, v -> mailCrm(s)), lp2);
        }
        if (!t.isWorking() && !t.segments.isEmpty()) {
            if (Settings.crmEnabled(this) && !Templates.CRM.equals(t.template)) {
                summaryBox.addView(secondaryButton(R.string.crm_make, v -> makeReport(Templates.CRM)), new LinearLayout.LayoutParams(lp));
            }
            if (Settings.serviceEnabled(this) && !service) {
                summaryBox.addView(secondaryButton(R.string.service_make, v -> makeReport(Templates.SERVICE)), new LinearLayout.LayoutParams(lp));
            }
        }
    }

    private String crmText(Transcript.Summary s) {
        Transcript c = shownCopy();
        return Export.crmEntry(c, c.summary != null ? c.summary : s);
    }

    private void copyCrm(Transcript.Summary s) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm == null) return;
        ClipData clip = ClipData.newPlainText(getString(R.string.crm_clip_label), crmText(s));
        // Kundendaten: nicht in der Zwischenablage-Vorschau anzeigen (Android 13+)
        android.os.PersistableBundle extras = new android.os.PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        cm.setPrimaryClip(clip);
        Toast.makeText(this, Templates.SERVICE.equals(t.template) ? R.string.service_copied : R.string.crm_copied, Toast.LENGTH_SHORT).show();
    }

    private void mailCrm(Transcript.Summary s) {
        Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                .putExtra(Intent.EXTRA_SUBJECT, getString(Templates.SERVICE.equals(t.template)
                        ? R.string.service_mail_subject : R.string.crm_mail_subject, t.title))
                .putExtra(Intent.EXTRA_TEXT, crmText(s));
        launch(i);
    }

    /** Perspektive des CRM-Eintrags: Ich-Form (mit Wahl, welcher Sprecher „ich“ ist) oder neutral; danach neu erstellen. */
    private void crmPerspective() {
        if (!editable() || t.segments.isEmpty()) return;
        if (!AiKeys.ready(this)) { KeyDialog.showActive(this, null); return; }
        LinearLayout col = Ui.column(this);
        RadioGroup rg = new RadioGroup(this);
        RadioButton me = new RadioButton(this);
        me.setId(View.generateViewId());
        me.setText(R.string.crm_persp_me);
        me.setMinHeight(Ui.dp(this, 48));
        RadioButton neutral = new RadioButton(this);
        neutral.setId(View.generateViewId());
        neutral.setText(R.string.crm_persp_neutral);
        neutral.setMinHeight(Ui.dp(this, 48));
        rg.addView(me);
        rg.addView(neutral);
        rg.check(t.firstPerson ? me.getId() : neutral.getId());
        Ui.add(col, rg);
        List<String> labels = t.speakers();
        String[] names = new String[labels.size() + 1];
        names[0] = Settings.myName(this).isEmpty() ? getString(R.string.crm_me_auto)
                : getString(R.string.crm_me_auto_name, Settings.myName(this));
        int sel = 0;
        for (int i = 0; i < labels.size(); i++) {
            names[i + 1] = t.speakerName(labels.get(i));
            if (labels.get(i).equals(t.me)) sel = i + 1;
        }
        final int[] pick = {sel};
        TextView whoLabel = Ui.label(this, R.string.crm_me_who);
        TextView whoRow = Ui.choiceRow(this, names[sel]);
        whoRow.setOnClickListener(v -> Ui.dialog(this).setTitle(R.string.crm_me_who)
                .setSingleChoiceItems(names, pick[0], (d, w) -> {
                    pick[0] = w;
                    whoRow.setText(names[w]);
                    d.dismiss();
                }).show());
        if (labels.size() > 0) {
            Ui.add(col, whoLabel);
            Ui.add(col, whoRow);
        }
        rg.setOnCheckedChangeListener((g, id) -> {
            int vis = id == me.getId() && labels.size() > 0 ? View.VISIBLE : View.GONE;
            whoLabel.setVisibility(vis);
            whoRow.setVisibility(vis);
        });
        if (!t.firstPerson) { whoLabel.setVisibility(View.GONE); whoRow.setVisibility(View.GONE); }
        Ui.dialog(this).setTitle(R.string.crm_persp_title).setView(col)
                .setPositiveButton(R.string.summary_create, (d, w) -> {
                    Transcript n = Store.copy(t);
                    n.firstPerson = rg.getCheckedRadioButtonId() == me.getId();
                    n.me = pick[0] == 0 ? "" : labels.get(pick[0] - 1);
                    n.meName = Settings.myName(this);
                    Store.save(this, n);
                    TranscribeService.summary(this, id);
                    setTab(TAB_SUMMARY);
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    /** Vorlage auf CRM-Eintrag bzw. Servicebericht umstellen und Zusammenfassung neu erstellen. */
    private void makeReport(String template) {
        if (!editable() || t.segments.isEmpty()) return;
        if (!AiKeys.ready(this)) { KeyDialog.showActive(this, null); return; }
        Ui.dialog(this).setMessage(Templates.SERVICE.equals(template) ? R.string.service_make_confirm : R.string.crm_make_confirm)
                .setPositiveButton(R.string.summary_create, (d, w) -> {
                    Transcript n = Store.copy(t);
                    n.template = template;
                    Store.save(this, n);
                    TranscribeService.summary(this, id);
                    setTab(TAB_SUMMARY);
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    private TextView primaryButton(int res, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(res);
        b.setTextSize(16);
        b.setTextColor(Design.color(this, R.attr.pl_on_accent));
        b.setBackgroundResource(R.drawable.btn_primary);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(Ui.dp(this, 48));
        int p = Ui.dp(this, 18);
        b.setPadding(p, 0, p, 0);
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(l);
        return b;
    }

    private TextView secondaryButton(int res, View.OnClickListener l) {
        TextView b = primaryButton(res, l);
        b.setTextColor(Design.color(this, R.attr.pl_accent));
        b.setBackgroundResource(R.drawable.btn_secondary);
        return b;
    }

    private void section(String text) {
        TextView h = new TextView(this);
        h.setText(text);
        h.setTextSize(13);
        h.setAllCaps(true);
        h.setLetterSpacing(0.06f);
        h.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        h.setTextColor(Design.color(this, R.attr.pl_accent));
        h.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 2));
        h.setAccessibilityHeading(true);
        summaryBox.addView(h);
    }

    private void bullets(String heading, List<String> items) {
        if (items.isEmpty()) return;
        section(heading);
        for (String i : items) {
            TextView v = Ui.body(this, "•  " + i);
            v.setTextSize(15);
            summaryBox.addView(v);
        }
    }

    private View taskRow(Transcript.Task shown, Transcript.Task orig, int index, boolean token) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.crumb_bg);
        CheckBox cb = new CheckBox(this);
        cb.setChecked(orig.done);
        cb.setContentDescription(getString(R.string.task_done_cd, shown.what));
        cb.setOnCheckedChangeListener((b, on) -> TodoistSync.setDone(this, id, index, orig.what, on));
        row.addView(cb, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        TextView v = Ui.body(this, shown.who.isEmpty() ? shown.what : shown.what + " — " + shown.who);
        v.setTextSize(15);
        if (orig.done) {
            v.setPaintFlags(v.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            v.setTextColor(Design.color(this, R.attr.pl_muted));
        }
        v.setPadding(0, Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!orig.remote.isEmpty()) {
            TextView tag = new TextView(this);
            tag.setText(R.string.td_linked);
            tag.setTextSize(11);
            tag.setTextColor(Design.color(this, R.attr.pl_accent));
            tag.setPadding(0, 0, Ui.dp(this, 8), 0);
            row.addView(tag);
        }
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(x -> taskMenu(x, shown, orig, index, token));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 4);
        row.setLayoutParams(lp);
        return row;
    }

    private TextView linkText(int res, View.OnClickListener l) {
        TextView all = new TextView(this);
        all.setText(res);
        all.setTextColor(Design.color(this, R.attr.pl_accent));
        all.setTextSize(14);
        all.setMinHeight(Ui.dp(this, 44));
        all.setGravity(Gravity.CENTER_VERTICAL);
        all.setOnClickListener(l);
        return all;
    }

    private static List<Transcript.Task> openTasks(Transcript.Summary s) {
        List<Transcript.Task> l = new ArrayList<>();
        for (Transcript.Task k : s.tasks) if (!k.done) l.add(k);
        return l.isEmpty() ? s.tasks : l;
    }

    private void taskMenu(View anchor, Transcript.Task k, Transcript.Task orig, int index, boolean token) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, R.string.m_task_calendar);
        m.getMenu().add(0, 2, 0, R.string.m_task_mail);
        m.getMenu().add(0, 3, 0, R.string.m_task_share);
        if (token && orig.remote.isEmpty() && !orig.done) m.getMenu().add(0, 4, 0, R.string.td_send_one);
        m.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1: addToCalendar(k); break;
                case 2: mailTasks(java.util.Collections.singletonList(k)); break;
                case 3: shareTasks(java.util.Collections.singletonList(k)); break;
                case 4: sendTodoist(index); break;
                default: return false;
            }
            return true;
        });
        m.show();
    }

    private void sendTodoist(int index) {
        Toast.makeText(this, R.string.td_sending, Toast.LENGTH_SHORT).show();
        TodoistSync.push(this, id, index, res -> {
            if (!isFinishing()) Toast.makeText(this, res, Toast.LENGTH_LONG).show();
        });
    }

    /** An jede Aufgaben-App (Todoist, Microsoft To Do, TickTick …): eine Zeile je Aufgabe. */
    private void shareTasks(List<Transcript.Task> tasks) {
        StringBuilder b = new StringBuilder();
        for (Transcript.Task k : tasks) b.append(k.what).append(k.who.isEmpty() ? "" : " (" + k.who + ")").append('\n');
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, tasks.size() == 1 ? Text.line(tasks.get(0).what, 200) : getString(R.string.tasks_mail_subject, t.title))
                .putExtra(Intent.EXTRA_TEXT, b.toString().trim());
        launch(Intent.createChooser(i, getString(R.string.m_task_share)));
    }

    /** Kalender-App mit vorausgefülltem Termin öffnen (keine Kalender-Berechtigung nötig). */
    private void addToCalendar(Transcript.Task k) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, 1);
        c.set(Calendar.HOUR_OF_DAY, 9);
        c.set(Calendar.MINUTE, 0);
        Intent i = new Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, Text.line(k.what, 200))
                .putExtra(CalendarContract.Events.DESCRIPTION, (k.who.isEmpty() ? "" : k.who + "\n") + t.title)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, c.getTimeInMillis())
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, c.getTimeInMillis() + 30 * 60_000L);
        launch(i);
    }

    private void mailTasks(List<Transcript.Task> tasks) {
        StringBuilder b = new StringBuilder();
        for (Transcript.Task k : tasks) b.append("• ").append(k.what).append(k.who.isEmpty() ? "" : " (" + k.who + ")").append('\n');
        b.append('\n').append(t.title).append(", ").append(Ui.date(t.createdMs));
        Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.tasks_mail_subject, t.title))
                .putExtra(Intent.EXTRA_TEXT, b.toString());
        launch(i);
    }

    private void launch(Intent i) {
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ Fragen

    private void buildAnswers() {
        answersBox.removeAllViews();
        if (t.answers.isEmpty()) {
            TextView none = Ui.body(this, getString(R.string.ask_empty));
            none.setTextColor(Design.color(this, R.attr.pl_muted));
            none.setPadding(0, Ui.dp(this, 16), 0, 0);
            answersBox.addView(none);
            return;
        }
        for (Transcript.Answer a : t.answers) {
            TextView q = new TextView(this);
            q.setText(a.question);
            q.setTextSize(15);
            q.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            q.setTextColor(Design.color(this, R.attr.pl_text));
            q.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 2));
            answersBox.addView(q);
            if (a.expert) {
                TextView tag = new TextView(this);
                tag.setText(a.sources.isEmpty() ? R.string.ask_expert_label_plain : R.string.ask_expert_label);
                tag.setTextSize(12);
                tag.setTextColor(Design.color(this, R.attr.pl_accent));
                answersBox.addView(tag);
            }
            TextView ans = Ui.body(this, a.answer);
            ans.setTextSize(15);
            ans.setOnLongClickListener(v -> { answerMenu(v, a); return true; });
            answersBox.addView(ans);
            if (!a.sources.isEmpty()) {
                TextView sh = new TextView(this);
                sh.setText(R.string.ask_sources);
                sh.setTextSize(12);
                sh.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                sh.setTextColor(Design.color(this, R.attr.pl_muted));
                sh.setPadding(0, Ui.dp(this, 6), 0, 0);
                answersBox.addView(sh);
                for (String[] src : a.sources) {
                    TextView l = new TextView(this);
                    l.setText("↗ " + src[0]);
                    l.setTextSize(13);
                    l.setTextColor(Design.color(this, R.attr.pl_accent));
                    l.setMinHeight(Ui.dp(this, 36));
                    l.setGravity(Gravity.CENTER_VERTICAL);
                    l.setOnClickListener(v -> {
                        String u = de.mca.pulsascribe.core.WebPage.cleanUrl(src[1]);
                        if (u != null) launch(new Intent(Intent.ACTION_VIEW, Uri.parse(u)).addCategory(Intent.CATEGORY_BROWSABLE));
                    });
                    answersBox.addView(l);
                }
            }
            if (!a.refs.isEmpty() && t.isAudio()) {
                LinearLayout chips = new LinearLayout(this);
                chips.setOrientation(LinearLayout.HORIZONTAL);
                for (long ms : a.refs) {
                    TextView c = new TextView(this);
                    c.setText("▶ " + Times.clock(ms));
                    c.setTextSize(13);
                    c.setTextColor(Design.color(this, R.attr.pl_accent));
                    c.setBackgroundResource(R.drawable.tag_bg);
                    c.setGravity(Gravity.CENTER);
                    c.setMinHeight(Ui.dp(this, 36));
                    int p = Ui.dp(this, 10);
                    c.setPadding(p, 0, p, 0);
                    c.setContentDescription(getString(R.string.ask_jump, Times.clock(ms)));
                    c.setOnClickListener(v -> jumpTo(ms));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.setMarginEnd(Ui.dp(this, 8));
                    chips.addView(c, lp);
                }
                answersBox.addView(chips);
            }
        }
    }

    private void answerMenu(View anchor, Transcript.Answer a) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, R.string.read_answer);
        m.getMenu().add(0, 2, 0, R.string.ask_delete);
        m.setOnMenuItemClickListener(mi -> {
            if (mi.getItemId() == 1) {
                reader.speak(Reader.of(a.question, a.answer), speechLang(true));
            } else if (!t.isWorking()) {
                Transcript n = Store.copy(t);
                for (int i = 0; i < n.answers.size(); i++) {
                    Transcript.Answer x = n.answers.get(i);
                    if (x.askedMs == a.askedMs && x.question.equals(a.question)) { n.answers.remove(i); break; }
                }
                Store.save(this, n);
            }
            return true;
        });
        m.show();
    }

    private void sendQuestion() {
        String q = Text.line(askInput.getText().toString(), 300);
        if (q.isEmpty()) return;
        if (askQuestion(q, Settings.askExpert(this))) askInput.setText("");
    }

    /** Frage stellen; expert = mit Fachwissen und eigenen Vergleichsfällen (klar als Einschätzung markiert). */
    private boolean askQuestion(String q, boolean expert) {
        if (t.isWorking()) {
            Toast.makeText(this, R.string.ask_busy, Toast.LENGTH_SHORT).show();
            return false;
        }
        if (!AiKeys.ready(this)) {
            KeyDialog.showActive(this, null);
            return false;
        }
        InputMethodManager im = getSystemService(InputMethodManager.class);
        if (im != null) im.hideSoftInputFromWindow(askInput.getWindowToken(), 0);
        TranscribeService.ask(this, id, q, expert);
        return true;
    }

    private void jumpTo(long ms) {
        setTab(TAB_TEXT);
        int seg = segmentAt(ms);
        int row = rows.indexOf(seg);
        if (row >= 0) list.setSelectionFromTop(row + list.getHeaderViewsCount(), Ui.dp(this, 40));
        if (t.audioReady) playFrom(ms);
    }

    // ------------------------------------------------------------------ Suche

    private void openSearch() {
        if (tab != TAB_TEXT) setTab(TAB_TEXT);
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
        else if (reader != null && reader.isSpeaking()) reader.stop();
        else super.onBackPressed();
    }

    // ------------------------------------------------------------------ Vorlesen

    /** Sprache für die Sprachausgabe: Zusammenfassung ggf. in ihrer eingestellten Sprache. */
    private String speechLang(boolean summary) {
        if (viewLang != null) return viewLang;
        if (summary) {
            String sl = Settings.summaryLanguage(this);
            if (!Languages.SUM_SAME.equals(sl)) return sl;
        }
        return t.language;
    }

    private void readText(int fromSegment) {
        List<String> items = new ArrayList<>();
        String last = null;
        for (int i = Math.max(0, fromSegment); i < t.segments.size(); i++) {
            String name = t.speakerName(t.segments.get(i).speaker);
            String text = segText(i);
            items.add(!name.isEmpty() && !name.equals(last) ? name + ": " + text : text);
            last = name;
        }
        reader.speak(items, speechLang(false));
    }

    private void readMenu() {
        String[] opts = {getString(R.string.read_summary), getString(R.string.read_text)};
        Ui.dialog(this).setTitle(R.string.m_read).setItems(opts, (d, w) -> {
            if (w == 0) {
                Transcript.Summary s = shownSummary();
                if (s == null) Toast.makeText(this, R.string.summary_none, Toast.LENGTH_SHORT).show();
                else reader.speak(Export.speechSummary(t, s), speechLang(true));
            } else {
                int from = currentRow >= 0 && currentRow < rows.size() ? rows.get(currentRow) : 0;
                readText(from);
            }
        }).show();
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
            if (reader.isSpeaking()) reader.stop();
            player.start();
            play.setImageResource(R.drawable.ic_pause);
            play.setContentDescription(getString(R.string.pause));
            lastUserScroll = 0;
            tick();
        }
    }

    private void playFrom(long ms) {
        if (!ensurePlayer()) return;
        if (reader.isSpeaking()) reader.stop();
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
            int lp = row + list.getHeaderViewsCount();
            if (follow && row >= 0 && player.isPlaying()
                    && (lp < list.getFirstVisiblePosition() || lp > list.getLastVisiblePosition() - 1)) {
                list.smoothScrollToPositionFromTop(lp, Ui.dp(this, 40), 250);
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
        if (!labels.isEmpty()) {
            Ui.add(col, Ui.label(this, R.string.edit_speaker));
            TextView spRow = Ui.choiceRow(this, t.speakerName(s.speaker));
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
            Ui.add(col, spRow);
        }
        Ui.add(col, Ui.label(this, R.string.edit_text));
        EditText e = Ui.input(this, s.text, Transcript.MAX_TEXT, true);
        e.setMinLines(3);
        e.setGravity(Gravity.TOP | Gravity.START);
        Ui.add(col, e);
        ScrollView sc = new ScrollView(this);
        sc.addView(col);
        String head = getString(R.string.edit_segment) + (t.isAudio() ? " · " + Times.clock(s.startMs) : "");
        Ui.dialog(this).setTitle(head).setView(sc)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String txt = Text.block(e.getText().toString(), Transcript.MAX_TEXT);
                    Transcript n = Store.copy(t);
                    if (index >= n.segments.size()) return;
                    if (txt.isEmpty()) removeSegment(n, index);
                    else {
                        n.segments.get(index).text = txt;
                        n.segments.get(index).speaker = speaker[0];
                    }
                    Store.save(this, n);
                })
                .setNeutralButton(R.string.edit_delete_segment, (d, w) -> {
                    Transcript n = Store.copy(t);
                    if (index < n.segments.size()) removeSegment(n, index);
                    Store.save(this, n);
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    /** Abschnitt löschen; Übersetzungen behalten die gleiche Reihenfolge. */
    private static void removeSegment(Transcript n, int index) {
        n.segments.remove(index);
        for (Transcript.Translation tr : n.translations.values()) {
            if (index < tr.segments.size()) tr.segments.remove(index);
        }
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

    private void chooseTemplate() {
        if (!editable()) return;
        Ui.dialog(this).setTitle(R.string.template_title)
                .setSingleChoiceItems(Settings.templateLabels(Settings.templateIds(this, t.template)),
                        Settings.templateIds(this, t.template).indexOf(Templates.clean(t.template)), (d, w) -> {
                    d.dismiss();
                    String tpl = Settings.templateIds(this, t.template).get(w);
                    if (tpl.equals(t.template)) return;
                    Transcript n = Store.copy(t);
                    n.template = tpl;
                    Store.save(this, n);
                    if (!n.segments.isEmpty()) {
                        Ui.dialog(this).setMessage(R.string.template_resummary)
                                .setPositiveButton(R.string.summary_create, (d2, w2) -> resummarize())
                                .setNegativeButton(R.string.cancel, null).show();
                    }
                }).show();
    }

    /** Zusammenfassung (neu) erstellen: Länge und Sprache wählbar. */
    private void resummarize() {
        if (!editable() || t.segments.isEmpty()) return;
        if (!AiKeys.ready(this)) { KeyDialog.showActive(this, null); return; }
        LinearLayout col = Ui.column(this);
        Ui.add(col, Ui.label(this, R.string.sum_length));
        final String[] len = {t.summaryLength};
        TextView lenRow = Ui.choiceRow(this, Templates.LENGTH_LABELS[Templates.lengthIndex(len[0])]);
        lenRow.setOnClickListener(v -> Ui.dialog(this).setTitle(R.string.sum_length)
                .setSingleChoiceItems(Templates.LENGTH_LABELS, Templates.lengthIndex(len[0]), (d, w) -> {
                    len[0] = Templates.LENGTHS[w];
                    lenRow.setText(Templates.LENGTH_LABELS[w]);
                    d.dismiss();
                }).show());
        Ui.add(col, lenRow);
        Ui.add(col, Ui.label(this, R.string.set_sum_lang));
        final String[] sl = {Settings.summaryLanguage(this)};
        TextView langRow = Ui.choiceRow(this, Languages.SUM_LABELS[Languages.summaryIndex(sl[0])]);
        langRow.setOnClickListener(v -> Ui.dialog(this).setTitle(R.string.set_sum_lang)
                .setSingleChoiceItems(Languages.SUM_LABELS, Languages.summaryIndex(sl[0]), (d, w) -> {
                    sl[0] = Languages.SUM_CODES[w];
                    langRow.setText(Languages.SUM_LABELS[w]);
                    d.dismiss();
                }).show());
        Ui.add(col, langRow);
        Ui.dialog(this).setTitle(R.string.m_resummary).setView(col)
                .setPositiveButton(R.string.summary_create, (d, w) -> {
                    Transcript n = Store.copy(t);
                    n.summaryLength = Templates.cleanLength(len[0]);
                    Store.save(this, n);
                    Settings.setSummaryLanguage(this, sl[0]);
                    TranscribeService.summary(this, id);
                    setTab(TAB_SUMMARY);
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void chooseTranslation() {
        if (!editable() || t.segments.isEmpty()) return;
        // Ohne "Automatisch"
        String[] labels = new String[Languages.CODES.length - 1];
        System.arraycopy(Languages.LABELS, 1, labels, 0, labels.length);
        Ui.dialog(this).setTitle(R.string.translate_title)
                .setItems(labels, (d, w) -> {
                    if (!AiKeys.ready(this)) { KeyDialog.showActive(this, null); return; }
                    String code = Languages.CODES[w + 1];
                    wantLang = code;
                    TranscribeService.translate(this, id, code);
                }).show();
    }

    private void chooseView() {
        List<String> codes = new ArrayList<>(t.translations.keySet());
        String[] labels = new String[codes.size() + 1];
        labels[0] = getString(R.string.show_original);
        for (int i = 0; i < codes.size(); i++) labels[i + 1] = Languages.label(codes.get(i));
        int sel = viewLang == null ? 0 : codes.indexOf(viewLang) + 1;
        Ui.dialog(this).setTitle(R.string.m_show_lang)
                .setSingleChoiceItems(labels, sel, (d, w) -> {
                    d.dismiss();
                    viewLang = w == 0 ? null : codes.get(w - 1);
                    reload();
                }).show();
    }

    private void menu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, R.string.m_export);
        m.getMenu().add(0, 8, 0, R.string.m_read);
        m.getMenu().add(0, 2, 0, R.string.m_copy);
        if (t.isAudio()) m.getMenu().add(0, 3, 0, R.string.m_speakers);
        if (!t.isWorking() && !t.segments.isEmpty()) {
            m.getMenu().add(0, 9, 0, R.string.m_template);
            m.getMenu().add(0, 4, 0, R.string.m_resummary);
            m.getMenu().add(0, 10, 0, R.string.m_translate);
        }
        if (!t.translations.isEmpty()) m.getMenu().add(0, 11, 0, R.string.m_show_lang);
        m.getMenu().add(0, 12, 0, R.string.folder_move);
        m.getMenu().add(0, 13, 0, R.string.m_tags);
        m.getMenu().add(0, 5, 0, R.string.m_rename);
        if (t.audioReady && !t.isWorking()) m.getMenu().add(0, 6, 0, R.string.m_delete_audio);
        if (!t.url.isEmpty()) m.getMenu().add(0, 40, 0, R.string.m_open_source);
        if (!t.url.isEmpty() && de.mca.pulsascribe.core.WebPage.youTubeId(t.url).isEmpty()) m.getMenu().add(0, 41, 0, R.string.br_open);
        m.getMenu().add(0, 7, 0, R.string.m_delete);
        m.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1: exportDialog(); return true;
                case 2: copyText(); return true;
                case 3: renameSpeakers(); return true;
                case 4: resummarize(); return true;
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
                case 41:
                    startActivity(new Intent(this, BrowserActivity.class).putExtra(BrowserActivity.EXTRA_URL, t.url));
                    return true;
                case 40: {
                    String u = de.mca.pulsascribe.core.WebPage.cleanUrl(t.url);
                    if (u != null) launch(new Intent(Intent.ACTION_VIEW, Uri.parse(u)).addCategory(Intent.CATEGORY_BROWSABLE));
                    return true;
                }
                case 8: readMenu(); return true;
                case 9: chooseTemplate(); return true;
                case 10: chooseTranslation(); return true;
                case 11: chooseView(); return true;
                case 12: MainActivity.moveTranscript(this, id); return true;
                case 13: MainActivity.editTags(this, id); return true;
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
            cm.setPrimaryClip(ClipData.newPlainText(t.title, Export.txt(shownCopy(), o)));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        }
    }

    /** Für Kopieren/Export: Transkript in der gerade angezeigten Sprache. */
    private Transcript shownCopy() {
        Transcript.Translation tr = translation();
        if (tr == null) return t;
        Transcript c = Store.copy(t);
        for (int i = 0; i < c.segments.size() && i < tr.segments.size(); i++) {
            if (!tr.segments.get(i).isEmpty()) c.segments.get(i).text = tr.segments.get(i);
        }
        if (tr.summary != null) c.summary = tr.summary;
        return c;
    }

    // ------------------------------------------------------------------ Export

    private void exportDialog() {
        LinearLayout col = Ui.column(this);
        Ui.add(col, Ui.label(this, R.string.exp_format));
        RadioGroup g = new RadioGroup(this);
        int[] labels = {R.string.exp_pdf, R.string.exp_docx, R.string.exp_txt, R.string.exp_srt};
        for (int i = 0; i < labels.length; i++) {
            if (i == 3 && !t.isAudio()) continue;
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
        CheckBox times = check(R.string.exp_times, t.isAudio());
        times.setEnabled(t.isAudio());
        CheckBox speakers = check(R.string.exp_speakers, true);
        CheckBox summary = check(R.string.exp_summary, t.summary != null);
        summary.setEnabled(t.summary != null);
        if (t.isAudio()) Ui.add(col, times);
        if (!t.speakers().isEmpty()) Ui.add(col, speakers);
        Ui.add(col, summary);
        g.setOnCheckedChangeListener((gr, cid) -> {
            boolean srt = cid == 103;
            times.setEnabled(!srt && t.isAudio());
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
        Transcript src = shownCopy();
        Export.Options o = new Export.Options();
        o.times = times && t.isAudio();
        o.speakers = speakers;
        o.summary = summary && src.summary != null;
        byte[] data;
        try {
            switch (format) {
                case 0: {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    PdfExport.write(src, o, bos);
                    data = bos.toByteArray();
                    break;
                }
                case 1: data = Export.docx(src, o); break;
                case 2: data = Export.txt(src, o).getBytes(StandardCharsets.UTF_8); break;
                default: data = Export.srt(src, speakers).getBytes(StandardCharsets.UTF_8);
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
    static void deleteOld(File root) {
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
            tm.setVisibility(t.isAudio() ? View.VISIBLE : View.GONE);
            String text = segText(index);
            if (!query.isEmpty()) {
                SpannableString span = new SpannableString(text);
                String low = text.toLowerCase(Locale.GERMANY);
                int from = 0, at;
                int col = Design.color(TranscriptActivity.this, R.attr.pl_accent_soft);
                while (from < low.length() && (at = low.indexOf(query, from)) >= 0) {
                    span.setSpan(new BackgroundColorSpan(col), at, at + query.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    from = at + Math.max(1, query.length());
                }
                tx.setText(span);
            } else {
                tx.setText(text);
            }
            View root = v.findViewById(R.id.sg_root);
            root.setBackgroundResource(pos == currentRow ? R.drawable.seg_now : 0);
            root.setOnClickListener(x -> {
                if (t.isAudio() && t.audioReady) playFrom(s.startMs);
            });
            root.setOnLongClickListener(x -> {
                editSegment(index);
                return true;
            });
            root.setContentDescription((t.isAudio() ? Times.clock(s.startMs) + ", " : "") + (name.isEmpty() ? "" : name + ": ") + text);
            return v;
        }
    }
}
