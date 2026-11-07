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

/**
 * v13 -> v14: the figures a report states (photos, objects, health) are saved with it, computed
 * from the sessions it was written from. Nullable: old reports keep NULL and the screen falls
 * back to the sessions that still exist.
 */
internal val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE generated_reports ADD COLUMN figPhotos INTEGER")
        db.execSQL("ALTER TABLE generated_reports ADD COLUMN figObjects INTEGER")
        db.execSQL("ALTER TABLE generated_reports ADD COLUMN figHealth INTEGER")
    }
}
