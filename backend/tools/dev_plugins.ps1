# 建立/修复 dev 模式插件目录（幂等，可反复运行）。
#
# 用法：backend/ 下执行   powershell -File tools\dev_plugins.ps1
#
# 产物（application-dev.yml 约定的 PF4J 开发模式布局）：
#   plugins-dev/<plugin.id>/
#     ├── plugin.properties   <- 从 <module>/target/classes/plugin.properties 拷贝（已由 Maven 过滤版本）
#     └── classes             <- 目录联接 -> <module>/target/classes（IDEA/Maven 的编译产物）
#
# 于是 dev profile 下 SBP 直接加载 target/classes：改插件代码 Build 后「重载」即生效，
# 源码与运行字节码同步，IDEA 断点可正常绑定。

$ErrorActionPreference = 'Stop'
$backend = Split-Path -Parent $PSScriptRoot
$devRoot = Join-Path $backend 'plugins-dev'
New-Item -ItemType Directory -Force -Path $devRoot | Out-Null

# plugin.id -> Maven 模块目录
$plugins = [ordered]@{
    'notes'        = 'plugins/notes'
    'ai'           = 'plugins/ai'
    'favorite'     = 'plugins/favorite'
    'memory-cycle' = 'plugins/memory-cycle'
    'baidu-sync'   = 'plugins/baidu-sync'
    'card-importer' = 'plugins/card-importer'
    'attachment-importer' = 'plugins/attachment-importer'
    'state-reset' = 'plugins/state-reset'
    'graphify' = 'plugins/graphify'
    'tags' = 'plugins/tags'
    'ai-summary' = 'plugins/ai-summary'
    'ai-title' = 'plugins/ai-title'
    'guide' = 'plugins/guide'
}

foreach ($id in $plugins.Keys) {
    $classesSrc = Join-Path $backend "$($plugins[$id])\target\classes"
    $descriptor = Join-Path $classesSrc 'plugin.properties'
    if (-not (Test-Path $descriptor)) {
        throw "缺少 $descriptor —— 先在 backend/ 下执行 mvn compile 构建插件模块"
    }

    $pluginDir = Join-Path $devRoot $id
    New-Item -ItemType Directory -Force -Path $pluginDir | Out-Null

    # 描述符在插件目录根（PF4J 开发模式默认布局）
    Copy-Item $descriptor (Join-Path $pluginDir 'plugin.properties') -Force

    # classes 联接已存在则摘掉重建，保证指向有效（Junction 删除只移除联接本身，不动 target/classes）
    $link = Join-Path $pluginDir 'classes'
    if (Test-Path $link) { (Get-Item $link).Delete() }
    New-Item -ItemType Junction -Path $link -Target $classesSrc | Out-Null

    # 过滤校验：若描述符里还是 Maven 占位符，版本校验会出问题
    if ((Get-Content (Join-Path $pluginDir 'plugin.properties') -Raw) -match '@project\.') {
        Write-Warning "$id 的 plugin.version 仍是占位符：执行 mvn -pl $($plugins[$id]) process-resources 后重跑本脚本"
    }
    Write-Host "OK  $id -> $($plugins[$id])\target\classes"
}

Write-Host ""
Write-Host "plugins-dev 就绪：$devRoot"
