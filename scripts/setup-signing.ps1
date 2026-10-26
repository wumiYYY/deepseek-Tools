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
    # ⚠️ 必须和正式版一致：改了这个值，Gradle 会拿着别的别名去签，
    # 出的包老用户装不上（INSTALL_FAILED_UPDATE_INCOMPATIBLE）。
    [string]$KeyAlias = "jingyuniang",
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
# Set-Content -Encoding UTF8 在 PowerShell 5 会写 BOM，而 Java 的 Properties.load()
# 会把 BOM 当成第一个键的一部分（变成一条垃圾键）。所以这里显式写「无 BOM」的 UTF-8。
[System.IO.File]::WriteAllText($props, ($lines -join "`n"), (New-Object System.Text.UTF8Encoding($false)))
Write-Host "✅ 已写入 local.properties（不会进 git）"

# 自检：真拿口令和别名去开一次密钥库。口令/别名错在这里就暴露，
# 而不是等到发版时 Gradle 静默降级成 unsigned 包（踩过）。
$keytool = Join-Path $root ".toolchain\jdk17\bin\keytool.exe"
if (-not (Test-Path $keytool)) { $keytool = "keytool" }
$probe = & $keytool -list -keystore $dest -storepass $StorePassword -alias $KeyAlias 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ 密钥库自检失败：口令或别名不对（别名 $KeyAlias）" -ForegroundColor Red
    Write-Host ($probe | Out-String)
    Write-Host "请核对后重跑；别在口令不对的情况下发版。" -ForegroundColor Yellow
    exit 1
}
Write-Host "✅ 密钥库自检通过（别名 $KeyAlias 存在）" -ForegroundColor Green
# 指纹比对：keytool 输出是 E7:96:2E:34… 这种带冒号的大写形式，
# 直接拿 -notmatch "e7962e34" 去比会永远不匹配（踩过：把好钥匙误报成野钥匙）。
$EXPECTED = "e7962e34c241992bb69d781a6451390871bce3c26fb67a9b45dcac0558ef1975"
$vout = (& $keytool -list -v -keystore $dest -storepass $StorePassword -alias $KeyAlias 2>&1) -join "`n"
$m = [regex]::Match($vout, "SHA256:\s*([0-9A-Fa-f:]+)")
if ($m.Success) {
    $fp = $m.Groups[1].Value.Replace(":", "").ToLower()
    Write-Host "   证书指纹：SHA256 $fp"
    if ($fp -eq $EXPECTED) {
        Write-Host "   ✅ 与正式版一致，出的包能覆盖安装老用户" -ForegroundColor Green
    } else {
        Write-Host "   ⚠️ 指纹不是原版（应为 $EXPECTED）——这是另一把钥匙，出的包老用户装不上！" -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "现在可以构建正式包了：" -ForegroundColor Cyan
Write-Host "  .\scripts\release.ps1"
Write-Host ""
Write-Host "验证签名是否和作者一致（cert SHA-256 应为 e7962e34…1975）：" -ForegroundColor Cyan
Write-Host "  keytool -list -v -keystore keystore\jingyuniang-release.jks | Select-String 'SHA256'"
