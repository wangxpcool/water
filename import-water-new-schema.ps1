param(
    [string]$CsvPath = "water.csv",
    [string]$DatabasePath = "water.db",
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

$cashAccountCodes = @{
    a = "ALIPAY"
    b = "WECHAT"
    c = "DEFAULT_BANK_CARD"
}

$investmentAccountCodes = @{
    a = "FUND_ACCOUNT"
    b = "A_SHARE_ACCOUNT"
    c = "US_STOCK_ACCOUNT"
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

function Resolve-PathSafe {
    param([string]$PathValue)
    return [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot $PathValue))
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
        [hashtable]$AccountCodes
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

        if (-not $AccountCodes.ContainsKey($itemCode)) {
            continue
        }

        $items.Add([pscustomobject]@{
            account_code = $AccountCodes[$itemCode]
            amount = Evaluate-Expression $expression
            raw_expression = $expression
        })
    }

    return $items
}

$resolvedCsvPath = Resolve-PathSafe $CsvPath
$resolvedDatabasePath = Resolve-PathSafe $DatabasePath

if (-not (Test-Path $SqlitePath)) {
    throw "sqlite3.exe not found at: $SqlitePath"
}

if (-not (Test-Path $resolvedCsvPath)) {
    throw "CSV file not found: $resolvedCsvPath"
}

$rows = Get-Content -Path $resolvedCsvPath | Select-Object -Skip 1 | ConvertFrom-Csv -Header $headers
$sqlLines = New-Object System.Collections.Generic.List[string]
$sqlLines.Add("BEGIN TRANSACTION;")
$sqlLines.Add("DELETE FROM asset_snapshot_detail;")
$sqlLines.Add("DELETE FROM asset_snapshot;")

$importedSnapshots = 0
$importedDetails = 0
$sourceRowNumber = 1

foreach ($row in $rows) {
    $sourceRowNumber += 1
    if ([string]::IsNullOrWhiteSpace($row.time)) {
        continue
    }

    $snapshotDate = Parse-DateValue $row.time
    $snapshotDateSql = To-SqlValue $snapshotDate
    $sourceRowSql = To-SqlValue $sourceRowNumber
    $snapshotIdExpression = "(SELECT id FROM asset_snapshot WHERE snapshot_date = $snapshotDateSql AND source_row_number = $sourceRowSql)"

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
    source_row_number
) VALUES (
    $snapshotDateSql,
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
    $sourceRowSql
);
"@.Trim())

    $detailItems = New-Object System.Collections.Generic.List[object]

    foreach ($item in Parse-Breakdown $row.cash $cashAccountCodes) {
        $detailItems.Add([pscustomobject]@{
            account_code = $item.account_code
            amount = $item.amount
            currency_code = "CNY"
            remark = "cash:" + $item.raw_expression
        })
    }

    foreach ($item in Parse-Breakdown $row.asset $investmentAccountCodes) {
        $currencyCode = if ($item.account_code -eq "US_STOCK_ACCOUNT") { "USD" } else { "CNY" }
        $detailItems.Add([pscustomobject]@{
            account_code = $item.account_code
            amount = $item.amount
            currency_code = $currencyCode
            remark = "asset:" + $item.raw_expression
        })
    }

    $loanTotal = Parse-DecimalValue $row.total3
    if ($null -ne $loanTotal) {
        $detailItems.Add([pscustomobject]@{
            account_code = "RECEIVABLES"
            amount = $loanTotal
            currency_code = "CNY"
            remark = "loan aggregate:" + $row.loan
        })
    }

    $publicFunds = Parse-DecimalValue $row.publicFunds
    if ($null -ne $publicFunds) {
        $detailItems.Add([pscustomobject]@{
            account_code = "HOUSING_FUND"
            amount = $publicFunds
            currency_code = "CNY"
            remark = "publicFunds"
        })
    }

    $profitLoss = Parse-DecimalValue $row.win
    if ($null -ne $profitLoss -and $profitLoss -lt 0) {
        $detailItems.Add([pscustomobject]@{
            account_code = "INVESTMENT_LOSS"
            amount = [decimal]::Negate($profitLoss)
            currency_code = "CNY"
            remark = "negative profit_loss"
        })
    }

    foreach ($item in $detailItems) {
        $sqlLines.Add(@"
INSERT OR REPLACE INTO asset_snapshot_detail (
    snapshot_id,
    account_id,
    amount,
    original_amount,
    currency_code,
    remark
) VALUES (
    $snapshotIdExpression,
    (SELECT id FROM asset_account WHERE account_code = $(To-SqlValue $item.account_code)),
    $(To-SqlValue $item.amount),
    $(To-SqlValue $item.amount),
    $(To-SqlValue $item.currency_code),
    $(To-SqlValue $item.remark)
);
"@.Trim())
        $importedDetails += 1
    }

    $importedSnapshots += 1
}

$sqlLines.Add("COMMIT;")
$tempSqlPath = Join-Path $env:TEMP ("water-new-schema-import-" + [guid]::NewGuid().ToString("N") + ".sql")

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

Write-Output "Imported $importedSnapshots snapshots and $importedDetails details into $resolvedDatabasePath"
