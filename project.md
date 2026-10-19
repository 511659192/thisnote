# thisnote 项目基线

> 本文是**后续开发的基线文档**：记录本仓库的结构、架构、契约、运行方式与已知问题。
> 结论均来自仓库内代码（含 `文件:行号`）；行号以本次记录时的工作区为准（HEAD `31eed8b` + 后续未提交改动），代码变动后可能漂移。
> 标注「未运行验证」的内容是静态阅读得出的判断，未经实际启动/测试确认。
> **改架构、契约、端口、数据路径或交付方式后，请同步更新本文。**

---

## 1. 项目是什么

thisnote（韧知笔记）是一个**本机运行的个人笔记工具**，形态是「本地 Java 后端（纯 API）+ 浏览器界面（微前端）」。

三个从代码里明确读出的设计决策：

1. **文件是唯一权威，没有数据库。** 笔记数据以本地 md/json 文件为准；平台侧没有任何表
   （`backend/app/src/main/java/com/thisnote/ThisnoteApplication.java:12`；插件说明见 `backend/plugins/notes/src/main/resources/application.yml:4`）。
2. **产物分离。** 后端不端出任何静态资源（`backend/app/src/main/resources/application.yml:37-41` 的 `add-mappings: false`），
   界面由界面侧独立构建、交付、提供服务。
3. **平台不认识业务，业务全在插件里。** 主体按 SBP/PF4J 最佳实践做成纯平台（插件发现/契约校验/启停/健康 + 管理面），
   笔记域本身也是插件（`notes`，`undeactivatable=true`），功能插件各自独立存储
   （`backend/plugins/notes/src/main/java/com/thisnote/ext/notes/NotesRegister.java:33-35`）。

术语口径（`frontend/tools/deliver.mjs:13-20` 明确写死了这套叫法）：

| 术语 | 指什么 |
|---|---|
| 后端 | `backend/`（jar、API、后端交付目录） |
| 界面侧 | `frontend/`（不是「前端」） |
| 界面资源 | 界面侧构建产物（不是「前端产物」） |
| 产物分离 | 界面资源不进 jar，由 `/plugin-assets` 从磁盘端出 |
| 交付 | 后端 `backend/runtime/plugins/`（jar）· 界面侧 `frontend/runtime/plugins/`（界面资源） |

---

## 2. 关键数字与路径（速查）

| 项 | 值 | 出处 |
|---|---|---|
| 后端端口 | `45917`（可用 `THISNOTE_PORT` 覆盖），只监听 `127.0.0.1` | `backend/app/src/main/resources/application.yml:2-4` |
| 界面 dev server | `5173`，`/api` 代理到 45917（`THISNOTE_BACKEND` 可覆盖目标） | `frontend/host/vite.config.ts:63-73` |
| 插件 dev server 端口 | favorite `7111`、memory-cycle `7112`、notes `7113`、baidu-sync `7114`、card-importer `7115`、attachment-importer `7116`、state-reset `7117`、graphify `7118`、tags `7119`、ai `7120` | `frontend/host/src/plugins/devPorts.ts` |
| 后端插件模块数 | 10（notes + ai + 8 个功能插件） | `backend/pom.xml` |
| 数据根目录 | `${thisnote.data-root:${user.home}/.thisnote}` → 笔记根 = `<data-root>/notes` | `backend/plugins/notes/src/main/java/com/thisnote/ext/notes/config/NotesConfig.java:18-20` |
| 插件契约版本 | `0.0.1` | `backend/plugin-api/src/main/java/com/thisnote/plugin/api/lifecycle/PluginRegister.java:27` |
| 后端插件目录（生产） | `D:/projects/thisnote/backend/runtime/plugins`（**写死的绝对路径**） | `backend/app/src/main/resources/application.yml:19` |
| 后端插件目录（dev） | `backend/plugins-dev`（相对工作目录） | `backend/app/src/main/resources/application-dev.yml:22` |
| 界面资源 URL | `/plugin-assets/<pluginId>/plugin.js` 与 `plugin.css` | `frontend/host/src/plugins/loader.ts:120-124,184-199` |
| 后端日志 | `${user.home}/.thisnote/logs/backend.log` | `backend/app/src/main/resources/application.yml:48` |
| Java / Spring Boot | 21 / 3.5.6 | `backend/pom.xml:8-12,36` |
| PF4J / SBP | 3.16.0 / 3.5.27 | `backend/pom.xml:37-38` |
| 前端 | Vue 3.5 / Vite 6.2 / qiankun 3 rc / Pinia 3 / vue-router 4.5 / markdown-it 14 | `frontend/host/package.json:14-30` |

---

## 3. 仓库结构

```
thisnote/
├── AGENTS.md                 工作规范（唯一规则源，.dsh/AGENTS.md 只做转发）
├── project.md                本文：项目基线
├── .agent/mem/lessons.md     项目经验教训（有实际内容；.dsh/mem/lessons.md 为空）
├── backend/                  Maven 反应堆（从 backend/ 构建，仓库根不放 Maven 文件）
│   ├── pom.xml               父 POM：模块、版本、plugin.properties 过滤 profile
│   ├── plugin-api/           插件契约（扩展点 / 事件 / DTO / 异常 / FileHelper）
│   ├── plugins/              10 个插件模块（各产出一个独立 jar）
│   ├── app/                  平台主体（纯 API，无业务、无数据库）
│   ├── runtime/plugins/      后端交付目录：<pluginId>.jar + disabled.txt（不入库）
│   ├── plugins-dev/          dev 模式插件目录（目录联接，不入库）
│   └── tools/dev_plugins.ps1 生成/修复 plugins-dev
├── frontend/                 pnpm 工作区（界面侧）
│   ├── host/                 主应用（qiankun 宿主 + 设置页 + 壳）
│   ├── plugin-api/           界面侧插件契约（**注意：目前未被 git 跟踪，见 §9**）
│   ├── plugins/              10 个微应用源码
│   ├── runtime/plugins/      界面交付目录（不入库）
│   └── tools/deliver.mjs     dist → runtime/plugins 的交付脚本
└── scripts/seed-notes.mjs    压测/造数据脚本（通过 HTTP API 灌 1000 篇笔记）
```

`.gitignore` 刻意忽略：`backend/runtime/plugins/`、`frontend/runtime/plugins/`、`backend/plugins-dev/`、
`frontend/plugins/*/dist/`、`docs/`、`.thisnote/`、各类日志（`.gitignore:18-45`）。

---

## 4. 总体架构

### 4.1 后端运行时

```
                 ┌──────────────────────── backend/app（纯平台）────────────────────────┐
 HTTP 127.0.0.1  │ HealthController  /api/health/get                                  │
   :45917 ──────►│ PluginController  /api/plugins/{get,get-detail,active,reload,       │
                 │                              get-health}                            │
                 │ ApiExceptionHandler（统一 JSON 错误）  ThisnoteConfig（CORS/JSON/闸门）│
                 │ PluginRegistry / PluginStateService / PluginLifecycle               │
                 └───────────────▲───────────────────────────────▲────────────────────┘
                                 │ 契约（编译期唯一可见）           │ SBP/PF4J 装载
                        ┌────────┴────────┐              ┌────────┴─────────────────────┐
                        │   plugin-api    │              │ runtime/plugins/*.jar         │
                        │ PluginRegister  │              │ notes / favorite /            │
                        │ 事件 / DTO / 异常│              │ memory-cycle / baidu-sync /   │
                        │ FileHelper      │              │ card-importer /               │
                        │                 │              │ attachment-importer /         │
                        │                 │              │ state-reset                   │
                        └─────────────────┘              └───────────────────────────────┘
```

- 平台组件扫描显式列举 `com.thisnote.api / config / plugin`，**刻意不含 `com.thisnote.ext`**
  （`ThisnoteApplication.java:21-26`）。
- 每个插件是独立的 Spring Boot 工程，SBP 给它自己的 `ApplicationContext`（`CardChangedEvent.java:10-17` 有实测说明）；
  控制器由 SBP 挂进主 MVC，所以外部 URL 仍是 `/api/**`（`NotesPlugin.java:17-24`）。

### 4.2 界面运行时

```
host（qiankun 主应用，:5173）
 ├─ 安装宿主服务 installHostServices（notify / 格式化 / MarkdownView）
 ├─ waitForBackend → GET /api/health/get
 ├─ GET /api/plugins/get 取启用列表 → loadMicroApp 逐个装载
 │    ├─ 生产：/plugin-assets/<id>/plugin.js（+ plugin.css）
 │    └─ 开发：http://localhost:<devPort>/index.ts（探活失败回退生产产物）
 └─ 共享依赖经 sandbox.modules 注入：vue / vue-router / pinia / @thisnote/plugin-api
```

