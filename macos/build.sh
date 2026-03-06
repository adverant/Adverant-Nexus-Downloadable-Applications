#!/bin/bash
# Build Nexus Local Compute App

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_NAME="NexusLocalCompute"
BUILD_DIR="$SCRIPT_DIR/build"
APP_DIR="$BUILD_DIR/$APP_NAME.app"
CONTENTS_DIR="$APP_DIR/Contents"
MACOS_DIR="$CONTENTS_DIR/MacOS"
RESOURCES_DIR="$CONTENTS_DIR/Resources"

echo "Building Nexus Local Compute App..."

# Clean previous build
rm -rf "$BUILD_DIR"
mkdir -p "$MACOS_DIR" "$RESOURCES_DIR"

# Compile Swift files
echo "Compiling Swift..."
swiftc -o "$MACOS_DIR/$APP_NAME" \
    -framework Cocoa \
    -framework UserNotifications \
    -target arm64-apple-macos13.0 \
    -O \
    "$SCRIPT_DIR/MageAgentMenuBar/main.swift" \
    "$SCRIPT_DIR/MageAgentMenuBar/AppDelegate.swift"

# Copy Info.plist
echo "Copying Info.plist..."
cp "$SCRIPT_DIR/MageAgentMenuBar/Info.plist" "$CONTENTS_DIR/Info.plist"

# Create white app icon with transparent background
echo "Creating white app icon..."
ICONSET_DIR="$BUILD_DIR/AppIcon.iconset"
mkdir -p "$ICONSET_DIR"

# Use Python to create white icons from the transparent source
python3 << 'PYTHON_EOF'
from PIL import Image
import os

source = "/Users/don/Adverant/Adverant.ai/public/brand/adverant-icon-github-final.png"
output_dir = os.environ.get("ICONSET_DIR", "build/AppIcon.iconset")

img = Image.open(source).convert("RGBA")
pixels = img.load()
width, height = img.size

# Convert to white (preserve alpha)
for y in range(height):
    for x in range(width):
        r, g, b, a = pixels[x, y]
        if a > 0:
            pixels[x, y] = (255, 255, 255, a)

sizes = [
    (16, "icon_16x16.png"), (32, "icon_16x16@2x.png"),
    (32, "icon_32x32.png"), (64, "icon_32x32@2x.png"),
    (128, "icon_128x128.png"), (256, "icon_128x128@2x.png"),
    (256, "icon_256x256.png"), (512, "icon_256x256@2x.png"),
    (512, "icon_512x512.png"), (1024, "icon_512x512@2x.png"),
]

for size, filename in sizes:
    resized = img.resize((size, size), Image.LANCZOS)
    resized.save(os.path.join(output_dir, filename), "PNG")
PYTHON_EOF

# Convert to icns
iconutil -c icns "$ICONSET_DIR" -o "$RESOURCES_DIR/AppIcon.icns" 2>/dev/null || echo "Note: iconutil not available"

# Copy menu bar icon
cp "$HOME/.claude/mageagent-menubar/icons/icon_18x18@2x.png" "$RESOURCES_DIR/icon.png" 2>/dev/null || true

# Create PkgInfo
echo "APPLNexu" > "$CONTENTS_DIR/PkgInfo"

echo ""
echo "Build complete!"
echo "App location: $APP_DIR"
echo ""

# Install to Applications
INSTALL_DIR="/Applications"
if [ -d "$INSTALL_DIR" ]; then
    echo "Installing to /Applications..."
    rm -rf "$INSTALL_DIR/$APP_NAME.app"
    cp -R "$APP_DIR" "$INSTALL_DIR/"
    echo "Installed to $INSTALL_DIR/$APP_NAME.app"
fi

echo ""
echo "To run: open /Applications/$APP_NAME.app"
echo "To add to Login Items: System Settings > General > Login Items > + > Nexus Local Compute"
