#!/bin/bash
# build-sherpa-onnx.sh
# Builds the sherpa-onnx xcframeworks for iOS from source.
# The resulting frameworks are placed in ../Frameworks/ for the Xcode project.
#
# Prerequisites:
#   - Xcode 15+ with command line tools
#   - CMake 3.25+
#   - Git
#
# Usage:
#   cd ProseCreatorTTS/scripts
#   ./build-sherpa-onnx.sh
#
# This script:
#   1. Clones sherpa-onnx (or updates existing clone)
#   2. Runs the iOS build script
#   3. Copies the xcframeworks to ../Frameworks/
#   4. Copies the Swift API wrapper to ../ProseCreatorTTS/

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
BUILD_DIR="${SCRIPT_DIR}/sherpa-onnx-build"
FRAMEWORKS_DIR="${PROJECT_DIR}/Frameworks"
SWIFT_SRC_DIR="${PROJECT_DIR}/ProseCreatorTTS"

echo "========================================="
echo "Building sherpa-onnx for iOS"
echo "========================================="
echo "Script dir:     ${SCRIPT_DIR}"
echo "Project dir:    ${PROJECT_DIR}"
echo "Build dir:      ${BUILD_DIR}"
echo "Frameworks dir: ${FRAMEWORKS_DIR}"
echo ""

# Check prerequisites
command -v cmake >/dev/null 2>&1 || { echo "ERROR: cmake not found. Install via: brew install cmake"; exit 1; }
command -v git >/dev/null 2>&1 || { echo "ERROR: git not found."; exit 1; }
command -v xcodebuild >/dev/null 2>&1 || { echo "ERROR: Xcode command line tools not found."; exit 1; }

# Step 1: Clone or update sherpa-onnx
if [ -d "${BUILD_DIR}/sherpa-onnx" ]; then
    echo "Updating existing sherpa-onnx clone..."
    cd "${BUILD_DIR}/sherpa-onnx"
    git pull --rebase
else
    echo "Cloning sherpa-onnx..."
    mkdir -p "${BUILD_DIR}"
    cd "${BUILD_DIR}"
    git clone --depth 1 https://github.com/k2-fsa/sherpa-onnx.git
fi

cd "${BUILD_DIR}/sherpa-onnx"

# Step 2: Run the iOS build script
echo ""
echo "Building iOS frameworks..."
echo "This may take 10-15 minutes on first build."
echo ""

# The official build-ios.sh creates build-ios/ with the xcframeworks
if [ -f "./build-ios.sh" ]; then
    bash ./build-ios.sh
else
    echo "ERROR: build-ios.sh not found in sherpa-onnx repo. The repository structure may have changed."
    exit 1
fi

# Step 3: Copy xcframeworks to project
echo ""
echo "Copying frameworks to ${FRAMEWORKS_DIR}..."
mkdir -p "${FRAMEWORKS_DIR}"

# The build produces these frameworks in build-ios/
SHERPA_FW="${BUILD_DIR}/sherpa-onnx/build-ios/sherpa-onnx.xcframework"
ONNX_FW="${BUILD_DIR}/sherpa-onnx/build-ios/onnxruntime.xcframework"

if [ -d "${SHERPA_FW}" ]; then
    rm -rf "${FRAMEWORKS_DIR}/sherpa_onnx.xcframework"
    cp -R "${SHERPA_FW}" "${FRAMEWORKS_DIR}/sherpa_onnx.xcframework"
    echo "  Copied sherpa_onnx.xcframework"
else
    echo "WARNING: sherpa-onnx.xcframework not found at expected path."
    echo "  Looking in build-ios/ for any xcframeworks..."
    find "${BUILD_DIR}/sherpa-onnx/build-ios" -name "*.xcframework" -maxdepth 2 2>/dev/null | while read fw; do
        echo "  Found: $fw"
        cp -R "$fw" "${FRAMEWORKS_DIR}/"
    done
fi

if [ -d "${ONNX_FW}" ]; then
    rm -rf "${FRAMEWORKS_DIR}/onnxruntime.xcframework"
    cp -R "${ONNX_FW}" "${FRAMEWORKS_DIR}/onnxruntime.xcframework"
    echo "  Copied onnxruntime.xcframework"
fi

# Step 4: Copy the official Swift API wrapper if it exists
SWIFT_API="${BUILD_DIR}/sherpa-onnx/swift-api-examples/SherpaOnnx.swift"
if [ -f "${SWIFT_API}" ]; then
    echo ""
    echo "NOTE: Official SherpaOnnx.swift found at:"
    echo "  ${SWIFT_API}"
    echo ""
    echo "The project includes its own SherpaOnnxWrapper.swift that matches the C API."
    echo "If you encounter API mismatches, copy the official file:"
    echo "  cp '${SWIFT_API}' '${SWIFT_SRC_DIR}/SherpaOnnxWrapper.swift'"
fi

# Step 5: Copy the C API header for the bridging header
C_API_HEADER="${BUILD_DIR}/sherpa-onnx/sherpa-onnx/c-api/c-api.h"
if [ -f "${C_API_HEADER}" ]; then
    mkdir -p "${FRAMEWORKS_DIR}/Headers"
    cp "${C_API_HEADER}" "${FRAMEWORKS_DIR}/Headers/c-api.h"
    echo "  Copied c-api.h to Frameworks/Headers/"
fi

echo ""
echo "========================================="
echo "Build complete!"
echo "========================================="
echo ""
echo "Frameworks available in: ${FRAMEWORKS_DIR}"
ls -la "${FRAMEWORKS_DIR}" 2>/dev/null || echo "(empty)"
echo ""
echo "Next steps:"
echo "  1. Open ProseCreatorTTS.xcodeproj in Xcode"
echo "  2. Add the xcframeworks from Frameworks/ to the project"
echo "  3. Set the bridging header to ProseCreatorTTS/SherpaOnnxBridge.h"
echo "  4. Build and run on your iOS device"
echo ""
echo "NOTE: sherpa-onnx requires a physical device (not Simulator)"
echo "      due to ARM-only ONNX runtime optimizations."
