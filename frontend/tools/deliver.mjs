#!/usr/bin/env node
/**
 * 界面侧交付脚本：把某个插件界面包构建出的 `dist/` 同步到运行期交付目录
 * `backend/plugin-assets/<id>/`（`/plugin-assets` 从那里端出）。
 *
 * <p><b>为什么交付动作在界面侧</b>：后端 pom 不该知道界面侧的路径、构建方式与产物形态。
 * 界面侧自己产、自己送 —— 数据流单向：`frontend/plugins/<id>/dist` →（本脚本）→ 交付目录。
 * 后端只负责自己的 jar（`mvn package` 拷 `plugins-dist/<id>.jar`）。
 *
 * <p>先清后拷：界面资源名带 hash，只覆盖不清理会把历史分片留在交付目录里。
 *
 * <p><b>统一术语</b>（全仓一份口径）：
 * <ul>
 *   <li><b>后端</b> = `backend/`（jar、API、交付目录）；</li>
 *   <li><b>界面侧</b> = `frontend/`（此前叫「前端」）；</li>
 *   <li><b>界面资源</b> = 界面侧构建产物（此前叫「前端产物」）；</li>
 *   <li><b>产物分离</b> = 界面资源不进 jar，由 `/plugin-assets` 从磁盘端出；</li>
 *   <li><b>交付目录</b> = `backend/plugins-dist/`（jar）+ `backend/plugin-assets/`（界面资源）。</li>
 * </ul>
 *
 * <p>用法：`node tools/deliver.mjs <pluginId>`（各插件 package.json 的 build 脚本已接上，
 * 所以 `pnpm -r build` 一条命令 = 构建 + 交付）。也可不带参数：同步所有存在的 dist。
 */
import { cpSync, existsSync, mkdirSync, readdirSync, rmSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))          // frontend/tools
const frontendRoot = resolve(here, '..')                       // frontend
const repoRoot = resolve(frontendRoot, '..')                   // 仓库根
const targetRoot = join(repoRoot, 'backend', 'plugin-assets')

const arg = process.argv[2]
const ids = arg
  ? [arg]
  : readdirSync(join(frontendRoot, 'plugins')).filter((name) =>
      statSync(join(frontendRoot, 'plugins', name)).isDirectory() &&
      existsSync(join(frontendRoot, 'plugins', name, 'dist')))

let delivered = 0
for (const id of ids) {
  const src = join(frontendRoot, 'plugins', id, 'dist')
  if (!existsSync(src)) {
    console.warn(`[deliver] 跳过 ${id}：没有 ${src}（先构建）`)
    continue
  }
  const dest = join(targetRoot, id)
  rmSync(dest, { recursive: true, force: true })
  mkdirSync(dest, { recursive: true })
  cpSync(src, dest, { recursive: true })
  console.log(`[deliver] ${id}: dist/ → backend/plugin-assets/${id}/`)
  delivered++
}
console.log(`[deliver] 完成：${delivered} 个插件`)
