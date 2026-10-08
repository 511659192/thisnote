# thisnode（韧知笔记）· 最终需求文档与技术文档

> **⚠️ 本文档是 v1.0 的历史设计基准，已与当前实现不一致。** 现状以 [`README.md`](README.md) 为准，
> 保留它只为留下决策脉络，**不要照它改代码**。主要差异（截至当前实现）：
>
> | 本文档的说法 | 当前实现 |
> |---|---|
> | 笔记/卡片/画布/分组/图谱都存数据库（`nodes`、`note_blocks`、`containers`…） | 笔记数据以**本地 md 文件为权威**（一张卡片一个 md，首行是 meta 注释）；数据库里只剩两本迁移账本 |
> | 复习调度用 FSRS | 自研的简化 SM-2（学习步 1m/10m，毕业后一天一次），排期写在本插件自己的 meta 文件里 |
> | Node 是统一原子，Card 是它在画布上的投影 | 一「笔记」持有卡片清单，卡片是笔记里的内容块；画布/图谱模型未按本文档落地 |
> | 单体 Spring Boot 应用 | 主体是**纯平台**，业务在 `plugin-notes`（`kind=CORE`）；插件是 `plugins/*.jar`（PF4J/SBP） |
> | 插件经门面 `VaultGateway` 读笔记 | 该契约已删除：插件不读笔记数据；跨插件通知走主容器转发的 Spring event（`CardChangedEvent`） |

> 版本：v1.0  
> 定位：本地优先的节点化知识工具，以 Node 为统一原子，笔记、画布、图谱、记忆、AI 是同一份数据的多种投影。  
> 核心原则：**先有笔记，再有卡片；核心保持纯粹，能力通过插件扩展。**

---

# 第一部分：需求文档

## 1. 产品概述

### 1.1 一句话描述
thisnode（韧知笔记）是一款本地优先的节点化知识工具。每个 Node 是最小知识原子，可编辑、可链接、可空间化、可复习、可被 AI 增强。

### 1.2 核心概念

| 概念 | 说明 |
|---|---|
| **Node 节点** | 最小知识原子。含 Markdown 内容、标签、链接、属性、记忆状态 |
| **Note 笔记** | Node 的线性阅读/编辑视图，包含多个 Card |
| **Card 卡片** | Node 在画布上的投影，有位置、大小、颜色 |
| **Board 画布** | Node 的空间布局容器 |
| **Group 分组** | 画布上的容器，可容纳卡片 |
| **Link 链接** | Node 与 Node 之间的关系 |
| **Graph 图谱** | Link 的可视化视图 |
| **Review 复习** | Node 的 FSRS 记忆调度 |
| **Daily Note 每日笔记** | 特殊 Node，按日期自动创建 |

### 1.3 产品原则

1. **本地优先**：所有数据先写本地，云端可选。
2. **Node 为原子**：笔记、卡片、闪卡是同一份数据的三种投影。
3. **核心纯粹**：核心不包含记忆、AI、云同步逻辑，通过插件扩展。
4. **插件开放**：官方免费插件（记忆）+ 付费插件（AI）+ 第三方插件。
5. **数据可移植**：Markdown + JSON，导出不锁定。
6. **渐进增强**：MVP 先做笔记 + 画布，后续叠加图谱、记忆、AI。

### 1.4 商业模式

| 能力 | 定价 |
|---|---|
| 笔记 + 画布 + 双向链接 + 图谱 | 免费 |
| 本地文件系统存储 | 免费 |
| FSRS 记忆插件 | 免费（官方插件） |
| 云端存储渠道（百度网盘 / WebDAV / 自建网盘） | 付费 |
| AI 总结 + 知识图谱发现 | 付费（订阅 + 配额） |
| 高级记忆算法 / AI 生成卡片 | 付费 |
| 插件市场 | 免费 + 付费插件分成 |

---

## 2. MVP 需求

### 2.1 MVP 目标
让用户能**创建、编辑、组织、搜索笔记**，并把笔记投影到无限画布上做空间化组织。数据不丢，操作顺滑，愿意持续使用。

**核心假设**：用户愿意用“笔记 + 空间位置 + 链接 + 分组”来表达信息结构。  
**验证方式**：内测用户连续 7 天使用，人均每周创建 Node > 50，链接 > 10。

### 2.2 MVP 范围

#### 2.2.1 笔记能力（P0）

| 模块 | 功能 |
|---|---|
| 笔记 CRUD | 新建、编辑、删除、复制、归档、回收站 |
| 编辑器 | Markdown 源码/预览，斜杠命令，快捷键 |
| 内容块 | 标题、正文、列表、待办、引用、代码块、表格、图片、附件、分割线 |
| 笔记列表 | 全部、文件夹/笔记本、标签、收藏、最近、回收站 |
| 标签 | 多标签、标签筛选 |
| 搜索 | 全文搜索（SQLite FTS5），Cmd/Ctrl + K 命令面板 |
| 双向链接基础 | `[[笔记标题]]`，反向链接面板 |
| 导入导出 | Markdown 导入导出，JSON 备份 |
| 自动保存 | 防抖 500ms，状态栏显示“已保存” |
| 快照 | 每天自动备份，保留最近 7 天 |
| 每日笔记 | 按日期创建，模板可选 |

#### 2.2.2 空间化组织（P0）

| 模块 | 功能 |
|---|---|
| 无限画布 | 平移、缩放、网格、小地图 |
| 卡片投影 | 笔记拖入画布成为卡片，编辑同步 |
| 卡片操作 | 拖拽、缩放、颜色、标签、置顶/置底、删除 |
| 分组 | 容器、命名、折叠、移动 |
| 选择 | 多选、框选、对齐、分布 |
| 模板 | 三列看板、四象限、自由白板 |
| 画布列表 | 侧边栏管理多个画布 |

#### 2.2.3 不做

云同步、多人协作、账号系统、FSRS、知识图谱可视化、插件市场、移动端 App、PDF/EPUB 标注、AI 总结/问答、手动连线。

### 2.3 用户故事

