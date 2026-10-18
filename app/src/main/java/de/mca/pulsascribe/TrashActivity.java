package de.mca.pulsascribe;

import android.app.Activity;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import de.mca.pulsascribe.core.Times;

/** Gelöschte Transkripte: Infos (gelöscht am, Größe, Dauer, Ordner), wiederherstellen, endgültig löschen. */
public final class TrashActivity extends Activity {
    private LinearLayout list;
    private String themeName;
    private final Runnable onChange = this::render;

    @Override
    protected void onCreate(Bundle state) {
        Design.apply(this);
        themeName = Design.get(this);
        super.onCreate(state);
        setContentView(R.layout.activity_trash);
        list = findViewById(R.id.trash_list);
        findViewById(R.id.trash_back).setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!themeName.equals(Design.get(this))) { recreate(); return; }
        Store.addListener(onChange);
        render();
    }

    @Override
    protected void onPause() {
        Store.removeListener(onChange);
        super.onPause();
    }

    private String size(long bytes) {
        return Formatter.formatShortFileSize(this, bytes);
    }

    private void render() {
        list.removeAllViews();
        List<Store.TrashItem> items = Store.trashList(this);
        long trash = Store.trashBytes(this), active = Store.activeBytes(this);
        int days = Settings.trashDays(this);

        // Übersicht: Platz im Papierkorb im Verhältnis zum Gesamtspeicher der App
        LinearLayout card = card();
        TextView big = new TextView(this);
        big.setText(size(trash));
        big.setTextSize(28);
        big.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        big.setTextColor(Design.color(this, R.attr.pl_text));
        card.addView(big);
        card.addView(muted(items.isEmpty() ? getString(R.string.trash_empty_long)
                : getResources().getQuantityString(R.plurals.trash_summary, items.size(), items.size(), size(trash))));
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        long total = trash + active;
        bar.setMax(1000);
        bar.setProgress(total == 0 ? 0 : (int) Math.max(trash > 0 ? 8 : 0, trash * 1000 / total));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Design.color(this, R.attr.pl_accent)));
        bar.setContentDescription(getString(R.string.trash_share, size(trash), size(total)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 12));
        blp.topMargin = Ui.dp(this, 10);
        card.addView(bar, blp);
        card.addView(muted(getString(R.string.trash_share, size(trash), size(total))));
        card.addView(muted(days == 0 ? getString(R.string.trash_auto_never)
                : getResources().getQuantityString(R.plurals.trash_auto, days, days)));
        if (!items.isEmpty()) {
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setPadding(0, Ui.dp(this, 10), 0, 0);
            actions.addView(button(R.string.trash_restore_all, false, v -> restoreAll(items)));
            View gap = new View(this);
            actions.addView(gap, new LinearLayout.LayoutParams(Ui.dp(this, 10), 1));
            actions.addView(button(R.string.trash_empty, true, v -> confirmEmpty(items.size(), trash)));
            card.addView(actions);
        }
        list.addView(card);

        for (Store.TrashItem it : items) list.addView(itemCard(it, days));
    }

    private View itemCard(Store.TrashItem it, int days) {
        LinearLayout c = card();
        TextView title = new TextView(this);
        title.setText(it.title);
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Design.color(this, R.attr.pl_text));
        c.addView(title);
        c.addView(info(R.string.trash_deleted_at, Ui.date(it.deletedMs)));
        String sz = size(it.bytes) + (it.audio ? " · " + getString(R.string.trash_with_audio) : "");
        c.addView(info(R.string.trash_size, sz));
        if (it.durationMs > 0) c.addView(info(R.string.trash_duration, Times.duration(it.durationMs)));
        c.addView(info(R.string.trash_created, Ui.date(it.createdMs)));
        if (!it.folder.isEmpty()) c.addView(info(R.string.trash_folder, it.folder));
        if (days > 0) c.addView(info(R.string.trash_until, Ui.date(it.deletedMs + days * 86_400_000L)));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, Ui.dp(this, 10), 0, 0);
        actions.addView(button(R.string.trash_restore, false, v -> {
            if (Store.restore(this, it.id)) Toast.makeText(this, R.string.trash_restored, Toast.LENGTH_SHORT).show();
            else Toast.makeText(this, R.string.trash_restore_failed, Toast.LENGTH_LONG).show();
        }));
        View gap = new View(this);
        actions.addView(gap, new LinearLayout.LayoutParams(Ui.dp(this, 10), 1));
        actions.addView(button(R.string.trash_purge, true, v -> Ui.dialog(this)
                .setTitle(getString(R.string.trash_purge_title, it.title))
                .setMessage(R.string.trash_purge_text)
                .setPositiveButton(R.string.delete, (d, w) -> Store.purge(this, it.id))
                .setNegativeButton(R.string.cancel, null).show()));
        c.addView(actions);
        return c;
    }

    private void restoreAll(List<Store.TrashItem> items) {
        int ok = 0;
        for (Store.TrashItem it : items) if (Store.restore(this, it.id)) ok++;
        Toast.makeText(this, getResources().getQuantityString(R.plurals.trash_restored_n, ok, ok), Toast.LENGTH_SHORT).show();
    }

    private void confirmEmpty(int n, long bytes) {
        Ui.dialog(this).setTitle(R.string.trash_empty_title)
                .setMessage(getResources().getQuantityString(R.plurals.trash_empty_text, n, n, size(bytes)))
                .setPositiveButton(R.string.trash_empty, (d, w) -> {
                    Store.emptyTrash(this);
                    Toast.makeText(this, R.string.trash_emptied, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    // ------------------------------------------------------------------ Bausteine

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.crumb_bg);
        int p = Ui.dp(this, 14);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 10);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView muted(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(Design.color(this, R.attr.pl_muted));
        t.setPadding(0, Ui.dp(this, 4), 0, 0);
        return t;
    }

    private TextView info(int label, String value) {
        TextView t = new TextView(this);
        t.setText(getString(label) + ": " + value);
        t.setTextSize(13);
        t.setTextColor(Design.color(this, R.attr.pl_muted));
        t.setPadding(0, Ui.dp(this, 3), 0, 0);
        return t;
    }

    private TextView button(int res, boolean danger, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(res);
        b.setTextSize(14);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(Ui.dp(this, 44));
        int p = Ui.dp(this, 14);
        b.setPadding(p, 0, p, 0);
        b.setBackgroundResource(R.drawable.btn_secondary);
        b.setTextColor(danger ? 0xFFE57373 : Design.color(this, R.attr.pl_accent));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return b;
    }
}
