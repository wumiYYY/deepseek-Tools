param(
    [switch]$SkipTests,
    [switch]$GuideOnly,
    [switch]$Install,
    [switch]$PrepareUpdate,
    [string]$UpdateNotes = "",
    [string]$Serial = "bd7a85e9"
)
# 鲸鱼娘金库 · 发布一条龙：单测 → 构建 → dist/<版本>/APK + SHA256 + 使用说明 →（可选）装机
# 用法：
#   .\scripts\release.ps1                      # 完整发布
#   .\scripts\release.ps1 -SkipTests           # 跳过单测
#   .\scripts\release.ps1 -Install -Serial bd7a85e9
#   .\scripts\release.ps1 -GuideOnly           # 只重新生成 dist 里的「使用说明」，不重打包（sha 不变）
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# ---- 1. 从 build.gradle.kts 读版本号（versionCode 由它派生，别在这里另写一份）----
$gradleFile = Join-Path $root "app\build.gradle.kts"
$m = Select-String -Path $gradleFile -Pattern 'val\s+versionNameValue\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"' -Encoding UTF8
if (-not $m) {
    Write-Output "❌ 没在 app/build.gradle.kts 里找到 versionNameValue"
    exit 1
}
$versionName = $m.Matches[0].Groups[1].Value
$parts = $versionName.Split(".")
$versionCode = [int]$parts[0] * 10000 + [int]$parts[1] * 100 + [int]$parts[2]
Write-Output "=== 版本：v$versionName (versionCode=$versionCode) ==="
$distDir = Join-Path $root "dist\$versionName"

# ---- 2. 生成「给使用者看」的说明（.md 原样 + .txt 纯文本，带 BOM 防记事本乱码）----
function Sync-DistGuide {
    $guideSrc = Join-Path $root "docs\用户指引.md"
    if (-not (Test-Path $guideSrc)) {
        Write-Output "⚠ 没找到 docs\用户指引.md，跳过使用说明"
        return
    }
    New-Item -ItemType Directory -Force -Path $distDir | Out-Null
    Copy-Item $guideSrc (Join-Path $distDir "使用说明.md") -Force

    $md = Get-Content $guideSrc -Raw -Encoding UTF8
    $txt = $md -replace '(?m)^!\[[^\]]*\]\([^)]*\)\s*$', ''
    $txt = $txt -replace '(?m)^#{1,3}\s+(.+?)\s*$', '【$1】'
    $txt = $txt -replace '\*\*(.+?)\*\*', '$1'
    $txt = $txt -replace '\[([^\]]+)\]\([^)]+\)', '$1'
    $txt = "鲸鱼娘金库 v$versionName · 安装与使用说明" + "`r`n`r`n" + $txt.Trim()
    [System.IO.File]::WriteAllText(
        (Join-Path $distDir "使用说明.txt"),
        $txt,
        (New-Object System.Text.UTF8Encoding($true))
    )
    Write-Output "  使用说明.md / 使用说明.txt 已更新"
}

# ---- 2.5 打个 zip：微信/QQ 不能直接发 .apk，发这个 zip 最省事 ----
function Sync-DistZip {
    $zipPath = Join-Path $distDir "鲸鱼娘金库-v$versionName-发布包.zip"
    if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
    $apk = Join-Path $distDir "鲸鱼娘金库-v$versionName.apk"
    if (-not (Test-Path $apk)) {
        Write-Output "  （没有 APK，跳过 zip 打包）"
        return
    }
    $names = @("鲸鱼娘金库-v$versionName.apk", "SHA256SUMS.txt", "VERSION.txt", "使用说明.txt", "使用说明.md")
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::Open($zipPath, 'Create')
    try {
        foreach ($n in $names) {
            $p = Join-Path $distDir $n
            if (Test-Path $p) {
                [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $p, $n, 'Optimal') | Out-Null
            }
        }
    } finally {
        $zip.Dispose()
    }
    $mb = [math]::Round((Get-Item $zipPath).Length / 1MB, 2)
    Write-Output "  发布包 zip 已生成（${mb}MB，微信可直接发）"
}

if ($GuideOnly) {
    Sync-DistGuide
    Sync-DistZip
    Write-Output "（-GuideOnly：没有重新构建，APK 与 SHA256SUMS 保持不变）"
    exit 0
}

