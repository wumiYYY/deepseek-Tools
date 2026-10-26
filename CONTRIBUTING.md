# 参与开发

> 这份文档给**合作者**看。它假设你没有作者那台机器，也没有他的私人密码。
> 如果你只是想用这个 App，看 [`docs/用户指引.md`](docs/用户指引.md)。
> 如果你想改抓取规则（不写代码），看 [`docs/CAPTURE_RULES_V1.md`](docs/CAPTURE_RULES_V1.md) 第 7 节。

---

## 0. 一句话原则

**改规则必须跑测试，改数据库必须写迁移，这两件事没有例外。**

- 抓取规则和测试是**强绑定**的：`CaptureAllRulesTest` 会逐条遍历 490 条规则做行为断言，
  你删一个词它就会红——这是故意的，防止规则被悄悄放松。
- 数据库 schema 一旦加字段而忘了写迁移，**用户打开 App 会看到账本为空**（旧版本还会静默清库）。
  细节见 `docs/CAPTURE_RULES_V1.md` 第 6 节。

---

## 1. 环境准备

需要 **JDK 17** + **Android SDK（platform 35 / build-tools 35.0.0）**。

```bash
git clone https://github.com/wumiYYY/deepseek-Tools.git
cd deepseek-Tools
```

**用 Android Studio 打开根目录**即可（推荐），或者命令行：

```bash
# macOS / Linux
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug        # 编译
./gradlew :app:testDebugUnitTest    # 跑全部单测（108 个）
```

```powershell
# Windows
$env:JAVA_HOME = "C:\path\to\jdk17"
$env:ANDROID_HOME = "C:\path\to\android-sdk"
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

> 作者机器上用的是自带的 `.toolchain`（jdk17 / gradle-8.9 / android-sdk），
> 这个目录**不入库**。你用自己的 JDK + SDK 就行；`gradlew` 已经入库，不需要单独装 gradle。

---

## 2. 三种常见改动，各自的流程

### 2.1 只想加/删几个识别词（不写代码，最安全）

1. 打开 `app/src/main/assets/capture_rules.json`
2. 找到对应的表加/删词（字段说明见 `docs/CAPTURE_RULES_V1.md` 附录 A）
3. 跑 `./gradlew :app:testDebugUnitTest`
4. **单独一个 commit**，信息写清楚加了什么词、为什么：

```
feat(rules): 营销词增加「限时秒杀」「粉丝价」

原因：抖音小店的促销推送频繁误抓（样本：...）
```

⚠️ 不要在一次 commit 里既改规则又改代码——规则是**最容易冲突**的文件，单独提交方便回溯。

### 2.2 改抓取逻辑（写代码）

1. 先补**测试用例**（`CaptureRuleMatrixTest` 或 `CaptureScenariosExtraTest` 里加一条真实场景），
   让它先失败
2. 改 `CaptureFilters` / `ParseEngine` / `CapturePipeline`
3. 跑全量测试，**全绿才算完成**
4. 真机自检（见第 3 节）
5. PR 描述里贴：改了哪道闸门、新增/移除了哪些词、真机 probe 输出

### 2.3 改数据库（加字段/改表）

三件套，缺一不可：

1. `@Database(version = N + 1)`，同时把 `AppDatabase.DB_VERSION` 改掉
2. 在 `MIGRATIONS` 里加 `Migration(N, N+1)`（只加列用 `ALTER TABLE … ADD COLUMN … NOT NULL DEFAULT x`）
3. **真机验证**：升级前后 `txnCount` 与 `balances` 必须完全一致（用第 3 节的 `diag` 命令）

**绝对不要**加回 `fallbackToDestructiveMigration()` 来"修复"迁移失败——
那会在下次出错时静默清空用户账本。

---

## 3. 真机自检（改了抓取相关的一定要跑）

App 内置了一组 `diag` 命令，全部只打日志、不联网：

```bash
ADB="adb"   # 或 .toolchain/android-sdk/platform-tools/adb.exe
PKG="com.jingyuniang.vault/com.jingyuniang.ledger.MainActivity"

# 规则体检：把一段通知原文喂进闸门，只判定不写库
$ADB shell am start -n $PKG --es diag "probe:com.tencent.mm#微信支付#支付成功，已支付¥25.00元"

# 走完整流水线（会写库，用完记得 delcap 删掉）
$ADB shell am start -n $PKG --es diag "simulate:cmb.pb#招商银行#您账户3979…人民币10.00元"

