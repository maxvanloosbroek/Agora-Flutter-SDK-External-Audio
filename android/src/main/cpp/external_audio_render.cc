#include <android/log.h>
#include <jni.h>

#include "AgoraMediaBase.h"
#include "IAgoraMediaEngine.h"
#include "IAgoraRtcEngine.h"
#include "aec_processor.h"

namespace {

constexpr char kLogTag[] = "ExternalAudioRender";

int64_t cached_engine_handle = 0;
agora::media::IMediaEngine* cached_media_engine = nullptr;
AecProcessor* g_aec = nullptr;

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

extern "C" JNIEXPORT jint JNICALL
Java_io_agora_agora_1rtc_1ng_ExternalAudioCapture_nativePushAudioFrame(
    JNIEnv* env, jobject, jlong engine_handle, jobject direct_buffer,
    jint samples_per_channel, jint channels, jint sample_rate, jint track_id,
    jlong timestamp_ms) {
  auto* media_engine = get_media_engine(engine_handle);
  if (media_engine == nullptr) {
    return -1;
  }

  void* buffer = env->GetDirectBufferAddress(direct_buffer);
  if (buffer == nullptr) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                        "nativePushAudioFrame requires a direct buffer");
    return -2;
  }

  agora::media::IAudioFrameObserverBase::AudioFrame frame{};
  frame.type = agora::media::IAudioFrameObserverBase::FRAME_TYPE_PCM16;
  frame.samplesPerChannel = samples_per_channel;
  frame.bytesPerSample = agora::rtc::TWO_BYTES_PER_SAMPLE;
  frame.channels = channels;
  frame.samplesPerSec = sample_rate;
  frame.buffer = buffer;
  frame.renderTimeMs = timestamp_ms;

  const int result = media_engine->pushAudioFrame(
      &frame, static_cast<agora::rtc::track_id_t>(track_id));
  if (result != 0) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                        "pushAudioFrame failed: %d", result);
    return -3;
  }

  return samples_per_channel * channels * 2;
}

extern "C" JNIEXPORT void JNICALL
Java_io_agora_agora_1rtc_1ng_ExternalAudioRender_nativeAecCreate(
    JNIEnv*, jobject, jint sample_rate, jint channels) {
  delete g_aec;
  g_aec = AecProcessor::Create(sample_rate, channels);
  __android_log_print(ANDROID_LOG_INFO, kLogTag,
                      "nativeAecCreate: %p (sr=%d ch=%d)",
                      g_aec, sample_rate, channels);
}

extern "C" JNIEXPORT void JNICALL
Java_io_agora_agora_1rtc_1ng_ExternalAudioRender_nativeAecDestroy(
    JNIEnv*, jobject) {
  __android_log_print(ANDROID_LOG_INFO, kLogTag,
                      "nativeAecDestroy: %p", g_aec);
  delete g_aec;
  g_aec = nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_io_agora_agora_1rtc_1ng_ExternalAudioRender_nativeAecPlayback(
    JNIEnv* env, jobject, jobject direct_buffer, jint samples_per_channel) {
  if (g_aec == nullptr) return;
  auto* buf = static_cast<int16_t*>(env->GetDirectBufferAddress(direct_buffer));
  if (buf == nullptr) return;
  g_aec->Playback(buf, samples_per_channel);
}

extern "C" JNIEXPORT void JNICALL
Java_io_agora_agora_1rtc_1ng_ExternalAudioCapture_nativeAecCapture(
    JNIEnv* env, jobject, jobject direct_buffer, jint samples_per_channel) {
  if (g_aec == nullptr) return;
  auto* buf = static_cast<int16_t*>(env->GetDirectBufferAddress(direct_buffer));
  if (buf == nullptr) return;
  g_aec->Capture(buf, buf, samples_per_channel);
}

extern "C" JNIEXPORT void JNICALL
Java_io_agora_agora_1rtc_1ng_ExternalAudioRender_nativeAecSetDelay(
    JNIEnv*, jobject, jint delay_ms) {
  if (g_aec == nullptr) return;
  g_aec->SetStreamDelayMs(delay_ms);
  __android_log_print(ANDROID_LOG_INFO, kLogTag,
                      "nativeAecSetDelay: %d ms", delay_ms);
}
