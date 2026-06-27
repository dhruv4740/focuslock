# PowerShell script to add an inbound rule for TCP port 4050 in Windows Defender Firewall.
# Must be run as Administrator.

$ruleName = "FocusLock Backend"
$port = 4050

Write-Output "Checking Windows Defender Firewall rules..."
$rule = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue

if (-not $rule) {
    Write-Output "Creating inbound firewall rule for port $port..."
    New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -LocalPort $port -Protocol TCP -Action Allow -Description "Allow FocusLock device syncing" | Out-Null
    Write-Output "Firewall rule '$ruleName' created successfully."
} else {
    Write-Output "Firewall rule '$ruleName' already exists."
}
