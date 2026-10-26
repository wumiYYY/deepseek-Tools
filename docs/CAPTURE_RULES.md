# 抓取规则 · 外部参考与我们的实现对照

> 结论先说：**没有官方/标准化的「自动抓账规则」**。钱迹、一木、随手记这些都闭源，规则不公开。
> 能查到的、可引用的，是三类东西：①开源实现的**方法论**②银行短信的**字段模型**③工程社区的**共识原则**。
> 本文把这三类扒下来，逐条对照我们的实现，标出差距和下一步。

---

## 一、可引用的一手来源

### 1. `Hidayat112/bank-sms-parser`（Kotlin 纯 JVM，最值得抄方法论）
<https://github.com/Hidayat112/bank-sms-parser>

摘录它的关键设计（原文）：

- **“It deliberately favors false negatives over false positives”**
  → 宁可漏记，不可错记。这句话是这类功能的行业共识总结。
- **“every exclusion carries a specific reason and confidence score”**
  → 每条被排除的通知都要能说清「为什么排除」，并带置信度。
- **流水线分阶段，硬排除先于提取**：
  ```
  normalization → ordered hard exclusions → completed-event gates
  → issuer resolution → field extraction → confidence gate
  ```
- **“A hard exclusion can never be overcome by a high extraction score”**
  → 硬排除（如 OTP、还款、EMI 转换）**不能被高提取分翻盘**。资格（eligibility）与置信度（confidence）是两件事。
- **规则目录化**：`parser_config.json` 里放 `banks` / `products` / `exclusions`，
  加一家银行只加配置不改代码。
- 明确列出的**业务语义排除**：
  - OTP / 验证码
  - 借记卡（只想记信用卡支出时）
  - UPI、储蓄账户转账
  - **EMI 转换**（原文 Sample 17：把已有消费转成分期，若当成新交易会**重复计**）
  - **信用卡还款**（原文 Sample 19：还款不是消费，也不是退款）
  - 金额格式非法（`2,4`）→ fail closed
- **置信度模型**：显式信用卡证据 0.94 起；已知银行通用卡模板 0.72；缺卡尾号/商户/日期扣分；<0.60 直接放弃。
  「清晰排除」反而给高置信度——**置信度描述的是判断的确定性，不是交易存在的概率**。
- 去重（生产建议）：交易身份 = `发行方 + 卡尾号 + 金额 + 时间 + 参考号`，
  配合内容 hash 做幂等，避免同一笔被重复记账。

### 2. `saurabhgupta050890/transaction-sms-parser`（TS，正则库）
<https://github.com/saurabhgupta050890/transaction-sms-parser>

它定义了这类解析的**标准字段模型**（我们目前缺一半）：

```ts
{ account:   { type: CARD|WALLET|ACCOUNT, number, name },
  balance:   { available, outstanding },     // ← 我们没提取
  transaction:{ type: debit|credit, amount, referenceNo, merchant } }  // ← refNo 我们没提取
```

测试覆盖 9 家银行 + 10 张信用卡 + 5 个钱包，**逐家模板**——这也说明业界做法不是"一套通用正则"，
而是"通用框架 + 每家模板"。

### 3. 社区工程文章（观点性，作为佐证）
- <https://marnieapp.vercel.app/blog/android-notification-expense-tracker>
  要点：「应过滤选定来源，并把通知转成**待审草稿**，而不是把每条通知都当交易」。
- Android 官方 `NotificationListenerService` 文档（唯一"官方"可引用的部分）：
  <https://developer.android.com/reference/android/service/notification/NotificationListenerService>
  提供的能力边界就在这里；**通知渠道（NotificationChannel）** 是区分"交易提醒/营销推送"最有效的系统级信号。
- <https://f-droid.org/packages/com.pennywiseai.tracker/>（开源 SMS 记账）：把「自定义规则」做成用户可编辑的功能，
  而非硬编码——这也是一个可借鉴的方向。

---

## 二、归纳出的 7 条共识

