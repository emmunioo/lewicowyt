// JNI: dekoduje bajty AVIF do android.graphics.Bitmap (ARGB_8888) przez libavif + aom (decode-only).
// Wywoływane z pl.lewicowyt.notifier.images.AvifDec.nativeDecode(data).
// Potrzebne tylko na Androidzie < 12 — od API 31 AVIF dekoduje platforma.

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <string.h>

#include "avif/avif.h"

#define LOG_TAG "AvifDecNative"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// Tworzy android.graphics.Bitmap (ARGB_8888) o zadanych wymiarach albo NULL.
static jobject createArgbBitmap(JNIEnv *env, int width, int height) {
    jclass bitmapCls = (*env)->FindClass(env, "android/graphics/Bitmap");
    jclass configCls = (*env)->FindClass(env, "android/graphics/Bitmap$Config");
    if (bitmapCls == NULL || configCls == NULL) return NULL;

    jmethodID createBitmap = (*env)->GetStaticMethodID(
        env, bitmapCls, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jfieldID argb8888 = (*env)->GetStaticFieldID(
        env, configCls, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    if (createBitmap == NULL || argb8888 == NULL) return NULL;

    jobject config = (*env)->GetStaticObjectField(env, configCls, argb8888);
    jobject result = (*env)->CallStaticObjectMethod(env, bitmapCls, createBitmap, width, height, config);
    if ((*env)->ExceptionCheck(env)) { // np. OutOfMemoryError — nie zostawiamy wiszącego wyjątku
        (*env)->ExceptionClear(env);
        return NULL;
    }
    return result;
}

JNIEXPORT jobject JNICALL
Java_pl_lewicowyt_notifier_images_AvifDec_nativeDecode(
    JNIEnv *env, jobject thiz, jbyteArray data) {
    (void) thiz;

    const jsize size = (*env)->GetArrayLength(env, data);
    if (size <= 0) return NULL;
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (bytes == NULL) return NULL;

    jobject resultBitmap = NULL;
    avifDecoder *decoder = avifDecoderCreate();
    if (decoder == NULL) {
        (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
        return NULL;
    }
    decoder->maxThreads = 1;
    decoder->ignoreExif = AVIF_TRUE;
    decoder->ignoreXMP = AVIF_TRUE;
    // F-07: twarde limity rozmiaru — mały plik AVIF nie zmusi dekodera do alokacji gigapikseli.
    // 16 Mpx łącznie oraz 4096 px na bok (spójne z limitem po stronie Kotlina).
    decoder->imageSizeLimit = 16u * 1024u * 1024u;
    decoder->imageDimensionLimit = 4096u;
    // Animowane AVIF: nie więcej niż 500 klatek (nieufny plik nie może rozdąć parsowania/pamięci); jeden wątek.
    decoder->imageCountLimit = 500u;

    avifRGBImage rgb;
    memset(&rgb, 0, sizeof(rgb));

    if (avifDecoderSetIOMemory(decoder, (const uint8_t *) bytes, (size_t) size) != AVIF_RESULT_OK) {
        LOGW("setIOMemory failed");
        goto cleanup;
    }
    if (avifDecoderParse(decoder) != AVIF_RESULT_OK) {
        LOGW("parse failed");
        goto cleanup;
    }
    if (avifDecoderNextImage(decoder) != AVIF_RESULT_OK) {
        LOGW("nextImage failed");
        goto cleanup;
    }

    avifRGBImageSetDefaults(&rgb, decoder->image);
    rgb.format = AVIF_RGB_FORMAT_RGBA;
    rgb.depth = 8;
    /* A-01: Bitmap ARGB_8888 na Androidzie jest PREMULTIPLIED — prosimy libavif o premultiplikowaną alfę,
     * inaczej półprzezroczyste krawędzie byłyby zbyt jasne (jak R-17 dla JXL). */
    rgb.alphaPremultiplied = AVIF_TRUE;
    if (avifRGBImageAllocatePixels(&rgb) != AVIF_RESULT_OK) {
        LOGW("allocatePixels failed");
        goto cleanup;
    }
    if (avifImageYUVToRGB(decoder->image, &rgb) != AVIF_RESULT_OK) {
        LOGW("YUVToRGB failed");
        goto cleanup;
    }

    resultBitmap = createArgbBitmap(env, (int) rgb.width, (int) rgb.height);
    if (resultBitmap == NULL) {
        goto cleanup;
    }

    {
        void *pixels = NULL;
        AndroidBitmapInfo info;
        if (AndroidBitmap_getInfo(env, resultBitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            AndroidBitmap_lockPixels(env, resultBitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
            pixels == NULL) {
            LOGW("lockPixels failed");
            resultBitmap = NULL;
        } else {
            const uint32_t rows = rgb.height < info.height ? rgb.height : info.height;
            const uint32_t srcRow = rgb.rowBytes;
            const uint32_t copyBytes = (srcRow < info.stride ? srcRow : info.stride);
            for (uint32_t y = 0; y < rows; y++) {
                memcpy((uint8_t *) pixels + (size_t) y * info.stride,
                       rgb.pixels + (size_t) y * srcRow, copyBytes);
            }
            AndroidBitmap_unlockPixels(env, resultBitmap);
        }
    }

cleanup:
    avifRGBImageFreePixels(&rgb);
    avifDecoderDestroy(decoder);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    return resultBitmap;
}
