# memory-curve 插件实现分析（后端 + 界面侧）

> 本文分析 thisnode 插件平台里一个完整的参考实现：记忆曲线（Anki 式 SM-2 间隔重复）。
> 读它 = 读「一个插件如何独立管理自己的状态、通过消息事件协作并贡献界面插槽」。
>
> 代码位置：后端 `backend/plugins/memory-curve/` ·
> 界面侧 `frontend/plugins/memory-curve/`（index.ts / memoryCurveApi.ts / MemoryCurveTool.vue / ReviewView.vue，约 990 行）

---

## 1. 一句话定位

**它把「哪些卡片要复习、什么时候复习、掌握到什么程度」这件事，完整地放在插件自己的地盘里。**
平台与核心笔记插件都不认识「复习」这个概念，笔记数据也不经过它。

| 关注点 | 归属 | 存放位置 |
|---|---|---|
| 卡片是否开启记忆曲线（开关） | **插件自己** | `<data-root>/plugins/memory-curve/enabled-cards.properties` |
| 排期（状态/难度/间隔/到期时间…） | **插件自己** | `<data-root>/plugins/memory-curve/scheduling.meta` |
| 卡片正文与标题 | **不归它管**（不读笔记数据） | 由界面侧经核心的公开 API 现拼 |

---

## 2. 后端实现

### 2.1 装载与生命周期（PF4J / SBP）

```
backend/runtime/plugins/memory-curve.jar
  ├── plugin.properties          ← PF4J 的 PropertiesPluginDescriptorFinder 读它
  │     plugin.id=memory-curve
  │     plugin.class=com.thisnode.ext.memorycurve.MemoryCurvePlugin
  │     plugin.version=@project.version@   ← Maven 过滤注入（版本单源，父 pom 写一次）
  └── 类 + 空 Spring 容器入口
```

- `MemoryCurvePlugin extends SpringBootPlugin`（PF4J/SBP 的插件基类）；身份/版本在 `plugin.properties`，代码里不出现：
- 它是**可停用插件**（描述符里 `undeactivatable=false`）：工具渲染在卡片工具行里（见 3.3），
  可随时停用/重载 —— 分类标签不靠声明，能力由它调用的 `register*` 决定；
- `createSpringBootstrap()` 返回 `new SpringBootstrap(this, MemoryCurveStarter.class)`：
  插件有**自己的 Spring 子容器**（`MemoryCurveStarter` 是空 `@SpringBootApplication`，只负责扫描本包），
  不导入平台共享状态存储；状态文件由插件自己读写，跨插件协作只通过通用契约与消息事件；
- 依赖面刻意保持最小：`plugin-api` / `sbp-core` / spring-context / spring-web / slf4j /
  spring-boot-autoconfigure。**没有数据库、没有 MyBatis、没有核心插件的任何依赖。**

### 2.2 插件如何独立管理状态

开关和排期都由记忆曲线插件拥有，平台不解释也不持有 per-entity 状态：

| 状态 | 存放 | 为什么 |
|---|---|---|
| 「这张卡开了记忆曲线吗」 | 插件自己的 `enabled-cards.properties` | 开关由插件专属 API 读写，不与其他插件共享文件或存储契约 |
| 排期（state/ease/intervalDays/dueAt/reps/lapses/learningStep…） | 插件自己的 `scheduling.meta`（JSON 对象，以 `cardId` 为键） | 平台不认识调度语义，也不该认识；插件自己拥有、自己演进（换算法不动平台） |

`SrsService` 通过同容器内的 `EnabledCardsStore` 查询开关，并在启用卡片时创建初始排期；控制器通过本插件端点调用它，不需要平台状态服务回调，也没有跨容器的存储依赖。

### 2.3 调度算法：为「笔记」调过的 SM-2

`SrsService`（431 行）是插件的核心，状态机完全照 Anki：

```
new ──评分──► learning（页面内短周期重现）──毕业──► review
                  ▲                                   │
                  └────── 忘记：relearning（页面内重现）◄──┘
```

与 Anki 的三处**刻意偏差**（都写在类注释里）：

1. **毕业后固定 1 天**，不按 ease 放大间隔（2d→6d→15d…）。理由：这是给「活笔记」用的，不是应试闪卡 —— 每天碰一次足够，间隔爆炸等于从此不再见；
2. ease（1.3 – 3.0）**只塑造曲线形状**（遗忘更快 / 保持率更高），不放大日历间隔；
3. 没有牌组（deck）概念，短期学习在复习页队列中重现，不等待分钟级计时。

