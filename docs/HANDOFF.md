# 鲸鱼娘金库 · 会话交接（HANDOFF）

> **给下一个会话的自己 / 给合作者**：读完这一份就能接手，不用重新摸索。
> 面向使用者的说明书是 `docs/用户指引.md`；抓取规则的**定版规范**是 `docs/CAPTURE_RULES_V1.md`；
> 协作流程是 `CONTRIBUTING.md`；发版流程是 `docs/RELEASE.md`。

---

## 0. 现状（2026-10-26）

| 项 | 值 |
|---|---|
| 版本 | **v1.3.2**（versionCode **10302**，数据库 schema **v2**） |
| 提交 | `main` HEAD = `dafe76d`（tag `v1.3.2` = `eac3531`），已推送 GitHub |
| CI | ✅ 绿（GitHub Actions：`单元测试（108 个）` + `规则文件校验`） |
| 产物 | `dist/1.3.2/鲸鱼娘金库-v1.3.2.apk`（24.56 MB，仅 arm64-v8a） |
| 设备 | 用户手机已装 v1.3.2；**真实账本 30 笔**、`pendingCaptures=1`、`blocklist=0` |
| 余额基线 | `1:0, 2:-119910, 3:-38923`（账户 1:现金 2:微信* 3:支付宝） |
| 测试 | **108 个**单测全绿（含 22 个"逐条遍历 490 条规则"的断言） |

> ⚠️ **绝对不要清用户数据**。造测试数据后必须用 `del:<txnId>` / `delcap:<id>` 删干净，
> 并复核 `txnCount` / `balances` 回到上表基线。

---

## 1. 一页命令

```powershell
# 构建 / 测试（项目自带工具链，不动系统环境）
.\scripts\build.ps1 -Task ":app:testDebugUnitTest"
.\scripts\build.ps1 -Task assembleRelease

# CI 同款（仓库已有 gradle wrapper）
$env:JAVA_HOME="$pwd\.toolchain\jdk17"; $env:ANDROID_HOME="$pwd\.toolchain\android-sdk"
.\gradlew.bat :app:testDebugUnitTest --no-daemon

# 发布（测试 → 构建 → dist/<ver>/：APK + SHA256SUMS + VERSION + 使用说明 + 发布包 zip）
.\scripts\release.ps1
.\scripts\release.ps1 -SkipTests     # 已跑过测试
.\scripts\release.ps1 -GuideOnly     # 只刷新说明书/zip，不重新构建

# 装到手机（install -r 会重置权限，装完必须重新授权）
.\scripts\setup-phone.ps1 -Serial bd7a85e9
```

**`.ps1` 必须保存为 UTF-8 with BOM**，否则 PowerShell 5 报"缺少右 }"。

### 1.1 真机 diag 自检（改了抓取一定要跑）

```powershell
$adb=".toolchain\android-sdk\platform-tools\adb.exe"
$PKG="com.jingyuniang.vault/com.jingyuniang.ledger.MainActivity"
& $adb shell am start -n $PKG --es diag "<命令>"
& $adb logcat -d -s WhaleMoney:*
```

| 命令 | 用途 |
|---|---|
| `probe:<pkg>#<title>#<原文>` | **规则体检**：喂原文进闸门，输出 kind/shouldRead/金额/证据/置信度。**只判定不写库** |
| `simulate:<pkg>#<title>#<原文>` | 走**完整流水线**（写库），验证"到底记没记、记成什么" |
| `identity:<pkg>#<原文>` | 看交易身份（ref / card / app 三形态） |
| `reasoncheck` | 最近抓取的判定原因 |
| `captures` / `dump` / `settings` | 抓取记录 / 账目 / 配置（Key 打掩码） |
| `rules` / `exportrules` | 导出全部规则表（日志 / JSON 文件） |
| `importrules` / `resetrules` | 导入用户规则 / 恢复内置规则 |
| `snap` / `snaps` / `restoredb` | 生成快照 / 列出快照 / 用迁移前快照压回（重启生效） |
| `dumpdb` | 复制数据库到 `Android/data/<pkg>/files/dbdump`，可 `adb pull` 兜底 |
| `del:<txnId>` / `delcap:<id>` / `restorecap:<id>` | 删测试账目 / 删抓取记录 / 恢复被误忽略的抓取 |

