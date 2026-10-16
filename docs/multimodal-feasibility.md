# Multimodal implementation notes — image / video

## Implemented in source

The app now has a complete source-level image/video attachment pipeline and a native `mtmd` integration path.

### Kotlin side

* `AttachmentManager` copies selected media into the private workspace under `uploads/`.
* Images are decoded to RGB, EXIF-rotated, downscaled, and passed as `ImageData`.
* Videos are sampled into RGB frames with the configured frame count; native `MTMD_VIDEO` support is intentionally OFF,
  so ffmpeg is not a dependency of the Android build.
* Image/video menu items are disabled unless the currently loaded model has a projector.

### Model loading

* `ModelManager` treats `*mmproj*.gguf` as projector files rather than normal text models.
* The Models load dialog offers a projector dropdown and suggests a matching projector by normalized model name.
* The selected projector is persisted as `lastMmprojPath` and restored by auto-load/reload.
* Status shows `Vision: active` when a projector is loaded.

### Native side

`SessionConfig::mmproj_path`, `GenerateRequest::images`, JNI byte-array transport, and the `BMOE_ENABLE_MULTIMODAL`
build flag are implemented. When `BMOE_HAVE_MTMD` is present, `Session` creates an mtmd context, inserts one default
media marker per image, tokenizes text+images through `mtmd_tokenize`, and pre-fills through
`mtmd_helper_eval_chunks`.

Image turns explicitly disable speculative decoding because the text-only KV mirror cannot represent image embeddings.
The image turn starts from a clean KV and marks the cache as containing media. A later turn therefore clears/replays text
history instead of pretending the old image embeddings are reusable.

## Verification status

* `session.cpp` passes host syntax with `BMOE_HAVE_MTMD=1` and without it.
* `native-bridge.cpp` passes host syntax with a minimal JNI stub and mtmd enabled.
* The Android CMake/NDK link, a real vision model, a matching mmproj, image inference, video frame inference, and device
  memory/performance are **not verified in this sandbox**.

The remaining risk is therefore integration/runtime rather than an intentionally omitted feature.
