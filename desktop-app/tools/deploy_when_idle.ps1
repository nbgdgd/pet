# Ждёт, пока в плеере ничего не играет (90 с без player.render.rate в журнале
# диагностики), и только тогда зовёт deploy.ps1 — чтобы не обрывать просмотр.
#
#   powershell -ExecutionPolicy Bypass -File desktop-app\tools\deploy_when_idle.ps1
$log = "$env:APPDATA\AniBlaze\logs\player-diagnostics.log"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$deploy = Join-Path $here "deploy.ps1"
$out = Join-Path $here "deploy_when_idle.log"
"start $(Get-Date)" | Out-File $out -Encoding utf8
$deadline = (Get-Date).AddHours(6)
while ((Get-Date) -lt $deadline) {
    $running = Get-Process AniBlaze -ErrorAction SilentlyContinue
    if (-not $running) { "app not running -> deploy $(Get-Date)" | Out-File $out -Append -Encoding utf8; break }
    $tail = Get-Content $log -Tail 40 -ErrorAction SilentlyContinue
    $last = $tail | Select-String 'player.render.rate|vlc.statistics' | Select-Object -Last 1
    $idle = $true
    if ($last) {
        $stamp = ($last.Line -split ' \| ')[0]
        try { $t = [DateTime]::Parse($stamp).ToUniversalTime(); if (((Get-Date).ToUniversalTime() - $t).TotalSeconds -lt 90) { $idle = $false } } catch {}
    }
    if ($idle) { "idle -> deploy $(Get-Date)" | Out-File $out -Append -Encoding utf8; break }
    Start-Sleep -Seconds 30
}
& $deploy 2>&1 | Out-File $out -Append -Encoding utf8
"done $(Get-Date)" | Out-File $out -Append -Encoding utf8
