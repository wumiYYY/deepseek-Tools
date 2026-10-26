# 鲸鱼娘金库 · 会话交接文档（HANDOFF）

> 给下一个会话/开发者：读完这份就能无缝接上。最后更新：本会话末尾。

---

## 0. 一句话现状

Android 记账 App「鲸鱼娘金库」（包名 `com.jingyuniang.vault`），核心是**自动记账**（通知/短信抓取 + AI 一句话记账），UI 是二次元深蓝+鎏金女仆风。
**当前版本 v1.2.1（versionCode 10201）**，产物 `dist/1.2.1/鲸鱼娘金库-v1.2.1.apk`（24.5 MB，只含 arm64-v8a），手机已装机（用户的真实账本 28 笔，别乱清）。
发布/版本更新流程见 `docs/RELEASE.md`，变更历史见 `CHANGELOG.md`，**给使用者的说明书是 `docs/用户指引.md`**（`release.ps1` 会自动把它放进 `dist/<版本>/`）。
代码已入 git 并推到 `https://github.com/wumiYYY/deepseek-Tools`（`main` + tag `v1.2.0`）。
本轮修完：**P0-3 AI 记账真写库 ✅**、**更新余额面板第 1 刀（手动输入）✅**、**第 2 刀（截图 OCR + 认账户）✅**；
另做过一轮「数据安全 + 回归」：加密备份/恢复、抓取流水线、离线一句话记账都真机验过（见 §3）。只剩第 3 刀（无障碍读屏）。

---

## 1. 关键路径与命令

```
项目根：D:\Tools\deepseek_build_Tools\everyday_money
源码：  app/src/main/java/com/jingyuniang/ledger/
资源：  app/src/main/res/
工具链：.toolchain/（JDK17 + Gradle + Android SDK，全部本地，勿用系统环境）
版本控制：git，远端 origin = https://github.com/wumiYYY/deepseek-Tools（分支 main，tag v<版本>）
```

**构建**（PowerShell，脚本含 BOM，勿改编码）：
```powershell
.\scripts\release.ps1                  # 发布一条龙：单测 + 构建 + dist/<版本>/APK + SHA256
.\scripts\build.ps1 -Task assembleRelease   # 只构建
.\scripts\build.ps1 -Task ":app:testDebugUnitTest"
```

**装机 + 授权 + 开监听（一条命令）**：
```powershell
.\scripts\setup-phone.ps1 -Serial bd7a85e9 -Apk "dist\1.2.0\鲸鱼娘金库-v1.2.0.apk"
```

