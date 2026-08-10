#include <android/log.h>
#include <jni.h>

#include "AgoraMediaBase.h"
#include "IAgoraMediaEngine.h"
#include "IAgoraRtcEngine.h"

namespace {

constexpr char kLogTag[] = "ExternalAudioRender";

int64_t cached_engine_handle = 0;
agora::media::IMediaEngine* cached_media_engine = nullptr;

agora::media::IMediaEngine* get_media_engine(int64_t engine_handle) {
  if (engine_handle == cached_engine_handle && cached_media_engine != nullptr) {
    return cached_media_engine;
  }

  auto* engine = reinterpret_cast<agora::rtc::IRtcEngine*>(engine_handle);
  if (engine == nullptr) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                        "get_media_engine: null engine handle");
    return nullptr;
  }

  agora::media::IMediaEngine* media_engine = nullptr;
  const int result = engine->queryInterface(
      agora::rtc::AGORA_IID_MEDIA_ENGINE,
      reinterpret_cast<void**>(&media_engine));
  if (result != 0 || media_engine == nullptr) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                        "queryInterface failed: %d", result);
    return nullptr;
  }

  cached_engine_handle = engine_handle;
  cached_media_engine = media_engine;
  __android_log_print(ANDROID_LOG_INFO, kLogTag,
                      "media engine acquired: %p", media_engine);
  return media_engine;
}

}

extern "C" JNIEXPORT jint JNICALL
Java_io_agora_agora_1rtc_1ng_ExternalAudioRender_nativePullAudioFrame(
    JNIEnv* env, jobject, jlong engine_handle, jobject direct_buffer,
    jint samples_per_channel, jint channels, jint sample_rate) {
  auto* media_engine = get_media_engine(engine_handle);
  if (media_engine == nullptr) {
    return -1;
  }

  void* buffer = env->GetDirectBufferAddress(direct_buffer);
  if (buffer == nullptr) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                        "nativePullAudioFrame requires a direct buffer");
    return -2;
  }

  agora::media::IAudioFrameObserverBase::AudioFrame frame;
  frame.type = agora::media::IAudioFrameObserverBase::FRAME_TYPE_PCM16;
  frame.samplesPerChannel = samples_per_channel;
  frame.bytesPerSample = agora::rtc::TWO_BYTES_PER_SAMPLE;
  frame.channels = channels;
  frame.samplesPerSec = sample_rate;
  frame.buffer = buffer;

  const int result = media_engine->pullAudioFrame(&frame);
  if (result != 0) {
    return -3;
  }

  return samples_per_channel * channels * 2;
}

extern "C" JNIEXPORT void JNICALL
Java_io_agora_agora_1rtc_1ng_ExternalAudioRender_nativeResetCache(
    JNIEnv*, jobject) {
  cached_engine_handle = 0;
  cached_media_engine = nullptr;
}
