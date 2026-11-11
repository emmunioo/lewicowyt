// JNI: koduje android.graphics.Bitmap (ARGB_8888) do JPEG enkoderem MozJPEG.
// Wywoływane z pl.lewicowyt.notifier.images.MozJpeg.nativeEncode(bitmap, quality, effort).
//
// „moc kompresji" (effort 0..100) mapujemy na opcje mozjpeg:
//   < 34  : baseline + zoptymalizowany Huffman (najszybciej),
//   34..66: + progresywny (mniejsze pliki),
//   >= 67 : + trellis (pełny mozjpeg, najmniejsze pliki, najwolniej).
// Jakość 1..100 jak w libjpeg. Wejście czytamy jako RGBA (JCS_EXT_RGBA) — bez ręcznej konwersji.

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <setjmp.h>
#include <stdlib.h>
#include <string.h>

#include "jpeglib.h"

#define LOG_TAG "MozJpegNative"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// F-07/F-23: domyślny error_exit libjpeg woła exit() → UBIJA CAŁY PROCES aplikacji przy błędzie
// (np. wymiary > 65500 px). Zastępujemy go longjmp, żeby błąd kodowania kończył się czystym NULL-em.
struct lewicowyt_error_mgr {
    struct jpeg_error_mgr pub;
    jmp_buf setjmp_buffer;
};

static void lewicowyt_error_exit(j_common_ptr cinfo) {
    struct lewicowyt_error_mgr *err = (struct lewicowyt_error_mgr *) cinfo->err;
    longjmp(err->setjmp_buffer, 1);
}

