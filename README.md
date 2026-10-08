# thisnode 韧知笔记

本地优先的节点化知识工具。**Node 是最小知识原子**，一篇笔记就是一个可以摊开的页面，页面由若干**卡片**组成。

> 当前状态：**可运行**（笔记页面 + 卡片 + 搜索 + 双向链接 + 快照；收藏 / 记忆曲线为插件）。
> 百度网盘备份插件已**暂时移除**（代码在 git 历史 `946fb1d`，恢复方法见 [`docs/GAPS.md`](docs/GAPS.md)）。
> **笔记数据以本地文件为权威**：一张卡片 = 一个 md（头部一行 meta 注释 + 正文），加载时扫目录、
> 按注释里的归属聚合成一篇篇笔记（见 §1.2）。已知缺口与设计取舍见 [`docs/GAPS.md`](docs/GAPS.md)。

---

## 核心模型：一篇笔记 = 一个页面 = 一列卡片

```
笔记 Node
 └── 页面（一篇笔记一个，你打开就是它）
      ├── 卡片 A   ← 这段内容 + 可选的自定义标题
      ├── 卡片 B
      └── 卡片 C
```

- **卡片只属于它所在的那篇笔记**，装的是这篇笔记的内容片段（不是别的笔记的引用）
- **页面是一列，从上往下长**：新卡片接在最后一张下面；双击的位置决定卡片的左边界与插入点
- **在卡片里直接写**：双击页面即新建卡片并聚焦，`Ctrl+V` 把剪贴板内容变成卡片，
  写完点别处自动保存
- **卡片标题是可选的**：不写就没有标题栏（不显示任何自动生成的标题，避免和内容重复）；
  选中卡片后点右上角「＋ 标题」可以命名
- **空卡片自动删除**：一张卡片如果既没有文字也没有标题，离开它就自动消失
- 笔记的 markdown 是**它的卡片按顺序拼接**的结果 —— 内容只存一份，
  所以卡片、笔记、搜索结果、导出的 Markdown、快照永远一致
- **没有**「画布」这个独立实体，也**没有**看板 / 四象限这类工作流概念；页面背景是纯色，不带点阵

交互上对着 OneNote 的手感做：点一下就写，写完就在那儿，不用先想结构。

---

## 1. 架构

```
│  Vue 3 + TS + Vite + Pinia + CodeMirror 6                             │
│    笔记列表 · 笔记页面（卡片） · 搜索 · 命令面板 · 设置                  │
│                          │ HTTP 127.0.0.1:45917                        │
│  ┌───────────────────────▼────────────────────────────────────────┐   │
│  │  Spring Boot 3.5 本地后端（sidecar，仅监听回环地址）              │   │
│  │  Node / NoteBlock / Tag / Link / Search / IO / Snapshot / Notes  │   │
│  └───────────────────────┬────────────────────────────────────────┘   │
│  ┌───────────────────────▼────────────────────────────────────────┐   │
│  │  ~/.thisnode/                                                  │   │
│  │    notes/     ★权威数据：一张卡片 = 一个 md（头部 meta 注释）   │   │
│  │               （.notes/ 里是笔记 meta、分组、设置与操作日志）    │   │
│  │    attachments/  图片与附件                                     │   │
│  │    snapshots/    每日 zip 快照（保留 7 天）                      │   │
│  │    logs/         后端日志                                       │   │
│  └────────────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────────┘
```

设计要点：

- **文件是权威数据**：一张卡片 = 一个 md —— 头部一行 `<!-- thisnode:meta {…} -->` 注释装它的
  元数据（归属笔记、位置尺寸、颜色、插件扩展区），其余是正文。笔记级信息（标题、标签、别名、
  回收站、归属分组）在 `notes/.notes/<noteId>.json`。
- **笔记的 markdown 是卡片的投影**：正文 = 各卡片按 z 序拼接。内容只存一份（在卡片里），
  所以搜索结果、双链、导出、快照永远一致。
- **加载即聚合**：启动时扫一遍目录，先读所有 md，再按注释里的 `noteId` 聚合成一篇篇笔记；
  没有 meta 注释的 md 会被**认领**成一篇单卡笔记、当场补上注释 —— 把一堆 md 丢进目录就能用。
- **外部改动一概以磁盘为准**：后端每 4 秒比对目录指纹（路径 + mtime + 大小），变了就整库重扫
  （`NoteSyncService`）。不再有"镜像回写"这一步，也就不存在"数据库和文件谁更新"的问题。
- **链接与搜索都是派生数据**：链接图由正文里的 `[[标题]]` 现算，搜索索引就是内存里的笔记 ——
  没有索引表要同步，也没有"索引过期"这种状态。