评分四档：`1 忘记 / 2 困难 / 3 良好 / 4 简单`。

一个防漂移技巧：**`previewDue()` 在副本上重放 `apply()`** 来算「这次评分后何时再见」，
而不是另写一份间隔公式 —— 预览与真实调度永远一致（分别实现迟早会漂）。

写入方式：内存 `Map` + 每次变更以 JSON 全量重写 `scheduling.meta`。旧 Properties 文件首次读取时会先备份为
`scheduling.meta.properties.bak`（同名备份已存在时追加序号），再迁移为 JSON；JSON 文件无法解析时会保留原文件并报错，避免后续写入覆盖数据。

JSON 中的 `dueAt`、`lastReviewedAt`、`createdAt`、`updatedAt` 按应用运行环境时区保存为 `yyyy年MM月dd日 HH:mm:ss`；读入后仍转换为毫秒值参与调度计算。旧数字 JSON 首次读入时会先备份为 `scheduling.meta.json.bak`（同名备份已存在时追加序号），再迁移为日期字符串。格式精确到秒，毫秒部分会舍弃，备份保留原精度。

### 2.4 端点面（6 个，查询 GET、写入 POST + `@RequestParam`）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/plugins/memory-curve/get-cards?ids=a,b,c` | 一页卡片一次批量取状态（开关 + 排期 + 各评分预览） |
| GET | `/api/plugins/memory-curve/get-card?cardId=` | 单张卡片状态 |
| POST | `/api/plugins/memory-curve/update-state?cardId=&value=` | 插件内开启/关闭卡片记忆曲线 |
| POST | `/api/plugins/memory-curve/review?cardId=` + body `{grade}` | 评分（1..4），返回新排期 |
| GET | `/api/plugins/memory-curve/get-due?limit=` | 到期卡片（只含排期与预览，**不含正文**） |
| POST | `/api/plugins/memory-curve/delete-schedule?cardId=` | 删一条排期（孤儿清理兜底） |

`MemoryCurveController` 挂在 `@RequestMapping("/api/plugins/memory-curve")` 下 ——
**插件自有端点的固定命名空间**，平台据此把它与插件管理端点区分开，
停用插件时只拦前者（`PluginEndpointHandlerInterceptor`）。

### 2.5 跨插件协作：`CardChangedEvent`（唯一一处「知道隔壁发生了什么」）

```
notes 插件：删掉卡片
   └─► 往**主容器** publishEvent(CardChangedEvent(DELETED, cardId))
          └─► SchedulingListener（注册在主容器上的插件监听器）
                 └─► SrsService.forget(cardId)   ← 排期条目一起删，不留孤儿

   注：**发布方目前没有接线** —— notes 侧只注册了 MainEventPublisher，
   CardService.delete 并未真的 publishEvent，所以这条链现在不会触发；
   删卡留下的排期靠下面 ⑤ 复习页的孤儿清理兜底。
```

两个必须记住的机制：

1. **Spring 事件只在发布它的容器内广播**。插件 A 在自己容器里发，插件 B 容器里的监听器收不到
   （实测确认，非推测）。所以约定是：**发布方拿主容器发，订阅方把监听器注册到主容器**
   （`MemoryCurvePlugin.afterPluginBootstrap` 里 `getMainApplicationContext().addApplicationListener(listener)`）。
2. **停止时必须摘掉监听器**（`stop()`）：主容器比插件活得久，留着既泄漏，又会让下次启用变成重复注册
   （同一条删除事件被清两次）。另外，`afterPluginBootstrap` 执行时插件自己的
   `getApplicationContext()` 仍是 null，所以要用回调参数 `ctx` 取 bean。

兜底：**外部绕过 API 删卡片**（直接删 md 文件、或重建库）不会发事件。这类孤儿由界面侧发现 ——
复习页在核心那边查不到某条排期对应的卡片时，顺手调 `forget` 清掉（3.5）。

### 2.6 「不依赖核心插件」的代价与收获

- 收获：核心插件（notes）缺位时它照样能独立启动；两端之间**没有任何契约**；
- 代价：它不知道卡片属于哪篇笔记、正文是什么。所以 `due` 只返回排期，
  正文由**界面侧**经核心的公开 API（`/api/notes/get`、`/api/notes/cards/get`）现拼。

---

## 3. 界面侧实现（qiankun 微应用）

### 3.1 微应用形态与生命周期

