# Lessons

## 1. 列表路径直接消费 NoteMeta,列表接口返回 meta 本体

- **纠正来源**：用户三轮逐步收窄——先要求补 Note 字段，再改为「NoteSummary 直接用 NoteMeta 构建」，最后要求「NoteSummary 删除，返回 NoteMeta」。方向：列表页不为展示造中间视图、也不留投影 DTO。
- **事实依据**：NoteMeta 是为列表准备的轻量数据，一次目录遍历即可全部读出（NoteCatalog 无状态无缓存，`NoteCatalog.java:16`，每次 scan 都扫磁盘）；摘要/标签/别名/notebookId/卡片清单/三个计数全是现成字段；`NoteService.list()` 直接 `catalog.scan()` → 过滤 → 排序 → 分页 → 返回 `List<NoteMeta>`；`Dtos.NoteSummary` 已删除。早先注释里"meta 是全量载入的内存索引"的说法是错的，已修正。
- **规则**：列表接口（`GET /api/notes`）返回 `NoteMeta` 本体；不要再建 NoteSummary 之类的投影 DTO，也不给 `Note` 轻量视图加字段（「contentMd 不塞摘要」的修复保留）。meta 的 tags/aliases 保持存储顺序，列表侧不排序。

## 2. 前端字段名跟随 NoteMeta:summary / notebookId,标题前端解析

- **纠正来源**：用户明确「titlesByIds 也没必要，直接从 meta 中取值」及后续删除 NoteSummary。
- **事实依据**：接口返回 meta 后，前端列表行字段是 `summary`（不再是 preview）、`notebookId`（单值，不再是 notebooks 数组）；`NoteList.vue` 用 `notebookTitle()`（store 台账）解析显示标题；`types.ts` 的 `NoteMeta`/`LinkRef` 镜像后端 JSON。
- **规则**：改列表相关字段时以后端 NoteMeta 的 JSON 字段为准（summary/notebookId/links/cards…）；显示文本（分组标题等）一律前端用已加载台账解析，后端不加 id→标题的解析方法（nodeNotebookTitles / titlesByIds 都删过，别再长回来）。

## 3. 一个业务一个名字:container 已彻底清除（含兼容层）

- **纠正来源**：用户原则「一个业务中不能产生两种命名」，先指示「收敛」（字段改名），再指示「不考虑兼容 直接按最新的来」（兼容层一并删除）。
- **事实依据**：数据库时代分组叫 container，文件模型改叫 notebook。已清理：`containerRepository` 字段名、`NoteMeta`/`NotebookBook` 的 `@JsonAlias("containerId"/"containers")` 旧键兼容、`NoteFilePaths` 的 containers.json 读取回退与 `notebooksWriteFile` 双方法、测试里的 containers 变量名/方法名/旧键 fixture。仅存的 container 字样：描述「无 board/container 概念」的注释、`NotebookRepository.java:20` 的新旧模型对比、与分组无关的 DOM/qiankun container 用法。
- **规则**：分组业务的标识符与 JSON 键一律 notebook，不再新增任何 container 兼容代码；已知后果——老库（containerId 键 / containers.json）升级后分组归属落到默认分组「收集箱」，这是用户明示接受的。

## 4. 文件读写只有一个入口:NoteStore（Catalog 层已合并）

- **纠正来源**：用户两次质疑 Catalog 命名（「是不是 notebook」「没必要技术上做分类」），在 A（只改名）/ B（合并）/ C（维持）三方案中选了 **B**。
- **事实依据**：`NoteCatalog` 与 `CardCatalog` 已删除，方法并入 `NoteStore`（note / scanNotes / writeNoteMeta / deleteNoteMeta / titleIndex / resolveTitle / cardPath / readCard(CardRef) / cardContent）；原 `scan()`/`save()`/`delete()` 等薄包装不再存在，调用方直接用 NoteStore 的本体方法名。组合层（NoteCompositionService）只依赖 noteStore，各仓储依赖面反而变窄。
- **规则**：笔记 meta 与卡片文件的任何磁盘读写都走 `NoteStore`，不要再拆出「某某Catalog」类，也不要给同一操作起第二个名字（save→writeNoteMeta 这类映射别再反向新增）。

## 5. 移除入口前先查「功能还剩几个入口」

