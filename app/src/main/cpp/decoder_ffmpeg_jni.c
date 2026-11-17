/**
 * JNI del decoder de video por software FFmpeg (E7-T12).
 *
 * Contrato con DecoderFfmpeg (Kotlin):
 * - nativeCreateDecoder(codecName): abre el decoder libavcodec; 0 = fallo.
 * - nativeSendPacket: envía un AU completo (Media3 entrega AUs del demuxer).
 *   Con buffer nulo señala fin de stream (drain).
 * - nativeReceiveFrame: extrae un frame; llena info = [ancho, alto,
 *   colorspace] y pts[0]. Códigos: 1 = frame, 0 = EAGAIN (sin salida),
 *   -100 = EOF drenado, -200 = formato de pixel no soportado,
 *   < 0 (AVERROR) = error del decodificador, -1 = handle nulo.
 * - nativeCopyFrame: copia los planos YUV del último frame recibido hacia
 *   los ByteBuffers de la salida Media3, de-stridendo filas (GLES2 no tiene
 *   UNPACK_ROW_LENGTH).
 * - nativeFlushDecoder / nativeReleaseDecoder: ciclo de vida.
 *
 * Los códigos AVERROR se propagan tal cual (negativos); el mensaje del
 * diagnóstico es el código, nunca contenido del stream.
 */

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
#include <libavutil/pixfmt.h>

/** Estado nativo de una instancia de decoder. */
typedef struct {
    AVCodecContext *ctx;
    AVPacket *packet;
    AVFrame *frame;
} FfmpegDecoder;

/** Traduce el colorspace del frame a los constantes de VideoDecoderOutputBuffer. */
static jint mapColorspace(const AVFrame *frame) {
    switch (frame->colorspace) {
        case AVCOL_SPC_BT709:
            return 2; /* COLORSPACE_BT709 */
        case AVCOL_SPC_BT470BG:
        case AVCOL_SPC_SMPTE170M:
            return 1; /* COLORSPACE_BT601 */
        case AVCOL_SPC_BT2020_NCL:
        case AVCOL_SPC_BT2020_CL:
            return 3; /* COLORSPACE_BT2020 */
        default:
            return 0; /* COLORSPACE_UNKNOWN */
    }
}

static void liberar(FfmpegDecoder *d) {
    if (d == NULL) {
        return;
    }
    if (d->frame != NULL) {
        av_frame_free(&d->frame);
    }
    if (d->packet != NULL) {
        av_packet_free(&d->packet);
    }
    if (d->ctx != NULL) {
        avcodec_free_context(&d->ctx);
    }
    free(d);
}

JNIEXPORT jlong JNICALL
Java_com_copernicotv_app_player_software_DecoderFfmpeg_nativeCreateDecoder(
        JNIEnv *env, jobject thiz, jstring jCodecName) {
    (void)thiz;
    const char *codecName = (*env)->GetStringUTFChars(env, jCodecName, NULL);
    if (codecName == NULL) {
        return 0;
    }
    const AVCodec *codec = avcodec_find_decoder_by_name(codecName);
    if (codec == NULL) {
        (*env)->ReleaseStringUTFChars(env, jCodecName, codecName);
        return 0;
    }
    (*env)->ReleaseStringUTFChars(env, jCodecName, codecName);

    AVCodecContext *ctx = avcodec_alloc_context3(codec);
    if (ctx == NULL) {
        return 0;
    }
    /* Auto: el decodificador decide hilos por slice según el hardware. */
    ctx->thread_count = 0;
    if (avcodec_open2(ctx, codec, NULL) < 0) {
        avcodec_free_context(&ctx);
        return 0;
    }

    FfmpegDecoder *d = calloc(1, sizeof(FfmpegDecoder));
    if (d == NULL) {
        avcodec_free_context(&ctx);
        return 0;
    }
    d->ctx = ctx;
    d->packet = av_packet_alloc();
    d->frame = av_frame_alloc();
    if (d->packet == NULL || d->frame == NULL) {
        liberar(d);
        return 0;
    }
    return (jlong)(intptr_t)d;
}

