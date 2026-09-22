# Build minified assets — jalankan setiap kali app.js/styles.css berubah SEBELUM commit.
# esbuild binary: C:\Users\hanif\AppData\Local\rythmix-build\esbuild\w64\package\esbuild.exe
$es = "$env:LOCALAPPDATA\rythmix-build\esbuild\w64\package\esbuild.exe"
if (-not (Test-Path $es)) { Write-Error "esbuild tidak ditemukan: $es"; exit 1 }
$pub = Join-Path $PSScriptRoot "public"
& $es "$pub\app.js" --minify --target=es2018 "--outfile=$pub\app.min.js"
& $es "$pub\styles.css" --minify "--outfile=$pub\styles.min.css"
Write-Host "minified: app.min.js + styles.min.css"
