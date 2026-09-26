package com.macroandroid.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Hand-written migrations; the schema JSON under `schemas/` is the reference for the expected DDL. */
object Migrations {
    /** v2: Trigger Area configurations (Phase 12). */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `trigger_configs` (" +
                    "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `package_name` TEXT, " +
                    "`config_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_trigger_configs_package_name` ON `trigger_configs` (`package_name`)",
            )
        }
    }

    val ALL: List<Migration> get() = listOf(MIGRATION_1_2)
}
