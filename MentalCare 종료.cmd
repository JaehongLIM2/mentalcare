@echo off
rem Double-click: stops only the MentalCare backend started on this PC (graceful shutdown, then force).
start "" /min powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0scripts\stop.ps1"
