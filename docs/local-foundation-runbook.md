# 知识库服务基础阶段本地运行手册

本文覆盖 SSPX 登录、角色鉴权、租户隔离、知识库管理，以及版本化文档上传、解析、OCR、预览和人工校正。Elasticsearch、Milvus 检索与手动发布流程在下一阶段接入。

## 1. 前置服务

- JDK 21
- Maven 3.9+
- MySQL 8.x，默认本地端口 `3307`
- Redis，默认本地端口 `6380`
- SSPX 服务，默认地址 `http://127.0.0.1:8080`
- SSPX 应用 444 下已给测试用户配置 `KNOWLEDGE_ADMIN` 或 `KNOWLEDGE_SUPER_ADMIN`
- MinIO、RabbitMQ 与独立 PaddleOCR（可使用仓库内 `compose.knowledge.yml`）

创建独立数据库：

```sql
CREATE DATABASE IF NOT EXISTS order_logistics_knowledge
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

启动时 Flyway 会自动执行 `src/main/resources/db/migration` 下的版本迁移，请不要手工修改已经执行过的迁移文件。

## 2. 本地启动

在知识库服务仓库根目录打开 PowerShell。客户端密钥只在当前终端进程中输入，不要写入配置文件、命令历史或 Git：

```powershell
$env:SPRING_PROFILES_ACTIVE='local'
$env:SSPX_CLIENT_SECRET = Read-Host -MaskInput 'SSPX Client Secret'
$env:KNOWLEDGE_MINIO_ACCESS_KEY = Read-Host 'MinIO Access Key'
$env:KNOWLEDGE_MINIO_SECRET_KEY = Read-Host -MaskInput 'MinIO Secret Key'
$env:KNOWLEDGE_RABBITMQ_PASSWORD = Read-Host -MaskInput 'RabbitMQ Password'
mvn spring-boot:run
```

可选环境变量名称如下，值按本机环境填写：

```text
KNOWLEDGE_SERVER_PORT
KNOWLEDGE_DB_URL
KNOWLEDGE_DB_USERNAME
KNOWLEDGE_DB_PASSWORD
KNOWLEDGE_REDIS_HOST
KNOWLEDGE_REDIS_PORT
SSPX_BASE_URL
SSPX_CLIENT_ID
SSPX_CLIENT_SECRET
SSPX_APPLICATION_ID
KNOWLEDGE_SECURE_COOKIE
KNOWLEDGE_MINIO_ENDPOINT
KNOWLEDGE_MINIO_ACCESS_KEY
KNOWLEDGE_MINIO_SECRET_KEY
KNOWLEDGE_RABBITMQ_HOST
KNOWLEDGE_RABBITMQ_PASSWORD
KNOWLEDGE_OCR_BASE_URL
```

默认健康检查地址：`http://127.0.0.1:8084/actuator/health`。

## 3. Gateway 路由

本地 Gateway 增加一条知识库服务路由，目标描述如下：

```text
Path: /knowledge/**
Rewrite: remove /knowledge prefix
Target: http://127.0.0.1:8084
```

生产或测试环境的目标地址应使用服务发现或对应环境配置，不要把环境专用主机名提交到本仓库。

## 4. PowerShell 接口验证

以下账号密码全部是占位示例。先建立一个能自动保存 Cookie 的 WebSession：

```powershell
$baseUrl = 'http://127.0.0.1:8084'
$webSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
```

### 4.1 获取 CSRF Token

```powershell
$csrf = Invoke-RestMethod `
  -Method Get `
  -Uri "$baseUrl/api/v1/admin/auth/csrf" `
  -WebSession $webSession

$csrfHeaders = @{
  'X-XSRF-TOKEN' = $csrf.data.token
  'X-Request-Id' = [guid]::NewGuid().ToString()
}
```

### 4.2 登录

```powershell
$loginBody = @{
  account = 'demo-account'
  password = 'demo-password'
} | ConvertTo-Json

Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/api/v1/admin/auth/login" `
  -WebSession $webSession `
  -Headers $csrfHeaders `
  -ContentType 'application/json' `
  -Body $loginBody
```

浏览器只会收到 `KB_ADMIN_SESSION` 不透明 HttpOnly Cookie；SSPX access token、refresh token 和客户端密钥不会返回浏览器。

### 4.3 查询当前用户

```powershell
Invoke-RestMethod `
  -Method Get `
  -Uri "$baseUrl/api/v1/admin/auth/me" `
  -WebSession $webSession
```

### 4.4 新建知识库

系统管理员只能把 `{tenantId}` 替换成自己的租户，超级管理员可显式指定其他租户。

```powershell
$createBody = @{
  name = '售后规则'
  description = '退款与换货政策'
} | ConvertTo-Json

Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/api/v1/admin/tenants/1/knowledge-bases" `
  -WebSession $webSession `
  -Headers $csrfHeaders `
  -ContentType 'application/json' `
  -Body $createBody
```

### 4.5 查询知识库列表

```powershell
Invoke-RestMethod `
  -Method Get `
  -Uri "$baseUrl/api/v1/admin/tenants/1/knowledge-bases" `
  -WebSession $webSession `
  -Headers @{ 'X-Request-Id' = [guid]::NewGuid().ToString() }
```

### 4.6 退出

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/api/v1/admin/auth/logout" `
  -WebSession $webSession `
  -Headers $csrfHeaders
```

退出后服务端 Redis 会话被删除，浏览器 Cookie 同时过期。

## 5. 常见错误码

```text
AUTH_REQUIRED                   未登录或会话已过期
AUTH_INVALID                    SSPX 凭据或身份响应无效
AUTH_SERVICE_UNAVAILABLE        SSPX 暂时不可用
KNOWLEDGE_ACCESS_DENIED         没有知识库管理角色或 CSRF 校验失败
TENANT_ACCESS_DENIED            系统管理员访问了其他租户
KNOWLEDGE_BASE_NAME_CONFLICT    同租户知识库名称已存在
KNOWLEDGE_BASE_VERSION_CONFLICT 乐观锁版本冲突，需要刷新后重试
```