| 编号 | 角色 | 故事 | 优先级 |
|---|---|---|---|
| US-01 | 知识工作者 | 我想创建笔记，并用画布把笔记摆出来，理清思路 | P0 |
| US-02 | 学生 | 我想把一门课的章节做成笔记，用分组整理结构 | P0 |
| US-03 | 项目经理 | 我想用三列看板管理任务，卡片可拖拽、可标记颜色 | P0 |
| US-04 | 创作者 | 我想在笔记里写 Markdown，并快速搜索到之前的内容 | P0 |
| US-05 | 所有用户 | 我重启软件后，所有笔记、位置、分组都还在 | P0 |
| US-06 | 所有用户 | 我想导出 Markdown 和 JSON，避免被工具锁定 | P1 |
| US-07 | 所有用户 | 我想把 Obsidian Markdown 导入进来 | P1 |
| US-08 | 所有用户 | 我想用 `[[笔记标题]]` 建立双向链接 | P1 |

### 2.4 功能需求

#### 2.4.1 笔记

| 编号 | 需求 | 说明 |
|---|---|---|
| F-N-01 | 新建笔记 | Cmd/Ctrl + N |
| F-N-02 | 编辑笔记 | Markdown 源码/预览，失焦自动保存 |
| F-N-03 | 笔记列表 | 全部、文件夹、标签、收藏、最近、回收站 |
| F-N-04 | 标签 | 多标签、筛选、重命名 |
| F-N-05 | 属性 | 标题、别名、创建时间、更新时间、来源、状态 |
| F-N-06 | 每日笔记 | 按日期创建，可配模板 |
| F-N-07 | 模板 | 笔记模板、画布模板 |
| F-N-08 | 附件 | 图片、文件、音频，本地存储 |
| F-N-09 | 回收站 | 删除后进回收站，可恢复或彻底删除 |

#### 2.4.2 编辑器

| 编号 | 需求 | 说明 |
|---|---|---|
| F-E-01 | Markdown | 源码模式 + 预览模式 + 分屏 |
| F-E-02 | 斜杠命令 | `/` 插入标题、列表、待办、代码块、表格、图片 |
| F-E-03 | 快捷键 | 加粗、斜体、链接、代码、引用、待办 |
| F-E-04 | 代码块 | 语法高亮，语言选择 |
| F-E-05 | 表格 | 基础表格编辑 |
| F-E-06 | 图片 | 粘贴/拖拽上传，本地引用 |
| F-E-07 | 双向链接 | 输入 `[[` 自动补全笔记标题 |

#### 2.4.3 链接与搜索

| 编号 | 需求 | 说明 |
|---|---|---|
| F-L-01 | 双向链接 | `[[笔记标题]]` 建立链接 |
| F-L-02 | 反向链接 | 笔记底部显示“被以下笔记引用” |
| F-L-03 | 别名 | 笔记可有多个别名，链接可用别名 |
| F-L-04 | 全文搜索 | SQLite FTS5，标题/正文/标签 |
| F-L-05 | 命令面板 | Cmd/Ctrl + K，搜索笔记、执行命令 |
| F-L-06 | 跳转 | 点击结果后打开笔记或画布居中卡片 |

#### 2.4.4 画布

| 编号 | 需求 | 说明 |
|---|---|---|
| F-C-01 | 无限画布 | 任意方向平移，无边界 |
| F-C-02 | 缩放 | 10%–400% |
| F-C-03 | 网格与背景 | 点阵/线阵，可切换颜色 |
| F-C-04 | 小地图 | 右下角缩略图，可点击跳转 |
| F-C-05 | 画布列表 | 侧边栏管理多个画布 |
| F-C-06 | 画布模板 | 三列看板、四象限、自由白板 |

#### 2.4.5 卡片与分组

| 编号 | 需求 | 说明 |
|---|---|---|
| F-K-01 | 创建卡片 | 从笔记列表拖入，或双击画布创建新笔记并投影 |
| F-K-02 | 编辑卡片 | 双击卡片编辑笔记内容，双向同步 |
| F-K-03 | 拖拽移动 | 单张/多张拖拽 |
| F-K-04 | 调整大小 | 拖拽边角，最小 120×80 |
| F-K-05 | 删除/复制 | 右键菜单或快捷键 |
| F-K-06 | 层级 | 置顶、置底、上移、下移 |
| F-K-07 | 颜色 | 预设 8 种颜色 |
| F-K-08 | 标签 | 显示笔记标签 |
| F-K-09 | 卡片状态 | 普通、已完成、待办、重要 |
| F-G-01 | 创建分组 | 选中多张卡片，右键“创建分组” |
| F-G-02 | 分组容器 | 命名、改色、折叠 |
| F-G-03 | 移动分组 | 内部卡片跟随移动 |
| F-G-04 | 卡片入组/出组 | 拖拽进入或离开 |
| F-G-05 | 分组层级 | MVP 只支持一层 |

#### 2.4.6 选择与操作

| 编号 | 需求 | 说明 |
|---|---|---|
| F-S-01 | 单选 | 点击卡片 |
| F-S-02 | 多选 | Shift + 点击，或框选 |
| F-S-03 | 对齐 | 左、右、顶、底、水平居中、垂直居中 |
| F-S-04 | 分布 | 水平等距、垂直等距 |
| F-S-05 | 快捷键 | 复制、粘贴、删除、撤销、重做、全选 |

#### 2.4.7 持久化与导入导出

| 编号 | 需求 | 说明 |
|---|---|---|
| F-P-01 | 自动保存 | 防抖 500ms |
| F-P-02 | 本地 SQLite | 存储 Node、画布、分组、标签 |
| F-P-03 | Markdown 文件 | 每个 Node 同时导出为 .md 文件 |
| F-P-04 | 快照 | 每天自动备份，保留最近 7 天 |
| F-P-05 | 崩溃恢复 | 异常退出后恢复未保存内容 |
| F-IO-01 | JSON 备份 | 导出全量数据 |
| F-IO-02 | JSON 导入 | 从 JSON 恢复 |
| F-IO-03 | Markdown 导出 | 导出文件夹 |
| F-IO-04 | Markdown 导入 | 导入 Obsidian 文件夹 |
| F-IO-05 | 图片附件 | 本地图片引用 |

### 2.5 非功能需求

