#include "aec_processor.h"

#if defined(HAS_WEBRTC_APM)
#include "modules/audio_processing/include/audio_processing.h"
#endif

#if defined(HAS_WEBRTC_APM)
namespace {

class WebRtcAecProcessor : public AecProcessor {
 public:
  WebRtcAecProcessor(int sample_rate, int channels)
      : sample_rate_(sample_rate), channels_(channels) {
    apm_ = webrtc::AudioProcessingBuilder().Create();
    webrtc::AudioProcessing::Config config = apm_->GetConfig();
    config.echo_canceller.enabled = true;
    config.echo_canceller.mobile_mode = false;
    config.high_pass_filter.enabled = true;
    apm_->ApplyConfig(config);
  }

  ~WebRtcAecProcessor() override = default;

  void Playback(const int16_t* far_frame, int samples_per_channel) override {
    webrtc::StreamConfig config(sample_rate_, channels_);
    apm_->ProcessReverseStream(far_frame, config, config,
                               const_cast<int16_t*>(far_frame));
  }

  void Capture(const int16_t* near_frame, int16_t* out_frame,
               int samples_per_channel) override {
    webrtc::StreamConfig config(sample_rate_, channels_);
    apm_->set_stream_delay_ms(stream_delay_ms_);
    apm_->ProcessStream(near_frame, config, config, out_frame);
  }

  void SetStreamDelayMs(int delay_ms) override { stream_delay_ms_ = delay_ms; }

 private:
  int sample_rate_;
  int channels_;
  int stream_delay_ms_ = 150;
  rtc::scoped_refptr<webrtc::AudioProcessing> apm_;
};

}  // namespace
#endif  // HAS_WEBRTC_APM

AecProcessor* AecProcessor::Create(int sample_rate, int channels) {
#if defined(HAS_WEBRTC_APM)
  return new WebRtcAecProcessor(sample_rate, channels);
#else
  return nullptr;
#endif
}
