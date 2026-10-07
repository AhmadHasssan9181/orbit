@echo off
title Orbit
set "JAVA_HOME=G:\Programs\Android Studio\jbr"
cd /d "%~dp0"
call gradlew.bat :app:run
pause