| 类别 | 要求 |
|---|---|
| 性能 | 单画布 500 张卡片下拖拽/缩放 > 50fps；搜索 < 500ms |
| 启动 | 冷启动 < 3 秒（桌面端） |
| 体积 | 安装包 < 80MB（含 JRE 可放宽到 150MB） |
| 离线 | 完全离线可用，不依赖网络 |
| 数据安全 | 本地优先，自动备份，导出不加密 |
| 跨平台 | Windows / macOS / Linux |
| 可访问性 | 键盘可完成核心操作，快捷键可自定义（P1） |
| 国际化 | MVP 只做简体中文，预留 i18n |

### 2.6 验收标准

- 用户能在 5 分钟内创建 10 篇笔记，并在画布上添加 20 张卡片完成分组。
- 重启软件后，所有笔记、卡片位置、内容、分组完整恢复。
- 500 张卡片下拖拽/缩放流畅，帧率 > 50fps。
- 搜索标题和正文，1 秒内返回结果。
- JSON 导出后可在另一台机器完整恢复。
- Markdown 导出后可在 Obsidian 中打开。
- 至少 3 个内测用户说：“这比 Obsidian Canvas / OneNote 更顺。”

### 2.7 里程碑

| 阶段 | 时间 | 交付 |
|---|---|---|
| M0 笔记核心 + 技术验证 | 1 周 | Tauri + Vue + SQLite 跑通，Node CRUD、Markdown 编辑器 |
| M1 笔记组织与搜索 | 2 周 | 笔记列表、标签、全文搜索、导入导出 |
| M2 画布投影与空间组织 | 2-3 周 | 卡片、拖拽、分组、对齐、颜色 |
| M3 双向链接与命令面板 | 1-2 周 | `[[ ]]`、反向链接、Cmd+K |
| M4 快照与内测 | 1-2 周 | 5-10 个真实用户，收集留存与反馈 |

### 2.8 风险与对策

| 风险 | 对策 |
|---|---|
| 范围蔓延 | 严格锁定 MVP，不做云同步、复习、插件 |
| 画布性能差 | 虚拟化渲染，限制单画布卡片数，先优化 500 张 |
| 笔记能力被弱化 | 原子统一为 Node，笔记视图和画布视图平级 |
| 数据丢失 | 自动保存 + 本地快照 + JSON 导出 |
| 用户不迁移 | 先做 Markdown / Obsidian 导入 |
| 未来同步难 | 从第一天记录 ops 操作日志 |
| Vue 画布引擎不达预期 | 先用 Vue Flow 验证，不足再换 AntV X6 或自研 Konva |

---

## 3. 全部需求（路线图）

### 阶段一：笔记核心 + 空间化组织（MVP）
见上文。

**成功指标**：
- 次日留存 > 40%
- 7 日留存 > 20%
- 人均每周创建 Node > 50
- 人均每周链接 > 10

### 阶段二：双向链接与知识图谱

| 编号 | 需求 | 说明 |
|---|---|---|
| F-L2-01 | 双向链接 | `[[Node标题]]` 自动建立链接 |
| F-L2-02 | 反向链接 | Node 底部显示“被以下 Node 引用” |
| F-L2-03 | 别名 | Node 可有多个别名，链接可用别名 |
| F-L2-04 | 未链接提及 | 提示“提到了某 Node 但未建立链接” |
| F-L2-05 | 知识图谱视图 | 全局图谱，节点为 Node，边为链接 |
| F-L2-06 | 局部图谱 | 以某 Node 为中心，显示 N 层关系 |
| F-L2-07 | 图谱过滤 | 按标签、颜色、分组过滤 |
| F-L2-08 | 图谱交互 | 点击节点跳转，拖拽节点固定位置 |
| F-L2-09 | 链接类型 | 自定义关系类型（依赖、参考、冲突） |

**技术要点**：
- 解析 Markdown 中的 `[[...]]`，写入 `links` 表。
- 图谱渲染：D3.js / Cytoscape.js / Sigma.js。
- 图谱与画布共享同一份 Node 数据，图谱是另一种投影。
- 性能：1000 节点内流畅，超过则聚合。

### 阶段三：插件系统

| 编号 | 需求 | 说明 |
|---|---|---|
| F-PL-01 | 插件宿主 | 支持 JS/WASM 插件，沙箱运行 |
| F-PL-02 | 插件 API | 访问 Node、画布、图谱数据 |
| F-PL-03 | 自定义视图 | 插件可注册新视图（时间线、日历、表格） |
| F-PL-04 | 自定义 Node 类型 | 插件可定义新类型（代码、公式、思维导图） |
| F-PL-05 | 插件市场 | 浏览、安装、更新、禁用 |
| F-PL-06 | 权限系统 | 插件声明权限，用户授权 |
| F-PL-07 | 开发者文档 | API 文档、示例插件、调试工具 |

**技术要点**：
- 插件运行在独立 Web Worker 或 iframe，禁止直接访问文件系统。
- 通过消息总线与主进程通信。
- 插件数据存储在 `plugin_data` 表，按插件隔离。
- 插件版本与核心版本兼容性检查。

### 阶段四：记忆能力（官方免费插件）

| 编号 | 需求 | 说明 |
|---|---|---|
| F-R-01 | 标记为复习 Node | 任意 Node 可开启“复习”开关 |
| F-R-02 | 复习队列 | 每日待复习列表，按 FSRS 排序 |
| F-R-03 | 复习界面 | 显示正面/背面，用户评分 |
| F-R-04 | 评分 | 忘记 / 困难 / 良好 / 简单 |
| F-R-05 | FSRS 参数 | 支持默认参数，后续可自定义 |
| F-R-06 | 记忆状态 | 显示每张 Node 的 DSR |
| F-R-07 | 复习统计 | 每日复习量、正确率、未来到期预测 |
| F-R-08 | 看板联动 | 看板位置、分组、链接可影响复习优先级 |
| F-R-09 | 图谱联动 | 孤立节点自动进入复习，高连接节点降低频率 |

**技术要点**：
- 记忆数据存储在 `plugin_data` 表，按 `plugin_id = com.thisnode.fsrs` 隔离。
- 核心不感知记忆，只提供通用扩展点和事件。
- FSRS 算法用 Java 实现，插件订阅画布/链接事件调整调度。