微应用在 `mount()` 里向注册表登记插槽，`unmount()` 里 `unregisterPlugin(id)` 整体撤销；
`停用/重载`因此是真实的界面生命周期事件，不需要刷新页面（`frontend/host/src/plugins/loader.ts:206-263`）。

---

## 5. 后端

### 5.1 平台主体（`backend/app`）

| 类 | 职责 | 关键点 |
|---|---|---|
| `ThisnoteApplication` | 启动 | `@EnableScheduling`；扫描范围不含插件包 |
| `ThisnoteConfig` | Web 装配 | 宽松 CORS（只监听本机）；`/api/plugins/**` 挂闸门；用 fastjson2 替换 Jackson |
| `PluginRegistry` | PF4J 直通 + 扩展查询 | **PF4J 是唯一来源**；按 `plugin.requires` 用 `VersionManager` 过滤不满足契约的插件 |
| `PluginStateService` | 全局启停/重载/健康/管理视图 | 状态真相在 PF4J，不另存标记；不可停用插件拒绝停用与在线重载（409） |
| `PluginLifecycle` | 启动四阶段 | 发现 → 校验契约 → 激活 → 健康检查；`SmartInitializingSingleton` 时机；结果存 `report()` |
| `PluginController` | 插件管理面 | 非 RESTful：查询 GET + 动作 POST + `@RequestParam`（无路径变量） |
| `PluginEndpointHandlerInterceptor` | 停用闸门 | 停用插件的自有端点返回 409；管理面路径永远放行 |
| `ApiExceptionHandler` | 统一错误 | 插件里抛的契约异常也能被兜住（advice 必须在主体） |
| `HealthController` | 探活 | `/api/health/get`，返回 port/pid/jvm/uptime |

关键行为：

- **契约版本校验**：`plugin.requires` 为空则不校验；不满足的插件被**跳过并 warn**，不炸进程
  （`PluginRegistry.java:72-83`）。
- **启停**：`setActive` 走 PF4J 的 `enable/disable` + 显式 `start/stop`，然后尽力通知插件
  （`PluginStateService.java:65-82`）；停用外置插件后不再向已关闭的容器发通知（`PluginStateService.java:170-180`）。
- **重载**：SBP 重建类加载器与 Spring 子容器；装载失败（`reloadPlugins` 返回 null）抛 409 而非假装成功
  （`PluginStateService.java:93-114`）；`undeactivatable` 插件拒绝在线重载。
- **健康**：停用的外置插件不真的去问（容器已关闭），直接给 `unknown`，避免一条健康检查把整个插件列表打挂
  （`PluginStateService.java:142-161`）。
- **管理视图**：`id/title/description/undeactivatable/dependencies/version/active/health/diskVersion/reloadAvailable`，
  `diskVersion ≠ version` 即表示「换了 jar 还没重载」（`PluginStateService.java:194-210`）。

### 5.2 插件契约 `backend/plugin-api`

| 内容 | 说明 |
|---|---|
| `lifecycle/PluginRegister` | 运行时 PF4J 扩展点：仅包含 `health/onGlobalStateChanged` |
| `lifecycle/PluginHealth` | `ok / degraded / error / unknown` + 一句话；**刻意不发起网络请求** |
| `util/FileHelper` | 插件所有持久文件写/删/移的统一入口：写盘后按操作发事件；同一事件 5 秒窗口内去重后再投递（`FileHelper.java:37,121-138`）；另含 `readMapIndex`/`readMapOrEmpty` 两个 JSON 索引读取原语（缺失/损坏语义不同） |
| `util/MainContainerListeners` | 插件跨容器监听器统一注册口：`add`/`onPayload`（按 payload 类型分发）/`onStateReset` 注册、`removeAll` 在插件 stop 时统一摘除 |
| `event/PluginEvent` | 所有跨插件事件的强类型标记，不使用 `String + Map` 的通用事件替代具体载荷 |
| `event/PluginNotification` / `PluginCommand` / `PluginControl` | 单向通知、无返回值命令、平台控制信号的类型分类 |
| `event/PluginQuery<R>` | 带 `CompletableFuture<R>` 返回值的查询/请求契约 |
| `event/CardChangedEvent` | `CREATED/UPDATED/DELETED/REQUEST`；`REQUEST` 携带 `CardCreateRequest`（导入卡片用），其中 `meta.w/h` 作为卡片初始尺寸 |
| `event/NoteChangedEvent` | `CREATED/TITLE_CHANGED/DELETED`（2026-10-18 删除无引用的 `REQUEST` 分支与 `NoteCreateRequest` 载荷） |
| `event/NoteTextChangedEvent` | 标题或卡片正文变化，Tags 据此只刷新受影响笔记的标签出现次数 |
| `event/AiRuntimeEvent` | Graphify 向 AI 核心插件查询当前 Provider、模型与 API key；运行时通过事件传递，不读取配置文件 |
| `event/NoteTagSuggestionEvent` | 可选标签建议契约：请求方发起 `noteId + limit`，语义提供者完成 `CompletableFuture<List<String>>`；双方不共享存储 |
| `event/BookChangedEvent` | `CREATED/UPDATED/DELETED` + `movedNoteIds` |
| `event/CardQueryEvent` | `CompletableFuture<List<CardDTO>>`：发布方等结果，notes 侧返回无主卡片 |
| `event/AttachmentQueryEvent` | `CompletableFuture<List<AttachmentDTO>>`：未被管理的附件（attachments 目录里、没有任何卡片引用），notes 侧监听填充 |
| `event/AttachmentAdoptEvent` | `CompletableFuture<AttachmentDTO>` + `fileName`：接管一个未被管理的附件（改名为 `<uuid>.<ext>`、原名记入元数据），notes 侧监听填充 |
| `event/AttachmentChangedEvent` | Notes 发布附件创建、更新、删除、重命名；Graphify 只消费此事件，不解析附件目录 |
| `event/PluginStateResetEvent` | 状态重置（`sourcePluginId` + 活动插件 future 表）：各插件完成清理并回报 `PluginResetResult` |
| `event/PluginStateResetReport` | 全部已安装插件的重置汇总；状态为 `RESET / SKIPPED / FAILED` |
| `event/FileChangeEvent` | `CREATED/UPDATED/DELETED/RENAME`，绝对路径，重命名带 `previousUrl` |
| `event/MemoryCycleStateChangedEvent` | `cardId + enabled` |
| `exception/*` | `NotFoundException(404) / ConflictException(409) / CardLimitException(409 + limit)`；放契约里是因为要跨容器被主体 advice 捕获 |
| `notes/dto/*` | `NoteDTO`、`CardDTO`、`CardMetaDTO`、`BookDTO`、`AttachmentDTO(fileName,size,width,height)` |

契约包的依赖刻意压到最小（pf4j / spring-context / spring-web / fastjson2 / hutool / guava / lombok，
`backend/plugin-api/pom.xml:22-60`），不把实现技术栈强加给插件作者。

**跨插件事件的硬约定（实测得出，不是风格偏好）**：Spring 事件只在发布它的容器里广播 ——
发布方必须拿主容器发（`SpringBootPlugin#getMainApplicationContext()`），订阅方也必须把 listener 注册到主容器；
插件 `stop()` 时必须把 listener 摘掉，否则泄漏且重复注册（`CardChangedEvent.java:10-21`，
`MemoryCyclePlugin.java:57-95`）。

### 5.3 插件清单

| id | 角色 | 不可停用 | `plugin.requires` | 依赖插件 | 自有存储 |
|---|---|---|---|---|---|
| `notes` | 核心业务域（笔记/卡片/搜索/附件） | 是 | `>=0.0.1` | 无 | `<data-root>/notes/**` |
| `ai` | 统一 AI Provider、模型、凭据与文本/视觉调用 | 是 | `>=0.0.1` | `notes` | `notes/plugins/ai/{settings.json,.credentials.json}` |
| `favorite` | 笔记收藏（★ + 列表筛选） | 否 | `>=0.0.1` | 无 | `notes/plugins/favorite/.favorites.properties` |
| `memory-cycle` | 间隔重复（Anki 式） | 否 | `>=0.0.1` | 无 | `notes/plugins/memory-cycle/.scheduling.json`（排期）、`.schedule-history.json`（复习历史） |
| `baidu-sync` | 平台级百度网盘备份适配器 | 否 | `>=0.0.1` | 无 | `notes/plugins/baidu-sync/{.credentials,.sync-state}.json` |
| `card-importer` | 定时把「无主卡片」导入成笔记 | 否 | `>=0.0.1` | `notes` | 无（只通过事件协作） |
| `attachment-importer` | 定时把未被引用的图片附件导入成笔记 | 否 | `>=0.0.1` | `notes` | 无（每轮按引用关系判断） |
| `state-reset` | 重置全部已安装插件并汇总结果 | 否 | `>=0.0.1` | 无 | 无（平台控制插件） |
| `graphify` | Graphify 知识图谱与社区自动标签 | 否 | `>=0.0.1` | `ai,notes` | `notes/plugins/graphify/`（CLI 设置、索引映射与投影语料） |
| `tags` | 独立笔记标签、标签页与标签筛选 | 否 | `>=0.0.1` | `ai,notes` | `notes/plugins/tags/tags.json` |

