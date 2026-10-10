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
- **规则**：改后端优先用上面的编译命令拿硬证据；要验证运行时行为就写临时目录里的一次性程序，别为了验证往项目里塞文件。
