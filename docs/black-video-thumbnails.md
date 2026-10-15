# Black video thumbnails

## Findings

`ThumbnailsProvider` used `ThumbnailUtils.createVideoThumbnail` without selecting a timestamp. Its `sameAs` check compared the result with a newly allocated bitmap, which does not reliably recognize an opaque black video frame. The result could then become the persistent JPEG and in-memory artwork. Videos with an artwork URL also bypassed video extraction, preventing existing black thumbnails from being repaired.

## Changes

- Keep the existing extraction result when it contains visible image content.
- For an unusable result, try frames at 10%, 40%, 70% and 90% of the duration. If duration is unknown, try 1, 3 and 10 seconds. Use `OPTION_CLOSEST` so a long GOP does not force every request back to the opening black keyframe.
- Sample at most 32 × 32 pixels. A generated image is rejected if no more than 1% of samples have both alpha and at least one RGB channel above 16. This tolerates near-black compression noise; it can also reject genuinely very dark scenes with almost no visible detail.
- Use scaled decoding on API 27 and later; scale the returned frame on older versions. Release rejected bitmaps and the retriever.
- Validate existing artwork inside the application's `medialib` directory. Repair it even when the media library says a thumbnail has already been generated. Custom artwork outside that directory keeps its existing behavior.
- Give validated video memory entries a separate namespace, and evict all sizes of rejected artwork. Publish thumbnail metadata after a successful JPEG write. Unregistered media uses memory only.
- Keep extraction failures in a bounded cache, with a 60-second retry interval. Preserve the existing media-library fallback for media without a generated thumbnail. No network URI is passed to Android's frame extractor.

The additional sampling is bounded and only used when the initial result is unusable or unavailable. It does not guarantee finding a visible scene in every video; all candidate positions can still be black. Playback, media scanning, application ID, signing and release settings are unchanged.

## Validation on 2026-10-13

- Independently compiled the three changed production files against Android SDK 36, using dependency stubs for other project modules. This checks Android API calls and Kotlin compilation, not the full application build.
- Executed 26 local regression cases against the production extraction, provider and bitmap-cache code. Android platform operations use test doubles. Cases include black/transparent/noisy frames, visible dark scenes, letterboxing, short and unknown durations, bounded retries, API 17 scaling, content URIs, inaccessible sources, generated-cache repair, custom artwork, failed writes and media-library fallback. Two cases use actual FFmpeg-decoded frames from the synthetic MP4.
- Added `VideoThumbnailExtractorTest` and `ThumbnailsProviderTest` to the project's Robolectric tests. At this stage the project tests had not yet run: the local environment had Gradle 8.6 instead of the required 9.3.1, lacked the cached Android Gradle plugin 9.1.1, and had no `local.properties` or `libvlcjni/libvlc` build directory. The attempted offline test and Debug build stopped during plugin resolution.
- `git diff --check` passed. Changes are on `codex/fix-black-video-thumbnails`, based on `origin/personal`.

## Validation on 2026-10-14

- Prepared the required Debug build environment and successfully built the complete APK. Verified its Debug signature, ZIP alignment, package name, native ABIs, Remote Access assets, and the inclusion of the new thumbnail extractor and cache namespace in DEX.
- All 12 newly added Robolectric tests passed using the compiled project code and a portable JDK 11 runner. The cache image reader is controlled in the tests; real device decoding and JPEG behavior still require device verification.
- The standard Gradle unit-test task is blocked by unrelated upstream test compilation errors. The new tests were compiled through a local isolated entry point and executed directly with JUnit, without changing application dependency versions or production code.
- Artifact, hash, environment details and reproduction commands are recorded in `docs/debug-apk-build.md`.

The standard project commands are below. The unit-test command currently encounters the upstream test compilation issues described above; use the isolated local runner documented in the build record for the new tests.

```text
gradlew.bat :application:vlc-android:testDebugUnitTest --tests org.videolan.vlc.util.VideoThumbnailExtractorTest --tests org.videolan.vlc.util.ThumbnailsProviderTest
gradlew.bat :application:app:assembleDebug
```

## Device regression, deferred

Target reported by the user: OriginOS 6. Exact Android version and device model still need to be read from the phone.

Local synthetic samples are in `build/thumbnail-regression/` (ignored by Git):

- `black-intro-long-gop.mp4`: 6 seconds, 2-second black opening followed by visible content, H.264 with only the opening keyframe. The 10% sample is black; the 40% sample contains visible content.
- `all-black.mp4`: 3 seconds of black video, to check bounded retries and placeholder behavior.

After connecting the phone and preparing the Debug APK:

1. Check the reported MP4 and the synthetic sample with no existing thumbnail.
2. Check repair with a pre-existing black JPEG and a generated-thumbnail flag, then scroll and restart to verify memory and disk reuse.
3. Check ordinary videos, dark scenes, portrait videos, custom artwork, folder/group thumbnails and the all-black sample.
4. Check playback, seeking, media-library refresh, file browsing, removable storage and content-URI permissions, including revoked grants.

For future upstream synchronization, review changes to thumbnail storage paths, cache-key handling and media-library thumbnail generation together with this patch. The cache ownership check currently relies on the application's `medialib` directory.
