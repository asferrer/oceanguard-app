package com.oceanguard.ai.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v12 -> v13: traceability of what ran.
 *
 * - `detection_sessions.modelName`: model that produced the analysis. The time was already saved
 *   in `processingTimeMs`.
 * - `generated_reports.modelName`: model that wrote the report, which can differ from the chosen
 *   one after a retry with a smaller tier.
 *
 * Both are nullable and stay NULL on old rows: nobody knows what ran then and the app must say so
 * instead of guessing.
 */
internal val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE detection_sessions ADD COLUMN modelName TEXT")
        db.execSQL("ALTER TABLE generated_reports ADD COLUMN modelName TEXT")
    }
}
