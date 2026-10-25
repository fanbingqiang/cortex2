@echo off
chcp 65001 >nul
title Cortex2 - Docker 一键启动
setlocal

echo ========================================
echo   Cortex2 - Docker 一键启动
echo ========================================
echo.

:: 0. 检查 .env 配置文件
if not exist ".env" (
    echo [错误] 未找到 .env 配置文件！
    echo   请先创建 .env 文件，至少包含：
    echo     MYSQL_PASSWORD=你的数据库密码
    echo     LLM_API_KEY=你的 DeepSeek API Key
    echo.
    pause
    exit /b 1
)
echo [0/4] .env 配置已找到
echo.

:: 1. 检查 Docker 是否运行
echo [1/4] 检查 Docker...
docker info >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo.
    echo   [错误] Docker 未运行！
    echo   请先手动启动 Docker Desktop，等待右下角显示 Running 后再运行本脚本。
    echo.
    pause
    exit /b 1
)
echo   Docker 已就绪
echo.

:: 2. 检查后端 JAR 构建产物（Docker 打包 Java 镜像需要）
echo [2/4] 检查后端构建产物...
if not exist "target\cortex-0.0.1-SNAPSHOT.jar" (
    echo   [提示] 未找到 JAR，先执行 Maven 打包（需要 JDK 17 + Maven）...
    call mvn clean package -DskipTests
    if %ERRORLEVEL% neq 0 (
        echo   [错误] Maven 打包失败，无法构建 Java 镜像！
        pause
        exit /b 1
    )
)
echo   JAR 已就绪
echo.

:: 3. 构建镜像（首次较慢，之后秒级）
echo [3/4] 构建 Docker 镜像（首次约 5-10 分钟，请耐心等待）...
echo.
docker compose build
if %ERRORLEVEL% neq 0 (
    echo   [错误] 镜像构建失败！
    pause
    exit /b 1
)
echo.

:: 4. 启动全部服务
echo [4/4] 启动全部服务...
docker compose up -d
if %ERRORLEVEL% neq 0 (
    echo   [错误] 服务启动失败！
    pause
    exit /b 1
)
echo.

:: 等待服务就绪
echo 等待服务就绪...
timeout /t 5 /nobreak >nul

echo.
echo ========================================
echo   启动完成！
echo.
echo   前端页面:   http://localhost:8081/lab/
echo   后台管理:   http://localhost:8081/lab/#/admin
echo                 账号 admin / 密码 admin123
echo   Python AI:  http://localhost:8001/docs
echo.
echo   常用命令:
echo     docker compose ps            查看服务状态
echo     docker compose logs -f       查看实时日志
echo     docker compose down          停止全部服务（或运行 stop.bat）
echo     docker compose down -v       停止并清空数据库数据
echo ========================================
echo.
pause