### 阶段五：云端存储（付费）

| 编号 | 需求 | 说明 |
|---|---|---|
| F-ST-01 | 存储抽象层 | 统一 StorageProvider 接口 |
| F-ST-02 | 本地文件系统 | 免费基础渠道 |
| F-ST-03 | 百度网盘 | 付费渠道，OAuth2.0 |
| F-ST-04 | WebDAV | 付费渠道，支持自建网盘 |
| F-ST-05 | 自建网盘 | 付费渠道，WebDAV 协议 |
| F-ST-06 | 多渠道切换 | 用户可选择默认渠道 |
| F-ST-07 | License 验证 | Ed25519 离线签名 |
| F-ST-08 | 功能门控 | 未授权渠道显示锁图标 |

**技术要点**：
- `StorageProvider` 接口：upload / download / delete / list / exists。
- 百度网盘：封装开放平台 REST API。
- WebDAV：Sardine 客户端。
- License：Ed25519 签名 + 公钥内嵌，离线可验。
- 付费策略：本地免费，云端渠道需 License。

### 阶段六：AI 能力（付费插件）

| 编号 | 需求 | 说明 |
|---|---|---|
| F-AI-01 | AI 总结 | 对 Node 内容做摘要、提炼、改写 |
| F-AI-02 | 知识图谱发现 | 从内容中挖掘隐含关联，推荐链接 |
| F-AI-03 | 向量化 | 本地轻量模型 / 云端强模型 |
| F-AI-04 | 相似度检索 | Top-K 相似 Node |
| F-AI-05 | LLM 推理 | 判断关系类型，生成推荐理由 |
| F-AI-06 | 隐私模式 | 脱敏 + 本地模型 fallback |
| F-AI-07 | 计费与配额 | 订阅 + Token 配额 + 模型分级 |
| F-AI-08 | 调用日志 | 用户可查看每次 AI 调用内容 |
| F-AI-09 | 批量能力 | 批量总结、批量发现 |
| F-AI-10 | 桥接发现 | 发现两个子图之间的可能连接 |
| F-AI-11 | 主题聚类 | 自动发现隐性主题，建议 MOC |
| F-AI-12 | 矛盾检测 | 发现内容冲突的 Node |
| F-AI-13 | 知识缺口 | 发现某主题下笔记稀疏，建议补充 |

**技术要点**：
- AI 插件标记 `premium: true`，声明权限 `network:ai-gateway`。
- 云端推理网关：Spring Boot + License 验证 + 配额管理 + 模型路由 + 缓存。
- 本地向量化：sqlite-vec 或 LanceDB + bge-small。
- 隐私模式：脱敏 + 本地模型（Ollama + Qwen）。
- 计费：订阅 + 配额，避免按量计费焦虑。

### 阶段七：云端同步与协作

| 编号 | 需求 | 说明 |
|---|---|---|
| F-SY-01 | 账号系统 | 注册、登录、第三方登录 |
| F-SY-02 | 多端同步 | 桌面、Web、移动端数据同步 |
| F-SY-03 | 冲突解决 | 基于 ops 日志 + CRDT，自动合并 |
| F-SY-04 | 选择性同步 | 按画布同步 |
| F-SY-05 | 端到端加密 | 可选，用户持有密钥 |
| F-SY-06 | 分享 | 生成只读链接，可嵌入 |
| F-SY-07 | 协作 | 多人实时编辑同一画布 |
| F-SY-08 | 版本历史 | 查看和恢复历史版本 |
| F-SY-09 | 离线队列 | 离线操作排队，联网后自动同步 |

**技术要点**：
- 同步协议：基于 ops 表的增量同步 + CRDT（Yjs / Automerge）。
- 云端后端：Spring Boot + PostgreSQL + Redis + 对象存储。
- 实时协作：WebSocket + Yjs。
- 端到端加密：客户端加密，服务端只存密文。

### 阶段八：移动端与生态

| 编号 | 需求 | 说明 |
|---|---|---|
| F-M-01 | 移动端 App | iOS / Android |
| F-M-02 | 小程序 | 微信/支付宝小程序 |
| F-M-03 | 浏览器插件 | 网页剪藏到 Node |
| F-M-04 | API 开放 | 第三方通过 API 读写 Node |
| F-M-05 | 自动化 | Webhook、Zapier、n8n |
| F-M-06 | 模板市场 | 用户分享画布模板、笔记模板 |

---

## 4. 长期愿景

**一句话**：thisnode（韧知笔记）成为“记录—连接—记忆—创造”一体化的本地优先知识操作系统。

- **记录**：Node + Markdown + 画布 + 多端
- **连接**：双向链接 + 知识图谱 + 插件扩展
- **记忆**：FSRS + 看板/图谱联动 + 复习统计
- **创造**：白板 + 图谱 + AI + 分享协作

**核心差异**：
- Node 是统一原子，笔记、卡片、闪卡是同一份数据的三种投影。
- 空间位置、链接关系、记忆状态互相影响，形成闭环。
- 本地优先，云端可选，插件开放，数据可移植。
- AI 和云端存储作为付费插件，核心保持免费纯粹。

---

# 第二部分：技术文档

## 1. 技术栈

