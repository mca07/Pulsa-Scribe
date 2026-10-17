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

import de.mca.pulsascribe.core.Todoist;

/**
 * Todoist-Token: wie der Gemini-Schlüssel AES-256-GCM mit eigenem, nicht exportierbarem Keystore-Schlüssel
 * und eigener Einstellungsdatei (Löschen des einen berührt den anderen nicht). Nicht in der Datensicherung.
 */
final class TodoistToken {
    private TodoistToken() {}

    private static final String ALIAS = "pulsa_scribe_todoist_v1";
    private static final String PREFS = "pulsa_scribe_todoist";
    private static final String K_DATA = "ct", K_IV = "iv", K_PROJECT = "project";

    static boolean has(Context c) {
        return get(c) != null;
    }

    static String get(Context c) {
        SharedPreferences p = prefs(c);
        String data = p.getString(K_DATA, null), iv = p.getString(K_IV, null);
        if (data == null || iv == null) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            String k = new String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), StandardCharsets.UTF_8);
            return Todoist.validToken(k) ? k : null;
        } catch (Exception e) {
            return null;
        }
    }

    static boolean set(Context c, String k) {
        if (!Todoist.validToken(k)) return false;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            byte[] ct = cipher.doFinal(k.getBytes(StandardCharsets.UTF_8));
            return prefs(c).edit()
                    .putString(K_DATA, Base64.encodeToString(ct, Base64.NO_WRAP))
                    .putString(K_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .remove(K_PROJECT)
                    .commit();
        } catch (Exception e) {
            return false;
        }
    }

    static void delete(Context c) {
        prefs(c).edit().clear().commit();
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            ks.deleteEntry(ALIAS);
        } catch (Exception ignored) { }
    }

    /** Zwischengespeicherte ID des Projekts "Pulsa Scribe". */
    static String project(Context c) {
        String p = prefs(c).getString(K_PROJECT, "");
        return Todoist.validId(p) ? p : "";
    }

    static void setProject(Context c, String id) {
        prefs(c).edit().putString(K_PROJECT, Todoist.validId(id) ? id : "").apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey key(boolean create) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            KeyStore.Entry e = ks.getEntry(ALIAS, null);
            if (e instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) e).getSecretKey();
            ks.deleteEntry(ALIAS);
        }
        if (!create) throw new IllegalStateException("kein Schlüssel");
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }
}
