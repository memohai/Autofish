# Skill Library — 自进化操作经验系统设计

## 1. 背景与动机

### 1.1 问题陈述

amctl 作为 memoh 的设备控制层，提供原子级操作（tap、swipe、find_element 等）。当前完成一个用户意图（如"清理最近任务"）需要 AI Agent 实时推理 + 多轮 API 调用：

```
用户: "清理最近任务"
Agent 第 1 轮: press_key(187)          → 打开概览
Agent 第 2 轮: get_screen()            → 观察屏幕
Agent 第 3 轮: find_nodes(text=清除)    → 没找到
Agent 第 4 轮: get_screen() 全量        → 发现按钮在 offscreen
Agent 第 5 轮: swipe() × 5             → 滚动到按钮
Agent 第 6 轮: get_screen()            → 确认按钮位置
Agent 第 7 轮: tap(272, 568)           → 点击
Agent 第 8 轮: get_screen()            → 验证结果
```

每次执行同一意图都重复这个过程，存在三个问题：


| 问题  | 影响                             |
| --- | ------------------------------ |
| 高延迟 | 8 轮 API 调用 + LLM 推理，端到端 30-60s |
| 高成本 | 每轮消耗 LLM token，重复任务重复花钱        |
| 不稳定 | 依赖 LLM 当次推理质量，相同任务可能走不同路径、偶发失败 |


### 1.2 核心假设

Android 设备操作存在大量**可复用模式**：

- 同一 App 的同一操作，步骤基本固定（版本间缓慢演化）
- 不同设备上的同一 App，操作逻辑相同（仅布局/坐标不同）
- 类似 App 的类似功能，操作模式可迁移（如所有 Launcher 都有"清除最近"）

如果能把成功的操作序列记录下来、索引起来、在相似场景下直接复用，就能把重复任务的延迟从 30s 降到 3s，成本从 N 次 LLM 调用降到 0。

### 1.3 这不是什么

在开始设计前，明确边界：

- **不是替代 AI Agent。** Agent 仍然是处理新任务、异常恢复、开放式目标的核心。Skill Library 是 Agent 的加速缓存，不是替代品。
- **不是 RPA 脚本录制。** RPA 录制的是固定的坐标序列，换个分辨率就废。Skill Library 存储的是语义化的操作意图。
- **不是 DroidRun 的推理流水线。** 不在设备控制层硬编码推理策略，经验的获取和使用完全由 Agent 侧（memoh）决定。

---

## 2. 核心概念

### 2.1 术语定义


| 术语                      | 定义                                                       |
| ----------------------- | -------------------------------------------------------- |
| **Skill**               | 一个完成特定意图的操作序列，附带上下文索引和执行条件                               |
| **Episode**             | 一次完整的任务执行记录（原始轨迹），可能成功也可能失败                              |
| **Recipe**              | 从一个或多个成功 Episode 中提炼出的、泛化后的可复用 Skill                     |
| **Anchor**              | 定位 UI 元素的语义标识符（resource_id / text / content_desc / 结构路径） |
| **Context Fingerprint** | 描述执行环境的特征向量（平台 + App + 设备类型 + 语言）                        |
| **Confidence**          | 一条 Recipe 的可靠性评分，基于历史成功/失败统计                             |


### 2.2 与 RL 概念的对应关系


| RL 概念                    | Skill Library 对应                     |
| ------------------------ | ------------------------------------ |
| State (s)                | Context Fingerprint + 当前屏幕 UI 树      |
| Action (a)               | amctl 原子操作（tap、swipe、find_element 等） |
| Reward (r)               | 任务成功/失败 + 用户反馈                       |
| Policy (π)               | Recipe 选择策略（匹配 + 置信度排序）              |
| Experience Replay Buffer | Episode Store                        |
| Skill（HRL）               | Recipe                               |


但要注意一个关键差异：经典 RL 通过梯度更新神经网络权重来优化策略；Skill Library 通过**离散的经验记录增删改查**来优化。更接近 **Case-Based Reasoning (CBR)** 或 **Hierarchical RL 中的 Option Framework**，而非端到端 RL。

