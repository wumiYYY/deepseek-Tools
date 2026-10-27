<#
.SYNOPSIS
    重新生成「给合作者的一包」——**这是一次性 onboarding 物料，不是每版都要发的发布物**。

.DESCRIPTION
    什么时候需要重新跑：
      · 换了新合作者
      · 改了《合作者交接》/HANDOFF 这类文档，想给对方一份最新快照
      · 口令/签名文件有变动（正常永远不会变）

    什么时候**不需要**：发新版本。代码和文档走 git（`git pull`），APK 由合作者用同一把钥匙自己打，
    或者等更新服务器上线后从 `update.json` 拉。判断标准：**只有"秘密"需要私发，别的一律进 git**。

    产物结构：
      <OutDir>/
        README-合作者交接.md       ← 主文档
        版本信息.txt               ← 哪个 commit / 哪个版本 / 生成时间 / 签名指纹
        签名/                      ← jks + local.properties + 装法说明（**秘密**）
        文档/                      ← HANDOFF / RELEASE / CAPTURE_RULES / 用户指引 / CONTRIBUTING / LICENSE
        更新服务/                  ← update.json 模板 + 生成脚本 + nginx 片段
        SHA256SUMS.txt
      <ZipPath>                    ← 整个目录打包（体积极小，因为默认不带 APK）

.PARAMETER SigningDir
    放签名文件的本机目录（默认 D:\Tools\deepseek_build_Tools\_signing_handover）。

.PARAMETER OutDir
    生成目录（会被覆盖）。

.PARAMETER IncludeApk
    把 dist 里最新一版 APK 也塞进去（约 +25MB）。**默认不带**：APK 一升级就过期，
    需要对照签名的话看「证书指纹」就够了。

.EXAMPLE
    .\scripts\make-handover.ps1
    .\scripts\make-handover.ps1 -IncludeApk
