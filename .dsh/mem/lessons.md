# 本项目经验教训（thisnote）

> 循环约定：每轮开工前先读本文件；用户每次纠正后把「错误模式」追加进来；同一教训重复 ≥2 次升级为硬规则。
> 每条三段：**纠正来源** / **事实依据**（可核验）/ **规则**。

## 1. 工作区可能停在「半成品重构」上，别默认能编译

- **纠正来源**：用户要求 `com.thisnode.plugin.PluginDataStore#read` 返回 `Map<String,Map<String,Boolean>>`（去掉包一层的领域类型）。
- **事实依据**（2026-10-10 核验）：`git diff` 显示 `backend/app/.../plugin/PluginDataStore.java` 是未提交的重构中——
  四层 `StateFile`（`pluginId → entityType → entityId → key → value`）已被删成两级，但 line 77 留下的
  `record PluginData(Map<String,Boolean> memoryCurve, Map<String,Boolean> favorite) {}` 是空壳，line 84–174 仍在调
  `PluginData.empty()/plugins()/enabled()/openEntities()/drop()/entities()/enabledIds()`，全仓库无这些方法 ⇒ 编译必失败。
- **规则**：动 `backend/` 任何文件前，先 `git status --short` + `git diff -- <file>` 看它是否处于未完成状态；
  见到「调用了不存在的方法」这类断口，先判断这是上一轮未完成的重构，再决定是补完还是照用户新指示改写——
  不要按文件当前内容臆测它「本来就是这样设计的」。

## 2. 本项目后端验证方式（已跑通，可复用）

- **纠正来源**：无（首次建立基线）。
- **事实依据**：`java` 不在 PATH，`JAVA_HOME=C:\Users\51165\.jdks\temurin-21.0.9`；Maven = `D:\devTools\apache-maven-3.6.3`，
  本地仓库 `D:/maven`（`~/.m2/settings.xml` 配 aliyun 镜像）。
  - 全仓编译：在 `backend/` 跑 `mvn -o -B -DskipTests clean compile` → 8 个模块 `BUILD SUCCESS`（本次实测）。
  - 单点行为验证（不新增项目文件）：临时目录写一个单文件源码，用
    `java -cp "backend/app/target/classes;backend/plugin-api/target/classes;<fastjson2 jar>;<slf4j-api jar>" <file>.java` 直接跑。
- **规则**：上述编译命令仅在用户明确要求编译时运行；代码修改完成后，未经用户明确要求，不运行测试、不执行部署或编译。运行时验证也仅在用户明确要求时进行。

## 3. 启动应用的标准姿势（2026-10-10 本机实测）

- **纠正来源**：用户要求「启动应用」。
- **事实依据**：
  - 打包：`cd backend && mvn -o -B -DskipTests package` → 产出 `backend/app/target/thisnode-backend.jar`，
    并由各插件模块的 `maven-antrun-plugin` 把插件 jar 拷进 `backend/plugins-dist/`（那目录 gitignore，是交付物）。
  - 启动（默认模式：插件从 `plugins-dist/*.jar` 装载，5 个全上）：**工作目录必须是 `backend/`**
    （`sbp.plugins-root: plugins-dist` 是相对路径），命令 `"$env:JAVA_HOME\bin\java.exe" -jar app/target/thisnode-backend.jar`
    → 监听 `127.0.0.1:45917`（`application.yml` 的 `server.address`）。
  - dev 模式（插件从 `target/classes` 装载，可热重载）另需 `pwsh backend\tools\dev_plugins.ps1`，
    且只覆盖 notes / favorite / memory-curve 三个插件（search、fuzzy-search 不在其中）——本次未实测。
  - 前端一整套（6 个 vite）用 `cd frontend && pnpm dev` 起，它执行 `pnpm -r --parallel run dev`：
    **host 5173 + 插件微应用 7111 favorite / 7112 memory-curve / 7113 notes / 7114 search / 7115 fuzzy-search**
    （端口表见 `frontend/host/src/plugins/devPorts.ts`）。
  - vite 只绑 `::1`：`127.0.0.1:5173` 连不上，要用 `localhost` / `[::1]`；宿主 `/api` 代理到 45917。
    插件 dev server 没起时宿主自动回退到 `frontend/plugin-assets/<id>/`（宿主 vite 中间件端出）。
  - 运行中的服务是**独立于会话存活**的（实测 12:16 起的 6 个 vite 活到 15:00 仍在），
    所以重启前务必先枚举端口，别以为上一轮的进程已经没了。
  - 端到端验证：`GET /api/plugins/states?pluginId=<id>&ids=a,b`（参数名是 **ids**，不是 entityIds）、
    `/api/plugins/entities`、`/api/plugins/state`；UI 侧可用 `node --input-type=module -e` 在 `frontend/host/`
    下跑 playwright（裸 import 按 cwd 解析），无头打开 `http://localhost:5173` 数 `.note-row` 与 console 错误。