⚠️ `probe`/`simulate` 分隔符是 `#`；**不能带空格和 `|`**（adb shell 会截断，踩过）。

---

## 2. 工程地图

```
app/src/main/
├── assets/capture_rules.json        ← 490 条识别规则（用户可改）
├── java/com/jingyuniang/ledger/
│   ├── MainActivity.kt              ← 全部 diag 命令
│   ├── WhaleApp.kt                  ← Services（服务定位器）+ 启动时恢复快照/自动快照
│   ├── capture/
│   │   ├── CaptureFilters.kt        ← 【核心】8 道闸门 shouldRead/judge + 三类 App 分级
│   │   ├── ParseEngine.kt           ← 【核心】金额打分、方向、商户、账户/分类匹配
│   │   ├── CapturePipeline.kt       ← 【核心】去重（身份/跨渠道/长窗）+ 置信度 + 入账
│   │   ├── RuleStore.kt             ← 规则库：用户文件 → assets → 代码兜底 + 加载校验
│   │   ├── TxnIdentity.kt           ← 交易身份（ref > 卡尾号+金额+时间桶 > 包名+金额+桶）
│   │   ├── CaptureBlocklist.kt      ← 「以后别再抓这类」黑名单
│   │   └── CaptureNotificationListener.kt / CaptureAccessibilityService.kt
│   ├── data/                        ← Room（AppDatabase/Entities/Daos/Repo/Prefs）
│   ├── io/BackupManager.kt          ← 加密备份（.whalebk，AES-GCM）
│   ├── io/SnapshotManager.kt        ← 自动快照（迁移前 db 复制 + 每日 JSON）
│   └── ui/                          ← Compose（CaptureScreen/SettingsScreens/AppViewModel…）
└── test/java/.../
    ├── CaptureRuleMatrixTest.kt     ← 37 个主路径场景（断言归类+金额+是否自动入账）
    ├── CaptureScenariosExtraTest.kt ← 80 个长尾场景（银行短信模板/微信支付宝/资金转移/干扰）
    ├── CaptureAllRulesTest.kt       ← **逐条遍历 18 张规则表 / 490 条**，删词即红
    └── RuleStoreTest / SnapshotVersionTest / CaptureFiltersTest / ParseEngineTest / BalanceParserTest / BillImporterTest
```

---

## 3. 抓取规则体系（改之前必读 `docs/CAPTURE_RULES_V1.md`）

**总原则：宁可漏记，不可错记。资格（shouldRead）与置信度分离，硬排除不能被高分翻盘。**

### 3.1 八道闸门（顺序固定）

```
长度/垃圾词 → 黑名单 → 营销渠道 → 必须有金额 → 聊天钱卡片 → 广告门
→ 资金转移(TRANSFER) → 非动账模板 → 列表页(≥3笔金额) → 来源分级
```

**来源分级**：支付通道（微信/支付宝/银行）宽松；电商（淘宝/京东/美团）只认**强支付模板**；
内容（抖音/快手/小红书/B站）同电商且**不读屏**；聊天类看标题是否支付发送方；短信最可靠即放行。

### 3.2 置信度（阈值默认 80）

| 情况 | 处理 |
|---|---|
| 读屏（ACCESS） | 封顶 79，永不自动入账 |
| 通知 + 有结构证据（尾号/账户/余额/单号/商户…） | 按原分（常见 88–95） |
| 通知 + 无证据但命中强支付模板 | 按原分（微信「已支付¥10」→ 90） |
| 两者皆无 | 封顶 79 |
| 聊天类且标题不是支付发送方 | 再 −20 |
| **退款** | **封顶 79**（要用户决定"冲销原账"还是当收入） |

### 3.3 去重（三档窗口）

| 档 | 窗口 | 条件 |
|---|---|---|
| 同渠道 | 150s | 交易身份相同 |
| 跨渠道·短 | 60s | 同金额 + 不同包名 |
| 跨渠道·长 | 10min | 同金额 + 本条写着「财付通/微信支付/支付宝/云闪付」+ 该渠道 App 10min 内报过同金额 |

