// Package inventory stands in for a third-party library: its own module, its
// own release cycle, and no telemetry code. The shop app depends on it; the
// oscope integration weaves spans into it at compile time.
package inventory

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
)

var ErrOutOfStock = errors.New("inventory: out of stock")

type Store struct{ db *sql.DB }

// Open creates the schema and seeds stock for SKUs sku-0 .. sku-(n-1).
func Open(ctx context.Context, db *sql.DB, skus, stock int) (*Store, error) {
	if _, err := db.ExecContext(ctx, `CREATE TABLE IF NOT EXISTS stock (sku TEXT PRIMARY KEY, qty INTEGER NOT NULL)`); err != nil {
		return nil, err
	}
	if _, err := db.ExecContext(ctx, `CREATE TABLE IF NOT EXISTS reservation (id INTEGER PRIMARY KEY AUTOINCREMENT, sku TEXT, qty INTEGER, order_id TEXT)`); err != nil {
		return nil, err
	}
	for i := 0; i < skus; i++ {
		if _, err := db.ExecContext(ctx, `INSERT OR REPLACE INTO stock (sku, qty) VALUES (?, ?)`, fmt.Sprintf("sku-%d", i), stock); err != nil {
			return nil, err
		}
	}
	return &Store{db: db}, nil
}

// Reserve takes qty units of sku for an order, in one transaction.
func (s *Store) Reserve(ctx context.Context, orderID, sku string, qty int) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var have int
	if err := tx.QueryRowContext(ctx, `SELECT qty FROM stock WHERE sku = ?`, sku).Scan(&have); err != nil {
		return err
	}
	if have < qty {
		return ErrOutOfStock
	}
	if _, err := tx.ExecContext(ctx, `UPDATE stock SET qty = qty - ? WHERE sku = ?`, qty, sku); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO reservation (sku, qty, order_id) VALUES (?, ?, ?)`, sku, qty, orderID); err != nil {
		return err
	}
	return tx.Commit()
}

// Available reports current stock for sku.
func (s *Store) Available(ctx context.Context, sku string) (int, error) {
	var n int
	err := s.db.QueryRowContext(ctx, `SELECT qty FROM stock WHERE sku = ?`, sku).Scan(&n)
	return n, err
}