- **重命名会跟随链接**（与 Obsidian 一致）：改名后自动改写所有 `[[旧标题]]` 引用。
- **删除维持一致性**：软删除只翻笔记 meta 里的标记（卡片文件原地保留，可恢复）；硬删除把该笔记的
  卡片文件与笔记 meta 一起删掉，插件挂在卡片上的数据（如记忆曲线的排期）随之消失。
- **所有写操作记录到 `notes/.notes/ops.jsonl`**，为将来的同步/冲突解决留出历史。

## 1.1 插件平台（前后端自完备）

一个插件 = **一个模块**，后端与前端都在里面，且各自的 jar 携带各自的产物：

```
plugin-favorite/
├── frontend/                       插件前端源码（自己的 vite.config.ts，产出 plugin.js）
├── src/main/java/com/thisnode/ext/favorite/    后端：SpringBootPlugin + Starter + Controller
└── src/main/resources/
    ├── plugin.properties           PF4J 描述符（plugin.id / plugin.class / version / provider）
    └── plugin/favorite/
        └── plugin.js  plugin.css   前端产物（由本模块的 Maven 构建生成，随 jar 发布）
```

### 三层

| 层 | 内容 |
| --- | --- |
| `plugin-api`（Maven 模块） | 后端契约：`Plugin` / `PluginDescriptor` / `PluginDataStore` / 契约异常（插件看不到核心内部，编译期强制） |
| `frontend/plugin-api`（pnpm 工作区包 `@thisnode/plugin-api`） | 前端契约：六个插槽注册器、可用性桥、插件管理客户端、HTTP 客户端、宿主服务 `host()` |
| `backend/.../plugin/` | 平台的插件运行时：注册表（PF4J）、启动四阶段、状态与配置、端点闸门、`/plugin-assets` |

### 主体 = 纯平台，业务领域 = 核心插件（notes）

按 sbp 最佳实践（参考 `sbp-demo`，见 [`docs/SBP_REFACTOR.md`](docs/SBP_REFACTOR.md)），主体只做插件
生命周期管理、共享 DataSource 与前端产物托管，**不带业务**：笔记 / 卡片 / 搜索 / 附件 / 快照 / IO
全部在 **`plugin-notes`**（`kind=CORE`）里，控制器由 SBP 挂进主 MVC（URL 不变）。兄弟插件
（favorite / memory-curve）**不依赖它**：各自的状态写在自己的文件里，也不读笔记数据，所以
`plugin.dependencies` 是空的 —— 核心插件缺位时它们照样能独立启动。核心插件**不允许停用/在线重载**
（管理面拒绝，闸门双保险），理由是整块笔记能力会随一次误点整体下线。

### 插件之间怎么通信：主容器转发的 Spring event

SBP 给每个插件一个**独立的 ApplicationContext**（主应用是 `AnnotationConfigServletWebServerApplicationContext`，
插件是 `AnnotationConfigApplicationContext`），而 Spring 的事件只在**发布它的容器**内广播 ——
插件 A 在自己容器里 `publishEvent`，插件 B 容器里的 listener 收不到（实测确认，非推测）。

所以跨插件通知的约定是：**发布方拿主容器发、订阅方把 listener 注册到主容器**
（`SpringBootPlugin#getMainApplicationContext()`）。当前唯一的用例是 `CardChangedEvent`：notes 删卡片
（含外部删掉 md 文件）时发布，memory-curve 收到后清掉对应排期条目 —— 这样"卡片没了、排期还在"
的孤儿就不会出现；插件停用时要把 listener 从主容器摘掉，否则重新启用会重复注册。

三个实测踩出来的坑：

- 注入 `ApplicationEventPublisher` / `ApplicationContext` **永远拿到当前容器**：Spring 在
  `prepareBeanFactory` 把它们注册成 *resolvable dependency*，优先级高于 bean 查找 —— 要用自定义
  类型（`MainEventPublisher`）包一层，或直接调插件类的 `getMainApplicationContext()`；
- `afterPluginBootstrap` 钩子里 `getApplicationContext()` 还是 **null** —— 插件容器要用回调参数 `ctx`；
- 该钩子在容器 refresh **之后**执行 —— 那时注册的 bean 无法被 refresh 期间创建的 bean 构造器注入，
  消费方要用 `ObjectProvider` 延迟取。

### 怎么被装载：PF4J / SBP（不是 Spring 装配声明）

插件**不在**应用 classpath 上，也不靠 `@AutoConfiguration` 自装配。它们是 `plugins/` 目录里的独立 jar，
由 **PF4J**（经 **SBP** 包装）用自己的 ClassLoader 装载，在**自己的 Spring 子容器**里运行：

