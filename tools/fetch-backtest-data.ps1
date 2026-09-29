# Holt Wellness und Aktivitäten der letzten Monate aus intervals.icu in EINE JSON-Datei
# für den Backtest (app/src/test/.../BacktestTest.kt).
#
# Aufruf:
#   .\tools\fetch-backtest-data.ps1 -ApiKey <intervals.icu API-Key> [-Days 200] [-Out backtest.json]
# Danach:
#   $env:READINESS_RAW = (Resolve-Path backtest.json); gradle :app:testDebugUnitTest --tests '*BacktestTest*'
#
# Der API-Key wird nur für die Anfrage verwendet und nirgends gespeichert.
param(
    [Parameter(Mandatory)][string]$ApiKey,
    [string]$Athlete = "0",
    [int]$Days = 200,
    [string]$Out = "backtest.json"
)
$ErrorActionPreference = "Stop"
$newest = (Get-Date).ToString("yyyy-MM-dd")
$oldest = (Get-Date).AddDays(-$Days).ToString("yyyy-MM-dd")
$auth = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("API_KEY:$ApiKey"))
$h = @{ Authorization = $auth }
$base = "https://intervals.icu/api/v1/athlete/$Athlete"
$fields = "id,type,trainer,start_date_local,moving_time,icu_training_load,icu_intensity,icu_zone_times,icu_eftp," +
          "icu_weighted_avg_watts,icu_normalized_watts,icu_average_watts,average_watts,average_heartrate,icu_average_hr," +
          "average_hr,icu_decoupling,decoupling"
$w = Invoke-RestMethod -Headers $h -Uri "$base/wellness?oldest=$oldest&newest=$newest"
$a = Invoke-RestMethod -Headers $h -Uri "$base/activities?oldest=$oldest&newest=$newest&fields=$fields"
$json = @{ wellness = @($w); activities = @($a) } | ConvertTo-Json -Depth 8 -Compress
[IO.File]::WriteAllText((Join-Path (Get-Location) $Out), $json, (New-Object Text.UTF8Encoding $false))
Write-Output "Gespeichert: $Out ($(@($w).Count) Wellness-Tage, $(@($a).Count) Aktivitäten)"