出处：各模块 `src/main/resources/plugin.properties`。

每个插件 jar 还携带 `META-INF/thisnote/plugin.json`，平台在**不启动插件容器**时读取它：
`title/description/undeactivatable` 是管理面的权威值，`resetTargets` 供停用插件状态重置，
`backupEnabled/backupExclude` 供百度备份适配器限定同步范围。`PluginRegister` 只负责运行时健康与回调。

### 5.4 装载、启停、交付

- **生产（默认 profile）**：插件从 `backend/runtime/plugins/*.jar` 装载；`plugin.properties` 的
  `plugin.version=@project.version@` 由 Maven 过滤注入（父 POM 的 `plugins` profile，`backend/pom.xml:144-176`），
  **版本单源**，jar 契约测试会断言它等于 Maven 构建版本。
- **开发（`--spring.profiles.active=dev`）**：`runtime-mode: DEVELOPMENT`，插件从 `backend/plugins-dev/<id>/`
  直接加载；`classes` 是指向模块 `target/classes` 的目录联接（`backend/tools/dev_plugins.ps1:1-53`）。
  改插件代码 → Build → 管理页「重载」即生效，不用重新打包。
- **jar 交付**：每个插件模块的 `maven-antrun-plugin` 在 `package` 阶段把自己的 jar 拷到
  `backend/runtime/plugins/<plugin.id>.jar`（`backend/plugins/memory-cycle/pom.xml:86-105`）。
  注意 `app/pom.xml:93-99` 的注释仍写着「在 app 里拷插件」，与实际实现不符（见 §9）。
- **PF4J 停用名单**：当前 `backend/runtime/plugins/disabled.txt` 仅包含 `baidu-sync`。

### 5.5 停用闸门（「停用」是不是装饰的关键）

`PluginEndpointHandlerInterceptor`（`PluginEndpointHandlerInterceptor.java:33-75`）：

1. 只处理 `/api/plugins/` 前缀；未注册的插件 id 直接放行（交给控制器回「未知插件」）。
2. 管理面段（`get/get-detail/get-health/active/reload`，白名单在 `PluginController.MANAGEMENT_SEGMENTS`）永远放行。
3. 不可停用插件永远放行（双保险）。
4. 其余请求在插件停用时返回 **409 + JSON**（`{"status":409,...,"message":"插件「X」已停用…"}`）。

### 5.6 HTTP 约定与错误映射

- **URL 形态**：纯查询用 GET 且保留动作名（`/get`、`/get-detail`）；有副作用用 POST + 动作路径
  （`/create`、`/update`、`/delete`、`/update-content`）；**不使用路径变量**，标识一律 `@RequestParam`
  （`PluginController.java:14-22`，端点形态亦同 `.agent/mem/lessons.md:15`）。
- **JSON**：全后端统一 fastjson2；`extendMessageConverters` 把 Jackson 转换器摘掉，并把 fastjson2 插到链首
  （`ThisnoteConfig.java:62-74`）。两个与旧 Jackson 行为对齐的点：
  - 关掉默认的字段名字母序（`JSONFactory.setDefaultWriterAlphabetic(false)`）；
  - `WriteNonStringKeyAsString`：`gradePreview` 那种 `Map<Integer,Long>` 不能写成 `{1:…}` 裸键（非法 JSON）。
- **错误映射**（`ApiExceptionHandler.java`）：

| 异常 | 状态码 | 额外字段 |
|---|---|---|
| `NotFoundException` | 404 | — |
| `ConflictException` | 409 | — |
| `CardLimitException` | 409 | `error=BLOCK_LIMIT`、`limit` |
| `IllegalArgumentException` / `ServletRequestBindingException` | 400 | — |
| `IllegalStateException` | 409 | — |
| `SecurityException` | 403 | — |
| `NoResourceFound/NoHandlerFound` | 404（`"接口不存在"`） | — |
| `HttpRequestMethodNotSupported` | 405 | — |
| 其它 `Exception` | 500 | — |

### 5.7 数据模型与磁盘布局

```
<data-root>/notes/
├── cards/                      卡片（Markdown，一张一个文件，只含正文）
│   └── <标题>_<n>.md
├── book/.books.json            分组台账（数组）
├── attachments/                <名称>.<png|jpg|webp>（上传生成 uuid；手工放入的其它名字也支持）
└── plugins/
    ├── notes/.note-metadata.json   noteId → NotePO（扁平 map）
    ├── notes/.card-metadata.json   cardId → meta（扁平 map；meta.fileName 即文件名）
    ├── notes/.attachment-metadata.json  fileName → {attachmentId,fileName,originalName,cardId,noteId,w,h,size,contentType,uploadedAt}
    ├── ai/settings.json / .credentials.json   Provider、模型、标签文本上限与 API key
    ├── graphify/
    │   ├── settings.json / index-state.json
    │   └── corpus/<noteId>/note.md + attachments/；corpus/graphify-out/{graph.json,graph.html,...}
    ├── tags/tags.json            noteId → { tags, occurrences }（独立插件存储）
    └── <pluginId>/              其它插件自己的数据
```

- **既有插件持久化文件多使用隐藏命名**：notes 的 `.note-metadata.json`、
  `.card-metadata.json`、`.attachment-metadata.json`，favorite 的 `.favorites.properties`，
  memory-cycle 的 `.scheduling.json` / `.schedule-history.json`，baidu-sync 的 `.credentials.json` /
  `.sync-state.json`，notes 分组台账 `book/.books.json`。AI 插件使用 `ai/settings.json` 与
  `.credentials.json`；Graphify 只保留 CLI 设置和索引状态；标签插件单独使用 `tags/tags.json`。
  卡片 md 与附件等内容文件保持原命名。
- **空 notes 索引必须保留为空 JSON**：`.note-metadata.json`、`.card-metadata.json`、
  `.attachment-metadata.json` 缺失会被 `FileHelper.readMapIndex` 判定为损坏，手工清库后应写回 `{}`。
- **卡片文件格式**：md 只存正文，没有首行元数据注释；元数据（几何、归属…）在
  `notes/plugins/notes/.card-metadata.json` 的 `cardId → meta` 里，`CardMeta` 字段不变
  （`CardPO.java:50-70`），文件名就是 `meta.sourceFileName`。几何下限 `MIN_W=240 / MIN_H=88`（`CardRepository.java:27-28`）。
- **笔记与卡片都是 ID → 数据**：笔记元数据（title/bookId/cardIds/updatedAt）在
  `notes/plugins/notes/.note-metadata.json` 的 map 里（`NoteRepository.java`），卡片元数据在
  `.card-metadata.json` 里；两者都不依赖物理文件名，标题改名只改数据（卡片文件仍按标题重命名）。
- **笔记列表顺序**：按 `NotePO.updatedAt` 倒序（原来用 meta 文件 mtime）；创建/更新时刷新。
- **旧格式完全不兼容**：`notes/meta/*.json`、`.note-file-index.json`、`.card-file-index.json` 都不读取、不迁移；
  旧卡片 md 首行的 `<!-- thisnode:meta … -->` 也不再剥离，按普通正文处理。
- **几何更新只写索引**：拖动/缩放卡片不再改写 md，md 的 mtime 只在正文变化时更新；代价是每次
  几何提交都会重写整份 `.card-metadata.json`（卡片数量大时写入量随之增长）。
- **md 只是正文**：外部把卡片 md 改名后，索引按文件名找不到它 → 该卡片按「无主卡片」处理
  （元数据仍留在索引里成为孤儿条目）。
- **笔记必须有归属分组**：默认分组固定 `grp-default` / 标题「收集箱」（`BookPO.java:32-33`）；
  删除分组时把其笔记改归默认分组（`BookService.java:68-91`）；默认分组不可删。
- **改名联动**：笔记改标题只更新 map 数据，并重命名它的全部卡片文件、发布事件
  （`NoteService.java:61-79`、`CardRepository.java:159`）；标题重复（忽略大小写）直接 400。
- **内容保存的并发基线**：`update-content` 带 `baseContentMd`，磁盘上已不是这一份就回 409，
  **磁盘优先**，不覆盖外部编辑器（Obsidian/VS Code）的改动（`CardService.java:91-118`；
  前端处理见 `frontend/plugins/notes/src/stores/notePage.ts:388-423`）。
- **附件**：只接受 png/jpg/webp（扩展名大小写不敏感）；文件名不含路径分隔符，可含空格/括号
  （上传生成 UUID，手工放入的其它名字也能被识别；删除只发生在「全库索引里没有其它卡片引用」时，`AttachmentService.java`）。
