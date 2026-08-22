#include <jni.h>
#include <android/log.h>
#include <oboe/Oboe.h>
#include <opus.h>

#include <atomic>
#include <chrono>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "SSEngine", __VA_ARGS__)

constexpr int32_t kSampleRate = 48000;
constexpr int32_t kFrame      = 960;             // 20 ms @ 48k
constexpr uint16_t kMagic     = 0x5353;
constexpr uint8_t  kVersion   = 1;
constexpr uint8_t  kPayloadOpus = 0;
constexpr uint8_t  kPayloadPCM  = 1;

static void put16(uint8_t* p, uint16_t v) { p[0] = v >> 8; p[1] = v & 0xFF; }
static void put32(uint8_t* p, uint32_t v) { p[0] = v >> 24; p[1] = (v >> 16) & 0xFF; p[2] = (v >> 8) & 0xFF; p[3] = v & 0xFF; }
static uint16_t get16(const uint8_t* p) { return (uint16_t)((p[0] << 8) | p[1]); }
static int32_t  seqDiff(uint16_t a, uint16_t b) { return (int16_t)(a - b); }

// ---------- lock-free SPSC ring (§11) ----------
class SPSCRing {
public:
    explicit SPSCRing(size_t pow2) : cap(pow2), mask(pow2 - 1), buf(new int16_t[pow2]) {}
    size_t write(const int16_t* src, size_t n) {
        size_t w = head.load(std::memory_order_relaxed);
        size_t r = tail.load(std::memory_order_acquire);
        n = std::min(n, cap - (w - r));
        for (size_t i = 0; i < n; i++) buf[(w + i) & mask] = src[i];
        head.store(w + n, std::memory_order_release);
        return n;
    }
    size_t read(int16_t* dst, size_t n) {
        size_t r = tail.load(std::memory_order_relaxed);
        size_t w = head.load(std::memory_order_acquire);
        n = std::min(n, w - r);
        for (size_t i = 0; i < n; i++) dst[i] = buf[(r + i) & mask];
        tail.store(r + n, std::memory_order_release);
        return n;
    }
    size_t available() const { return head.load(std::memory_order_acquire) - tail.load(std::memory_order_acquire); }
private:
    size_t cap, mask;
    std::unique_ptr<int16_t[]> buf;
    std::atomic<size_t> head{0}, tail{0};
};

// ================= SENDER: (mic OR external feed) -> Opus/PCM -> UDP =================
class SenderEngine : public oboe::AudioStreamDataCallback {
public:
    bool start(const std::string& ip, uint16_t udpPort, int codec, int bitrateKbps, bool useMic) {
        stop();
        mDestIp = ip; mDestPort = udpPort; mCodec = codec;

        if (mCodec == 0) {
            int err = 0;
            mEnc = opus_encoder_create(kSampleRate, 1, OPUS_APPLICATION_AUDIO, &err);
            if (err != OPUS_OK || !mEnc) return false;
            opus_encoder_ctl(mEnc, OPUS_SET_BITRATE(bitrateKbps * 1000));
            opus_encoder_ctl(mEnc, OPUS_SET_INBAND_FEC(1));
            opus_encoder_ctl(mEnc, OPUS_SET_PACKET_LOSS_PERC(15));
        }

        if (useMic) {
            oboe::AudioStreamBuilder b;
            b.setDirection(oboe::Direction::Input)
             ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
             ->setSharingMode(oboe::SharingMode::Shared)
             ->setFormat(oboe::AudioFormat::I16)
             ->setChannelCount(1)
             ->setSampleRate(kSampleRate)
             ->setDataCallback(this);
            if (b.openStream(mIn) != oboe::Result::OK) { LOGI("sender: input open failed"); stop(); return false; }
            mIn->requestStart();
        } else {
            LOGI("sender: external feed mode (media capture)");
        }

        mRunning = true;
        mNetThread = std::thread([this] { sendLoop(); });
        LOGI("sender codec=%d -> %s:%d", mCodec, mDestIp.c_str(), mDestPort);
        return true;
    }

    /** Called from Kotlin (MediaAudioCapture thread) when mic is not used. */
    size_t feed(const int16_t* data, size_t n) { return mRing.write(data, n); }

