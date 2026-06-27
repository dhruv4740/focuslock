param (
    [int]$HwndVal
)

# Load UI Automation Assemblies
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

try {
    $hWnd = [IntPtr]$HwndVal
    $ae = [System.Windows.Automation.AutomationElement]::FromHandle($hWnd)
    if ($ae -eq $null) {
        Write-Output ""
        exit
    }

    # 1. Search for edit controls (Chromium browsers: Chrome, Edge, Brave, Opera, etc.)
    # Chromium's address bar is typically an Edit control.
    # We look for Edit controls and check if their value contains a domain or URL structure.
    $editCondition = [System.Windows.Automation.PropertyCondition]::new(
        [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
        [System.Windows.Automation.ControlType]::Edit
    )
    $editElements = $ae.FindAll([System.Windows.Automation.TreeScope]::Descendants, $editCondition)
    foreach ($edit in $editElements) {
        # Check if ValuePattern is supported
        $valuePattern = $null
        if ($edit.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern, [ref]$valuePattern)) {
            $val = $valuePattern.Current.Value
            if ($val -match "(\w+\.)+\w+" -or $val -like "http*") {
                # Clean up URL (sometimes has 'Search or enter address' prefix)
                $val = $val -replace "^Search or enter web address\s*", ""
                Write-Output $val
                exit
            }
        }
    }

    # 2. Search for Document controls (Firefox uses Document control for address bar or page)
    $docCondition = [System.Windows.Automation.PropertyCondition]::new(
        [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
        [System.Windows.Automation.ControlType]::Document
    )
    $docElements = $ae.FindAll([System.Windows.Automation.TreeScope]::Descendants, $docCondition)
    foreach ($doc in $docElements) {
        $valuePattern = $null
        if ($doc.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern, [ref]$valuePattern)) {
            $val = $valuePattern.Current.Value
            if ($val -match "(\w+\.)+\w+" -or $val -like "http*") {
                Write-Output $val
                exit
            }
        }
    }

    # 3. Fallback: Search for any element with class 'OmniboxViewViews' (Chrome/Edge internal name)
    $classCondition = [System.Windows.Automation.PropertyCondition]::new(
        [System.Windows.Automation.AutomationElement]::ClassNameProperty,
        "OmniboxViewViews"
    )
    $omni = $ae.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $classCondition)
    if ($omni -ne $null) {
        $valuePattern = $null
        if ($omni.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern, [ref]$valuePattern)) {
            Write-Output $valuePattern.Current.Value
            exit
        }
    }

} catch {
    # Fail silently to avoid interrupting agent loop
}

Write-Output ""
