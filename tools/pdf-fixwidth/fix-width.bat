@echo off
REM Drag one or more PDFs (or folders) onto this file.
REM Output: <name>-fixed.pdf next to each input, all pages the same width.
if "%~1"=="" (
  echo Drag PDF files or folders onto this .bat, or run: fix-width.bat file.pdf
  pause
  exit /b 1
)
python "%~dp0fixwidth.py" %*
pause
