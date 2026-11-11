// JNI: dekoduje bajty JPEG XL do android.graphics.Bitmap (ARGB_8888) przez WŁASNY wrapper nad libjxl
// (JxlDecoder, decode-only). Wywoływane z pl.lewicowyt.notifier.images.NativeJxl.
//
// Dwa tryby:
//   nativeDecode(data)                          — pełne, jednorazowe dekodowanie (parytet z jxl-coder).
//   nativeDecodeProgressive(data, cb)           — dekodowanie PROGRESYWNE: po każdym przebiegu obrazu
//                                                 (JxlDecoderFlushImage) oddaje częściową bitmapę przez
//                                                 callback cb.onPass(Bitmap), a na końcu zwraca pełną.
// Dzięki temu duży obraz pojawia się najpierw w niskiej jakości i ostrzeje w miarę dekodowania/pobierania,
// zamiast czekać na komplet (czego dekoder jxl-coder 2.6.1 nie potrafił — stąd ten moduł).

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "jxl/decode.h"
#include "jxl/encode.h"

#define LOG_TAG "NativeJxl"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// Spójne z resztą pipeline'u obrazów (MAX_IMAGE_DIM): twarde granice anty-DoS.
#define JXL_MAX_DIM 4096u
#define JXL_MAX_PIXELS (16u * 1024u * 1024u)
// T-07: kanały dodatkowe (alfa, głębia…) każą libjxl alokować pamięć roboczą wielokrotnie większą niż bufor RGBA
#define JXL_MAX_EXTRA_CHANNELS 4u
// T-07: druga linia obrony przed nieograniczonym wejściem w trybie strumieniowym (limit Kotlina to 6 MB)
#define JXL_MAX_INPUT_BYTES (16u * 1024u * 1024u)

