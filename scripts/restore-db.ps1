param(
  [string]$Snapshot = "backups/pre-loadtest-snapshot.sql",
  [string]$Container = "event-mysql-1",
  [string]$DbUser = "vms",
  [string]$DbPassword = "vms",
  [string]$Database = "vms"
)

if (-not (Test-Path $Snapshot)) {
  Write-Error "Snapshot not found: $Snapshot"
  exit 1
}

Write-Host "Restoring $Snapshot into $Container/$Database ..."
Get-Content $Snapshot | docker exec -i $Container mysql -u$DbUser -p$DbPassword $Database
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Restore complete."