# 看判定结果
$ADB shell am start -n $PKG --es diag "reasoncheck"
$ADB logcat -d -s WhaleMoney:* | Select-String "DIAG"
```

其他：`settings`（配置）、`captures`（抓取记录）、`dump`（账目）、`rules`（导出规则）、
`snap/snaps`（快照）、`del:<txnId>` / `delcap:<id>`（删测试数据）。

⚠️ `probe`/`simulate` 的参数分隔符是 `#`，**不能用空格和 `|`**（adb shell 会截断）。
完整列表见 `docs/HANDOFF.md` 第 1 节。

---

## 4. 分支与提交约定

- 直接在 `main` 上提交的只有：**发版提交**（`release: vX.Y.Z — …`）
- 其他一律开分支 + PR：

```bash
git checkout -b feat/capture-refund-off
# …改完、测试全绿…
git commit -m "feat(capture): 退款支持一键冲销原账"
git push -u origin feat/capture-refund-off
# 然后在 GitHub 上开 PR，等 CI 绿了再合并
```

- 提交信息用 `类型(范围): 说明`，类型取 `feat` / `fix` / `rules` / `docs` / `chore` / `release`
- **CI 必须绿**（GitHub Actions 会跑 108 个单测 + 校验规则 JSON）

---

## 5. 版本号与发布（重要：签名）

### 5.1 版本号规则

`app/build.gradle.kts` 里的 `versionNameValue = "1.3.2"` 是唯一来源，
`versionCode` 自动按 `major*10000 + minor*100 + patch` 生成（1.3.2 → 10302）。

**versionCode 必须单调递增**，否则手机上覆盖安装会失败。

### 5.2 签名（和作者保持一致）

`keystore/` 与 `*.jks` **被 .gitignore 排除**，仓库里没有签名文件——这是必须的：
签名私钥一旦公开，任何人都能伪造更新包。

要让合作者能出**可覆盖安装**的正式包，请通过**私密渠道**向仓库所有者索取：

- `jingyuniang-release.jks` 文件
- `WHALE_STORE_PASSWORD` / `WHALE_KEY_ALIAS` / `WHALE_KEY_PASSWORD` 三个值

拿到之后两种配置方式：

```powershell
# 方式一：脚本帮你写 local.properties
.\scripts\setup-signing.ps1 -JksPath "D:\path\to\jingyuniang-release.jks"

# 方式二：手写 local.properties（不入库）
WHALE_STORE_PASSWORD=xxxx
WHALE_KEY_ALIAS=whale
WHALE_KEY_PASSWORD=xxxx
```

也可以用环境变量（CI/临时构建更方便）：
`WHALE_STORE_PASSWORD`、`WHALE_KEY_ALIAS`、`WHALE_KEY_PASSWORD`。

没有签名凭据时构建仍会成功，但产出的是 **unsigned** 包，装不到手机上。

### 5.3 发版

```powershell
.\scripts\release.ps1                 # 跑测试 → 构建 → 产出 dist/<版本>/
.\scripts\release.ps1 -SkipTests      # 已跑过测试时跳过
.\scripts\release.ps1 -GuideOnly      # 只刷新 dist 里的说明书/zip，不重新构建
```

产物在 `dist/<版本>/`：APK + `SHA256SUMS.txt` + `VERSION.txt` + 使用说明 + 发布包 zip。
`dist/` 不随代码库分发（体积大），按需发给使用者。

⚠️ `.ps1` 脚本必须保存为 **UTF-8 with BOM**，否则 PowerShell 5 会报语法错误。

---

## 6. 不要做的事

- ❌ 提交 `local.properties`、`*.jks`、`*.keystore`、真实 `.db`、`dist/` 里的 APK
- ❌ 提交真实账本数据（`backup_real_db/` 已在 .gitignore）
- ❌ 加回 `fallbackToDestructiveMigration()`
- ❌ 为了让「待确认少几条」而放宽 `shouldRead`
- ❌ 把 `JUNK`（绝对垃圾词表）当通用广告词表用——真实红包曾因为 `JUNK` 里的「领取」被误杀
- ❌ 改规则不跑测试、改 schema 不写迁移

---

## 7. 许可证

代码与文档 **MIT**；角色立绘等美术素材 **CC BY-NC-SA 4.0（禁止商用）**。
详见 [`LICENSE`](LICENSE)，商用前必须先替换掉全部第三方素材。
