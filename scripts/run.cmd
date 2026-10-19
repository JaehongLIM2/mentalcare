@echo off
setlocal
rem Runs the packaged jar (frontend included) on http://127.0.0.1:8080 with variables from .env.
cd /d "%~dp0.."

if exist .env (
  for /f "usebackq eol=# tokens=1,* delims==" %%A in (".env") do (
    if not "%%A"=="" set "%%A=%%B"
  )
)
if "%JAVA_HOME%"=="" if exist "%USERPROFILE%\tools\jdk-21" set "JAVA_HOME=%USERPROFILE%\tools\jdk-21"
set "PATH=%JAVA_HOME%\bin;%PATH%"
if "%MENTALCARE_DATA_DIR%"=="" set "MENTALCARE_DATA_DIR=%cd%\data"

java -jar backend\target\mentalcare.jar