已知边界：**银行通知先到、微信晚于 60s** 仍可能双记；**合并支付拆分金额**（零钱10+银行卡15）无法自动合并。

### 3.4 资金转移 vs 退款

- **资金转移**（还款/提现/ATM取现/理财申购赎回）→ 记 `TRANSFER`，不计收支。
  **只有信用卡还款**需要信用卡账户；其余按单边转移记（否则真实流水会被丢——v1.2.7 的回归教训）。
- **退款** → 回查 30 天内同金额支出，判定原因写 `疑似 #<id>「商户」（¥x）的退款`，
  待确认行出现「冲销原账」按钮 → 删原支出 + 本条不计收入。

---

## 4. 数据安全（唯一能一次毁掉账本的地方）

1. `AppDatabase` **不用** `fallbackToDestructiveMigration()`；迁移缺失时**抛异常**（数据文件保留，回装旧版可继续用）。
2. 加字段三件套：`@Database(version = N+1)` + `DB_VERSION` + `MIGRATIONS` 里加 `Migration(N, N+1)`。
3. `SnapshotManager.beforeMigration()` 在 Room 打开前读 SQLite 头 `user_version`，低于目标就复制 db+wal+shm。
4. 日常快照：升级后首次启动 / 每天一次（保留 7 份）；迁移前快照保留 3 份。
5. **绝对不要**为了"修复"迁移失败而加回 destructive fallback。

---

## 5. 已验证清单（真机证据）

| 项 | 证据 |
|---|---|
| schema v1→v2 迁移 | 升级后 `txnCount=30`、`balances=1:0, 2:-119910, 3:-38923` 一字不差；配置全保留 |
| 迁移前快照 | `pre_migrate_v1_to_v2_*.db`(139KB) + `-wal`(440KB) + `-shm` |
| 自动快照 | `auto_upgrade_from_v0_to_v10300_*.json`、`auto_manual_*.json` |
| 规则可编辑（v1.3.2） | 用户规则加 `ABCXYZ` → `好物100元ABCXYZ` 被拦；恢复内置 → 放行（同一文本只改规则文件） |
| 退款冲正 | `simulate:…退款到账10.00元` → `PENDING`，`reason=疑似 #68「微信」（¥10）的退款` |
| 跨渠道长窗 | 微信 27.00 → 90s → 招行「财付通-微信支付」27.00 → `已跳过`；同渠道两笔 28.00 → 记两条不误合 |
| 抓取准确率 | probe：抖音「9.9元包邮」拦 / 抖音「支付成功¥12.9」抓 / 淘宝「925银」拦且金额=0 / 招行「本期账单」拦 / 招行真实消费 87.13 抓 |
| 一键忽略 | 待确认 11 条 → `pendingCaptures=0`，已入账账目不变 |
| 抓取黑名单 | 点 🚫 → `blocklist=1` → 「已屏蔽的来源」→「全部恢复」归 0 |
| 升级保留配置 | `aiEnabled/model=sc-deepseek-flash/apiBase=litellm…/key=len=25` 升级前后一致 |

---

## 6. 坑（都踩过）

