# memory-curve 插件实现分析（后端 + 界面侧）

> 本文分析 thisnode 插件平台里 **最完整的一个参考实现**：记忆曲线（Anki 式 SM-2 间隔重复）。
> 读它 = 读「一个功能完整的插件该怎么写」。它同时用到了平台提供的几乎所有插件能力：
> 自有端点、自有状态文件、自有页面、per-entity 开关、跨插件事件、卡片工具插槽、侧栏入口与徽标。
>
> 代码位置：后端 `backend/plugins/memory-curve/`（5 个 Java 文件，752 行）·
> 界面侧 `frontend/plugins/memory-curve/`（index.ts / memoryCurveApi.ts / MemoryCurveTool.vue / ReviewView.vue，约 990 行）

---

## 1. 一句话定位

**它把「哪些卡片要复习、什么时候复习、掌握到什么程度」这件事，完整地放在插件自己的地盘里。**
平台与核心笔记插件都不认识「复习」这个概念，笔记数据也不经过它。

| 关注点 | 归属 | 存放位置 |
|---|---|---|
| 卡片是否开启记忆曲线（开关） | **平台通用状态面**（per-entity 状态） | `<data-root>/plugin-data.json`（平台 KV） |
| 排期（状态/难度/间隔/到期时间…） | **插件自己** | `<data-root>/plugins/memory-curve/scheduling.meta` |
| 卡片正文与标题 | **不归它管**（不读笔记数据） | 由界面侧经核心的公开 API 现拼 |

---

## 2. 后端实现

### 2.1 装载与生命周期（PF4J / SBP）

```
plugins-dist/memory-curve.jar
  ├── plugin.properties          ← PF4J 的 PropertiesPluginDescriptorFinder 读它
  │     plugin.id=memory-curve
  │     plugin.class=com.thisnode.ext.memorycurve.MemoryCurvePlugin
  │     plugin.version=@project.version@   ← Maven 过滤注入（版本单源，父 pom 写一次）
  └── 类 + 空 Spring 容器入口
```

- `MemoryCurvePlugin extends SpringBootPlugin`（PF4J/SBP 的插件基类）；身份/版本在 `plugin.properties`，代码里不出现：
- 它是**可停用插件**（描述符里 `undeactivatable=false`）：工具渲染在卡片工具行里（见 3.3），
  可随时停用/重载 —— 分类标签不靠声明，能力由它调用的 `register*` 决定；
- `createSpringBootstrap()` 返回 `new SpringBootstrap(this, MemoryCurveStarter.class).importBean(PluginDataRepository.class)`：
  插件有**自己的 Spring 子容器**（`MemoryCurveStarter` 是空 `@SpringBootApplication`，只负责扫描本包），
  平台只把 `PluginDataRepository`（状态 KV）显式搭桥进来 —— 插件与平台之间只有 `plugin-api` 这层契约；
- 依赖面刻意保持最小：`plugin-api` / `sbp-core` / spring-context / spring-web / slf4j /
  spring-boot-autoconfigure。**没有数据库、没有 MyBatis、没有核心插件的任何依赖。**

### 2.2 状态为什么分两处存

这是本插件最有意思的设计决策：

| 状态 | 存放 | 为什么 |
|---|---|---|
| 「这张卡开了记忆曲线吗」 | 平台 KV（`plugin-data.json`，分层 JSON），经通用状态面读写（键只有 pluginId + 卡片 id，没有 entityType 那一层） | 这是**平台级的 per-entity 状态**：平台要据此做批量查询、停用闸门、以及「状态变化时通知插件」；所有插件共用一套，无需各自造轮子 |
| 排期（state/ease/intervalDays/dueAt/reps/lapses/learningStep…） | 插件自己的 `scheduling.meta`（Properties 格式 `cardId|字段 = 值`（键就是卡片 id，与 entityType 无关）） | 平台不认识调度语义，也不该认识；插件自己拥有、自己演进（换算法不动平台） |

`SrsService` 里有一处很讲究的细节：**「是否开启」直接读 `PluginDataRepository`，而不是注入 `PluginStateService`**。原因是后者在状态翻转时会回调插件的 `onEntityStateChanged` —— 插件正是订阅方，形成字段级循环依赖，Spring 装配不出来。直接读 KV（同一份底座数据的插件侧半边）绕开了这个环。

### 2.3 调度算法：为「笔记」调过的 SM-2

`SrsService`（431 行）是插件的核心，状态机完全照 Anki：

```
new ──评分──► learning（学习步 1 分钟 → 10 分钟）──毕业──► review
                  ▲                                        │
                  └──────── 忘记：relearning（10 分钟）◄────┘
```

与 Anki 的三处**刻意偏差**（都写在类注释里）：