| # | 原则 | 出处 |
|---|---|---|
| 1 | **宁漏不错**（false negative > false positive） | bank-sms-parser |
| 2 | **资格与置信度分离**：硬排除不能被高分数翻盘 | bank-sms-parser |
| 3 | **流水线分阶段**：规范化 → 硬排除 → 事件门 → 抽取 → 置信度门 | bank-sms-parser |
| 4 | **每条排除都要有 reason**（可解释、可排查） | bank-sms-parser |
| 5 | **规则目录化**（配置化，加银行/模板不改核心代码） | bank-sms-parser |
| 6 | **业务语义排除**：OTP、还款、EMI/分期转换、账户转账 ≠ 消费 | bank-sms-parser |
| 7 | **幂等去重**：交易身份（机构+尾号+金额+时间+参考号）+ 内容 hash | bank-sms-parser 生产建议 |

---

## 三、我们现在的实现对照

| 原则 | 我们的状态 | 位置 |
|---|---|---|
| 1 宁漏不错 | ✅ 已对齐（多次强调并写进注释） | `CaptureFilters` 顶部文档 |
| 2 资格/置信度分离 | 🟡 部分：`shouldRead` 是硬门，置信度只决定"自动入账/待确认" | `CaptureFilters` + `CapturePipeline.effectiveConfidence` |
| 3 分阶段流水线 | ✅ **渠道门 → 广告门 → 非动账模板 → 列表页门 → 分类判定 → 解析 → 置信度门** | `CaptureFilters.shouldRead` |
| 4 排除要可解释 | 🟡 部分：`NotifyDiag` 只有一句 verdict；`diag probe:` 能逐项打印，但没有"命中哪条规则" | `NotifyDiag` / `probe` |
| 5 规则目录化 | ❌ 全部是 Kotlin 常量表，加银行要改代码 | `CaptureFilters` |
| 6 业务语义排除 | 🟡 部分：账单提醒/还款提醒/营销已排除；**信用卡还款成功、账单分期、EMI 转换还没排除** | `NON_TXN_MARKERS` |
| 7 幂等去重 | 🟡 部分：同来源同金额 150s + 与已入账同金额 180s；**没有交易身份，跨渠道延迟会漏合** | `CapturePipeline` |

### 本轮（v1.2.4）补上的（只增补，不动已验证的规则）
- **信用卡还款 / 账单分期 / EMI 转换** 纳入 `NON_TXN_MARKERS` —— 这类会**重复计支出**
  （分期把一笔消费拆成多笔，还款把资金转移当消费）。
  ⚠️ 刻意不排除「分期付款成功」——那可能是真的一笔支付。
- **跨渠道去重改为「只挡跨来源」**：原来窗口内有同金额的自动账目就跳过，
  会误杀「同来源连续两笔同金额」（连买两杯同价咖啡）。现在只有来源不同才算同一笔。
  （曾把窗口放宽到 600s，评估后撤回：10 分钟内两笔同金额很常见，会漏记。）
- 外部参考整理进本文件，避免下个会话重新调研。

### 刻意保留的自有规则（真机验证过，不动）
1. 规格数字不是金额（`925银`/`18K金`/`5000mAh`/`2件`）
2. 内容/短视频 App 默认不抓、不读屏
3. 电商只认强支付模板
4. 通知渠道过滤（营销渠道）
5. 账单列表页（≥3 笔金额）整段丢弃
6. 读屏只认页面切换 + 支付结果页，永不自动入账
7. 真实动账自动入账（微信支付「已支付/已扣费」、银行「账户+扣款+金额」）

### 下一步（按收益排序）
1. **规则目录化**：把 `AD_MARKERS`/`NON_TXN_MARKERS`/银行名→账户映射挪到 `assets/capture_rules.json`，
   每次改动不用发版（也让用户能自己加关键词）
2. **可解释的排除原因**：`shouldRead` 返回 `Verdict(reasonCode)`，UI 里显示「为什么没抓：命中营销渠道」，
   直击用户"这条为什么不记"的疑问
3. **交易身份去重**：`(机构, 卡尾号, 金额, 时间±10min)` 做 key，替代"同金额"这种粗判
4. **结构化字段**：从银行短信里抽 `balance`（余额）与 `referenceNo`（交易单号），
   可用于「余额自动校准」和「精确去重」
5. **逐家银行模板**：招行/工行/建行/中信各写一条正则，精度比通用评分高得多