1. **`.ps1` 缺 UTF-8 BOM** → PowerShell 报"缺少右 }"；每次改完要重新加 BOM。
2. PowerShell 函数日志用 `Write-Host`，`return` 只返回值；否则 `$LASTEXITCODE` 判断失效。
3. **`am start --es` 参数不能带空格**；`|` 被 shell 当管道 → diag 统一用 `#` 分隔。
4. **PowerShell 单引号里的 `` `n `` 不是换行**，会写字面量进源码（本会话踩了多次）。改完 grep 反引号。
5. **Kotlin `isLetter()` 对中文也返回 true** → 用它判"编号前缀"会跳过所有中文短信金额。
6. 正则 `(\d{1,3}(?:,\d{3})*|\d+)` 的 `*` 应为 `+`，否则「1000.00」被截成 100（四位数金额全错）。
7. **「尾号」降权必须只看紧邻**：金额前 12 字里有"尾号"就 −6 分 → 「尾号8888账户取现500元」金额变 0。
8. 「余额宝」会被「余额」降权误伤 → 判断前先剔除产品名。
9. **`JUNK`（绝对垃圾）不能塞宽泛词**：「领取」曾导致真实红包被误杀。
10. `org.json` 在 JVM 单测里是空实现 → 需要 `testImplementation("org.json:json:20231013")`。
11. `AppLog` 必须包 runCatching，否则单测一打日志就 `Method w not mocked`。
12. **真实数据库绝不能入库**：`adb pull` 到仓库目录会被 `git add -A` 带走；
    已加进 `.gitignore`（`backup_real_db/`、`*.db-wal`、`*.db-shm`）。
13. 签名 keystore 与 `local.properties` 不入库（公开仓库放私钥 = 任何人能伪造更新包）。
14. MIUI 用 `adb install` 装新包名会被拦（USB 安装管理），演示机要保持**亮屏**。
15. `dumpState` 是 suspend，回调里用 `dumpStateAsync`。
16. `repo.accountDao().all()` 是属性不是函数（`repo.accountDao.all()`）。
17. KSP 对空 `arrayOf()` 报 `[MissingType]` → 写 `Array<androidx.room.migration.Migration>`。
18. GitHub 匿名 API 60 次/小时会 403；查 CI 抓 HTML 页面或用 `gh`。
19. `lifecycleScope.launch` 里 `this@MainActivity` 会被解析成 CoroutineScope → 用 `applicationContext`。
20. Room DAO 加查询要看清楚加到哪个 Dao（退款查询曾加进 CaptureDao 导致 Unresolved）。

---

## 7. Backlog（按收益排序）

| 优先级 | 项 | 说明 |
|---|---|---|
| P1 | 规则卡的「人话索引」 | 490 行 JSON 普通用户不会改。加"老被淘宝推送烦 → 往 nonTxnMarkers 加词"这类对照，或直接给"加一个屏蔽词"输入框 |
| P1 | P1-6 图片附件 | 记账拍小票存图（`res/xml/file_paths.xml` 已有 cache-path） |
| P2 | 应用锁入口 | `Settings.lockPin` 已有字段，缺 UI 与校验 |
| P2 | AI 第二轮 | 自由聊天（不锁账本）+ 推理模型 + 月报复盘；`ai/AiAgent` 已有工具路由 |
| P2 | 余额面板第 3 刀 | 无障碍读屏自动校准余额（复用 `BalanceParser.parse` / `guessAccountType`） |
| P2 | 银行正则模板库 | 每家银行一条模板，比通用评分准 |
| P3 | App 内检查更新 | `update.json` 格式在 `docs/RELEASE.md` §4.2 |
| P3 | 删 `ui/VoicePanel.kt` | 无用文件 |

---

## 8. 协作（有合作者会一起改）

- **许可证**：代码 MIT + 美术素材 CC BY-NC-SA 4.0（禁止商用）→ `LICENSE`、README「素材授权」。
- **CI**：`.github/workflows/ci.yml` —— push/PR 到 main 跑 108 个单测 + 规则 JSON 校验。**红灯不许合**。
- **流程**：功能走 `feat/xxx` 分支 + PR；`main` 只放发版提交；规则改动**单独 commit**（最易冲突）。
- **签名**：仓库**故意不含** `.jks`。合作者私密渠道拿到 `jingyuniang-release.jks` + 三个密码，
  然后 `.\scripts\setup-signing.ps1 -JksPath <路径>` 一步配好（写 `local.properties`，不入库）。
  没凭据也能构建，但出的是 unsigned 包，装不上手机。
- **证书指纹**：`SHA-256 = e7962e34c241992bb69d781a6451390871bce3c26fb67a9b45dcac0558ef1975`（CN=JingYuniang）。
- **不要做**：提交 `local.properties`/`*.jks`/真实 `.db`/`dist/` 里的 APK；加回 destructive fallback；
  为了"待确认少几条"放宽 `shouldRead`；改规则不跑测试 / 改 schema 不写迁移。

---

## 9. 素材授权（**不可上架**）

立绘 = 深海女仆工坊皮肤 **CC BY-NC-SA 4.0**（上善 → ZipZipPipe → Small-tailqwq）+「蓝色大肥鱼」存档（原作者版权）。

**只能个人非商业使用**：不得上应用商店、不得付费/带广告分发。About 页与 README 必须保留署名。
