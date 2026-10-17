package de.mca.pulsascribe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import de.mca.pulsascribe.core.Todoist;
import de.mca.pulsascribe.core.Transcript;

/** Aufgaben aus allen Gesprächen: abhaken, zum Gespräch springen, mit Todoist abgleichen. */
public final class TasksActivity extends Activity {
    private LinearLayout list;
    private TextView count;
    private boolean showDone;
    private String themeName;
    private final Runnable onChange = this::render;

    @Override
    protected void onCreate(Bundle state) {
        Design.apply(this);
        themeName = Design.get(this);
        super.onCreate(state);
        setContentView(R.layout.activity_tasks);
        list = findViewById(R.id.tasks_list);
        count = findViewById(R.id.tasks_count);
        findViewById(R.id.tasks_back).setOnClickListener(v -> finish());
        showDone = state != null && state.getBoolean("done", false);
        Switch sw = findViewById(R.id.tasks_show_done);
        sw.setChecked(showDone);
        sw.setOnCheckedChangeListener((b, on) -> { showDone = on; render(); });
        View sync = findViewById(R.id.tasks_sync);
        sync.setOnClickListener(v -> {
            Toast.makeText(this, R.string.td_syncing, Toast.LENGTH_SHORT).show();
            TodoistSync.pull(this, true, res -> { if (!isFinishing()) Toast.makeText(this, res, Toast.LENGTH_SHORT).show(); });
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("done", showDone);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!themeName.equals(Design.get(this))) { recreate(); return; }
        findViewById(R.id.tasks_sync).setVisibility(TodoistToken.has(this) ? View.VISIBLE : View.GONE);
        Store.addListener(onChange);
        render();
        TodoistSync.pull(this, false, null);
    }

    @Override
    protected void onPause() {
        Store.removeListener(onChange);
        super.onPause();
    }

    private void render() {
        List<Transcript> all = Store.all(this);
        int open = Todoist.openCount(all);
        count.setText(getResources().getQuantityString(R.plurals.tasks_open, open, open));
        list.removeAllViews();
        List<Todoist.Item> items = Todoist.tasks(all, showDone);
        if (items.isEmpty()) {
            TextView none = Ui.body(this, getString(R.string.tasks_empty));
            none.setTextColor(Design.color(this, R.attr.pl_muted));
            none.setPadding(Ui.dp(this, 4), Ui.dp(this, 24), Ui.dp(this, 4), 0);
            list.addView(none);
            return;
        }
        String lastId = null;
        for (Todoist.Item it : items) {
            if (!it.transcript.id.equals(lastId)) {
                lastId = it.transcript.id;
                TextView h = new TextView(this);
                h.setText(it.transcript.title + " · " + Ui.date(it.transcript.createdMs));
                h.setTextSize(13);
                h.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                h.setTextColor(Design.color(this, R.attr.pl_accent));
                h.setPadding(Ui.dp(this, 4), Ui.dp(this, 18), Ui.dp(this, 4), Ui.dp(this, 4));
                h.setMaxLines(2);
                h.setEllipsize(android.text.TextUtils.TruncateAt.END);
                list.addView(h);
            }
            list.addView(row(it));
        }
    }

    private View row(Todoist.Item it) {
        Transcript.Task k = it.task;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.crumb_bg);
        CheckBox cb = new CheckBox(this);
        cb.setChecked(k.done);
        cb.setContentDescription(getString(R.string.task_done_cd, k.what));
        cb.setOnCheckedChangeListener((b, on) -> TodoistSync.setDone(this, it.transcript.id, it.index, k.what, on));
        row.addView(cb, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        TextView what = Ui.body(this, k.what);
        what.setTextSize(15);
        if (k.done) {
            what.setPaintFlags(what.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            what.setTextColor(Design.color(this, R.attr.pl_muted));
        }
        col.addView(what);
        String sub = (k.who.isEmpty() ? "" : k.who) + (k.remote.isEmpty() ? "" : (k.who.isEmpty() ? "" : " · ") + getString(R.string.td_linked));
        if (!sub.isEmpty()) {
            TextView w = new TextView(this);
            w.setText(sub);
            w.setTextSize(12);
            w.setTextColor(Design.color(this, R.attr.pl_muted));
            col.addView(w);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(getString(R.string.tasks_open_talk));
        row.setOnClickListener(v -> startActivity(new Intent(this, TranscriptActivity.class)
                .putExtra(TranscribeService.EXTRA_ID, it.transcript.id)
                .putExtra(TranscriptActivity.EXTRA_TAB_SUMMARY, true)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 4);
        row.setLayoutParams(lp);
        return row;
    }
}