1. **毕业后固定 1 天**，不按 ease 放大间隔（2d→6d→15d…）。理由：这是给「活笔记」用的，不是应试闪卡 —— 每天碰一次足够，间隔爆炸等于从此不再见；
2. ease（1.3 – 3.0）**只塑造曲线形状**（遗忘更快 / 保持率更高），不放大日历间隔；
3. 没有牌组（deck）概念，学习步固定为 1m/10m。

评分四档：`1 忘记 / 2 困难 / 3 良好 / 4 简单`。

一个防漂移技巧：**`previewDue()` 在副本上重放 `apply()`** 来算「这次评分后何时再见」，
而不是另写一份间隔公式 —— 预览与真实调度永远一致（分别实现迟早会漂）。

写入方式：内存 `Map` + 每次变更全量重写 `scheduling.meta`（单文件、小体量，本地工具足够；
坏文件按空库处理并告警，不让插件起不来）。

### 2.4 端点面（5 个，全 GET/POST + `@RequestParam`）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/plugins/memory-curve/cards?ids=a,b,c` | 一页卡片一次批量取状态（开关 + 排期 + 各评分预览） |
| GET | `/api/plugins/memory-curve/card?cardId=` | 单张卡片状态 |
| POST | `/api/plugins/memory-curve/review?cardId=` + body `{grade}` | 评分（1..4），返回新排期 |
| GET | `/api/plugins/memory-curve/due?limit=` | 到期卡片（只含排期与预览，**不含正文**） |
| POST | `/api/plugins/memory-curve/forget?cardId=` | 删一条排期（孤儿清理兜底） |

`MemoryCurveController` 挂在 `@RequestMapping("/api/plugins/memory-curve")` 下 ——
**插件自有端点的固定命名空间**，平台据此把它与管理面
（`/api/plugins/detail|states|state|entities|health|active|reload`）区分开，
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
  正文由**界面侧**经核心的公开 API（`/api/notes`、`/api/notes/cards`）现拼。

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
   <component :is="plugin.cardComponent" :cardId="card.id" />  ← 插件自己的组件
```

也就是说：**宿主只认契约，不认记忆曲线**；`activeCardPlugins()` 已按插件启用状态过滤。

### 3.4 状态与三条写路径

界面侧状态在模块级 `reactive(Map<cardId, {enabled, schedule, gradePreview}>)`：

| 用户动作 | 路径 | 说明 |
|---|---|---|
| 点 🧠 开启/关闭 | `pluginApi.setState('memory-curve','block',id,next)` → 平台通用状态面 | 平台落盘后回调插件的 `onEntityStateChanged` → 后端建「立即到期」排期；界面侧同步本地形态 |
| 评分 | `POST /api/plugins/memory-curve/review` | 用返回的最新排期覆盖本地 |
| 复习页发现孤儿 | `POST /forget` | 兜底清理（见 3.5） |

注意 `toggle` 用的是**平台通用状态面**而不是插件自己的端点 —— 于是「开关」这件事在平台上
对所有插件是同构的（批量查询、停用闸门、状态通知都复用同一套），插件不用自己实现一遍。

### 3.5 复习页（`ReviewView.vue`，440 行）

- 取 `due(50)` 得到排期 → `loadCardContent(cardIds)` 拼正文 → 多列瀑布流渲染完整内容
  （Markdown 用宿主服务 `host().MarkdownView` 渲染，标记事件用 `host().notify`）；
- **`loadCardContent` 的代价是显式承认的**：列出笔记 → 每 8 篇并发取页面 → 找齐即停，
  最坏情况 N 个请求（N = 笔记数）。换来的是插件之间零契约（见 2.6）；
- **孤儿处理**：`due` 里那些在核心查不到卡片的排期，一律跳过渲染（渲染成空白卡会让人以为复习坏了），
  并顺手 `forget` 掉；界面上如果有孤儿会明确提示；
- 评分两条路：单张（四档按钮，按钮上标注**这次评分后的下次间隔**）与勾选批量；
  评分成功后从视图移除（它已进入自己的时间周期，最快 1 分钟后重现）；
- 键盘 `1-4` 作用于最上方卡片；
- **插件被全局停用时**不假装「没有到期的卡片」，而是说明原因并给出到设置页的入口 ——
  这类"安静的误导"比报错更糟。

### 3.6 卡片徽标（`MemoryCurveTool.vue`，302 行）

三种形态一眼可辨：未开启 → 悬停才淡入的 🧠 占位；已开启 → **常驻徽标**
（`已到期` 高亮 / `N 天后` / `N 分钟后` 倒计时）；点击 → 复习浮层
（四档评分 + 各档间隔预览 + 难度 ease + 遗忘曲线 `R(t)=e^(-t/S)` 的 SVG，S 取当前间隔天数）。

---

## 4. 交付与运行形态（产物分离后）

| 产物 | 位置 | 说明 |
|---|---|---|
| 后端 jar | `backend/plugins-dist/memory-curve.jar` | Maven 在 package 阶段从模块 target 拷入 |
| 界面资源 | `frontend/plugin-assets/memory-curve/` | 由 vite `dist/` 经交付脚本送进界面侧交付目录；`plugin.js` + `plugin.css` + 动态分片 |
| 运行期提供 | `GET /plugin-assets/memory-curve/plugin.js` | **界面侧静态服务**提供（开发期：宿主 dev server 的中间件从 `frontend/plugin-assets/` 端出）；闸门在装载器（停用即不装载） |
| 开发期 | `frontend/plugins/memory-curve` dev server: **7112** | 端口约定见 `host/src/plugins/devPorts.ts`；没起则自动回退到交付产物 |

> 排期文件是**运行期数据**，不在仓库里：`<data-root>/plugins/memory-curve/scheduling.meta`。

---

## 5. 一次完整流程（把上面的机制串起来）

```
① 用户点某张卡片的 🧠
   pluginApi.setState(memory-curve, block, id, true)
     → 平台写 plugin-data.json
     → 平台回调 MemoryCurvePlugin.onEntityStateChanged(block, id, true)
        → SrsService.createIfAbsent(id)：写一条 dueAt=now 的排期
   → 卡片徽标变「已到期」，侧栏「复习」徽标 +1