- **纠正来源**：用户圈图移除「命令面板 / 侧栏搜索 / 列表筛选」三个入口，经查命令面板是回收站的**唯一** UI 入口（更早之前侧栏入口已删，AppSidebar 注释记录了这次迁移）。
- **事实依据**：回收站（filter='trash' + 恢复/清空 API + NoteView 渲染）后端完整，但 UI 入口链是 侧栏→命令面板→（本次删除后）无。搜索全链路（SearchController/SearchService/domain/search 包/前端 searchApi+SearchView+CommandPalette）随入口一起删除，SettingsView 的快捷键帮助行与两个未使用的 searchApi import 一并清理。
- **规则**：删任何入口（按钮/面板/路由）前，grep 该功能的所有入口并评估剩余可达性；入口清零但数据层还在时，必须在汇报里明示「功能不可达」这一后果，由用户决定是否补新入口。

## 6. 重新部署 = 三份产物:app jar + plugins/notes.jar + 前端 static

- **纠正来源**：用户指示「重新部署」；首启后 `/api/search` 仍返回 200——app jar 里确实没有 SearchController（0 命中），旧响应来自 `plugins/notes.jar`（PF4J 插件，运行期从仓库根 `plugins/` 装载）。
- **事实依据**：notes 功能不在 app jar 里；`backend/app/pom.xml` 注释明确交付形态是「应用 jar + plugins/ 目录的插件 jar」。前端 `pnpm build` 直落 `backend/app/src/main/resources/static`。另：Git Bash 里 `java` 不在 PATH，要用 `/c/Users/51165/.jdks/temurin-21.0.9/bin/java.exe`；`mvn -pl` 单模块构建会因 plugin-api/plugin-support 不在本地仓库而失败，必须带 `-am`；Windows 下运行中的进程锁 jar，打包前先停进程。
- **规则**：改动 backend/plugins/notes 后的完整部署顺序：停进程 → `pnpm build`（frontend/host）→ `mvn -pl backend/plugins/notes -am package -DskipTests` → `cp backend/plugins/notes/target/plugin-notes-0.1.0.jar plugins/notes.jar` → `mvn -pl backend/app -am package -DskipTests` → 用完整 JDK 路径启动 → 验证 `/api/health`、首页资源 hash、改动过的端点。只动前端时前两步即可。

## 7. 汇报「已删除」前必须核对实际执行,且 UI 元素的红框范围以用户为准

- **纠正来源**：用户圈的三处之一含列表头（筛选框+＋按钮），我汇报「已移除整个 list-head」但实际**没有执行那条编辑**（验证 grep 只查了 searchApi/paletteOpen 等关键词，没覆盖该 UI）；侧栏「新建笔记」按钮在用户红框范围内，被我以「核心功能」为由擅自保留，用户追问「怎么还在」。
- **事实依据**：NoteList.vue 的 list-head 直至下一轮才真正删除；创建类入口（侧栏按钮/＋按钮/新建第一篇/Ctrl+N）全部移除后，保留的只有：点击 [[未解析链接]] 建笔记（NoteView:365）、卡片内容拆新笔记（NoteView:173 toast）、后端 POST /api/notes（上述功能与复制一份/每日笔记的公共依赖）。
- **规则**：①每完成一个 UI 删除，立刻用「被删元素的**可见文案**」（如「在列表中筛选」「新建笔记」）grep 产物验证，不能只查接口名；②红框圈住多个元素时逐个与用户意图对齐，不自行裁剪范围；③判断「核心功能」可以保留，但必须在汇报里明示「这个我保留了、为什么」，让用户纠正。

## 8. 迁移逻辑已整体拔除:数据模型只有一个当前版本

- **纠正来源**：用户问「Migration 还有迁移逻辑残留吗」，确认后指示「执行」（按「保认领、拔迁移」）。
- **事实依据**：已删除——`NoteMigrationService`（v1→v2、refreshEmptySummaries、naming-v2、link-cache-v1 四项）改为 `StartupMaintenance`（只剩认领游离 md + 摘除消失卡片）；`CardFile.legacyNoteId`/`CardData.legacyNoteId`、`NoteMeta` v1 兼容构造器、`TagRepository` 扁平 tags.json 升级分支、`NoteSyncService` 的 vault-watch 旧键兼容、三个迁移测试用例。用户数据目录的 `containers.json` 孤儿文件已删（实测 7 篇 meta 全 v2、标记文件齐 → 迁移逻辑早已无事可做）。
- **规则**：不再新增任何「老数据升级」代码——meta 就写当前 schema（v2），tags.json 就写台账格式，配置键只用 note-watch；文件镜像的常驻对账在 `StartupMaintenance.run()`（认领 + 摘除），不要往里加迁移步骤。
