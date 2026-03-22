param(
    [string]$CsvPath = "..\..\water.csv",
    [string]$DatabasePath = "..\data\water.db",
    [string]$SqlitePath = "C:\Users\wangx\Desktop\peisonal\sqlite-tools-win-x64-3510300\sqlite3.exe"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$headers = @(
    "time",
    "income",
    "red",
    "cash",
    "total1",
    "asset",
    "total2",
    "loan",
    "total3",
    "account",
    "win",
    "actual",
    "log",
    "remark",
    "publicFunds",
    "extra",
    "balance",
    "unused1",
    "unused2"
)

$cashItemNames = @{
    a = "alipay"
    b = "wechat"
    c = "bankCard"
}

$investmentItemNames = @{
    a = "fund"
    b = "aShare"
    c = "usShare"
}

$liabilityItemNames = @{
    a = "consumerLoan"
    b = "creditCard"
}

function Escape-SqlLiteral {
    param([AllowNull()][string]$Value)

    if ($null -eq $Value) {
        return "NULL"
    }

    return "'" + $Value.Replace("'", "''") + "'"
}

function To-SqlValue {
    param($Value)

    if ($null -eq $Value) {
        return "NULL"
    }

    if ($Value -is [string]) {
        if ([string]::IsNullOrWhiteSpace($Value)) {
            return "NULL"
        }
        return Escape-SqlLiteral $Value
    }

    if ($Value -is [datetime]) {
        return Escape-SqlLiteral ($Value.ToString("yyyy-MM-dd"))
    }

    if ($Value -is [decimal]) {
        return $Value.ToString([System.Globalization.CultureInfo]::InvariantCulture)
    }

    if ($Value -is [int] -or $Value -is [long]) {
        return "$Value"
    }

    return Escape-SqlLiteral "$Value"
}

function Parse-DateValue {
    param([string]$Raw)

    if ([string]::IsNullOrWhiteSpace($Raw)) {
        return $null
    }

    $value = $Raw.Trim()
    $culture = [System.Globalization.CultureInfo]::InvariantCulture
    if ($value.Contains("/")) {
        return [datetime]::ParseExact($value, "yyyy/M/d", $culture)
    }

    return [datetime]::ParseExact($value, "yyyyMMdd", $culture)
}

function Evaluate-Expression {
    param([string]$RawExpression)

    if ([string]::IsNullOrWhiteSpace($RawExpression)) {
        return $null
    }

    $expression = $RawExpression.Replace(" ", "").Replace(",", ".")
    $matches = [regex]::Matches($expression, '([+-]?)(\d+(?:\.\d+)?)')
    if ($matches.Count -eq 0) {
        throw "Unsupported expression: $RawExpression"
    }

    [decimal]$result = 0
    foreach ($match in $matches) {
        [decimal]$value = [decimal]::Parse($match.Groups[2].Value, [System.Globalization.CultureInfo]::InvariantCulture)
        if ($match.Groups[1].Value -eq "-") {
            $result -= $value
        } else {
            $result += $value
        }
    }

    return $result
}

function Parse-DecimalValue {
    param([string]$Raw)

    if ([string]::IsNullOrWhiteSpace($Raw)) {
        return $null
    }

    $normalized = $Raw.Trim().Replace(",", ".")
    $numericCandidate = [regex]::Replace($normalized, "[^0-9+\-.]", "")
    if ([string]::IsNullOrWhiteSpace($numericCandidate) -or $numericCandidate -in @("-", "+", ".")) {
        return $null
    }

    if ($numericCandidate -match '^[+-]?\d+(?:\.\d+)?$') {
        return [decimal]::Parse($numericCandidate, [System.Globalization.CultureInfo]::InvariantCulture)
    }

    return Evaluate-Expression $numericCandidate
}

function Parse-Breakdown {
    param(
        [string]$Raw,
        [string]$CategoryType,
        [hashtable]$ItemNames
    )

    if ([string]::IsNullOrWhiteSpace($Raw)) {
        return @()
    }

    $normalized = $Raw.Trim().Replace(",", ".")
    $segmentMatches = [regex]::Matches($normalized, '([A-Za-z])([^A-Za-z]*)')
    $items = New-Object System.Collections.Generic.List[object]

    foreach ($match in $segmentMatches) {
        $itemCode = $match.Groups[1].Value.ToLowerInvariant()
        $expression = $match.Groups[2].Value.Trim()
        if ([string]::IsNullOrWhiteSpace($expression)) {
            continue
        }

        $itemName = if ($ItemNames.ContainsKey($itemCode)) { $ItemNames[$itemCode] } else { "unknown" }
        $items.Add([pscustomobject]@{
            category_type = $CategoryType
            item_code = $itemCode
            item_name = $itemName
            amount = Evaluate-Expression $expression
            raw_expression = $expression
        })
    }

    return $items
}

function Resolve-PathSafe {
    param([string]$PathValue)
    return [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot $PathValue))
}

