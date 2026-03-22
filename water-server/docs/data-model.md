# Data model from `water.csv`

`water.csv` is a snapshot ledger, not a transaction ledger. Each dated row represents the state of assets on a specific day.

## Raw column interpretation

- `time`: snapshot date
- `income`: monthly income baseline
- `red`: fixed expense baseline
- `cash`: encoded cash breakdown like `a2b0c10.6`
- `total1`: total cash
- `asset`: encoded investment breakdown like `a23.3b12.6c0`
- `total2`: total investments
- `loan`: encoded liabilities like `a10+10+3+5b2.4`
- `total3`: total liabilities
- `account`: gross account value
- `win`: profit/loss
- `actual`: actual net worth
- `log...`: note
- `remark`: extra remark
- `publicFunds`: public reserve / family reserve style amount
- `extra`: currently sparse
- `balance`: derived total balance in later rows

## Problems to normalize

- Date format is inconsistent: `2020/3/5` and `20260110` both exist.
- Header names are not clean.
- There are blank template rows at the end of the file.
- Encoded fields need parsing into named sub-accounts.

## Recommended normalized model

### asset_snapshot

- `id`
- `snapshot_date`
- `income`
- `fixed_expense`
- `cash_total`
- `investment_total`
- `liability_total`
- `gross_account_value`
- `profit_loss`
- `net_worth`
- `public_funds`
- `extra_amount`
- `balance`
- `note`
- `remark`
- `source_row_number`

### asset_snapshot_item

- `id`
- `snapshot_id`
- `category_type` (`CASH`, `INVESTMENT`, `LIABILITY`)
- `item_code` (`a`, `b`, `c`, etc.)
- `item_name`
- `amount`
- `raw_expression`

## Suggested code mapping

- Cash: `a=alipay`, `b=wechat`, `c=bankCard`
- Investment: `a=fund`, `b=aShare`, `c=usShare`
- Liability: `a=consumerLoan`, `b=creditCard`

These names should stay configurable because the CSV conventions may evolve.
