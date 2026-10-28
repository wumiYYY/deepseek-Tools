# 鲸鱼娘金库 · 会话交接（HANDOFF）

> **给下一个会话的自己 / 给合作者**：读完这一份就能接手，不用重新摸索。
> **如果把开发工作转给另一个 AI/agent，先让它读 `docs/AGENT_HANDOFF.md`**（一页速览 + 架构 + 红线 + 坑 + backlog，是这份的"蒸馏版"）。
> 面向使用者的说明书是 `docs/用户指引.md`；抓取规则的**定版规范**是 `docs/CAPTURE_RULES_V1.md`；
> 协作流程是 `CONTRIBUTING.md`；发版流程是 `docs/RELEASE.md`；
> **要拿签名、要部署服务器 / 做内置更新、想看红线 → `docs/合作者交接.md`**。

---

## 0. 现状（2026-10-28）

> ⚠️ 本节由「上游作者」维护到 v1.4.5；**1.4.6 / 1.4.7 是合作者通过 PR #1 合并进来的**（公司 AI 端点 + 在线升级）。
> 接手前先读 `docs/AGENT_HANDOFF.md`（已按 v1.4.7 更新）与 `docs/在线升级.md`。

| 项 | 值 |
|---|---|
| 版本 | **v1.4.7**（versionCode **10407**，数据库 schema **v2**，未动 schema） |
| 提交 | `main` HEAD 见 `git log -1`（`1ccd152` = PR #1 合并，含 1.4.6 公司 AI + 1.4.7 在线升级） |
| CI | ✅ 绿（GitHub Actions：`单元测试（218 个）` + `规则文件校验`） |
| 产物 | 本机 `dist/` 只到 **1.4.5**；1.4.6 / 1.4.7 由合作者在自己的构建机产出并发布到更新服务器 |
| 在线升级 | 1.4.7 起支持：启动每天静默检查 + 「我的 → 关于 → 检查更新」；清单 `https://161.118.233.45/whale-vault/update.json`，需 `X-Whale-Update-Token`（见 `docs/在线升级.md`） |
| 设备 | 用户手机最后一次由本会话装的是 **v1.4.5**；1.4.6/1.4.7 未在本机回归 |
| 真实账本 | 38 笔、`pendingCaptures=0`；账户 = `1:现金, 8:微信, 9:招商银行`（旧的 2/3/7 已删，见 §6.37） |
| 余额基线 | `1:0, 8:25000, 9:1077100`（分；1:现金 8:微信 ¥250 9:招商银行 ¥10,771.00） |
| 测试 | **218 个**（本会话原有 191 + 合作者新增 `AiEndpointTest` 10 / `DeepSeekClientTest` 7 / `UpdateClientTest` 10） |
| 新分类 | 系统分类「待分类」（❓，`isSystem`）= 「猜不出来」的去处，**不要删** |

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
| `cats` / `cats:<分类名>` | 分类表体检：id / 名称 / 词条数；指定名字时打印**DB 里全量关键词**（查「为什么判成这个类」） |
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
│   │   │                              + 单张表读写（table/withTable/appendWords/addWords/removeWord）
│   │   ├── RuleGuide.kt             ← 「说人话的规则」索引：症状 → 该动哪张表 + 加哪些词（v1.4.0）
│   │   ├── TxnIdentity.kt           ← 交易身份（ref > 卡尾号+金额+时间桶 > 包名+金额+桶）
│   │   ├── CaptureBlocklist.kt      ← 「以后别再抓这类」黑名单
│   │   └── CaptureNotificationListener.kt / CaptureAccessibilityService.kt
│   ├── data/                        ← Room（AppDatabase/Entities/Daos/Repo/Prefs）
│   ├── io/BackupManager.kt          ← 加密备份（.whalebk，AES-GCM）
│   ├── io/SnapshotManager.kt        ← 自动快照（迁移前 db 复制 + 每日 JSON）
│   └── ui/                          ← Compose（CaptureScreen/SettingsScreens/AppViewModel/RuleGuideCard…）
└── test/java/.../
    ├── CaptureRuleMatrixTest.kt     ← 37 个主路径场景（断言归类+金额+是否自动入账）
    ├── CaptureScenariosExtraTest.kt ← 80 个长尾场景（银行短信模板/微信支付宝/资金转移/干扰）
    ├── CaptureAllRulesTest.kt       ← **逐条遍历 18 张规则表 / 490 条**，删词即红
    ├── RuleGuideTest.kt             ← **把 25 条「人话建议」逐条真加一遍**，说假话即红（v1.4.0）
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