- **规则**：启动后端一律 `cd backend` + `"$env:JAVA_HOME\bin\java.exe" -jar app/target/thisnode-backend.jar`；
  动手前先确认 `backend/app/target/thisnode-backend.jar` 存在（`mvn clean` 会删掉它，`.run.log` 里那条
  `Unable to access jarfile` 就是这么来的）。

## 4. 下「某服务没在跑」这种结论前，先把端口范围枚举干净（第 1 次）

- **纠正来源**：我向用户报告「插件微应用的 dev server 没起，界面走 `plugin-assets` 回退」——**结论错误**。
- **事实依据**（2026-10-10 核验）：当时我只 `netstat` 过滤了 `45917|5173|5174|5175`，漏掉了插件真正用的
  `7111–7115`；实际 12:16 起的 6 个 vite（host 5173 + 5 个插件 7111–7115）全都在跑，
  浏览器实测的请求来源端口就是 `5173,7111,7112,7113,7114,7115`，没有一个走回退产物。
- **规则**：判断"某个服务/端口在不在跑"时，用 `netstat -ano | Select-String "LISTENING"` 按**端口正则整段扫**
  （本项目要覆盖 `45917|51[7-9][0-9]|711[0-9]`），并把 PID 映射回命令行核对归属；
  不要只探自己记得的那两个端口就下结论。杀掉别人（用户/上一轮会话）留下的进程前，也先核对命令行属于本项目。

## 5. 这个工程用户会同时用 IDE 改：报「file changed」就重读，别按记忆重放

- **纠正来源**：本轮编辑 `ThisnodeConfig.java` 被拒（`file changed since it was read`）。
- **事实依据**：用户同时在 IDE 里把 `PluginEndpointGate` 改名成 `PluginEndpointHandlerInterceptor`
  （文件时间戳 15:16:17；我先前读到的 `PluginEndpointGate` 已不存在，`PluginController`/`ThisnodeConfig` 都换了名）。
- **规则**：多文件改名这类批量操作要**逐个文件先读再改**；遇到 file changed / not found 就重读该文件、
  确认它是否也被用户改过，不要按记忆重放替换；**也不去顺手修用户改名留下的旧引用**（在报告里提，不代改）。

## 6. 写「某改动带来 X 效果」前必须实测，并先确认测量方法本身可信（第 1 次）

- **纠正来源**：我一度要把「health 移到宿主后 Tomcat 一起来就能答、比插件就绪早约 2s」当既成事实写进报告；
  实测发现我的脚本有两处问题——PowerShell 是 **5.1**（没有 `-SkipHttpErrorCheck`），而且我把循环**起点**时间戳
  当成了响应时刻，前两次测量都没测到真值。
- **事实依据**：最终用**应用日志**判定 —— PID 84172：`Tomcat started` 15:34:46.144、
  **首个请求（GET /api/health）15:34:46.442 → 200**、`[SBP] 5 plugins are started` 15:34:48.057
  ⇒ 宿主比插件就绪**早 1.6s** 就能应答。
- **规则**：① 结论要挂在能被定位的证据上，优先用**被测进程自己的日志时间戳**，而不是调用方脚本的计时；
  ② 脚本报出异常值（如 5ms 就 200）先怀疑脚本；③ 判 HTTP 状态要从 `$_.Exception.Response.StatusCode` 取，
  且 `Invoke-WebRequest` 非 2xx 会抛异常，"ERR" 分不清是连接失败还是 404。