**装机后自检 / 写库验证（不用点 UI，全走 Intent + logcat）**：
```powershell
$adb = ".toolchain\android-sdk\platform-tools\adb.exe"
# activity 全名是 com.jingyuniang.ledger.MainActivity（不是 .vault.MainActivity，写错会 Error type 3）
& $adb shell am start -n com.jingyuniang.vault/com.jingyuniang.ledger.MainActivity --es diag "ai:发微信开销了十块钱"
& $adb shell am start -n com.jingyuniang.vault/com.jingyuniang.ledger.MainActivity --es diag "book:午饭25元"
& $adb shell am start -n com.jingyuniang.vault/com.jingyuniang.ledger.MainActivity --es diag "bal:2:1000"
& $adb shell am start -n com.jingyuniang.vault/com.jingyuniang.ledger.MainActivity --es diag "dump"
& $adb shell am start -n com.jingyuniang.vault/com.jingyuniang.ledger.MainActivity --es diag "del:12"
# 截图识别（第 2 刀）：先把 docs 里的 mock 图推到相册并登记进 MediaStore
& $adb push docs/test_balance_wx.png /sdcard/Pictures/test_balance_wx.png
& $adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/test_balance_wx.png
& $adb shell content query --uri content://media/external/images/media --projection _id:_display_name | Select-String "test_balance"
& $adb shell pm grant com.jingyuniang.vault android.permission.READ_MEDIA_IMAGES
& $adb shell am start -n com.jingyuniang.vault/com.jingyuniang.ledger.MainActivity --es diag "ocrbal:content://media/external/images/media/<上一步的_id>"
# 抓取 / 备份 / 恢复 自检
& $adb shell am start -n com.jingyuniang.vault/com.jingyuniang.ledger.MainActivity --es diag "capture:0"   # 0=微信 1=银行短信 2=支付宝
& $adb shell am start -n com.jingyuniang.vault/com.jingyuniang.ledger.MainActivity --es diag "backup:1234"
& $adb shell am start -n com.jingyuniang.vault/com.jingyuniang.ledger.MainActivity --es diag "restorefile:latest:1234"
& $adb logcat -d -s WhaleMoney:* | Select-String "DIAG|AGENT|AI book|BALANCE"
```
> `ai:` = AI 中枢决策 + 记账；`book:` = 一句话记账弹窗那条路径；`bal:<账户id>:<余额>` = 更新余额；
> `ocrbal:<图片uri>` = 走真实 OCR → BalanceParser → 猜账户 → 校准（等价于截图识别里点「校准」）；
> `capture:<0|1|2>` = 把示例通知喂进真实抓取流水线；`backup:<密码>` = 加密备份；
> `restorefile:latest:<密码>` = 恢复最新一份备份（备份文件名带中文，adb 传参会丢字节，所以用 `latest`）；
> `dump` = 打印账户 + 余额 + 最近 6 笔；`del:<txnId>` = 删测试数据。
> ⚠️ extra 里**别带空格**（adb 会二次拆分，`"book:午饭 25"` 会变成 `pkg=25`）。
> ⚠️ 自检入口只认 adb shell（uid 2000）/ root 发的指令（`MainActivity` 是 exported，不设门槛任何 App 都能用 Intent 改账本）。
> 实测 MIUI 上 `launchedFromUid` 常给 `-1`，所以判定是「**拿得到真实 uid 且不是 shell/root** 才拒绝」。

**真机**：Redmi K80 `24117RK2CC`，serial `bd7a85e9`，Android 16 / HyperOS，1080×2400，status=121px / nav=133px。
**USB 安全调试已开启** → `adb shell input tap/keyevent/text` 全部可用（`input text` 只能 ASCII，**中文打不进输入框**；
要验证中文语义请用 §1 的 `--es diag "ai:…"`，中文能原样传到设备）。

---

## 2. 架构速查

