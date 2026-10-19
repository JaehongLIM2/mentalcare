@echo off
setlocal
rem Builds the frontend into the backend's static resources and packages one runnable jar.
cd /d "%~dp0.."

if "%JAVA_HOME%"=="" if exist "%USERPROFILE%\tools\jdk-21" set "JAVA_HOME=%USERPROFILE%\tools\jdk-21"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "MVN=mvn"
where mvn >nul 2>nul || if exist "%USERPROFILE%\tools\maven\bin\mvn.cmd" set "MVN=%USERPROFILE%\tools\maven\bin\mvn.cmd"

cd frontend
if not exist node_modules call npm install
call npm run build || exit /b 1
cd ..\backend
rem clean: target\classes\static would otherwise keep every older frontend bundle inside the jar
call "%MVN%" -q -DskipTests clean package || exit /b 1
echo.
echo Built: backend\target\mentalcare.jar
echo Run:   scripts\run.cmd
