# thisnote Docker 部署

## 镜像说明

- 镜像名：`thisnote:local`
- 运行基础镜像：`eclipse-temurin:21-jre-jammy`，即 **Ubuntu 22.04**
- 单容器内运行：
  - Nginx：容器内 `8080`，对外默认 `8081`
  - Java 后端：容器内仅监听 `127.0.0.1:45917`
  - Graphify CLI：`graphifyy[openai]==0.9.92`
- 前端宿主和全部插件资源已构建进镜像。

## 首次部署

从仓库根目录执行：

```powershell
docker compose up -d --build
```

访问：<http://127.0.0.1:8081>

检查状态：

```powershell
docker compose ps
docker logs -f thisnote
Invoke-RestMethod http://127.0.0.1:8081/api/health/get
```

默认端口 `8081` 可在启动前覆盖：

```powershell
$env:THISNOTE_HTTP_PORT = '8090'
docker compose up -d --build
```

## 数据位置与风险

本部署**不挂载任何数据卷或宿主机目录**。笔记、插件数据、日志全部保存在容器可写层的 `/app/data`：

- 笔记：`/app/data/notes`
- 日志：`/app/data/logs/backend.log`

`docker stop` / `docker start`、`docker compose stop` / `docker compose start` 会保留容器，也就保留数据。

以下操作会删除或替换容器，**未备份时数据会丢失**：

- `docker compose down`
- `docker rm thisnote`
- 重新执行 `docker compose up --build` 且容器被重建
- 删除镜像或清理 Docker 容器数据

## 备份

容器运行时可直接复制出数据：

```powershell
$backup = ".\thisnote-backup-" + (Get-Date -Format 'yyyyMMdd-HHmmss')
docker cp thisnote:/app/data $backup
```

恢复时先启动新容器，再复制数据并修正文件所有者：

```powershell
docker compose up -d --build
docker cp .\thisnote-backup-YYYYMMDD-HHmmss\. thisnote:/app/data
docker exec -u root thisnote chown -R 10001:10001 /app/data
docker compose restart
```

## 更新镜像

更新会重建容器，因此先备份：

```powershell
$backup = ".\thisnote-backup-" + (Get-Date -Format 'yyyyMMdd-HHmmss')
docker cp thisnote:/app/data $backup
docker compose up -d --build
```

需要恢复旧数据时，按上一节复制备份并重启容器。

## 配置

- Compose：`docker-compose.yml`
- Dockerfile：`docker/Dockerfile`
- Nginx：`docker/nginx.conf`
- 启动脚本：`docker/entrypoint.sh`
- 当前停用插件清单：`docker/plugins-disabled.txt`（`baidu-sync`、`guide`）

构建和运行阶段均不执行测试；`mvn package` 使用 `-DskipTests`。