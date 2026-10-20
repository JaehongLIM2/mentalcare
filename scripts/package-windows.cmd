@echo off
setlocal
rem Builds the portable Windows app-image dist\MentalCare\MentalCare.exe (bundled Java runtime, no console).
rem Frontend build -> Spring Boot jar (scripts\build.cmd) -> jpackage --type app-image.
rem The jpackage input folder holds exactly the two jars; no data, .env, credentials or .run ever goes in.
cd /d "%~dp0.."
set "ROOT=%cd%"
set "APP_VERSION=0.2.0"

if "%JAVA_HOME%"=="" if exist "%USERPROFILE%\tools\jdk-21" set "JAVA_HOME=%USERPROFILE%\tools\jdk-21"
if not exist "%JAVA_HOME%\bin\jpackage.exe" (
  echo jpackage.exe not found under JAVA_HOME ^(%JAVA_HOME%^). A JDK 21 is required.
  exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"

rem The image is replaced as a whole, so it must not be running (the exe would be locked).
powershell -NoProfile -Command "$p = Get-CimInstance Win32_Process -Filter \"Name = 'MentalCare.exe'\" | Where-Object { $_.ExecutablePath -ieq '%ROOT%\dist\MentalCare\MentalCare.exe' }; if ($p) { exit 1 }"
if errorlevel 1 (
  echo dist\MentalCare\MentalCare.exe is running. Stop it first ^(stop script or the in-app button^).
  exit /b 1
)

rem By design nothing personal lives in the image; refuse to replace it if something does.
if exist dist\MentalCare (
  call :find_personal dist\MentalCare && (
    echo dist\MentalCare contains the files above. Move them out before rebuilding.
    exit /b 1
  )
)

call scripts\build.cmd || exit /b 1
if not exist backend\target\mentalcare-launcher.jar (
  echo backend\target\mentalcare-launcher.jar was not built.
  exit /b 1
)

set "INPUT=backend\target\jpackage-input"
if exist "%INPUT%" rmdir /s /q "%INPUT%"
mkdir "%INPUT%" || exit /b 1
copy /y backend\target\mentalcare.jar "%INPUT%\" >nul || exit /b 1
copy /y backend\target\mentalcare-launcher.jar "%INPUT%\" >nul || exit /b 1

if exist dist\MentalCare (
  rmdir /s /q dist\MentalCare
  if exist dist\MentalCare (
    echo Could not remove dist\MentalCare.
    exit /b 1
  )
)
if not exist dist mkdir dist

rem No --add-modules: jpackage's default set is every JDK module (incl. charsets, locale data, mscapi),
rem so the bundled runtime behaves like the JDK the CMD start uses. Only debug info and tools are stripped.
echo Running jpackage...
jpackage --type app-image --name MentalCare --app-version %APP_VERSION% ^
  --vendor MentalCare --description "MentalCare (local)" ^
  --input "%INPUT%" --main-jar mentalcare-launcher.jar ^
  --main-class com.mentalcare.launcher.MentalCareLauncher ^
  --java-options "-Dfile.encoding=UTF-8" ^
  --dest dist || exit /b 1

rem Post-check: no personal data or secrets inside the image or the jars.
set "BAD="
call :find_personal dist\MentalCare && set "BAD=1"
for %%J in (dist\MentalCare\app\mentalcare.jar dist\MentalCare\app\mentalcare-launcher.jar) do (
  jar tf "%%J" | findstr /r /i "\.db$ \.db-wal$ \.env$ /credentials/ /sessions/.*\.md$ \.run/" && set "BAD=1"
)
if defined BAD (
  echo Package check FAILED: the entries above must not be in the image. Do not distribute dist\MentalCare.
  exit /b 1
)

echo.
echo Built: dist\MentalCare\MentalCare.exe
echo Data:  %ROOT%\data ^(shared with the CMD start while the image stays in dist\^)
exit /b 0

rem Prints anything personal under %1 (SQLite files, .env, PID/log, OAuth credential files, data folders);
rem errorlevel 0 when something was found. Wildcards stay outside FOR sets: FOR would expand them in the cwd.
:find_personal
set "FOUND="
for %%E in (db db-wal db-shm db-journal) do where /r "%~1" *.%%E 2>nul && set "FOUND=1"
for %%N in (.env mentalcare.pid mentalcare.log chatgpt-auth.json chatgpt-host.json) do where /r "%~1" %%N 2>nul && set "FOUND=1"
for %%D in (data sessions credentials .run) do for /d /r "%~1" %%X in (%%D) do if exist "%%X\" (echo %%X& set "FOUND=1")
if defined FOUND exit /b 0
exit /b 1
