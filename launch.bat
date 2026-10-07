@echo off
title Orbit Desktop
if not exist "%JAVA_HOME%\bin\java.exe" (
    if exist "C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot\bin\java.exe" (
        set "JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot"
    )
)
cd /d "%~dp0"
call gradlew.bat :app:run
pause