// Tworzy android.graphics.Bitmap (ARGB_8888) albo NULL. Zwalnia WSZYSTKIE własne local-refy
// (klasy, config), bo w trybie progresywnym wołane w pętli — inaczej tabela local-ref przepełnia się.
static jobject createArgbBitmap(JNIEnv *env, int width, int height) {
    jclass bitmapCls = (*env)->FindClass(env, "android/graphics/Bitmap");
    jclass configCls = (*env)->FindClass(env, "android/graphics/Bitmap$Config");
    jobject result = NULL;
    if (bitmapCls != NULL && configCls != NULL) {
        jmethodID createBitmap = (*env)->GetStaticMethodID(
            env, bitmapCls, "createBitmap",
            "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
        jfieldID argb8888 = (*env)->GetStaticFieldID(
            env, configCls, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
        if (createBitmap != NULL && argb8888 != NULL) {
            jobject config = (*env)->GetStaticObjectField(env, configCls, argb8888);
            result = (*env)->CallStaticObjectMethod(env, bitmapCls, createBitmap, width, height, config);
            // np. OutOfMemoryError z createBitmap: nie zwracaj połowicznego obiektu i wyczyść wyjątek,
            // by kolejne wywołania JNI były zdefiniowane.
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionClear(env);
                if (result != NULL) { (*env)->DeleteLocalRef(env, result); result = NULL; }
            }
            if (config != NULL) (*env)->DeleteLocalRef(env, config);
        }
    }
    if (bitmapCls != NULL) (*env)->DeleteLocalRef(env, bitmapCls);
    if (configCls != NULL) (*env)->DeleteLocalRef(env, configCls);
    return result;
}

// Kopiuje bufor RGBA8 (width*height*4, ciągły) do pikseli bitmapy (z poszanowaniem stride). true = OK.
// libjxl daje PROSTĄ alfę, a Bitmap ARGB_8888 jest PREMULTIPLIED — mnożymy R/G/B przez A, inaczej
// obrazy z przezroczystością renderują się za jasno. Dla pikseli nieprzezroczystych (A=255) to no-op.
static int copyRgbaToBitmap(JNIEnv *env, jobject bitmap, const uint8_t *rgba, uint32_t width, uint32_t height) {
    AndroidBitmapInfo info;
    void *pixels = NULL;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return 0;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || pixels == NULL) return 0;
    const uint32_t rows = height < info.height ? height : info.height;
    const uint32_t cols = width < info.width ? width : info.width;
    const uint32_t srcStride = width * 4u;
    for (uint32_t y = 0; y < rows; y++) {
        const uint8_t *src = rgba + (size_t) y * srcStride;
        uint8_t *dst = (uint8_t *) pixels + (size_t) y * info.stride;
        for (uint32_t x = 0; x < cols; x++) {
            const uint32_t a = src[4u * x + 3u];
            dst[4u * x + 0u] = (uint8_t) ((src[4u * x + 0u] * a + 127u) / 255u);
            dst[4u * x + 1u] = (uint8_t) ((src[4u * x + 1u] * a + 127u) / 255u);
            dst[4u * x + 2u] = (uint8_t) ((src[4u * x + 2u] * a + 127u) / 255u);
            dst[4u * x + 3u] = (uint8_t) a;
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return 1;
}

// Rdzeń: dekoduje `data`/`size` do bitmapy RGBA8. Gdy listener != NULL, włącza tryb progresywny i po każdym
// przebiegu woła listener.onPass(Bitmap). Zwraca finalną bitmapę albo NULL.
static jobject decodeInternal(
    JNIEnv *env, const uint8_t *data, size_t size, jobject listener, jmethodID onPass) {
    JxlDecoder *dec = JxlDecoderCreate(NULL);
    if (dec == NULL) return NULL;

    jobject result = NULL;
    uint8_t *buffer = NULL;      // bufor pikseli RGBA8 (width*height*4)
    size_t bufferSize = 0;
    uint32_t width = 0, height = 0;

    int events = JXL_DEC_BASIC_INFO | JXL_DEC_FULL_IMAGE;
    if (listener != NULL) events |= JXL_DEC_FRAME_PROGRESSION;
    if (JxlDecoderSubscribeEvents(dec, events) != JXL_DEC_SUCCESS) goto cleanup;

    // Zrezygnuj z wielowątkowości (brak runnera) — prostota i brak dodatkowej biblioteki wątków.
    if (JxlDecoderSetInput(dec, data, size) != JXL_DEC_SUCCESS) goto cleanup;
    JxlDecoderCloseInput(dec);

    const JxlPixelFormat format = {4, JXL_TYPE_UINT8, JXL_NATIVE_ENDIAN, 0};

    for (;;) {
        JxlDecoderStatus status = JxlDecoderProcessInput(dec);
        if (status == JXL_DEC_ERROR) {
            LOGW("JxlDecoderProcessInput: ERROR");
            break;
        } else if (status == JXL_DEC_NEED_MORE_INPUT) {
            // całe wejście już podane i zamknięte — brak danych = błąd pliku
            LOGW("need more input (truncated)");
            break;
        } else if (status == JXL_DEC_BASIC_INFO) {
            JxlBasicInfo info;
            if (JxlDecoderGetBasicInfo(dec, &info) != JXL_DEC_SUCCESS) break;
            width = info.xsize;
            height = info.ysize;
            // B-19: xsize/ysize to wymiary PRZED orientacją, a dekoder (keep_orientation = false) oddaje piksele
            // już obrócone. Dla orientacji 5–8 (transpozycja / obrót o 90° lub 270°) wymiary wyjścia są
            // zamienione — bez tego wiersze kopiowane były z krokiem złej szerokości i obraz się „szatkował”.
            if (info.orientation >= JXL_ORIENT_TRANSPOSE) {
                const uint32_t swapped = width;
                width = height;
                height = swapped;
            }
            if (width == 0 || height == 0 || width > JXL_MAX_DIM || height > JXL_MAX_DIM ||
                (uint64_t) width * (uint64_t) height > JXL_MAX_PIXELS ||
                info.num_extra_channels > JXL_MAX_EXTRA_CHANNELS) {
                LOGW("rejected size %ux%u, extra channels %u", width, height, info.num_extra_channels);
                break;
            }
            bufferSize = (size_t) width * (size_t) height * 4u;
            // calloc zamiast malloc: częściowy obraz (wczesny przebieg progresywny) nie pokaże przypadkowej zawartości sterty
            buffer = (uint8_t *) calloc(1, bufferSize);
            if (buffer == NULL) break;
            if (listener != NULL) {
                // kPasses = najbardziej granularne przebiegi (DC → kolejne pasy). Gdy niewspierane, trudno —
                // dostaniemy po prostu jeden obraz końcowy.
                JxlDecoderSetProgressiveDetail(dec, kPasses);
            }
        } else if (status == JXL_DEC_NEED_IMAGE_OUT_BUFFER) {
            if (buffer == NULL) break;
            if (JxlDecoderSetImageOutBuffer(dec, &format, buffer, bufferSize) != JXL_DEC_SUCCESS) break;
        } else if (status == JXL_DEC_FRAME_PROGRESSION) {
            // częściowy obraz gotowy — wypłucz do bufora i oddaj callbackowi jako osobną bitmapę
            if (JxlDecoderFlushImage(dec) == JXL_DEC_SUCCESS && buffer != NULL) {
                jobject partial = createArgbBitmap(env, (int) width, (int) height);
                if (partial != NULL && copyRgbaToBitmap(env, partial, buffer, width, height)) {
                    (*env)->CallVoidMethod(env, listener, onPass, partial);
                }
                if (partial != NULL) (*env)->DeleteLocalRef(env, partial);
                if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); break; }
            }
        } else if (status == JXL_DEC_FULL_IMAGE) {
            // PIERWSZY pełny obraz = wynik; kończymy. To dekoder obrazu statycznego — dalszych klatek
            // animowanego JXL NIE dekodujemy (oszczędza CPU/pamięć; animacje mają osobny tor).
            if (buffer != NULL) {
                jobject full = createArgbBitmap(env, (int) width, (int) height);
                if (full != NULL && copyRgbaToBitmap(env, full, buffer, width, height)) {
                    result = full;
                } else if (full != NULL) {
                    (*env)->DeleteLocalRef(env, full);
                }
            }
            break;
        } else if (status == JXL_DEC_SUCCESS) {
            break;
        } else {
            // nieoczekiwany status — kończymy bezpiecznie
            break;
        }
    }

cleanup:
    if (buffer != NULL) free(buffer);
    JxlDecoderDestroy(dec);
    return result;
}

