@echo off
title Bili Web Launcher
cd /d "%~dp0"

echo ============================================
echo   Bili Web Downloader - One-click Start
echo ============================================
echo.

REM ---------- 1. Start Spring Boot app ----------
echo [1/3] Checking app on port 8080...
netstat -ano | findstr ":8080.*LISTENING" >nul 2>&1
if not errorlevel 1 (
    echo      App already running, skip.
    goto app_ok
)
echo [1/3] Starting Spring Boot app...
start "BiliWeb-App" cmd /k "cd /d "%~dp0" && mvn spring-boot:run"
echo      Waiting for port 8080...
:wait_app
netstat -ano | findstr ":8080.*LISTENING" >nul 2>&1
if errorlevel 1 (
    timeout /t 2 /nobreak >nul
    goto wait_app
)
echo      App ready (port 8080).
:app_ok
echo.

REM ---------- 2. Start cloudflared tunnel ----------
echo [2/3] Checking cloudflared...
tasklist | findstr /i "cloudflared" >nul 2>&1
if not errorlevel 1 (
    echo      Tunnel already running, skip.
    goto tunnel_ok
)
set TOKEN_FILE=config\tunnel-token.txt
if not exist "%TOKEN_FILE%" (
    echo  [ERROR] token file not found: %TOKEN_FILE%
    echo          Save your cloudflared token to config\tunnel-token.txt
    pause
    exit /b 1
)
set /p TOKEN=<"%TOKEN_FILE%"
echo [2/3] Starting cloudflared tunnel...
start "BiliWeb-Tunnel" "C:\Program Files (x86)\cloudflared\cloudflared.exe" tunnel run --token "%TOKEN%"
echo      Tunnel starting...
:tunnel_ok
echo.

REM ---------- 3. Open browser ----------
echo [3/3] Opening browser...
start "" "https://blibli.tylab.shop"
echo.
echo ============================================
echo   Done!
echo   Local:  http://localhost:8080
echo   Public: https://blibli.tylab.shop
echo   Keep App and Tunnel windows open.
echo ============================================
echo.
pause
