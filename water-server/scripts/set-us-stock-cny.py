"""Correct the existing RMB-entered US stock account's currency labels, without conversion."""
import argparse
import datetime
import json
import sqlite3
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("database", type=Path)
parser.add_argument("--backup-dir", type=Path, required=True)
args = parser.parse_args()
path = args.database.resolve(strict=True)
args.backup_dir.mkdir(parents=True, exist_ok=True)
backup = args.backup_dir / ("water-before-cny-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S-%f") + ".db")
with sqlite3.connect(path) as db:
    with sqlite3.connect(backup) as copy:
        db.backup(copy)
    before = db.execute("SELECT * FROM asset_snapshot ORDER BY id").fetchall()
    amounts = {table: db.execute(f"SELECT id, amount, original_amount FROM {table} ORDER BY id").fetchall()
               for table in ("asset_snapshot_detail", "asset_snapshot_group_detail")}
    account_ids = [r[0] for r in db.execute("SELECT id FROM asset_account WHERE account_code = 'US_STOCK_ACCOUNT'")]
    group_ids = [r[0] for r in db.execute("SELECT id FROM asset_account_group WHERE group_code = 'US_STOCK_ACCOUNT'")]
    if not account_ids and not group_ids:
        raise RuntimeError("US_STOCK_ACCOUNT not found; no changes made")
    db.execute("UPDATE asset_account SET currency_code = 'CNY' WHERE account_code = 'US_STOCK_ACCOUNT'")
    db.execute("UPDATE asset_account_group SET currency_code = 'CNY' WHERE group_code = 'US_STOCK_ACCOUNT'")
    for account_id in account_ids:
        db.execute("UPDATE asset_snapshot_detail SET currency_code = 'CNY' WHERE account_id = ?", (account_id,))
    for group_id in group_ids:
        db.execute("UPDATE asset_snapshot_group_detail SET currency_code = 'CNY' WHERE group_id = ?", (group_id,))
    if db.execute("SELECT 1 FROM sqlite_master WHERE name = 'asset_snapshot_context'").fetchone():
        for snapshot_id, raw in db.execute("SELECT snapshot_id, accounts_json FROM asset_snapshot_context").fetchall():
            definitions = json.loads(raw)
            for a in definitions:
                if a['id'] in account_ids or a['id'] in [-i for i in group_ids]:
                    a['currencyCode'] = 'CNY'
            db.execute("UPDATE asset_snapshot_context SET accounts_json = ? WHERE snapshot_id = ?", (json.dumps(definitions, ensure_ascii=False), snapshot_id))
    assert before == db.execute("SELECT * FROM asset_snapshot ORDER BY id").fetchall()
    for table, original in amounts.items():
        assert original == db.execute(f"SELECT id, amount, original_amount FROM {table} ORDER BY id").fetchall()
    assert db.execute("PRAGMA integrity_check").fetchone()[0] == 'ok'
print(f"Updated account IDs {account_ids}, group IDs {group_ids}; all amounts and snapshot totals unchanged. Backup: {backup.resolve()}")