JNIEXPORT jobject JNICALL
Java_pl_lewicowyt_notifier_images_NativeJxl_nativeDecode(
    JNIEnv *env, jobject thiz, jbyteArray data) {
    (void) thiz;
    const jsize size = (*env)->GetArrayLength(env, data);
    if (size <= 0) return NULL;
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (bytes == NULL) return NULL;
    jobject result = decodeInternal(env, (const uint8_t *) bytes, (size_t) size, NULL, NULL);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    return result;
}

JNIEXPORT jobject JNICALL
Java_pl_lewicowyt_notifier_images_NativeJxl_nativeDecodeProgressive(
    JNIEnv *env, jobject thiz, jbyteArray data, jobject listener) {
    (void) thiz;
    if (listener == NULL) return NULL;
    jclass listenerCls = (*env)->GetObjectClass(env, listener);
    jmethodID onPass = (*env)->GetMethodID(env, listenerCls, "onPass", "(Landroid/graphics/Bitmap;)V");
    if (onPass == NULL) return NULL;
    const jsize size = (*env)->GetArrayLength(env, data);
    if (size <= 0) return NULL;
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (bytes == NULL) return NULL;
    jobject result = decodeInternal(env, (const uint8_t *) bytes, (size_t) size, listener, onPass);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    return result;
}

// Czy to w ogóle strumień JPEG XL (po sygnaturze) — tanie sprawdzenie bez tworzenia dekodera.
JNIEXPORT jboolean JNICALL
Java_pl_lewicowyt_notifier_images_NativeJxl_nativeIsJxl(
    JNIEnv *env, jobject thiz, jbyteArray data) {
    (void) thiz;
    const jsize size = (*env)->GetArrayLength(env, data);
    if (size < 12) return JNI_FALSE;
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (bytes == NULL) return JNI_FALSE;
    JxlSignature sig = JxlSignatureCheck((const uint8_t *) bytes, (size_t) size);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    return (sig == JXL_SIG_CODESTREAM || sig == JXL_SIG_CONTAINER) ? JNI_TRUE : JNI_FALSE;
}

// ============================================================================================================
// DEKODOWANIE STRUMIENIOWE (w trakcie pobierania). Dane podajemy kawałkami (nativeStreamFeed) — dekoder libjxl
// pracuje na tym, co już przyszło, a gdy dotrze do kolejnego przebiegu (najpierw zgrubny obraz DC 1:8, potem
// ostrzejsze), aplikacja może poprosić o podgląd (nativeStreamPreview). Na końcu ten sam dekoder oddaje pełny
// obraz (nativeStreamResult), więc pliku nie trzeba dekodować drugi raz.
// Uchwyt jest używany przez jeden wątek naraz (wołający po stronie Kotlina to zapewnia).
// ============================================================================================================