- **附件元数据**：上传时、以及导入器「接管」未管理附件时（改名 + 记 `originalName`），把宽高、字节数、
  MIME、时间写进 `notes/plugins/notes/.attachment-metadata.json`（`AttachmentService.java`）；
  `cardId` / `noteId` 字段已预留，目前没有卡片/笔记上下文，值为 null。附件被删除时同步移除条目；
  `ImageSize.of()` 读不出尺寸时宽高记 0。
  正文引用写法是 `../attachments/<文件名>`，界面渲染时改写成 blob URL 拉取（`frontend/host/src/core/markdown.ts:27-35`、
  `frontend/host/src/components/MarkdownPreview.vue:21-42`）。

### 5.8 端点清单

**平台**

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/health/get` | 探活（界面启动等待也用它） |
| GET | `/api/plugins/get` | 全部插件管理视图 |
| GET | `/api/plugins/get-detail?pluginId=` | 单个插件 |
| POST | `/api/plugins/active?pluginId=&value=` | 全局启停 |
| POST | `/api/plugins/reload?pluginId=` | 重载（换 jar / dev 改代码后） |
| GET | `/api/plugins/get-health?pluginId=` | 健康 |
| * | `/sbp/list`、`/sbp/start`、`/sbp/stop`、`/sbp/reload/{id}`、`/sbp/reload-all` | SBP 官方管理端点，留给运维/脚本 |

**notes**（`NoteController` / `CardController` / `BookController` / `AttachmentController` / `NotesSettingsController`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/notes/get?bookId=&text=&limit=500&offset=0` | 列表；`text` 同时匹配标题与卡片正文 |
| POST | `/api/notes/create` | body `{bookId}`（标题可为空，离开前会提示补） |
| GET | `/api/notes/get-detail?noteId=` | 单篇 meta |
| POST | `/api/notes/update` | body `{noteId, title}` |
| POST | `/api/notes/delete?id=` | 连同卡片与无引用附件一起删 |
| POST | `/api/notes/delete-batch` | body `{noteIds}`，逐篇走单篇删除流程 |
| POST | `/api/notes/move` | body `{noteIds, bookId}`，只改归属分组 |
| GET | `/api/notes/cards/get?noteId=&cardIds=a,b` | 省略 `cardIds` = 整页（**返回数组**） |
| POST | `/api/notes/cards/create` | body 含 `noteId/x/y/w/h/content/color` |
| POST | `/api/notes/cards/update-content` | body `{noteId, cardId, contentMd, baseContentMd}` |
| POST | `/api/notes/cards/batch-update` | body `{noteId, cards:[{cardId,x,y,w,h,color,status,collapsed}]}` |
| POST | `/api/notes/cards/update` | 单卡几何/状态 |
| POST | `/api/notes/cards/delete?noteId=&cardId=` | 删卡（并从笔记清单移除） |
| POST | `/api/notes/attachments/upload` | multipart `file`，返回 `../attachments/<name>` |
| GET | `/api/notes/attachments/get?fileName=` | 读图（`no-cache` + `nosniff`） |
| POST | `/api/notes/attachments/delete?fileName=` | 无引用才删 |
| GET | `/api/notes/settings/get` | 笔记根目录（`rootDir/activeRootDir/restartRequired`） |
| GET | `/api/books/get` | 分组列表（type=book） |
| POST | `/api/books/create` / `/update?id=` / `/delete?id=` | 分组增删改 |

**favorite**：`GET /api/plugins/favorite/get-favorites`、`GET .../get-state?noteId=`、`POST .../update-state?noteId=&value=`、
`POST .../update-state-batch`（body `{noteIds, value}`，按笔记本批量收藏/取消）

**memory-cycle**：`GET .../get-cards?ids=a,b`、`GET .../get-card?cardId=`、`POST .../update-state?cardId=&value=`、
`POST .../update-state-batch`（body `{cardIds, value}`，按笔记本批量开启/关闭）、
`POST .../review?cardId=`（body `{grade:1..4}`）、`GET .../get-due?limit=50`、`GET .../get-due-count`（今日到期数）、
`GET .../get-history?cardId=`、`GET .../get-histories?ids=a,b`（卡片 id → 复习历史）、`POST .../delete-schedule?cardId=`

到期口径：**自然天**。`due` / `get-due` / `get-due-count` 都按「排期落在今天或更早」计算（边界是明天 0 点），
不比较当前时分秒；`schedule.due` 字段同样按自然天给出。

复习历史（`.schedule-history.json`）是「是否到期」的依据：开启记忆曲线时立即写一条 `grade=0`（dueAt=当时）、
每次评分写一条 `grade=1..4`（dueAt=按评分算出的下次时间），前端「今日到期」只读这张表（无历史的新卡片才回退排期）。

**baidu-sync**：`GET .../get-settings`、`POST .../update-settings`、`GET .../get-authorization-url`、
`POST .../exchange-code`、`POST .../update-auto-sync?value=`、`POST .../sync`、`GET .../get-sync-status`、
`GET .../get-diff-preview`

**card-importer**：无端点（只有 `@Scheduled` 任务）。

**attachment-importer**：无端点（只有 `@Scheduled` 任务；设置页是静态说明，不读后端）。

**state-reset**：`POST /api/plugins/state-reset/reset`，返回 `PluginStateResetReport`
（`ok` + 每个插件的 `pluginId/status/message`，见 §5.9）。

**ai**：`GET/POST /api/plugins/ai/settings`、`POST /api/plugins/ai/test`。设置响应包含 `maxTextChars`
与 `keyConfigured`，但不返回 API key。

**graphify**：`GET/POST /api/plugins/graphify/settings`、`GET .../status`、
`POST .../index`、`GET .../graph`、`GET .../graph.html`、`GET .../tags`、`GET .../related`、
`POST .../communities/relabel`。Graphify 设置只写 CLI 路径，Provider 信息来自 AI 插件。

**tags**：`GET /api/plugins/tags/tags`、`POST .../tags/add`、`POST .../tags/remove`、
`POST .../tags/delete`、`POST .../tags/auto`。标签只存于 tags 插件自己的 `tags.json`，不读取 Graphify 或 notes 的数据目录。

### 5.9 各插件业务规则（读代码得到的事实）

**notes**
- 一篇笔记 = 一张页面；卡片是页面的碎片，正文只有一份（`frontend/plugins/notes/src/stores/notePage.ts:45-54`）。
- 打开笔记先取 8 张卡（`PAGE_SIZE=8`），滚到底再取下一批（同文件 `:84,138-161`）；接口支持只按 cardIds 取。
- 删除笔记会先收集卡片引用的附件，删除后再做一次「无引用即删」的兜底（`NoteService.java:81-102`）。
- 卡片删除会发 `CardChangedEvent(DELETED, removedAttachmentFileNames)`，附件清理与排期清理都挂在这条事件上
  （`CardService.java:162-174`；`CardAttachmentCleanupListener.java:20-25`；`CardChangedEventListener.java:22-31`）。

**favorite**
- 只存「哪些 noteId 被收藏」，状态文件是 Java `Properties`（`notes/plugins/favorite/.favorites.properties`）。
- 端点只回 id，不读笔记数据 → 不依赖 notes（`FavoriteController.java:16-20`）。
- 订阅 `NoteChangedEvent(DELETED)` 清理收藏记录（`NoteChangedEventListener.java:17-22`）。

**memory-cycle**
- 状态机：`new → learning → review`，忘记时 `review → relearning`；评级 1..4 分别对应忘记/困难/良好/简单。
- 间隔是 10 档查表（`INTERVAL_DAYS_BY_LEVEL`，最高 60 天，`MemoryCycleService.java:16`），`level` 随成功评分递增。
- 「开关」与「排期」是同一个东西：有排期即开启；关闭即删排期（`MemoryCycleService.java:82-112`）。
- 到期判定：短周期状态（new/learning/relearning）一律算到期；其余按 `dueAt`。
- `gradePreview` 的实现是**在副本上重放 `apply()`**，保证「按钮上写的间隔」与真实调度永不漂移
  （`MemoryCycleService.java:216-228`）。
- 排期 JSON 读不出来时**保留原文件并报错**，不做静默重建（`CardScheduleRepository.java:59-82`）。

**baidu-sync**
- 平台级备份适配器，不再是严格数据闭环插件：只同步 `cards/`、`attachments/`、`plugins/notes/`
  和清单声明 `backupEnabled=true` 的已安装插件目录；未知目录不进入备份范围。
- AI 的 `.credentials.json` 由 `backupExclude` 排除，`baidu-sync` 自身 `backupEnabled=false`；
  本地清单、事件增量同步和远端下载共用同一个 `BackupScope` 判断。
- 远端目录形如 `/apps/<appName>/notes/`；同步范围外的远端历史文件保持不动。
- 双端比较与基线都用 **`size:mtimeSeconds`** 指纹，不用云端 MD5（`.agent/mem/lessons.md:7`；
  `BaiduSyncService.java:531-578`）。
