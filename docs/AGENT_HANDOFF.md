# 开发交接 · 给下一个 Agent

> **这份文档是给「接手的 AI/开发者」看的**：读完它 + `docs/HANDOFF.md`，你应该能直接开工、改代码、
> 上真机验证、发版，而不用重新摸索。写于 **2026-10-27 / v1.4.5**。
>
> 阅读顺序建议：§0 速览 → §1 十五分钟上手 → §2 架构地图 → **§6 红线 + §7 坑**（最容易犯错的都在这）
> → §9 下一步。
>
> 另外两份必读：`docs/HANDOFF.md`（运行手册/命令/坑的原始清单）、`docs/CAPTURE_RULES_V1.md`（抓取规则定版）。

---

## 0. 一页速览（先看这张表）

| 项 | 值 |
|---|---|
| 产品 | 鲸鱼娘金库 · 安卓记账 App（Kotlin + Compose，纯本地，无账号、无广告 SDK） |
| 版本 | **v1.4.5** / versionCode **10405** / 数据库 schema **v2**（自 v1.3.0 起未变） |
| 包名 | `com.jingyuniang.vault`（**不能改**，改了老用户装不上） |
| 仓库 | `https://github.com/wumiYYY/deepseek-Tools`（**公开**，所以任何秘密都不能进 git） |
| 提交 | `main` HEAD 见 `git log -1`；tag 已到 **v1.4.5**，全部已 push |
| CI | GitHub Actions：`单元测试（191 个）` + `规则文件校验`，**当前绿**；红灯不许合 |
| 产物 | `dist/1.4.5/鲸鱼娘金库-v1.4.5.apk`（24.59 MB，仅 arm64-v8a） |
| 签名 | keystore `keystore/jingyuniang-release.jks`（**不入库**），别名 `jingyuniang`，证书 SHA-256 `e7962e34c241992bb69d781a6451390871bce3c26fb67a9b45dcac0558ef1975`（CN=JingYuniang） |
| 口令 | **仓库里没有**（公开仓库）。本机在 `local.properties`；对外发的是私密交接包 |
| 测试机 | Redmi K80 / Android 16 / HyperOS，adb serial **`bd7a85e9`**，用户已装 v1.4.5 |
| 真实账本 | 38 笔、`pendingCaptures=0`、`blocklist=1`；账户 `1:现金 8:微信 9:招商银行` |
| 余额基线 | `1:0, 8:25000, 9:1077100`（**单位是分**：微信 ¥250、招行 ¥10,771.00） |

⚠️ **不要在没被要求的情况下动用户数据**。任何造数据都要用 `del:<txnId>` / `delcap:<id>` 清掉，
并复核 `txnCount` / `balances` 回到上表。

---

## 1. 十五分钟上手

```powershell
# 0) 环境：项目自带工具链，不动系统
cd D:\Tools\deepseek_build_Tools\everyday_money

# 1) 签名（一次性）：把私密渠道拿到的 jks 放好并写入 local.properties
.\scripts\setup-signing.ps1 -JksPath <你的.jks>     # 口令交互输入
#   成功标志：✅ 密钥库自检通过 + ✅ 与正式版一致（指纹 e7962e34…1975）

# 2) 跑测试（191 个，约 30s）
.\scripts\build.ps1 -Task ":app:testDebugUnitTest"

# 3) 打正式包（测试 → 构建 → dist/<版本>/）
.\scripts\release.ps1
.\scripts\release.ps1 -SkipTests      # 测试已跑过
.\scripts\release.ps1 -GuideOnly      # 只刷新说明书/zip，不重打包

# 4) 装到手机（会重置权限，装完必须重新授权）
.\scripts\setup-phone.ps1 -Serial bd7a85e9 -Apk "dist\1.4.5\鲸鱼娘金库-v1.4.5.apk"
```

CI 同款（Linux）：

```bash
./gradlew :app:testDebugUnitTest --no-daemon --console=plain
```

