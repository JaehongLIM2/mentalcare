@echo off
setlocal
rem Starts the Spring Boot backend on http://127.0.0.1:8080 with the variables from .env (if present).
cd /d "%~dp0.."

if exist .env (
  for /f "usebackq eol=# tokens=1,* delims==" %%A in (".env") do (
    if not "%%A"=="" set "%%A=%%B"
  )
)

if "%JAVA_HOME%"=="" if exist "%USERPROFILE%\tools\jdk-21" set "JAVA_HOME=%USERPROFILE%\tools\jdk-21"
if "%JAVA_HOME%"=="" (
  echo JAVA_HOME is not set and no JDK was found in %USERPROFILE%\tools\jdk-21.
  exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"

set "MVN=mvn"
where mvn >nul 2>nul || if exist "%USERPROFILE%\tools\maven\bin\mvn.cmd" set "MVN=%USERPROFILE%\tools\maven\bin\mvn.cmd"

if "%MENTALCARE_DATA_DIR%"=="" set "MENTALCARE_DATA_DIR=%cd%\data"

cd backend
call "%MVN%" -q spring-boot:run
