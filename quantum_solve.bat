@echo off
setlocal
title LOOM - quantum policy (QUBO + QAOA in Qiskit)
cd /d "%~dp0"
rem Solves LOOM's profile choice for every FPS goal and heat band with QAOA (Qiskit),
rem checks every answer by brute force, and writes app\src\main\assets\loom_policy.json.
rem Afterwards run build_loom.bat so the phone gets the new table.

set "PY="
where py >nul 2>&1 && set "PY=py -3"
if not defined PY where python >nul 2>&1 && set "PY=python"
if not defined PY (
  echo [ERROR] Python 3 not found. Install it from python.org ^(tick "Add to PATH"^) and run this again.
  goto :end
)

echo [1/3] Installing Qiskit (first time only) ...
%PY% -m pip install --quiet --disable-pip-version-check -r quantum\requirements.txt
if errorlevel 1 (
  echo [ERROR] pip install failed. Is the laptop online?
  goto :end
)

echo.
echo [2/3] Measured model (from the dashboard's recorded sessions) ...
if exist live_logs\*.jsonl (
  %PY% quantum\calibrate.py
) else (
  echo     no live_logs yet - using the numbers already in quantum\loom_model.json
)

echo.
echo [3/3] Solving 15 problems with QAOA (takes about 15-20 minutes) ...
%PY% quantum\solve_policy.py %*
if errorlevel 1 (
  echo.
  echo [WARN] At least one QAOA answer differs from brute force - see quantum\results\report.md
) else (
  echo.
  echo [ok] Done. Charts and tables: quantum\results\   Policy: app\src\main\assets\loom_policy.json
  echo      Run build_loom.bat to put the new policy on the phone.
)
:end
echo.
pause