```java
public class FavoritePlugin extends SpringBootPlugin implements Plugin {
    @Override protected SpringBootstrap createSpringBootstrap() {
        return new SpringBootstrap(this, FavoriteStarter.class)   // 插件自己的 @SpringBootApplication
                .importBean(PluginDataStore.class)                // 平台只共享状态存储
                .importBean(DataSource.class);
    }
}
```

因此：**删掉 `plugins/` 里的 jar，插件就整体消失（前端入口与端点一起走），核心不需要重新编译**。
管理页里"停用"走 PF4J 的 `stopPlugin`/`disablePlugin`，状态持久化在 `plugins/disabled.txt`；
每个插件卡片还有一个「重载」按钮（`POST /api/plugins/{id}/reload`）：**SBP 会重建插件的类加载器与
Spring 子容器**，于是换掉磁盘上的 jar 当场生效，不必重启应用。开发期更省事 —— 见
[`docs/PLUGIN_PLATFORM.md`](docs/PLUGIN_PLATFORM.md) 的「插件后端开发模式」：
`pwsh tools/dev_plugins.ps1` + `--spring.profiles.active=dev`，改完插件代码编译一下点重载即可。

### 启动五阶段

```
发现 → 校验契约 → 激活 → 健康检查
```

由 `PluginLifecycle`（`SmartInitializingSingleton`）在 Web 容器开始接流量之前跑完；健康检查只报告不拦启动。

### 前端：qiankun 微应用（运行时装载，不是打进核心 bundle）

- 核心前端是 **qiankun 主应用**：启动后按 `GET /api/plugins` 的**启用列表**，用 `loadMicroApp`
  把每个插件前端作为微应用装载进隐藏容器；插件 `mount()` 里注册插槽，`unmount()` 里注销 ——
  设置页的停用/启用/重载是**真实的前端生命周期**，不再依赖整页刷新
- 微应用入口是运行时生成的 blob HTML（后端零改动），指向：开发 = 插件自己 dev server 的
  源码入口（端口约定见 `frontend/host/src/plugins/devPorts.ts`，dev server 没起自动回退 jar 产物）；
  生产 = 后端 `PluginAssetController` 从**插件 jar** 里流式端出的
  `/plugin-assets/<id>/frontend/plugin.js`（停用的插件连产物都不拉）
- **共享运行时靠沙箱的 modules 注入**：宿主把自己的 `vue` / `vue-router` / `@thisnode/plugin-api`
  命名空间注入微应用沙箱 —— qiankun 3 的 ESM 沙箱里 URL 模块是私有实例，旧的 import map
  方案在沙箱下会裂成两个 Vue；注入后微应用里的裸 import 解析到的就是宿主同一份实例，
  插件页面路由在 `mount()` 后由 `router.addRoute` 挂上
- 开发期共享依赖由 `@thisnode/plugin-api/vite` 的 `qiankunSharedDeps()` 拦截成哨兵路径、
  宿主 resolveHook 映射回裸名（Vite dev 会把裸 import 改写成私有依赖 URL，必须拦）

机制、生命周期与踩坑记录见 [`docs/QIANKUN_MFE.md`](docs/QIANKUN_MFE.md)。

### 六个插槽

| 注册函数 | 插槽 | 例子 |
| --- | --- | --- |
| `registerCardPlugin` | 卡片工具行 | 记忆曲线的 🧠 开关 |
| `registerNoteTool` | 笔记标题旁 | 收藏的 ★ |
| `registerPluginSettings` | 设置页插件卡片下 | 网盘备份的授权面板（该插件已暂时移除，当前此插槽无使用者） |
| `registerNavEntry` | 侧栏入口 | 🧠 复习 |
| `registerPluginRoute` | 插件自有页面 | `/review` |
| `registerNoteListFilter` | 笔记列表的筛选 | ★ 收藏（不另开页面，就是核心列表的一个筛选项） |

### 加一个新插件（不用改核心一行）

1. 新建 Maven 模块，依赖 `plugin-api` + `plugin-support` + `sbp-core`
2. `resources/plugin.properties` 写 `plugin.id` / `plugin.class` / `plugin.version` / `plugin.provider`
3. `XxxPlugin extends ThisnodePlugin` + `XxxStarter`（`@SpringBootApplication`）
4. 需要持久化就用自己的文件（`PluginDataStore` 或插件自己的 meta 文件）—— 平台没有数据库可建表
5. 前端：`index.ts` 里用 `@thisnode/plugin-api` 的 `register*` 登记插槽 + 自己的 `vite.config.ts`
   （lib 模式，外置 vue / vue-router / @thisnode/plugin-api；导出 qiankun 生命周期，见 [`docs/QIANKUN_MFE.md`](docs/QIANKUN_MFE.md)）