## 7. 当前接口 / 命名事实（2026-10-10 这轮改完，后续按此为准）

- 探活：`GET /api/health` 由**宿主**提供 —— `backend/app/src/main/java/com/thisnode/api/HealthController.java`
  （notes 的 `SystemController` 已删；响应字段与路径跟原来一致）。
- notes 写接口：`POST /api/notes/update`（原 `/patch`）、`POST /api/notes/delete`（原方法名 `purge`，URL 未变）。
  审计日志的动作名仍是 `"purge"`（ops.jsonl 里已有历史值，故意不改）。
- 契约类型：`com.thisnode.plugin.api.PluginDataRepository`（接口）+ `com.thisnode.plugin.PluginDataRepository`（实现），
  原 `PluginDataStore`；`PluginRegister.CONTRACT_VERSION` 仍为 `4.0.0`（本轮明确选择不升）。
- 运行中实例（截至本轮）：后端 = 后台作业 `pwsh-691` / java PID 84172；前端 = 15:00 起的 `pnpm dev`
  （宿主 5173 + 插件 7111–7115），前端改名靠 vite HMR 生效，未重启。
- **规则**：重新打包 app jar 前必须先停后端，否则 `spring-boot:repackage` 会因 Windows 文件锁失败
  （`Unable to rename 'thisnode-backend.jar' to 'thisnode-backend.jar.original'`，本轮实际踩到）。
- `backend/tools/` 现在**只有 `dev_plugins.ps1`**（dev 模式的 `plugins-dev/` 由它建立，
  `application-dev.yml:9` 明确引用 ⇒ 承重、不能删）。原来的 4 个 `.py` 维护脚本
  （`vault_test` / `cleanup_empty_blocks` / `cleanup_test_notes` / `reset_demo`）2026-10-10 按用户决定删除
  —— 它们调的 `/api/notes/{id}/blocks`、`DELETE /api/notes/{id}?hard=true`、`/api/notes/counts`、
  `/api/maintenance/*`、`POST /api/notes/daily` 在当前后端**全部不存在**（已 grep 核实）。
  连带的后果：以后清测试垃圾笔记要手工做或重写脚本。

## 8. 项目阶段：开发版本，不考虑兼容性（用户 2026-10-10 明确）

- **纠正来源**：用户要求把「现在是开发版本、不考虑兼容性」记进项目本地 `AGENTS.md`。
- **事实依据**：已新建项目根 `D:\projects\thisnote\AGENTS.md`（此前根目录只有 `.gitignore`，没有这个文件）。
- **规则**：改 HTTP 接口路径与语义、`plugin-api` 契约类型/方法名、落盘格式（`plugin-data.json` / `ops.jsonl`）、
  前端动作名、本仓一次性脚本时，**不再以"向后兼容"为由保留旧形态**：不写兼容层/重载别名、不写数据迁移脚本、
  不为了"标记断裂"而递增版本号。这一条**推翻我本轮基于兼容性给出的那条建议**
  （当时我建议不改 `PluginDataStore` 的名字，理由正是"契约二进制断裂、而 `plugin.requires` 是下界拦不住旧 jar"）。
  仍然要给影响面清单（谁会被打破、要同步重建/重跑什么）。

## 9. 用户正在 IDE 里改代码时，重打包会把半成品编进 jar（第 1 次）

- **纠正来源**：无（本轮自己造成的后果，事后自查）。
- **事实依据**：15:58 我跑 `mvn -o -B "-Dmaven.test.skip=true" package` 时，用户正把 `NoteRepository` 的依赖
  从 `NotesConfig` 换成 `NoteFilePaths`：主代码能编译，但 `NotesConfig` 里没有 `NoteFilePaths` 这个 bean、
  全仓也没有 `new NoteFilePaths(...)` ⇒ 新 `plugins-dist/notes.jar` 装载后
  `[SBP] 4 plugins are started, 1 failed` + `No qualifying bean of type ... NoteFilePaths`，notes 全部端点 404
  （上一个 jar 是好的）。同一次 `mvn package`（带测试）也红：10 个测试文件仍 `Mockito.mock(NotesConfig.class)`
  并传给要 `NoteFilePaths` 的构造器。