    void stop() {
        mRunning = false;
        if (mNetThread.joinable()) mNetThread.join();
        if (mIn) { mIn->requestStop(); mIn->close(); mIn.reset(); }
        if (mEnc) { opus_encoder_destroy(mEnc); mEnc = nullptr; }
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* data, int32_t n) override {
        mRing.write(static_cast<const int16_t*>(data), (size_t)n);
        return oboe::DataCallbackResult::Continue;
    }

private:
    void sendLoop() {
        int sock = ::socket(AF_INET, SOCK_DGRAM, 0);
        if (sock < 0) return;
        sockaddr_in dest{};
        dest.sin_family = AF_INET;
        dest.sin_port = htons(mDestPort);
        inet_pton(AF_INET, mDestIp.c_str(), &dest.sin_addr);

        uint8_t packet[12 + 2048];
        int16_t pcm[kFrame];
        uint16_t seq = 0; uint32_t ts = 0;

        while (mRunning) {
            if (mRing.read(pcm, kFrame) < (size_t)kFrame) { usleep(1000); continue; }

            int32_t len = 0; uint8_t type;
            if (mCodec == 0) {
                len = opus_encode(mEnc, pcm, kFrame, packet + 12, 1275);
                if (len <= 0) continue;
                type = kPayloadOpus;
            } else {
                memcpy(packet + 12, pcm, (size_t)kFrame * 2);
                len = kFrame * 2;
                type = kPayloadPCM;
            }

            put16(packet + 0, kMagic);
            packet[2] = kVersion;
            packet[3] = type;
            put16(packet + 4, seq);
            put32(packet + 6, ts);
            put16(packet + 10, (uint16_t)len);
            ::sendto(sock, packet, (size_t)(12 + len), 0, (sockaddr*)&dest, sizeof(dest));
            seq++; ts += (uint32_t)kFrame;
        }
        ::close(sock);
    }

    SPSCRing mRing{1 << 17};
    OpusEncoder* mEnc = nullptr;
    int mCodec = 0;
    std::shared_ptr<oboe::AudioStream> mIn;
    std::thread mNetThread;
    std::atomic<bool> mRunning{false};
    std::string mDestIp;
    uint16_t mDestPort = 50001;
};

// ================= RECEIVER: UDP -> jitter/PCM -> speaker (unchanged) =================
class ReceiverEngine : public oboe::AudioStreamDataCallback {
public:
    bool start(uint16_t udpPort, int bufferMs) {
        stop();
        int err = 0;
        mDec = opus_decoder_create(kSampleRate, 1, &err);
        if (err != OPUS_OK || !mDec) return false;

        mPrefillSamples = (size_t)bufferMs * (kSampleRate / 1000);
        mPrefilled = false;
        mRunning = true;
        mNetThread = std::thread([this, udpPort] { recvLoop(udpPort); });
        mDecThread = std::thread([this] { decodeLoop(); });

        oboe::AudioStreamBuilder b;
        b.setDirection(oboe::Direction::Output)
         ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
         ->setSharingMode(oboe::SharingMode::Shared)
         ->setFormat(oboe::AudioFormat::I16)
         ->setChannelCount(1)
         ->setSampleRate(kSampleRate)
         ->setDataCallback(this);
        if (b.openStream(mOut) != oboe::Result::OK) { LOGI("receiver: output open failed"); stop(); return false; }

        mOut->requestStart();
        LOGI("receiver on UDP %d, buffer %d ms", udpPort, bufferMs);
        return true;
    }