- 双方都改过 → 标冲突，用较新的秒级 mtime 决胜（`BaiduSyncService.java:548-565`）。
- 自动同步：`@Scheduled(fixedDelay=300_000, initialDelay=300_000)`，仅当 `autoSync=true`（`:189-195`）。
- 文件变更事件驱动增量同步，且**忽略自己插件发出的事件**避免自激（`FileChangeEventListener.java:25-36`）。
- OAuth：`redirect_uri=oob`（授权码手动粘贴），token 过期前 60 秒自动 refresh（`BaiduOAuthClient.java:32-65`）。
- 同步进行中请求预览会被拒绝（`ensureSyncIdle`）；路径越界、符号链接一律拒绝（`:617-638`）。

**card-importer**
- 每 10 秒查询无主卡片（cards 目录里、卡片索引中没有的 md）；对每张发
  `CardChangedEvent.requestCardCreate`，标题取文件名去掉 `.md`，落到默认分组（`CardImporterService.java`）。
- notes 侧 `CardCreateRequestListener` 收到后**新建笔记 + 以固定文件名建卡**，并沿用 `CardDTO.meta` 里的 `w/h` 作为初始尺寸（`CardCreateRequestListener.java:23-35`）。
- 它通过事件查询卡片，不直接读 notes 目录（`.agent/mem/lessons.md:29`；`CardQueryEventListener.java:20-27`）。

**attachment-importer**
- 候选 = notes 通过 `AttachmentQueryEvent` 返回的未被管理附件（`AttachmentService.unmanagedAttachments()`）；
  不保存导入状态，是否已导入由「有没有卡片引用它」判断（`AttachmentImporterService.java`）。
- 每个候选先经 `AttachmentAdoptEvent` 交给 notes：改名为 `<uuid>.<ext>`、原始文件名记入附件元数据；
  `AttachmentDTO` 同时返回宽高，本插件不再读取附件文件，直接按像素尺寸建卡。
- 通过 `CardChangedEvent.requestCardCreate` 交给 notes 建笔记/卡片，归入 `grp-default`；本插件不写笔记数据。
- 卡片初始框体按图片像素尺寸 1:1 生成：宽 = 图片宽 + 30（下限 240），高 = 图片高 + 44（下限 88），不设宽度上限；
  png/jpg 用 ImageIO、WebP 解析 RIFF 头；都读不到时用 notes 默认的 720×260。界面侧打开时仍按视口高度封顶（`NotePage.onFitHeight`）。
- 未被管理的附件由 notes 回答（`AttachmentQueryEvent` → `AttachmentService.unmanagedAttachments()`），本插件不扫描附件目录、不解析卡片内容。

**state-reset**
- `POST /api/plugins/state-reset/reset` 覆盖**全部已安装插件**。活动插件通过 `PluginStateResetEvent`
  执行自身清理并返回 future；state-reset 汇总结果，Graphify 异步清理完成后请求才结束。
- 停用插件不启动容器，按 `plugin.json.resetTargets` 删除自己声明的状态文件。目标必须是插件私有目录内的
  相对路径，拒绝绝对路径、`..` 与符号链接逃逸。
- 有状态文件的活动插件重写为初始内容：favorite、memory-cycle、tags、baidu-sync、notes 与 Graphify
  分别走自己的 reset；baidu 同步进行中时该插件返回 `FAILED`，不伪报成功。
- AI 的 Provider、模型、API key，Graphify 的 `settings.json`，百度授权凭据和 Notes 卡片/附件文件均保留。
- attachment-importer / card-importer / state-reset 无状态文件，返回 `SKIPPED`。

**graphify**
- 首次索引由用户在界面手动启动；后续笔记、卡片与引用图片变更进入 2 秒防抖的单线程队列。Graphify CLI 从 thisnote 的 `PATH` 查找，也可在设置中指定路径。
- Graphify 硬依赖不可停用的 `ai` 与 `notes` 插件；通过 `AiRuntimeEvent` 获取 Provider、模型与 API key，
  通过 `AttachmentChangedEvent` 失效附件相关笔记，本身不保存凭据。
- 所有笔记被删除时不调用 Graphify 抽取；插件递归清除整个 `corpus/` 生成内容（包括日期目录中的历史 graph、manifest、报告和缓存），并将索引状态置为空语料，避免缩图保护把正常清空误判成失败。
- thisnote 启动 Graphify 子进程时设置 `GRAPHIFY_NO_AUTO_REFRESH=1`，避免后台调用改写用户目录里的 Agent skill 副本。
- Graphify headless API 后端依赖 CLI 可选 extra：OpenAI 与 OpenCode Go 使用 `openai` extra，Claude 使用 `anthropic` extra；缺少对应包时无法验证 provider。
- 通过 Notes API 分页读取笔记与卡片，将标题、卡片正文及引用图片投影到 `notes/plugins/graphify/corpus/`；不直接扫描 Notes 插件目录。Graphify `extract` 用语义缓存，首次完成社区命名；重新抽取可手动强制，社区重新命名需显式触发 `label`。
- `graph.json` 的 `source_file` 首级目录用于把节点 `label` 投影为只读概念标签，把 `community_name` 投影为只读社区标签；纯哈希、`paste-<id>` 附件名及 `Paste note` / `Image attachment` 等机械占位名不作为标签。`index-state.json` 保存卡片和附件到笔记的事件映射。
- 笔记页「相关笔记」只从 `graph.json` 派生，不修改索引数据：跨笔记关系、共同概念（低频概念加权）和同社区共同计分，返回最多 5 条并附中文关联理由。
- API key 只保存在 AI 插件的 `.credentials.json`，不由 API 返回；Graphify 通过事件取得运行时凭据，OpenCode Go 子进程使用短期本机代理 token。
  文件未加密；百度备份清单会排除该凭据文件。提供商接收参与索引的笔记文字与图片；“测试连接”只提交合成文本。
- 模型供应商为 OpenCode Go（默认 `glm-5.3-flash`）、Claude 与 OpenAI；独立 Kimi/Gemini 供应商已移除。OpenCode Go 使用本机回环转发器补充会话 ID 与客户端标识，并通过 Chat Completions 接口调用模型；旧设置不自动迁移，现存凭据不会因移除选项而自动删除。
- 图片超过 5 MiB 时仍作为引用进入语料，但 Graphify 不会将其像素发送给模型，界面状态会显示告警。图谱页提供 `graph.json` 与 Graphify `graph.html`；该 HTML 从 unpkg 加载 `vis-network`，需要网络访问。

**ai**
- AI 是不可停用的基础能力插件，硬依赖 `notes`，独占 Provider、模型与 API key 配置；
  Graphify 与 tags 都在 `plugin.dependencies` 中声明依赖 `ai,notes`。
- Provider 支持 OpenCode Go、Claude 与 OpenAI；自动标签请求会读取笔记正文和图片附件并直接调用当前 Provider，失败时 tags 回退到本地文本抽取。
- 标签抽取文本上限由 `settings.json` 的 `maxTextChars` 控制，默认 `1,000,000` 字符，允许 `1,000..1,000,000`；它是字符数，不是 token 数。
- Graphify 通过 `AiRuntimeEvent` 取得运行时配置；AI 插件不读写 Graphify 或 tags 的业务数据。

**tags**
- 标签是独立插件的业务状态，存储在 `notes/plugins/tags/tags.json`；插件不读取 Graphify、notes 或其他插件的数据目录。
- `tags.json` 保存每篇笔记的 `tags` 与该笔记内各标签的 `occurrences`。
  `/tags/usage` 直接聚合持久化计数，不再每次读取全部笔记正文。
- Notes 发布 `NoteTextChangedEvent` 后，tags 将对应笔记标记为待刷新，并由单线程防抖任务重算该笔记，
  不阻塞卡片保存请求。
- tags 硬依赖 `ai,notes`；AI 自动标签通过 `NoteTagSuggestionEvent` 请求文本与视觉概念，标签落盘仍由 tags 自己完成。
- 自动抽取通过 Notes API 读取卡片正文，从标题层级、强调项和短首行中筛选高层标签；候选足够时补齐到 20 个，过滤文件路径、代码符号和通用标题，不调用模型。
- AI 候选通过 `NoteTagSuggestionEvent` 获取，再与本地文本候选合并；AI 未配置或请求失败时直接使用本地结果。标签仍只写入 tags 插件自己的 `tags.json`。
- 打开笔记时若标签少于 10 个，标签工具自动执行一次抽取；标签不足时仍保留可见的自动抽取按钮，供用户手动重试。
- 笔记页标题下方显示标签工具；点击 `#标签` 会通过宿主列表筛选回到 `/notes`。标签页按标签文本在全部笔记标题与正文中的实际出现次数排行，显示出现次数与覆盖笔记数，并以气泡图展示 Top 60，可从全部笔记中移除某个标签。
- 笔记删除或状态重置由主容器事件驱动；插件停用后标签工具、标签页和筛选入口一起下线，数据保留。