| 层级 | 技术 |
|---|---|
| 桌面壳 | Tauri 2 |
| 前端框架 | Vue 3 + TypeScript |
| 构建工具 | Vite |
| 状态管理 | Pinia |
| 路由 | Vue Router |
| 画布引擎 | Vue Flow（MVP）→ AntV X6 或自研 Konva（按需） |
| Markdown 编辑器 | CodeMirror 6 |
| 请求/缓存 | Axios + @tanstack/vue-query |
| 本地后端 | Java Spring Boot |
| 本地数据库 | SQLite + FTS5 |
| 云端数据库 | PostgreSQL / MySQL |
| 搜索 | SQLite FTS5 |
| 向量存储 | sqlite-vec / LanceDB |
| 同步协议 | ops 日志 + CRDT（Yjs / Automerge） |
| 实时协作 | WebSocket + Yjs |
| 插件框架（后端） | PF4J |
| 插件框架（前端） | 自研微内核 + iframe 沙箱 |
| License | Ed25519 离线签名 |
| 云端存储 | 百度网盘 API / Sardine (WebDAV) / 本地 NIO |
| AI 推理 | 云端网关 + LLM API / 本地 Ollama |

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────┐
│                    thisnode 客户端                        │
│  ┌───────────────────────────────────────────────────┐  │
│  │                 Tauri 2 桌面壳                     │  │
│  │  ┌─────────────────────────────────────────────┐  │  │
│  │  │         前端（Vue 3 + TS + Vite）            │  │  │
│  │  │  ┌─────────┐ ┌─────────┐ ┌──────────────┐  │  │  │
│  │  │  │ 笔记视图 │ │ 画布视图 │ │ 插件 UI 容器  │  │  │  │
│  │  │  └─────────┘ └─────────┘ └──────────────┘  │  │  │
│  │  │  ┌─────────────────────────────────────┐   │  │  │
│  │  │  │      前端微内核 + 事件总线            │   │  │  │
│  │  │  └─────────────────────────────────────┘   │  │  │
│  │  └───────────────────┬─────────────────────────┘  │  │
│  │                      │ HTTP localhost             │  │
│  │  ┌───────────────────▼─────────────────────────┐  │  │
│  │  │         本地 Spring Boot 进程                │  │  │
│  │  │  ┌─────────┐ ┌─────────┐ ┌──────────────┐  │  │  │
│  │  │  │ Node API│ │ 存储抽象 │ │ 插件宿主 PF4J │  │  │  │
│  │  │  └─────────┘ └─────────┘ └──────────────┘  │  │  │
│  │  │  ┌─────────────────────────────────────┐   │  │  │
│  │  │  │       SQLite + FTS5 + 文件系统        │   │  │  │
│  │  │  └─────────────────────────────────────┘   │  │  │
│  │  └─────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
                            │ HTTPS
┌───────────────────────────▼─────────────────────────────┐
│              云端服务（Spring Boot）                      │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌──────────────┐  │
│  │ 账号服务 │ │ 同步服务 │ │ AI 网关  │ │ License 服务  │  │
│  └─────────┘ └─────────┘ └─────────┘ └──────────────┘  │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌──────────────┐  │
│  │PostgreSQL│ │  Redis  │ │ 对象存储 │ │  LLM API     │  │
│  └─────────┘ └─────────┘ └─────────┘ └──────────────┘  │
└─────────────────────────────────────────────────────────┘
```

## 3. 数据模型

### 3.1 SQLite 表结构

```sql
-- 统一原子：Node
nodes (
  id TEXT PRIMARY KEY,
  title TEXT,
  content_md TEXT,
  type TEXT,              -- note / daily / template
  created_at INTEGER,
  updated_at INTEGER,
  archived INTEGER DEFAULT 0,
  deleted INTEGER DEFAULT 0,
  daily_date TEXT
)

-- 容器：分组和笔记
containers (
  id TEXT PRIMARY KEY,
  type TEXT,              -- group / note
  title TEXT,
  parent_id TEXT,
  created_at INTEGER,
  updated_at INTEGER
)

-- 卡片归属
card_containers (
  card_id TEXT,
  container_id TEXT,
  position INTEGER,
  PRIMARY KEY (card_id, container_id)
)

-- 别名
node_aliases (id, node_id, alias)

-- 标签
tags (id, name UNIQUE)
node_tags (node_id, tag_id, PRIMARY KEY(node_id, tag_id))

-- 链接
links (
  id TEXT PRIMARY KEY,
  from_node_id TEXT,
  to_node_id TEXT,
  label TEXT,
  type TEXT,
  created_at INTEGER
)

-- 画布
boards (id, name, created_at, updated_at, is_favorite)
board_items (
  id TEXT PRIMARY KEY,
  board_id TEXT,
  node_id TEXT,
  x REAL, y REAL,
  w REAL, h REAL,
  z INTEGER,
  color TEXT,
  collapsed INTEGER DEFAULT 0,
  group_id TEXT
)
groups (id, board_id, title, x, y, w, h, color, collapsed)

-- 插件数据
plugin_data (
  id TEXT PRIMARY KEY,
  plugin_id TEXT,
  entity_type TEXT,       -- node / board / global
  entity_id TEXT,
  key TEXT,
  value TEXT,             -- JSON
  updated_at INTEGER
)

-- 操作日志
ops (
  id TEXT PRIMARY KEY,
  device_id TEXT,
  entity_type TEXT,
  entity_id TEXT,
  action TEXT,
  payload TEXT,
  created_at INTEGER
)
```

### 3.2 关键设计

- 笔记和卡片统一为 `nodes`。
- `board_items` 是 Node 在画布上的投影。
- 链接属于 Node 关系，跨笔记、跨分组天然支持。
- 插件数据按 `plugin_id` 隔离。
- 所有写操作记录到 `ops`，为未来同步做准备。
- Markdown 文件与 SQLite 双向同步，以 SQLite 为准。

## 4. 前端技术文档

### 4.1 目录结构

```
src/
├── main.ts
├── App.vue
├── core/                    # 微内核
│   ├── plugin-manager.ts    # 插件管理
│   ├── event-bus.ts         # 事件总线
│   ├── extension-points.ts  # 扩展点注册
│   └── sandbox.ts           # 插件沙箱
├── views/                   # 内置视图
│   ├── NoteView.vue         # 笔记视图
│   ├── BoardView.vue        # 画布视图
│   ├── GraphView.vue        # 图谱视图
│   └── SearchView.vue       # 搜索视图
├── components/              # 通用组件
│   ├── Editor/              # CodeMirror 编辑器
│   ├── Card/                # 卡片组件
│   ├── Group/               # 分组组件
│   └── Sidebar/             # 侧边栏
├── stores/                  # Pinia 状态
│   ├── node.ts
│   ├── board.ts
│   ├── plugin.ts
│   └── settings.ts
├── api/                     # 后端 API
│   ├── node.ts
│   ├── board.ts
│   ├── plugin.ts
│   └── storage.ts
└── plugins/                 # 官方插件
    ├── fsrs/                # 记忆插件
    └── ai/                  # AI 插件