`frontend/plugins/memory-curve/index.ts` 导出 `bootstrap / mount / unmount`：

```ts
export async function mount()   { register() }                  // 注册三处插槽
export async function unmount() { unregisterPlugin('memory-curve') }  // 一次全撤
if (!window.__POWERED_BY_QIANKUN__) register()                  // 独立预览：import 即注册
```

宿主停用/重载插件 → qiankun `unmount()` → 注册整体撤走（插槽图标、侧栏入口、`/review` 路由一起消失），
再启用时重新注册，**无需刷新页面**。共享依赖（vue / vue-router / pinia / @thisnode/plugin-api）
由宿主经沙箱 `modules` 注入同一份实例（否则会有两个 Vue、两个注册表）。

### 3.2 三处注册

| 插槽 | 声明 | 表现 |
|---|---|---|
| `registerCardPlugin` | `{ id, title, glyph:'🧠', loadState, isEnabled, toggle, cardComponent }` | 卡片右上角的 🧠 徽标 + 复习浮层 |
| `registerNavEntry` | `{ label:'复习', glyph:'🧠', to:'/review', badge: () => due().length }` | 侧栏入口，带「今天到期 N 张」徽标（3 秒在飞合并，避免重复请求） |
| `registerPluginRoute` | `{ path:'review', name:'review', component: () => import('./ReviewView.vue') }` | `/review` 页面（**核心路由表里不出现这条路径**） |

### 3.3 契约被宿主消费的两处（数据流入口）

```
NotePage 装载一页卡片时：
   for (plugin of activeCardPlugins()) plugin.loadState(ids)   ← 批量取状态（一张卡一个请求 → 一次请求）
NoteCard 渲染卡片时：
   <component :is="plugin.cardComponent" :cardId="card.cardId" />  ← 插件自己的组件
```

也就是说：**宿主只认契约，不认记忆曲线**；`activeCardPlugins()` 已按插件启用状态过滤。

### 3.4 状态与三条写路径

界面侧状态在模块级 `reactive(Map<cardId, {enabled, schedule, gradePreview}>)`：

| 用户动作 | 路径 | 说明 |
|---|---|---|
| 点右上图标或右键卡片菜单切换 | `POST /api/plugins/memory-curve/update-state?cardId=&value=` | 插件写入自己的开关文件；启用时创建「立即到期」排期，界面侧同步本地形态 |
| 评分 | `POST /api/plugins/memory-curve/review` | 用返回的最新排期覆盖本地 |
| 复习页发现孤儿 | `POST /delete-schedule` | 兜底清理（见 3.5） |

`toggle` 与排期操作都走记忆曲线自己的端点；平台只负责插件级启停与端点闸门，不参与卡片状态存储。

### 3.5 复习页（`ReviewView.vue`，440 行）

- 取 `due(50)` 得到排期 → `loadCardContent(cardIds)` 拼正文 → 每次居中呈现一张卡片（可用宽度的 2/3）
  （Markdown 用宿主服务 `host().MarkdownView` 渲染，标记事件用 `host().notify`）；
- **`loadCardContent` 的代价是显式承认的**：列出笔记 → 每 8 篇并发取页面 → 找齐即停，
  最坏情况 N 个请求（N = 笔记数）。换来的是插件之间零契约（见 2.6）；
- **孤儿处理**：`due` 里那些在核心查不到卡片的排期，一律跳过渲染（渲染成空白卡会让人以为复习坏了），
  并顺手 `forget` 掉；界面上如果有孤儿会明确提示；
- 卡片逐张评分（四档按钮标注下次出现方式）；
  短周期卡按队列规则随机插回；只有一张时在当前队列中立即重现；毕业卡移出视图；
- 键盘 `1-4` 作用于最上方卡片；
- **插件被全局停用时**不假装「没有到期的卡片」，而是说明原因并给出到设置页的入口 ——
  这类"安静的误导"比报错更糟。

### 3.6 卡片状态图标（`MemoryCurveTool.vue`）

卡片右上角始终显示状态图标：灰色表示未开启；开启后图标为红色，并在旁边显示下次提醒（如「下次 10 分钟后」或「已到期」）。点击图标或右键菜单都可开启/关闭；评分与复习操作在「复习」页面完成。

---

## 4. 交付与运行形态（产物分离后）