---

## 3. 架构

### 3.1 系统边界

```
memoh (AI Agent)
  │
  │  ┌─────────────────────────────────────────────┐
  │  │           Skill Library Layer                │ ← 新增
  │  │                                              │
  │  │  ┌──────────┐  ┌──────────┐  ┌───────────┐ │
  │  │  │ Recorder  │  │ Matcher  │  │ Executor  │ │
  │  │  └──────────┘  └──────────┘  └───────────┘ │
  │  │        │              │             │       │
  │  │        ▼              ▼             │       │
  │  │  ┌──────────────────────────┐       │       │
  │  │  │     Experience Store     │       │       │
  │  │  │  (Episodes + Recipes)    │       │       │
  │  │  └──────────────────────────┘       │       │
  │  └─────────────────────────────────────┼───────┘
  │                                        │
  ▼ MCP / REST                             ▼ MCP / REST
┌──────────────────────────────────────────────────┐
│                 amctl (设备端)                      │
│         ToolRouter → 原子操作                      │
└──────────────────────────────────────────────────┘
```

**关键决策：Skill Library 在 Agent 侧（memoh），不在设备端（amctl）。**

理由：

- amctl 是无状态的设备控制接口，不应承担知识管理职责
- 经验数据涉及用户意图和上下文，属于 Agent 的认知层
- 同一个 memoh 实例可能控制多台设备，经验应集中管理

### 3.2 模块职责


| 模块            | 职责                       | 输入                         | 输出                 |
| ------------- | ------------------------ | -------------------------- | ------------------ |
| **Recorder**  | 记录每次任务执行的完整轨迹            | API 调用序列 + 屏幕状态 + 结果       | Episode            |
| **Distiller** | 从成功 Episode 中提炼泛化 Recipe | Episode(s)                 | Recipe             |
| **Matcher**   | 给定目标和上下文，检索最佳 Recipe     | Goal + Context Fingerprint | Ranked Recipe List |
| **Executor**  | 执行 Recipe，处理中途失败和适配      | Recipe + amctl 连接          | 执行结果               |
| **Evolver**   | 根据执行反馈更新 Recipe 状态       | 执行结果                       | 更新后的 Recipe        |
| **Store**     | 持久化存储 Episodes 和 Recipes | CRUD 操作                    | 存储的数据              |


### 3.3 执行流程

```
用户意图: "清理最近任务"
         │
         ▼
  ┌─────────────┐
  │   Matcher    │ ── 检索 Experience Store
  └──────┬──────┘
         │
    ┌────┴────┐
    │ 命中？   │
    └────┬────┘
     Yes │          No
         ▼           ▼
  ┌───────────┐  ┌───────────────┐
  │ Executor  │  │ memoh AI 推理  │
  │ 执行 Recipe│  │ 原始探索模式   │
  └─────┬─────┘  └───────┬───────┘
        │                │
   ┌────┴────┐      Recorder 全程记录
   │ 成功？   │           │
   └────┬────┘           ▼
  Yes   │  No     ┌───────────┐
    │   │         │ Distiller │ ── 提炼新 Recipe
    │   ▼         └───────────┘
    │ 降低置信度
    │ 标记需更新
    │ 回退到 AI 探索
    │
    ▼
  Evolver: +1 成功，更新置信度
```

---

## 4. 数据模型

### 4.1 Episode（原始执行记录）

