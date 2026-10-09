# Prepare the plugin development layout: backend/plugins-dev/<id>/
#
#   backend/plugins-dev/memory-curve/
#     plugin.properties                       <- copy of target/classes/plugin.properties (version filtered)
#     classes -> backend/plugins/memory-curve/target/classes   (junction / symlink)
#
# Why this shape (PF4J development mode, learned the hard way):
#   * The DESCRIPTOR is looked up by PropertiesPluginDescriptorFinder in the PLUGIN DIRECTORY ROOT
#     (plugin.properties). Putting it inside classes/ makes PF4J report
#     "No PluginDescriptorFinder for plugin 'plugins-dev/xxx'".
#   * CLASSES and resources are looked up in `classes/` (PF4J default layout), so pointing that
#     junction at Maven's target/classes is enough - no classes-directories config needed.
#
#   pwsh backend\tools\dev_plugins.ps1            # create (idempotent)
#   pwsh backend\tools\dev_plugins.ps1 -Remove    # delete
#
# Then start in development mode (workdir = backend/, so plugins-dev resolves there):
#   cd backend; java -jar app/target/thisnode-backend.jar --spring.profiles.active=dev
#
# Editing a plugin then needs just (Maven 父工程在 backend/，从那里执行):
#   cd backend; mvn -pl :plugin-<id> -am compile -Dthisnode.skip-frontend=true     # -am: 父工程构件不在本地仓库
#   curl -X POST http://127.0.0.1:45917/api/plugins/<id>/reload       # or the button in Settings -> Plugins

param([switch]$Remove)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)   # 仓库根（脚本现居 backend/tools/，plugins-dev 由应用工作目录决定在根）
$devRoot = Join-Path $root 'backend/plugins-dev'
$plugins = @('notes', 'favorite', 'memory-curve')

# Delete a link itself, never the directory it points at (Remove-Item would recurse into the target).
function Remove-Link($path) {
    if (Test-Path $path) { (Get-Item $path).Delete() }
}

if ($Remove) {
    foreach ($p in $plugins) {
        $dir = Join-Path $devRoot $p
        Remove-Link (Join-Path $dir 'classes')
        if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
        Write-Host "removed $dir"
    }
    if ((Test-Path $devRoot) -and -not (Get-ChildItem $devRoot)) { Remove-Item $devRoot }
    return
}

New-Item -ItemType Directory -Force -Path $devRoot | Out-Null
foreach ($p in $plugins) {
    $moduleDir = Join-Path $root "backend/plugins/$p"
    $classes = Join-Path $moduleDir 'target\classes'
    $descriptor = Join-Path $classes 'plugin.properties'
    $dir = Join-Path $devRoot $p

    if (-not (Test-Path $descriptor)) {
        Write-Warning "$p is not built yet ($descriptor missing); run (from backend/): mvn -pl :plugin-$p compile"
        continue
    }

    # A previous run may have left a junction where the directory should be: replace it.
    if ((Test-Path $dir) -and (Get-Item $dir).LinkType) { Remove-Link $dir }

    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    Copy-Item $descriptor (Join-Path $dir 'plugin.properties') -Force

    $link = Join-Path $dir 'classes'
    Remove-Link $link
    if ($IsWindows -or $env:OS -eq 'Windows_NT') {
        New-Item -ItemType Junction -Path $link -Target $classes | Out-Null
    } else {
        New-Item -ItemType SymbolicLink -Path $link -Target $classes | Out-Null
    }
    Write-Host "ready: $dir (plugin.properties + classes -> $classes)"
}

Write-Host ''
Write-Host 'start in development mode:'
Write-Host '  java -jar backend/app/target/thisnode-backend.jar --spring.profiles.active=dev,mysql'