### 3.4 分类（v1.4.2 重写，改之前必读）

分类链路：**商户记忆 → 分类关键词 → AI（可选）→ 待分类**。

| 环节 | 规则 | 为什么这么定 |
|---|---|---|
| 商户抽取 | `【…】`（招行这类「在【财付通-微信支付-瑞幸咖啡】发生…」）优先，其次「商户:xxx」「向X付款」… | 银行短信的商户就在方括号里；旧版只认「商户:」标签，导致抽不到 |
| 商户清洗 | 剥渠道前缀（财付通-微信支付-）、去分店括号（`兰溪手擀面（盛夏路店）`→`兰溪手擀面`） | 不剥的话关键词永远命中不了 |
| 商户可用性 | `isUsableMerchant()`：App 名 / 银行名 / 渠道名 / 纯分店名（盛夏路店）/ 含 3 个以上数字 → **不算商户** | 它们一旦被学进记忆，会把该来源的**所有**账单锁死到同一分类 |
| 学 习 | **只有用户亲手定的分类才学**（确认待确认项、手动改账目） | 自动入账时"猜"出来的分类拿去做记忆 = 自我强化错误（v1.4.1 事故） |
| 记忆匹配 | 完全相等 > 规则名 ≥3 字且被包含；**不合格的历史键直接忽略** | 等于自动中和老版本写进去的脏记忆，不用删库 |
| 关键词 | `Category.keywords`（DB 里，**不在** capture_rules.json）：商户命中 `5+词长`，正文命中 `2+词长/2`，分类名命中 +3；同分比最长命中词 | v1.4.3 起按词长加权，长词（美团外卖）压过短词（美团） |
| 关键词升级 | `SeedData.KEYWORD_REV` + `Repo.refreshCategoryKeywords()`（启动时一次性**并集**进 DB） | 分类行是首次启动写进库的，光改 SeedData 对老安装无效；并集保留用户自己加的词，不动 schema |
| 猜不出 | 先问 AI（`AI_CATEGORY_TIMEOUT_SEC = 6s` 硬超时），仍不行就落**「待分类」**（`ParseEngine.UNCERTAIN_CATEGORY`，`repo.ensureCategory` 按需建） | 不许再静默塞进「其他」——猜不出来要让人看见 |

**已知待改进**（backlog）：「待分类」目前只能去明细里一条条改（想批量纠正要新 UI）；时段×金额启发没做
（精度不够，宁可不猜 —— 见 §7）。

### 3.5 资金转移 vs 退款