6. 根 `pom.xml` 加 `<module>`、`pnpm-workspace.yaml` 加 `frontend/plugins/<id>`、
   插件 pom 加 `frontend` profile（激活路径指向 `../frontend/plugins/<id>`）、
   宿主的 `devPorts.ts` 登记开发端口（插件 jar 由各插件 pom 的 antrun 自拷，主体无需登记）
   （**不要**把它加进 `backend` 的依赖 —— 插件是外置的）
7. `mvn package` 会自动把它的 jar 拷进 `plugins/`；完整步骤与踩坑见 [`docs/PLUGIN_PLATFORM.md`](docs/PLUGIN_PLATFORM.md)

### 开发期与生产期的差别

| | 开发（`pnpm dev`，5173） | 生产（后端 45917） |
| --- | --- | --- |
| 插件前端来源 | 插件**源码**（各自的 dev server，frontend/plugins/<id>） | 插件 jar 里的 `plugin.js` |
| 改插件前端 | 刷新页面或点「重载」（沙箱内不接 HMR；独立预览页有 HMR） | 需重建该插件（`cd frontend/plugins/<id> && pnpm build`），刷新页面生效 |


## 1.2 笔记怎么存

```
~/.thisnode/notes/
├── 笔记名-卡片描述-3f2a91bb.md   一张卡片 = 一个 md：
│                                  第 1 行    <!-- thisnode:meta {"id":"…","x":0,"y":0,"z":0,…} -->
│                                  第 2 行起  卡片正文（渲染时注释不可见）
└── .notes/
    ├── <noteId>.json             笔记元数据 + **卡片清单**（顺序即阅读顺序）
    │                             + 列表页缓存：摘要 / 出链清单 / 出链数 / **入链数** / 未解析数
    ├── notebooks.json            分组台账（老库文件名是 containers.json，读取兼容）
    ├── tags.json                 标签台账：名称 / 颜色 / 时间（老库是扁平颜色表，首次读取自动升级）
    ├── settings.json             笔记侧设置（设备 id 等）
    ├── ops.jsonl                 操作日志，一行一条
    └── snapshots.json            快照台账
```

- **归属在笔记这边**：笔记 meta 持有**卡片清单**（`cards: [{id, file}]`，顺序即阅读顺序），卡片自己
  **不记**它属于谁。于是"读到笔记集合"只要扫一遍笔记 meta（O(笔记数)），卡片正文按需读；
  每万张卡片的一次全扫在百毫秒量级，而**列表页一个数字都不用读正文**（见下方"列表页缓存"）。
- **meta 是 md 头部的一行注释**：`<!-- thisnode:meta {…单行 JSON…} -->`，字段是卡片自己的 id、
  位置尺寸（`x y w h z`）、颜色 / 折叠、以及插件扩展区（`ext`）。用注释而不是 frontmatter，
  是因为它渲染时不可见、也不会被 Obsidian 当成属性；写在文件里而不是旁边的 sidecar，是因为
  这样一张 md 就是一张**自足**的卡片 —— 复制、移动、丢进目录都能被识别，也不存在"两份 meta
  谁说了算"。注释是唯一权威：旧版本留下的 `.meta.json` 会在加载时被回填进注释、然后删除。
- **文件名三段**：`<笔记名>-<卡片描述>-<随机串>.md`。笔记名让人一眼看出这张卡属于哪篇；卡片描述
  取卡片标题，没标题就用正文的首个标题或首行；随机串保证同名同描述的两张卡不互相覆盖。
  **身份由注释里的完整 id 决定**，所以标题变了不会去改文件名 —— 否则 Obsidian / Git 会看到
  一次无意义的增删。（认领来的单卡笔记两段同源，只写一次，免得出现"标题-标题-3f2a91bb.md"。）
- **笔记没有自己的正文文件**：一篇笔记的 markdown 是它所有卡片按 z 序拼出来的。这个不变量是从
  旧模型保留下来的，也是"卡片、笔记、搜索结果、导出永远一致"的原因。
- **认领**：不在任何清单里的 md（手拷进来的、外部新建的）由**定时器**发现，当作一篇新笔记（单卡），
  当场补上注释，md 原地不动。定时器只做一次目录遍历 + 差异处理（默认 30 秒一次），不重扫整库。
- **删除**：软删除只改笔记 meta 里的 `deleted`（卡片文件保留，可恢复）；硬删除把卡片文件与
  笔记 meta 一起删掉。插件挂在卡片上的数据（`ext` 里的记忆曲线排期）随之消失。
- **外部改动**：直接在这个目录里改 / 加 / 删文件都有效 —— 后端默认每 30 秒走一次目录遍历 + 差异
  处理（只读变化的文件），也可以 `POST /api/notes/sync/reconcile` 立刻触发。
