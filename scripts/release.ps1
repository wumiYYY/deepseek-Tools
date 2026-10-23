param(
    [switch]$SkipTests,
    [switch]$Install,
    [string]$Serial = "bd7a85e9"
)
# 鲸鱼娘金库 · 发布一条龙：单测 → 构建 → dist/<版本>/APK + SHA256 →（可选）装机
# 用法：
#   .\scripts\release.ps1
#   .\scripts\release.ps1 -SkipTests
#   .\scripts\release.ps1 -Install -Serial bd7a85e9
$ErrorActionPreference = "Continue"
$root = "D:\Tools\deepseek_build_Tools\everyday_money"
Set-Location $root

# ---- 1. 从 build.gradle.kts 读版本号（versionCode 由它派生，别在这里另写一份）----
$gradleFile = Join-Path $root "app\build.gradle.kts"
$m = Select-String -Path $gradleFile -Pattern 'val\s+versionNameValue\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"' -Encoding UTF8
if (-not $m) {
    Write-Output "❌ 没在 app/build.gradle.kts 里找到 versionNameValue = \"x.y.z\""
    exit 1
}
$versionName = $m.Matches[0].Groups[1].Value
$parts = $versionName.Split(".")
$versionCode = [int]$parts[0] * 10000 + [int]$parts[1] * 100 + [int]$parts[2]
Write-Output "=== 版本：v$versionName (versionCode=$versionCode) ==="

# ---- 2. 单测 ----
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

# ---- 3. 构建 release ----
Write-Output "=== 构建 release ==="
if ((Invoke-Gradle "assembleRelease") -ne 0) {
    Write-Output "❌ 构建失败"
    exit 1
}

# ---- 4. 产物落到 dist/<版本>/ ----
$src = Join-Path $root "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $src)) {
    Write-Output "❌ 没找到 $src"
    exit 1
}
$distDir = Join-Path $root "dist\$versionName"
New-Item -ItemType Directory -Force -Path $distDir | Out-Null
$apkName = "鲸鱼娘金库-v$versionName.apk"
$apkOut = Join-Path $distDir $apkName
Copy-Item $src $apkOut -Force

$hash = (Get-FileHash $apkOut -Algorithm SHA256).Hash.ToLower()
$sizeMB = [math]::Round((Get-Item $apkOut).Length / 1MB, 2)
"$hash  $apkName" | Set-Content -Path (Join-Path $distDir "SHA256SUMS.txt") -Encoding UTF8
"v$versionName ($versionCode)  $apkName  ${sizeMB}MB  $hash" | Set-Content -Path (Join-Path $distDir "VERSION.txt") -Encoding UTF8

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
Write-Output "  1) 真机自检（docs/RELEASE.md §3 的 diag 清单）"
Write-Output "  2) git add -A && git commit -m `"release: v$versionName`" && git tag v$versionName"
Write-Output "  3) 把 dist\$versionName\ 整个目录丢网盘/局域网"