| 文件 | 职责 |
|---|---|
| `MainActivity.kt` | 入口 + 底部导航（`WhaleRoot`）+ 开屏 `SplashCurtain`；`resolveAction()` 处理 `screen` 参数 |
| `ui/HomeScreen.kt` | 首页：主卡立绘（呼吸浮动）、`MascotAskBar`（AI 问问条）、明细列表 |
| `ui/AddTxnScreen.kt` | 记一笔：自定义数字键盘、分类网格、**一键模板行**、AI 弹窗 |
| `ui/AiChatScreen.kt` | 问账（聊天）+ `QuickAiDialog`（一句话记账） |
| `ui/Art.kt` | 立绘本、`MoodBadge`、`HeaderArtBanner`、`SplashCurtain`、`GoldParticles`、`MascotAskBar` |
| `ui/Ui.kt` | 主题组件：`WhaleCard`/`GradientCard(art=)`/`ChipPill`/`EmptyState`/`EmojiAvatar`/`SearchPill` |
| `ui/CaptureScreen.kt` | 自动记账页：总开关、规则医生 `RuleDoctorCard`、抓取开关、通知体检 |
| `ui/SettingsScreens.kt` | **AI 设置 / 数据 / 权限向导 / 关于 / 搜索**（账户管理不在这里！） |
| `ui/ManageScreens.kt` | **账本 / 分类 / 账户管理**（`AccountScreen` 含「更新余额」面板）/ 周期 / 规则 |
| `ui/Onboarding.kt` | 首装三步引导 |
| `ui/AppViewModel.kt` | 业务核心：`askVault`（问答）、`quickAiBook`（一句话记账）、`aiAgent`（工具路由）、`aiSuggestRule`（规则生成）、`saveAccount`、`balances`、`applyBalanceInput`（更新余额） |
| `ai/DeepSeekClient.kt` | `Obj AiService`：`available()`/`ask()`/`askVault()`/`parseNaturalLanguage()`；人设与提示词都在这里 |
| `ai/AiAgent.kt` | **AI 中枢**：让模型返回 `{"tool":"addTxn|queryStats|none"}` |
| `ai/JsonExtract.kt` | **`parseJsonObject()`：模型回复 → JSONObject 的统一入口（P0-3 就是这里踩的坑）** |
| `ocr/BalanceParser.kt` | 余额截图 OCR 文本 → 余额（`parse`）+ 猜账户类型（`guessAccountType`），纯 JVM 可单测 |
| `capture/CaptureFilters.kt` | 抓取闸门：聊天类拦截、**广告词闸门**、`shouldRead()` |
| `capture/CapturePipeline.kt` | 解析 → 置信度 → 自动入账/待确认；**总开关在此判断** |
| `capture/ParseEngine.kt` | 金额评分、方向、分类/账户匹配、`isJunk()` |
| `widget/WhaleWidget.kt` | 桌面小组件 + **抓取总开关广播** `com.jingyuniang.ledger.WIDGET_TOGGLE_CAPTURE` |

**数据**：Room（`Txn`/`Category`/`Account`/`AutoRule`/`Budget`…）+ DataStore（`data/Prefs.kt`，含 `captureMaster`/`captureAccess`/`aiKey`/`lockPin`）。
**余额不是存储字段**：`repo.accountBalanceMap()` 由「期初余额 + 流水」计算，VM 暴露 `balances: StateFlow<Map<Long,Long>>`。
**余额调整**：`vm.applyBalanceInput(acc, targetCents)` 不改期初余额，而是补一笔 `source = TxnSource.ADJUST`、`excludeBudget = true` 的流水（分类「余额调整」，`repo.ensureCategory` 自动建）。ADJUST 在 `computeStats` / `Repo.statsOnly()` / 首页日汇总里都被排除，**不进收支统计**。

---

## 3. 已完成且**实测验证**过

