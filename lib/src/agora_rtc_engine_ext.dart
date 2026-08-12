import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart' show MethodCall;

import '/src/agora_media_player.dart';
import '/src/agora_rtc_engine.dart';
import '/src/agora_rtc_engine_ex.dart';
import '/src/impl/agora_rtc_engine_impl.dart';
import '/src/impl/agora_rtc_engine_impl.dart' as impl;
import '/src/impl/media_player_impl.dart';
import '/src/agora_pip_controller.dart';
import '/src/impl/agora_pip_controller_impl.dart';

/// @nodoc
extension RtcEngineExt on RtcEngine {
  /// Get the actual absolute path of an asset from its relative asset path.
  ///
  /// * [assetPath] The flutter -> assets field configured in the pubspec.yaml file.
  ///
  /// Returns
  /// The actual path of the asset.
  Future<String?> getAssetAbsolutePath(String assetPath) async {
    final impl = this as RtcEngineImpl;
    return impl.getAssetAbsolutePath(assetPath);
  }

  /// @nodoc
  int getApiEngineHandle() {
    final impl = this as RtcEngineImpl;
    return impl.getApiEngineHandle();
  }

  /// @nodoc
  AgoraPipController createPipController() {
    return AgoraPipControllerImpl(this);
  }

  /// @nodoc
  @optionalTypeArgs
  Future<T?> invokeAgoraMethod<T>(String method, [dynamic arguments]) {
    final impl = this as RtcEngineImpl;
    return impl.invokeAgoraMethod<T>(method, arguments);
  }

  /// @nodoc
  Future<void> registerMethodChannelHandler(
    String method,
    Future<dynamic> Function(MethodCall call) handler,
  ) {
    final impl = this as RtcEngineImpl;
    return impl.registerMethodChannelHandler(method, handler);
  }

  /// @nodoc
  Future<void> unregisterMethodChannelHandler(
    String method,
    Future<dynamic> Function(MethodCall call)? handler,
  ) {
    final impl = this as RtcEngineImpl;
    return impl.unregisterMethodChannelHandler(method, handler);
  }

  /// @nodoc
  void setEnableArgusCounters(bool enabled) {
    if (this is RtcEngineImpl) {
      (this as RtcEngineImpl).setEnableArgusCounters(enabled);
    }
  }

  /// Returns whether an Android USB audio output is connected.
  ///
  /// Call this before joining a channel. If this returns false, leave external
  /// audio rendering disabled so Agora uses its normal renderer.
  Future<bool> hasUsbAudioOutput() async {
    if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) {
      return false;
    }
    return await invokeAgoraMethod<bool>('hasUsbAudioOutput') ?? false;
  }

  /// Starts rendering mixed remote audio through the Android USB output.
  ///
  /// Call `setExternalAudioSink` with the same format before joining a channel,
  /// then call this method after joining. Call [stopExternalAudioRender] before
  /// releasing the engine.
  Future<bool> startExternalAudioRender(
      {int sampleRate = 48000, int channels = 1}) async {
    if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) {
      return false;
    }
    final nativeHandle = await getNativeHandle();
    if (nativeHandle == 0) {
      return false;
    }
    return await invokeAgoraMethod<bool>('startExternalAudioRender', {
          'nativeHandle': nativeHandle,
          'sampleRate': sampleRate,
          'channels': channels,
        }) ??
        false;
  }

  /// Stops Android USB external audio rendering.
  ///
  /// Call this before releasing the engine.
  Future<void> stopExternalAudioRender() async {
    if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) {
      return;
    }
    await invokeAgoraMethod<void>('stopExternalAudioRender');
  }

  /// Starts capturing the microphone and pushing frames to a custom audio track.
  ///
  /// Create the track with `createCustomAudioTrack` first and pass its
  /// [trackId]. Join the channel with `publishMicrophoneTrack: false`,
  /// `publishCustomAudioTrack: true` and `publishCustomAudioTrackId: trackId`.
  Future<bool> startExternalAudioCapture({
    required int trackId,
    int sampleRate = 48000,
    int channels = 1,
    bool usePlatformEffects = false,
  }) async {
    if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) {
      return false;
    }
    final nativeHandle = await getNativeHandle();
    if (nativeHandle == 0) {
      return false;
    }
    return await invokeAgoraMethod<bool>('startExternalAudioCapture', {
          'nativeHandle': nativeHandle,
          'trackId': trackId,
          'sampleRate': sampleRate,
          'channels': channels,
          'usePlatformEffects': usePlatformEffects,
        }) ??
        false;
  }

  /// Stops Android USB external audio capture.
  Future<void> stopExternalAudioCapture() async {
    if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) {
      return;
    }
    await invokeAgoraMethod<void>('stopExternalAudioCapture');
  }

  /// Sets the AEC stream delay hint in milliseconds.
  ///
  /// AEC3 uses this as a starting point for adaptive delay tracking.
  /// Range 0–300 ms, default 150. Adjust live during a call to tune.
  Future<void> setAecDelay(int delayMs) async {
    if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) {
      return;
    }
    await invokeAgoraMethod<void>('setAecDelay', {'delayMs': delayMs});
  }
}

/// Error code and description.
class AgoraRtcException implements Exception {
  /// @nodoc
  AgoraRtcException({required this.code, this.message});

  /// Error code. See ErrorCodeType.
  final int code;

  /// Error description.
  final String? message;

  @override
  String toString() => 'AgoraRtcException($code, $message)';
}

/// @nodoc
RtcEngine createAgoraRtcEngine({Object? sharedNativeHandle}) {
  return impl.RtcEngineImpl.create(sharedNativeHandle: sharedNativeHandle);
}

/// @nodoc
RtcEngineEx createAgoraRtcEngineEx({Object? sharedNativeHandle}) {
  return impl.RtcEngineImpl.create(sharedNativeHandle: sharedNativeHandle);
}

/// @nodoc
MediaPlayerCacheManager getMediaPlayerCacheManager(RtcEngine rtcEngine) {
  return MediaPlayerCacheManagerImpl.create(rtcEngine);
}