typedef struct {
    JxlDecoder *dec;
    uint8_t *input;        // niewykorzystana przez dekoder reszta danych + nowe kawałki
    size_t inputLen;
    size_t inputCap;
    uint8_t *buffer;       // piksele RGBA8 (width*height*4)
    size_t bufferSize;
    uint32_t width, height;
    int outBufferSet;      // bufor wyjściowy podany dekoderowi (dopiero wtedy działa JxlDecoderFlushImage)
    int newPass;           // od ostatniego podglądu dekoder dotarł do kolejnego przebiegu
    int state;             // 0 = trwa, 1 = pełny obraz gotowy, -1 = błąd
} JxlStream;

#define STREAM_RUNNING 0
#define STREAM_DONE 1
#define STREAM_FAILED (-1)

JNIEXPORT jlong JNICALL
Java_pl_lewicowyt_notifier_images_NativeJxl_nativeStreamCreate(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    JxlStream *s = (JxlStream *) calloc(1, sizeof(JxlStream));
    if (s == NULL) return 0;
    s->dec = JxlDecoderCreate(NULL);
    if (s->dec == NULL ||
        JxlDecoderSubscribeEvents(s->dec, JXL_DEC_BASIC_INFO | JXL_DEC_FRAME_PROGRESSION | JXL_DEC_FULL_IMAGE) !=
            JXL_DEC_SUCCESS) {
        if (s->dec != NULL) JxlDecoderDestroy(s->dec);
        free(s);
        return 0;
    }
    return (jlong) (intptr_t) s;
}

// Przetwarza bieżące wejście aż do braku danych / końca / błędu. Ustawia s->state i s->newPass.
static void streamProcess(JxlStream *s) {
    const JxlPixelFormat format = {4, JXL_TYPE_UINT8, JXL_NATIVE_ENDIAN, 0};
    for (;;) {
        JxlDecoderStatus status = JxlDecoderProcessInput(s->dec);
        if (status == JXL_DEC_NEED_MORE_INPUT) {
            return; // czekamy na kolejny kawałek (wołający zachowa nieprzetworzoną resztę)
        } else if (status == JXL_DEC_BASIC_INFO) {
            JxlBasicInfo info;
            if (JxlDecoderGetBasicInfo(s->dec, &info) != JXL_DEC_SUCCESS) { s->state = STREAM_FAILED; return; }
            uint32_t w = info.xsize, h = info.ysize;
            if (info.orientation >= JXL_ORIENT_TRANSPOSE) { const uint32_t t = w; w = h; h = t; } // B-19
            if (w == 0 || h == 0 || w > JXL_MAX_DIM || h > JXL_MAX_DIM ||
                (uint64_t) w * (uint64_t) h > JXL_MAX_PIXELS || info.num_extra_channels > JXL_MAX_EXTRA_CHANNELS) {
                LOGW("stream: rejected size %ux%u, extra channels %u", w, h, info.num_extra_channels);
                s->state = STREAM_FAILED;
                return;
            }
            s->width = w;
            s->height = h;
            s->bufferSize = (size_t) w * (size_t) h * 4u;
            s->buffer = (uint8_t *) calloc(1, s->bufferSize); // zera = przezroczyste tło przed pierwszym przebiegiem
            if (s->buffer == NULL) { s->state = STREAM_FAILED; return; }
            JxlDecoderSetProgressiveDetail(s->dec, kPasses);
        } else if (status == JXL_DEC_NEED_IMAGE_OUT_BUFFER) {
            if (s->buffer == NULL ||
                JxlDecoderSetImageOutBuffer(s->dec, &format, s->buffer, s->bufferSize) != JXL_DEC_SUCCESS) {
                s->state = STREAM_FAILED;
                return;
            }
            s->outBufferSet = 1;
        } else if (status == JXL_DEC_FRAME_PROGRESSION) {
            s->newPass = 1; // podgląd zrobi się dopiero na prośbę (nativeStreamPreview) — bez zbędnej pracy
        } else if (status == JXL_DEC_FULL_IMAGE) {
            s->state = STREAM_DONE; // pierwszy pełny obraz = wynik (dekoder obrazu statycznego)
            return;
        } else if (status == JXL_DEC_SUCCESS) {
            if (s->state != STREAM_DONE) s->state = STREAM_FAILED;
            return;
        } else {
            s->state = STREAM_FAILED;
            return;
        }
    }
}

