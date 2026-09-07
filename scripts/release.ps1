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

function Write-ReleaseNotes {
    param(
        [string]$Name,
        [string]$Code,
        [string]$OutFile
    )
    $text = ""
    if (Test-Path "CHANGELOG.md") {
        $text = Get-Content "CHANGELOG.md" -Raw -Encoding UTF8
    }
    $notes = "Fixes and improvements."
    $escaped = [regex]::Escape($Name)
    $pattern = '(?ms)^##\s+' + $escaped + '\s*\r?\n(.*?)(?=^##\s|\z)'
    $matched = [regex]::Match($text, $pattern)
    if ($matched.Success) {
        $body = $matched.Groups[1].Value
        $body = [regex]::Replace($body, '(?m)^(versionCode|versionName|cosUrl)\s*=.*\r?\n?', "")
        $body = $body.Trim()
        if ($body) { $notes = $body }
    }
    $content = "versionCode=$Code`nversionName=$Name`n`n$notes`n"
    [System.IO.File]::WriteAllText($OutFile, $content, [System.Text.UTF8Encoding]::new($false))
}

function Get-Python {
    foreach ($cmd in @("python", "py")) {
        $found = Get-Command $cmd -ErrorAction SilentlyContinue
        if ($found) { return $found.Source }
    }
    throw "python not found"
}

$gradle = Get-Content "app\build.gradle.kts" -Raw -Encoding UTF8
if ($gradle -notmatch 'versionCode\s*=\s*(\d+)') { throw "versionCode not found" }
$code = $Matches[1]
if ($gradle -notmatch 'versionName\s*=\s*"([^"]+)"') { throw "versionName not found" }
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
    if ($existing -match '(?m)^cosUrl=') {
        Write-Host "Release $tag already has cosUrl, skip."
        exit 0
    }
    Write-Host "Release $tag exists without cosUrl, upload DogeCloud only."
}

if (-not $releaseExists) {
    Clear-Proxy
    $env:GRADLE_USER_HOME = "C:\Users\duhy\.gradle"
    Write-Host "Building release $name ($code)..."
    # lint-gradle is often missing from the local offline cache
    & .\gradlew.bat :app:assembleRelease --offline -x lintVitalAnalyzeRelease -x lintVitalRelease
    if ($LASTEXITCODE -ne 0) { throw "assembleRelease failed" }
    if (-not (Test-Path $apkSrc)) { throw "missing $apkSrc" }

    Write-ReleaseNotes -Name $name -Code $code -OutFile $notesFile
    Copy-Item $apkSrc $apkName -Force
    Set-GitHubProxy
    gh release create $tag $apkName --title "KcbD $name" --latest --notes-file $notesFile
    if ($LASTEXITCODE -ne 0) { throw "gh release create failed" }
    Write-Host "Created GitHub Release $tag"
}

if (-not (Test-Path $apkName)) {
    if (Test-Path $apkSrc) {
        Copy-Item $apkSrc $apkName -Force
    } else {
        Set-GitHubProxy
        gh release download $tag --pattern "*.apk" --dir $Root
        if (-not (Test-Path $apkName)) { throw "no APK for DogeCloud upload" }
    }
}
Clear-Proxy
$ErrorActionPreference = "Continue"
& $py -c "import boto3" 2>$null
$hasBoto = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = "Stop"
if (-not $hasBoto) {
    & $py -m pip install --quiet boto3
    if ($LASTEXITCODE -ne 0) { throw "pip install boto3 failed" }
}
$env:VERSION_NAME = $name
$env:APK_PATH = (Resolve-Path $apkName).Path
$env:COS_URL_FILE = $cosFile
& $py (Join-Path $Root "scripts\upload_dogecloud.py")
if ($LASTEXITCODE -ne 0) { throw "DogeCloud upload failed" }
if (Test-Path $cosFile) {
    $url = (Get-Content $cosFile -Raw -Encoding UTF8).Trim()
    if ($url) {
        if (-not (Test-Path $notesFile)) {
            Set-GitHubProxy
            $body = gh release view $tag --json body -q .body
            [System.IO.File]::WriteAllText($notesFile, $body, [System.Text.UTF8Encoding]::new($false))
        }
        $text = Get-Content $notesFile -Raw -Encoding UTF8
        if ($text -notmatch '(?m)^cosUrl=') {
            $lines = $text -split "`r?`n"
            $insertAt = 0
            for ($i = 0; $i -lt $lines.Length; $i++) {
                if ($lines[$i] -like "versionName=*") { $insertAt = $i + 1; break }
            }
            $newLines = @()
            if ($insertAt -gt 0) { $newLines += $lines[0..($insertAt - 1)] }
            $newLines += "cosUrl=$url"
            if ($insertAt -lt $lines.Length) { $newLines += $lines[$insertAt..($lines.Length - 1)] }
            $joined = (($newLines -join "`n").TrimEnd() + "`n")
            [System.IO.File]::WriteAllText($notesFile, $joined, [System.Text.UTF8Encoding]::new($false))
        }
        Set-GitHubProxy
        gh release edit $tag --notes-file $notesFile
        if ($LASTEXITCODE -ne 0) { throw "failed to write cosUrl" }
        Write-Host "Wrote cosUrl into Release notes"
    }
}

Remove-Item $apkName, $notesFile, $cosFile -ErrorAction SilentlyContinue
Write-Host "Local release done: $tag"
