<#
.SYNOPSIS
    从 dist/<版本>/ 自动生成「App 内置检查更新」要用的 update.json，并把 APK 摆成可静态托管的目录。

.DESCRIPTION
    只做静态文件，不需要任何后端。产物结构：

        <PublishDir>/
          update.json
          <版本>/jingyuniang-v<版本>.apk      ← 改成 ASCII 文件名，避免 URL 百分号编码
          <版本>/SHA256SUMS.txt

    把 PublishDir 整个丢到 nginx / 对象存储的 /whale-vault/ 下就行（配置片段见
    docs/合作者交接.md §4.2）。update.json 的 Cache-Control 必须 no-store，否则客户端拿旧版本号。

.PARAMETER Version
    dist 下的版本号；省略 = 自动取 dist 里最高的那个。

.PARAMETER PublishDir
    要生成到哪个目录（会被创建/覆盖同名版本文件）。

.PARAMETER BaseUrl
    这个目录最终对外的 URL 前缀，只写进控制台提示，不写进 json（json 里 apk 用相对路径）。

.PARAMETER Notes
    给用户看的一句话更新说明。

.PARAMETER MinSupportedVersionCode
    低于这个 versionCode 的旧版必须更新（0 = 不强制）。

.EXAMPLE
    .\scripts\make-update-json.ps1 -PublishDir D:\publish\whale-vault `
        -BaseUrl https://files.example.com/whale-vault -Notes "修 4 处交互问题"
#>
param(
    [string]$Version = "",
    [Parameter(Mandatory = $true)][string]$PublishDir,
    [Parameter(Mandatory = $true)][string]$BaseUrl,
    [string]$Notes = "",
    [int]$MinSupportedVersionCode = 0,
    [string]$Channel = "stable"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = Join-Path $root ".toolchain\jdk17"
$distRoot = Join-Path $root "dist"

# ---- 1. 选版本 ----
if ([string]::IsNullOrWhiteSpace($Version)) {
    $cand = Get-ChildItem $distRoot -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name } -Descending |
        Select-Object -First 1
    if (-not $cand) { Write-Host "❌ dist/ 下没有 <主>.<次>.<修> 形式的版本目录" -ForegroundColor Red; exit 1 }
    $Version = $cand.Name
}

$srcDir = Join-Path $distRoot $Version
$apkSrc = Get-ChildItem $srcDir -Filter "*.apk" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $apkSrc) { Write-Host "❌ 没找到 $srcDir 里的 APK，先跑 .\scripts\release.ps1" -ForegroundColor Red; exit 1 }

$versionCode = ([version]$Version).Major * 10000 + ([version]$Version).Minor * 100 + ([version]$Version).Build
$sha = (Get-FileHash $apkSrc.FullName -Algorithm SHA256).Hash.ToLower()
$size = (Get-Item $apkSrc.FullName).Length

# ---- 2. 校验签名（指纹对不上就别发，出了也装不上）----
$apksigner = Join-Path $root ".toolchain\android-sdk\build-tools\35.0.0\apksigner.bat"
$fp = ""
if (Test-Path $apksigner) {
    $out = & $apksigner verify --print-certs $apkSrc.FullName 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "APK 签名验证失败，停止生成更新信息" }
    $m = [regex]::Match($out, "SHA-256 digest:\s*([0-9A-Fa-f]+)")
    if ($m.Success) { $fp = $m.Groups[1].Value.ToLower() }
}
$expected = "e7962e34c241992bb69d781a6451390871bce3c26fb67a9b45dcac0558ef1975"
if ($fp -eq "") {
    throw "未读取到 APK 签名，停止生成更新信息"
} elseif ($fp -ne $expected) {
    Write-Host "❌ 这个包的签名指纹是 $fp，不是原版 $expected —— 老用户装不上，已中止" -ForegroundColor Red
    exit 1
} else {
    Write-Host "✅ 签名指纹与原版一致：$fp" -ForegroundColor Green
}

# ---- 3. 摆目录（ASCII 文件名）----
$verDir = Join-Path $PublishDir $Version
New-Item -ItemType Directory -Force -Path $verDir | Out-Null
$apkName = "jingyuniang-v$Version.apk"
Copy-Item $apkSrc.FullName (Join-Path $verDir $apkName) -Force
$aapt = Join-Path $root ".toolchain\android-sdk\build-tools\35.0.0\aapt.exe"
$badging = & $aapt dump badging (Join-Path $verDir $apkName) 2>&1 | Out-String
if ($LASTEXITCODE -ne 0 -or !$badging.Contains("name='com.jingyuniang.vault'") -or
    !$badging.Contains("versionCode='$versionCode'") -or !$badging.Contains("versionName='$Version'")) {
    throw "APK 包名或版本与发布目录不一致"
}
"$sha  $apkName" | Set-Content -Path (Join-Path $verDir "SHA256SUMS.txt") -Encoding UTF8

# ---- 4. 写 update.json（无 BOM！JSON 带 BOM 有些客户端会解析失败）----
$now = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
$json = [ordered]@{
    versionCode = $versionCode
    versionName = $Version
    apk = "$Version/$apkName"
    sha256 = $sha
    sizeBytes = $size
    signatureSha256 = $fp
    channel = $Channel
    publishedAt = $now
    minSupportedVersionCode = $MinSupportedVersionCode
    notes = $Notes
} | ConvertTo-Json -Depth 4
$jsonPath = Join-Path $PublishDir "update.json"
[System.IO.File]::WriteAllText($jsonPath, $json, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ""
Write-Host "=== 产物 ===" -ForegroundColor Cyan
Write-Host "  $jsonPath"
Write-Host "  $verDir\$apkName  ($([math]::Round($size / 1MB, 2)) MB)"
Write-Host "  sha256 $sha"
Write-Host ""
Write-Host "=== 记得做的事 ===" -ForegroundColor Cyan
Write-Host "  1) 把 $PublishDir 整个同步到服务器：$BaseUrl"
Write-Host "  2) update.json 必须 Cache-Control: no-store（否则客户端一直拿旧版本号）"
Write-Host "  3) 静态目录别开门，至少 Basic Auth / 签名 URL（素材授权：不能公开分发）"
Write-Host "  4) python scripts/deploy-update.py --publish-dir $PublishDir（SSH 密码交互输入，不上传签名私钥）"