| 功能 | 证据 |
|---|---|
| 通知/短信自动抓取 + 置信度 + 待确认 | logcat `抓取成功 来源=NOTIFY 金额=2500 置信度=99` |
| 银行广告过滤（4元水电券） | A/B：广告无抓取日志，真动账 `金额=400` |
| 聊天类（QQ/微信）群消息拦截 | 10 个单测（`CaptureFiltersTest`） |
| 一键启停总开关 | 真机截图 + 状态持久化 |
| 桌面小组件抓取开关 | 广播触发后 App 内显示「已暂停/运行中」 |
| 首装三步引导 | 真机截图 |
| 二次元 UI（立绘、金线图标、问问条、搜索钮、我的页头图） | 多张真机截图 |
| AI Key 配置 + 连通 | `连通成功 ✅` |
| 一键模板 | 点「午饭 25」→ 金额 25 / 备注 午饭 / 分类 餐饮 |
| **P0-3 AI 记账真写库** | `AGENT raw={"tool":"addTxn",...}` → `AGENT tool=addTxn` → `AI book ok txnId=11`；首页明细出现该笔（`docs/v_ai_booked.png`） |
| **更新余额面板第 1 刀（手动输入）** | 账户页「更新余额」三行；输入 1000 → `BALANCE adjust acc=微信#2 975 → 1,000 delta=25 txn=14`，账户页 ¥1,000、明细出现「余额调整 调账 +25」，本月支出仍 ¥55（`docs/v_balance_panel.png`、`docs/v_balance_dialog.png`） |
| **更新余额面板第 2 刀（截图 OCR）** | 真机选图 → `BALANCE ocr hit=8850 label=零钱 raw=钱包\|零线\|¥ 88.50\|零钱通\|1,000.00…` → 对话框预填 88.50 并提示「已从截图读到「零钱」¥88.50」→ 点校准 `BALANCE adjust acc=微信#2 256.80 → 88.50 delta=-168.30 txn=21`，账户页 ¥88.50（`docs/v_balance_ocr.png`）。另验一张支付宝截图：`hit=25680 label=余额`，正确避开「余額宝 总金额 ¥12,345.67」 |
| **截图 OCR 会认账户** | `零钱`截图 → `accType=WECHAT acc=微信`（`2:8850`）；`余额宝`截图 → `accType=ALIPAY acc=支付宝`（`3:25680`）。对话框恒提示「账户猜为「X」，不对就在下面换」 |
| **AI 一句话记账只发一次请求** | `AGENT raw={"tool":"addTxn","amount":10,...}` → 2ms 后 `AI book source=agent amount=1000`（改造前要再发一次解析请求，相隔 ~1.4s） |
| **离线（关闭 AI）也能一句话记账** | 关「启用 AI 能力」→ `DIAG ai available=false` → 无任何请求 → `AI book source=local amount=1000` → txn 落库（`发微信开销了十块钱`） |
| **加密备份 → 恢复** | `backup ok=true 备份成功：鲸鱼娘备份-…whalebk（9 KB）`；删掉 3 笔后用**错误密码**恢复 → `密码不对，或文件已损坏`，数据不动；用正确密码 → `恢复完成，共 3 笔账目`，id/类型/金额/账户/来源/时间戳完全一致，余额也回来了 |
| **抓取流水线回归（本轮改过 ParseEngine/findSimilar）** | `capture:0` 微信 ¥25 → 2500/置信度68→待确认；`capture:1` 银行短信 → 4800/65→待确认；`capture:2` 支付宝 → 1250/88→自动入账 txn#27（acc=支付宝、cat=23） |
| **中文口语金额** | `十块钱 / 二十五块 / 一百五十元 / 十二块五` 全走单测 + 真机 `AI book amount=1000` |
| 54 个单元测试（8 BillImporter + 19 CaptureFilters + 16 ParseEngine + 11 BalanceParser） | `testDebugUnitTest` 全绿 0 失败 |
| **升级不丢数据（v1.2.0 → v1.2.1）** | 覆盖安装前后 `txnCount=31`、`balances=1:0, 2:-121210, 3:123456` 一字不差；versionCode 10200→10201 |
| **抓取准确率（v1.2.1 规则）** | 读屏只在页面切换+结果页触发；账单提醒/额度/营销渠道拦掉；≥3 笔金额判列表页；通知无结构证据与所有读屏来源置信度封顶 79 |
| **待确认「全部忽略」** | 真机点按：`一键忽略待确认：11 条` → `pendingCaptures=0`，`txnCount` 仍 31（账目未受影响） |
| **全新安装（模拟发给使用者）的第一次体验** | 装了 debug 变体（`com.jingyuniang.vault.debug`，独立包名，不碰正式包数据）：首装引导 3 页 → 点「去开启权限」直接落到权限向导（4 项 + 无障碍可选 + 5 家厂商自启动路径）；首次种子数据正确（`accounts=1:现金, 2:微信*, 3:支付宝, 4:银行卡, 5:信用卡`，0 笔流水）。截图：`docs/v_guide_onboarding2/3.png`、`docs/v_guide_permission.png` |
| **随包使用说明** | `docs/用户指引.md` 由 `release.ps1` 自动落到 `dist/<版本>/使用说明.txt(.md)`（txt 带 BOM）；`release.ps1 -GuideOnly` 可只更新说明、不动 APK 的 sha256 |

---

## 4. 未完成（按优先级，含已定位的实现细节）

