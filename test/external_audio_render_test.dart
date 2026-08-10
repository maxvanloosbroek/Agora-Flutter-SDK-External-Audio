import 'package:agora_rtc_engine/agora_rtc_engine.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('external audio methods are disabled on non-Android platforms', () async {
    final previousPlatform = debugDefaultTargetPlatformOverride;
    debugDefaultTargetPlatformOverride = TargetPlatform.linux;
    addTearDown(() {
      debugDefaultTargetPlatformOverride = previousPlatform;
    });

    final engine = createAgoraRtcEngine();

    expect(await engine.hasUsbAudioOutput(), isFalse);
    expect(
      await engine.startExternalAudioRender(),
      isFalse,
    );
    await engine.stopExternalAudioRender();
  });
}
