# Lessons

> 2026-10-12 ~ 10-15 的 23 条记录按主题提炼。每条 = 执行规则 + 依据/来源；同类错误重复 ≥2 次的标记【硬规则】。

## 百度网盘同步
- 【硬规则】云 API 语义以官方文档为准，不套用其他 SDK/接口的枚举含义：上传冲突覆盖用 `rtype=3`，precreate 与 create 都要传（曾误用 rtype=2 致云端出现多个副本）；create 响应的实际路径须与请求目标核对一致；不擅自清理用户云端已有副本。
- 云端列表 MD5 不是真实内容 MD5（纠正：用户指出其不可用于对比）：双端比较与持久化基线用 `size:mtimeSeconds`，旧 MD5 基线视为缺失；下载仅在有有效 `Content-MD5` 时校验摘要，否则校验服务端报告大小；上传分块协议仍单独用本地 MD5。
- "批量"分清两类（纠正：用户指出逐文件调用和本地并发都不等于远端批量上传）：文件管理 API（移动/删除）每次最多 50 项，仅适用于文件管理；上传无远端批量 API（单文件 precreate+分片+create），不按 50 项切组，本地并发仅按用户授权规格实现（8 线程 + 256 容量有界队列），且不得称为批量上传。
- 文件同步在调用线程内执行、文件逐个上传（纠正：用户要求 BaiduSyncService 不使用线程池）：文件同步不使用执行器；远端目录初始化按单独要求异步。用户明确接受全量与事件同步并发，移除 `syncExecutionLock` 和同步入口的 `status.running()` 短路，`status` 仅用于展示；`/sync` 控制器与定时任务直接调 `startSync()`（全目录扫描规划，属全量语义）。
- 文件变更事件不能直接触发全量同步（纠正：用户指出 requestSyncAfterChange 触发的是全量）：消费者保留并传递事件载荷（操作类型 + 当前 URI + 可选旧 URI）；事件增量同步实现前只记录事件并留 TODO，不以全量扫描代替。
- 下载为流式原地覆盖，无临时文件/目录，发布事件前校验字节数与可用的真实 MD5；用户确认接受下载中断时目标文件可能不完整。

## 前后端接口契约
- 【硬规则】契约沿数据流核对：请求体字段须与后端 DTO 一致（曾因前端发 `contentMd` 而后端 `CreateCardRequest` 读 `content`，只建成空白卡片）；UI 命名与 HTTP 字段不同时在调用处显式映射。
- 单值响应直接返回字符串，不包 DTO（纠正：用户两次要求上传接口去掉 `ImageAttachmentDto`、直接返回 `path` 字符串）；改动后检查控制器、服务、前端解析与 DTO 无残余引用。
- 纯查询用 GET/`@GetMapping`（曾误把 list/detail/get 等改为 POST），路径保留动作名；创建/修改/删除等有副作用的操作用 POST 并在路径标明动作。

## 需求范围与实现路径
- 【硬规则】只做用户明确提出的功能点（纠正：用户要求移除未授权的 ID 迁移任务、质疑多余的协调逻辑）：不自行增加存量 ID 改写、数据/状态迁移、别名、兼容层，不新增协调服务/事件；先追踪现有调用链，优先复用已有方法（例：标题改名直接 `NoteRepository.saveRenamed`）。
- 改完检查新增的孤立代码；删除文件前说明影响并取得确认。
- 精简防御分支以可读性为目标（纠正：用户澄清精简不是为减行数）：先核对业务约束（如"配置文件存在则内容始终合规"）与全部调用点，只删证实不可能的分支，保留保护真实数据风险的逻辑。
- 用户纠正（2026-10-17）：薄层委托不需要额外封装，直接调用底层实现。依据：`NoteRepository#writeJson` 仅转调 `FileHelper.fileWritten(Path, Object)`，用户要求删除该封装并将规则记入 `AGENTS.md`。下次仅当方法增加校验、转换或独立语义时保留封装；纯转发直接调用底层方法。
- 纯重启只停启服务，不默认 clean/package/前端构建（纠正：用户要求从流程中移除构建步骤）；仅在用户要求或源码/产物变更时构建（实测 clean 0.5s / package 11s / 前端 5s）。

## 前端 UI
- 【硬规则】输入框"关闭编辑态后卸载引发的 blur"会二次进入 commit 类函数（纠正：用户报告改标题发出两次 /api/notes/update，NoteView.vue commitTitle；同文件 Escape 曾因此误保存）：Enter 提交先置 renaming=false → v-if 卸载 → blur 带同一草稿重入，而首次请求未返回、current.title 仍旧值，"同值跳过"守卫失效。下次凡「Enter 与 blur 共用提交函数且提交会关闭编辑态」的实现，入口必须可重入防护（如 `if (!renaming) return` 或在途标记）；NoteList 的 savingNoteId 是已验证的同类守卫。
- 【硬规则】UI 指代先定位具体组件层，不从截图或旧界面推断，指代不清先确认：页眉（`.rv-head`）、页签导航（`.rv-tabs`）、卡片元数据（`.rv-note-title`）是不同目标（曾删错层）。
- 复习评分预览按用户明确给出的间隔实现（纠正：曾按截图误判为 1 分钟阈值）：忘记=轮询、困难=1 天、良好=2 天、简单=3 天；预览与实际评分复用同一调度规则（`SrsService.previewDue` 复制状态复用 `apply`）。
- 计数徽标的数据须在进入页面时加载，或加载期显示占位，不能等点击页签才出现计数（曾只在页签点击时 `loadEnabledCardList()`）。