**提交前自检**：`git status --short` 里不允许出现 `*.jks` / `local.properties` / `*.db` / `dist/`。

---

## 2. 架构地图（改代码前先认路）

```
app/src/main/
├── assets/capture_rules.json      ← 490 条识别规则（用户可在 App 内改/导入导出）
├── java/com/jingyuniang/ledger/
│   ├── MainActivity.kt            ← 全部 adb diag 命令（§8）+ Compose 导航
│   ├── WhaleApp.kt                ← Services 服务定位器（prefs/repo/appScope）+ 启动初始化
│   ├── capture/                   ← 【核心】抓取
│   │   ├── CaptureFilters.kt      ← 8 道闸门 shouldRead/judge + 三类 App 分级 + 转移/退款判定
│   │   ├── ParseEngine.kt         ← 金额打分、方向、商户抽取、账户匹配、分类匹配
│   │   ├── CapturePipeline.kt     ← 去重(3 档) + 置信度 + 分类兜底(AI/待分类) + 入账 book()
│   │   ├── RuleStore.kt           ← 规则库：用户文件 → assets → 代码兜底；单张表读写
│   │   ├── RuleGuide.kt           ← 「说人话的规则」索引：25 条症状 → 该动哪张表 + 加哪些词
│   │   ├── TxnIdentity.kt         ← 交易身份（ref > 卡尾号+金额+时间桶 > 包名+金额+桶）
│   │   └── CaptureBlocklist.kt / CaptureNotificationListener.kt / CaptureAccessibilityService.kt
│   ├── data/
│   │   ├── Entities.kt / Daos.kt / AppDatabase.kt   ← Room（schema v2）
│   │   ├── Repo.kt                ← 唯一数据入口
│   │   ├── BalanceMath.kt         ← 【余额唯一算法，v1.4.5】
│   │   ├── AccountMove.kt         ← 【删账户时搬账目，v1.4.5】
│   │   ├── SeedData.kt            ← 默认分类/账户/规则 + KEYWORD_REV（分类词表版本）
│   │   └── Prefs.kt               ← DataStore 设置
│   ├── ai/                        ← DeepSeekClient + AiService + AiAgent（工具路由）
│   ├── io/BackupManager.kt        ← 加密备份 .whalebk（AES-GCM）
│   ├── io/SnapshotManager.kt      ← 自动快照（迁移前 db 复制 + 每日 JSON）
│   ├── ui/                        ← Compose：Home/Capture/Stats/Plan/Settings/CategoryScreen…
│   └── util/AppLog.kt             ← 日志（tag `WhaleMoney`，一律包 runCatching，否则单测炸）
└── test/java/.../                 ← 191 个单测（§5 有清单）
```

**数据流**：通知/短信 → `CaptureNotificationListener` → `CapturePipeline.process()`
→ `CaptureFilters.judge()`（8 闸门）→ `ParseEngine.parse()`（金额/方向/商户/账户/分类）
→ 去重（3 档窗口）→ 置信度校正 → `book()`（写 txn）→ 通知栏。

---

## 3. 抓取流水线（改之前必读 `CAPTURE_RULES_V1.md`）

**总原则：宁可漏记，不可错记。资格（shouldRead）与置信度分离，硬排除不能被高分翻盘。**

八道闸门（顺序固定）：长度/垃圾词 → 黑名单 → 营销渠道 → 必须有金额 → 聊天钱卡片 → 广告门
→ 资金转移（TRANSFER）→ 非动账模板 → 列表页（≥3 笔金额）→ 来源分级。

来源分级：支付通道（微信/支付宝/银行）宽松；电商（淘宝/京东/美团）只认强支付模板；
内容（抖音/快手/小红书/B站）同电商且**不读屏**；短信最可靠即放行。

置信度（阈值 80）：读屏封顶 79；通知有结构证据按原分；聊天类标题非支付发送方 −20；**退款封顶 79**。