    void stop() {
        mRunning = false;
        if (mNetThread.joinable()) mNetThread.join();
        if (mDecThread.joinable()) mDecThread.join();
        if (mOut) { mOut->requestStop(); mOut->close(); mOut.reset(); }
        if (mDec) { opus_decoder_destroy(mDec); mDec = nullptr; }
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* data, int32_t n) override {
        auto* out = static_cast<int16_t*>(data);
        if (!mPrefilled) {
            if (mRing.available() < mPrefillSamples) {
                memset(out, 0, (size_t)n * 2);
                return oboe::DataCallbackResult::Continue;
            }
            mPrefilled = true;
        }
        size_t got = mRing.read(out, (size_t)n);
        for (size_t i = got; i < (size_t)n; i++) out[i] = 0;
        return oboe::DataCallbackResult::Continue;
    }

private:
    void recvLoop(uint16_t port) {
        int sock = ::socket(AF_INET, SOCK_DGRAM, 0);
        if (sock < 0) return;
        sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_addr.s_addr = htonl(INADDR_ANY);
        addr.sin_port = htons(port);
        if (::bind(sock, (sockaddr*)&addr, sizeof(addr)) < 0) { ::close(sock); return; }

        timeval tv{0, 100000};
        setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

        uint8_t buf[2048];
        int16_t tmp[1024];
        while (mRunning) {
            ssize_t n = ::recvfrom(sock, buf, sizeof(buf), 0, nullptr, nullptr);
            if (n < 12) continue;
            if (get16(buf) != kMagic || buf[2] != kVersion) continue;
            uint16_t len = get16(buf + 10);
            if (len > 2048 || n < 12 + (ssize_t)len) continue;

            if (buf[3] == kPayloadOpus) {
                uint16_t seq = get16(buf + 4);
                std::lock_guard<std::mutex> lk(mJitMtx);
                if (!mJitHaveSeq) { mJitNext = seq; mJitHaveSeq = true; }
                if (seqDiff(seq, mJitNext) >= 0 && mJit.size() < 64)
                    mJit.emplace(seq, std::vector<uint8_t>(buf + 12, buf + 12 + len));
            } else if (buf[3] == kPayloadPCM) {
                memcpy(tmp, buf + 12, len);
                mRing.write(tmp, len / 2);
            }
        }
        ::close(sock);
    }

    void decodeLoop() {
        auto nextTick = std::chrono::steady_clock::now();
        while (mRunning) {
            nextTick += std::chrono::milliseconds(20);
            std::this_thread::sleep_until(nextTick);

            std::vector<uint8_t> payload; bool found = false;
            {
                std::lock_guard<std::mutex> lk(mJitMtx);
                if (mJitHaveSeq) {
                    auto it = mJit.find(mJitNext);
                    if (it != mJit.end()) { payload = std::move(it->second); mJit.erase(it); found = true; }
                    for (auto o = mJit.begin(); o != mJit.end();)
                        o = (seqDiff(o->first, mJitNext) < 0) ? mJit.erase(o) : ++o;
                    mJitNext++;
                }
            }

            int n = found ? opus_decode(mDec, payload.data(), (opus_int32)payload.size(), mTmp, kFrame, 0)
                          : opus_decode(mDec, nullptr, 0, mTmp, kFrame, 0);
            if (n > 0) mRing.write(mTmp, (size_t)n);
        }
    }

    SPSCRing mRing{1 << 17};
    OpusDecoder* mDec = nullptr;
    int16_t mTmp[5760];
    std::mutex mJitMtx;
    std::map<uint16_t, std::vector<uint8_t>> mJit;
    uint16_t mJitNext = 0;
    bool mJitHaveSeq = false;
    size_t mPrefillSamples = 4800;
    std::shared_ptr<oboe::AudioStream> mOut;
    std::thread mNetThread, mDecThread;
    std::atomic<bool> mRunning{false}, mPrefilled{false};
};

static SenderEngine   gSender;
static ReceiverEngine gReceiver;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_localaux_soundshare_audio_NativeEngine_startSender(JNIEnv* env, jobject, jstring ip, jint port, jint codec, jint bitrateKbps, jboolean useMic) {
    const char* c = env->GetStringUTFChars(ip, nullptr);
    std::string s(c);
    env->ReleaseStringUTFChars(ip, c);
    return gSender.start(s, (uint16_t)port, codec, bitrateKbps, useMic);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_localaux_soundshare_audio_NativeEngine_feedAudio(JNIEnv* env, jobject, jshortArray arr, jint count) {
    jshort* p = env->GetShortArrayElements(arr, nullptr);
    if (!p) return 0;
    size_t w = gSender.feed(reinterpret_cast<const int16_t*>(p), (size_t)count);
    env->ReleaseShortArrayElements(arr, p, JNI_ABORT);
    return (jint)w;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_localaux_soundshare_audio_NativeEngine_startReceiver(JNIEnv*, jobject, jint port, jint bufferMs) {
    return gReceiver.start((uint16_t)port, bufferMs);
}

extern "C" JNIEXPORT void JNICALL
Java_com_localaux_soundshare_audio_NativeEngine_stop(JNIEnv*, jobject) {
    gSender.stop();
    gReceiver.stop();
}