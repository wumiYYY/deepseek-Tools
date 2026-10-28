# 鲸鱼娘记账管家 - 一次性 Android 构建工具链安装脚本
# 安装内容: Temurin JDK 17 / Android cmdline-tools / platform-tools / android-35 / build-tools 35.0.0 / Gradle 8.9
# 幂等：已下载/已解压的会跳过（可反复重跑）
$ErrorActionPreference = "Continue"
$ProgressPreference = "SilentlyContinue"
$root = Split-Path -Parent $PSScriptRoot
$tc = Join-Path $root ".toolchain"
New-Item -ItemType Directory -Force -Path $tc | Out-Null

function Log($m) { Write-Output ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $m) }
function Get-File($url, $out) {
    if (Test-Path $out) { Log "skip download (exists): $(Split-Path $out -Leaf)"; return }
    Log "downloading $(Split-Path $out -Leaf)"
    Invoke-WebRequest -Uri $url -OutFile $out -UseBasicParsing -TimeoutSec 3600 | Out-Null
    if (-not (Test-Path $out)) { throw "download failed: $url" }
    Log "downloaded $(Split-Path $out -Leaf) -> $([math]::Round((Get-Item $out).Length/1MB,1)) MB"
}

# ---------- 1. JDK 17 ----------
$jdkDir = Join-Path $tc "jdk17"
if (-not (Test-Path (Join-Path $jdkDir "bin\java.exe"))) {
    Get-File "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk" (Join-Path $tc "jdk17.zip")
    Log "extracting JDK"
    if (Test-Path (Join-Path $tc "_jdk")) { Remove-Item (Join-Path $tc "_jdk") -Recurse -Force }
    Expand-Archive -Path (Join-Path $tc "jdk17.zip") -DestinationPath (Join-Path $tc "_jdk") -Force
    $inner = Get-ChildItem (Join-Path $tc "_jdk") -Directory | Select-Object -First 1
    if (Test-Path $jdkDir) { Remove-Item $jdkDir -Recurse -Force }
    Move-Item $inner.FullName $jdkDir
    Remove-Item (Join-Path $tc "_jdk") -Recurse -Force
}
$env:JAVA_HOME = $jdkDir
Log "JAVA_HOME=$env:JAVA_HOME"
$jv = (& (Join-Path $jdkDir "bin\java.exe") -version 2>&1 | Out-String).Trim()
Log ("  " + ($jv -replace "`r?`n", " | "))

# ---------- 2. Android cmdline-tools ----------
$sdk = Join-Path $tc "android-sdk"
$cmdtools = Join-Path $sdk "cmdline-tools\latest"
if (-not (Test-Path (Join-Path $cmdtools "bin\sdkmanager.bat"))) {
    Get-File "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" (Join-Path $tc "cmdtools.zip")
    Log "extracting cmdline-tools"
    $tmp = Join-Path $tc "_cmd"
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    Expand-Archive -Path (Join-Path $tc "cmdtools.zip") -DestinationPath $tmp -Force
    New-Item -ItemType Directory -Force -Path (Split-Path $cmdtools) | Out-Null
    Move-Item (Join-Path $tmp "cmdline-tools") $cmdtools
    Remove-Item $tmp -Recurse -Force
}
$sdkmanager = Join-Path $cmdtools "bin\sdkmanager.bat"
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
Log "ANDROID_HOME=$sdk"

# ---------- 3. accept licenses ----------
Log "accepting licenses"
$y = ("y`n" * 60)
$y | & $sdkmanager --sdk_root=$sdk --licenses 2>&1 | Select-Object -Last 3 | ForEach-Object { Log "  $_" }

# ---------- 4. install packages ----------
Log "installing platform-tools / platforms;android-35 / build-tools;35.0.0"
& $sdkmanager --sdk_root=$sdk "platform-tools" "platforms;android-35" "build-tools;35.0.0" 2>&1 | Select-Object -Last 6 | ForEach-Object { Log "  $_" }
Log "sdk exit code: $LASTEXITCODE"

# ---------- 5. Gradle ----------
$gradleDir = Join-Path $tc "gradle-8.9"
if (-not (Test-Path (Join-Path $gradleDir "bin\gradle.bat"))) {
    Get-File "https://services.gradle.org/distributions/gradle-8.9-bin.zip" (Join-Path $tc "gradle.zip")
    Log "extracting gradle"
    if (Test-Path (Join-Path $tc "_gradle")) { Remove-Item (Join-Path $tc "_gradle") -Recurse -Force }
    Expand-Archive -Path (Join-Path $tc "gradle.zip") -DestinationPath (Join-Path $tc "_gradle") -Force
    $inner = Get-ChildItem (Join-Path $tc "_gradle") -Directory | Select-Object -First 1
    if (Test-Path $gradleDir) { Remove-Item $gradleDir -Recurse -Force }
    Move-Item $inner.FullName $gradleDir
    Remove-Item (Join-Path $tc "_gradle") -Recurse -Force
}

Log "=== toolchain state ==="
foreach ($p in @("jdk17\bin\java.exe", "android-sdk\cmdline-tools\latest\bin\sdkmanager.bat", "android-sdk\platforms\android-35", "android-sdk\build-tools\35.0.0", "gradle-8.9\bin\gradle.bat")) {
    Log ("{0,-60} {1}" -f $p, (Test-Path (Join-Path $tc $p)))
}
Log "TOOLCHAIN SETUP FINISHED"