- **保存冲突**：保存卡片时带上「编辑开始时卡片上的正文」作为基线（`baseContentMd`）；磁盘上已经
  不是那一份（外部编辑器改过）就返回 **409**，前端提示并重新载入磁盘上的内容 —— 应用里的旧内容
  不会覆盖外部改动。
- **列表页一个数字都不读正文**：摘要、出链清单、出链数、**入链数**、未解析数都在笔记 meta 里，
  写入时顺手算好（`NoteCompositionService#refreshCachedView`）。入链数是跨笔记的量：对端写入时按
  「旧清单 → 新清单」的差集在目标笔记的 meta 上 ±1；「先写 `[[链接]]`、后建笔记」由新建时的补解析
  处理；删掉引用者会立刻释放。老库没有这些字段，首次启动会用标记文件 `link-cache-v1` 补齐一次；
  任何时候都可以 `POST /api/maintenance/reindex` 按正文重算并校准。
- **按需现扫，没有常驻索引**：读取一律现扫目录 / 现读那一个文件，内存里不保留任何索引映射；
  一次方法内部需要多次用到笔记集合时，由调用方自己扫一次、在局部变量里传递。因此外部改名、
  手改 meta、往目录里丢文件都在**下一次请求**生效（对账定时器只剩"认领游离文件"与"摘掉消失的
  卡片"两件事）。缓存以后要做的话，作为插件加在 `NoteCatalog` / `CardCatalog` 这一层。

## 2. 技术栈

| 层级 | 选型 | 说明 |
|---|---|---|
| 前端 | Vue 3.5 + TypeScript 5.9 + Vite 6 | Pinia 状态管理，Vue Router（hash 模式） |
| 卡片编辑 | 卡片内 `<textarea>` + 卡片标题栏 | 双击即写，无弹窗；`Ctrl+Enter` 保存 |
| 笔记页面 | 自研 DOM + CSS transform | 卡片绝对定位、按层叠顺序渲染、可缩放/平移 |
| 预览渲染 | markdown-it | 自定义 `[[wiki link]]` 与 `#标签` 行内规则 |
| 后端 | Spring Boot 3.5.6 + Java 21 | REST + 文件存储（按需现扫 + 原子写，无 ORM、无常驻索引） |
| 笔记存储 | `notes/` 下的卡片 md + meta json | 权威数据；加载时扫目录、按 meta 聚合 |
| 搜索 | 内存检索（标题加权 + 正文子串） | 中文按子串、英文按词；索引就是内存里的笔记，不会过期 |
| 数据库 | **无** | 已彻底退役：笔记数据是卡片 md + meta json，插件状态与配置是 `plugin-data.json`，平台不再连接任何数据库 |
| 测试 | JUnit 5 + Vitest + Playwright 脚本 | `mvn test` / `pnpm test` / `frontend/host/tools/*.mjs` |

## 3. 环境要求

| 依赖 | 版本 | 用途 |
|---|---|---|
| JDK | **21+** | 后端（`JAVA_HOME` 需要可用） |
| Maven | 3.6+ | 后端构建 |
| Node.js | 20+ | 前端构建 |
| pnpm | 9+ | 前端包管理 |
| ~~MySQL~~ | ~~8+~~ | **不再需要**：数据库层已退役（笔记 = 文件，插件状态 = `plugin-data.json`） |

## 4. 快速开始

### 4.1 单体运行（交付形态：一个进程、一个端口）

前端产物在打包时被拷进后端 jar 的 `static/`，**后端自己把界面端出来** —— 没有独立的前端服务，
也不需要反向代理。

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-21"