---

## 6. 界面侧

### 6.1 工作区

`frontend/pnpm-workspace.yaml`：`host`、`plugin-api`、`plugins/{favorite,baidu-sync,card-importer,attachment-importer,state-reset,memory-cycle,notes,graphify,tags,ai}`。

| 包 | 作用 |
|---|---|
| `frontend/host` (`thisnote-frontend`) | 主应用：壳、设置页、qiankun 装载器、Markdown 渲染与格式化服务 |
| `frontend/plugin-api` (`@thisnote/plugin-api`) | **界面侧唯一契约**：注册表、可用性桥、插件管理客户端、HTTP、宿主服务、shell 状态、vite 共享依赖插件；2026-10-18 起另含跨插件共享 UI（`ConfirmDialog`、`createPreviewImageInteractions`、`createPluginLifecycle`）—— 源码形态进宿主模块图，样式随宿主 CSS 全局生效 |
| `frontend/plugins/*` | 各自的微应用；产物 `plugin.js` + `plugin.css` + 分片 |

### 6.2 主应用启动顺序（很重要，深链接靠它）

`frontend/host/src/main.ts:16-48`：

1. 记下 `window.location.hash`（插件路由还没登记，深链接会被 catch-all 重定向）。
2. `createApp` → Pinia → Router → `installHostServices`。
3. `await waitForBackend(20000)`。
4. `await loadActivePluginFrontends(router)`（按后端 active 列表装载，**在 isReady 之前**）。
5. `await router.isReady()`，再把初始 hash `replace` 回去。
6. `app.mount('#app')`。

宿主路由表只有 `/`（Shell）→ `''`(ShellHome) 与 `settings`，加一条 catch-all 兜底；业务路径全部由插件登记
（`frontend/host/src/router/index.ts:11-27`）。`ShellHome` 在插件路由就绪后自动接管并保留 `fullPath`
（`ShellHome.vue:13-19`）。

### 6.3 微应用装载器（`frontend/host/src/plugins/loader.ts`）

- **入口**：运行时生成 blob HTML，里面只有一个绝对地址的 module script（`:96-99`）。
- **共享模块**：宿主把 `vue / vue-router / pinia / @thisnote/plugin-api` 的**模块命名空间**注入
  `sandbox.modules`（`:40-61`）。开发期还有 `resolveHook` 把 `@thisnote/plugin-api/vite` 的哨兵路径
  （`/@qk-shared/<name>`）映射回裸名（`:69-86`；契约见 `frontend/plugin-api/vite.ts:17-20`）。
  这是为了避免「两个 Vue / 两个注册表」。
- **容器**：每个插件一个独立容器 `#plugin-microapp-<id>`，放在 `#plugin-microapps` 下
  （qiankun 容器闸门是排他的，共用会互相等待，`:153-177`）。
- **样式**：生产按需插 `<link data-plugin-css>`，404 就不插（`:184-199`）。
- **装载/卸载/重载**：`loadPluginFrontend` / `unloadPluginFrontend` / `reloadPluginFrontend`；
  卸载时先拆宿主侧插件路由再 `unmount()`（`:206-263`）。
- **路由挂载**：`syncPluginRoutes` 把注册表里的插件路由 `addRoute('shell', …)`（`:285-296`）。
- **开发兜底**：插件 dev server 不可达时回退 `/plugin-assets/<id>/plugin.js`；该路径在开发期由宿主 Vite 的
  中间件从 `frontend/runtime/plugins/` 端出（`frontend/host/vite.config.ts:12-42`）。

### 6.4 插槽与可用性

`frontend/plugin-api/src/registry.ts:14-24` 共有 **11 个响应式注册表**：

| 插槽 | 注册函数 | 消费方 |
|---|---|---|
| 卡片工具 | `registerCardPlugin` | `NoteCard`（`activeCardPlugins()`） |
| 笔记工具 | `registerNoteTool` | `NoteView`（`activeNoteTools()`，如 ★；`placement: 'below-title'` 的组件渲染到标题下方） |
| 设置面板 | `registerPluginSettings` | 设置页「插件管理」展开区 |
| 侧栏入口 | `registerNavEntry` | notes 侧栏（`activeNavEntries()`，带徽标） |
| 插件路由 | `registerPluginRoute` | `loader.syncPluginRoutes` |
| 列表筛选 | `registerNoteListFilter` | 笔记列表（`activeNoteListFilters()`） |
| 侧栏主面板 | `registerSidebarPanel` | `Shell`（notes 的列表面板） |
| 列表头 | `registerListHeader` | `NoteList`（标题搜索框） |
| 顶栏搜索 | `registerTopbarSearch` | `Shell`（全局搜索） |
| 列表行内动作 | `registerNoteRowAction` | `NoteList`（`activeNoteRowActions()`，如 ☆/🧠） |
| 列表批量动作 | `registerNoteBatchAction` | `NoteList`（`activeNoteBatchActions()`，如「收藏 +/−」「记忆 +/−」） |

- 行内动作 / 批量动作都使用 `NoteRef`（`{noteId,title,cardIds}`）：状态的读写、`loadState`、`isActive`、`toggle` 全在插件里，
  核心只渲染注册表并把所选笔记递进去（favorite、memory-cycle 各在自己的 `src/actions.ts`）。
- 笔记工具默认渲染在标题右侧；插件可声明 `placement: 'below-title'`，宿主会把它放进标题下方的独立区域，组件仍只接收 `noteId`；
  同一位置的多个工具可用可选 `order` 稳定排序。

- **插件状态变化事件**（`pluginState.ts`）：插件改完自己的数据（收藏、记忆曲线…）调
  `emitPluginStateChange({pluginId, noteIds?, cardIds?})`；核心侧栏订阅后强制刷新徽标，
  笔记页订阅后重新装载当前卡片的插件状态。核心不认识任何具体插件，插件也不知道谁在听。

- 共享指针 `activeListFilter`：插件 UI（搜索框、侧栏筛选行）设它，notes 域 `watch` 它并调用筛选的 `load()`
  取命中 id（`registry.ts:31-41`；`stores/notes.ts:59-92`）。
- **可用性桥**（`availability.ts`）：把后端 `active` 拉成响应式 `inactive` 集；`active*()` 系列据此过滤；
  30 秒新鲜期 + 在飞合并，管理页启停后 `force` 刷新（`:16-77`）。
- **徽标**：`navBadges()` 3 秒缓存 + 在飞合并；侧栏在进应用/切路由/窗口聚焦时刷新，且不给当前所在插件页取徽标
  （`registry.ts:130-157`；`NotesSidebarPanel.vue:52-98`）。

### 6.5 宿主服务、壳状态与 HTTP

- `host()`：`notify / formatBytes / formatRelative / MarkdownView`，由核心在启动时 `installHostServices` 注入；
  插件不得 `import '@/…'`（`frontend/plugin-api/src/host.ts:11-35`；有测试守着，见 §8）。
- `shellState`：主题、toast 队列、侧栏收起；跨微应用共享的模块级 reactive 单例（不是 Pinia），
  持久化键 `thisnote.theme` / `thisnote.sidebar`（`shell-state.ts:21-57`）。宿主 `useUiStore` 只是它的 Pinia 代理。
- HTTP：`apiBase` 解析顺序 = 桌面壳注入 `__THISNOTE_API__` → `localStorage['thisnote.apiBase']` →
  同源（5173/无端口）→ `http://127.0.0.1:45917`（`http.ts:12-26`）；
  `waitForBackend()` 并发去重，供「重试连接」复用（`:119-147`）。

### 6.6 notes 域界面（最复杂的部分）

- **路由**：`/notes`（列表 + 空页面）与 `/notes/:id`（笔记页），都由 notes 微应用登记（`frontend/plugins/notes/index.ts:57-64`）。
- **侧栏面板**：新建分组、全部笔记、分组树（右键改名/删除）、插件筛选行、插件入口行（`NotesSidebarPanel.vue`）。
- **列表**：296px 宽；单击延迟 250ms 打开（让双击能进改名），双击标题改名，悬停出现 ×，行内显示归属分组 chip
  （`NoteList.vue:41-123,161-208`）。
- **页面（`NotePage.vue`）**：单列向下生长的卡片页面，不是白板 —— 双击空白新建卡、Ctrl+V 把剪贴板变成卡片、
  卡片原地编辑写回笔记、空卡片不会被自动清理（`NotePage.vue:11-22`）。支持框选、拖拽/缩放、网格与吸附、
  自动排版、图片预览缩放（`:37-50,563-737`）。
- **卡片（`NoteCard.vue`）**：没有标题字段，卡片名就是正文里的标题；默认渲染 Markdown，编辑时就地显示源码；
  粘贴图片/ Markdown 文件、按内容自动调高（`NoteCard.vue:12-22,187-320,341-495`）。
