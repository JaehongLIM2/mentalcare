@echo off
rem Double-click: starts MentalCare in the background (no console) and opens the browser when ready.
start "" /min powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0scripts\start-background.ps1"