# 一条命令搞定：构建前端 → 构建 7 个模块 → 内嵌前端产物 → 把插件 jar 拷进 plugins/
# 交付请带 clean：前端产物文件名带 hash，Maven 复制资源时不会删掉上一次的旧文件，
# 增量构建会让 jar 里留下历史版本的 assets/xxx-旧hash.js。
mvn -DskipTests clean package
java -jar backend/app/target/thisnode-backend.jar
```

浏览器打开 <http://127.0.0.1:45917> 即可使用完整功能。

交付物是**两份东西**，缺一不可（平台主体 + 插件目录，其中 notes 是核心业务插件）：

```
backend/app/target/thisnode-backend.jar     平台主体（纯平台：插件管理 + 共享 DataSource + SPA 托管）
plugins/notes.jar                       ★ 核心业务插件（笔记领域，kind=CORE，不允许停用）
plugins/{favorite,memory-curve}.jar             功能插件（后端 + 前端产物，**不依赖 notes**）
```

插件是 `plugins/` 目录里的独立 jar，由 PF4J/SBP 在运行期装载 —— `mvn package` 会**自动**把各插件模块
产出的 jar 拷进 `plugins/`，不需要手工 cp。加插件就是往这个目录丢一个 jar，删插件就是删掉那个 jar
（另一种"停用"是管理页里的开关，它会被 PF4J 记进 `plugins/disabled.txt`，重启后仍然停用）。

`mvn package` 会顺带跑 `pnpm install` + `pnpm build`，所以需要 Node.js 与 pnpm。
只编后端、不想碰前端工具链时：

```powershell
mvn -DskipTests "-Dthisnode.skip-frontend=true" package   # 跳过前端构建与内嵌
```

> PowerShell 里带点的属性名要加引号，否则会被拆成两个参数（`Unknown lifecycle phase`）。

两条边界值得记住：

- **`/api/**` 命中不了时如实报错**（404），不会被界面兜底成 200 —— 否则"接口不存在"就再也看不出来了
- 静态资源走 Spring Boot 默认映射（`classpath:/static/`），**没有 SPA 转发兜底**：只有真实存在的文件才取得到；
  前端路由是 hash 模式（`#` 后面），服务端只需要在 `/` 端出 `index.html`，所以未知路径 404 不影响使用

### 4.2 开发模式（前后端分离 + qiankun 微前端）

前端与后端分离运行：后端只提供 API，前端（主应用 + 三个插件微应用）各自跑 Vite dev server。
开发期仍有热更新（宿主页面完整 HMR；插件微应用在沙箱里不接 HMR，改完刷新即可，
独立预览页有完整 HMR，见 [`docs/QIANKUN_MFE.md`](docs/QIANKUN_MFE.md)）。

```powershell
# 终端 1：后端（同上）
java -jar backend/app/target/thisnode-backend.jar

# 终端 2：全部前端（主应用 5173 + 插件 7111/7112/7113，pnpm -r --parallel dev）
pnpm dev            # 仓库根
#   或分开：pnpm dev:host / pnpm dev:plugins
```

- 插件 dev server 没起也能用：宿主自动回退到插件 jar 里的产物（无插件源码级调试体验）
- 与 `java -jar` 同时跑会**抢同一个端口**：错开 `THISNODE_PORT`，或同一时间只跑一个

### 4.3 在 IDEA 里跑（后端 + 页面）

前端产物由 Vite 直接产出到 `backend/app/src/main/resources/static/`（该目录已 gitignore），
所以 **IDEA 自己拷 resources 时就会带上页面**，不需要挂 Maven 目标：

```powershell
cd frontend\host
pnpm install
pnpm build          # 产物直接落到 ..\backend\app\src\main\resources\static（IDEA 与 Maven 都能端出来）
```

然后 IDEA 的 Run Configuration：**不需要填 Active profiles**（数据库已退役，没有连接参数），
启动后开 <http://127.0.0.1:45917> 就是完整页面。

注意：

- 改了前端代码要重新 `pnpm build`（想要热更新就用上面的 `pnpm dev` + 开 5173）
- IDEA 的 **Rebuild 会清掉 `target/classes`**，但不会清掉 `src/main/resources`，所以页面还在
- 与 `java -jar` 同时跑会**抢同一个端口**：错开 `THISNODE_PORT`，或同一时间只跑一个

## 5. 验证

> 浏览器测试（`*.mjs`）会往演示库里累积卡片和笔记，且 `note_page_test.mjs` 需要一张
> 「未被笔记列表遮住、也不与其他卡片重叠」的卡片。**跑之前先重播演示库**，否则会因数据
> 累积而误报：
>
> ```powershell
> python tools\reset_demo.py --reset
> ```

```powershell
# 后端单元测试（插件 jar 契约 + 笔记文件模型 + 链接/搜索派生）
mvn test                       # 从仓库根：父工程 + 插件 + 后端

# 前端单元测试（Markdown 渲染、编辑器配置、插件前端边界）
cd frontend; pnpm test         # 前端在仓库根的 frontend/，不在 backend 下

# 真实浏览器：笔记页面交互（双击建卡 / 卡片内输入 / Ctrl+V / 拖动 / 缩放）
cd frontend\host; node tools\note_page_test.mjs

# 真实浏览器：标题重命名（双击聚焦 / Enter·失焦提交 / Esc 取消）
node tools\title_rename_test.mjs

# 真实浏览器：卡片内容保存（离开焦点必须保存 / 空卡片仍回收 / 写满后向下伸展且宽度不变）
node tools\card_save_test.mjs

# 真实浏览器：列表常驻 + 卡片工具行不重叠 + 每张卡片的源码/双列/预览模式
node tools\card_modes_test.mjs

# 真实浏览器：走遍所有视图，收集运行时错误
node tools\browser_walkthrough.mjs

# 其余浏览器套件（同一目录下）：卡片链接 / 分组 / 插件管理页 / 插件启停闭环 / 收藏插件
#   card_link_test.mjs  grouping_test.mjs  plugin_manager_test.mjs
#   plugin_disable_closure_test.mjs  favorite_plugin_test.mjs
#   blank_space_probe.mjs  browser_probe.mjs  drag_probe.mjs  qiankun_smoke_test.mjs
#   （复习页目前没有对应套件：文档里曾写的 review_page_test.mjs 并不存在，回归靠手工）

# 笔记目录外部改动（应用外改 md → 重扫 → 搜索；应用外删 md → 卡片消失）
python tools\vault_test.py
```

> 浏览器套件会**真的停用插件**（随后再启用）。停用是持久的（PF4J 写 `plugins/disabled.txt`），
> 所以套件结束时会把三个插件都拉回启用态 —— 中途失败也一样。

> 后端单测集中在笔记域的文件模型上（`backend/plugins/notes/src/test`：聚合、认领、删除一致性、
> 链接派生、搜索过滤），平台侧只剩插件 jar 契约测试（细节与缺口见 [`docs/GAPS.md`](docs/GAPS.md) §5）。
> 功能回归仍主要靠上面这些**真实浏览器套件**：它们跑在真后端上，代价是慢、且需要环境。

`tools/` 下还有：

| 脚本 | 用途 |
|---|---|
| `frontend/host/tools/*.mjs` | 真实浏览器套件：笔记页 / 卡片模式 / 卡片保存 / 链接 / 分组 / 标题重命名 / 遍历 / 插件平台 / 插件启停闭环 / 收藏 |
| `vault_test.py` | 验证笔记目录外部改动（外部编辑 → 重扫；外部删除 → 卡片消失） |
| `baidu_pan_probe.py` | ~~百度网盘探针~~（插件已暂时移除，脚本随之删除；恢复插件时一并从 git 历史取回） |
| `dev_plugins.ps1` | 建立/删除插件开发模式的目录布局（`plugins-dev/`），配合 `--spring.profiles.active=dev` |
| `reset_demo.py --reset` | 清空并重建演示数据（笔记页面模型） |
| `cleanup_test_notes.py` | 清理测试产生的笔记（默认只报告，`--yes` 才删除） |
| `cleanup_empty_blocks.py` | 清理自动化跑完留下的空卡片（默认只报告，`--yes` 才删除） |

## 5.1 插件平台（SBP/PF4J）

插件是 `plugins/` 目录里的**独立 jar**：由 PF4J 用自己的 ClassLoader 装载、在自己的 Spring 子容器里运行，
控制器挂进主 MVC，前端产物由后端从那个 jar 里端出。**丢一个 jar 进目录就能装，换一个 jar 就换它的前后端。**

架构、交付形态、加插件的步骤，以及迁移时踩过的 9 个类加载器/装配坑，见
[`docs/PLUGIN_PLATFORM.md`](docs/PLUGIN_PLATFORM.md)。

## 6. 目录结构

```
.
├── pom.xml                      父工程（多模块聚合：版本与依赖只在这里锁一次）
├── backend/                     **全部后端代码**（Maven 多模块，聚合 pom 在仓库根）
│   ├── app/                     Spring Boot 平台主体（纯平台：插件管理 + 插件状态文件 + SPA 托管）
│   │   └── src/main/java/com/thisnode/
│   │       ├── api/             统一 JSON 异常映射（契约异常 → 状态码）
│   │       ├── config/          Web 装配（CORS、插件闸门拦截器）+ 插件状态 KV（plugin-data.json）
│   │       └── plugin/          插件运行时：注册表、四阶段生命周期、状态/配置、闸门、/plugin-assets
│   ├── plugin-api/              插件契约（独立构件）
│   │   └── src/main/java/com/thisnode/plugin/api/
│   │       ├── Plugin / PluginDescriptor / PluginKind   插件身份与契约版本（apiVersion）
│   │       ├── PluginConfigField / PluginHealth         配置声明与自我体检
│   │       ├── PluginDataStore                          插件状态与配置的 KV（一个文件）
│   │       └── 契约异常 / CardChangedEvent              跨容器异常与跨插件事件
│   ├── plugin-support/          插件侧共享支持（由平台打进自己的 jar，插件运行期从它加载）
│   │   └── src/main/java/com/thisnode/plugin/support/ThisnodePlugin.java
│   │                                                    契约与 PF4J 的接缝：id/版本取自描述符
│   └── plugins/                 插件源码（与 frontend/plugins 对称）
│       ├── notes/               ★ 笔记核心插件（kind=CORE）：主体原有业务（笔记数据走本地文件）
│       │   └── src/main/java/com/thisnode/ext/notes/
│       │       ├── store/       文件模型的落点：NoteFilePaths / NoteStore / NoteCatalog / CardCatalog
│       │       │                / NoteCompositionService（组合层）/ NoteMigrationService（启动维护）/ *Meta / *Book / *Ledger
│       │       ├── domain/      Repository（按需读写磁盘）+ 领域实体；链接图由 meta 里缓存的出链投影
│       │       ├── service/     业务逻辑（Note / NoteBlock / Search / Snapshot / 笔记目录重扫 / IO）
│       │       ├── api/         REST 控制器 + DTO（URL 与前端契约保持原样）
│       │       └── util/        Markdown 解析与渲染、slug、ID
│       ├── favorite/            收藏插件（实体插件）
│       └── memory-curve/        记忆曲线插件（卡片插件；排期写在卡片 meta 的 ext 扩展区）
├── frontend/                    **全部前端代码**（与后端分离；qiankun 微前端）
│   ├── host/                    主应用（Vue 3 + Vite + Pinia；qiankun 宿主，:5173）
│   │   ├── src/plugins/         微应用装载器（loader.ts）+ 共享端口约定（devPorts.ts）
│   │   └── tools/*.mjs          真实浏览器套件（Playwright）
│   ├── plugin-api/              前端插件契约（pnpm 工作区包 @thisnode/plugin-api：插槽注册器 + 宿主服务 + vite 插件）
│   └── plugins/                 插件微应用源码（favorite / memory-curve）
│                                产物由各自 vite build 落到对应插件模块的 resources，随 jar 发布
├── plugins/                     **插件交付目录**：运行期外置 jar（由各插件 package 阶段自拷）
├── plugins-dev/                 开发模式的目录布局（tools/dev_plugins.ps1 生成，不入库）
├── tools/                       验证与辅助脚本
└── docs/                        已知缺口、插件平台、微前端等技术文档
```

## 7. 快捷键与手势

| 操作 | 功能 |
|---|---|
| **双击页面** | 新建一张卡片（以点击处为左边界），立即进入输入状态 |
| **双击卡片**（或选中后 `F2`/`Enter`） | 在卡片里写内容；`Ctrl+Enter` 保存，`Esc`/点别处退出 |
| **`Ctrl+V`**（页面上） | 把剪贴板文本/图片变成一张卡片 |
| **拖动卡片左/右边框** | 调整卡片宽度 |
| **拖动卡片下边框** | 调整卡片高度 |
| **拖动卡片上边框** | 移动卡片（卡身其余部分也可拖动） |
| **拖动卡片边角** | 双向调整尺寸（最小 240×88） |
| 选中卡片 → 右上「＋ 标题」 | 给卡片起名（可选）；命名后卡片顶部显示标题栏 |
| 点击页面空白 | 结束当前卡片的编辑；**空卡片会被自动删除** |
| `Shift + 拖拽` | 框选多张卡片 |
| `Alt + 拖拽` / 中键拖拽 | 平移页面 |
| `Ctrl + 滚轮` / 滚轮 | 缩放（25%–300%） |
| `Ctrl/Cmd + 1` / `0` | 适应全部卡片 / 重置视图 |
| `Ctrl/Cmd + N` | 新建笔记 |
| `Ctrl/Cmd + K` | 命令面板（搜索笔记、执行命令） |
| `Ctrl/Cmd + D` | 复制选中卡片（复制成新笔记） |
| `Delete` | 删除选中卡片 |
| `Ctrl/Cmd + A` | 全选卡片 |

长文卡片默认展示到浏览器底缘，超出部分在**卡片内滚动**（卡片体上的滚轮直接滚内容，不移动画布）。

## 8. 与需求文档的差异

| 文档 | 实现 | 原因 |
|---|---|---|
| 笔记数据存数据库 | 卡片 md + meta 文件 | 本地优先工具里，用户自己的编辑器就是最好的界面：文件能直接用 Obsidian / VS Code 打开、能被 Git 管、删掉文件就是删掉数据。平台已不连任何数据库，插件状态与配置落在 `plugin-data.json`。 |
| 「Markdown 镜像 / 双向同步」 | 文件即权威，没有镜像 | 两份数据必然有先后与冲突；只剩一份就不存在"谁赢了"这个问题，也不需要把改动同步回去。 |
| MySQL 存笔记与插件状态 | **无数据库** | 数据库层已彻底退役：平台不连任何库，插件自己的数据用文件（`PluginDataStore` / 插件 meta），插件迁移机制随之删除（插件契约升到 v2）。 |
| 中文全文检索 | 内存检索：中文按子串、英文按词 | ngram / FULLTEXT 是数据库的解法；文件模型下索引就是内存里的笔记，子串包含在小规模语料上更准，也不会"索引静默失效" |

## 9. 许可

未定。代码与文档均为本地优先、数据可移植设计（Markdown + JSON 导出不锁定）。
