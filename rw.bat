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

rem Game folder: beside these files first, then GAME_DIR.
set "GAME="
call :probe "%HERE%"
if not defined GAME if defined GAME_DIR call :probe "%GAME_DIR%"

rem Attaching needs a 64-bit JVM; the game's own jvm64 is the one it is meant to sit next to.
set "JAVA="
if defined GAME if exist "%GAME%\jvm64\bin\java.exe" set "JAVA=%GAME%\jvm64\bin\java.exe"
if not defined JAVA if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA for /f "delims=" %%i in ('where java.exe 2^>nul') do if not defined JAVA set "JAVA=%%i"
if not defined JAVA (
    echo [rw] no java found, put these files inside the game folder or set GAME_DIR.
    pause
    exit /b 1
)

rem Attach to the running game. Exit 3 means a game was found that rw already closed again,
rem so either way the launch below is what brings it back with the agent loaded.
"%JAVA%" -cp "%JAR%" rw
if not errorlevel 1 goto :done
if not defined GAME (
    echo [rw] cannot find the game folder, set GAME_DIR to it and run this again.
    goto :done
)
"%JAVA%" -cp "%JAR%" rw --launch "%GAME%"

:done
pause
exit /b

:probe
if defined GAME exit /b
if exist "%~1\jvm64\bin\java.exe" ( set "GAME=%~1" & exit /b )
if exist "%~1\jvm\bin\java.exe" ( set "GAME=%~1" & exit /b )
exit /b