#>
param(
    [string]$SigningDir = "D:\Tools\deepseek_build_Tools\_signing_handover",
    [string]$OutDir = "D:\Tools\deepseek_build_Tools\_collaborator_handover",
    [string]$ZipPath = "",
    [switch]$IncludeApk
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$EXPECTED_FP = "e7962e34c241992bb69d781a6451390871bce3c26fb67a9b45dcac0558ef1975"

# ---- 1. 版本与提交（写进 版本信息.txt，方便对方知道自己拿的是哪一版快照）----
$gradle = Get-Content "app\build.gradle.kts" -Encoding UTF8
$version = ([regex]::Match($gradle, 'versionNameValue\s*=\s*"([0-9.]+)"')).Groups[1].Value
$versionCode = ([version]$version).Major * 10000 + ([version]$version).Minor * 100 + ([version]$version).Build
$head = (git rev-parse --short HEAD).Trim()
$branch = (git rev-parse --abbrev-ref HEAD).Trim()

# ---- 2. 校验签名文件（发出去之前必须确认是原版，否则对方签的包盖不了老用户）----
$jks = Join-Path $SigningDir "keystore\jingyuniang-release.jks"
if (-not (Test-Path $jks)) { Write-Host "❌ 找不到 $jks（用 -SigningDir 指定）" -ForegroundColor Red; exit 1 }
$keytool = Join-Path $root ".toolchain\jdk17\bin\keytool.exe"
if (-not (Test-Path $keytool)) { $keytool = "keytool" }
$jksPass = ""
$props = Join-Path $SigningDir "local.properties"
if (Test-Path $props) {
    $line = Get-Content $props -Encoding UTF8 | Where-Object { $_ -match '^\s*WHALE_STORE_PASSWORD\s*=' } | Select-Object -First 1
    if ($line) { $jksPass = ($line -split '=', 2)[1].Trim() }
}
if ($jksPass -eq "") { Write-Host "❌ $props 里没有 WHALE_STORE_PASSWORD，无法校验签名库" -ForegroundColor Red; exit 1 }
$vout = (& $keytool -list -v -keystore $jks -storepass $jksPass 2>&1) -join "`n"
$m = [regex]::Match($vout, "SHA256:\s*([0-9A-Fa-f:]+)")
if (-not $m.Success) { Write-Host "❌ 读不到证书指纹（口令对不对？）" -ForegroundColor Red; Write-Host $vout; exit 1 }
$fp = $m.Groups[1].Value.Replace(":", "").ToLower()
if ($fp -ne $EXPECTED_FP) {
    Write-Host "❌ 签名指纹是 $fp，不是原版 $EXPECTED_FP —— 这个包发出去会害对方签出盖不了老用户的包，已中止" -ForegroundColor Red
    exit 1
}
Write-Host "✅ 签名库校验通过：$fp" -ForegroundColor Green

# ---- 3. 铺目录 ----
if (Test-Path $OutDir) { Remove-Item $OutDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$OutDir\文档", "$OutDir\签名\keystore", "$OutDir\更新服务" | Out-Null

Copy-Item "docs\合作者交接.md" "$OutDir\README-合作者交接.md"
foreach ($f in @("HANDOFF.md", "RELEASE.md", "CAPTURE_RULES_V1.md", "用户指引.md")) {
    Copy-Item "docs\$f" "$OutDir\文档\$f"
}
Copy-Item "CONTRIBUTING.md", "LICENSE" "$OutDir\文档\"
Copy-Item "docs\update.json.example", "scripts\make-update-json.ps1" "$OutDir\更新服务\"
Copy-Item "docs\deploy\nginx-更新包托管.conf" "$OutDir\更新服务\"

# 签名资产（秘密）：jks + 口令模板 + 作者的原始文件（对照用）+ 装法说明
Copy-Item $jks "$OutDir\签名\keystore\jingyuniang-release.jks"
Copy-Item $props "$OutDir\签名\local.properties"
if (Test-Path (Join-Path $SigningDir "local.properties.orig")) {
    Copy-Item (Join-Path $SigningDir "local.properties.orig") "$OutDir\签名\local.properties.orig"
}
if (Test-Path (Join-Path $SigningDir "README-签名交接.md")) {
    Copy-Item (Join-Path $SigningDir "README-签名交接.md") "$OutDir\签名\"
}

$apkNote = "不包含（默认不打包：APK 每版都会过期，签名对照看下面的证书指纹即可）"
if ($IncludeApk) {
    $latest = Get-ChildItem "dist" -Directory | Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    if ($latest) {
        New-Item -ItemType Directory -Force -Path "$OutDir\发布物" | Out-Null
        Get-ChildItem $latest.FullName -Filter "*.apk" | Copy-Item -Destination "$OutDir\发布物\"
        Copy-Item (Join-Path $latest.FullName "SHA256SUMS.txt") "$OutDir\发布物\" -ErrorAction SilentlyContinue
        $apkNote = "dist\$($latest.Name)（含 APK，体积大）"
    }
}

# ---- 4. 版本信息（对方一眼知道自己拿的是哪一版快照）----
$info = @"
鲸鱼娘金库 · 合作者交接包

生成时间   ：$(Get-Date -Format "yyyy-MM-dd HH:mm:ss")
代码版本   ：v$version (versionCode $versionCode)
提交       ：$head（分支 $branch）
签名库     ：keystore/jingyuniang-release.jks
别名       ：jingyuniang
证书指纹   ：SHA-256 $EXPECTED_FP
APK        ：$apkNote

⚠️ 这个包**只发一次**。之后代码/文档走 git（git pull），
   APK 用同一把钥匙自己打（或等更新服务器从 update.json 拉）。
   只有在「换了新合作者 / 口令变了 / 想给对方一份最新文档快照」时才需要重新发。
"@
[System.IO.File]::WriteAllText("$OutDir\版本信息.txt", $info, (New-Object System.Text.UTF8Encoding($false)))

# ---- 5. 校验和 + 打包 ----
$lines = Get-ChildItem $OutDir -Recurse -File | Where-Object { $_.Name -ne "SHA256SUMS.txt" } |
    Sort-Object FullName |
    ForEach-Object {
        "{0}  {1}" -f (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower(),
        $_.FullName.Substring($OutDir.Length + 1).Replace('\', '/')
    }
[System.IO.File]::WriteAllText("$OutDir\SHA256SUMS.txt", ($lines -join "`n"), (New-Object System.Text.UTF8Encoding($false)))

if ($ZipPath -eq "") { $ZipPath = Join-Path (Split-Path -Parent $root) "jingyuniang-collaborator-handover.zip" }
if (Test-Path $ZipPath) { Remove-Item $ZipPath -Force }
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory($OutDir, $ZipPath, [System.IO.Compression.CompressionLevel]::Optimal, $false)

$mb = [math]::Round((Get-Item $ZipPath).Length / 1MB, 2)
Write-Host ""
Write-Host "=== 产物 ===" -ForegroundColor Cyan
Write-Host "  目录  $OutDir"
Write-Host "  zip   $ZipPath（${mb}MB）"
Write-Host "  版本  v$version ($versionCode) @ $head"
Write-Host ""
Write-Host "提醒：只有「新合作者 / 口令变化 / 想要最新文档快照」才需要重新发这个 zip；" -ForegroundColor Yellow
Write-Host "      发新版本不用发它 —— 代码文档走 git，APK 他自己打或从更新服务器拉。" -ForegroundColor Yellow