// Dokłada kawałek danych (data[offset, offset+length)) i dekoduje, ile się da. last = to był ostatni kawałek.
// Zwraca: 0 trwa (brak nowego przebiegu), 1 trwa i jest nowy przebieg do podglądu, 2 pełny obraz gotowy, -1 błąd.
JNIEXPORT jint JNICALL
Java_pl_lewicowyt_notifier_images_NativeJxl_nativeStreamFeed(
    JNIEnv *env, jobject thiz, jlong handle, jbyteArray data, jint offset, jint length, jboolean last) {
    (void) thiz;
    JxlStream *s = (JxlStream *) (intptr_t) handle;
    if (s == NULL) return -1;
    if (s->state == STREAM_DONE) return 2;
    if (s->state == STREAM_FAILED) return -1;
    if (length < 0 || offset < 0) return -1;
    if (length > 0) {
        if ((size_t) length > JXL_MAX_INPUT_BYTES || s->inputLen > JXL_MAX_INPUT_BYTES - (size_t) length) {
            s->state = STREAM_FAILED;
            return -1;
        }
        const size_t need = s->inputLen + (size_t) length;
        if (need > s->inputCap) {
            size_t cap = s->inputCap == 0 ? 65536u : s->inputCap;
            while (cap < need) cap *= 2u;
            uint8_t *grown = (uint8_t *) realloc(s->input, cap);
            if (grown == NULL) { s->state = STREAM_FAILED; return -1; }
            s->input = grown;
            s->inputCap = cap;
        }
        (*env)->GetByteArrayRegion(env, data, offset, length, (jbyte *) (s->input + s->inputLen));
        if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); s->state = STREAM_FAILED; return -1; }
        s->inputLen = need;
    }
    if (JxlDecoderSetInput(s->dec, s->input, s->inputLen) != JXL_DEC_SUCCESS) { s->state = STREAM_FAILED; return -1; }
    if (last) JxlDecoderCloseInput(s->dec);
    streamProcess(s);
    // dekoder trzyma wskaźnik do wejścia tylko do ReleaseInput — resztę nieprzetworzoną przesuwamy na początek
    const size_t remaining = JxlDecoderReleaseInput(s->dec);
    if (remaining > 0 && remaining <= s->inputLen) {
        memmove(s->input, s->input + (s->inputLen - remaining), remaining);
        s->inputLen = remaining;
    } else {
        s->inputLen = 0;
    }
    if (s->state == STREAM_DONE) return 2;
    if (s->state == STREAM_FAILED) return -1;
    if (last) { s->state = STREAM_FAILED; return -1; } // całe dane podane, a obrazu brak = plik ucięty/uszkodzony
    return s->newPass ? 1 : 0;
}

// Wymiary obrazu spakowane (szerokość << 32 | wysokość); 0 = jeszcze nieznane.
JNIEXPORT jlong JNICALL
Java_pl_lewicowyt_notifier_images_NativeJxl_nativeStreamSize(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env; (void) thiz;
    JxlStream *s = (JxlStream *) (intptr_t) handle;
    if (s == NULL || s->width == 0) return 0;
    return ((jlong) s->width << 32) | (jlong) s->height;
}

// Podgląd częściowego obrazu (po ostatnim przebiegu) jako nowa bitmapa albo NULL.
JNIEXPORT jobject JNICALL
Java_pl_lewicowyt_notifier_images_NativeJxl_nativeStreamPreview(JNIEnv *env, jobject thiz, jlong handle) {
    (void) thiz;
    JxlStream *s = (JxlStream *) (intptr_t) handle;
    if (s == NULL || s->state != STREAM_RUNNING || !s->outBufferSet || s->buffer == NULL) return NULL;
    if (JxlDecoderFlushImage(s->dec) != JXL_DEC_SUCCESS) return NULL;
    s->newPass = 0;
    jobject bitmap = createArgbBitmap(env, (int) s->width, (int) s->height);
    if (bitmap != NULL && !copyRgbaToBitmap(env, bitmap, s->buffer, s->width, s->height)) {
        (*env)->DeleteLocalRef(env, bitmap);
        return NULL;
    }
    return bitmap;
}

