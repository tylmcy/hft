@echo off
REM ============================================================
REM HFT Trading System - 状态检查脚本 (Windows)
REM ============================================================
REM 用法:
REM   status.bat
REM ============================================================

setlocal enabledelayedexpansion

set PID_FILE=hft-trading.pid
set LOG_DIR=logs

echo ==========================================
echo HFT Trading System - 状态检查
echo ==========================================
echo.

REM 检查 PID 文件
if exist "%PID_FILE%" (
    set /p PID=<%PID_FILE%

    REM 检查进程是否存在
    tasklist /FI "PID eq !PID!" 2>nul | find "java.exe" >nul
    if !errorlevel!==0 (
        echo 状态:     [运行中]
        echo PID:      !PID!
        echo.

        REM 显示进程信息
        echo 进程信息:
        tasklist /FI "PID eq !PID!" /V 2>nul | find "java.exe"

        echo.
        echo 端口监听:
        netstat -ano | find ":8080" | find "LISTENING"

        echo.
        echo 健康检查:
        curl -s -o nul -w "HTTP 端点: %%{http_code}\n" http://localhost:8080/actuator/health 2>nul
        if errorlevel 1 (
            echo HTTP 端点: 无响应
        )
    ) else (
        echo 状态:     [已停止] ^(PID 文件存在但进程已终止^)
        echo PID:      !PID! ^(无效^)
        echo.
        echo 提示: 运行 'del %PID_FILE%' 清理残留文件
    )
) else (
    echo 状态:     [未运行]
    echo PID:      无

    echo.
    echo 检查孤儿进程:
    tasklist /FI "WINDOWTITLE eq HFT-Trading-System*" 2>nul | find "java.exe" >nul
    if !errorlevel!==0 (
        echo [警告] 发现孤儿进程
        tasklist /FI "WINDOWTITLE eq HFT-Trading-System*" 2>nul | find "java.exe"
        echo 运行 'stop.bat' 来停止它们
    ) else (
        echo 未发现孤儿进程
    )
)

echo.
echo 日志文件:
if exist "%LOG_DIR%" (
    if exist "%LOG_DIR%\app.log" (
        for %%F in ("%LOG_DIR%\app.log") do echo   app.log: %%~zF 字节

        echo.
        echo 最近日志 ^(最后 5 行^):
        powershell -Command "Get-Content '%LOG_DIR%\app.log' -Tail 5" 2>nul
    ) else (
        echo   app.log: 不存在
    )
) else (
    echo   日志目录不存在
)

echo.
echo ==========================================

endlocal
