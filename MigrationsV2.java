package net.iyha.core;

import java.util.List;

/** Schema step 2: markets, claims, travel, combat, enchanting, items, social. */
final class MigrationsV2 {
    private MigrationsV2() {}

    static Migrations.Step step(Database db) {
        String id = db.autoId();
        String blob = db.blob();
        return new Migrations.Step(2, List.of(
                "CREATE TABLE IF NOT EXISTS claims (claim_id " + id + ", owner VARCHAR(36) NOT NULL, "
                        + "source VARCHAR(32) NOT NULL, source_id VARCHAR(64), item_bytes " + blob + " NOT NULL, "
                        + "amount INT NOT NULL, status VARCHAR(12) NOT NULL, idem_key VARCHAR(80) UNIQUE, "
                        + "created_at BIGINT NOT NULL, delivered_at BIGINT)",
                "CREATE INDEX IF NOT EXISTS idx_claims_owner ON claims(owner, status)",

                "CREATE TABLE IF NOT EXISTS ah_listings (listing_id " + id + ", seller VARCHAR(36) NOT NULL, "
                        + "item_bytes " + blob + " NOT NULL, item_key VARCHAR(64) NOT NULL, category VARCHAR(16) NOT NULL, "
                        + "quantity INT NOT NULL, price BIGINT NOT NULL, fee_bp INT NOT NULL, status VARCHAR(12) NOT NULL, "
                        + "version INT NOT NULL DEFAULT 0, created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL, "
                        + "buyer VARCHAR(36), sold_at BIGINT, tx_id BIGINT, jref VARCHAR(48))",
                "CREATE INDEX IF NOT EXISTS idx_ah_status_exp ON ah_listings(status, expires_at)",
                "CREATE INDEX IF NOT EXISTS idx_ah_status_cat ON ah_listings(status, category, price)",
                "CREATE INDEX IF NOT EXISTS idx_ah_seller ON ah_listings(seller, status)",
                "CREATE INDEX IF NOT EXISTS idx_ah_item ON ah_listings(item_key, status, price)",
                "CREATE INDEX IF NOT EXISTS idx_ah_jref ON ah_listings(jref)",
                "CREATE TABLE IF NOT EXISTS ah_history (id " + id + ", listing_id BIGINT NOT NULL, event VARCHAR(16) NOT NULL, "
                        + "actor VARCHAR(36), other VARCHAR(36), item_key VARCHAR(64), quantity INT, amount BIGINT, "
                        + "created_at BIGINT NOT NULL)",
                "CREATE INDEX IF NOT EXISTS idx_ah_hist_actor ON ah_history(actor, event, id)",

                "CREATE TABLE IF NOT EXISTS orders (order_id " + id + ", buyer VARCHAR(36) NOT NULL, item_key VARCHAR(64) NOT NULL, "
                        + "qty_total INT NOT NULL, qty_filled INT NOT NULL DEFAULT 0, unit_price BIGINT NOT NULL, "
                        + "status VARCHAR(12) NOT NULL, version INT NOT NULL DEFAULT 0, created_at BIGINT NOT NULL, "
                        + "expires_at BIGINT NOT NULL)",
                "CREATE INDEX IF NOT EXISTS idx_orders_item ON orders(status, item_key, unit_price)",
                "CREATE INDEX IF NOT EXISTS idx_orders_buyer ON orders(buyer, status)",
                "CREATE TABLE IF NOT EXISTS order_fills (id " + id + ", order_id BIGINT NOT NULL, seller VARCHAR(36) NOT NULL, "
                        + "quantity INT NOT NULL, unit_price BIGINT NOT NULL, tx_id BIGINT, created_at BIGINT NOT NULL)",

                "CREATE TABLE IF NOT EXISTS sell_log (id " + id + ", uuid VARCHAR(36) NOT NULL, item_key VARCHAR(64) NOT NULL, "
                        + "quantity INT NOT NULL, unit_price BIGINT NOT NULL, multiplier_pct INT NOT NULL, total BIGINT NOT NULL, "
                        + "source VARCHAR(12) NOT NULL, created_at BIGINT NOT NULL)",
                "CREATE INDEX IF NOT EXISTS idx_sell_log_uuid ON sell_log(uuid, id)",
                "CREATE TABLE IF NOT EXISTS sell_progress (uuid VARCHAR(36) NOT NULL, category VARCHAR(24) NOT NULL, "
                        + "total BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (uuid, category))",
                "CREATE TABLE IF NOT EXISTS shop_log (id " + id + ", uuid VARCHAR(36) NOT NULL, item_key VARCHAR(64) NOT NULL, "
                        + "quantity INT NOT NULL, unit_price BIGINT NOT NULL, mode VARCHAR(8) NOT NULL, created_at BIGINT NOT NULL)",

                "CREATE TABLE IF NOT EXISTS homes (id " + id + ", uuid VARCHAR(36) NOT NULL, name VARCHAR(32) NOT NULL, "
                        + "world VARCHAR(64) NOT NULL, x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, "
                        + "yaw FLOAT NOT NULL, pitch FLOAT NOT NULL, created_at BIGINT NOT NULL, UNIQUE (uuid, name))",
                "CREATE TABLE IF NOT EXISTS warps (name VARCHAR(32) PRIMARY KEY, world VARCHAR(64) NOT NULL, "
                        + "x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL, pitch FLOAT NOT NULL, "
                        + "permission VARCHAR(64), created_by VARCHAR(36))",

                "CREATE TABLE IF NOT EXISTS kill_log (id " + id + ", killer VARCHAR(36), victim VARCHAR(36) NOT NULL, "
                        + "world VARCHAR(64), x INT, y INT, z INT, weapon VARCHAR(64), counted INT NOT NULL, created_at BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS bounties (id " + id + ", target VARCHAR(36) NOT NULL, placer VARCHAR(36) NOT NULL, "
                        + "amount BIGINT NOT NULL, status VARCHAR(12) NOT NULL, claimed_by VARCHAR(36), created_at BIGINT NOT NULL)",
                "CREATE INDEX IF NOT EXISTS idx_bounty_target ON bounties(target, status)",

                "CREATE TABLE IF NOT EXISTS enchant_log (id " + id + ", uuid VARCHAR(36) NOT NULL, item_key VARCHAR(64), "
                        + "action VARCHAR(16) NOT NULL, details TEXT, coins BIGINT NOT NULL, xp_levels INT NOT NULL, "
                        + "result VARCHAR(12) NOT NULL, created_at BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS item_instances (uid VARCHAR(36) PRIMARY KEY, item_id VARCHAR(32) NOT NULL, "
                        + "amount INT NOT NULL, created_by VARCHAR(64), created_at BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS ignores (uuid VARCHAR(36) NOT NULL, ignored VARCHAR(36) NOT NULL, "
                        + "PRIMARY KEY (uuid, ignored))"));
    }
}
