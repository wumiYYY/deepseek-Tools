# 变更日志（CHANGELOG）

版本号规则：`versionName = 主.次.修订`，`versionCode = 主*10000 + 次*100 + 修订`（1.2.0 → 10200），
`versionCode` **只增不减**（覆盖安装必须 >= 已装版本）。发布流程见 `docs/RELEASE.md`。

## 1.2.0 — 2026-10-23

### 修复
- **AI 记账不写库（P0）**：模型返回的 JSON 被自己的抠取写法拆坏（`substringAfter("{")` 吃掉开头的 `{`），
  解析必然失败 → 判断对了却什么都不执行。统一走 `ai/JsonExtract.kt`，并补失败日志。
- **中文口语金额**：`十块钱 / 二十五块 / 一百五十元 / 十二块五` 以前识别为 0（`parseChineseAmount` 是空壳），
  现在真解析，且只在没有阿拉伯数字时兜底。
- **多账本写错账本**：`ledgerId` 没读到时写死成 1，现统一走 `resolveLedgerId()`。
- **微信钱包截图被猜成储蓄卡**：见新增的截图识别。

### 新增
- **更新余额 · 第 1 刀（手动输入）**：账户页顶部「更新余额」面板，校准写成 `TxnSource.ADJUST` 的
  「余额调整」流水，可追溯可删，且不计入本月收支 / 预算 / 图表 / 抓取去重。
- **更新余额 · 第 2 刀（截图 OCR）**：选余额截图 → ML Kit 中文 OCR → `BalanceParser` 抽余额
  → 并按截图品牌词猜账户（零钱→微信、余额宝→支付宝、信用卡、尾号→银行），只预填、要用户点「校准」。
- `scripts/release.ps1`：一条命令 = 单测 + 构建 + 产出 `dist/<版本>/…apk` + `SHA256SUMS.txt`。

### 优化
- **AI 记账从两次请求降为一次**：`AiAgent` 直接返回 `amount`/`type`，不再回头 `parseNaturalLanguage`
  （原来两次请求相隔 ~1.4s，且两次结果可能打架）。
- **APK 体积 53.2MB → 24.5MB**：ML Kit OCR 的 `.so` 只保留 `arm64-v8a`（x86/x86_64/v7a 共约 28MB 用不到）。

### 工程
- 签名口令移出 `build.gradle.kts`（改读 `local.properties` / 环境变量），`.gitignore` 补上
  `keystore/`、`*.jks`、`dist/`、`*.apk`，并首次纳入 git 版本控制。
- `AppDatabase` 增加 `MIGRATIONS` 模板与警告：泄漏这两步会触发 `fallbackToDestructiveMigration()` **静默清库**。
- 真机自检入口 `--es diag "…"`：`ai:` / `book:` / `bal:` / `ocrbal:` / `capture:` / `backup:` /
  `restorefile:` / `dump` / `del:`（命令见 `docs/HANDOFF.md` §1）。
- 单元测试 49 个（新增中文金额 4 个、余额 OCR 11 个）。

### 已验证（真机 Redmi K80 / Android 16）
AI 一句话记账落库、中文金额、OCR 余额 + 认账户、余额校准不污染收支、加密备份→恢复（含错误密码）、
抓取流水线 3 样例、关闭 AI 的离线记账。详见 `docs/HANDOFF.md` §3。

## 1.1.0 — 2026-10-23（基线）
通知/短信自动抓取 + 置信度 + 待确认、广告/聊天类过滤、一键总开关、桌面小组件开关、
首装引导、二次元 UI、AI 问答、一键模板、应用锁（代码就绪无入口）。