# ---- 3. 单测 / 构建 ----
function Invoke-Gradle([string]$task) {
    $out = & (Join-Path $root "scripts\build.ps1") -Task $task 2>&1
    # 注意：日志必须走 Write-Host（信息流），不能写成管道输出，否则会被当成函数返回值
    $out | Select-String "BUILD|FAILED|tests completed|e: " | ForEach-Object { Write-Host $_.Line }
    # build.ps1 末尾会打印 "=== gradle exit code: N ==="，以此为准（$LASTEXITCODE 对 PS 脚本不可靠）
    if ("$out" -match "gradle exit code:\s*(\d+)") { return [int]$Matches[1] } else { return 1 }
}

if (-not $SkipTests) {
    Write-Output "=== 单测 ==="
    if ((Invoke-Gradle ":app:testDebugUnitTest") -ne 0) {
        Write-Output "❌ 单测没过，发布中止"
        exit 1
    }
}

Write-Output "=== 构建 release ==="
if ((Invoke-Gradle "assembleRelease") -ne 0) {
    Write-Output "❌ 构建失败"
    exit 1
}

# ---- 4. 产物落到 dist/<版本>/ ----
$src = Join-Path $root "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $src)) {
    Write-Output "❌ 没找到已签名的正式安装包。请检查原版 keystore 和 local.properties 中的 WHALE_* 签名配置。"
    exit 1
}
# 正式发布必须与旧版使用同一身份，防止再次分发独立测试包或不同签名的安装包。
$env:JAVA_HOME = Join-Path $root ".toolchain\jdk17"
$buildTools = Join-Path $root ".toolchain\android-sdk\build-tools\35.0.0"
$certOutput = & (Join-Path $buildTools "apksigner.bat") verify --print-certs $src 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Output "❌ APK 签名验证失败，停止发布。"
    exit 1
}
$expectedCert = "e7962e34c241992bb69d781a6451390871bce3c26fb67a9b45dcac0558ef1975"
if (("$certOutput" -replace ':', '') -notmatch $expectedCert) {
    Write-Output "❌ APK 签名与原版不一致，无法覆盖升级，停止发布。请使用原版 keystore。"
    exit 1
}
$packageOutput = & (Join-Path $buildTools "aapt.exe") dump badging $src 2>&1
if ($LASTEXITCODE -ne 0 -or !("$packageOutput".Contains("name='com.jingyuniang.vault'")) -or
    !("$packageOutput".Contains("versionCode='$versionCode'"))) {
    Write-Output "❌ 包名或版本号不符合正式升级要求，停止发布。"
    exit 1
}
New-Item -ItemType Directory -Force -Path $distDir | Out-Null
$apkName = "鲸鱼娘金库-v$versionName.apk"
$apkOut = Join-Path $distDir $apkName
Copy-Item $src $apkOut -Force

$hash = (Get-FileHash $apkOut -Algorithm SHA256).Hash.ToLower()
$sizeMB = [math]::Round((Get-Item $apkOut).Length / 1MB, 2)
"$hash  $apkName" | Set-Content -Path (Join-Path $distDir "SHA256SUMS.txt") -Encoding UTF8
"v$versionName ($versionCode)  $apkName  ${sizeMB}MB  $hash" | Set-Content -Path (Join-Path $distDir "VERSION.txt") -Encoding UTF8
Sync-DistGuide
Sync-DistZip

if ($PrepareUpdate) {
    try {
        & (Join-Path $root "scripts\make-update-json.ps1") -Version $versionName `
            -PublishDir (Join-Path $root "dist\publish\whale-vault") `
            -BaseUrl "https://161.118.233.45/whale-vault" -Notes $UpdateNotes
        if (-not $?) { throw "更新清单生成失败" }
    } catch {
        Write-Output "❌ 更新清单生成失败：$($_.Exception.Message)"
        exit 1
    }
}

Write-Output ""
Write-Output "=== 产物 ==="
Write-Output "  $apkOut"
Write-Output "  体积   $sizeMB MB"
Write-Output "  sha256 $hash"

# ---- 5. 可选：装机 + 重授权 ----
if ($Install) {
    Write-Output "=== 装机 + 重授权 ==="
    & (Join-Path $root "scripts\setup-phone.ps1") -Serial $Serial -Apk $apkOut
}

Write-Output ""
Write-Output "下一步："
Write-Output "  1) 真机自检（docs/RELEASE.md 第 3 节 diag 清单）"
Write-Output "  2) git add -A; git commit -m `"release: v$versionName`"; git tag v$versionName; git push origin main --tags"
Write-Output "  3) 把 dist\$versionName\ 整个目录（APK + SHA256SUMS + 使用说明）发给使用者"
