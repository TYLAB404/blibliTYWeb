@echo off
title Bili Web Stop
echo ============================================
echo   Bili Web Downloader - Stop
echo ============================================

echo [1/2] Stopping cloudflared tunnel...
taskkill /f /im cloudflared.exe >nul 2>&1 && echo      Tunnel stopped || echo      No tunnel process.

echo [2/2] Stopping Spring Boot app (port 8080)...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8080.*LISTENING"') do (
    taskkill /f /pid %%a >nul 2>&1 && echo      App stopped (PID %%a)
)
echo.
echo Stopped. Double-click start.bat to restart.
echo.
pause
