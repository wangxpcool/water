# Data Model V2 Proposal

## Goal

Separate three concerns that are currently mixed together:

1. Account master data
2. Per-day account balances
3. Per-day snapshot metadata and derived summary

The current schema works, but `asset_snapshot` stores both:

- values entered by the user
- values derived from `asset_snapshot_detail`

That makes the model ambiguous and hard to evolve.

## Recommended target model

### 1. `asset_account`

Account master data only.

```sql
CREATE TABLE asset_account (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    account_code      TEXT NOT NULL,
    account_name      TEXT NOT NULL,
    category_group    TEXT NOT NULL,
    account_type      TEXT NOT NULL,
    parent_account_id INTEGER,
    is_summary        INTEGER NOT NULL DEFAULT 0,
    balance_direction TEXT NOT NULL,
    base_currency_code TEXT NOT NULL,
    institution_name  TEXT,
    owner_name        TEXT,
    remark            TEXT,
    sort_order        INTEGER NOT NULL DEFAULT 0,
    enabled           INTEGER NOT NULL DEFAULT 1,
    created_at        TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_asset_account_code UNIQUE (account_code),
    CONSTRAINT fk_asset_account_parent
        FOREIGN KEY (parent_account_id) REFERENCES asset_account(id),
    CONSTRAINT ck_asset_account_group
        CHECK (category_group IN ('CASH', 'INVESTMENT', 'LIABILITY')),
    CONSTRAINT ck_asset_account_direction
        CHECK (balance_direction IN ('ASSET', 'DEBT')),
    CONSTRAINT ck_asset_account_summary
        CHECK (is_summary IN (0, 1)),
    CONSTRAINT ck_asset_account_enabled
        CHECK (enabled IN (0, 1))
);
```

Notes:

- Add `category_group` as a stored field.
- Rename `currency_code` to `base_currency_code` to make the meaning explicit.
- Keep `parent_account_id` only for account hierarchy, not for summary computation rules beyond direct tree aggregation.

### 2. `asset_snapshot`

Snapshot header only. Keep fields that are not naturally derived from account balances.

```sql
CREATE TABLE asset_snapshot (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    snapshot_date     TEXT NOT NULL,
    profit_loss       NUMERIC,
    public_funds      NUMERIC,
    extra_amount      NUMERIC,
    note              TEXT,
    remark            TEXT,
    source_row_number INTEGER,
    created_at        TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_asset_snapshot_date UNIQUE (snapshot_date)
);
```

Notes:

- Remove `cash_total`, `investment_total`, `liability_total`, `gross_account_value`, `net_worth`, and `balance` from persisted write-model state.
- These values should be computed from detail rows plus snapshot metadata when queried.
- `income` and `fixed_expense` should remain only if they are business inputs rather than historical reference values. If they are monthly baseline metadata, keep them here. If they are report outputs, compute them elsewhere.

Pragmatic variant:

- Keep current summary columns for now, but treat them as cache columns only.
- Stop accepting them in the API request.

### 3. `asset_snapshot_balance`

Per-snapshot account balance facts.

```sql
CREATE TABLE asset_snapshot_balance (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    snapshot_id        INTEGER NOT NULL,
    account_id         INTEGER NOT NULL,
    amount             NUMERIC NOT NULL,
    original_amount    NUMERIC,
    currency_code      TEXT NOT NULL,
    remark             TEXT,
    created_at         TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_snapshot_balance_snapshot_account UNIQUE (snapshot_id, account_id),
    CONSTRAINT fk_snapshot_balance_snapshot
        FOREIGN KEY (snapshot_id) REFERENCES asset_snapshot(id) ON DELETE CASCADE,
    CONSTRAINT fk_snapshot_balance_account
        FOREIGN KEY (account_id) REFERENCES asset_account(id),
    CONSTRAINT fk_snapshot_balance_currency
        FOREIGN KEY (currency_code) REFERENCES currency_config(currency_code)
);
```

Notes:

- Rename from `asset_snapshot_detail` to `asset_snapshot_balance` because this table stores balances, not generic details.
- This is the core fact table of the app.

### 4. `currency_config`

Keep roughly as-is.

```sql
CREATE TABLE currency_config (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    currency_code  TEXT NOT NULL,
    currency_name  TEXT NOT NULL,
    currency_symbol TEXT,
    decimal_scale  INTEGER NOT NULL DEFAULT 2,
    remark         TEXT,
    enabled        INTEGER NOT NULL DEFAULT 1,
    created_at     TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_currency_config_code UNIQUE (currency_code)
);
```

## Derived values

These should be computed in query service or materialized view logic:

- `cash_total`
- `investment_total`
- `liability_total`
- `gross_account_value`
- `net_worth`
- latest account balance

Recommended formulas:

- `cash_total`: sum of leaf balances where `category_group = 'CASH'` and `balance_direction = 'ASSET'`
- `investment_total`: sum of leaf balances where `category_group = 'INVESTMENT'`
- `liability_total`: sum of leaf balances where `balance_direction = 'DEBT'`
- `gross_account_value`: sum of leaf balances where `balance_direction = 'ASSET'`
- `net_worth`: `gross_account_value - liability_total`

If `public_funds` or `extra_amount` are intended to affect reported net worth, define that explicitly in one place. Right now that rule is not clear enough.

## Why this is better

### Clear write model

- Users write account balances.
- The system derives totals.

### Clear read model

- Snapshot summary is a projection, not mixed storage.

### Better extensibility

- New account group rules become data changes instead of Java enum changes.
- Account hierarchy becomes a real model feature instead of a patch.

### Better integrity

- Foreign keys protect against orphaned detail rows.
- Check constraints make invalid enum-like values impossible.

## Migration strategy

### Phase 1: Stabilize semantics without breaking APIs

1. Add `category_group` to `asset_account`.
2. Backfill it from current `AccountCategoryGroup.resolve(...)` rules.
3. Keep existing `asset_snapshot_detail` table name for now.
4. Stop deriving group from `account_code` and `account_type` in new code.
5. Treat summary columns in `asset_snapshot` as read cache only.

This phase gives the biggest improvement with the smallest risk.

### Phase 2: Clean request/response model

1. Remove derived totals from snapshot write request.
2. Compute summary fields only in command/query service.
3. Keep response fields unchanged so the frontend does not break.

### Phase 3: Enforce integrity

SQLite cannot add all constraints cleanly with simple `ALTER TABLE`, so rebuild tables:

1. Create `asset_account_new` with foreign keys and checks.
2. Copy data from `asset_account`.
3. Swap table names.
4. Repeat for snapshot balance table.

### Phase 4: Rename tables and columns

Optional but cleaner:

1. Rename `asset_snapshot_detail` to `asset_snapshot_balance`.
2. Rename `asset_account.currency_code` to `base_currency_code`.
3. Remove no-longer-needed cached columns from `asset_snapshot`, or document them explicitly as projection cache.

## Suggested service-layer alignment

### Query service

- Read `category_group` directly from `asset_account`.
- Compute latest balance from the balance fact table.
- Build snapshot summary from balance rows plus snapshot header metadata.

### Command service

- Accept only leaf account balances as manual input.
- Derive summary-account balances server-side.
- Derive all totals server-side.

### API DTOs

- Distinguish:
  - snapshot header input
  - snapshot balance input
  - snapshot summary output

Right now those concerns are too close together.

## Recommended first change in this repo

If only one schema change is made first, make it this:

```sql
ALTER TABLE asset_account ADD COLUMN category_group TEXT;
```

Then backfill and make it required in application code. That removes the most awkward part of the current design with minimal migration risk.
