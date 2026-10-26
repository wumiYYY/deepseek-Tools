# 鲸鱼娘金库 · 发布与版本更新

> 一句话：**侧载发布，不上应用商店**。每次发版跑 `.\scripts\release.ps1`，产物进 `dist/<版本>/`，打 git tag。

---

## 0. 为什么不上架（也不发 APK）

立绘素材来自「深海女仆工坊」皮肤（**CC BY-NC-SA 4.0**）+ 蓝色大肥鱼存档（版权归原作者），
**仅限个人非商业使用，不可上架分发**。所以：

- ✅ 源码在私有/个人公开仓库里（`wumiYYY/deepseek-Tools`），素材按原授权随项目保留（CC BY-NC-SA 允许非商业分享，需署名 + 相同方式共享，App「关于」页已署名）
- ✅ 自己设备侧载、私人范围传 APK
- ❌ Google Play / 小米 / 华为 / OPPO 等任何商店
- ❌ 公开下载站、**GitHub Release 挂 APK**（仓库里也不放 APK：`.gitignore` 已排除 `dist/`、`*.apk`）

要公开分发安装包必须先换掉整套立绘素材并取得授权——那是另一件事，不在本发布流程范围内。

---

## 1. 版本号规则

只改一个地方：`app/build.gradle.kts` 里的 `versionNameValue`。

```kotlin
val versionNameValue = "1.2.0"        // 主.次.修订；功能=次，修 bug=修订
// versionCode 自动派生：10200（= 1*10000 + 2*100 + 0）
```

规则要点：

| 项 | 规则 |
|---|---|
| versionCode | `主*10000 + 次*100 + 修订`，**只增不减** |
| 覆盖安装 | 同签名 + `versionCode >= 已装版本`，数据保留；签名不同只能卸载重装 = 丢账本 |
| Room 数据库版本 | 与 `versionCode` **无关**；改表另见 §6 |

---

## 2. 发布流程（4 步）

远端仓库：**https://github.com/wumiYYY/deepseek-Tools**（分支 `main`，tag 走 `v<版本>`）。
本机已配好 `origin` 和 Git Credential Manager，`git push` 直接可用。

```powershell
# 0) 改版本号（app/build.gradle.kts 的 versionNameValue）+ 写 CHANGELOG.md

# 1) 一条命令：单测 → 构建 → 产物 + SHA256
.\scripts\release.ps1                       # 默认 arm64 release
.\scripts\release.ps1 -Install -Serial bd7a85e9   # 顺带装机 + 重授权

# 2) 真机自检（见 §3 清单）

# 3) 留痕 + 推远端
git add -A
git commit -m "release: v1.2.0"
git tag v1.2.0
git push origin main --tags

# 4) 分发：APK 不进仓库（.gitignore 已排除 dist/、*.apk），
#    把 dist/<版本>/ 里的 APK + SHA256SUMS.txt 单独丢网盘/局域网
```

---

## 3. 发布前检查清单

**必跑**

1. `.\scripts\build.ps1 -Task ":app:testDebugUnitTest"` → 全绿（当前 49 个）
2. `aapt dump badging` 核对 `versionCode/versionName` 与 `native-code: 'arm64-v8a'`
3. `apksigner verify --print-certs` 指纹应为 `e7962e34…ef1975`（换 keystore 会变 → 老用户升级失败）
4. 装机后 `install -r` **会重置运行时权限**，必须重跑 `scripts/setup-phone.ps1`

**真机自检（adb + logcat，不用点 UI）** — 完整命令见 `docs/HANDOFF.md` §1

```powershell
$adb = ".toolchain\android-sdk\platform-tools\adb.exe"
& $adb shell am start -n com.jingyuniang.vault/com.jingyuniang.ledger.MainActivity --es diag "dump"
& $adb shell am start -n com.jingyuniang.vault/com.jingyuniang.ledger.MainActivity --es diag "ai:发微信开销了十块钱"
& $adb shell am start -n com.jingyuniang.vault/com.jingyuniang.ledger.MainActivity --es diag "book:午饭25元"
& $adb shell am start -n com.jingyuniang.vault/com.jingyuniang.ledger.MainActivity --es diag "capture:2"
& $adb shell am start -n com.jingyuniang.vault/com.jingyuniang.ledger.MainActivity --es diag "ocrbal:content://media/external/images/media/<id>"
& $adb shell am start -n com.jingyuniang.vault/com.jingyuniang.ledger.MainActivity --es diag "backup:1234"
& $adb shell am start -n com.jingyuniang.vault/com.jingyuniang.ledger.MainActivity --es diag "restorefile:latest:1234"
& $adb logcat -d -s WhaleMoney:* | Select-String "DIAG|AGENT|AI book|BALANCE|抓取成功"
& $adb shell am start -n com.jingyuniang.vault/com.jingyuniang.ledger.MainActivity --es diag "del:<测试txnId>"
```

**验收线**：`AI book ok txnId=` 有号、`BALANCE ocr hit=` 有值、`抓取成功` 有行、`restore ok=true` 且笔数对得上。

---

## 4. 分发与更新

### 4.1 轻量（当前采用）

网盘/局域网目录放**整个 `dist/<版本>/`**（该目录由 `release.ps1` 自动生成）：

