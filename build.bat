@echo off
setlocal
set "OUT=%~dp0build"
set "CLASSES=%OUT%\classes"

rem javac and jar come from the game's own JDK, or from JAVA_HOME, or from PATH.
set "JAVA="
if defined GAME_DIR if exist "%GAME_DIR%\jvm64\bin\javac.exe" set "JAVA=%GAME_DIR%\jvm64\bin\"
if not defined JAVA if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JAVA=%JAVA_HOME%\bin\"
if not defined JAVA for /f "delims=" %%i in ('where javac.exe 2^>nul') do if not defined JAVA set "JAVA=%%~dpi"
if not defined JAVA ( echo cannot find javac, set GAME_DIR to the game folder & exit /b 1 )

if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%CLASSES%"

pushd "%~dp0"
set STAMP=local
for /f "delims=" %%i in ('git rev-parse --short HEAD 2^>nul') do set STAMP=%%i
popd
>"%CLASSES%\build-stamp.txt" echo %STAMP%

"%JAVA%javac" --release 8 -d "%CLASSES%" "%~dp0src\rw.java"
if errorlevel 1 ( echo COMPILE FAILED & exit /b 1 )
"%JAVA%jar" cfm "%OUT%\rw.jar" "%~dp0MANIFEST.MF" -C "%CLASSES%" .
if errorlevel 1 ( echo PACKAGE FAILED, is rw.jar still open? & exit /b 1 )
echo Built %OUT%\rw.jar
