<#
.SYNOPSIS
    配置正式版签名（把 .jks 放好 + 写 local.properties），让合作者也能出「可覆盖安装」的 APK。

.DESCRIPTION
    仓库里**故意不放**签名文件：签名私钥一旦公开，任何人都能伪造更新包。
    请通过私密渠道向仓库所有者索取：
      · jingyuniang-release.jks
      · WHALE_STORE_PASSWORD / WHALE_KEY_ALIAS / WHALE_KEY_PASSWORD

.PARAMETER JksPath
    你收到的 .jks 文件路径（脚本会复制到 keystore/jingyuniang-release.jks）。

.PARAMETER StorePassword
    可省略，省略时交互式输入。

.PARAMETER KeyPassword
    可省略，默认与 StorePassword 相同。

.EXAMPLE
    .\scripts\setup-signing.ps1 -JksPath "D:\下载\jingyuniang-release.jks"
#>
param(
    [Parameter(Mandatory = $true)][string]$JksPath,
    [string]$StorePassword = "",
    [string]$KeyAlias = "whale",
    [string]$KeyPassword = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Test-Path $JksPath)) {
    Write-Host "❌ 找不到文件：$JksPath" -ForegroundColor Red
    exit 1
}

$destDir = Join-Path $root "keystore"
$dest = Join-Path $destDir "jingyuniang-release.jks"
New-Item -ItemType Directory -Force -Path $destDir | Out-Null
Copy-Item -Path $JksPath -Destination $dest -Force
Write-Host "✅ 签名文件已放到：$dest"

if ([string]::IsNullOrWhiteSpace($StorePassword)) {
    $secure = Read-Host "请输入签名库密码 WHALE_STORE_PASSWORD" -AsSecureString
    $StorePassword = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
}
if ([string]::IsNullOrWhiteSpace($KeyPassword)) { $KeyPassword = $StorePassword }

# 写 local.properties（已在 .gitignore，不会入库）
$props = Join-Path $root "local.properties"
$lines = @()
if (Test-Path $props) {
    $lines = Get-Content $props -Encoding UTF8 | Where-Object {
        $_ -notmatch '^\s*WHALE_STORE_PASSWORD\s*=' -and
        $_ -notmatch '^\s*WHALE_KEY_ALIAS\s*=' -and
        $_ -notmatch '^\s*WHALE_KEY_PASSWORD\s*='
    }
}
if (-not ($lines -match '^\s*sdk\.dir\s*=')) {
    $lines += "sdk.dir=" + (Join-Path $root ".toolchain\android-sdk").Replace('\', '\\')
}
$lines += "WHALE_STORE_PASSWORD=$StorePassword"
$lines += "WHALE_KEY_ALIAS=$KeyAlias"
$lines += "WHALE_KEY_PASSWORD=$KeyPassword"
$lines -join "`n" | Set-Content -Path $props -Encoding UTF8
Write-Host "✅ 已写入 local.properties（不会进 git）"

Write-Host ""
Write-Host "现在可以构建正式包了：" -ForegroundColor Cyan
Write-Host "  .\scripts\release.ps1"
Write-Host ""
Write-Host "验证签名是否和作者一致（cert SHA-256 应为 e7962e34…1975）：" -ForegroundColor Cyan
Write-Host "  keytool -list -v -keystore keystore\jingyuniang-release.jks | Select-String 'SHA256'"
