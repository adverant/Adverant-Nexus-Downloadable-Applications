#!/bin/bash

# MageAgent DMG Installer Builder
# Creates a professional macOS DMG installer

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
BUILD_DIR="$PROJECT_ROOT/dist"
DMG_NAME="MageAgent-2.1.0"
VOLUME_NAME="MageAgent Installer"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  MageAgent DMG Installer Builder${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# Check for required tools
if ! command -v hdiutil &> /dev/null; then
    echo -e "${RED}Error: hdiutil not found (requires macOS)${NC}"
    exit 1
fi

# Create build directory
echo -e "${YELLOW}Creating build directory...${NC}"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/dmg-contents"

# Build the menu bar app
echo -e "${YELLOW}Building MageAgentMenuBar.app...${NC}"
cd "$PROJECT_ROOT/menubar-app"
./build.sh

# Copy app to DMG contents
echo -e "${YELLOW}Preparing DMG contents...${NC}"
cp -R "$PROJECT_ROOT/menubar-app/build/MageAgentMenuBar.app" "$BUILD_DIR/dmg-contents/"

# Create installer script that will run on first launch
cat > "$BUILD_DIR/dmg-contents/Install MageAgent.command" << 'INSTALLER'
#!/bin/bash

# MageAgent Installer Script
# Run this after dragging MageAgentMenuBar.app to Applications

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=========================================="
echo "  MageAgent Installation"
echo "=========================================="
echo ""

# Check for Apple Silicon
if [[ $(uname -m) != "arm64" ]]; then
    echo "Error: MageAgent requires Apple Silicon (M1/M2/M3/M4)"
    exit 1
fi

# Create directories
echo "Creating directories..."
mkdir -p ~/.claude/mageagent
mkdir -p ~/.claude/scripts
mkdir -p ~/.claude/debug
mkdir -p ~/.cache/mlx-models

# Download server files from GitHub
echo "Downloading server components..."
REPO_URL="https://raw.githubusercontent.com/adverant/nexus-local-mageagent/main"

curl -sL "$REPO_URL/mageagent/server.py" -o ~/.claude/mageagent/server.py
curl -sL "$REPO_URL/scripts/mageagent-server.sh" -o ~/.claude/scripts/mageagent-server.sh
chmod +x ~/.claude/scripts/mageagent-server.sh

# Install Python dependencies
echo "Installing Python dependencies..."
pip3 install --quiet mlx mlx-lm fastapi uvicorn pydantic huggingface_hub 2>/dev/null

# Create LaunchAgent for server (auto-start on boot)
echo "Setting up server auto-start..."
cat > ~/Library/LaunchAgents/ai.adverant.mageagent.plist << 'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>ai.adverant.mageagent</string>
    <key>ProgramArguments</key>
    <array>
        <string>/bin/bash</string>
        <string>-c</string>
        <string>~/.claude/scripts/mageagent-server.sh start</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <false/>
    <key>StandardOutPath</key>
    <string>~/.claude/debug/mageagent.log</string>
    <key>StandardErrorPath</key>
    <string>~/.claude/debug/mageagent.error.log</string>
</dict>
</plist>
PLIST

launchctl load ~/Library/LaunchAgents/ai.adverant.mageagent.plist 2>/dev/null || true

# Create LaunchAgent for MenuBar app (auto-start on boot)
echo "Setting up menu bar app auto-start..."
cat > ~/Library/LaunchAgents/ai.adverant.mageagent.menubar.plist << 'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>ai.adverant.mageagent.menubar</string>
    <key>ProgramArguments</key>
    <array>
        <string>/Applications/MageAgentMenuBar.app/Contents/MacOS/MageAgentMenuBar</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <false/>
    <key>StandardOutPath</key>
    <string>~/.claude/debug/mageagent-menubar.log</string>
    <key>StandardErrorPath</key>
    <string>~/.claude/debug/mageagent-menubar.error.log</string>
</dict>
</plist>
PLIST

launchctl load ~/Library/LaunchAgents/ai.adverant.mageagent.menubar.plist 2>/dev/null || true

# Start the server
echo "Starting MageAgent server..."
~/.claude/scripts/mageagent-server.sh start

# Wait for server
sleep 3

# Launch the MenuBar app now
echo "Launching MageAgent menu bar app..."
open /Applications/MageAgentMenuBar.app

# Test
if curl -s http://localhost:3457/health > /dev/null 2>&1; then
    echo ""
    echo "=========================================="
    echo "  Installation Complete!"
    echo "=========================================="
    echo ""
    echo "MageAgent is running at: http://localhost:3457"
    echo "Menu bar icon is now visible in your menu bar."
    echo ""
    echo "Both server and menu bar app will auto-start on boot."
    echo ""
    echo "Next steps:"
    echo "  1. Click the MageAgent menu bar icon"
    echo "  2. Download models (~110GB) via menu: Load Models > Load All"
    echo ""
    echo "Documentation: https://github.com/adverant/nexus-local-mageagent"
else
    echo ""
    echo "Warning: Server may not have started correctly."
    echo "Check logs: ~/.claude/debug/mageagent.log"
fi

echo ""
echo "Press Enter to close..."
read
INSTALLER

chmod +x "$BUILD_DIR/dmg-contents/Install MageAgent.command"

# Create README
cat > "$BUILD_DIR/dmg-contents/README.txt" << 'README'
MageAgent - Multi-Model AI Orchestration for Apple Silicon
==========================================================

Installation Steps:
1. Drag MageAgentMenuBar.app to your Applications folder
2. Double-click "Install MageAgent.command" to complete setup
   - The menu bar icon will appear automatically
   - Both server and menu bar app will auto-start on boot

Requirements:
- macOS 13.0 (Ventura) or later
- Apple Silicon (M1/M2/M3/M4)
- 64GB+ unified memory recommended (128GB for all models)
- Python 3.9+

The installer will:
- Download server components from GitHub
- Install Python dependencies (MLX, FastAPI)
- Configure auto-start on login (both server and menu bar app)
- Start the MageAgent server
- Launch the menu bar app immediately

Models (~110GB total) are downloaded on-demand via the menu bar app.

For help: https://github.com/adverant/nexus-local-mageagent
README

# Create symlink to Applications
ln -s /Applications "$BUILD_DIR/dmg-contents/Applications"

# Create DMG
echo -e "${YELLOW}Creating DMG...${NC}"
hdiutil create -volname "$VOLUME_NAME" \
    -srcfolder "$BUILD_DIR/dmg-contents" \
    -ov -format UDZO \
    "$BUILD_DIR/$DMG_NAME.dmg"

# Clean up
rm -rf "$BUILD_DIR/dmg-contents"

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  DMG Created Successfully!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "DMG Location: ${BLUE}$BUILD_DIR/$DMG_NAME.dmg${NC}"
echo ""
echo "To test: open $BUILD_DIR/$DMG_NAME.dmg"