// Pełny obraz (gdy nativeStreamFeed zwrócił 2) albo NULL.
JNIEXPORT jobject JNICALL
Java_pl_lewicowyt_notifier_images_NativeJxl_nativeStreamResult(JNIEnv *env, jobject thiz, jlong handle) {
    (void) thiz;
    JxlStream *s = (JxlStream *) (intptr_t) handle;
    if (s == NULL || s->state != STREAM_DONE || s->buffer == NULL) return NULL;
    jobject bitmap = createArgbBitmap(env, (int) s->width, (int) s->height);
    if (bitmap != NULL && !copyRgbaToBitmap(env, bitmap, s->buffer, s->width, s->height)) {
        (*env)->DeleteLocalRef(env, bitmap);
        return NULL;
    }
    return bitmap;
}

JNIEXPORT void JNICALL
Java_pl_lewicowyt_notifier_images_NativeJxl_nativeStreamDestroy(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env; (void) thiz;
    JxlStream *s = (JxlStream *) (intptr_t) handle;
    if (s == NULL) return;
    if (s->dec != NULL) JxlDecoderDestroy(s->dec);
    free(s->input);
    free(s->buffer);
    free(s);
}

// ============================================================================================================
// WYMIARY BEZ DEKODOWANIA PIKSELI (zastępuje JxlCoder.getSize na nieufnych bajtach).
// Zwraca (szerokość << 32 | wysokość) PO orientacji albo 0, gdy to nie JXL / nagłówek niepełny / wymiary poza
// limitami (JXL_MAX_DIM, JXL_MAX_PIXELS, JXL_MAX_EXTRA_CHANNELS). Dekoder czyta tylko nagłówek.
// ============================================================================================================
JNIEXPORT jlong JNICALL
Java_pl_lewicowyt_notifier_images_NativeJxl_nativeGetSize(JNIEnv *env, jobject thiz, jbyteArray data) {
    (void) thiz;
    const jsize size = (*env)->GetArrayLength(env, data);
    if (size < 12 || (uint32_t) size > JXL_MAX_INPUT_BYTES) return 0;
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (bytes == NULL) return 0;
    jlong packed = 0;
    JxlDecoder *dec = JxlDecoderCreate(NULL);
    if (dec != NULL) {
        if (JxlDecoderSubscribeEvents(dec, JXL_DEC_BASIC_INFO) == JXL_DEC_SUCCESS &&
            JxlDecoderSetInput(dec, (const uint8_t *) bytes, (size_t) size) == JXL_DEC_SUCCESS) {
            JxlDecoderCloseInput(dec);
            if (JxlDecoderProcessInput(dec) == JXL_DEC_BASIC_INFO) {
                JxlBasicInfo info;
                if (JxlDecoderGetBasicInfo(dec, &info) == JXL_DEC_SUCCESS) {
                    uint32_t w = info.xsize, h = info.ysize;
                    if (info.orientation >= JXL_ORIENT_TRANSPOSE) { const uint32_t t = w; w = h; h = t; }
                    if (w != 0 && h != 0 && w <= JXL_MAX_DIM && h <= JXL_MAX_DIM &&
                        (uint64_t) w * (uint64_t) h <= JXL_MAX_PIXELS &&
                        info.num_extra_channels <= JXL_MAX_EXTRA_CHANNELS) {
                        packed = (jlong) (((uint64_t) w << 32) | (uint64_t) h);
                    }
                }
            }
        }
        JxlDecoderDestroy(dec);
    }
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    return packed;
}