### 抓取准确率 · 第二轮（v1.2.1 后仍有待打磨）
已经做了：结果页判定、非动账模板、通知渠道过滤、列表页丢弃、置信度封顶、一键忽略。
还可以做（按收益排序）：
1. **「别抓这个 App / 这条」黑名单**：待确认行上加「以后别再抓」，写进新表或 AutoRule(反向)，命中直接丢；
2. **按包名 + 渠道的统计面板**：抓取记录里显示「某银行 3 天抓了 40 条，其中 2 条确认入账 → 建议关掉它的通知抓取」；
3. **结构化模板库**：为常见银行（招行/工行/建行/中信…）写正则模板，比通用评分准得多；
4. **同笔合并**：`capture:2` 这类「通知 + 短信同一笔」目前靠 180s 同金额去重，跨来源仍可能漏合。

### AI · 第二轮（v1.2.1 只做了「接口可换」）
- **自由聊天**：现在只有「问账」（锁死账本上下文）。要真正的聊天/推理，需要一个不注入账本的模式，
  由模型自己决定要不要查账（现有 `AiAgent` 的 JSON 工具路由可以扩成多轮 + 多工具）。
- **推理模型**：`deepseek-reasoner` 已能通过「模型名」使用，但 UI 没有引导，也没处理 reasoning 内容的展示。
- **AI 复盘/建议**：`aiReview()` 已存在，只差一个独立入口（月报卡片 P1-5 正好一起做）。

### 更新余额面板 · 第 3 刀：无障碍读屏（未做）
- UI 入口已就位：`BalanceSourceRow("♿", "读屏识别", …, false)`
- 现成能力：`capture/CaptureAccessibilityService.kt` 已在读页面文本；`Prefs.captureAccess`
- 做法：打开微信/支付宝「钱包」页 → 服务读到整屏文本 → 直接丢给 `BalanceParser.parse(文本)` 和 `guessAccountType(文本)` → 回填同一个 `BalanceInputDialog`
- 权限提示：需要用户在系统里开无障碍，注意别和自动记账的读屏逻辑打架

### P1-5 月报卡片（未做）
放 `ui/StatsScreen.kt`，内容：本月支出/收入/结余/Top3 分类/环比 + AI 一句点评。
（余额调整已从统计口径里排除，月报直接用 `vm.stats` 就是干净数）

### P1-6 图片附件（未做）
小票拍照贴账目；包内已有 ML Kit 中文 OCR（`com.google.mlkit:text-recognition-chinese:16.0.1`）。

### 应用锁（用户说先不做）
代码已写好但**无入口**：`ui/LockScreen.kt`（`LockScreen` + `LockSetupDialog`）、`Prefs.lockPin`、`vm.setLockPin`、`MainActivity` 已接入拦截逻辑（`lockPin` 非空即生效）。要启用只需在「我的」页挂一个入口。

### 语音（已废弃，方案 C：用输入法）
自建语音全部下线（源码中 `🎤` 出现 0 次）。`ui/VoicePanel.kt` 仍在但无入口，可删。

---

## 5. 踩过的坑（务必别重犯）

1. **RemoteViews 白名单**：小组件布局里用 `<View>` 会 inflate 失败 → 桌面显示「组件加载错误」。只能 LinearLayout/TextView/ImageView 等，间隔用空 `TextView`。
2. **PowerShell 字符串替换会静默失败**：锚点不匹配时不报错，我却打印了「已完成」。**改完必须回读验证**（`Select-String` 或读文件）。
3. **`$home` 是 PowerShell 只读变量**，不能用。
4. **here-string 的 `'@` 必须独占一行**，否则整段解析失败。
5. **`const val` 不能是 `.trimIndent()` 的字符串**。
6. **AI 人设里禁止编造**：曾写「主人在说收支就回『记好了』」，导致模型口头承诺但不写库。现在硬规则是「你只回答，不负责写库，不许说已记好」。
7. **MIUI 的 SpeechRecognizer 不回传结果**（`AsrService: onCallbackResults: param error`），只有系统面板能拿到文字。
8. **每轮 `install -r` 都会重置运行时权限**，装完必须重跑 `pm grant`（setup-phone.ps1 已封装）。
9. **Compose 自定义控件在 uiautomator 里常无文本节点**，坐标只能截图估算；验证优先用「广播/Intent + logcat」而不是点 UI。
   （实测补充：本项目的 Compose 页其实**能** dump 出 text 节点，`adb shell uiautomator dump` + 正则解析 `<node>` 就能拿到控件坐标，比截图估坐标准得多）
