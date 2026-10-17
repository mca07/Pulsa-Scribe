// Verbindung zwischen Pulsa Scribe (Java) und whisper.cpp.
// Eingaben: Modellpfad (nur aus dem privaten App-Ordner), PCM 16 kHz mono float, Sprachcode aus fester Liste.
// Ausgabe: JSON als UTF-8-Bytes [{"t":Hundertstelsekunden,"x":"Text"},…]; Java prüft alles erneut.
#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdbool.h>
#include <stdatomic.h>
#include "whisper.h"

#define TAG "ScribeWhisper"

static atomic_int g_abort = 0;
static atomic_int g_progress = 0;

static bool abort_cb(void * data) {
    (void) data;
    return atomic_load(&g_abort) != 0;
}

static void progress_cb(struct whisper_context * ctx, struct whisper_state * state, int progress, void * data) {
    (void) ctx; (void) state; (void) data;
    atomic_store(&g_progress, progress);
}

JNIEXPORT jlong JNICALL
Java_de_mca_pulsascribe_Whisper_nInit(JNIEnv * env, jclass cls, jstring path) {
    (void) cls;
    const char * p = (*env)->GetStringUTFChars(env, path, NULL);
    if (p == NULL) return 0;
    struct whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    struct whisper_context * ctx = whisper_init_from_file_with_params(p, cp);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT void JNICALL
Java_de_mca_pulsascribe_Whisper_nFree(JNIEnv * env, jclass cls, jlong ptr) {
    (void) env; (void) cls;
    if (ptr != 0) whisper_free((struct whisper_context *) (intptr_t) ptr);
}

JNIEXPORT void JNICALL
Java_de_mca_pulsascribe_Whisper_nAbort(JNIEnv * env, jclass cls, jboolean on) {
    (void) env; (void) cls;
    atomic_store(&g_abort, on ? 1 : 0);
}

JNIEXPORT jint JNICALL
Java_de_mca_pulsascribe_Whisper_nProgress(JNIEnv * env, jclass cls) {
    (void) env; (void) cls;
    return atomic_load(&g_progress);
}

// Hängt s JSON-sicher an buf an (wächst bei Bedarf). Rückgabe false bei Speichermangel.
static bool append(char ** buf, size_t * len, size_t * cap, const char * s, size_t n) {
    if (*len + n + 1 > *cap) {
        size_t nc = (*cap) * 2 + n + 64;
        char * nb = realloc(*buf, nc);
        if (nb == NULL) return false;
        *buf = nb;
        *cap = nc;
    }
    memcpy(*buf + *len, s, n);
    *len += n;
    (*buf)[*len] = 0;
    return true;
}

static bool append_escaped(char ** buf, size_t * len, size_t * cap, const char * s) {
    char tmp[8];
    for (const unsigned char * c = (const unsigned char *) s; *c; c++) {
        bool ok;
        if (*c == '"' || *c == '\\') { tmp[0] = '\\'; tmp[1] = (char) *c; ok = append(buf, len, cap, tmp, 2); }
        else if (*c < 0x20) { snprintf(tmp, sizeof tmp, "\\u%04x", *c); ok = append(buf, len, cap, tmp, 6); }
        else ok = append(buf, len, cap, (const char *) c, 1);
        if (!ok) return false;
    }
    return true;
}

JNIEXPORT jbyteArray JNICALL
Java_de_mca_pulsascribe_Whisper_nTranscribe(JNIEnv * env, jclass cls, jlong ptr, jfloatArray pcm, jstring lang, jint threads) {
    (void) cls;
    struct whisper_context * ctx = (struct whisper_context *) (intptr_t) ptr;
    if (ctx == NULL || pcm == NULL) return NULL;
    jsize n = (*env)->GetArrayLength(env, pcm);
    if (n <= 0) return NULL;
    jfloat * data = (*env)->GetFloatArrayElements(env, pcm, NULL);
    if (data == NULL) return NULL;
    const char * l = lang == NULL ? NULL : (*env)->GetStringUTFChars(env, lang, NULL);

    struct whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads < 1 ? 1 : (threads > 8 ? 8 : threads);
    p.print_realtime = false;
    p.print_progress = false;
    p.print_timestamps = false;
    p.print_special = false;
    p.translate = false;
    p.no_context = false;
    p.single_segment = false;
    p.language = (l != NULL && strcmp(l, "auto") != 0) ? l : "auto";
    p.detect_language = false;
    p.suppress_blank = true;
    p.abort_callback = abort_cb;
    p.abort_callback_user_data = NULL;
    p.progress_callback = progress_cb;
    p.progress_callback_user_data = NULL;
    atomic_store(&g_progress, 0);

    int rc = whisper_full(ctx, p, data, n);
    (*env)->ReleaseFloatArrayElements(env, pcm, data, JNI_ABORT);

    jbyteArray out = NULL;
    if (rc == 0 && atomic_load(&g_abort) == 0) {
        size_t cap = 4096, len = 0;
        char * buf = malloc(cap);
        bool ok = buf != NULL;
        if (ok) { buf[0] = 0; ok = append(&buf, &len, &cap, "[", 1); }
        int segs = whisper_full_n_segments(ctx);
        for (int i = 0; ok && i < segs; i++) {
            char head[48];
            int64_t t0 = whisper_full_get_segment_t0(ctx, i);
            int hn = snprintf(head, sizeof head, "%s{\"t\":%lld,\"x\":\"", i > 0 ? "," : "", (long long) t0);
            ok = append(&buf, &len, &cap, head, (size_t) hn)
                && append_escaped(&buf, &len, &cap, whisper_full_get_segment_text(ctx, i))
                && append(&buf, &len, &cap, "\"}", 2);
        }
        if (ok) ok = append(&buf, &len, &cap, "]", 1);
        if (ok) {
            out = (*env)->NewByteArray(env, (jsize) len);
            if (out != NULL) (*env)->SetByteArrayRegion(env, out, 0, (jsize) len, (const jbyte *) buf);
        }
        free(buf);
    } else {
        __android_log_print(ANDROID_LOG_WARN, TAG, "whisper_full rc=%d", rc);
    }
    if (l != NULL) (*env)->ReleaseStringUTFChars(env, lang, l);
    return out;
}