JNIEXPORT jint JNICALL
Java_com_copernicotv_app_player_software_DecoderFfmpeg_nativeSendPacket(
        JNIEnv *env, jobject thiz, jlong handle, jobject jBuffer, jint offset,
        jint size, jlong ptsUs) {
    (void)thiz;
    FfmpegDecoder *d = (FfmpegDecoder *)(intptr_t)handle;
    if (d == NULL || d->ctx == NULL) {
        return -1;
    }
    if (jBuffer == NULL) {
        /* Señal de fin de stream: el decodificador entra en modo drain. */
        return avcodec_send_packet(d->ctx, NULL) == 0 ? 0 : -1;
    }
    uint8_t *src = (uint8_t *)(*env)->GetDirectBufferAddress(env, jBuffer);
    if (src == NULL || size <= 0) {
        return -2;
    }
    if (av_new_packet(d->packet, size) < 0) {
        return -3;
    }
    memcpy(d->packet->data, src + offset, (size_t)size);
    d->packet->pts = ptsUs;
    d->packet->dts = ptsUs;
    int ret = avcodec_send_packet(d->ctx, d->packet);
    av_packet_unref(d->packet);
    return ret == 0 ? 0 : ret;
}

JNIEXPORT jint JNICALL
Java_com_copernicotv_app_player_software_DecoderFfmpeg_nativeReceiveFrame(
        JNIEnv *env, jobject thiz, jlong handle, jintArray jInfo, jlongArray jPts) {
    (void)thiz;
    FfmpegDecoder *d = (FfmpegDecoder *)(intptr_t)handle;
    if (d == NULL || d->ctx == NULL || d->frame == NULL) {
        return -1;
    }
    int ret = avcodec_receive_frame(d->ctx, d->frame);
    if (ret == AVERROR(EAGAIN)) {
        return 0; /* sin salida todavía */
    }
    if (ret == AVERROR_EOF) {
        return -100; /* drenado: no hay más frames hasta flush */
    }
    if (ret < 0) {
        return ret; /* AVERROR real: se reporta como error de decodificación */
    }
    if (d->frame->format != AV_PIX_FMT_YUV420P) {
        av_frame_unref(d->frame);
        return -200; /* formato de pixel fuera de la matriz inicial */
    }
    jint info[3] = { d->frame->width, d->frame->height, mapColorspace(d->frame) };
    jlong pts = d->frame->pts;
    (*env)->SetIntArrayRegion(env, jInfo, 0, 3, info);
    (*env)->SetLongArrayRegion(env, jPts, 0, 1, &pts);
    return 1;
}

JNIEXPORT jint JNICALL
Java_com_copernicotv_app_player_software_DecoderFfmpeg_nativeCopyFrame(
        JNIEnv *env, jobject thiz, jlong handle, jobjectArray jPlanes) {
    (void)thiz;
    FfmpegDecoder *d = (FfmpegDecoder *)(intptr_t)handle;
    if (d == NULL || d->frame == NULL) {
        return -1;
    }
    const AVFrame *f = d->frame;
    const jint uvw = (f->width + 1) / 2;
    const jint uvh = (f->height + 1) / 2;
    for (int i = 0; i < 3; i++) {
        jobject jPlane = (*env)->GetObjectArrayElement(env, jPlanes, i);
        if (jPlane == NULL) {
            (*env)->DeleteLocalRef(env, jPlane);
            return -1;
        }
        uint8_t *dst = (uint8_t *)(*env)->GetDirectBufferAddress(env, jPlane);
        jsize cap = (*env)->GetDirectBufferCapacity(env, jPlane);
        const jint w = (i == 0) ? f->width : uvw;
        const jint h = (i == 0) ? f->height : uvh;
        jint res = 0;
        if (dst == NULL || cap < w * h) {
            res = -2; /* plano mal dimensionado por la parte Kotlin */
        } else {
            const uint8_t *src = f->data[i];
            const jint stride = f->linesize[i];
            /* De-stride: filas contiguas para GLES2 sin UNPACK_ROW_LENGTH. */
            for (int r = 0; r < h; r++) {
                memcpy(dst + (size_t)r * w, src + (size_t)r * stride, (size_t)w);
            }
        }
        (*env)->DeleteLocalRef(env, jPlane);
        if (res != 0) {
            return res;
        }
    }
    av_frame_unref(d->frame);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_copernicotv_app_player_software_DecoderFfmpeg_nativeFlushDecoder(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    FfmpegDecoder *d = (FfmpegDecoder *)(intptr_t)handle;
    if (d != NULL && d->ctx != NULL) {
        avcodec_flush_buffers(d->ctx);
    }
}

JNIEXPORT void JNICALL
Java_com_copernicotv_app_player_software_DecoderFfmpeg_nativeReleaseDecoder(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    liberar((FfmpegDecoder *)(intptr_t)handle);
}
