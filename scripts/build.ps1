param(
    [string]$Task = "assembleRelease",
    [switch]$Info
)
# 鲸鱼娘记账管家 构建入口：固定工具链路径，避免污染系统环境
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = Join-Path $root ".toolchain\jdk17"
$env:ANDROID_HOME = Join-Path $root ".toolchain\android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"
Set-Location $root

$gradle = Join-Path $root ".toolchain\gradle-8.9\bin\gradle.bat"
& $gradle --no-daemon --console=plain $Task
$code = $LASTEXITCODE
Write-Output "=== gradle exit code: $code ==="
exit $code