② 用户打开 /review（插件登记的路由）
   GET due → 得到排期 → loadCardContent 拼正文（核心公开 API）→ 瀑布流渲染

③ 用户点「良好」
   POST /review?cardId=… {grade:3} → SrsService.apply() 推进状态机 → 全量重写 scheduling.meta
   → 界面侧用返回的新排期覆盖本地 → 该卡从视图移除（1 分钟后或明天再见）

④ 用户在笔记里删掉这张卡片
   notes 插件往主容器发 CardChangedEvent(DELETED, id)
     → SchedulingListener（挂在主容器）→ SrsService.forget(id)   ← 排期一起清掉

⑤ 若用户在文件系统里直接删 md（绕过了 API）
   事件不会发 → 排期成为孤儿 → 复习页发现查不到正文 → 调 POST /forget 兜底清理
```

---

## 6. 这套模式的取舍（给插件作者的结论）

1. **开关放平台、领域状态放自己**：per-entity 开关是平台能力（批量、闸门、通知都复用），
   领域数据是插件隐私（算法演进不惊动平台）。
2. **不依赖别的插件**要用「界面侧自己拼数据」来换：代价是最坏 N 个请求，收益是两端零契约、
   核心插件缺位也能启动。选择它的前提是数据量小（本地工具）。
3. **跨插件事件必须走主容器**，并且**停止时摘监听器** —— 这两条是踩出来的，不是文档里读来的。
4. **预览与执行共用同一段逻辑**（`apply()` 重放），否则「按钮上写的间隔」迟早和真实调度不一致。
5. **停用/缺数据时要说人话**：插件被停用时复习页明确提示，而不是渲染成「今天没有要复习的卡片」。

---

## 7. 它用到的 plugin-api 契约清单

后端（唯一的 PF4J 扩展点，见 `plugin-api`）：`PluginRegister`（显示名/说明/健康/状态回调都在它一个接口上）·
`PluginDataRepository`（状态 KV）·
`CardChangedEvent`（跨插件事件）· `PluginDataRepository.importBean` 搭桥

界面侧：`registerCardPlugin` · `registerNavEntry` · `registerPluginRoute` · `unregisterPlugin` ·
`pluginApi.setState` · `host()`（`notify` / `MarkdownView`）· `http` · `isPluginActive` · `refreshPluginAvailability`

---

## 附：两个已修复的坑（记录以免复发）

1. **DTO 键名改名不同步**：`block → card` 全量改名时，`loadCardContent` 仍在读 `view.block.id`
   （响应类型已写成 `card`），运行期 `TypeError` → 复习页取不到正文（页面显示空白卡）。
   教训：改 DTO 组件名/JSON 键之后，必须 grep **所有消费点的属性访问**，不只 grep 类型声明。
2. **HTTP 动词统一后的消费方遗漏**：`forget` 由 `DELETE` 改为 `POST`（平台约定：读 GET、写 POST+动作路径）后，
   界面侧仍用 `http.del` → 405，孤儿清理静默失效（被 `.catch(() => {})` 吞掉）。
   教训：批量改端点形态时，`tools/*.mjs` 与各 `*Api.ts` 要一起过一遍；**静默 catch 会掩盖契约漂移**。
