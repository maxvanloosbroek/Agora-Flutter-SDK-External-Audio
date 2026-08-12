#ifndef AEC_PROCESSOR_H_
#define AEC_PROCESSOR_H_

#include <cstdint>

// Minimal engine-agnostic echo canceller interface.
// All buffers are PCM16, mono or stereo, interleaved, one 10 ms frame.
class AecProcessor {
 public:
  virtual ~AecProcessor() = default;

  // Called from the render thread with the frame about to be played.
  virtual void Playback(const int16_t* far_frame, int samples_per_channel) = 0;

  // Called from the capture thread. May write in place (out may equal near).
  virtual void Capture(const int16_t* near_frame, int16_t* out_frame,
                       int samples_per_channel) = 0;

  // Reports the measured render-to-capture delay so the engine can align.
  virtual void SetStreamDelayMs(int delay_ms) = 0;

  static AecProcessor* Create(int sample_rate, int channels);
};

#endif  // AEC_PROCESSOR_H_