- **资金转移**（还款/提现/ATM取现/理财申购赎回/**银行卡↔微信零钱、支付宝余额的充值提现**）→ 记 `TRANSFER`，不计收支。
  银行卡 ↔ 零钱这种是**双边转移**（v1.4.4）：`matchTransferTarget()` 从正文认出另一方账户，
  记 `acc=来源, toAccount=另一方`，两边余额同时变。**认不出另一方就只扣来源（单边），绝不猜一个账户去加钱。**
  真机事故：招行短信「在【财付通-微信支付-微信零钱充值账户】发生快捷支付扣款」——既没认出是充值、
  又被正文里的「微信」带跑把账户判成微信，结果「微信余额反而变少」，见 §6.36。
- **只有信用卡还款**需要信用卡账户；其余优先双边、退化为单边（否则真实流水会被丢——v1.2.7 的回归教训）。
- **退款** → 回查 30 天内同金额支出，判定原因写 `疑似 #<id>「商户」（¥x）的退款`，
  待确认行出现「冲销原账」按钮 → 删原支出 + 本条不计收入。

---

### 3.6 余额口径（v1.4.5，只有这一处算法）

余额 = `期初余额 + BalanceMath.delta(全部账目, 账户id)`，规则：

| 类型 | 对账户的影响 |
|---|---|
| `INCOME` + `accountId=id` | `+amount` |
| `EXPENSE` + `accountId=id` | `-amount` |
| `TRANSFER` + `accountId=id` | `-amount`（转出） |
| `TRANSFER` + `toAccountId=id` | `+toAmountCents`（转入；缺省按 amount） |
| 其它（`accountId=0`、**账户已删**） | 不影响任何账户 → 计入 `orphanCount` |

- 以前这段逻辑在 SQL（`balanceDelta`）里，和手算容易跑偏；现在**只有一个实现** `data/BalanceMath.kt`，
  `Repo.accountBalance / accountBalanceMap` 都走它，`BalanceFlowTest` 直接断言「哪个账户动了多少钱」。
- **自己转给自己**（迁移后可能出现）按**净 0** 处理 —— 旧 SQL 的 `CASE` 只扣不加，会凭空少一笔。
- `余额调整`（source=ADJUST）**算余额**、不算收支（统计口径另行按 source 过滤）。
- **删除账户会先把账目迁走**：UI 里点删除 → 若名下有账目，弹「转到哪个账户」，迁移后再归档
  （`AccountMove.remap` + `Repo.migrateAccountTxns`）。不迁就会变孤儿，余额凭空变。

> ⚠️ 账本里可能已经有**历史孤儿**（账户删于 v1.4.5 之前）。`Repo.orphanTxnCount()` 能数出来，
> 但**还没有修复入口**（见 §7 backlog）：orphanCount > 0 时，这些账目不进任何账户余额。

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
| 升级保留配置 | `aiEnabled/model=<自填>/apiBase=<自填，长度不变>/key=len=25` 升级前后一致（**别把真实内网地址写进文档，这是公开仓库**） |
| 签名一致性（v1.4.0 会话） | `keytool` 读 `keystore/jingyuniang-release.jks`、`apksigner` 读 `dist/1.3.2` 与 `dist/1.4.0` 的 APK —— 三处 SHA-256 都是 `e7962e34…1975`，`CN=JingYuniang`，v2 签名方案 |
| 交接脚本可用（v1.4.0 会话） | `setup-signing.ps1` 在一个「只有 jks」的干净路径上跑通：放好 keystore、写好 `local.properties`（无 BOM）、自检打印 `✅ 与正式版一致`，退出码 0 |
| 说人话的规则（v1.4.0） | `RuleGuideTest` 12 个断言：25 条建议逐条真加一遍 + 导出/解析往返不丢词 + 重复加不撑表 + 能撤回 |
| 说人话的规则（v1.4.1 真机） | 点「加入这 3 个词」→ 计数 44→48 / 490→494 / 494→497 实时变化，来源立刻变「用户文件（capture_rules.json）」；自填词 `ZQTEST2` 加进非动账模板后**就地**提示「已加入」 |
| 加词真的会改判定（真机 A/B） | 同一段 `宝贝ZQTEST消费5元`（支付宝）：加词前 `probe → shouldRead=true extractAmount=500`；把 `ZQTEST` 加进 nonTxnMarkers 后 `nonTxn=true → shouldRead=false`；`resetrules` 后恢复 true |
| 升级不动数据（1.3.2→1.4.0→1.4.1） | 三次安装后 `txnCount=30`、`balances=1:0, 2:-119910, 3:-38923` 一字不差；配置全保留（`key=len=25`、`threshold=80`、`blocklist=0`、`access=true`） |
| 升级自动快照 | `auto_upgrade_from_v10302_to_v10400_*.json`、`auto_upgrade_from_v10400_to_v10401_*.json` 各生成一份 |
| 分类准确性（v1.4.2 真机 A/B） | 同一批短信重放：`【财付通-微信支付-滴滴出行】`→ cat=3 交通（改造前是**餐饮**）；`【…瑞幸咖啡】`→ 餐饮；正文无关键词的「张记饼铺」→ AI 兜底 → 餐饮；**断网**后「赵六杂货」→ ❓「待分类」(cat=35) |
| 老脏记忆自动失效 | 手机上残留的「招商银行→餐饮」不再生效（`CategoryAccuracyTest.历史污染的记忆会被自动忽略` 守着）；且新账目不再产生这类脏键 |
| 改造后数据没动 | 四个测试账目 + 六个抓取行全删干净：`txnCount=36`、`balances=1:0, 2:-131250, 3:-38923`、`pendingCaptures=0`（与测试前一字不差） |
| 分类批量重放（v1.4.3 真机） | 12 条招行句式逐条 `simulate`：淘宝→购物(4)、中石化加油→交通(3)、盒马鲜生→买菜(2)、春秋航空机票→交通(3)、个人所得税→税费(21)、奶粉→母婴(18)、携程酒店→旅行(16)、iCloud月费→订阅(19)、益丰大药房→医疗(9)、万达影城→娱乐(8)、全家便利店→日用(5)、优衣库→服饰(11) —— **12/12 全对** |
| 关键词升级实测 | 老安装（旧词表在 DB 里）升到 v1.4.3 后，「蜜雪冰城」「库迪」这种**只在新区表里**的词也能判对 → 说明 `refreshCategoryKeywords()` 真的把词并进去了 |
| 包名串味回归（v1.4.3 真机） | 修前「携程酒店」「iCloud月费」都被判成餐饮（因为商家抽不到 → 退成包名 `cmb.pb` → 命中早年被学进去的 `cmb.pb→餐饮` 记忆）；修后分别 16/19 |
| 充值识别（v1.4.4 真机） | 重放招行「在【财付通-微信支付-微信零钱充值账户】发生快捷支付扣款 ¥0.01」→ 日志 `资金转移：9 → 8（微信）`，`txn TRANSFER acc=9`，余额 `8:15000→15001 / 9:1082100→1082099` —— 双边都对，且不计收支 |
| 余额调整（v1.4.5 真机） | `diag bal:8:250`（**单位是元**）→ 微信 ¥250；再 `bal:8:20100` → ¥20,100；`bal:8:250` 调回 → ¥250。全程只动微信，招行不动；**删掉那 3 笔 ADJUST 流水后余额仍是 ¥250**（净 0，删记录不等于删钱） |
| 收到别人转账（真机） | 微信「[转账] 张三向你转账 100.00 元，请收款」→ 置信度 79 → 进**待确认**（不自动入账，安全）；单测断言方向 = INCOME、账户 = 微信、余额 +100 |
| 删账户迁移账目（v1.4.5 真机） | 建了个 `ZZTEST` 账户并给它记 1 笔 ¥1 余额调整 → 删除时弹出「『ZZTEST』下还有 1 笔账目 / 转到『现金』『微信』『招商银行』」→ 选微信 → 提示「已删除账户，1 笔账目已迁移过去」，微信 ¥250→**¥251**、总资产 ¥11,021→**¥11,022**、招行不动；随后删掉那笔调整，微信回到 ¥250（净 0）。**账户列表里 ZZTEST 已消失**，测试痕迹清理干净 |

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
21. **PowerShell `Set-Content -Encoding UTF8` 在 PS5 会写 BOM**，而 Java 的 `Properties.load()`
    把 BOM 当成第一行的一部分（多出一条垃圾键）。写 `local.properties` 要
    `[IO.File]::WriteAllText(..., New-Object Text.UTF8Encoding($false))`。
22. **`keytool` 打印的指纹是 `E7:96:2E:34…`（大写 + 冒号）**，直接 `-notmatch "e7962e34"`
    永远不匹配 → 会把好钥匙误报成野钥匙。比对前先 `-replace ':',''` 再 `ToLower()`。
23. `RuleStore` 里的 `Rules.table()/appendWords()` 是**顶层扩展函数**（不是我手误，是为了
    UI/测试不用写 `with(RuleStore){}`）；测试文件必须 `import com.jingyuniang.ledger.capture.table`
    等等，否则报一堆 `Unresolved reference`（`compileDebugUnitTestKotlin` 会一次喷 20 行）。
24. 仓库里的 `README.md` 曾被 PowerShell 的 `` `a `` `` `f `` `` `t `` 转义吃掉首字母（`assets` 变 `ssets`），
    表现为文件里混进 BEL/FF/TAB 控制字符；改文档后 grep 一下 `[char]7/12/9`。
25. **Compose 里读普通 object 的属性不会触发重组**：`RuleStore.current` 是单例字段，加完词界面
    还显示「App 内置 · 490 条」。要用一个 `version` 状态驱动（`remember(version) { … }`），
    并在改变处 `version++`；更好的做法是写盘后 `reload()` 回读，保证界面 = 磁盘。
26. **提示要画在操作点附近**：结果列表有 8 条，把「已加入」画在列表尾部等于没提示；
    自填词在卡片底部，提示画在顶部也一样看不到。两处都踩过。
27. **`probe` 做 A/B 时慎用英文字母开头紧贴数字**：`ZZQ5元` 的 `extractAmount=0`（被当编号前缀），
    要用中文 token，或让数字跟在「消费/支付」这类汉字后面。
28. **CI 秒挂（整个 job 25s 就 Failure）要先怀疑 action 版本，不是代码**：2026-10-26 那次红灯是
    `android-actions/setup-android@v3` —— 它会去下载 Google 早已下架的旧 cmdline-tools 包，直接 404；
    `@v4.0.2` 的更新说明就是 "Fix for removed tools package"。同批把 `gradle/actions/setup-gradle@v3` 升到 `@v6`。
    **判据**：`规则文件校验` 那种纯 python 的 job 绿、只有一个要装 SDK 的 job 红，且红 job 只跑了 20 多秒
    → 一定是 action 层。workflow 里已加「环境自检」步骤（java/ANDROID_HOME/platforms/gradlew --version），
    下次挂了日志能直接定位，不用再靠猜。
29. 看 CI 状态：`gh` 没装、匿名 API 60 次/小时会 403，**徽章是最省事的**：
    `https://github.com/wumiYYY/deepseek-Tools/actions/workflows/ci.yml/badge.svg?branch=main&event=push`
    —— 返回的 SVG 里 `<title>CI - passing</title>` 就是结论。
30. **分类串味的教训（v1.4.1 事故，v1.4.2 修）**：自动入账时**绝对不能**把当时的分类
    连同商户一起写进 `merchant_rule`。当时商户抽不到、退成 App 名「招商银行」，于是
    「招商银行→餐饮」被学进记忆，之后整批招行短信（连滴滴）全成了餐饮。
    现在：`isUsableMerchant()` 挡垃圾键 + 只有用户手动定分类才学习 + 历史脏键在匹配时过滤掉。
31. **抓取路径里调 AI 必须给短超时**：`DeepSeekClient.chat(timeoutSec = 6)`。
    默认是 connect 20s + read 60s，而且 OkHttp 的 `execute()` 是阻塞的、`withTimeout` 拦不住，
    真让它跑满会把「已记账」通知拖到一分钟以后。
32. 抓取调试命令 `am start --es diag` 现在会被 uid 校验挡（MIUI 上 `launchedFromUid` 会给 10364）：
    加 `-S` 强制重启 Activity 才能过闸门 —— `am start -S -n <pkg>/<activity> --es diag "..."`。
33. **「商户」等于 App 名/包名时，绝不能拿去查商户记忆**（v1.4.3 真机踩到）：抽不到商户会退成
    `appName`，模拟路径里甚至退成包名 `cmb.pb`；早年 `learnMerchant` 把 `cmb.pb` 学进过记忆，
    结果所有「抽不到商户」的短信都被判成餐饮（携程酒店、iCloud 月费都是）。现在：包名/域名不算商户
    （`isUsableMerchant`），且 `merchantForRule` 不可信时传空串。
34. **改 `SeedData` 的分类关键词对老安装不生效** —— 分类行是首装时写进数据库的。必须同时抬
    `SeedData.KEYWORD_REV`，由 `Repo.refreshCategoryKeywords()` 在启动时做一次并集
    （保留用户加的、补进新的；不动 schema）。改词后跑 `CategoryScenarioTest`，它按真种子表断言。
35. `diag dump` / `diag captures` 只打印最近 6 / 12 条 —— 批量验证时看不到更早的，删掉可见的再 dump
    才会「翻页」。查分类为什么判错用 `diag cats:<分类名>`（打印该分类在 DB 里的全量关键词）。
36. **「银行卡 ↔ 微信零钱」是双边转移，不是支出**（v1.4.4 真机事故：用户充值 ¥200 + ¥50，
    结果**微信余额反而少了 ¥250**）：三个坑叠在一起 ——
    ① `fundTransferMarkers` 没有「零钱充值/充值账户」→ 记成支出，支出虚增；
    ② 招行手机银行包名是 **`cmb.pb`**，`pkgToType` 只认 `cmbchina` → pkg 匹配失效，
       账户被正文里的「微信」抢走，扣的是微信账户；
    ③ 就算记成 TRANSFER 也只扣来源（单边），目标账户永远不加钱。
    现在：包名表加 `cmb`；转移词加 零钱充值/充值账户/转入零钱/提现到零钱/余额充值/转入微信/转入支付宝；
    `matchTransferTarget()` 认另一方 → `book(toAccountId=…)` 记双边。真机验：充值 ¥0.01 →
    `资金转移：9 → 8（微信）`，微信 +1 分、招商银行 −1 分。
37. **账户删除不会处理其下的账目**：删掉账户后，历史账目仍挂在该 id 上，`balanceDelta(id)` 不再被任何
    账户统计 → 余额/统计悄悄变。用户 2026-10-27 重建账户（1/8/9，删了 2/3/7）之后，旧账就是这样变孤儿的。
    删账户前请确认该账户下没有账目（或先改归属）。**待改进**：删除时提示 + 提供「迁移到另一个账户」。

---

## 7. Backlog（按收益排序）

| 优先级 | 项 | 说明 |
|---|---|---|
| ~~P1~~ | ~~规则卡的「人话索引」~~ | ✅ **v1.4.0 已做**：`capture/RuleGuide.kt` + `ui/RuleGuideCard.kt`，25 条症状→加词，`RuleGuideTest` 12 个断言守着 |
| ~~P1~~ | ~~自动抓取的分类不准/太笼统~~ | ✅ **v1.4.2 已做**：商户抽取（`【…】`句式）+ 只让用户教记忆 + 待分类 + AI 兜底，见 §3.4、§5 |
| P1 | P1-6 图片附件 | 记账拍小票存图（`res/xml/file_paths.xml` 已有 cache-path） |
| P1 | 「孤儿账目」修复入口 | 账户删于 v1.4.5 之前的历史账目仍挂在已删 id 上（`Repo.orphanTxnCount()` 可数）。需要在「账户管理」里给出「这 N 笔挂在不存在的账户上 → 一键迁到某个账户」，否则余额永远少一块 |
| P2 | 「收到别人转账」要不要自动入账 | 现在微信收款类通知置信度 79 → 进待确认（安全但不省事）。若要自动，需只在「收款成功 / 你收到一笔 + 金额 + 付款人」同时满足时放行，别放宽整个聊天类 |
| P2 | 「待分类」批量纠正入口 | 现在只能去明细一条条改；统计页/待确认页应能批量指定分类（一改就记住商户） |
| P2 | 时段×金额启发式 | 比如工作日 7–9 点小额→早餐/交通。**暂时没做**：精度不够，宁可落「待分类」也不要瞎猜，做了要先拿真机样本统计准确率 |
| P2 | 应用锁入口 | `Settings.lockPin` 已有字段，缺 UI 与校验 |
| P2 | AI 第二轮 | 自由聊天（不锁账本）+ 推理模型 + 月报复盘；`ai/AiAgent` 已有工具路由 |
| P2 | 余额面板第 3 刀 | 无障碍读屏自动校准余额（复用 `BalanceParser.parse` / `guessAccountType`） |
| P2 | 银行正则模板库 | 每家银行一条模板，比通用评分准 |
| P3 | App 内检查更新 | `update.json` 格式见 `docs/RELEASE.md` §4.2 与 `docs/合作者交接.md` §4 |
| P3 | 删 `ui/VoicePanel.kt` | 无用文件 |

> 「人话索引」的下一步延伸（还没做，别当成已完成）：把 `RuleGuide` 的命中词条接进 `CaptureScreen`
> 的「🤔 为什么没记」旁边，让用户在看某条被判定的通知时**从那条直接加词**（现在是先来设置页搜）。

---

## 8. 协作（有合作者会一起改）

- **许可证**：代码 MIT + 美术素材 CC BY-NC-SA 4.0（禁止商用）→ `LICENSE`、README「素材授权」。
- **CI**：`.github/workflows/ci.yml` —— push/PR 到 main 跑 108 个单测 + 规则 JSON 校验。**红灯不许合**。
- **流程**：功能走 `feat/xxx` 分支 + PR；`main` 只放发版提交；规则改动**单独 commit**（最易冲突）。
- **签名**：仓库**故意不含** `.jks`。合作者私密渠道拿到 `jingyuniang-release.jks` + 三个口令，
  然后 `.\scripts\setup-signing.ps1 -JksPath <路径> -StorePassword … -KeyPassword …` 一步配好
  （写 `local.properties`，不入库）。脚本现在会**自检**：真拿口令开一次密钥库、比一次证书指纹，
  口令/别名错或拿到野钥匙会在这一步就红，而不是等到发版时静默出 unsigned 包（踩过）。
  没凭据也能构建，但出的是 unsigned 包，装不上手机。
- **交接包**（一次性 onboarding 物料，**不是每版都发**）：`.\scripts\make-handover.ps1` 生成
  `D:\Tools\deepseek_build_Tools\_collaborator_handover\` + 同名 zip（在 `…\deepseek_build_Tools\` 下）。
  脚本会先核对 jks 指纹，然后打进去：主文档 + HANDOFF/RELEASE/CAPTURE_RULES/用户指引/CONTRIBUTING/LICENSE
  + 签名（jks/local.properties）+ 更新服务模板 + `版本信息.txt`（哪个 commit / 哪版）+ SHA256SUMS。
  **默认不带 APK**（一升级就过期，省得每次重发 17MB；签名对照看证书指纹就够；要带加 `-IncludeApk`）。
  只有「新合作者 / 口令变了 / 想给对方最新文档快照」才需要重发 —— 发新版本一律走 git。
  这两个路径都在**仓库外**，`git add -A` 带不走。
- **三个口令**：`WHALE_STORE_PASSWORD` / `WHALE_KEY_PASSWORD` / `WHALE_KEY_ALIAS`（别名 `jingyuniang`）。
  **口令不进仓库**（这是公开仓库）—— 只放在本机 `local.properties` 与私密交接包里；文档里一律写 `<SIGNING_PASSWORD>`。
  ⚠️ 2026-10-27 之前这两个文档里曾明文写过口令，已清除，但**git 历史里还在**，
  建议轮换（命令见 `docs/AGENT_HANDOFF.md` §10；轮换不换钥匙、证书指纹不变）。
  ⚠️ `setup-signing.ps1` 旧默认别名是 `whale`（错的，已修成 `jingyuniang`），手上若是旧脚本要自己传 `-KeyAlias`。
- **证书指纹**：`SHA-256 = e7962e34c241992bb69d781a6451390871bce3c26fb67a9b45dcac0558ef1975`（CN=JingYuniang）。
  `dist/1.3.2` 的 APK 与 `keystore/jingyuniang-release.jks` 已核对一致；密钥有效期到 2056-10-14。
- **不要做**：提交 `local.properties`/`*.jks`/真实 `.db`/`dist/` 里的 APK；加回 destructive fallback；
  为了"待确认少几条"放宽 `shouldRead`；改规则不跑测试 / 改 schema 不写迁移。

---

## 9. 素材授权（**不可上架**）

立绘 = 深海女仆工坊皮肤 **CC BY-NC-SA 4.0**（上善 → ZipZipPipe → Small-tailqwq）+「蓝色大肥鱼」存档（原作者版权）。

**只能个人非商业使用**：不得上应用商店、不得付费/带广告分发。About 页与 README 必须保留署名。