10. **P0-3 的真凶：JSON 抠取写法**。原来两处都写
   `raw.substringAfter("{", "{").substringBeforeLast("}")` —— 模型回复本身就是纯 JSON 时，`substringAfter` 会把开头的 `{` 一起吃进去，
   再配上 `substringBeforeLast("}")`，结果必然缺括号 → `JSONObject` 抛异常 → `AiAgent.decide()` 返回 null
   → **模型判断对了却什么都没执行**。现在统一走 `ai/JsonExtract.kt` 的 `parseJsonObject()`（取第一个 `{` 到最后一个 `}`，顺带剥 markdown 围栏）。
   教训：**凡是 `runCatching { JSONObject(...) }.getOrNull()` 都会把「解析失败」静默吞掉**，一定要配一行失败日志（`AGENT 解析失败 raw=...`）。
11. **中文金额不会被 `extractAmount` 认**：老 `parseChineseAmount` 是空壳（`return 0`）。现在实现真解析，
   支持 `十块 / 二十五块 / 一百五十元 / 一千零五元 / 十二块五(12.5)`，并且**只在没有阿拉伯数字时兜底**，避免抢走 `午饭 25` 这种正常文本。
12. **写库时账本 id 不要写死 `ledgerId.value.coerceAtLeast(1L)`**：设置还没读到时它是 0，会写成 1 号账本；
   多账本场景就成了「记了但看不见」。统一走 `AppViewModel.resolveLedgerId()`。
13. **adb `am start --es` 的 extra 不能带空格**（会被再拆一次，见 §1 命令块）；中文本身没问题（UTF-8 能原样到设备）。
14. **`am start` 的 activity 要写全名**：`com.jingyuniang.vault/com.jingyuniang.ledger.MainActivity`。写 `.MainActivity` 会 `Error type 3`（manifest 里是 `.MainActivity`，但实际类在 `ledger` 包下）。
15. **`getLaunchedFromUid()` 在 MIUI 上可能是 -1**，不能直接拿它做「非 2000 就拒绝」的硬判定（会把 adb 自检也挡掉）。
16. **ML Kit 中文 OCR 会把标签读错**：真机实测「零钱」→「零线」、「余额」→「佘额」，还会混出繁体「餘額 / 總金額 / 餘額寶」。
    `BalanceParser.fixOcrChars` 已做归一化（线/綫/銭/錢→钱，佘/赊/餘→余，颉/額→额，總→总，寶→宝）。
    ⚠️ 当初就漏了 `寶→宝`：「餘額寶」归一化成「余额寶」后仍含「余额」，于是抢到最高权重，直接读成了余额宝的 ¥12,345.67。
    现在同分时让**靠后的行**胜出（`>=`），因为 OCR 里「余额宝」标题总排在真正的「余额」前面。
17. **photo picker 的「最近照片」会被自己截的调试图污染**：`adb shell screencap -p /sdcard/x.png` 会把调试截图塞到相册最前，
    下一步选图就会选错（我第一次就选中了「picker 自己」的截图）。做法：选图前先删掉/别截，或者先推 mock 图再去截图。
18. **`adb shell content query --where "… LIKE …"` 的引号过不了 adb 二次拆分**（报 `Invalid token`）：要么不带 where 全量查再本地过滤，要么按 `_id` 直接 `content delete --uri content://media/external/images/media/<id>`。
19. **备份文件名带中文**（`鲸鱼娘备份-YYYYMMDD-NNNNN.whalebk`）：`am start --es diag` 传这个中文名会**整段丢字节**变成空串，于是恢复时打开的是 `files/backups` 目录 → `EISDIR`。
    自检一律用 `restorefile:latest:<密码>`（取最新一份 .whalebk）。