```json
{
  "id": "ep_20260312_173000_a1b2c3",
  "goal": "清理所有最近任务",
  "context": {
    "platform": { "os": "android", "api_level": 34 },
    "device": { "type": "rhinopi_x1", "screen": "2340x984", "density": 320, "orientation": "landscape" },
    "locale": "zh-CN"
  },
  "steps": [
    {
      "seq": 1,
      "action": "press_key",
      "params": { "key_code": 187 },
      "result": { "ok": true },
      "screen_before_hash": "sha256:abc...",
      "screen_after_hash": "sha256:def...",
      "duration_ms": 1200
    },
    {
      "seq": 2,
      "action": "wait",
      "params": { "ms": 800 },
      "result": { "ok": true },
      "duration_ms": 800
    },
    {
      "seq": 3,
      "action": "get_screen",
      "params": {},
      "result": { "ok": true, "observed": ["node:全部清除(offscreen)", "node:Firefox", "node:设置"] },
      "duration_ms": 3600
    }
  ],
  "outcome": "success",
  "total_duration_ms": 28000,
  "timestamp": "2026-03-12T17:30:00Z"
}
```

Episode 是**事实记录**，不做任何泛化，原样保存所有细节。

### 4.2 Recipe（泛化后的可复用技能）

```json
{
  "id": "recipe_clear_recents_launcher3",
  "goal": "清理所有最近任务",
  "goal_embedding": [0.12, -0.34, ...],

  "match_context": {
    "platform": { "os": "android", "api_level_min": 30 },
    "app": { "package_pattern": "com.android.launcher*" },
    "required_capabilities": ["accessibility", "shell"]
  },

  "preconditions": [
    { "type": "any_screen", "description": "可从任意界面开始" }
  ],

  "steps": [
    {
      "action": "press_key",
      "params": { "key_code": 187 },
      "description": "打开最近任务视图"
    },
    {
      "action": "wait",
      "params": { "ms": 800 }
    },
    {
      "action": "scroll_until_visible",
      "params": {
        "anchor": {
          "primary": { "type": "resource_id", "value": "*:id/clear_all" },
          "fallback": [
            { "type": "text", "value": { "zh": "全部清除", "en": "Clear all" } }
          ]
        },
        "direction": "right",
        "max_attempts": 10
      },
      "description": "滚动直到清除按钮可见"
    },
    {
      "action": "tap_element",
      "params": {
        "anchor": {
          "primary": { "type": "resource_id", "value": "*:id/clear_all" },
          "fallback": [
            { "type": "text", "value": { "zh": "全部清除", "en": "Clear all" } }
          ]
        }
      },
      "description": "点击全部清除"
    }
  ],

  "success_condition": {
    "type": "screen_contains",
    "anchor": { "type": "resource_id", "value": "*launcher*:id/workspace" },
    "description": "回到桌面即为成功"
  },

  "stats": {
    "success_count": 47,
    "failure_count": 2,
    "total_executions": 49,
    "confidence": 0.92,
    "avg_duration_ms": 3200,
    "last_success": "2026-03-12T17:30:00Z",
    "last_failure": "2026-03-01T09:15:00Z",
    "source_episodes": ["ep_20260301_...", "ep_20260305_..."]
  },

  "metadata": {
    "created": "2026-03-01T10:00:00Z",
    "updated": "2026-03-12T17:30:00Z",
    "created_by": "ai_exploration",
    "version": 3,
    "tags": ["launcher", "cleanup", "system"]
  }
}
```

### 4.3 Anchor 设计

Anchor 是解决跨设备/跨分辨率可移植性的核心抽象。

**优先级与适用性：**


| 优先级 | Anchor 类型           | 跨分辨率 | 跨语言 | 跨版本  | 适用场景              |
| --- | ------------------- | ---- | --- | ---- | ----------------- |
| 1   | `resource_id`       | ✅    | ✅   | ⚠️ 中 | 系统 App、主流 App     |
| 2   | `content_desc`      | ✅    | ❌   | ⚠️ 中 | 无障碍良好的 App        |
| 3   | `text`              | ✅    | ❌   | ❌ 低  | 文本可见的按钮/标签        |
| 4   | `structural_path`   | ✅    | ✅   | ❌ 低  | 列表项、无标识元素         |
| 5   | `normalized_coords` | ⚠️ 中 | ✅   | ⚠️ 中 | 游戏、WebView、Canvas |
| 6   | `absolute_coords`   | ❌    | ✅   | ❌    | 最后的 fallback      |