// ============================================================================================================
// KODOWANIE (rekompresja do cache, eksport do galerii) — zastępuje JxlCoder.encode. Bitmap ARGB_8888 -> RGB
// (bez alfy, jak dotąd), kodowanie stratne (distance z jakości 1..100), effort 1..10 (1 = najszybciej).
// Wejście to bitmapa już zdekodowana przez nasze dekodery z limitami, więc rozmiar jest sprawdzany tu tylko jako
// druga linia obrony. Zwraca bajty JXL albo NULL. Enkoder działa na zaufanych (już zdekodowanych) pikselach.
// ============================================================================================================
JNIEXPORT jbyteArray JNICALL
Java_pl_lewicowyt_notifier_images_NativeJxl_nativeEncode(
    JNIEnv *env, jobject thiz, jobject bitmap, jint quality, jint effort) {
    (void) thiz;
    if (bitmap == NULL) return NULL;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width == 0 || info.height == 0 ||
        info.width > JXL_MAX_DIM || info.height > JXL_MAX_DIM ||
        (uint64_t) info.width * (uint64_t) info.height > JXL_MAX_PIXELS) {
        return NULL;
    }
    if (quality < 1) quality = 1;
    if (quality > 100) quality = 100;
    if (effort < 1) effort = 1;
    if (effort > 10) effort = 10;

    const size_t rgbSize = (size_t) info.width * (size_t) info.height * 3u;
    uint8_t *rgb = (uint8_t *) malloc(rgbSize);
    if (rgb == NULL) return NULL;
    void *pixels = NULL;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || pixels == NULL) {
        free(rgb);
        return NULL;
    }
    for (uint32_t y = 0; y < info.height; y++) {
        const uint8_t *src = (const uint8_t *) pixels + (size_t) y * info.stride;
        uint8_t *dst = rgb + (size_t) y * info.width * 3u;
        for (uint32_t x = 0; x < info.width; x++) {
            dst[3u * x + 0u] = src[4u * x + 0u];
            dst[3u * x + 1u] = src[4u * x + 1u];
            dst[3u * x + 2u] = src[4u * x + 2u];
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);

    jbyteArray result = NULL;
    uint8_t *out = NULL;
    JxlEncoder *enc = JxlEncoderCreate(NULL);
    if (enc == NULL) goto done;
    {
        JxlBasicInfo bi;
        JxlEncoderInitBasicInfo(&bi);
        bi.xsize = info.width;
        bi.ysize = info.height;
        bi.bits_per_sample = 8;
        bi.num_color_channels = 3;
        bi.num_extra_channels = 0;
        bi.alpha_bits = 0;
        bi.uses_original_profile = JXL_FALSE; // stratne (XYB)
        if (JxlEncoderSetBasicInfo(enc, &bi) != JXL_ENC_SUCCESS) goto done;
        JxlColorEncoding color;
        JxlColorEncodingSetToSRGB(&color, JXL_FALSE);
        if (JxlEncoderSetColorEncoding(enc, &color) != JXL_ENC_SUCCESS) goto done;
        JxlEncoderFrameSettings *fs = JxlEncoderFrameSettingsCreate(enc, NULL);
        if (fs == NULL) goto done;
        if (JxlEncoderFrameSettingsSetOption(fs, JXL_ENC_FRAME_SETTING_EFFORT, effort) != JXL_ENC_SUCCESS) goto done;
        if (JxlEncoderSetFrameLossless(fs, JXL_FALSE) != JXL_ENC_SUCCESS) goto done;
        if (JxlEncoderSetFrameDistance(fs, JxlEncoderDistanceFromQuality((float) quality)) != JXL_ENC_SUCCESS) goto done;
        const JxlPixelFormat format = {3, JXL_TYPE_UINT8, JXL_NATIVE_ENDIAN, 0};
        if (JxlEncoderAddImageFrame(fs, &format, rgb, rgbSize) != JXL_ENC_SUCCESS) goto done;
        JxlEncoderCloseInput(enc);

        size_t cap = 64u * 1024u, len = 0;
        out = (uint8_t *) malloc(cap);
        if (out == NULL) goto done;
        for (;;) {
            uint8_t *next = out + len;
            size_t avail = cap - len;
            JxlEncoderStatus st = JxlEncoderProcessOutput(enc, &next, &avail);
            len = (size_t) (next - out);
            if (st == JXL_ENC_NEED_MORE_OUTPUT) {
                if (cap > (64u * 1024u * 1024u)) goto done; // wynik absurdalnie duży — porzuć
                cap *= 2u;
                uint8_t *grown = (uint8_t *) realloc(out, cap);
                if (grown == NULL) goto done;
                out = grown;
                continue;
            }
            if (st != JXL_ENC_SUCCESS) goto done;
            break;
        }
        result = (*env)->NewByteArray(env, (jsize) len);
        if (result != NULL) (*env)->SetByteArrayRegion(env, result, 0, (jsize) len, (const jbyte *) out);
    }
done:
    if (enc != NULL) JxlEncoderDestroy(enc);
    free(out);
    free(rgb);
    return result;
}