去重三档：同渠道 150s（交易身份）／跨渠道短 60s（同金额）／跨渠道长 10min（本条写着财付通/微信支付/支付宝/云闪付 + 该渠道 App 10min 内报过同金额）。

资金转移：还款/提现/取现/理财申购赎回/**银行卡↔微信零钱充值提现** → 记 `TRANSFER` 不计收支；
`matchTransferTarget()` 认出「另一方」账户就记**双边**（两边余额同时变），认不出就**单边只扣来源**，
**绝不猜一个账户去加钱**。只有信用卡还款必须有信用卡账户（v1.2.7 的回归教训）。

---

## 4. 分类系统（v1.4.2–1.4.5 重写，这块历史包袱最多）

链路：**商户抽取 → 商户清洗 → 商户记忆（只认用户教过的）→ 分类关键词 → AI 兜底 → 待分类**。

| 环节 | 规则 | 为什么 |
|---|---|---|
| 商户抽取 | `【…】`（招行「在【财付通-微信支付-瑞幸咖啡】发生…」）优先，其次「商户:xxx」「向X付款」 | 银行短信的商户在方括号里，旧版只认「商户:」→ 抽不到 → 退成 App 名 |
| 商户清洗 | 剥渠道前缀（财付通-微信支付-）、去分店括号（`兰溪手擀面（盛夏路店）`→`兰溪手擀面`） | 不剥就永远命中不了关键词 |
| 可用性 | `isUsableMerchant()`：App 名/银行名/渠道名/纯分店名（含路街号区镇城 + `店`）/含 3+ 数字/**包名域名** → **不算商户** | 垃圾值一旦学进记忆，会把该来源**所有**账单锁死成同一分类 |
| 学习 | **只有用户亲手定分类才学**（确认待确认项、改账目） | 自动入账拿"猜的结果"当记忆 = 自我强化错误 |
| 记忆匹配 | 完全相等 > 规则名 ≥3 字且被包含；**不合格历史键直接过滤** | 等于自动中和老版本脏记忆，不用删库 |
| 关键词 | `Category.keywords`（**在 DB 里，不在 capture_rules.json**）：商户命中 `5+词长`、正文 `2+词长/2`、分类名 `+3`，同分比最长命中词 | 长词压短词（美团外卖 > 美团） |
| 词表升级 | `SeedData.KEYWORD_REV` + `Repo.refreshCategoryKeywords()` 启动时**并集**进 DB | 分类行是首装写进库的，光改 SeedData 老安装吃不到；并集保留用户词、不动 schema |
| 猜不出 | 先问 AI（`AI_CATEGORY_TIMEOUT_SEC=6s` 硬超时），仍不行落**「待分类」**（`ParseEngine.UNCERTAIN_CATEGORY`，`ensureCategory` 按需建） | **不许再静默塞「其他」**——猜不出来要让人看见 |

词表 23 支出类 + 9 收入类，共 ~600 词。改词规矩：只放 2 字以上、同词不许跨类、优先商户名；
改完必须跑 `CategoryScenarioTest`（它直接拿种子表断言，删词即红）。

---

## 5. 余额口径（v1.4.5 起唯一算法）

余额 = `期初余额 + BalanceMath.delta(全部账目, 账户id)`：

| 账目 | 影响 |
|---|---|
| `INCOME` + `accountId=id` | `+amount` |
| `EXPENSE` + `accountId=id` | `-amount` |
| `TRANSFER` + `accountId=id` | `-amount` |
| `TRANSFER` + `toAccountId=id` | `+toAmountCents`（缺省按 amountCents） |
| 自己转给自己（迁移后可能出现） | **净 0**（旧 SQL `CASE` 只扣不加会凭空少钱） |
| `accountId=0` / 账户已删 | 不影响任何账户 → 计入 `orphanCount` |

- `Repo.accountBalance / accountBalanceMap` 都走 `BalanceMath`，**不要再写第二套 SQL**。
- `余额调整`（source=ADJUST）**算余额**、不算收支（统计按 source 过滤）。
- 删账户：UI 里点删除 → 名下有账目就弹「转到哪个账户」→ `AccountMove.remap`（`accountId` 与 `toAccountId` 一起改）→ 再归档。不迁就变孤儿。

---

## 6. 红线（踩了就是数据事故 / 安全事故，不是 bug）

1. **签名不能换**。同包名换签名 → `INSTALL_FAILED_UPDATE_INCOMPATIBLE` → 用户只能卸载重装 → 账本全丢。
   keystore 丢失 = 永远回不去。**keystore 不进 git、不进公开 CI、不上服务器**。
2. **秘密不进仓库**（仓库是公开的）：`.gitignore` 已含 `keystore/` `*.jks` `*.keystore` `*.p12`
   `local.properties` `dist/` `*.db-wal` `*.db-shm` `backup_real_db/`。写完代码用 `git status --short` 复核。
3. **不加回 `fallbackToDestructiveMigration()`**。迁移缺失就让它抛异常 —— 数据文件还在，回装旧版还能用。
4. **改 schema 必须三件套**：`@Database(version=N+1)` + `DB_VERSION` + `MIGRATIONS` 加 `Migration(N,N+1)`。
5. **不要为了「待确认少几条」放宽 `shouldRead`**；也不要把 `JUNK`（绝对垃圾）当通用广告词表
   （历史事故：「领取」把真实红包误杀）。
6. **结论要说清是"实测"还是"推断"**。这个项目里所有数值性结论都有真机日志/单测支撑，别写成"应该可以"。
7. **素材授权**：立绘 CC BY-NC-SA 4.0 + 原作者版权 → **只能个人非商业使用**，不得上架商店、不得公开分发 APK。
   要公开分发必须先换素材。

---

## 7. 坑（按类别，全部真机踩过）

### 7.1 环境 / 脚本
- **`.ps1` 必须 UTF-8 with BOM**，否则 PowerShell 5 报「缺少右 }」。改完重新加 BOM。
- PowerShell 单引号里的 `` `n `` 不是换行；`` `a `` `` `f `` `` `t `` 会变成控制字符写进文件
  （本项目 README 曾被吃掉 `assets`/`fallbackTo`/`txnCount` 的首字母，已修）。
- `Set-Content -Encoding UTF8` 在 PS5 会写 BOM，而 Java `Properties.load()` 会把 BOM 当键名 → 写 `local.properties` 用
  `[IO.File]::WriteAllText(..., UTF8Encoding($false))`。
- `keytool` 打印的指纹是 `E7:96:2E:34…`（大写+冒号），比对前先 `-replace ':',''` 再 `ToLower()`。
- CI 秒挂（job 只跑 20 多秒）= action 层，不是代码：`android-actions/setup-android@v3` 会去下 Google
  已下架的旧 cmdline-tools（404），必须 `@v4`；`gradle/actions/setup-gradle` 用 `@v6`。
- 看 CI 状态最省事的办法是徽章 SVG 的 `<title>`：
  `…/actions/workflows/ci.yml/badge.svg?branch=main&event=push`（`gh` 没装、匿名 API 会 403）。

### 7.2 真机调试协议（**这一节能省你一小时**）
```powershell
$adb=".toolchain\android-sdk\platform-tools\adb.exe"
$A="com.jingyuniang.vault/com.jingyuniang.ledger.MainActivity"
& $adb -s bd7a85e9 shell am start -S -n $A --es diag "probe:cmb.pb#招商银行#您账户3979消费48.00元"
& $adb -s bd7a85e9 logcat -d -s WhaleMoney:*
```
- **必须加 `-S`**（force-stop 后再起）。否则 MIUI 给 `launchedFromUid=10364`，diag 会被 guard 拒（`DIAG 拒绝非 shell 调用`）。
- `probe:`/`simulate:` 的分隔符是 `#`，**参数里不能有空格**（adb shell 会切词）→ 文案里空格去掉，或用 `¥`/`人民币` 替代。
- `diag dump` 只打**最近 6 条**、`diag captures` 只打**12 条**；要"翻页"得先删掉可见的。
- 查「为什么判成这个分类」用 `diag cats:<分类名>`（打印 DB 里那张表的**全量关键词**）。
- `diag bal:<账户id>:<元>` 的**单位是元**（`bal:8:250` = ¥250），别按分传。
- uiautomator 能 dump Compose 的文本节点，但**中文打不进输入框**（`input text` 只支持 ASCII）；
  弹窗底部按钮常被输入法遮住 → 先 `input keyevent 4` 收起键盘再点。
- MIUI 用 `adb install` 装包名不变的新版本没问题，但**装完权限会被重置**，必跑 `setup-phone.ps1` 重授权。

### 7.3 抓取 / 解析
- Kotlin `isLetter()` 对中文也返回 true → 用它判"编号前缀"会跳过所有中文短信金额。
- 金额正则 `(\d{1,3}(?:,\d{3})*|\d+)` 的 `*` 应为 `+`，否则「1000.00」被截成 100。
- 「尾号」降权只看**紧邻**（金额前 12 字），否则「尾号8888账户取现500元」金额变 0。
- 「余额宝」会被「余额」降权误伤 → 判前先剔除产品名。
- `probe` 做 A/B 时慎用「英文字母紧跟数字」：`ZZQ5元` 的 `extractAmount=0`（被当编号前缀）。
- 银行短信的账户**不能**被正文里的「微信支付」带走：`pkgToType` 要认 `cmb`（招行手机银行是 `cmb.pb`）。

### 7.4 Room / Compose
- `AppDatabase` 不用 destructive fallback；加字段三件套别漏。
- KSP 对空 `arrayOf()` 报 `[MissingType]` → 写 `Array<androidx.room.migration.Migration>`。
- Room DAO 加查询要看清加到哪个 Dao（退款查询曾加进 CaptureDao 导致 Unresolved）。
- `repo.accountDao().all()` 是属性不是函数。
- **Compose 里读普通 object 的属性不会触发重组**：`RuleStore.current` 这类要配 version 状态 + `remember(key)`，
  否则加了词界面还显示旧数字（真机踩过两次）。
- 提示要画在**操作点附近**：8 条结果列表把「已加入」画在尾部 = 没提示。
- `lifecycleScope.launch` 里 `this@MainActivity` 会被解析成 CoroutineScope → 用 `applicationContext`。

### 7.5 数据/余额
- **「银行卡 ↔ 微信零钱」是双边转移，不是支出**：词表缺「零钱充值/充值账户」时会被记成支出，
  再叠加包名识别失败 → 扣的是微信账户 → 用户看到「充值了钱反而变少」。
- **迁移账户后可能出现「自己转给自己」**：必须净 0。
- **删账户不迁账目 = 账目变孤儿**，余额凭空变；`orphanCount` 能数出来（见 §9 待办）。
- 退款一律进待确认（封顶 79），要用户决定「冲销原账」还是当收入。

---

## 8. diag 命令清单（MainActivity.handleDiag）

| 命令 | 用途 |
|---|---|
| `probe:<pkg>#<标题>#<原文>` | 规则体检：只判定不写库，输出 kind/shouldRead/金额/证据/置信度 |
| `simulate:<pkg>#<标题>#<原文>` | 走完整流水线（写库），验证"到底记没记、记成什么" |
| `identity:<pkg>#<原文>` | 看交易身份（ref / 卡尾号 / 包名三形态） |
| `reasoncheck` | 最近抓取的判定原因 |
| `captures`（12 条）/ `dump`（6 条）/ `settings` | 抓取记录 / 账目 / 配置（Key 打掩码） |
| `cats` / `cats:<分类名>` | 分类表体检（词条数 / 全量关键词） |
| `rules` / `exportrules` / `importrules` / `resetrules` | 规则表导出/导入/恢复 |
| `snap` / `snaps` / `restoredb` | 快照生成/列表/压回（重启生效） |
| `dumpdb` | 复制数据库到 `Android/data/<pkg>/files/dbdump`（adb pull 兜底） |
| `bal:<账户id>:<元>` | 走「更新余额」同一路径（写 ADJUST 流水） |
| `del:<txnId>` / `delcap:<id>` / `restorecap:<id>` | 删测试账目 / 删抓取记录 / 恢复被误忽略的抓取 |

---

## 9. 下一步（backlog，带验收标准）

| 优先级 | 任务 | 验收标准（做完怎么算过） |
|---|---|---|
| **P1** | **孤儿账目修复入口**：账户在 v1.4.5 之前被删过，账目挂在已删 id 上（当前账本就有：旧账户 2/3/7）。在「账户管理」加「这 N 笔挂在不存在的账户上 → 一键迁到某账户」 | `Repo.orphanTxnCount()` 归 0；迁移前后总资产守恒；`BalanceFlowTest` 加 2 条 |
| **P1** | P1-6 图片附件：记账拍小票存图（`res/xml/file_paths.xml` 已有 `files-path receipts/` 与 `cache-path`） | 拍照 → 存 `filesDir/receipts` → 明细可看 → 加密备份里带 photos 字段 |
| P1 | keystore 口令轮换（见 §10） | 用新口令能签名；`apksigner` 指纹不变 |
| P2 | 「待分类」批量纠正入口 | 统计页/待确认页能一次给多条指定分类；改完自动学商户 |
| P2 | 「收到别人转账」能否自动入账 | 只在「收款成功/你收到一笔 + 金额 + 付款人」同时满足时放行；现在一律 79 进待确认 |
| P2 | 时段×金额启发 | **先做统计**：拿真机历史账单跑一遍，命中时准确率 > 90% 才上；否则宁可留待分类 |
| P2 | 应用锁入口（`Settings.lockPin` 已有字段，缺 UI 与校验） | 冷启动要求输入 PIN；错误 5 次延迟 |
| P2 | AI 第二轮（自由聊天 + 月报复盘；`ai/AiAgent` 已有工具路由） | 不锁账本的闲聊可用；月报能引用真实数据 |
| P2 | 余额面板第 3 刀（无障碍读屏校准余额，复用 `BalanceParser.parse`） | 打开微信/支付宝余额页能读并预填 |
| P3 | App 内检查更新 | `update.json` 格式见 `docs/合作者交接.md` §4；需加 `REQUEST_INSTALL_PACKAGES` |
| P3 | 删 `ui/VoicePanel.kt` | 编译通过、无引用 |

---

## 10. 协作与发布

- **里程碑**：v1.4.0 规则卡人话索引 → v1.4.1 真机回归修 4 处 → v1.4.2 分类串味 → v1.4.3 词表扩到 600 +
  词长加权 + 词表版本升级 → v1.4.4 银行卡↔零钱充值识别为双边转账 → v1.4.5 余额算法单点化 + 删账户迁移。
- **分支**：功能走 `feat/xxx` + PR；`main` 只放发版提交；**规则改动单独 commit**（最易冲突）。
- **CI 红灯不许合**。
- **合作者**：仓库 collab（Write）；签名与文档打包用 `.\scripts\make-handover.ps1`（**只发一次**，
  之后代码文档走 git、APK 自己打或从更新服务器拉）。
- **更新服务器**：静态托管 + `update.json`（`scripts\make-update-json.ps1` 生成，会先核签名指纹），
  nginx 片段见 `docs/deploy/nginx-更新包托管.conf`；`update.json` 必须 `Cache-Control: no-store`。
- ⚠️ **keystore 口令曾在 `HANDOFF.md` / `合作者交接.md` 里明文出现过**（已从当前文件清除，但 **git 历史还在**）。
  建议轮换（轮换不换钥匙，证书指纹不变，老用户照常覆盖升级）：
  ```powershell
  $k=".toolchain\jdk17\bin\keytool.exe"
  & $k -storepasswd -keystore keystore\jingyuniang-release.jks      # 改库口令
  & $k -keypasswd -alias jingyuniang -keystore keystore\jingyuniang-release.jks  # 改私钥口令
  # 然后更新 local.properties 的 WHALE_STORE_PASSWORD / WHALE_KEY_PASSWORD，并重打私密交接包
  .\scripts\setup-signing.ps1 -JksPath keystore\jingyuniang-release.jks
  .\scripts\make-handover.ps1
  ```

---

## 11. 已验证清单（真机证据，别再重复验证）

| 项 | 证据 |
|---|---|
| 数据不丢（1.3.2→1.4.0→…→1.4.5 多轮升级） | 每轮装完 `txnCount`/`balances` 与升级前逐字一致；配置全保留；自动快照每次生成一份 |
| 分类准确 | 12 条招行句式批量重放 **12/12 对**；`滴滴出行→交通`、`瑞幸咖啡→餐饮`、`携程酒店→旅行`、`iCloud月费→订阅` |
| 分类兜底 | 关键词不命中 → AI 判成餐饮（张记饼铺）；断网 → ❓待分类（赵六杂货） |
| 串味修复 | 早年的 `cmb.pb→餐饮`、`招商银行→餐饮` 记忆已自动失效（不删库） |
| 充值识别 | 招行「微信零钱充值账户 ¥0.01」→ `资金转移：9 → 8（微信）`，微信 +1 分 / 招行 −1 分，不计收支 |
| 余额调整 | `bal:8:250` → ¥250、`bal:8:20100` → ¥20,100、调回 ¥250；**删掉 3 笔 ADJUST 后余额仍 ¥250** |
| 删账户迁移 | 建 ZZTEST 记 ¥1 → 删除时弹「还有 1 笔账目」→ 选微信 → 微信 ¥250→¥251、总资产 ¥11,021→¥11,022、招行不动 |
| 收到转账 | 微信「[转账] 张三向你转账 100 元」→ 置信度 79 → 待确认；单测断言方向/账户/余额 +100 |
| 规则可编辑 | 用户规则加 `ABCXYZ` → 该文本被拦；恢复内置 → 放行（只改规则文件） |
| 退款冲正 | 退款 → `PENDING`，原因写「疑似 #68『微信』（¥10）的退款」，待确认行有「冲销原账」 |

---

## 12. 单测清单（191 个）

| 文件 | 覆盖 |
|---|---|
| `CaptureAllRulesTest` | **逐条遍历 18 张规则表 / 490 条**（删词即红）+ 金额/账户/分类/置信度分支 |
| `CaptureRuleMatrixTest` / `CaptureScenariosExtraTest` | 37 主路径 + 80 长尾场景（6 家银行短信、微信支付宝各形态、资金转移、干扰） |
| `CategoryScenarioTest` | 34 条：**直接拿 SeedData 词表**断言 23+9 个分类的真实句式 + 反例 + 词表体检（长度/跨类重复） |
| `CategoryAccuracyTest` | 12 条：商户抽取/清洗/可用性/记忆匹配/污染中和（用例是招行真实原文） |
| `TransferFlowTest` | 6 条：`cmb.pb` 包名、零钱充值=转移、账户不被「微信」抢、双边目标识别 |
| `BalanceFlowTest` | 20 条：余额调整/收到转账/双边转账/信用卡还款/自转净0/孤儿/迁移守恒 |
| `RuleGuideTest` | 12 条：25 条「人话建议」逐条真加一遍 + 导出往返不丢词 + 能撤回 |
| `RuleStoreTest` / `SnapshotVersionTest` / `CaptureFiltersTest` / `ParseEngineTest` / `BalanceParserTest` / `BillImporterTest` / `CaptureBlocklistTest` | 规则加载/快照/schema/闸门/解析/余额 OCR/账单导入/黑名单 |

**加测试的默认姿势**：把真机原文抄进用例（别自己编），断言"余额/分类/是否入账"这类**可观测结果**，
而不是只断言函数返回中间值。