20. **`BackupManager.restore` 不含 capture 表**：它只恢复账本/分类/账户/预算/周期/规则/商户/流水，恢复后「抓取记录」归零、「待确认」清空。
    这是设计取舍（capture 是诊断流水），别当 bug 改；要连抓取历史一起备份得先在 `export` 里加 `captures` 段。
21. **截图 OCR 必须猜账户**：`零钱` 是微信、`余额宝/支付宝` 是支付宝；但钱包首页都会列「银行卡」入口，所以「银行卡」只能算弱信号（真机踩过：微信钱包截图被猜成储蓄卡）。权重在同文件 `guessAccountType` 里，改之前先看那条注释。
22. **`.ps1` 必须存成 UTF-8 **带 BOM**。** 新建 `scripts/release.ps1` 时按普通 UTF-8（无 BOM）写，Windows PowerShell 按 ANSI 解码中文 → 引号错位、整段解析失败（报「语句块缺少 }」「意外的标记」）。
    修法：`[System.IO.File]::WriteAllText($p, (Get-Content $p -Raw -Encoding UTF8), (New-Object System.Text.UTF8Encoding($true)))`。
23. **README 里曾把 keystore 口令写成明文**（口令原文，别再写回来）。仓库要公开/共享时，口令必须只在 `local.properties`（已 ignore）或环境变量里；提交前跑一遍
    `git grep -n -E "whale(money)?[0-9]{4,}|sk-[A-Za-z0-9]{10,}|ghp_"` 自查（连口令片段都别留）。
24. **PowerShell 函数里不能用管道输出日志**：`$out | Select-String ... | ForEach-Object { $_.Line }` 的输出会混进函数返回值，导致 `if ((Invoke-Gradle ...) -ne 0)` 恒真。日志一律 `Write-Host`。
25. **MIUI/HyperOS 用 adb 装「新包名」会被拦**：给 debug 变体（`.debug` 后缀）或别的包名装包时，系统会弹 `com.miui.securitycenter / USB安装管理` 并报 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`；同包名覆盖升级不受影响。
    解决：手机上手动点安装，或先在开发者选项里开启「USB 安装」（需登录小米账号）。**发给使用者时提醒他们自己点安装**，不要指望远程 adb。
    另一个现象：屏幕熄灭时 adb 安装也可能被取消，装之前先 `adb shell input keyevent KEYCODE_WAKEUP`。

---

## 6. 视觉规范（保持一致）

- 深蓝 `#0A1430 / #132657 / #1D3363`，鎏金 `#C5A468 / #E1BF7C / #D9B871`，奶油 `#FFFDF8`，雾蓝 `#97A9D8`
- 标题用 `WhaleSerif`（FontFamily.Serif）；卡片金线发丝边；`MascotBadge`/`MoodBadge` 用于人物
- 素材：`drawable-nodpi/maid_half|maid_full|maid_casual|icon_head|mood_delighted|mood_sleepy|mood_determined`
- **授权**：立绘来自「深海女仆工坊」皮肤（CC BY-NC-SA 4.0，链：上善 → ZipZipPipe → Small-tailqwq）+ 蓝色大肥鱼存档（版权归原作者）。关于页已署名；**仅个人非商业使用，不可上架分发**。

---

## 7. 给下一个会话的第一句话

> 读 `docs/HANDOFF.md`，做「更新余额面板第 3 刀：无障碍读屏」（第 1、2 刀和 P0-3 都已修完，别回退）；
> 抽余额可以直接复用 `ocr/BalanceParser.parse(整屏文本)`。每步按文档里的自检标准验证，装完机记得跑 §1 的 `diag` 自检命令。
