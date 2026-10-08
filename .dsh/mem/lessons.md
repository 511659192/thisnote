# 经验教训（本项目）

> 每轮开工前先读本文件；用户在**任何**纠正之后，把错误模式按「纠正来源 / 事实依据 / 规则」三段补记。
> 同一条教训在本项目重复出现 ≥2 次即升级为硬规则，并写进全局 `~/.dsh/AGENTS.md`。

---

## L1（硬规则 · 已出现 2 次）删除「无调用方」的接口/代码前，必须从端点反向核对每一个调用方

- **纠正来源**：用户实测 `PUT /api/plugins/memory-curve/states/block/<id>?value=true` 返回「接口不存在」——我把这个端点当「零调用」删掉了。此前用户还当场纠正过一次同类误删（`nodeApi.removeTag` 在 `stores/nodes.ts:531` 在用）。**同一模式出现 2 次**。
- **事实依据**：
  - `frontend/plugin-api/src/pluginApi.ts:71` 的 `setState` 打向 `PUT /api/plugins/{pluginId}/states/{entityType}/{entityId}`；
  - 调用方有 `frontend/plugins/memory-curve/index.ts:56`（卡片 🧠 开关）与 `frontend/plugins/favorite/favoriteApi.ts:27`（笔记 ★ 开关）——**同一端点被两个插件共用**；
  - 我当时的判断只 grep 了 `pluginApi.(list|get|health|config)`，没枚举 `setState`，也没从 `/states/` 路径反查，因此漏判；
  - 另一次：只查方法名 `nodeApi.removeTag` 的调用点，漏看了它出现在 `nodes.ts:531`。
- **规则**：删任何端点前跑**双向核对**，两端都为空才能删——
  ① **正查**：端点路径字符串（含 `?query`）与 Java 方法名在前端 `src`、`frontend/plugins/*`、`frontend/host/tools/*.mjs`、`tools/*.py` 中的出现；
  ② **反查**：从端点路径找到封装它的 API 函数（`frontend/plugin-api/src/*.ts`、`frontend/host/src/api/*.ts`、各插件 `*Api.ts`），再枚举**该函数的所有调用点**（含动态 `import('@thisnode/plugin-api').then(m => m.pluginApi.xxx)` 这种写法）。
  跨插件共用的底座端点（`/api/plugins/{id}/states|entities|health`、per-entity 状态面）**默认保留**；批量判断用脚本枚举"封装函数的全部调用点"，不靠肉眼挑方法名。

---

## L2 改中文文本文件不要用 PowerShell 的 Get-Content/Set-Content 做批量替换

- **纠正来源**：我用 `Get-Content -Raw` + `Set-Content -Encoding UTF8` 批量改 8 个脚本里的路径，把文件里所有中文注释变成乱码，只能 `git checkout` 恢复后重做。
- **事实依据**：`Get-Content -Raw` 在本机按 ANSI(GBK) 解码 UTF-8 文件，回写即永久损坏；改用 Python（显式 `encoding="utf-8"`）执行同一批替换后，乱码检查为 0。
- **规则**：批量文本替换一律用 Python `read_text(encoding="utf-8")/write_text`；已损坏则先 `git checkout -- <files>` 再重做，不要在乱码基础上继续编辑。

---

## L3 `mvn -pl <app> -am` 不会重建未被依赖的插件模块

- **纠正来源**：删掉插件里的端点后用 `-pl :thisnode-backend -am` 打包，`plugins/notes.jar` 时间戳未变、被测端点照旧返回 200，白验一轮（之后 `mvn clean package` 才生效）。
- **事实依据**：`-am` 只带「被选中模块所依赖」的模块；notes 不在 app 的依赖里，Maven 会从本地仓库解析旧构件。
- **规则**：**改了插件代码就整仓 `mvn clean package`** 再验证；只有在只改 `backend/app` 自身时才用 `-pl :thisnode-backend -am`。
- **第 3 次复发（本次）**：改完 `plugin-notes` 的 `TagRepository`/`NoteService` 后用 `-pl backend/app -am` 打包，`plugins/notes.jar` 仍是旧构件，
  台账验证与耗时对比全部无效（看到的是旧代码行为），只能整仓重建后重做。
  **加一道机械检查**：验证前先比 `plugins/<id>.jar` 与对应源码目录的 `LastWriteTime`，jar 比源码旧就说明没重建。

---

## L4 审计脚本的正则不能替代「逐条人工核验」

