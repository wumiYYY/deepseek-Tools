param(
    [string]$Apk = "D:\Tools\deepseek_build_Tools\everyday_money\app\build\outputs\apk\release\app-release.apk",
    [string]$Serial = ""
)
# 鲸鱼娘金库 · 手机一键安装与权限配置
# 用法：.\scripts\setup-phone.ps1            （自动选唯一设备）
#       .\scripts\setup-phone.ps1 -Serial xxx （多设备时指定）
$ErrorActionPreference = "Continue"
$root = "D:\Tools\deepseek_build_Tools\everyday_money"
$adb = "$root\.toolchain\android-sdk\platform-tools\adb.exe"

$PKG = "com.jingyuniang.vault"
$LISTENER = "com.jingyuniang.vault/com.jingyuniang.ledger.capture.CaptureNotificationListener"
$ACCESS = "com.jingyuniang.vault/com.jingyuniang.ledger.capture.CaptureAccessibilityService"

function ADB {
    if ($Serial -ne "") { & $adb -s $Serial @args } else { & $adb @args }
}

Write-Output "=== 1. 检查设备 ==="
$devices = (& $adb devices) | Select-String "device$" | ForEach-Object { ($_ -split "`t")[0] }
if (-not $devices) {
    Write-Output "没有找到已授权的设备。请检查："
    Write-Output "  1) 开发者选项里 USB 调试 已开"
    Write-Output "  2) 插线后下拉通知栏，USB 用途选「传输文件 / MTP」"
    Write-Output "  3) 手机弹出的「允许 USB 调试吗」勾选“始终允许”并确定"
    exit 1
}
if ($Serial -eq "") { $Serial = $devices[0] }
Write-Output "使用设备：$Serial"

Write-Output "=== 2. 安装 APK ==="
ADB install -r $Apk
Start-Sleep -Seconds 2   # 小米装完会重置权限，等安装器落定再授权
Write-Output "=== 3. 版本确认 ==="
ADB shell dumpsys package $PKG | Select-String -Pattern "versionName|firstInstallTime" | Select-Object -First 2

Write-Output "=== 4. 运行时权限 ==="
foreach ($p in @("android.permission.POST_NOTIFICATIONS","android.permission.RECEIVE_SMS","android.permission.READ_SMS","android.permission.CAMERA","android.permission.RECORD_AUDIO")) {
    $r = ADB shell pm grant $PKG $p 2>&1
    if ($LASTEXITCODE -eq 0) { Write-Output "  granted: $p" } else { Write-Output "  skip   : $p ($r)" }
}

Write-Output "=== 5. 通知使用权（最关键，也是你在设置里找不到的那个） ==="
ADB shell cmd notification allow_listener $LISTENER 2>&1 | Out-Null
$cur = ADB shell settings get secure enabled_notification_listeners
Write-Output "  enabled_notification_listeners = $cur"
if ("$cur" -notmatch "jingyuniang") {
    Write-Output "  cmd 方式未生效，尝试直接写 settings（部分系统需要）"
    ADB shell settings put secure enabled_notification_listeners "$LISTENER" 2>&1 | Out-Null
    Write-Output "  再查：" (ADB shell settings get secure enabled_notification_listeners)
}

Write-Output "=== 6. 悬浮球权限（显示在其他应用上层） ==="
ADB shell appops set $PKG SYSTEM_ALERT_WINDOW allow 2>&1 | Out-Null
ADB shell appops get $PKG SYSTEM_ALERT_WINDOW 2>&1 | Select-Object -First 1

Write-Output "=== 7. 电池优化白名单（防后台被杀） ==="
ADB shell dumpsys deviceidle whitelist +$PKG 2>&1 | Select-Object -First 2

Write-Output "=== 8. 无障碍增强（可选，默认关闭；需要时再开） ==="
Write-Output "  如需开启，执行："
Write-Output "  adb shell settings put secure enabled_accessibility_services $ACCESS"
Write-Output "  adb shell settings put secure accessibility_enabled 1"

Write-Output "=== 9. 结果校验 ==="
$check = ADB shell settings get secure enabled_notification_listeners
Write-Output "  通知监听: $check"
ADB shell dumpsys package $PKG | Select-String -Pattern "grantedPermissions" -Context 0,6 | Select-Object -First 1
Write-Output ""
Write-Output "=== 10. 权限落定复核 ==="
Start-Sleep -Seconds 1
$granted = ADB shell dumpsys package $PKG 2>&1 | Select-String -Pattern "POST_NOTIFICATIONS: granted=true|RECEIVE_SMS: granted=true"
if ($granted) { Write-Output "  运行时权限已生效" } else { Write-Output "  ⚠ 权限似乎没落定，请再跑一次本脚本" }

Write-Output "完成。若「自动记账」页仍显示未开启，请在手机上打开一次 App 后重新运行本脚本。"