```

### 4.2 前端微内核

```typescript
// core/plugin-manager.ts
export interface PluginManifest {
  id: string;
  name: string;
  version: string;
  main: string;
  premium?: boolean;
  permissions: string[];
  extensionPoints: string[];
  dependencies?: Record<string, string>;
}

export interface Plugin {
  manifest: PluginManifest;
  onLoad(): Promise<void>;
  onUnload(): Promise<void>;
}

class PluginManager {
  private plugins = new Map<string, Plugin>();
  private extensionPoints = new Map<string, any[]>();

  async load(manifest: PluginManifest): Promise<void> {
    const module = await import(manifest.main);
    const plugin: Plugin = module.default;
    await plugin.onLoad();
    this.plugins.set(manifest.id, plugin);
  }

  registerExtension(point: string, extension: any): void {
    if (!this.extensionPoints.has(point)) {
      this.extensionPoints.set(point, []);
    }
    this.extensionPoints.get(point)!.push(extension);
  }

  getExtensions<T>(point: string): T[] {
    return (this.extensionPoints.get(point) || []) as T[];
  }
}
```

### 4.3 事件总线

```typescript
// core/event-bus.ts
type EventHandler<T = any> = (payload: T) => void;

class EventBus {
  private handlers = new Map<string, EventHandler[]>();

  on<T>(event: string, handler: EventHandler<T>): void {
    if (!this.handlers.has(event)) {
      this.handlers.set(event, []);
    }
    this.handlers.get(event)!.push(handler);
  }

  off<T>(event: string, handler: EventHandler<T>): void {
    const handlers = this.handlers.get(event) || [];
    const index = handlers.indexOf(handler);
    if (index >= 0) handlers.splice(index, 1);
  }

  emit<T>(event: string, payload: T): void {
    const handlers = this.handlers.get(event) || [];
    handlers.forEach(h => h(payload));
  }
}

export const eventBus = new EventBus();
```

### 4.4 扩展点设计

| 扩展点 | 说明 | 示例 |
|---|---|---|
| `view:board` | 画布视图 | 时间线视图、日历视图 |
| `view:graph` | 图谱布局 | 力导向图、层级图 |
| `card:renderer` | 卡片渲染器 | 代码卡片、公式卡片 |
| `command` | 命令 | 导出 PDF、批量打标签 |
| `import` | 导入格式 | Roam、Notion |
| `review:algorithm` | 复习算法 | FSRS 变体 |
| `storage:provider` | 存储渠道 | 百度网盘、WebDAV |
| `ai:capability` | AI 能力 | 总结、图谱发现 |

### 4.5 插件沙箱

```typescript
// core/sandbox.ts
export function createPluginSandbox(pluginId: string): HTMLIFrameElement {
  const iframe = document.createElement('iframe');
  iframe.sandbox.add('allow-scripts');
  iframe.style.display = 'none';
  document.body.appendChild(iframe);

  const channel = new MessageChannel();
  iframe.contentWindow!.postMessage(
    { type: 'init', pluginId },
    '*',
    [channel.port2]
  );

  return iframe;
}
```

## 5. 后端技术文档

### 5.1 模块结构

```
backend/
├── src/main/java/com/thisnode/
│   ├── ThisnodeApplication.java
│   ├── controller/          # REST API
│   │   ├── NodeController.java
│   │   ├── BoardController.java
│   │   ├── PluginController.java
│   │   └── StorageController.java
│   ├── service/             # 业务逻辑
│   │   ├── NodeService.java
│   │   ├── BoardService.java
│   │   ├── LinkService.java
│   │   └── SearchService.java
│   ├── repository/          # 数据访问
│   │   ├── NodeRepository.java
│   │   ├── BoardRepository.java
│   │   └── PluginDataRepository.java
│   ├── storage/             # 存储抽象
│   │   ├── StorageProvider.java
│   │   ├── LocalFileProvider.java
│   │   ├── BaiduPanProvider.java
│   │   └── WebDavProvider.java
│   ├── plugin/              # 插件宿主
│   │   ├── PluginManager.java
│   │   └── ExtensionPoint.java
│   ├── license/             # License 验证
│   │   └── LicenseVerifier.java
│   └── sync/                # 同步（后续）
│       └── OpsSyncService.java
└── src/main/resources/
    ├── application.yml
    └── db/migration/        # Flyway 迁移
```

### 5.2 存储抽象层

```java
public interface StorageProvider {
    String upload(String path, InputStream data) throws StorageException;
    InputStream download(String path) throws StorageException;
    void delete(String path) throws StorageException;
    List<FileMeta> list(String path) throws StorageException;
    boolean exists(String path) throws StorageException;
    String getProviderId();
}

@Component
public class LocalFileProvider implements StorageProvider {
    private final Path root;

    public LocalFileProvider(@Value("${thisnode.storage.local.root}") String root) {
        this.root = Paths.get(root);
    }

    @Override
    public String upload(String path, InputStream data) throws StorageException {
        Path target = root.resolve(path);
        Files.createDirectories(target.getParent());
        Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);
        return target.toString();
    }
    // ...
}
```

### 5.3 百度网盘 Provider

```java
@Component
public class BaiduPanProvider implements StorageProvider {
    private final BaiduPanClient client;

    @Override
    public String upload(String path, InputStream data) {
        // 1. 预创建文件
        PreCreateResult pre = client.preCreate(path, data.available());
        // 2. 分片上传
        client.upload(pre.getUploadId(), path, data);
        // 3. 创建文件
        return client.createFile(path, pre.getUploadId());
    }

    @Override
    public InputStream download(String path) {
        return client.download(path);
    }

    @Override
    public String getProviderId() { return "baidu"; }
}
```

### 5.4 WebDAV Provider

```java
@Component
public class WebDavProvider implements StorageProvider {
    private final Sardine sardine;

    public WebDavProvider(WebDavConfig config) {
        this.sardine = SardineFactory.begin(config.getUsername(), config.getPassword());
    }

    @Override
    public String upload(String path, InputStream data) throws IOException {
        sardine.put(config.getBaseUrl() + path, data);
        return path;
    }