```
whale-vault/1.2.1/
├── 鲸鱼娘金库-v1.2.1.apk
├── SHA256SUMS.txt        # 校验用
├── VERSION.txt           # 一行：1.2.1 (10201) <sha256>
├── 使用说明.txt          # 给使用者看（纯文本，带 BOM，微信/记事本直接可读）
├── 使用说明.md           # 同一份，Markdown 版（源文件是 docs/用户指引.md）
└── 鲸鱼娘金库-v1.2.1-发布包.zip   # ↑ 前五个打包，专门用来过微信/QQ
```

**发给使用者时这三样一起发：APK + SHA256SUMS.txt + 使用说明**。少了说明，对方大概率卡在「通知使用权」上，然后反馈「什么都没抓到」。

> 📮 **微信 / QQ 不能直接发 `.apk`**（会提示"该文件类型暂不支持"）。所以直接发 **`…-发布包.zip`**（17MB，内含上面 5 个文件），对方"保存到手机 → 解压 → 点 APK 安装"。
> 备选：QQ 直接传 apk 一般可以；或把 APK 后缀改成 `.apk.1` 让对方改回来（不推荐，容易让不熟的人犯迷糊）；或上传网盘发链接。

安装：手机浏览器打开链接下载 → 允许「安装未知应用」→ 覆盖安装。
**装完提醒自己**：`setup-phone.ps1` 重授权（或进 App「权限向导」逐项点一遍）。

> ⚠️ **sha256 跨构建不可复现**：同样的源码重新 `assembleRelease`，zip 时间戳会让 hash 变一次。
> 校验只对照**当次 `dist/<版本>/SHA256SUMS.txt`**，别拿上一次构建的 hash 去比对。
> 只改文档、不想动 APK/hash 时用 `.\scripts\release.ps1 -GuideOnly`（只重新生成使用说明）。

### 4.2 将来加「检查更新」（预留格式，尚未实现）

```json
// https://<你的静态目录>/whale-vault/update.json
{
  "versionCode": 10200,
  "versionName": "1.2.0",
  "apk": "鲸鱼娘金库-v1.2.1.apk",
  "sha256": "……",
  "notes": "修复 AI 记账不落库；新增截图识别余额"
}
```

实现要点（做的时候照这个来）：

- 关于页加「检查更新」→ GET `update.json` → 比 `BuildConfig.VERSION_CODE`
- 下载到 `cacheDir` → **先校验 sha256** → `FileProvider` 拿 uri → `ACTION_VIEW` + `application/vnd.android.package-archive` 拉起系统安装器
- `AndroidManifest.xml` 需要 `<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />`
- `res/xml/file_paths.xml` 加 `<cache-path>` 段
- 失败要能降级：网络失败只提示「检查失败」，不影响手动安装

---

## 5. 签名与 keystore（最要命的一条）

- 文件：`keystore/jingyuniang-release.jks`（**已被 .gitignore 忽略**，只在工作机 + 离线备份里存在）
- 口令：在 `local.properties` 的 `WHALE_STORE_PASSWORD` / `WHALE_KEY_ALIAS` / `WHALE_KEY_PASSWORD`
  （也可用同名环境变量覆盖）。**不要把口令写回 `build.gradle.kts`**——那个文件会进 git。
- 指纹：`SHA-256 e7962e34c241992bb69d781a6451390871bce3c26fb67a9b45dcac0558ef1975`
- ⚠️ **keystore 丢失 = 以后无法覆盖升级**，只能卸载重装（账本全丢，除非有加密备份）。
  → 离线备份两份（例如密码管理器里的加密包 + 移动硬盘），别只放在项目目录。
- 想换口令可以，但 keystore 本身**不能换**（除非接受所有旧数据作废）。

---

## 6. 数据兼容

- 升级不改表结构时，`install -r` 直接保留数据（实测多次有效）。
- 改表结构（加字段/改索引）必须：`@Database(version = N+1)` + 在 `AppDatabase.MIGRATIONS` 里加 `Migration(N, N+1)`。
  漏了会命中 `fallbackToDestructiveMigration()` → **静默清空整库**，App 照常启动不报错。
- 发版前建议让用户（自己）先做一次「数据 → 创建加密备份」；备份文件在 `filesDir/backups/*.whalebk`，
  需要长期保存就用 App 里的「分享这个文件」导出去。

---

## 7. 回滚

```powershell
git log --oneline            # 找到上一版（tag 形如 v1.2.0；首个 tag 就是 v1.2.0）
git revert <commit>          # 或 git checkout <tag> -- app
# 然后**把版本号再往上提**（例如 1.2.1）重新打包，不要降 versionCode
.\scripts\release.ps1
```

想装回旧的 APK：`dist/<旧版本>/` 里还留着（注意 `versionCode` 更低，需要先卸载——会丢数据，
所以回滚的正确姿势是用旧版本代码重新打一个**更高 versionCode** 的包，或者先备份再卸载）。

---

## 8. 体积说明

APK 约 **24.5MB**（1.1.0 是 53.2MB）。构成：ML Kit OCR 的 `arm64-v8a` `.so` ~10.5MB、dex ~8.6MB、立绘资源 ~5MB。

`abiFilters` 只留 `arm64-v8a` 的副作用：**Android 模拟器（x86_64）上 OCR 会失败**。
要在模拟器调 OCR，临时在 `app/build.gradle.kts` 的 `ndk { abiFilters }` 里加回 `"x86_64"`。