| 产物 | 位置 | 说明 |
|---|---|---|
| 后端 jar | `backend/backend/runtime/plugins/memory-curve.jar` | Maven 在 package 阶段从模块 target 拷入 |
| 界面资源 | `frontend/runtime/plugins/memory-curve/` | 由 vite `dist/` 经交付脚本送进界面侧交付目录；`plugin.js` + `plugin.css` + 动态分片 |
| 运行期提供 | `GET /plugin-assets/memory-curve/plugin.js` | **界面侧静态服务**提供（开发期：宿主 dev server 的中间件从 `frontend/runtime/plugins/` 端出）；闸门在装载器（停用即不装载） |
| 开发期 | `frontend/plugins/memory-curve` dev server: **7112** | 端口约定见 `host/src/plugins/devPorts.ts`；没起则自动回退到交付产物 |

> 排期与卡片开关文件都是**运行期数据**，不在仓库里：`<data-root>/plugins/memory-curve/scheduling.meta` 和 `enabled-cards.properties`。

---

## 5. 一次完整流程（把上面的机制串起来）

```
① 用户右键卡片并选「开启记忆周期」（或点灰色图标）
   POST /api/plugins/memory-curve/update-state?cardId=id&value=true
     → EnabledCardsStore 写入 enabled-cards.properties
     → SrsService.createIfAbsent(id)：写一条 dueAt=now 的排期
   → 卡片徽标变「已到期」，侧栏「复习」徽标 +1

② 用户打开 /review（插件登记的路由）
   GET get-due → 得到排期 → loadCardContent 拼正文（核心公开 API）→ 瀑布流渲染

③ 用户点「良好」
   POST /review?cardId=… {grade:3} → SrsService.apply() 推进状态机 → 全量重写 scheduling.meta
   → 界面侧用返回的新排期覆盖本地 → 短周期卡随机回到当前队列，毕业卡移出视图

④ 用户在笔记里删掉这张卡片
   notes 插件往主容器发 CardChangedEvent(DELETED, id)
     → SchedulingListener（挂在主容器）→ SrsService.forget(id)   ← 排期一起清掉

⑤ 若用户在文件系统里直接删 md（绕过了 API）
   事件不会发 → 排期成为孤儿 → 复习页发现查不到正文 → 调 POST /forget 兜底清理
```

---

## 6. 这套模式的取舍（给插件作者的结论）

1. **业务状态由插件自己管理**：开关和领域数据都写入插件自己的文件；平台只管理插件级启停并拦截停用插件的端点。
2. **不依赖别的插件**要用「界面侧自己拼数据」来换：代价是最坏 N 个请求，收益是两端零契约、
   核心插件缺位也能启动。选择它的前提是数据量小（本地工具）。
3. **跨插件事件必须走主容器**，并且**停止时摘监听器** —— 这两条是踩出来的，不是文档里读来的。
4. **预览与执行共用同一段逻辑**（`apply()` 重放），否则「按钮上写的间隔」迟早和真实调度不一致。
5. **停用/缺数据时要说人话**：插件被停用时复习页明确提示，而不是渲染成「今天没有要复习的卡片」。

---

## 7. 它用到的 plugin-api 契约清单

后端（唯一的 PF4J 扩展点，见 `plugin-api`）：`PluginRegister`（显示名/说明/健康）·
`PropertiesFileUtils`（通用文件工具）· `CardChangedEvent`（跨插件消息）

界面侧：`registerCardPlugin` · `registerNavEntry` · `registerPluginRoute` · `unregisterPlugin` ·
插件自己的 `memoryCurveApi` · `host()`（`notify` / `MarkdownView`）· `http` · `isPluginActive` · `refreshPluginAvailability`

---

## 附：两个已修复的坑（记录以免复发）

1. **DTO 键名改名不同步**：`block → card` 全量改名时，`loadCardContent` 仍在读 `view.block.id`
   （响应类型已写成 `card`），运行期 `TypeError` → 复习页取不到正文（页面显示空白卡）。
   教训：改 DTO 组件名/JSON 键之后，必须 grep **所有消费点的属性访问**，不只 grep 类型声明。
2. **HTTP 动词统一后的消费方遗漏**：`forget` 由 `DELETE` 改为 `POST /delete-schedule`（平台约定：纯查询使用 GET，写操作使用带动作路径的 POST）后，
   界面侧仍用 `http.del` → 405，孤儿清理静默失效（被 `.catch(() => {})` 吞掉）。
   教训：批量改端点形态时，`tools/*.mjs` 与各 `*Api.ts` 要一起过一遍；**静默 catch 会掩盖契约漂移**。