**必须诚实面对的问题：**

- `resource_id` 看似最稳定，但实际上很多 App（特别是 Flutter/React Native/游戏）不暴露 resource_id
- App 版本更新可能改变 resource_id（重构 UI 时）
- `text` 受语言影响，受 A/B 测试影响（同一版本不同用户看到不同文案）
- **没有一种 Anchor 是万能的**，多级 fallback 是必须的，但也意味着匹配逻辑的复杂度

### 4.4 Context Fingerprint

```json
{
  "platform": {
    "os": "android",
    "api_level": 34,
    "rom": "stock"
  },
  "device": {
    "type": "rhinopi_x1",
    "screen_class": "landscape_large",
    "density_class": "xhdpi"
  },
  "app": {
    "package": "com.android.launcher3",
    "version_code": 34,
    "variant": null
  },
  "locale": "zh-CN",
  "capabilities": ["accessibility", "shizuku", "shell"]
}
```

注意：`screen_class` 和 `density_class` 是分档的（如 small/normal/large/xlarge），不是精确值。精确值（2340x984）跨设备无法匹配，分档后（landscape_large）可以匹配大量类似设备。

---

## 5. 关键算法

### 5.1 Recording（记录）

**策略：被动全量记录 + 主动标注**

```
每次 memoh 通过 amctl 执行操作:
  1. 自动记录: action、params、result、耗时、前后屏幕状态 hash
  2. 当 memoh 判定任务完成: 标记 outcome = success/failure
  3. 可选: memoh 对 Episode 补充语义标注 (goal 的自然语言描述)
```

被动记录的开销很低（只是追加日志），但需要注意：

- **不记录屏幕截图原图**（太大），只记录 UI 树摘要 + 截图 hash
- **不记录 LLM 的推理过程**（那是 memoh 内部的事），只记录最终的 API 调用

### 5.2 Distillation（提炼）

从原始 Episode 到泛化 Recipe 是最关键也最困难的步骤。

**提炼流程：**

```
输入: 1 个或多个完成同一 goal 的成功 Episodes

步骤:
  1. 对齐: 找出 Episodes 间的公共操作子序列 (LCS)
  2. 泛化坐标: 将绝对坐标替换为语义 Anchor
     - 检查操作目标是否有 resource_id → 用 resource_id
     - 否则检查是否有稳定的 text/content_desc → 用 text
     - 都没有 → 保留归一化坐标 + 标记为"低可移植"
  3. 去噪: 移除探索性失败步骤（retry、回退、重复 get_screen）
  4. 补充元数据: 提取 preconditions、success_condition
  5. 验证: 在同一设备上回放一次确认可行

输出: Recipe
```

**谁来做提炼？**

这里有一个设计选择：


| 方案       | 优点                        | 缺点                |
| -------- | ------------------------- | ----------------- |
| 规则引擎自动提炼 | 确定性、快速、无 LLM 成本           | 处理不了复杂情况          |
| LLM 辅助提炼 | 理解语义、能泛化                  | 需要消耗 token、可能过度泛化 |
| 混合       | 简单 case 用规则，复杂 case 用 LLM | 需要定义"简单"的边界       |


**建议方案：规则优先，LLM 兜底。** 大部分操作序列的模式是固定的（按键 → 等待 → 找元素 → 点击），规则引擎可以处理 80% 的情况。只有当规则引擎无法提炼时（如涉及条件分支、动态内容），才调用 LLM。

### 5.3 Matching（匹配检索）

给定一个新的 goal + context，如何找到最佳 Recipe？

**两级匹配：**

```
第一级: Context Filter（硬过滤）
  - platform.os 必须匹配
  - api_level >= recipe.match_context.api_level_min
  - app.package 匹配 recipe.match_context.app.package_pattern
  - required_capabilities 是当前设备 capabilities 的子集
  → 过滤掉不可能执行的 Recipe

第二级: Goal Similarity（软匹配）
  - 计算 goal_embedding 与查询 goal 的 embedding 的余弦相似度
  - 按 similarity × confidence 排序
  → 返回 Top-K 候选
```

