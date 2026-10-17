package de.mca.pulsascribe;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import de.mca.pulsascribe.core.Export;
import de.mca.pulsascribe.core.Transcript;

/** PDF (A4) mit Android-Bordmitteln; Seitenumbruch zeilenweise, Text ist durchsuchbar. */
final class PdfExport {
    private PdfExport() {}

    private static final int W = 595, H = 842, MARGIN = 48;

    static void write(Transcript t, Export.Options o, OutputStream out) throws IOException {
        List<Export.Block> blocks = Export.blocks(t, o);
        PdfDocument doc = new PdfDocument();
        try {
            Pager p = new Pager(doc);
            TextPaint base = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
            base.setColor(Color.rgb(26, 26, 26));
            int width = W - 2 * MARGIN;
            for (Export.Block b : blocks) {
                TextPaint paint = new TextPaint(base);
                CharSequence text;
                float before = 0, after = 6;
                switch (b.kind) {
                    case Export.H1:
                        paint.setTextSize(20);
                        paint.setTypeface(Typeface.DEFAULT_BOLD);
                        text = b.text;
                        after = 4;
                        break;
                    case Export.META:
                        paint.setTextSize(9.5f);
                        paint.setColor(Color.rgb(107, 114, 128));
                        text = b.text;
                        after = 16;
                        break;
                    case Export.H2:
                        paint.setTextSize(13.5f);
                        paint.setTypeface(Typeface.DEFAULT_BOLD);
                        text = b.text;
                        before = 10;
                        after = 4;
                        break;
                    case Export.BULLET:
                        paint.setTextSize(10.5f);
                        text = "•  " + b.text;
                        after = 3;
                        break;
                    case Export.PARA:
                        paint.setTextSize(10.5f);
                        text = b.text;
                        break;
                    default: {
                        paint.setTextSize(10.5f);
                        SpannableStringBuilder s = new SpannableStringBuilder();
                        if (!b.time.isEmpty()) {
                            int st = s.length();
                            s.append(b.time).append("   ");
                            s.setSpan(new ForegroundColorSpan(Color.rgb(107, 114, 128)), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            s.setSpan(new RelativeSizeSpan(0.85f), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        if (!b.speaker.isEmpty()) {
                            int st = s.length();
                            s.append(b.speaker).append(": ");
                            s.setSpan(new StyleSpan(Typeface.BOLD), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        s.append(b.text);
                        text = s;
                        after = 5;
                    }
                }
                StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setLineSpacing(0, 1.15f)
                        .build();
                p.y += before;
                for (int i = 0; i < layout.getLineCount(); i++) {
                    int top = layout.getLineTop(i), bottom = layout.getLineBottom(i);
                    int h = bottom - top;
                    if (p.y + h > H - MARGIN) p.newPage();
                    Canvas c = p.canvas();
                    c.save();
                    c.translate(MARGIN, p.y - top);
                    c.clipRect(0, top, width, bottom);
                    layout.draw(c);
                    c.restore();
                    p.y += h;
                }
                p.y += after;
            }
            p.finish();
            doc.writeTo(out);
        } finally {
            doc.close();
        }
    }

    private static final class Pager {
        final PdfDocument doc;
        PdfDocument.Page page;
        int number = 0;
        float y;

        Pager(PdfDocument doc) {
            this.doc = doc;
            newPage();
        }

        Canvas canvas() {
            return page.getCanvas();
        }

        void newPage() {
            if (page != null) doc.finishPage(page);
            number++;
            page = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, number).create());
            y = MARGIN;
            TextPaint foot = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
            foot.setTextSize(8);
            foot.setColor(Color.rgb(150, 150, 150));
            page.getCanvas().drawText(String.valueOf(number), W - MARGIN - 10, H - 24, foot);
        }

        void finish() {
            if (page != null) doc.finishPage(page);
            page = null;
        }
    }
}
