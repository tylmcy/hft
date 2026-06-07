@echo off
REM ============================================================
REM HFT Trading System - 停止脚本 (Windows)
REM ============================================================
REM 用法:
REM   stop.bat [选项]
REM 选项:
REM   -f, --force   强制杀死进程
REM   -h, --help    显示帮助信息
REM ============================================================

setlocal enabledelayedexpansion

REM 默认配置
set FORCE_KILL=false

REM 解析命令行参数
:parse_args
if "%~1"=="" goto :end_parse
if /i "%~1"=="-f" (
    set FORCE_KILL=true
    shift
    goto :parse_args
)
if /i "%~1"=="--force" (
    set FORCE_KILL=true
    shift
    goto :parse_args
)
if /i "%~1"=="-h" goto :show_help
if /i "%~1"=="--help" goto :show_help
echo 未知参数: %~1
exit /b 1

:show_help
echo 用法: %~nx0 [选项]
echo 选项:
echo   -f, --force   强制杀死进程
echo   -h, --help    显示帮助信息
exit /b 0

:end_parse

set PID_FILE=hft-trading.pid

echo [信息] 正在停止 HFT Trading System...

if exist "%PID_FILE%" (
    set /p PID=<%PID_FILE%

    REM 检查进程是否存在
    tasklist /FI "PID eq !PID!" 2>nul | find "java.exe" >nul
    if !errorlevel!==0 (
        echo 发现运行中的进程 (PID: !PID!)

        if "%FORCE_KILL%"=="true" (
            echo [提示] 强制杀死进程...
            taskkill /F /PID !PID! >nul 2>&1
        ) else (
            echo 发送终止信号...
            taskkill /PID !PID! >nul 2>&1

            REM 等待进程结束 (最多 10 秒)
            set COUNT=0
            :wait_loop
            set /a COUNT+=1
            if !COUNT! gtr 10 goto :force_kill
            timeout /t 1 /nobreak >nul
            tasklist /FI "PID eq !PID!" 2>nul | find "java.exe" >nul
            if !errorlevel!==0 goto :wait_loop
            goto :stopped

            :force_kill
            echo [警告] 进程未响应，强制杀死...
            taskkill /F /PID !PID! >nul 2>&1
        )

        :stopped
        del %PID_FILE%
        echo [成功] 应用已停止
    ) else (
        echo [提示] PID 文件存在但进程已不存在，清理 PID 文件
        del %PID_FILE%
    )
) else (
    echo [提示] 未找到 PID 文件，尝试通过窗口标题查找...

    REM 通过窗口标题查找并杀死进程
    if "%FORCE_KILL%"=="true" (
        taskkill /F /FI "WINDOWTITLE eq HFT-Trading-System*" >nul 2>&1
    ) else (
        taskkill /FI "WINDOWTITLE eq HFT-Trading-System*" >nul 2>&1
        timeout /t 3 /nobreak >nul
        tasklist /FI "WINDOWTITLE eq HFT-Trading-System*" 2>nul | find "java.exe" >nul
        if !errorlevel!==0 (
            echo [警告] 进程未响应，强制杀死...
            taskkill /F /FI "WINDOWTITLE eq HFT-Trading-System*" >nul 2>&1
        )
    )

    if errorlevel 1 (
        echo [提示] 未找到运行中的进程
    ) else (
        echo [成功] 应用已停止
    )
)

endlocal