**为什么不能只用关键词匹配：**

用户可能说"清理后台"、"关掉所有 App"、"清除最近任务"、"clean up recents"——都是同一个意图。纯关键词匹配会漏掉大量合法匹配。Embedding 相似度是必须的。

**但 embedding 也有坑：**

- "清理最近任务" 和 "清理应用缓存" 的 embedding 可能很接近，但操作完全不同
- 需要在 Context Filter 层通过 app package 区分（一个是 Launcher，一个是 Settings）

### 5.4 Execution（执行）

Recipe 的执行不是简单回放，而是**带验证的自适应执行**。

```
for step in recipe.steps:
    if step.action == "scroll_until_visible":
        # 语义操作：循环执行直到条件满足
        attempts = 0
        while attempts < step.params.max_attempts:
            screen = get_screen()
            element = find_element(screen, step.params.anchor)
            if element and element.is_on_screen:
                break
            scroll(step.params.direction)
            attempts++
        if attempts >= max_attempts:
            return ExecutionResult.STEP_FAILED(step)

    elif step.action == "tap_element":
        # 定位元素，获取实时坐标
        screen = get_screen()
        element = find_element(screen, step.params.anchor)
        if element is None:
            # 尝试 fallback anchors
            for fallback in step.params.anchor.fallback:
                element = find_element(screen, fallback)
                if element: break
        if element is None:
            return ExecutionResult.ELEMENT_NOT_FOUND(step)
        tap(element.center_x, element.center_y)

    else:
        # 简单操作：直接执行
        execute(step.action, step.params)

# 执行完所有步骤后，验证成功条件
if recipe.success_condition:
    screen = get_screen()
    if verify(screen, recipe.success_condition):
        return ExecutionResult.SUCCESS
    else:
        return ExecutionResult.VERIFICATION_FAILED
```

**核心原则：永远不存坐标，在执行时实时获取元素位置。**

Recipe 存的是"找到 resource_id 为 clear_all 的元素并点击"，执行时才去实际屏幕上定位这个元素的当前位置。这样自然适配任何分辨率。

### 5.5 Evolution（进化）

```
Recipe 状态机:

  DRAFT ──成功验证──► ACTIVE ──置信度衰减──► DEGRADED ──重新验证──► ACTIVE
    │                   │                      │
    │              连续失败≥3               持续低置信度
    │                   │                      │
    └───────────────────▼──────────────────────▼
                    DEPRECATED ──AI 重新探索──► 生成新 Recipe
```

**置信度计算：**

```
base_confidence = success_count / total_executions

# 时间衰减：长期未使用的 Recipe 置信度下降
time_decay = exp(-days_since_last_success / 30)

# 最近表现加权：最近 10 次的成功率比历史总体更重要
recent_rate = recent_successes / min(total_executions, 10)

confidence = 0.3 * base_confidence + 0.5 * recent_rate + 0.2 * time_decay
```

为什么需要时间衰减？因为 App 会更新。一个 6 个月没用过的 Recipe，即使历史成功率 100%，也不应该高置信度——App 可能已经改版了。

---

## 6. 必须诚实面对的难题

### 6.1 App 更新导致 Recipe 失效

这是最大的实际挑战。

**场景：** 微信更新了 UI，"发现"按钮的 resource_id 从 `com.tencent.mm:id/discover` 变成了 `com.tencent.mm:id/tab_discover`。所有引用旧 id 的 Recipe 全部失效。

**缓解措施（不是解决方案，因为无法完全解决）：**

- 多级 Anchor fallback：resource_id 失败后尝试 text/content_desc
- App 版本追踪：记录 Recipe 在哪些版本上验证过，版本变化时标记为待验证
- 失败时自动触发 AI 重新探索，更新 Recipe

**必须承认的：** 这个问题没有完美解决方案。高频使用的 Recipe 能通过快速失败-重建保持更新；低频的 Recipe 会默默腐烂。这是经验系统的固有缺陷——经验会过时。