- **视口**：按笔记持久化到 `localStorage['thisnote.page.viewport.<noteId>']`，防抖 300ms + 卸载前刷写
  （`notePage.ts:481-524`）；网格/吸附偏好也持久化（`:552-560`）。
- **保存策略**：几何写是乐观的（跟手），手势结束才批量 `batch-update`；失败就 toast + 重新载入（`notePage.ts:237-261`）；
  正文写带基线，409 时提示并重载（`:388-423`）。

### 6.7 其他插件的界面形态

| 插件 | 登记了什么 | 关键文件 |
|---|---|---|
| favorite | 笔记工具 ★ + 列表筛选 `favorites`（`hideNoteManagement: true`），**没有自己的页面** | `frontend/plugins/favorite/index.ts:24-42` |
| memory-cycle | 卡片工具 🧠（`loadState/isEnabled/toggle/cardComponent`）+ 侧栏入口（order 1，徽标=今日到期数）+ `/review` 路由 | `frontend/plugins/memory-cycle/index.ts:16-47` |
| baidu-sync | 设置面板 + 侧栏入口（order 2）+ `/baidu-sync` 路由 | `frontend/plugins/baidu-sync/index.ts:11-28` |
| card-importer | 只有设置面板 | `frontend/plugins/card-importer/index.ts:6-8` |
| attachment-importer | 只有设置面板（静态说明，不读后端） | `frontend/plugins/attachment-importer/index.ts:6-8` |
| state-reset | 只有设置面板（重置按钮 + 二次确认） | `frontend/plugins/state-reset/index.ts:6-8` |
| graphify | 设置面板 + 侧栏入口（order 3）+ `/graphify` 路由（概念/社区按边数分组排行）+ 标题下方相关笔记工具 + 隐藏自动标签筛选 | `frontend/plugins/graphify/index.ts` |
| tags | 侧栏入口（order 4）+ `/tags` 标签管理页 + 标题下方标签工具 + 隐藏标签筛选 | `frontend/plugins/tags/index.ts` |
| ai | 只有设置面板：Provider、模型、API key、连接测试 | `frontend/plugins/ai/index.ts` |
| notes | 侧栏主面板 + 列表头（标题搜索）+ 隐藏筛选 `title` + 顶栏搜索 + 两条路由 | `frontend/plugins/notes/index.ts:35-65` |

复习页（`ReviewView.vue`）：三个页签 —— **今日复习**（到期队列逐张评分）、**今日到期**（最新一条排期在今天或更早（含过期），
或历史上任意一条排期落在今天；默认 3 列，卡片列表不展示历史）、
**开启记忆**（所有开启记忆周期的卡片，默认 5 列，可切 3/5 列）；四档按钮标注「下次出现」；
短周期卡在队列内轮询重现，长周期卡移出视图；键盘 1-4 作用于最上方卡片；预览浮层显示完整复习历史；
查不到正文的排期按孤儿处理并调 `delete-schedule` 清理。复习历史由后端 `.schedule-history.json` 记录（每卡最多 200 条）。

### 6.8 构建与交付

- 插件产物：`vite build`（lib 模式，ES 格式，入口 `plugin.js`，样式 `plugin.css`，外部化共享依赖），
  然后 `node ../../tools/deliver.mjs <id>` 把 `dist/` 同步到 `frontend/runtime/plugins/<id>/`（先清后拷）。
- 主应用产物：`frontend/host/dist/`，由界面侧自行部署（`frontend/host/vite.config.ts:74-88`）。
- 开发：`pnpm dev` 全起；`pnpm dev:host` 只起宿主；`pnpm dev:plugins` 起功能插件（不含 notes）。

---

## 7. 运行与构建（脚本/配置所示，本次未执行）

**后端**

```powershell
cd backend
mvn package                                     # 产出 app/target/thisnote-backend.jar，并把插件 jar 拷到 runtime/plugins/
java -jar app/target/thisnote-backend.jar       # 默认 profile，端口 45917（日志见 backend/app/backend-run.log:20-22）
```

插件开发模式：

```powershell
mvn compile                                     # 先有各模块 target/classes
powershell -File tools\dev_plugins.ps1          # 生成 plugins-dev（目录联接）
java -jar app/target/thisnote-backend.jar --spring.profiles.active=dev
```

**界面侧**

```powershell
cd frontend
pnpm install
pnpm dev                                        # 宿主 5173 + 各插件 dev server（端口见 devPorts.ts）
pnpm --filter thisnote-frontend build           # 仅宿主
pnpm -r build                                   # 各包构建（插件 build 已接 deliver.mjs）
```

**数据**：默认 `%USERPROFILE%\.thisnote\notes`；删库/换库就是换这个目录（不要在应用运行时手工改索引文件）。

---

## 8. 测试与验证现状

| 位置 | 内容 | 运行方式 |
|---|---|---|
| `backend/app/src/test/java/com/thisnote/plugin/PluginJarContractTest.java` | 10 个插件 jar 的契约：描述符、依赖、注册类索引、静态插件清单、版本与产物分离；jar 未构建时 **skip** | `mvn test`（从 `backend/`） |
| `backend/app/src/test/.../PluginMetadataReaderTest.java` | 停用场景所需的静态清单可从开发目录读取，重置与备份字段解析正确 | `mvn test`（从 `backend/`） |
| `backend/plugins/notes/src/test/.../AttachmentServiceTest.java` | 未被管理的附件返回真实像素宽高，附件导入无需读取 Notes 目录 | `mvn test`（从 `backend/`） |
| `backend/plugins/baidu-sync/src/test/.../BackupScopeTest.java` | 备份范围只含声明目录，排除凭据、百度自身和未知插件目录 | `mvn test`（从 `backend/`） |
| `frontend/host/tests/markdown.test.ts` | HTML 转义、标题/列表/表格/任务项、`plainText`/`firstHeading`/`wordCount` | `pnpm --filter thisnote-frontend test` |
| `frontend/host/tests/plugin_boundary.test.ts` | 全部插件不得引用宿主内部路径、跨插件相对路径或其他插件包；同时检查 `package.json` 依赖 | 同上 |
| `frontend/host/tools/*.mjs` | 约 20 个 Playwright/HTTP 手动探针（卡片模式、拖拽、标题改名、插件管理、qiankun 冒烟等） | 手工 `node tools/xxx.mjs`，**未接进 `test` 脚本** |
| `scripts/seed-notes.mjs` | 通过 HTTP API 批量造笔记/卡片 | `node scripts/seed-notes.mjs [n]` |

vitest 配置为 `environment: 'node'`、只收 `tests/**/*.test.ts`（`frontend/host/vitest.config.ts:15-18`），
没有组件/DOM 测试。

---

## 9. 已知不一致、风险与坑（均有代码证据）

> 按「会不会让人白干一天」排序。带「未运行验证」的条目只做了静态阅读。

1. ~~**界面契约包 `frontend/plugin-api/` 没有被 git 跟踪。**~~（已修复：当前 `git ls-files frontend/plugin-api`
   能列出契约包文件，也不再命中忽略规则。）
2. **memory-cycle 复习页的卡片内容接口形态（已修复）。**
   原先前端按 `{ cards: [...] }` 解包，而后端 `/api/notes/cards/get` 返回裸数组 `List<CardDTO>`；
   现在 `memoryCycleApi.ts` 直接按数组使用，复习页不再报 `item.page.cards is not iterable`。
3. ~~顶栏全局搜索的「摘要命中」实际不生效~~（已修复：summary 匹配/摘要片段属不可达死代码，
    2026-10-18 删除，`GlobalSearch.vue` 现为纯标题匹配）。
4. **生产插件目录是写死的绝对路径**：`D:/projects/thisnote/backend/runtime/plugins`
   （`backend/app/src/main/resources/application.yml:19`）。换机器/换目录/换用户名即找不到插件；
   dev profile 用相对路径 `plugins-dev` 才不会踩到（`backend/app/src/main/resources/application-dev.yml:22`）。
5. **引用了不存在的文档**：`backend/app/pom.xml:21,31` 指向 `docs/PLUGIN_PLATFORM.md`，
   `frontend/host/vitest.config.ts:7` 指向 `docs/GAPS.md`；仓库里没有 `docs/`，且 `.gitignore:44` 忽略 `docs/`。
6. ~~**`backend/runtime/plugins/disabled.txt` 仍是旧插件 id `memory-curve`**~~（已清理，当前仅含 `baidu-sync`）。
   PF4J 按 id 停用，这条对现役插件无效，但会误导排查。
7. **memory-cycle README 有两处过时**：文中 `SrsService` 现已改名 `MemoryCycleService`；文中称
   「`CardService.delete` 并未真的 publishEvent」（`frontend/plugins/memory-cycle/README.md:101-104`），
   而实际代码已发布 `CardChangedEvent(DELETED)`（`CardService.java:172`）。
