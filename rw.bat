@echo off
setlocal

for %%i in ("%~dp0.") do set "HERE=%%~fi"
set "JAR=%HERE%\rw.jar"
if not exist "%JAR%" if exist "%HERE%\build\rw.jar" set "JAR=%HERE%\build\rw.jar"
if not exist "%JAR%" (
    echo [rw] rw.jar is missing, keep rw.bat next to rw.jar or run build.bat first.
    pause
    exit /b 1
)

rem Game folder: beside these files first, otherwise the running game is asked for its own
rem path, so the two files can be unpacked anywhere.
set "GAME="
call :probe "%HERE%"
if not defined GAME call :where_is_running

rem Attaching needs a 64-bit JVM; the jvm fallback can only relaunch, never attach.
set "JAVA="
if defined GAME if exist "%GAME%\jvm64\bin\java.exe" set "JAVA=%GAME%\jvm64\bin\java.exe"
if not defined JAVA if defined GAME if exist "%GAME%\jvm\bin\java.exe" set "JAVA=%GAME%\jvm\bin\java.exe"
if not defined JAVA if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA for /f "delims=" %%i in ('where java.exe 2^>nul') do if not defined JAVA set "JAVA=%%i"
if not defined JAVA (
    echo [rw] no java found to run with.
    pause
    exit /b 1
)

rem --wait keeps this window open until the game shows up.
"%JAVA%" -cp "%JAR%" rw --wait "%GAME%"
pause
exit /b

:probe
if defined GAME exit /b
if exist "%~1\jvm64\bin\java.exe" ( set "GAME=%~1" & exit /b )
if exist "%~1\jvm\bin\java.exe" ( set "GAME=%~1" & exit /b )
exit /b

:where_is_running
echo [rw] waiting for the game to start...
for /f "usebackq delims=" %%p in (`powershell -NoProfile -Command "while ($true) { $q = (Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -match '\\jvm(64)?\\bin\\java(w)?.exe$' } | Select-Object -First 1).ExecutablePath; if ($q) { $q; break }; Start-Sleep -Seconds 1 }" 2^>nul`) do (
    for %%d in ("%%~dpp\..\..") do call :probe "%%~fd"
)
exit /b