    @Override
    public InputStream download(String path) throws IOException {
        return sardine.get(config.getBaseUrl() + path);
    }

    @Override
    public String getProviderId() { return "webdav"; }
}
```

### 5.5 存储 Provider 工厂

```java
@Service
public class StorageProviderFactory {
    private final Map<String, StorageProvider> providers;
    private final LicenseVerifier licenseVerifier;

    public StorageProviderFactory(List<StorageProvider> providerList,
                                   LicenseVerifier licenseVerifier) {
        this.providers = providerList.stream()
            .collect(Collectors.toMap(StorageProvider::getProviderId, p -> p));
        this.licenseVerifier = licenseVerifier;
    }

    public StorageProvider get(String providerId, String licenseKey) {
        StorageProvider provider = providers.get(providerId);
        if (provider == null) {
            throw new IllegalArgumentException("Unknown provider: " + providerId);
        }
        if (!"local".equals(providerId)) {
            if (!licenseVerifier.isValid(licenseKey) ||
                !licenseVerifier.hasAccess(licenseKey, providerId)) {
                throw new PremiumFeatureException(providerId);
            }
        }
        return provider;
    }
}
```

### 5.6 License 验证

```java
@Component
public class LicenseVerifier {
    private final PublicKey publicKey;

    public LicenseVerifier(@Value("${thisnode.license.public-key}") String publicKeyBase64) {
        this.publicKey = KeyFactory.getInstance("Ed25519")
            .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)));
    }

    public boolean isValid(String licenseKey) {
        try {
            String[] parts = licenseKey.split("\\.");
            byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
            byte[] signature = Base64.getUrlDecoder().decode(parts[1]);

            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(publicKey);
            sig.update(payload);
            if (!sig.verify(signature)) return false;

            // 检查到期时间
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(payload);
            long expiresAt = node.get("expires_at").asLong();
            return System.currentTimeMillis() / 1000 < expiresAt;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean hasAccess(String licenseKey, String feature) {
        // 解析 payload，检查 features 数组是否包含 feature
        // ...
    }
}
```

### 5.7 插件宿主（PF4J）

```java
@Configuration
public class PluginConfig {
    @Bean
    public PluginManager pluginManager() {
        PluginManager pluginManager = new DefaultPluginManager(
            Paths.get("plugins")
        );
        pluginManager.loadPlugins();
        pluginManager.startPlugins();
        return pluginManager;
    }
}

// 扩展点定义
public interface NodeViewExtension extends ExtensionPoint {
    String getViewId();
    String getViewName();
}

// 插件实现
@Extension
public class KanbanViewExtension implements NodeViewExtension {
    @Override
    public String getViewId() { return "kanban"; }

    @Override
    public String getViewName() { return "看板视图"; }
}
```

### 5.8 搜索服务

```java
@Service
public class SearchService {
    private final JdbcTemplate jdbcTemplate;

    public List<SearchResult> search(String query, int limit) {
        String sql = """
            SELECT n.id, n.title, snippet(nodes_fts, 1, '<mark>', '</mark>', '...', 32) AS snippet
            FROM nodes_fts
            JOIN nodes n ON n.id = nodes_fts.id
            WHERE nodes_fts MATCH ?
            ORDER BY rank
            LIMIT ?
        """;
        return jdbcTemplate.query(sql, new Object[]{query, limit},
            (rs, rowNum) -> new SearchResult(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("snippet")
            ));
    }
}
```

## 6. 插件系统技术文档

### 6.1 插件 Manifest

```json
{
  "id": "com.thisnode.fsrs",
  "name": "FSRS 记忆",
  "version": "1.0.0",
  "main": "index.js",
  "premium": false,
  "permissions": ["read:node", "write:node", "ui:sidebar"],
  "extensionPoints": ["command", "view:sidebar", "card:renderer"],
  "dependencies": {}
}
```

### 6.2 插件生命周期

```
安装 → 下载 → 验证签名 → 解压 → 注册 Manifest
  → 加载 → 检查权限 → 动态导入 → onLoad()
  → 运行 → 注册扩展点 → 订阅事件
  → 卸载 → onUnload() → 清理资源 → 删除数据（可选）
```

### 6.3 记忆插件（官方免费）

```typescript
// plugins/fsrs/index.ts
export default {
  id: 'com.thisnode.fsrs',
  name: 'FSRS 记忆',
  version: '1.0.0',

  commands: [
    { id: 'fsrs.toggle-review', label: '标记为复习卡片', action: toggleReview },
    { id: 'fsrs.start-review', label: '开始今日复习', action: startReview }
  ],

  views: [
    { id: 'fsrs.queue', label: '复习队列', component: ReviewQueue, slot: 'sidebar' },
    { id: 'fsrs.stats', label: '复习统计', component: ReviewStats, slot: 'main' }
  ],

  cardRenderers: [
    { id: 'fsrs.badge', component: DueBadge, position: 'top-right' }
  ],

  contextMenu: [
    { id: 'fsrs.mark', label: '加入复习', action: markForReview }
  ],

  events: {
    'node:deleted': (nodeId: string) => cleanupReviewData(nodeId),
    'board:item-moved': (payload: any) => adjustReviewPriority(payload),
    'link:created': (payload: any) => handleLinkCreated(payload)
  },

  async onLoad() {
    await initFSRS();
  },

  async onUnload() {
    cleanup();
  }
};
```

### 6.4 AI 插件（付费）

```typescript
// plugins/ai/index.ts
export default {
  id: 'com.thisnode.ai',
  name: 'AI 增强',
  version: '1.0.0',
  premium: true,

  commands: [
    { id: 'ai.summarize', label: 'AI 总结', action: summarizeNode },
    { id: 'ai.discover-links', label: '发现关联', action: discoverLinks },
    { id: 'ai.ask', label: '向笔记提问', action: askNotes }
  ],

  views: [
    { id: 'ai.related', label: '相关笔记', component: RelatedPanel, slot: 'sidebar' },
    { id: 'ai.summary', label: '总结', component: SummaryView, slot: 'node-footer' }
  ],

  contextMenu: [
    { id: 'ai.summarize-selection', label: '总结选中内容', action: summarizeSelection },
    { id: 'ai.expand', label: 'AI 扩写', action: expandText }
  ],

  settings: [
    { id: 'ai.model', label: '模型选择', type: 'select', options: ['fast', 'balanced', 'quality'] },
    { id: 'ai.privacy', label: '隐私模式', type: 'toggle', default: true }
  ],

  async onLoad() {
    await initAIClient();
  },

  async onUnload() {
    cleanup();
  }
};
```

### 6.5 云端 AI 推理网关

```java
@RestController
@RequestMapping("/api/ai")
public class AIGatewayController {

    @PostMapping("/summarize")
    public ResponseEntity<SummaryResult> summarize(
            @RequestBody SummarizeRequest request,
            @RequestHeader("X-License-Key") String licenseKey) {

        // 1. 验证 License
        if (!licenseVerifier.isValid(licenseKey)) {
            return ResponseEntity.status(401).build();
        }

        // 2. 检查配额
        Quota quota = quotaService.getQuota(licenseKey);
        if (quota.getRemaining() <= 0) {
            return ResponseEntity.status(429).build();
        }

        // 3. 路由模型
        LLMClient client = modelRouter.route(request.getModelLevel());

        // 4. 调用 LLM
        SummaryResult result = client.summarize(request.getContent());

        // 5. 扣减配额
        quotaService.consume(licenseKey, result.getTokenUsage());

        // 6. 缓存
        cacheService.put(request.getContent(), request.getModelLevel(), result);

        return ResponseEntity.ok(result);
    }

    @PostMapping("/discover-links")
    public ResponseEntity<DiscoverResult> discoverLinks(
            @RequestBody DiscoverRequest request,
            @RequestHeader("X-License-Key") String licenseKey) {
        // 1. License 验证
        // 2. 配额检查
        // 3. 向量化（本地或云端）
        // 4. 相似度检索
        // 5. LLM 推理
        // 6. 返回推荐链接
    }
}
```

## 7. 安全与隐私

| 层面 | 措施 |
|---|---|
| 插件沙箱 | iframe + postMessage，禁止直接访问文件系统 |
| 插件权限 | Manifest 声明，运行时校验 |
| License | Ed25519 签名，离线验证 |
| AI 隐私 | 默认本地向量化，隐私模式脱敏，云端不存储原文 |
| 同步加密 | 可选端到端加密，服务端只存密文 |
| 数据备份 | 本地快照 + JSON 导出 |
| 操作日志 | ops 表记录所有写操作，便于审计和同步 |

## 8. 性能优化

| 场景 | 策略 |
|---|---|
| 画布 500+ 卡片 | 虚拟化渲染，只渲染视口内卡片 |
| 全文搜索 | SQLite FTS5 + 索引优化 |
| 图谱 1000+ 节点 | 聚合、分层、按需加载 |
| 向量检索 | 本地 sqlite-vec，Top-K 限制 |
| 自动保存 | 防抖 500ms + 增量写入 |
| 启动速度 | 懒加载插件，异步初始化 |

## 9. 部署与打包

### 9.1 桌面端打包

```
Tauri 打包流程：
1. 前端构建：vite build → dist/
2. 后端构建：mvn package → thisnode-backend.jar
3. Tauri 配置：
   - 将 jar 作为 sidecar 资源
   - 启动时拉起 Java 进程
   - 监听 localhost 端口
4. 输出：Windows .msi / macOS .dmg / Linux .deb
```

### 9.2 云端部署

```
云端服务：
- Spring Boot 应用 → Docker 镜像
- PostgreSQL → 云数据库
- Redis → 云缓存
- 对象存储 → S3 / OSS
- LLM API → 按需调用
- 部署：K8s 或单机 Docker Compose
```

## 10. 开发规范

| 类别 | 规范 |
|---|---|
| 代码风格 | 前端 ESLint + Prettier；后端 Google Java Style |
| 提交规范 | Conventional Commits |
| 分支策略 | main / develop / feature/* / release/* |
| 测试 | 前端 Vitest；后端 JUnit 5 + Mockito |
| CI/CD | GitHub Actions：lint → test → build |
| 文档 | 每个模块 README + API 文档（SpringDoc） |
| 版本 | 语义化版本 SemVer |

---

# 附录

## A. 术语表

| 术语 | 说明 |
|---|---|
| Node | 最小知识原子，含 Markdown 内容、标签、链接、记忆状态 |
| Note | Node 的线性阅读/编辑视图 |
| Card | Node 在画布上的投影 |
| Board | 无限画布，Node 的空间布局 |
| Group | 画布上的容器 |
| Link | Node 与 Node 之间的关系 |
| Graph | Link 的可视化 |
| FSRS | Free Spaced Repetition Scheduler |
| DSR | Difficulty, Stability, Retrievability |
| ops | 操作日志，用于同步和冲突解决 |
| PF4J | Plugin Framework for Java |
| CRDT | Conflict-free Replicated Data Type |

## B. 参考工具

- Obsidian：双向链接、图谱、插件、Canvas
- OneNote：无限画布、空间化组织
- Anki：FSRS、间隔重复、复习统计
- Heptabase：白板 + 卡片
- Logseq：大纲 + 白板 + 闪卡
- RemNote：笔记 + 闪卡 + 层级
- MarginNote：阅读 + 脑图 + 复习
- Grafana：插件沙箱、微内核
- EngineCore：微内核 + DAG 依赖 + 沙箱

## C. 下一步行动

1. **今天**：确认 Node 为统一原子，更新项目名、仓库名、包名。
2. **本周**：用 Vue 3 + Vite + CodeMirror 6 做笔记编辑器原型。
3. **两周内**：接入 Spring Boot + SQLite，完成 Node CRUD、标签、搜索。
4. **一个月内**：用 Vue Flow 做画布投影，完成 M2 + M3。
5. **内测后**：根据用户反馈决定先做图谱还是先做插件系统。
6. **阶段三**：实现插件系统 + 官方 FSRS 记忆插件。
7. **阶段四**：实现云端存储 + License 付费。
8. **阶段五**：实现 AI 插件 + 云端推理网关。
9. **阶段六**：实现云端同步 + 移动端。

---

**文档结束。**

> thisnode（韧知笔记）· 让知识有韧性。