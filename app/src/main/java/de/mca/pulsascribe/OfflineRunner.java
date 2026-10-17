package de.mca.pulsascribe;

import android.media.MediaCodec;
import android.media.MediaFormat;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import de.mca.pulsascribe.core.Adts;
import de.mca.pulsascribe.core.Offline;
import de.mca.pulsascribe.core.Transcript;

/**
 * Offline-Transkription eines Abschnitts der eigenen AAC-Datei: Rahmen direkt dekodieren (kein Container),
 * auf 16 kHz umrechnen, in Fenstern zu 5 Minuten an whisper.cpp geben. Nichts verlässt das Gerät.
 */
final class OfflineRunner {
    private OfflineRunner() {}

    static final long WINDOW_MS = 5 * 60_000L;

    interface Progress {
        void on(int percent);

        boolean canceled();
    }

    static final class OfflineException extends IOException {
        final int messageRes;

        OfflineException(int res) {
            super("offline");
            messageRes = res;
        }
    }

    private static long ctx;
    private static String ctxModel;

    /** Modell laden (bleibt für weitere Teile geladen). */
    static synchronized void open(File model) throws OfflineException {
        if (!Whisper.load()) throw new OfflineException(R.string.off_err_native);
        if (ctx != 0 && model.getPath().equals(ctxModel)) return;
        close();
        ctx = Whisper.nInit(model.getPath());
        if (ctx == 0) throw new OfflineException(R.string.off_err_model);
        ctxModel = model.getPath();
    }

    static synchronized void close() {
        if (ctx != 0) Whisper.nFree(ctx);
        ctx = 0;
        ctxModel = null;
    }

    static void abort(boolean on) {
        if (Whisper.load()) Whisper.nAbort(on);
    }

    /** Transkribiert die Rahmen [startFrame, endFrame). */
    static List<Transcript.Segment> run(File aac, Adts.Index idx, int startFrame, int endFrame, String lang, Progress p)
            throws IOException {
        List<Transcript.Segment> out = new ArrayList<>();
        int rate = idx.sampleRate;
        int rateIndex = Adts.rateIndex(rate);
        if (rateIndex < 0) throw new OfflineException(R.string.err_audio_broken);
        int framesPerWindow = (int) (WINDOW_MS * rate / 1000 / Adts.SAMPLES_PER_FRAME);
        int threads = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() - 2));
        int total = endFrame - startFrame;
        for (int w = startFrame; w < endFrame; w += framesPerWindow) {
            if (p.canceled()) throw new IOException("canceled");
            int we = Math.min(endFrame, w + framesPerWindow);
            float[] pcm = decode(aac, idx, w, we, rateIndex);
            float[] s16 = Offline.resample(pcm, pcm.length, rate);
            if (s16.length < Offline.RATE / 2) continue; // weniger als 0,5 s
            final int done = w - startFrame, span = we - w;
            byte[] json;
            synchronized (OfflineRunner.class) {
                if (ctx == 0) throw new OfflineException(R.string.off_err_model);
                Thread poll = new Thread(() -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        int inner = Whisper.nProgress();
                        p.on((int) ((done + (long) span * inner / 100) * 100 / Math.max(1, total)));
                        if (p.canceled()) Whisper.nAbort(true);
                        try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                    }
                }, "whisper-progress");
                poll.start();
                try {
                    Whisper.nAbort(false);
                    json = Whisper.nTranscribe(ctx, s16, lang, threads);
                } finally {
                    poll.interrupt();
                }
            }
            if (p.canceled()) throw new IOException("canceled");
            long offset = idx.frameToMs(w);
            List<Transcript.Segment> segs = Offline.parse(json, offset, idx.frameToMs(we) - offset);
            if (segs == null) throw new OfflineException(R.string.off_err_run);
            out.addAll(segs);
        }
        return out;
    }

    /** Dekodiert Rahmen aus der ADTS-Datei direkt mit MediaCodec (Header werden entfernt). */
    static float[] decode(File aac, Adts.Index idx, int from, int to, int rateIndex) throws IOException {
        MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, idx.sampleRate, 1);
        f.setByteBuffer("csd-0", ByteBuffer.wrap(Offline.audioSpecificConfig(rateIndex, 1)));
        f.setInteger(MediaFormat.KEY_IS_ADTS, 0);
        MediaCodec dec;
        try {
            dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            dec.configure(f, null, null, 0);
            dec.start();
        } catch (IOException | RuntimeException e) {
            throw new OfflineException(R.string.err_codec);
        }
        int expected = (to - from) * Adts.SAMPLES_PER_FRAME;
        float[] out = new float[expected + Adts.SAMPLES_PER_FRAME * 4];
        int outLen = 0;
        try (RandomAccessFile raf = new RandomAccessFile(aac, "r")) {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int next = from;
            boolean inputDone = false, done = false;
            byte[] frame = new byte[Adts.MAX_FRAME];
            int channels = 1;
            long guard = 0;
            while (!done) {
                if (++guard > 20_000_000L) throw new OfflineException(R.string.err_codec);
                if (!inputDone) {
                    int ii = dec.dequeueInputBuffer(2000);
                    if (ii >= 0) {
                        ByteBuffer in = dec.getInputBuffer(ii);
                        if (next >= to || in == null) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long off = idx.offsets[next];
                            int len = (int) (idx.offsets[next + 1] - off);
                            if (len <= Adts.HEADER || len > Adts.MAX_FRAME) throw new OfflineException(R.string.err_audio_broken);
                            raf.seek(off);
                            raf.readFully(frame, 0, len);
                            in.clear();
                            in.put(frame, Adts.HEADER, len - Adts.HEADER);
                            long pts = (long) (next - from) * Adts.SAMPLES_PER_FRAME * 1_000_000L / idx.sampleRate;
                            dec.queueInputBuffer(ii, 0, len - Adts.HEADER, pts, 0);
                            next++;
                        }
                    }
                }
                int oi = dec.dequeueOutputBuffer(info, 2000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = dec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = Math.max(1, of.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                } else if (oi >= 0) {
                    ByteBuffer ob = dec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ob.order(ByteOrder.LITTLE_ENDIAN);
                        int samples = info.size / 2 / channels;
                        if (outLen + samples > out.length) {
                            float[] g = new float[Math.max(out.length * 2, outLen + samples)];
                            System.arraycopy(out, 0, g, 0, outLen);
                            out = g;
                        }
                        for (int i = 0; i < samples; i++) {
                            float sum = 0;
                            for (int c = 0; c < channels; c++) sum += ob.getShort();
                            out[outLen++] = sum / channels / 32768f;
                        }
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true;
                }
            }
        } finally {
            try { dec.stop(); } catch (RuntimeException ignored) { }
            try { dec.release(); } catch (RuntimeException ignored) { }
        }
        float[] r = new float[outLen];
        System.arraycopy(out, 0, r, 0, outLen);
        return r;
    }
}
