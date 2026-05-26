@echo off
setlocal enabledelayedexpansion

:: =============================================================================
:: Quant Server Startup Script (Windows)
:: =============================================================================

:: 设置代码页为UTF-8
chcp 65001 >nul 2>&1

:: 脚本所在目录
set "SCRIPT_DIR=%~dp0"
:: 部署根目录
set "DEPLOY_DIR=%SCRIPT_DIR%.."
:: 应用名称
set "APP_NAME=quant-server"
:: JAR文件路径
set "JAR_FILE=%DEPLOY_DIR%\lib\%APP_NAME%.jar"
:: PID文件路径
set "PID_FILE=%DEPLOY_DIR%\.%APP_NAME%.pid"
:: 日志目录
set "LOG_DIR=%DEPLOY_DIR%\logs"
:: 数据目录
set "DATA_DIR=%DEPLOY_DIR%\data"
:: 配置目录
set "CONFIG_DIR=%DEPLOY_DIR%\config"

:: JVM参数（如果未设置）
if "%~JVM_OPTS%"=="" (
    set "JVM_OPTS=-server -Xms512m -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Djava.awt.headless=true -Dfile.encoding=UTF-8"
)

:: 应用参数
if "%~APP_OPTS%"=="" (
    set "APP_OPTS=-Dconfig.file=%CONFIG_DIR% -Dlogback.configurationFile=%CONFIG_DIR%\logback.xml -Dserver.data.dir=%DATA_DIR%"
)

:: 创建必要的目录
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"
if not exist "%DATA_DIR%" mkdir "%DATA_DIR%"

:: 检查JAR文件
if not exist "%JAR_FILE%" (
    echo ❌ Error: JAR file not found: %JAR_FILE%
    echo Please run 'gradlew :ktor-server:prepareDeploy' first
    exit /b 1
)

:: 获取时间戳
goto :eof
call :timestamp
goto :eof

:timestamp
for /f "tokens=2 delims==." %%a in ('wmic os get localdatetime /value') do set "dt=%%a"
set "TIMESTAMP=%dt:~0,4%-%dt:~4,2%-%dt:~6,2% %dt:~8,2%:%dt:~10,2%:%dt:~12,2%"
goto :eof

:: 检查是否已经在运行
:check_running
if exist "%PID_FILE%" (
    set /p PID=<"%PID_FILE%"
    tasklist /FI "PID eq %PID%" 2>nul | findstr "%PID%" >nul
    if !errorlevel! == 0 (
        exit /b 0
    )
)
exit /b 1

:: 启动应用
:start
call :check_running
if !errorlevel! == 0 (
    set /p PID=<"%PID_FILE%"
    echo ⚠️  %APP_NAME% is already running (PID: %PID%)
    exit /b 1
)

echo 🚀 Starting %APP_NAME%...
echo    JAR: %JAR_FILE%
echo    Log: %LOG_DIR%\server.log

:: 启动Java进程
start /B javaw %JVM_OPTS% %APP_OPTS% -jar "%JAR_FILE%" > "%LOG_DIR%\server.log" 2>&1

:: 获取PID并保存
for /f "tokens=2" %%a in ('tasklist /FI "IMAGENAME eq javaw.exe" /FO LIST ^| findstr "PID:"') do (
    set "PID=%%a"
    set "PID=!PID: =!"
    echo !PID! > "%PID_FILE%"
    goto :started
)

:started
timeout /t 3 /nobreak >nul

call :check_running
if !errorlevel! == 0 (
    set /p PID=<"%PID_FILE%"
    echo ✅ %APP_NAME% started successfully (PID: %PID%)
    echo    Check logs: type "%LOG_DIR%\server.log"
) else (
    echo ❌ Failed to start %APP_NAME%
    del "%PID_FILE%" 2>nul
    exit /b 1
)
goto :eof

:: 停止应用
:stop
call :check_running
if not !errorlevel! == 0 (
    echo ⚠️  %APP_NAME% is not running
    del "%PID_FILE%" 2>nul
    goto :eof
)

set /p PID=<"%PID_FILE%"
echo 🛑 Stopping %APP_NAME% (PID: %PID%)...

:: 尝试优雅停止
taskkill /PID %PID% >nul 2>&1

:: 等待进程结束
set "COUNT=0"
:wait_loop
timeout /t 1 /nobreak >nul
set /a COUNT+=1
tasklist /FI "PID eq %PID%" 2>nul | findstr "%PID%" >nul
if !errorlevel! == 0 (
    if !COUNT! lss 30 (
        echo    Waiting... (!COUNT!/30)
        goto :wait_loop
    )
    :: 强制停止
    echo    Force stopping...
    taskkill /F /PID %PID% >nul 2>&1
)

del "%PID_FILE%" 2>nul
echo ✅ %APP_NAME% stopped
goto :eof

:: 重启应用
:restart
call :stop
timeout /t 2 /nobreak >nul
call :start
goto :eof

:: 查看状态
:status
call :check_running
if !errorlevel! == 0 (
    set /p PID=<"%PID_FILE%"
    echo ✅ %APP_NAME% is running (PID: %PID%)
    for /f "tokens=*" %%a in ('powershell -Command "(Get-Process -Id !PID!).WorkingSet64 / 1MB"') do (
        echo    Memory: %%a MB
    )
) else (
    echo ⭕ %APP_NAME% is not running
    del "%PID_FILE%" 2>nul
)
goto :eof

:: 查看日志
:logs
if exist "%LOG_DIR%\server.log" (
    type "%LOG_DIR%\server.log"
) else (
    echo ❌ Log file not found: %LOG_DIR%\server.log
    exit /b 1
)
goto :eof

:: 显示帮助
:help
echo.
echo Quant Server Management Script
echo.
echo Usage: %~nx0 {start^|stop^|restart^|status^|logs^|help}
echo.
echo Commands:
echo     start     Start the server
echo     stop      Stop the server
echo     restart   Restart the server
echo     status    Check server status
echo     logs      View server logs
echo     help      Show this help message
echo.
echo Examples:
echo     %~nx0 start       # Start the server
echo     %~nx0 stop        # Stop the server
echo     %~nx0 restart     # Restart the server
echo.
goto :eof

:: 主逻辑
if "%~1"=="" goto :help
if "%~1"=="start" goto :start
if "%~1"=="stop" goto :stop
if "%~1"=="restart" goto :restart
if "%~1"=="status" goto :status
if "%~1"=="logs" goto :logs
if "%~1"=="help" goto :help
if "%~1"=="--help" goto :help
if "%~1"=="-h" goto :help

echo ❌ Unknown command: %~1
goto :help