## 插件与文件持久化
- 笔记根目录统一由 Spring `thisnode.data-root`（默认 `${user.home}/.thisnode`）派生：笔记根 = `data-root/notes`，插件数据 = `data-root/notes/plugins/<pluginId>`；不以 settings.json 的 `notesRoot` 为准；不保留旧 `data-root/plugins/<pluginId>` 的迁移兼容，不自动搬移/删除已有数据；少调用点的路径拼接直接内联，不留纯封装工具类（`PluginDataPathUtils` 已删）。
- 用户纠正（2026-10-17）：`Card` 领域模型留在 Notes 插件内部，跨插件枚举和申请使用 plugin-api 的 `CardDTO` / `CardMetaDTO`。依据：用户要求将 `Card` 移回原位置，并将 `NotesCardFile` 替换为映射现有卡片结构的 DTO；后续仅在 Notes 内部使用领域 `CardPO`，导入插件通过 Notes API 使用 DTO，不直接扫描 Notes 目录或解析磁盘格式。
- 用户纠正（2026-10-17）：不单独保留 `NoteCreateRequestListener`。依据：`CardCreateRequestListener` 处理卡片创建申请时已调用 `NoteService.create(...)` 创建笔记，再调用 `CardService.create(...)` 创建卡片；用户指出额外的笔记申请监听器没有必要。下次导入对缺少 `CardMeta` 的手动复制文件发送 `CardChangedEvent` 创建申请，由该监听器完成笔记和卡片创建；不要发布无人处理的 `NoteChangedEvent` 创建申请。
- FileHelper 是文件操作与事件发布入口（纠正：用户指出回调式写入不直观）：插件 Spring 上下文注册单例 FileHelper，内部持有事件发布器，调用方不传发布器。重载选择：JSON 对象→`fileWritten(Path, Object)`；文本→String；附件→MultipartFile；百度下载→`(Path, InputStream, modifiedAtMillis)`。异常：`fileDeleted` 捕 IOException 记录后返回 false；`fileWritten`/`fileMoved` 捕获记录后抛 `UncheckedIOException`。
- 跨插件文件变更事件（纠正：用户指出仅监听领域事件无法覆盖插件文件操作）：先盘点所有插件持久化入口（`JsonFileUtils`、`PropertiesFileUtils`、notes 直接写/移/删、附件、百度下载），不能以领域事件清单推断全覆盖；插件为独立 Spring 子容器，跨插件事件须经主容器发布，且 SBP `onPluginBootstrap` 在插件上下文 refresh 前调用，故来源专属 FileHelper 单例须在 refresh 前注册；持久写/删/移成功后发布带操作类型 + 绝对文件 URI 的事件（重命名另带旧 URI）；临时文件/目录清理不发布；消费者按路径范围与来源过滤。
- 【硬规则】插件全量改名覆盖契约与模块身份（纠正：MemoryCurve→MemoryCycle 时遗漏 module 名与事件类型）：搜索并同步插件 ID、plugin-api 公共事件类型及其消费者、Maven/PNPM module 名、资源与数据路径、物理目录、workspace 与 lockfile；交付前全局搜索旧命名。
- 核验插件前先从 Maven 模块列表与插件描述符枚举目标清单，不凭记忆数数（纠正：用户把目标数从 2 更正为 3）；运行目录旧 ID 残留构件（如旧 memory-curve JAR）单独报告，不混入当前数量。

## 其他
- 用户纠正（2026-10-17）：禁止用布尔 `!=` / `==` 把多个条件压成等价或异或校验。依据：`CardChangedEvent`、`NoteChangedEvent` 的 REQUEST 载荷校验曾写成 `(kind == Kind.REQUEST) != (createRequest != null)`，用户指出这种写法炫技多于实用；规则：表达业务约束时拆成直观的独立 `if`，让每种非法组合及其错误信息一眼可读。
- "优化日志输出格式"指改 Logback appender pattern，先检查现有 Logback 配置（当时无 XML，级别与路径在 `backend/app/src/main/resources/application.yml`），不改业务日志消息文本。
- 粘贴格式判定按来源（纠正：Markdown 粘贴丢换行、代码误判、富文本丢格式）：`.md` 文件原样读取；有结构的富文本优先按 text/html 转 Markdown 并保留段落与常见行内格式；代码不因行内符号被判成 Markdown（勿在 text/html 前优先返回 text/plain）；纯文本先保留换行再按结构语法判定。
- 交付表述区分"源码已实现"与"运行中已生效"（纠正："双端一致"统计界面显示为空）：新增响应字段后未获授权构建/重载，不声称界面已呈现新值；前端对旧响应缺字段显示明确"不可用"提示，不留空或伪报 0。
