package de.mca.pulsascribe;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

import de.mca.pulsascribe.core.Adts;

/** Teil einer ADTS-AAC-Datei (eigene Aufbereitung) ohne Neukodierung in eine .m4a-Datei packen (für OpenAI/Mistral). */
final class M4a {
    private M4a() {}

    static void write(File adts, Adts.Index idx, int startFrame, int endFrame, File out) throws IOException {
        MediaMuxer mux = null;
        boolean ok = false;
        try (RandomAccessFile raf = new RandomAccessFile(adts, "r")) {
            int rate = idx.sampleRate, rateIndex = Adts.rateIndex(rate);
            // AudioSpecificConfig: AAC-LC (2), Ratenindex, 1 Kanal (eigene Aufbereitung ist immer mono)
            int asc = (2 << 11) | (rateIndex << 7) | (1 << 3);
            MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1);
            f.setByteBuffer("csd-0", ByteBuffer.wrap(new byte[]{(byte) (asc >> 8), (byte) asc}));
            mux = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int track = mux.addTrack(f);
            mux.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] buf = new byte[Adts.MAX_FRAME + 16];
            for (int i = startFrame; i < endFrame; i++) {
                long off = idx.offsets[i];
                int len = (int) (idx.offsets[i + 1] - off);
                if (len <= Adts.HEADER || len > buf.length) continue;
                raf.seek(off);
                raf.readFully(buf, 0, len);
                boolean crc = (buf[1] & 0x01) == 0; // protection_absent = 0 → 2 Byte CRC
                int head = crc ? Adts.HEADER + 2 : Adts.HEADER;
                if (len <= head) continue;
                ByteBuffer data = ByteBuffer.wrap(buf);
                info.set(head, len - head, (long) (i - startFrame) * Adts.SAMPLES_PER_FRAME * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_KEY_FRAME);
                mux.writeSampleData(track, data, info);
            }
            mux.stop();
            ok = true;
        } catch (RuntimeException e) {
            throw new IOException("m4a", e);
        } finally {
            if (mux != null) try { mux.release(); } catch (RuntimeException ignored) { }
            if (!ok) //noinspection ResultOfMethodCallIgnored
                out.delete();
        }
    }
}