- **纠正来源**：我用启发式脚本判「零调用」时产生过双向误判——既漏了 `pluginApi.setState`，也把仅出现在注释里的 `/aliases` 当成调用方。
- **事实依据**：`audit_removed.py` 对 `/api/nodes/{id}`、`/aliases`、`/api/tags/{id}`、`/api/attachments` 全部报「有调用方」，逐行看后都是 `GET/PATCH/DELETE` 同族路径、注释、或 `POST`/`/**` 的误报。
- **规则**：脚本只用来**缩小范围**；每一条「删/留」的结论都要回到具体文件行确认，并在报告里给出 `文件:行号`。

---

## L7 跑浏览器套件前，先确认「页面实际连的 API 端口」与「后端实例是谁的」

- **纠正来源**：验证冲突保护时跑 `card_save_test.mjs`，第一轮打到一个已死端口（页面 JS 把非 5173/空端口的 API 基址硬编码为 45917，见 `frontend/plugin-api/src/http.ts:19-24`），第二轮 45917 已被用户自己启动的实例占用——套件连人带数据全部打到了**用户的真实库** `~/.thisnode`。
- **事实依据**：SPA 的 API 基址只在 `location.port` 为 5173 或空时取同源，否则硬编码 `http://127.0.0.1:45917`；两次套件的 RUN 后缀（595422/694607）能在不同库的 ops.jsonl 里查到痕迹。
- **规则**：① 用临时数据根起验证实例时，必须让它监听 **45917**（页面才连得上），起之前确认该端口空闲、并弄清原占用进程是谁的；② 套件跑完后按标题后缀查一遍库，把自己造的测试数据清干净（本次用 API trash+purge 清掉了 `repro`）。

## L8 构建验证不能只看过滤后的头部输出，要看尾部与退出码

- **纠正来源**：`mvn clean package` 实际在最后一个模块 repackage 时失败（jar 被运行中的后端进程锁定），但我用 `Select-String -First 40` 过滤输出，BUILD FAILURE 被截掉，误以为构建成功；随后 `maven-jar-plugin` 还把 43MB 的 fat jar 覆盖成了 246KB 的 thin jar。
- **事实依据**：`target/thisnode-backend.jar` 的时间戳与体积暴露了异常；用 `[System.IO.File]::Open(path,'ReadWrite','None')` 锁检查定位到占用进程。
- **规则**：① 构建验证一律看输出**尾部**的 BUILD SUCCESS/FAILURE 与退出码，不过滤头部；② Windows 上 package 前先确认没有进程锁着产物（尤其是用户自己起着的 `java -jar`）；③ 停别人的进程前按 L5 打印 PID/启动时间/命令行核对，只停确认过的那一个。
- **再次复发（本次）**：验证用的临时实例没停干净（`--server.port=45923` 那个），整仓 `clean package` 在 repackage 阶段因"无法重命名 jar"失败，还让主实例处于停着的状态。
  **补一条**：临时实例用完**立刻**停（同一段脚本里用 try/finally 式的收尾），构建失败时先查 `Get-CimInstance Win32_Process` 里还有哪些 `thisnode-backend.jar` 进程。

## L9 隔离验证环境的三个坑：workdir 重定向、脚本位置、请求计数口径

- **纠正来源**：验证「点击列表不再调 /api/plugins」时连续踩了三次环境坑——后台 vite 任务的日志重定向用了相对路径（workdir 在 frontend/host，`.tmp-verify` 不在那儿）直接启动失败；`pnpm dev:host` 写在了子包目录（该脚本只存在于根 package.json）；playwright 计数脚本用 `includes('/api/plugins')` 把收藏/记忆曲线自己的业务端点也算进去，得出 12 次/点击的假阳性。
- **事实依据**：job_output 的 stderr 明确给出 DirectoryNotFoundException 与 ERR_PNPM_RECURSIVE_EXEC_FIRST_FAIL；精确匹配 `u.pathname === '/api/plugins'` 后同场景为 0 次/点击。
- **规则**：① 后台任务的日志重定向一律写**绝对路径**；② 验证脚本要么放进被测包目录（ESM 从脚本位置解析依赖），要么确保 workdir 与脚本位置一致；③ 统计网络请求按 `pathname` 精确匹配，先想清楚要数的是哪个端点；④ 改共享组件的刷新时机前，先 grep 它的全部调用方并读依赖此行为的测试（本次靠这一步提前发现 `plugin_disable_closure_test` 依赖外部停用场景，改法才没有破坏闭环）。

## 本项目的既有事实（便于后续判断，非教训）

