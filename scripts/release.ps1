# Local GitHub + DogeCloud release. Run from repo root after pushing master.
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

function Clear-Proxy {
    foreach ($name in @("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy")) {
        Remove-Item "Env:$name" -ErrorAction SilentlyContinue
    }
}

function Set-GitHubProxy {
    $env:HTTP_PROXY = "http://127.0.0.1:10808"
    $env:HTTPS_PROXY = "http://127.0.0.1:10808"
}

function Get-Python {
    foreach ($cmd in @("python", "py")) {
        $found = Get-Command $cmd -ErrorAction SilentlyContinue
        if ($found) { return $found.Source }
    }
    throw "找不到 python，请先安装 Python 3"
}

$gradle = Get-Content "app\build.gradle.kts" -Raw
if ($gradle -notmatch 'versionCode\s*=\s*(\d+)') { throw "读不到 versionCode" }
$code = $Matches[1]
if ($gradle -notmatch 'versionName\s*=\s*"([^"]+)"') { throw "读不到 versionName" }
$name = $Matches[1]
$tag = "v$name"
$notesFile = Join-Path $Root "release-notes.txt"
$apkName = "sysukcb-$name.apk"
$apkSrc = Join-Path $Root "app\build\outputs\apk\release\app-release.apk"
$cosFile = Join-Path $Root ".cos_url"
$py = Get-Python

Set-GitHubProxy
$prevEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
gh release view $tag 2>$null | Out-Null
$releaseExists = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = $prevEap
if ($releaseExists) {
    $existing = gh release view $tag --json body -q .body
    if ($existing -match "(?m)^cosUrl=") {
        Write-Host "Release $tag 已存在且已有 cosUrl，跳过发版。"
        exit 0
    }
    Write-Host "Release $tag 已存在但没有 cosUrl，只补传多吉云。"
}

if (-not $releaseExists) {
    Clear-Proxy
    $env:GRADLE_USER_HOME = "C:\Users\duhy\.gradle"
    Write-Host "正在编译 release $name ($code)…"
    & .\gradlew.bat :app:assembleRelease --offline
    if ($LASTEXITCODE -ne 0) { throw "assembleRelease 失败" }
    if (-not (Test-Path $apkSrc)) { throw "找不到 $apkSrc" }

    & $py -c @"
from pathlib import Path
import re
name, code = '$name', '$code'
text = Path('CHANGELOG.md').read_text(encoding='utf-8') if Path('CHANGELOG.md').exists() else ''
match = re.search(rf'(?ms)^##\s+{re.escape(name)}\s*\n(.*?)(?=^##\s|\Z)', text)
notes = match.group(1).strip() if match else ''
notes = re.sub(r'^(versionCode|versionName|cosUrl)\s*=.*\n?', '', notes, flags=re.M).strip()
if not notes:
    notes = '修复与改进。'
Path(r'$notesFile').write_text(f'versionCode={code}\nversionName={name}\n\n{notes}\n', encoding='utf-8')
"@
    Copy-Item $apkSrc $apkName -Force
    Set-GitHubProxy
    gh release create $tag $apkName --title "课程表D $name" --latest --notes-file $notesFile
    if ($LASTEXITCODE -ne 0) { throw "gh release create 失败" }
    Write-Host "已创建 GitHub Release $tag"
}

if (-not (Test-Path $apkName)) {
    if (Test-Path $apkSrc) {
        Copy-Item $apkSrc $apkName -Force
    } else {
        Set-GitHubProxy
        gh release download $tag --pattern "*.apk" --dir $Root
        if (-not (Test-Path $apkName)) { throw "没有 APK 可上传多吉云" }
    }
}
Clear-Proxy
$ErrorActionPreference = "Continue"
& $py -c "import boto3" 2>$null
$hasBoto = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = "Stop"
if (-not $hasBoto) {
    & $py -m pip install --quiet boto3
    if ($LASTEXITCODE -ne 0) { throw "安装 boto3 失败" }
}
$env:VERSION_NAME = $name
$env:APK_PATH = (Resolve-Path $apkName).Path
$env:COS_URL_FILE = $cosFile
& $py (Join-Path $Root "scripts\upload_dogecloud.py")
if ($LASTEXITCODE -ne 0) { throw "多吉云上传失败" }
if (Test-Path $cosFile) {
    $url = (Get-Content $cosFile -Raw -Encoding UTF8).Trim()
    if ($url) {
        if (-not (Test-Path $notesFile)) {
            Set-GitHubProxy
            $body = gh release view $tag --json body -q .body
            Set-Content -Path $notesFile -Value $body -Encoding UTF8
        }
        $text = Get-Content $notesFile -Raw -Encoding UTF8
        if ($text -notmatch "(?m)^cosUrl=") {
            $lines = $text -split "`r?`n"
            $insertAt = 0
            for ($i = 0; $i -lt $lines.Length; $i++) {
                if ($lines[$i] -like "versionName=*") { $insertAt = $i + 1; break }
            }
            $newLines = @()
            if ($insertAt -gt 0) { $newLines += $lines[0..($insertAt - 1)] }
            $newLines += "cosUrl=$url"
            if ($insertAt -lt $lines.Length) { $newLines += $lines[$insertAt..($lines.Length - 1)] }
            Set-Content -Path $notesFile -Value (($newLines -join "`n").TrimEnd() + "`n") -Encoding UTF8
        }
        Set-GitHubProxy
        gh release edit $tag --notes-file $notesFile
        if ($LASTEXITCODE -ne 0) { throw "写入 cosUrl 失败" }
        Write-Host "已把对象存储地址写进 Release notes"
    }
}

Remove-Item $apkName, $notesFile, $cosFile -ErrorAction SilentlyContinue
Write-Host "本机发版完成：$tag"