8. **仓库根 `plugin.properties` 是旧副本**（`plugin.id=baidu-sync`、`version=0.1.0`、`requires=>=5.0.0`），
   不在任何 Maven 模块路径下，容易被人当成现役描述符。现役描述符在 `backend/plugins/*/src/main/resources/`。
9. **`backend/package.json` 残留 `vite ^8.3.4` 依赖**（以及同目录的 `pnpm-lock.yaml`）；后端构建不需要 Node。
   属历史遗留，不要在这里加前端依赖。
10. **`app/pom.xml:93-99` 的注释与实现不符**：注释说 app 在 package 阶段拷插件 jar，实际是各插件模块的
    `maven-antrun-plugin` 干的（`plugins/memory-cycle/pom.xml:86-105` 等），app 模块没有该配置。
11. ~~`NotesSidebarPanel.vue:1-7` 有 `<style>` 块之外的 CSS 文本~~（已修复：2026-10-18 删除头部游离 CSS 残片，
     块内同名规则保留）。
12. ~~`registry.ts:9` 注释说「目前有五个插槽」，实际注册表有 9 个~~（已修复：注释改为指向 §6.4 的完整清单，
     注册表现有 11 个）。
13. ~~`MemoryCycleStateChangedEventListener` 有两份实例~~（已修复：2026-10-18 删除插件容器内的 `@Component` 死注册——
    跨插件事件只经主容器广播，插件容器内那份从未收到过事件；notes 的 `CardAttachmentCleanupListener` 同形态同批修复。
    同批把 7 个插件的「listener 注册/摘除」样板与 7 份同构的 `PluginStateResetListener` 下沉为
    `plugin-api` 的 `MainContainerListeners`（`add`/`onPayload`/`onStateReset`/`removeAll`），
    业务 listener 以 lambda 内联进各 Plugin 类；`FileChangeEventListener` 例外保留类形态——
    `BaiduSyncStarter` 的 `@EnableAsync` 使其 `@Async` 代理真实生效，必须保持异步执行）。
14. **历史日志不能当现状依据**：`backend/app/backend-run.log` 与各处 `*.log` 是 2026-10-12 的运行记录，
    里面还是 `memory-curve`、契约版本 `4.0.0`（`backend-run.log:31-37`）。
15. **`notes` 新建笔记没有重名校验**：`NoteRepository.save` 直接用标题写 `<title>.json`
    （`NoteRepository.java:35-44`），重名检查只做在改名路径上（`NoteService.java:67`）。自动导入
    （card-importer、attachment-importer）用文件名作标题，若与已有笔记同名会覆盖那份 meta ——
    旧笔记的 `cardIds` 清单随之丢失。改动导入逻辑前先想清楚标题去重。
16. **Graphify 图谱 HTML 依赖外部 CDN**：`graph.html` 从 `unpkg.com` 加载 `vis-network`；离线或 CDN 不可访问时交互可视化不能使用，但 `graph.json` 仍可读取。

---

## 10. 改代码时的落点清单

### 新增一个后端插件

1. `backend/pom.xml`：`<modules>` 加模块；若需要新的第三方依赖，在 `dependencyManagement` 锁版本。
2. `backend/plugins/<id>/pom.xml`：父 POM、`plugin-api` + `sbp-core` + 所需 Spring 依赖、
   `spring-boot-maven-plugin`（standalone）、`maven-antrun-plugin` 拷 jar（`<plugin.id>` 属性）。
3. `src/main/resources/plugin.properties`：`plugin.id/class/version=@project.version@/requires/dependencies`；
   **不要**把中文说明写进描述符（ISO-8859-1 会乱码）。
   同时提供 `src/main/resources/META-INF/thisnote/plugin.json`，声明显示名、说明、核心保护、
   重置目标与备份范围；平台在插件停用时也直接读取这份 UTF-8 清单。
4. Java：`XxxPlugin extends SpringBootPlugin`、`XxxStarter`（`@SpringBootApplication`，需要定时任务再加
   `@EnableScheduling`）、`XxxRegister implements PluginRegister`（`@Extension`，要注入服务才加 `@Component`）。
5. 在两个 `onPluginBootstrap` / `afterPluginBootstrap` 里：`ctx.registerBean(FileHelper.class, …)`；
   需要跨插件监听就 `new` 一个 listener 注册到 `getMainApplicationContext()`，并在 `stop()` 里摘掉。
6. 端点放 `/api/plugins/<pluginId>/…`；业务异常用 `plugin-api` 的契约异常。
7. 数据路径：`${thisnote.data-root}` → `notes/plugins/<pluginId>/`，不要自造根目录。
8. `backend/tools/dev_plugins.ps1` 的 `$plugins` 表加一行（dev 模式需要）。
9. `PluginJarContractTest` 的 `@CsvSource` 加一行（否则新 jar 没有契约测试覆盖）。

### 新增界面微应用

1. `frontend/plugins/<id>/{package.json,vite.config.ts,index.ts,index.html,src/**}`；
   包名 `@thisnote/plugin-<id>`；`vite.config.ts` 用 `vue() + qiankun() + qiankunSharedDeps()`，
   `build.lib` 入口 `index.ts`、ES、`plugin.js`、`plugin.css`，`external` 四个共享依赖。
2. `frontend/pnpm-workspace.yaml` 加包路径；根 `frontend/package.json` 的 `dev:plugins` 视情况加过滤。
3. `vite.config.ts` 固定端口 + `frontend/host/src/plugins/devPorts.ts` 登记同一端口。
4. `index.ts` 导出 `bootstrap/mount/unmount`；`mount` 里 `register*`，`unmount` 里 `unregisterPlugin(id)`；
   独立运行时（`!window.__POWERED_BY_QIANKUN__`）import 即注册，便于单独调试。
5. 只能 `import '@thisnote/plugin-api'` 与包内相对路径，**不得** `import '@/…'`；需要宿主能力用 `host()`。
6. 插件自己的 API/类型放 `src/api/`，不要塞进 `@thisnote/plugin-api`（底座只认通用管理面）。

### 改动公开契约时

- `PluginRegister.CONTRACT_VERSION` 只有明确要求时才修改，**不随公开类型/扩展点改动自动递增**；
  若确实修改，同步各插件 `plugin.properties` 的 `plugin.requires`。
- 改 DTO 字段名 → 后端控制器/服务、`plugin-api` DTO、**每个前端消费点的属性访问**一起改
  （`.agent/mem/lessons.md:13,32` 记录了两次踩坑）。
- 改端点形态（GET/POST、参数名、返回包裹）→ 同步 `frontend/plugins/*/src/api/*.ts` 与
  `frontend/host/tools/*.mjs` 探针；**静默 catch 会掩盖契约漂移**。

---

## 11. 约定与边界（做设计判断时的默认答案）

1. **最小改动**：只改本次需求必需的代码，不顺手重构、不扩大需求（`AGENTS.md` §3、§4）。
2. **领域模型不出插件**：notes 用 `CardPO`，跨插件只走 `CardDTO/CardMetaDTO`；插件不直接扫别的插件的数据目录
   （`.agent/mem/lessons.md:29`）。
3. **平台不认识具体插件**：核心不硬编码「复习/收藏/百度同步」；一切通过插槽注册（`registry.ts`、`loader.ts`）。
4. **插件状态归插件**：平台只做插件级启停与端点闸门，不存 per-entity 业务状态
   （`PluginStateService.java:12-19`）。
5. **后端纯 API**：不要开 `add-mappings`，不要往 jar 里塞界面资源（会触发 SBP 资源链 500，且违反产物分离，
   契约测试会失败）。
6. **优先复用**：`FileHelper`（文件 + 事件）、既有 `http` 客户端、`host()` 服务、已有事件类型。
7. **需要真实外部连通性时**：不要放进 `PluginHealth`（它每次刷管理页都会被调用），另做一个显式「测试连接」动作
   （`PluginHealth.java:5-9`）。
8. **Controller 只依赖应用 Service**：不得直接注入 Repository、OAuth/Provider Client 或读取数据根路径；
   Repository 只负责持久化，业务规则放在 Service。AI、Graphify、Tags、百度同步和分组管理已按此方向收口。
9. **外部访问单独放 `client` 包**：HTTP、OAuth、AI Provider、Notes API Client、CLI 进程与本地代理适配器
   与 `service` 并列；Service 只编排能力，不直接实现外部协议。

---

## 12. 文档缺口（现状）

- 仓库根没有 `README.md`、也没有 `docs/`（被 `.gitignore` 忽略）；目前只有本文件与
  `frontend/plugins/memory-cycle/README.md`（后者部分过时，见 §9.7）。
- 开发/构建步骤只存在于 POM 注释、脚本注释与日志里；本文 §7 是它们的汇总，**不是**经过本轮验证的命令。