### 6.2 状态依赖与前置条件

**场景：** "发送微信消息给张三" 的 Recipe 假设微信已经登录。如果微信未登录，Recipe 第一步就会进入登录页面而非聊天列表。

**这比坐标问题更难。** 因为：

- 前置条件的可能空间巨大（登录状态、网络状态、权限状态、已打开的页面...）
- 很多前置条件是隐式的（Recipe 不会记录"微信已登录"，因为记录时这是理所当然的）

**可行的缓解：**

- Recipe 的第一步加 screen 验证：执行前检查当前屏幕是否符合预期
- 失败时不是简单重试，而是报告"前置条件不满足"让 Agent 处理

### 6.3 过度拟合特定路径

**场景：** 系统学会了通过 "设置 → 应用 → 微信 → 强行停止" 来关闭微信，但实际上 `am force-stop com.tencent.mm` 一条命令就够了。

Recipe 倾向于记录它第一次成功的路径，而不是最优路径。

**缓解措施：**

- 当同一 goal 积累多个 Recipe 时，优先保留步骤数最少的
- 定期让 AI 审查高频 Recipe，寻找更优路径
- 但不要过度优化——一个"可用但非最优"的 Recipe 比"没有 Recipe"好得多

### 6.4 安全性

存储和复用操作序列有安全隐患：

- Recipe 可能包含敏感操作（如"打开银行 App → 输入密码"）
- 众包共享经验时可能被投毒（恶意 Recipe："打开设置 → 恢复出厂"）

**必须有的机制：**

- 敏感操作标记：涉及密码输入、支付、删除等操作的 Recipe 不自动执行，需用户确认
- 来源标记：区分"本地学习的"和"外部导入的" Recipe
- 危险操作白名单：`am clear-data`、`pm uninstall` 等命令永远不进入 Recipe

### 6.5 成功判定

"任务成功了吗？" 这个问题比想象中难。

- "清理最近任务"好判定——回到桌面就行
- "发消息给张三"怎么判定？看到"已发送"？但不同 App 的反馈不同
- "搜索 memoh"怎么判定？搜索结果出现了？但搜索结果可能是空的

**务实的做法：**

- 简单任务：基于 screen 状态验证（presence/absence of specific elements）
- 复杂任务：让 Agent 判定（这里不可避免地需要 LLM）
- 兜底：执行完所有步骤且无错误 → 标记为"假定成功"，但置信度打折

---

## 7. 经验共享（慎重设计）

### 7.1 共享的价值

```
用户 A 的 RhinoPi: 第一次学会了"清理 Launcher3 最近任务"（花了 30s AI 探索）
                    → 导出 Recipe
用户 B 的 Pixel:   导入 Recipe → 直接执行（3s，零 LLM 成本）
```

如果每个用户的每次成功都贡献到读kms有什么不能跑（公共知识库，理论上整个社区只需要为每个 goal 支付一次探索成本。

### 7.2 共享的风险


| 风险   | 说明                       | 严重性 |
| ---- | ------------------------ | --- |
| 隐私   | Recipe 包含用户的 App 列表、操作习惯 | 高   |
| 投毒   | 恶意 Recipe 执行危险操作         | 高   |
| 版本混乱 | 不同 App 版本的 Recipe 混在一起   | 中   |
| 地域差异 | 国内版微信 vs 国际版 WeChat      | 中   |


### 7.3 建议方案

**第一阶段不做共享。** 先确保本地的 Record → Distill → Match → Execute 闭环可靠运行。共享是锦上添花，本地闭环是核心价值。

如果未来做共享：

- 仅共享 Recipe（泛化后的），不共享 Episode（原始记录）
- Recipe 共享前脱敏（去除设备 ID、用户特定信息）
- 导入的 Recipe 默认不信任（低置信度），需要本地验证通过后才提升
- 维护一个 App 版本 → Recipe 版本的兼容性矩阵

---

## 8. 存储设计

