#include <jni.h>
#include <android/log.h>
#include "ring_buffer.h"

#define TAG "TrinetraNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

static std::unique_ptr<YUVRingBuffer> g_ring_buffer = nullptr;
static YUVRingBuffer::Frame* g_locked_frame = nullptr;

extern "C"
JNIEXPORT void JNICALL
Java_com_example_vx_NativeBufferBridge_initRingBuffer(JNIEnv *env, jobject thiz, jint capacity) {
    g_ring_buffer = std::make_unique<YUVRingBuffer>(capacity);
    LOGD("Ring Buffer initialized with capacity: %d", capacity);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_vx_NativeBufferBridge_pushFrame(JNIEnv *env, jobject thiz,
                                                 jobject y_buffer, jobject u_buffer, jobject v_buffer,
                                                 jint width, jint height,
                                                 jint y_stride, jint uv_stride, jint uv_pixel_stride,
                                                 jlong timestamp) {
    if (!g_ring_buffer) return;

    uint8_t* y_data = static_cast<uint8_t*>(env->GetDirectBufferAddress(y_buffer));
    uint8_t* u_data = static_cast<uint8_t*>(env->GetDirectBufferAddress(u_buffer));
    uint8_t* v_data = static_cast<uint8_t*>(env->GetDirectBufferAddress(v_buffer));

    if (!y_data || !u_data || !v_data) return;

    g_ring_buffer->push(y_data, u_data, v_data, width, height, y_stride, uv_stride, uv_pixel_stride, timestamp);
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_example_vx_NativeBufferBridge_lockLatestFrame(JNIEnv *env, jobject thiz) {
    if (!g_ring_buffer) return nullptr;
    g_locked_frame = g_ring_buffer->lockLatest();
    return nullptr; // Metadata can be returned via other calls if needed
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_example_vx_NativeBufferBridge_getLatestYBuffer(JNIEnv *env, jobject thiz) {
    if (!g_locked_frame) return nullptr;
    return env->NewDirectByteBuffer(g_locked_frame->y_plane.data(), g_locked_frame->y_plane.size());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_vx_NativeBufferBridge_unlockFrame(JNIEnv *env, jobject thiz) {
    if (g_ring_buffer) {
        g_ring_buffer->unlock();
        g_locked_frame = nullptr;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_vx_NativeBufferBridge_preprocessFrame(JNIEnv *env, jobject thiz,
                                                      jobject out_buffer,
                                                      jint target_width, jint target_height,
                                                      jboolean normalize) {
    if (!g_locked_frame || !out_buffer) return;

    float* out_data = static_cast<float*>(env->GetDirectBufferAddress(out_buffer));
    if (!out_data) return;

    int src_w = g_locked_frame->width;
    int src_h = g_locked_frame->height;
    int y_stride = g_locked_frame->y_stride;
    int uv_stride = g_locked_frame->uv_stride;
    int uv_pixel_stride = g_locked_frame->uv_pixel_stride;

    const uint8_t* y_plane = g_locked_frame->y_plane.data();
    const uint8_t* u_plane = g_locked_frame->u_plane.data();
    const uint8_t* v_plane = g_locked_frame->v_plane.data();

    float scale_x = static_cast<float>(src_w) / static_cast<float>(target_width);
    float scale_y = static_cast<float>(src_h) / static_cast<float>(target_height);

    for (int dy = 0; dy < target_height; ++dy) {
        float src_y = static_cast<float>(dy) * scale_y;
        int y0 = static_cast<int>(src_y);

        for (int dx = 0; dx < target_width; ++dx) {
            float src_x = static_cast<float>(dx) * scale_x;
            int x0 = static_cast<int>(src_x);

            // Fetch Y
            uint8_t y = y_plane[y0 * y_stride + x0];

            // Fetch U and V (4:2:0 subsampling)
            int uv_idx = (y0 / 2) * uv_stride + (x0 / 2) * uv_pixel_stride;
            uint8_t u = u_plane[uv_idx];
            uint8_t v = v_plane[uv_idx];

            // YUV to RGB Conversion (Integer approximation for speed)
            int c = y - 16;
            int d = u - 128;
            int e = v - 128;

            float r = (298 * c + 409 * e + 128) >> 8;
            float g = (298 * c - 100 * d - 208 * e + 128) >> 8;
            float b = (298 * c + 516 * d + 128) >> 8;

            auto clamp = [](float val) { return val < 0 ? 0 : (val > 255 ? 255 : val); };

            float rf = clamp(r);
            float gf = clamp(g);
            float bf = clamp(b);

            if (normalize) {
                rf /= 255.0f;
                gf /= 255.0f;
                bf /= 255.0f;
            }

            int out_idx = (dy * target_width + dx) * 3;
            out_data[out_idx] = rf;
            out_data[out_idx + 1] = gf;
            out_data[out_idx + 2] = bf;
        }
    }
}
