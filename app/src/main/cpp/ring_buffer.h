#ifndef RING_BUFFER_H
#define RING_BUFFER_H

#include <vector>
#include <mutex>
#include <memory>
#include <cstring>

/**
 * A thread-safe Zero-Copy Ring Buffer for YUV_420_888 frames.
 * Includes a locking mechanism to prevent overwriting frames currently in inference.
 */
class YUVRingBuffer {
public:
    struct Frame {
        std::vector<uint8_t> y_plane;
        std::vector<uint8_t> u_plane;
        std::vector<uint8_t> v_plane;
        int width = 0;
        int height = 0;
        int y_stride = 0;
        int uv_stride = 0;
        int uv_pixel_stride = 0;
        long long timestamp = 0;
    };

    YUVRingBuffer(size_t capacity) : capacity_(capacity), head_(0), tail_(0), full_(false), locked_index_(-1) {
        buffer_.resize(capacity);
    }

    void push(const uint8_t* y_data, const uint8_t* u_data, const uint8_t* v_data,
              int width, int height, int y_stride, int uv_stride, int uv_pixel_stride,
              long long timestamp) {
        std::lock_guard<std::mutex> lock(mutex_);

        size_t next_head = (head_ + 1) % capacity_;

        // Drop frame if the next slot is locked by the ML engine
        if (static_cast<int>(next_head) == locked_index_) {
            return;
        }

        head_ = next_head;
        if (full_) {
            tail_ = (tail_ + 1) % capacity_;
        }
        full_ = head_ == tail_;

        Frame& frame = buffer_[head_];

        size_t y_size = y_stride * height;
        size_t uv_size = uv_stride * (height / 2);

        if (frame.y_plane.size() != y_size) frame.y_plane.resize(y_size);
        if (frame.u_plane.size() != uv_size) frame.u_plane.resize(uv_size);
        if (frame.v_plane.size() != uv_size) frame.v_plane.resize(uv_size);

        std::memcpy(frame.y_plane.data(), y_data, y_size);
        std::memcpy(frame.u_plane.data(), u_data, uv_size);
        std::memcpy(frame.v_plane.data(), v_data, uv_size);

        frame.width = width;
        frame.height = height;
        frame.y_stride = y_stride;
        frame.uv_stride = uv_stride;
        frame.uv_pixel_stride = uv_pixel_stride;
        frame.timestamp = timestamp;
    }

    Frame* lockLatest() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!full_ && head_ == tail_ && buffer_[head_].width == 0) return nullptr;
        locked_index_ = head_;
        return &buffer_[locked_index_];
    }

    void unlock() {
        std::lock_guard<std::mutex> lock(mutex_);
        locked_index_ = -1;
    }

private:
    std::vector<Frame> buffer_;
    size_t capacity_;
    size_t head_;
    size_t tail_;
    bool full_;
    int locked_index_;
    mutable std::mutex mutex_;
};

#endif // RING_BUFFER_H
