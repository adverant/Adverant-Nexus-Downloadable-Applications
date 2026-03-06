// SherpaOnnxBridge.h
// Bridging header for sherpa-onnx C API and system libraries.
// This file allows Swift to call the sherpa-onnx C functions
// and libbz2 decompression functions directly.

#ifndef SherpaOnnxBridge_h
#define SherpaOnnxBridge_h

// sherpa-onnx TTS C API
// The header path depends on how the xcframework was built.
// Try framework-relative path first, then fall back to direct include.
#if __has_include("sherpa-onnx/c-api/c-api.h")
#include "sherpa-onnx/c-api/c-api.h"
#elif __has_include("c-api.h")
#include "c-api.h"
#elif __has_include(<sherpa_onnx/sherpa_onnx.h>)
#include <sherpa_onnx/sherpa_onnx.h>
#endif

// libbz2 for model archive decompression
#include <bzlib.h>

#endif /* SherpaOnnxBridge_h */
