# build-android.ps1
# Automates the setup of a minimal portable Android build environment and builds the project.

param(
    [string]$InstallDir = "C:\Users\haizi\AndroidDevTools"
)

$ErrorActionPreference = "Stop"

# URLs for JDK and SDK
$JdkUrl = "https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip"
$SdkUrl = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"

# Resolve absolute path for InstallDir
$InstallDir = [System.IO.Path]::GetFullPath($InstallDir)
Write-Host "=============================================" -ForegroundColor Cyan
Write-Host "Minimal Android Build Environment Setup" -ForegroundColor Cyan
Write-Host "Target Directory: $InstallDir" -ForegroundColor Cyan
Write-Host "=============================================" -ForegroundColor Cyan

# Create Install Directory if it doesn't exist
if (!(Test-Path $InstallDir)) {
    New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
    Write-Host "Created installation directory: $InstallDir" -ForegroundColor Green
}

# 1. Setup JDK 17
$JdkHome = Join-Path $InstallDir "jdk17"
if (Test-Path $JdkHome) {
    Write-Host "JDK 17 already exists at $JdkHome. Skipping download." -ForegroundColor Yellow
} else {
    $JdkZip = Join-Path $InstallDir "jdk17.zip"
    Write-Host "Downloading JDK 17..." -ForegroundColor Green
    try {
        Start-BitsTransfer -Source $JdkUrl -Destination $JdkZip -ErrorAction Stop
    } catch {
        Write-Host "BitsTransfer failed, falling back to Invoke-WebRequest..." -ForegroundColor Yellow
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri $JdkUrl -OutFile $JdkZip -UseBasicParsing
    }
    
    $JdkTemp = Join-Path $InstallDir "jdk17-temp"
    Write-Host "Extracting JDK 17..." -ForegroundColor Green
    if (Test-Path $JdkTemp) { Remove-Item -Recurse -Force $JdkTemp }
    Expand-Archive -Path $JdkZip -DestinationPath $JdkTemp -Force
    
    # Locate the inner folder
    $SubDirs = Get-ChildItem -Path $JdkTemp -Directory
    if ($SubDirs.Count -eq 1) {
        Move-Item -Path $SubDirs[0].FullName -Destination $JdkHome -Force
    } else {
        Move-Item -Path $JdkTemp -Destination $JdkHome -Force
    }
    
    # Cleanup zip and temp dir
    if (Test-Path $JdkZip) { Remove-Item -Force $JdkZip }
    if (Test-Path $JdkTemp) { Remove-Item -Recurse -Force $JdkTemp }
    Write-Host "JDK 17 installed successfully." -ForegroundColor Green
}

# 2. Setup Android SDK Command-line Tools
$SdkRoot = Join-Path $InstallDir "android-sdk"
$CmdlineToolsRoot = Join-Path $SdkRoot "cmdline-tools"
$LatestToolsDir = Join-Path $CmdlineToolsRoot "latest"

if (Test-Path $LatestToolsDir) {
    Write-Host "Android Command-line Tools already exist at $LatestToolsDir. Skipping download." -ForegroundColor Yellow
} else {
    $SdkZip = Join-Path $InstallDir "sdk-tools.zip"
    Write-Host "Downloading Android SDK Command-line Tools..." -ForegroundColor Green
    try {
        Start-BitsTransfer -Source $SdkUrl -Destination $SdkZip -ErrorAction Stop
    } catch {
        Write-Host "BitsTransfer failed, falling back to Invoke-WebRequest..." -ForegroundColor Yellow
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri $SdkUrl -OutFile $SdkZip -UseBasicParsing
    }
    
    $SdkTemp = Join-Path $InstallDir "android-sdk-temp"
    Write-Host "Extracting Command-line Tools..." -ForegroundColor Green
    if (Test-Path $SdkTemp) { Remove-Item -Recurse -Force $SdkTemp }
    Expand-Archive -Path $SdkZip -DestinationPath $SdkTemp -Force
    
    # Re-structure: move $SdkTemp\cmdline-tools to $LatestToolsDir
    if (Test-Path $SdkRoot) { Remove-Item -Recurse -Force $SdkRoot }
    New-Item -ItemType Directory -Path $LatestToolsDir -Force | Out-Null
    
    $SourceTools = Join-Path $SdkTemp "cmdline-tools"
    Move-Item -Path "$SourceTools\*" -Destination $LatestToolsDir -Force
    
    # Cleanup zip and temp dir
    if (Test-Path $SdkZip) { Remove-Item -Force $SdkZip }
    if (Test-Path $SdkTemp) { Remove-Item -Recurse -Force $SdkTemp }
    Write-Host "Android Command-line Tools installed successfully." -ForegroundColor Green
}

# 3. Environment Variables configuration for the current process
$env:JAVA_HOME = $JdkHome
$env:ANDROID_HOME = $SdkRoot
$env:PATH = "$JdkHome\bin;$LatestToolsDir\bin;$env:PATH"

Write-Host "JAVA_HOME set to: $env:JAVA_HOME" -ForegroundColor Gray
Write-Host "ANDROID_HOME set to: $env:ANDROID_HOME" -ForegroundColor Gray

# 4. Accept Licenses
$SdkManager = Join-Path $LatestToolsDir "bin\sdkmanager.bat"
Write-Host "Accepting Android SDK licenses..." -ForegroundColor Green
$y = , "y" * 100
$y | & $SdkManager --licenses --sdk_root=$SdkRoot

# 5. Install Required SDK Platform and Build Tools
Write-Host "Installing Android SDK platforms and build-tools (API 34)..." -ForegroundColor Green
& $SdkManager "platforms;android-34" "build-tools;34.0.0" "platform-tools" --sdk_root=$SdkRoot

# 6. Build the project using Gradle wrapper
Write-Host "=============================================" -ForegroundColor Cyan
Write-Host "Building Transcoder Android Demo App..." -ForegroundColor Cyan
Write-Host "=============================================" -ForegroundColor Cyan

# Run gradlew
$GradleW = Join-Path $PSScriptRoot "gradlew.bat"
if (!(Test-Path $GradleW)) {
    Write-Error "Could not find gradlew.bat in the current directory: $PSScriptRoot"
}

# We run gradlew with local JDK and SDK configuration
& $GradleW :demo:assembleDebug --no-daemon

if ($LASTEXITCODE -eq 0) {
    Write-Host "=============================================" -ForegroundColor Green
    Write-Host "Build Succeeded!" -ForegroundColor Green
    $ApkPath = Join-Path $PSScriptRoot "demo\build\outputs\apk\debug\demo-debug.apk"
    if (Test-Path $ApkPath) {
        Write-Host "APK location: $ApkPath" -ForegroundColor Green
    }
    Write-Host "=============================================" -ForegroundColor Green
} else {
    Write-Error "Gradle build failed with exit code $LASTEXITCODE"
}