- 卡片坐标（x/y/w/h/z/color/collapsed）只存在**卡片 md 的 meta 注释**里；笔记 meta 的 `cards[]` 只有 `{id, file}`（`CardRef`）。因此「列表返回卡片清单」只能是身份，几何与正文属页面级数据。
- `/api/notes/{noteId}/page` 已删除，卡片 API 挂在 `/api/notes/{noteId}/blocks/**`；读接口支持 `?ids=`（省略=整页）。
- 「一篇笔记至少一张卡」的不变量由 `NodeRepository.insert` 建立（**无条件**建首卡，空正文也建）。

---

## L5 停后端前必须确认「当前 PID」，不要凭记忆或宽泛过滤器执行 Stop-Process

- **纠正来源**：本轮核验时所有请求都报 `WinError 10061 目标计算机积极拒绝`，发现刚启动的后端（PID 7056）已被我自己杀掉。
- **事实依据**：启动日志显示 22:01:34 就绪；随后的 `Get-CimInstance ... | Where-Object { CommandLine -match "thisnode-backend.jar" }` + `Stop-Process -Force` 在未确认 PID 的情况下执行，把当时唯一在跑的那个进程杀掉了。
- **规则**：执行 `Stop-Process` 前，先打印候选进程的 `ProcessId` 与 `CreationDate`，确认它就是「运行中的旧实例」；更稳的做法是把启动时拿到的 PID 记下来，只停那一个。停完立刻 `Test-NetConnection 127.0.0.1:45917` 确认端口已释放，再继续。

---

## L6（硬规则 · 已出现 3 次）批量文本替换禁止用 PowerShell，只用 Python 或 write 工具

- **纠正来源**：三批批量替换连续把源码文件写坏——`AttachmentController.java` 里所有 `n` 被替换成 `o`
  （`thisoode.ext.ootes`）、`ContainerController.java` 的 `Container` 变成 `Cootaioer`、
  `SystemController.java` 的构造器参数被叠成 `NoteStore store) {, NoteStore store) {`。
- **事实依据**：根因是 PowerShell 把 `@('a','b')` 这类嵌套数组里的字符串按**字符数组**处理，
  `'nodeIdsOf'` 实际只取到首字符 `n`，于是全文件的 `n` 被替换。同一脚本前一轮还把
  `import ...ext.notes.config.NotesConfig` 误改成 `...notes.notesConfig...`（`config.` 规则命中了 import 行）。
- **规则**：
  ① 批量替换一律用 Python（`str.replace` 或以 `.replace()` 调用的**独立字符串变量**）或 `write` 工具；
  ② PowerShell 只允许 `[string]::Replace($str, $old, $new)`，且 `$old`/`$new` 必须是**独立变量**
     （从 `Import-Csv` 属性读出的更稳），绝不在嵌套数组里内联；
  ③ 涉及字段名时用正则 `(?<!\.)\bname\.`，负向后视可避免命中 `import ...pkg.Name` 这类包路径；
  ④ 每次写盘前做**长度校验**（写入前后字节数相差超过 10% 就拒绝写）。
- **回退**：被写坏且已入库的文件用 `git checkout HEAD -- <path>` 恢复，**注意那是 HEAD 版**（可能是改名前的旧名），
  恢复后要按旧名重新应用改名；未入库的文件直接用 `write` 全量重写。

---

## L10（硬规则）本项目禁止 TDD：先改代码，改完统一跑一次测试

- **纠正来源**：用户明确要求「禁止 tdd 修改后统一测试」——此前我按仓库 AGENTS.md 的 TDD 触发条款，
  在改实现前先写失败测试、再补实现，逐段往返。
- **事实依据**：本轮之前改 `NoteIndex`/`CardCatalog` 时，我是"改一处 → 编译 → 跑全量测试"循环；
  用户要求改为"一个批次的改动全部落盘后，统一跑一次测试"。项目内此前无关于测试时机的条目。
- **规则**：
  ① 本项目一律**先改代码、后测试**：同一批次的所有改动（含配套的测试新增/调整）全部落盘后，
     跑**一次** `mvn test`；
  ② 测试仍是收尾验证手段（证明改对了），**不是**驱动手段：不先写失败的测试用例再补实现；
  ③ 遇到编译错误才中途编译，编译通过不算"验证"；
  ④ 本项为**硬规则**，优先级高于仓库 AGENTS.md 里"TDD 触发"的条款，后续同类纠正也并入本条；
     2026-10-08 已按用户要求升级写入全局 `~/.zcode/AGENTS.md`（替换原「TDD 触发」条目）。
