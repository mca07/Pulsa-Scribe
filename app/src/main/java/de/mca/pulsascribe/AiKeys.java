package de.mca.pulsascribe;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import de.mca.pulsascribe.core.Ai;

/**
 * API-Schlüssel der weiteren KI-Anbieter (Claude, OpenAI, Mistral): je Anbieter eigener, nicht exportierbarer
 * Keystore-Schlüssel (AES-256-GCM) und eigene Einstellungsdatei. Gemini bleibt in GeminiKey.
 */
final class AiKeys {
    private AiKeys() {}

    private static String alias(String p) { return "pulsa_scribe_ai_" + Ai.clean(p) + "_v1"; }

    private static SharedPreferences prefs(Context c, String p) {
        return c.getSharedPreferences("pulsa_scribe_ai_" + Ai.clean(p), Context.MODE_PRIVATE);
    }

    /** Aktiver Anbieter aus den Einstellungen. */
    static String provider(Context c) {
        return Settings.provider(c);
    }

    /** Schlüssel des Anbieters vorhanden? */
    static boolean has(Context c, String p) {
        return get(c, p) != null;
    }

    /** Ist der aktive Anbieter einsatzbereit (Schlüssel vorhanden)? */
    static boolean ready(Context c) {
        return has(c, provider(c));
    }

    static String get(Context c, String p) {
        String id = Ai.clean(p);
        if (Ai.GEMINI.equals(id)) return GeminiKey.get(c);
        SharedPreferences sp = prefs(c, id);
        String data = sp.getString("ct", null), iv = sp.getString("iv", null);
        if (data == null || iv == null) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(id, false), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            String k = new String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), StandardCharsets.UTF_8);
            return Ai.validKey(id, k) ? k : null;
        } catch (Exception e) {
            return null;
        }
    }

    static boolean set(Context c, String p, String k) {
        String id = Ai.clean(p);
        if (Ai.GEMINI.equals(id)) return GeminiKey.set(c, k);
        if (!Ai.validKey(id, k)) return false;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(id, true));
            byte[] ct = cipher.doFinal(k.getBytes(StandardCharsets.UTF_8));
            return prefs(c, id).edit()
                    .putString("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
                    .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .commit();
        } catch (Exception e) {
            return false;
        }
    }

    static void delete(Context c, String p) {
        String id = Ai.clean(p);
        if (Ai.GEMINI.equals(id)) { GeminiKey.delete(c); return; }
        prefs(c, id).edit().clear().commit();
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            ks.deleteEntry(alias(id));
        } catch (Exception ignored) { }
    }

    private static SecretKey key(String p, boolean create) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        String a = alias(p);
        if (ks.containsAlias(a)) {
            KeyStore.Entry e = ks.getEntry(a, null);
            if (e instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) e).getSecretKey();
            ks.deleteEntry(a);
        }
        if (!create) throw new IllegalStateException("kein Schlüssel");
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(a, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }
}