### 8.1 存储选型


| 数据              | 特征             | 建议存储                          |
| --------------- | -------------- | ----------------------------- |
| Episode         | 追加写入、按时间查询、可清理 | SQLite + 时间分区（保留最近 N 天）       |
| Recipe          | 读多写少、需要全文/向量检索 | SQLite + Embedding 列（或独立向量索引） |
| Screen Snapshot | 大对象、可丢失        | 文件系统，按 hash 命名去重              |


### 8.2 容量估算

```
单条 Episode:  ~2-10 KB（不含截图）
单条 Recipe:   ~1-3 KB
日均新 Episode: ~50-200 条（重度使用）
活跃 Recipe:   ~100-500 条（稳定期）

存储预算: < 100 MB（完全可接受）
```

---

## 9. 与 amctl 的接口设计

Skill Library 位于 Agent 侧，需要 amctl 提供以下能力（大部分已有）：


| 需要的接口                      | amctl 当前状态              | 是否需要新增 |
| -------------------------- | ----------------------- | ------ |
| 原子操作（tap/swipe/key/text）   | ✅ REST + MCP            | 否      |
| 获取 UI 树                    | ✅ GET /api/screen       | 否      |
| 查找元素                       | ✅ POST /api/nodes/find  | 否      |
| 点击元素                       | ✅ POST /api/nodes/click | 否      |
| 截图                         | ✅ GET /api/screenshot   | 否      |
| **scroll_until_visible**   | ❌                       | **是**  |
| **verify_screen_contains** | ❌                       | **是**  |
| **获取 App 版本信息**            | ❌                       | **是**  |
| **批量操作（减少网络往返）**           | ❌                       | **可选** |


`scroll_until_visible` 和 `verify_screen_contains` 是 Recipe 执行器频繁使用的高级操作，如果在 amctl 侧实现可以减少多轮 API 调用。但也可以在 Agent 侧纯客户端实现（代价是多几轮网络往返）。

---

## 10. 落地路径

### Phase 0: 被动记录（2 周）

- 在 memoh → amctl 的调用链上加一层透明的 Episode Recorder
- 只记录，不使用，不改变任何现有行为
- 目的：积累数据，验证记录格式是否足够

**交付物：** Episode 记录格式 + 记录中间件 + 本地 SQLite 存储

### Phase 1: 手动 Recipe + 回放（3 周）

- 提供 CLI 工具：从 Episode 手动提炼 Recipe（半自动，人工审核）
- 实现 Recipe Executor（带 Anchor 定位 + fallback）
- memoh 在执行前查询 Recipe，命中则优先使用

**交付物：** Recipe 格式 + Executor + CLI 提炼工具

### Phase 2: 自动提炼 + 匹配（4 周）

- 实现规则引擎自动提炼（覆盖 80% 的简单 case）
- 实现 Embedding 语义匹配
- 实现置信度计算和时间衰减

**交付物：** Distiller 规则引擎 + Matcher + Evolver

### Phase 3: 自适应执行 + 反馈闭环（4 周）

- Executor 支持步骤级失败恢复（Anchor 找不到时尝试 fallback）
- 失败自动回退到 AI 探索，成功后更新 Recipe
- 建立 Recipe 状态机（DRAFT → ACTIVE → DEGRADED → DEPRECATED）

**交付物：** 完整的自进化闭环

---

## 11. 不做什么（明确的非目标）


| 非目标                         | 理由                                |
| --------------------------- | --------------------------------- |
| 在 amctl 设备端实现 Skill Library | 职责不对，amctl 是无状态控制接口               |
| 构建通用 RPA 平台                 | 目标是 Agent 加速，不是取代 Selenium/Appium |
| 端到端 RL 训练                   | 状态空间太大、奖励太稀疏，不适合经典 RL             |
| 第一期就做经验共享                   | 本地闭环未验证前不引入分布式复杂性                 |
| 保证 100% 成功率                 | Recipe 是加速手段，Agent 是最终兜底，两者配合     |


