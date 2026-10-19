@echo off
rem Starts the Vite dev server on http://127.0.0.1:5173 (proxies /api to the backend on 8080).
cd /d "%~dp0..\frontend"
if not exist node_modules call npm install
call npm run dev
