package de.mca.pulsascribe;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import de.mca.pulsascribe.core.Adts;

/**
 * Bereitet die Tonspur einer beliebigen Audio- oder Videodatei auf: dekodieren, auf Mono mischen,
 * bei Bedarf umrechnen und als AAC (ADTS) mit 48 kbit/s speichern. Das ist klein, überall abspielbar
 * und lässt sich an Rahmengrenzen in Teile schneiden. Nebenbei wird die Lautstärke je Rahmen erfasst,
 * damit beim Teilen an leisen Stellen geschnitten werden kann.
 */
final class AudioPrep {
    private AudioPrep() {}

    static final long MAX_DURATION_US = 10L * 3600 * 1_000_000; // 10 Stunden
    private static final long TIMEOUT_US = 2_000;

    interface Progress {
        void onProgress(int percent);

        boolean isCanceled();
    }

    static final class Result {
        int sampleRate;
        int frames;
        float[] energy;
        long durationMs;
    }

    static final class PrepException extends IOException {
        final int messageRes;

        PrepException(int messageRes, String detail) {
            super(detail);
            this.messageRes = messageRes;
        }
    }

    static Result run(Context ctx, Uri src, File out, Progress progress) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec decoder = null;
        Encoder enc = null;
        boolean ok = false;
        try {
            try {
                ex.setDataSource(ctx, src, null);
            } catch (IOException | RuntimeException e) {
                throw new PrepException(R.string.err_open_file, e.getClass().getSimpleName());
            }
            int track = -1;
            MediaFormat inFmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    inFmt = f;
                    break;
                }
            }
            if (track < 0) throw new PrepException(R.string.err_no_audio, "no audio track");
            long durationUs = inFmt.containsKey(MediaFormat.KEY_DURATION) ? inFmt.getLong(MediaFormat.KEY_DURATION) : -1;
            if (durationUs > MAX_DURATION_US) throw new PrepException(R.string.err_too_long, "duration");
            ex.selectTrack(track);
            String mime = inFmt.getString(MediaFormat.KEY_MIME);
            try {
                decoder = MediaCodec.createDecoderByType(mime);
                decoder.configure(inFmt, null, null, 0);
                decoder.start();
            } catch (IOException | RuntimeException e) {
                throw new PrepException(R.string.err_codec, mime);
            }

            enc = new Encoder(out);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, decodeDone = false;
            int lastPct = -1;
            long guard = 0;
            while (!enc.done) {
                if (progress.isCanceled()) throw new IOException("canceled");
                if (++guard > 50_000_000L) throw new PrepException(R.string.err_codec, "loop");
                // 1) Eingang des Decoders füttern (mehrere Puffer je Runde)
                for (int k = 0; k < 8 && !inputDone; k++) {
                    int idx = decoder.dequeueInputBuffer(k == 0 ? TIMEOUT_US : 0);
                    if (idx < 0) break;
                    ByteBuffer buf = decoder.getInputBuffer(idx);
                    int n = buf == null ? -1 : ex.readSampleData(buf, 0);
                    if (n < 0) {
                        decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        long t = ex.getSampleTime();
                        decoder.queueInputBuffer(idx, 0, n, Math.max(0, t), 0);
                        ex.advance();
                        if (durationUs > 0) {
                            int pct = (int) Math.min(99, Math.max(0, t * 100 / durationUs));
                            if (pct != lastPct) { lastPct = pct; progress.onProgress(pct); }
                        }
                        if (t > MAX_DURATION_US) throw new PrepException(R.string.err_too_long, "duration");
                    }
                }
                // 2) PCM aus dem Decoder holen (alles, was fertig ist)
                for (int k = 0; k < 8 && !decodeDone; k++) {
                    int idx = decoder.dequeueOutputBuffer(info, k == 0 ? TIMEOUT_US : 0);
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        enc.setInput(decoder.getOutputFormat());
                        continue;
                    }
                    if (idx < 0) break;
                    if (!enc.hasInput()) enc.setInput(decoder.getOutputFormat());
                    ByteBuffer buf = decoder.getOutputBuffer(idx);
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        enc.addPcm(buf);
                    }
                    decoder.releaseOutputBuffer(idx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        decodeDone = true;
                        enc.endOfInput();
                    }
                }
                // 3) Encoder füttern und leeren
                enc.pump(decodeDone);
            }
            if (enc.frames == 0) throw new PrepException(R.string.err_no_audio, "empty");
            Result r = new Result();
            r.sampleRate = enc.outRate;
            r.frames = enc.frames;
            r.energy = enc.energy();
            r.durationMs = enc.frames * (long) Adts.SAMPLES_PER_FRAME * 1000L / enc.outRate;
            progress.onProgress(100);
            ok = true;
            return r;
        } finally {
            try { ex.release(); } catch (RuntimeException ignored) { }
            if (decoder != null) {
                try { decoder.stop(); } catch (RuntimeException ignored) { }
                try { decoder.release(); } catch (RuntimeException ignored) { }
            }
            if (enc != null) enc.close();
            if (!ok) //noinspection ResultOfMethodCallIgnored
                out.delete();
        }
    }

    /** Mischt auf Mono, rechnet die Rate um und kodiert AAC-LC. */
    static final class Encoder {
        private final OutputStream os;
        private MediaCodec codec;
        int inRate, inChannels, pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
        int outRate;
        int rateIndex;
        private double step = 1, pos = 0; // Umrechnung: Eingangsrahmen je Ausgangssample
        private float prevSample = 0;
        private boolean haveInput;

        private short[] pending = new short[16384];
        private int pendingLen = 0;
        private long samplesQueued = 0;
        private boolean inputEnded = false, eosQueued = false;
        boolean done = false;
        int frames = 0;
        private float[] energy = new float[4096];
        private int energyLen = 0;
        private final byte[] header = new byte[Adts.HEADER];
        private byte[] payload = new byte[2048];
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        Encoder(File out) throws IOException {
            os = new BufferedOutputStream(new FileOutputStream(out), 64 * 1024);
        }

        boolean hasInput() {
            return haveInput;
        }

        void setInput(MediaFormat f) throws IOException {
            int rate = f.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? f.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 0;
            int ch = f.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 0;
            int pe = f.containsKey(MediaFormat.KEY_PCM_ENCODING) ? f.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
            if (rate < 4000 || rate > 384000 || ch < 1 || ch > 32) throw new PrepException(R.string.err_codec, "pcm " + rate + "/" + ch);
            if (pe != AudioFormat.ENCODING_PCM_16BIT && pe != AudioFormat.ENCODING_PCM_FLOAT) {
                throw new PrepException(R.string.err_codec, "pcm encoding " + pe);
            }
            if (haveInput) {
                // Formatwechsel mitten im Strom: Kanäle/Kodierung übernehmen, Ausgaberate bleibt
                inChannels = ch;
                pcmEncoding = pe;
                if (rate != inRate) { inRate = rate; step = (double) inRate / outRate; }
                return;
            }
            inRate = rate;
            inChannels = ch;
            pcmEncoding = pe;
            if (Adts.encoderRate(rate)) outRate = rate;
            else outRate = rate > 48000 ? 48000 : 16000;
            step = (double) inRate / outRate;
            rateIndex = Adts.rateIndex(outRate);
            MediaFormat ef = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, outRate, 1);
            ef.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            ef.setInteger(MediaFormat.KEY_BIT_RATE, outRate <= 16000 ? 32000 : 48000);
            ef.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192);
            try {
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                codec.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                codec.start();
            } catch (IOException | RuntimeException e) {
                throw new PrepException(R.string.err_codec, "aac encoder");
            }
            haveInput = true;
        }

        /** Nimmt PCM entgegen (interleaved), mischt auf Mono und rechnet um. */
        void addPcm(ByteBuffer buf) {
            buf.order(ByteOrder.LITTLE_ENDIAN);
            int bytesPerSample = pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
            int frameBytes = bytesPerSample * inChannels;
            int n = buf.remaining() / frameBytes;
            for (int i = 0; i < n; i++) {
                float sum = 0;
                for (int c = 0; c < inChannels; c++) {
                    if (bytesPerSample == 4) sum += buf.getFloat() * 32767f;
                    else sum += buf.getShort();
                }
                float mono = sum / inChannels;
                if (step == 1.0) {
                    push(mono);
                } else {
                    // Lineare Interpolation zwischen vorigem und aktuellem Eingangswert
                    while (pos <= 1.0) {
                        push(prevSample + (float) ((mono - prevSample) * pos));
                        pos += step;
                    }
                    pos -= 1.0;
                }
                prevSample = mono;
            }
        }

        private void push(float v) {
            if (v > 32767f) v = 32767f;
            else if (v < -32768f) v = -32768f;
            if (pendingLen == pending.length) {
                short[] g = new short[pending.length * 2];
                System.arraycopy(pending, 0, g, 0, pendingLen);
                pending = g;
            }
            pending[pendingLen++] = (short) v;
        }

        void flush() throws IOException {
            os.flush();
        }

        /** Für die Aufnahme: Mono-PCM mit fester Rate, ohne Umrechnung. */
        static MediaFormat pcmFormat(int rate) {
            MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_RAW, rate, 1);
            f.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            return f;
        }

        void endOfInput() {
            inputEnded = true;
        }

        /** Füttert den Encoder in Blöcken zu 1024 Samples und schreibt fertige Rahmen. */
        void pump(boolean decodeDone) throws IOException {
            if (codec == null) {
                if (decodeDone) done = true; // nie Ton bekommen
                return;
            }
            // Eingang: so viele Blöcke wie möglich, damit sich kein Rückstand aufbaut
            for (int k = 0; k < 32; k++) {
                if (!feed(decodeDone, k == 0 ? TIMEOUT_US : 0)) break;
            }
            // Ausgang: alles abholen, was fertig ist
            for (int k = 0; k < 64; k++) {
                if (!drain(k == 0 ? TIMEOUT_US : 0)) break;
            }
        }

        private boolean feed(boolean decodeDone, long timeout) {
            boolean last = inputEnded && decodeDone;
            if (eosQueued) return false;
            if (pendingLen < Adts.SAMPLES_PER_FRAME && !last) return false;
            int idx = codec.dequeueInputBuffer(timeout);
            if (idx < 0) return false;
            ByteBuffer in = codec.getInputBuffer(idx);
            if (pendingLen == 0) {
                codec.queueInputBuffer(idx, 0, 0, pts(), MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                eosQueued = true;
                return false;
            }
            if (in == null) {
                codec.queueInputBuffer(idx, 0, 0, pts(), 0);
                return false;
            }
            in.clear();
            in.order(ByteOrder.LITTLE_ENDIAN);
            int count = Math.min(Adts.SAMPLES_PER_FRAME, pendingLen);
            count = Math.min(count, in.capacity() / 2);
            double sq = 0;
            for (int i = 0; i < count; i++) {
                short s = pending[i];
                in.putShort(s);
                sq += (double) s * s;
            }
            // Letzter Block wird mit Stille aufgefüllt, damit jeder Rahmen 1024 Samples hat
            int fill = 0;
            if (count < Adts.SAMPLES_PER_FRAME && last && in.remaining() >= (Adts.SAMPLES_PER_FRAME - count) * 2) {
                fill = Adts.SAMPLES_PER_FRAME - count;
                for (int i = 0; i < fill; i++) in.putShort((short) 0);
            }
            addEnergy((float) Math.sqrt(sq / Math.max(1, count + fill)));
            System.arraycopy(pending, count, pending, 0, pendingLen - count);
            pendingLen -= count;
            long t = pts();
            samplesQueued += count + fill;
            codec.queueInputBuffer(idx, 0, (count + fill) * 2, t, 0);
            return true;
        }

        private boolean drain(long timeout) throws IOException {
            int out = codec.dequeueOutputBuffer(info, timeout);
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || out == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) return true;
            if (out < 0) return false;
            ByteBuffer ob = codec.getOutputBuffer(out);
            boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            if (ob != null && info.size > 0 && !config) {
                if (info.size + Adts.HEADER > Adts.MAX_FRAME) throw new PrepException(R.string.err_codec, "frame");
                if (payload.length < info.size) payload = new byte[info.size];
                ob.position(info.offset);
                ob.limit(info.offset + info.size);
                ob.get(payload, 0, info.size);
                Adts.header(header, rateIndex, 1, info.size);
                os.write(header);
                os.write(payload, 0, info.size);
                frames++;
            }
            codec.releaseOutputBuffer(out, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                os.flush();
                done = true;
                return false;
            }
            return true;
        }

        private long pts() {
            return samplesQueued * 1_000_000L / outRate;
        }

        private void addEnergy(float e) {
            if (energyLen == energy.length) {
                float[] g = new float[energy.length * 2];
                System.arraycopy(energy, 0, g, 0, energyLen);
                energy = g;
            }
            energy[energyLen++] = e;
        }

        float[] energy() {
            float[] r = new float[energyLen];
            System.arraycopy(energy, 0, r, 0, energyLen);
            return r;
        }

        void close() {
            try { os.close(); } catch (IOException ignored) { }
            if (codec != null) {
                try { codec.stop(); } catch (RuntimeException ignored) { }
                try { codec.release(); } catch (RuntimeException ignored) { }
            }
        }
    }
}
