#!/bin/bash

# Heimdall Whitelist Plugin Build Script

echo "Building Heimdall Whitelist Plugin..."

# Check if Maven is installed
if ! command -v mvn &> /dev/null; then
    echo "Error: Maven is not installed or not in PATH"
    echo "Please install Maven to build this plugin"
    exit 1
fi

# Clean and compile
echo "Running Maven clean compile..."
mvn clean compile

if [ $? -ne 0 ]; then
    echo "Compilation failed!"
    exit 1
fi

# Package
echo "Packaging plugin..."
mvn package -q

if [ $? -ne 0 ]; then
    echo "Packaging failed!"
    exit 1
fi

# Check if JAR was created
JAR_FILE=$(find target -name "*.jar" | grep -v "original-" | head -n 1)

if [ -f "$JAR_FILE" ]; then
    echo "✅ Build successful!"
    echo "Plugin JAR: $JAR_FILE"
    echo ""
    echo "To install:"
    echo "1. Copy $JAR_FILE to your server's plugins/ folder"
    echo "2. Restart your server or use '/hwl reload'"
    echo "3. Configure the plugin in plugins/HeimdallWhitelist/config.yml"
else
    echo "❌ Build failed - JAR file not found"
    exit 1
fi
