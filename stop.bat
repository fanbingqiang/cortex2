@echo off
chcp 65001 >nul
title Cortex2 - Stop Docker Services

echo ========================================
echo   Stopping Docker services...
echo ========================================
echo.

docker compose down

echo.
echo ========================================
echo   全部服务已停止。
echo.
echo   如需同时删除数据库数据卷（清空所有数据）:
echo     docker compose down -v
echo ========================================
echo.
pause
