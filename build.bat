@echo off
REM Heimdall Whitelist Plugin Build Script for Windows

echo Building Heimdall Whitelist Plugin...

REM Check if Maven is installed
where mvn >nul 2>nul
if %errorlevel% neq 0 (
    echo Error: Maven is not installed or not in PATH
    echo Please install Maven to build this plugin
    pause
    exit /b 1
)

REM Clean and compile
echo Running Maven clean compile...
call mvn clean compile

if %errorlevel% neq 0 (
    echo Compilation failed!
    pause
    exit /b 1
)

REM Package
echo Packaging plugin...
call mvn package -q

if %errorlevel% neq 0 (
    echo Packaging failed!
    pause
    exit /b 1
)

REM Find the JAR file
for /f "tokens=*" %%i in ('dir /b target\*.jar ^| findstr /v "original-"') do set JAR_FILE=target\%%i

if exist "%JAR_FILE%" (
    echo ✅ Build successful!
    echo Plugin JAR: %JAR_FILE%
    echo.
    echo To install:
    echo 1. Copy %JAR_FILE% to your server's plugins/ folder
    echo 2. Restart your server or use '/hwl reload'
    echo 3. Configure the plugin in plugins/HeimdallWhitelist/config.yml
) else (
    echo ❌ Build failed - JAR file not found
    pause
    exit /b 1
)

pause