JNIEXPORT jbyteArray JNICALL
Java_pl_lewicowyt_notifier_images_MozJpeg_nativeEncode(
    JNIEnv *env, jobject thiz, jobject bitmap, jint quality, jint effort) {
    (void) thiz;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGW("getInfo failed");
        return NULL;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width == 0 || info.height == 0) {
        LOGW("unsupported bitmap format=%d", info.format);
        return NULL;
    }

    void *pixels = NULL;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || pixels == NULL) {
        LOGW("lockPixels failed");
        return NULL;
    }

    struct jpeg_compress_struct cinfo;
    struct lewicowyt_error_mgr jerr;
    // L-11: volatile — zmienna jest ustawiana PO setjmp (w jpeg_mem_dest) i czytana w handlerze błędu
    // (free), więc bez volatile jej wartość po longjmp jest nieokreślona (ryzyko wycieku / stale pointer).
    unsigned char *volatile outbuffer = NULL;   // alokowane przez libjpeg (jpeg_mem_dest), zwalniane free()
    unsigned long outsize = 0;
    jbyteArray result = NULL;
    // B-20: od startu kompresji libjpeg może POWIĘKSZAĆ bufor wyjściowy — zwalnia wtedy stary (wskazywany
    // przez `outbuffer`) i trzyma nowy tylko u siebie, a `outbuffer` aktualizuje dopiero jpeg_finish_compress.
    // Zwolnienie `outbuffer` w ścieżce błędu po starcie byłoby podwójnym free — wtedy wolimy rzadki wyciek.
    volatile int compressStarted = 0;
    volatile int cinfoCreated = 0; // T-06: czy jpeg_create_compress zdążył się wykonać (od tego zależy jpeg_destroy_compress)
    // bufor jednego wiersza po nałożeniu alfy na białe tło (zwalniany także w ścieżce błędu)
    unsigned char *volatile rowbuf = (unsigned char *) malloc((size_t) info.width * 4u);
    if (rowbuf == NULL) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return NULL;
    }

    cinfo.err = jpeg_std_error(&jerr.pub);
    jerr.pub.error_exit = lewicowyt_error_exit; // błąd → longjmp, nie exit() (F-07/F-23)

    // punkt powrotu przy błędzie libjpeg — MUSI istnieć, zanim libjpeg dostanie szansę zgłosić błąd (T-06: dawniej
    // setjmp stał po jpeg_create_compress, więc błąd w samym tworzeniu robił longjmp do niezainicjowanego bufora);
    // sprzątamy zasoby i zwracamy NULL zamiast ubijać proces
    if (setjmp(jerr.setjmp_buffer)) {
        if (cinfoCreated) jpeg_destroy_compress(&cinfo);
        if (outbuffer != NULL && !compressStarted) {
            free(outbuffer);
        }
        if (rowbuf != NULL) free(rowbuf);
        AndroidBitmap_unlockPixels(env, bitmap);
        return NULL;
    }

    jpeg_create_compress(&cinfo);
    cinfoCreated = 1;

    jpeg_mem_dest(&cinfo, (unsigned char **) &outbuffer, &outsize); // rzutowanie zdejmuje volatile na czas zapisu przez libjpeg

    cinfo.image_width = info.width;
    cinfo.image_height = info.height;
    cinfo.input_components = 4;
    cinfo.in_color_space = JCS_EXT_RGBA; // rozszerzenie libjpeg-turbo/mozjpeg: bezpośrednio RGBA

    jpeg_set_defaults(&cinfo);
    jpeg_set_quality(&cinfo, quality < 1 ? 1 : (quality > 100 ? 100 : quality), TRUE);
    cinfo.optimize_coding = TRUE; // zoptymalizowany Huffman — tanio i zawsze pomaga

    // opcje mozjpeg wg „mocy". Domyślnie mozjpeg włącza trellis+progresję; tu sterujemy tym jawnie.
    jpeg_c_set_bool_param(&cinfo, JBOOLEAN_TRELLIS_QUANT, effort >= 67 ? TRUE : FALSE);
    jpeg_c_set_bool_param(&cinfo, JBOOLEAN_TRELLIS_QUANT_DC, effort >= 67 ? TRUE : FALSE);
    jpeg_c_set_bool_param(&cinfo, JBOOLEAN_OPTIMIZE_SCANS, effort >= 34 ? TRUE : FALSE);
    if (effort >= 34) {
        jpeg_simple_progression(&cinfo); // progresywny JPEG (mniejszy)
    }

    compressStarted = 1;
    jpeg_start_compress(&cinfo, TRUE);

    const uint32_t stride = info.stride;
    unsigned char *base = (unsigned char *) pixels;
    while (cinfo.next_scanline < cinfo.image_height) {
        unsigned char *src = base + (size_t) cinfo.next_scanline * stride;
        // B-90: bitmapa ARGB_8888 jest PREMULTIPLIED, a JPEG nie ma alfy — półprzezroczyste piksele wychodziły
        // przyciemnione. Nakładamy je na białe tło: (premult) c + (255 − a). Nieprzezroczyste (a=255) bez zmian.
        for (uint32_t x = 0; x < info.width; x++) {
            unsigned char *px = rowbuf + 4u * x;
            const unsigned char *in = src + 4u * x;
            const unsigned int add = 255u - in[3];
            px[0] = (unsigned char) (in[0] + add > 255u ? 255u : in[0] + add);
            px[1] = (unsigned char) (in[1] + add > 255u ? 255u : in[1] + add);
            px[2] = (unsigned char) (in[2] + add > 255u ? 255u : in[2] + add);
            px[3] = 255u;
        }
        JSAMPROW row = (JSAMPROW) rowbuf;
        jpeg_write_scanlines(&cinfo, &row, 1);
    }
    jpeg_finish_compress(&cinfo);
    free(rowbuf);
    rowbuf = NULL;

    AndroidBitmap_unlockPixels(env, bitmap);

    if (outbuffer != NULL && outsize > 0) {
        result = (*env)->NewByteArray(env, (jsize) outsize);
        if (result != NULL) {
            (*env)->SetByteArrayRegion(env, result, 0, (jsize) outsize, (const jbyte *) outbuffer);
        }
    }

    jpeg_destroy_compress(&cinfo);
    if (outbuffer != NULL) {
        free(outbuffer);
    }
    return result;
}
