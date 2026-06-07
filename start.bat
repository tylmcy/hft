@echo off
REM ============================================================
REM HFT Trading System - 启动脚本 (Windows)
REM ============================================================
REM 用法:
REM   start.bat [选项]
REM 选项:
REM   -m, --memory SIZE   设置内存大小 (默认: 2g)
REM   -p, --profile NAME  Spring Profile (默认: prod)
REM   -d, --debug         启用远程调试 (端口 5005)
REM   -h, --help          显示帮助信息
REM ============================================================

setlocal enabledelayedexpansion

REM 默认配置
set MEMORY_SIZE=2g
set SPRING_PROFILE=prod
set DEBUG_MODE=false
set DEBUG_PORT=5005

REM 解析命令行参数
:parse_args
if "%~1"=="" goto :end_parse
if /i "%~1"=="-m" (
    set MEMORY_SIZE=%~2
    shift
    shift
    goto :parse_args
)
if /i "%~1"=="--memory" (
    set MEMORY_SIZE=%~2
    shift
    shift
    goto :parse_args
)
if /i "%~1"=="-p" (
    set SPRING_PROFILE=%~2
    shift
    shift
    goto :parse_args
)
if /i "%~1"=="--profile" (
    set SPRING_PROFILE=%~2
    shift
    shift
    goto :parse_args
)
if /i "%~1"=="-d" (
    set DEBUG_MODE=true
    shift
    goto :parse_args
)
if /i "%~1"=="--debug" (
    set DEBUG_MODE=true
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
echo   -m, --memory SIZE   设置内存大小 (默认: 2g)
echo   -p, --profile NAME  Spring Profile (默认: prod)
echo   -d, --debug         启用远程调试 (端口 5005)
echo   -h, --help          显示帮助信息
exit /b 0

:end_parse

REM 检查 Java 是否存在
where java >nul 2>&1
if errorlevel 1 (
    echo [错误] 未找到 Java，请先安装 Java 17 或更高版本
    exit /b 1
)

REM 显示 Java 版本
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    echo Java 版本: %%v
    goto :java_version_done
)
:java_version_done

REM JAR 文件路径
set JAR_FILE=target\hft-trading-1.0.0-SNAPSHOT.jar
set PID_FILE=hft-trading.pid
set LOG_DIR=logs

REM 检查是否已在运行
if exist "%PID_FILE%" (
    set /p OLD_PID=<%PID_FILE%
    tasklist /FI "PID eq !OLD_PID!" 2>nul | find "java.exe" >nul
    if !errorlevel!==0 (
        echo [警告] 应用已在运行中 (PID: !OLD_PID!)
        echo 如需重启，请先运行: stop.bat
        exit /b 1
    ) else (
        echo [提示] 发现残留 PID 文件，清理中...
        del %PID_FILE%
    )
)

REM 检查 JAR 文件是否存在
if not exist "%JAR_FILE%" (
    echo [提示] JAR 文件不存在，正在构建...
    where mvn >nul 2>&1
    if errorlevel 1 (
        echo [错误] 未找到 Maven，无法自动构建
        echo 请手动运行: mvn clean package -DskipTests
        exit /b 1
    )
    call mvn clean package -DskipTests
    if errorlevel 1 (
        echo [错误] 构建失败
        exit /b 1
    )
)

REM 创建日志目录
if not exist "%LOG_DIR%" mkdir %LOG_DIR%

REM JVM 参数 - Chronicle Queue 需要
set JVM_OPTS=--add-exports java.base/jdk.internal.ref=ALL-UNNAMED --add-exports java.base/sun.nio.ch=ALL-UNNAMED --add-exports java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED

REM 内存配置
set MEMORY_OPTS=-Xms%MEMORY_SIZE% -Xmx%MEMORY_SIZE%

REM GC 配置
set GC_OPTS=-XX:+UseZGC -XX:+ZGenerational

REM 调试选项
set DEBUG_OPTS=
if "%DEBUG_MODE%"=="true" (
    set DEBUG_OPTS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:%DEBUG_PORT%
    echo [提示] 远程调试已启用，端口: %DEBUG_PORT%
)

REM 应用参数
set APP_OPTS=--spring.profiles.active=%SPRING_PROFILE%

echo.
echo ==========================================
echo 启动配置:
echo   内存: %MEMORY_SIZE%
echo   Profile: %SPRING_PROFILE%
echo   JAR: %JAR_FILE%
echo ==========================================
echo.

REM 启动应用
echo [信息] 正在启动 HFT Trading System...

REM 使用 start 命令在新窗口中启动，并记录 PID
start "HFT-Trading-System" /min java %JVM_OPTS% %MEMORY_OPTS% %GC_OPTS% %DEBUG_OPTS% -jar %JAR_FILE% %APP_OPTS%

REM 等待进程启动
timeout /t 2 /nobreak >nul

REM 查找 Java 进程 PID
for /f "tokens=2" %%p in ('tasklist /FI "WINDOWTITLE eq HFT-Trading-System*" ^| find "java.exe"') do (
    set NEW_PID=%%p
)

if defined NEW_PID (
    echo %NEW_PID% > %PID_FILE%
    echo [成功] 应用启动成功!
    echo   PID: %NEW_PID%
    echo   日志: %LOG_DIR%\app.log
    echo   访问: http://localhost:8080/
) else (
    echo [警告] 无法确定 PID，但应用可能已启动
    echo   日志: %LOG_DIR%\app.log
    echo   访问: http://localhost:8080/
)

endlocal