- **规则**：① 重打包/重启前先看工作区是不是"别人正在改的中途"（`git status` + 最近几分钟被改的文件）；
  ② 打完包**必须读启动日志确认 `[SBP] N plugins are started, 0 failed`**，不能只看 `BUILD SUCCESS`；
  ③ 报告里把"我的改动"与"工作区在途状态导致的失败"分开写，别含糊过去；
  ④ PowerShell 里 `-Dmaven.test.skip=true` 要加引号，否则被拆成 `.test.skip=true` 当成生命周期阶段报错。

## 10. 别把工作区的断口默认说成"用户在改"（第 1 次）

- **纠正来源**：我连续几轮把 notes 测试源码编不过（`NotesConfig` → `NoteFilePaths`）报告成"你在途的重构，我没插手"；
  用户回：「你来执行 我没有改」。
- **事实依据**：那 10 个测试文件确实只是**遗留断口**（`NoteRepository` 构造器要 `NoteFilePaths`，测试还 `mock(NotesConfig.class)`），
  按 `new NoteFilePaths(root)` 改完 + 顺手删掉随之无用的 7 个 `import org.mockito.Mockito;`，`mvn -o -B test` 立刻
  `Tests run: 38 ... BUILD SUCCESS`（宿主 5 个也绿）。
- **规则**：发现工作区编不过时，**先问一句"这是你在改吗"再决定要不要动手**，不要直接归因给用户；
  报告里也只写事实（哪一行、什么错误），不写"这是你的在途改动"这类归因。
  另外：`pwsh` 里 `mvn … 2>&1 | Select-String …` 有时会把 stderr 当成 NativeCommandError，让整条命令以 exit=1 结束，
  即使 `BUILD SUCCESS` —— 判断成败请看输出里的 `BUILD SUCCESS/FAILURE` 与 `Tests run`，或显式读 `$LASTEXITCODE`。

## 11. Vue 模板**属性值内**的注释不能含直引号（我自己造的回归，第 1 次）

- **纠正来源**：无（我造成、由真实页面验证抓到）。
- **事实依据**：`NoteView.vue` 的 `@create-note="…"` 属性值里，我写注释 `// resolveStub 在后端就是"有就返回、没有就建"…`，
  其中的直引号 `"` 把属性提前闭合 → `frontend/.dev.log`：
  `[vite] Internal server error: Error parsing JavaScript expression: Unexpected token (4:37)  File: .../NoteView.vue:367:27`；
  `GET http://[::1]:7113/src/views/NoteView.vue` 返回 **500**、notes 微应用挂载失败、页面 **0 行笔记**。
  改成「有就返回、没有就建」（全角括号）后该模块 **200**，页面恢复 6 行、0 console error。
- **规则**：改 Vue 模板属性内的表达式/注释时，**禁用直引号与反引号**（用「」或去掉）；
  改完必须 ①直接请求该模块确认 200（`http://[::1]:7113/src/...`）②跑真实页面检查（行数 > 0 且 0 console error）。
  **后端/构建绿灯 ≠ 前端没问题**：模板编译错误只在请求该模块时才暴露。

## 12. 从数组里取"某一项"前，先确认拿到的是单项还是数组（第 2 次栽在"读数"上）

- **纠正来源**：无（自查）。
- **事实依据**：核标签计数时写 `$base = (Get-Tags | Where-Object { $_.name -eq '设计' })`，脚本打印 `count=13` ——
  13 其实是**整个标签数组的长度**（那一次 `GET /api/tags` 共 13 条），不是 `设计` 的计数。
  dump 原始 JSON 得 `{"id":"设计","name":"设计","count":1}`，与 `/api/notes` 现算的次数完全一致 ⇒ 产品逻辑正确，是我读数错。
  同一轮另一处错：用 `Post-Json` 调了 `GET /api/notes/detail`，拿到 405（脚本继续跑、打印空值），差点当成"改名没生效"。
- **规则**：拿接口值做判断时 **dump 原始 JSON**（不要经过 `-f` / 字符串拼接 / `$x.count`），或 `| Select-Object -First 1` 明确取单项；
  调接口前核对 **HTTP 方法**；结论落笔前用第二个独立来源（如从 `/api/notes` 现算）交叉核对。