$resolvedCsvPath = Resolve-PathSafe $CsvPath
$resolvedDatabasePath = Resolve-PathSafe $DatabasePath

if (-not (Test-Path $SqlitePath)) {
    throw "sqlite3.exe not found at: $SqlitePath"
}

if (-not (Test-Path $resolvedCsvPath)) {
    throw "CSV file not found: $resolvedCsvPath"
}

$databaseDirectory = Split-Path -Parent $resolvedDatabasePath
if (-not (Test-Path $databaseDirectory)) {
    New-Item -ItemType Directory -Path $databaseDirectory | Out-Null
}

$rows = Get-Content -Path $resolvedCsvPath | Select-Object -Skip 1 | ConvertFrom-Csv -Header $headers
$sqlLines = New-Object System.Collections.Generic.List[string]
$sqlLines.Add("PRAGMA foreign_keys = ON;")
$sqlLines.Add("BEGIN TRANSACTION;")
$sqlLines.Add("DELETE FROM asset_snapshot_item;")
$sqlLines.Add("DELETE FROM asset_snapshot;")

$importedSnapshots = 0
$importedItems = 0
$sourceRowNumber = 1

foreach ($row in $rows) {
    $sourceRowNumber += 1
    if ([string]::IsNullOrWhiteSpace($row.time)) {
        continue
    }

    $snapshotDate = Parse-DateValue $row.time
    $cashItems = Parse-Breakdown $row.cash "CASH" $cashItemNames
    $investmentItems = Parse-Breakdown $row.asset "INVESTMENT" $investmentItemNames
    $liabilityItems = Parse-Breakdown $row.loan "LIABILITY" $liabilityItemNames
    $snapshotIdExpression = "(SELECT id FROM asset_snapshot WHERE snapshot_date = $(To-SqlValue $snapshotDate) AND source_row_number = $(To-SqlValue $sourceRowNumber))"

    $sqlLines.Add(@"
INSERT INTO asset_snapshot (
    snapshot_date,
    income,
    fixed_expense,
    cash_total,
    investment_total,
    liability_total,
    gross_account_value,
    profit_loss,
    net_worth,
    public_funds,
    extra_amount,
    balance,
    note,
    remark,
    raw_cash,
    raw_investment,
    raw_liability,
    source_row_number
) VALUES (
    $(To-SqlValue $snapshotDate),
    $(To-SqlValue (Parse-DecimalValue $row.income)),
    $(To-SqlValue (Parse-DecimalValue $row.red)),
    $(To-SqlValue (Parse-DecimalValue $row.total1)),
    $(To-SqlValue (Parse-DecimalValue $row.total2)),
    $(To-SqlValue (Parse-DecimalValue $row.total3)),
    $(To-SqlValue (Parse-DecimalValue $row.account)),
    $(To-SqlValue (Parse-DecimalValue $row.win)),
    $(To-SqlValue (Parse-DecimalValue $row.actual)),
    $(To-SqlValue (Parse-DecimalValue $row.publicFunds)),
    $(To-SqlValue (Parse-DecimalValue $row.extra)),
    $(To-SqlValue (Parse-DecimalValue $row.balance)),
    $(To-SqlValue $row.log),
    $(To-SqlValue $row.remark),
    $(To-SqlValue $row.cash),
    $(To-SqlValue $row.asset),
    $(To-SqlValue $row.loan),
    $(To-SqlValue $sourceRowNumber)
);
"@.Trim())

    foreach ($item in @($cashItems) + @($investmentItems) + @($liabilityItems)) {
        $sqlLines.Add(@"
INSERT INTO asset_snapshot_item (
    snapshot_id,
    category_type,
    item_code,
    item_name,
    amount,
    raw_expression
) VALUES (
    $snapshotIdExpression,
    $(To-SqlValue $item.category_type),
    $(To-SqlValue $item.item_code),
    $(To-SqlValue $item.item_name),
    $(To-SqlValue $item.amount),
    $(To-SqlValue $item.raw_expression)
);
"@.Trim())
        $importedItems += 1
    }

    $importedSnapshots += 1
}

$sqlLines.Add("COMMIT;")
$tempSqlPath = Join-Path $env:TEMP ("water-import-" + [guid]::NewGuid().ToString("N") + ".sql")

try {
    [System.IO.File]::WriteAllLines($tempSqlPath, $sqlLines, [System.Text.UTF8Encoding]::new($false))
    $sqliteOutput = & $SqlitePath $resolvedDatabasePath ".bail on" ".read $tempSqlPath" 2>&1
    if ($LASTEXITCODE -ne 0 -or ($sqliteOutput -join "`n") -match "Runtime error|Parse error|Error:") {
        throw (($sqliteOutput -join "`n").Trim())
    }
} finally {
    if (Test-Path $tempSqlPath) {
        Remove-Item $tempSqlPath -Force
    }
}

Write-Output "Imported $importedSnapshots snapshots and $importedItems items into $resolvedDatabasePath"
