package io.github.vitalyostanin.pushmenot.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.vitalyostanin.pushmenot.rules.RuleJson
import java.util.UUID

@Database(
    entities = [
        SourceSettings::class,
        JournalItem::class,
        RuleSet::class,
        FrequencySnapshot::class,
        JournalSettings::class,
        JournalState::class,
        JournalMedia::class,
        RuleSnapshot::class,
        RuleRevisionFloor::class,
        NotificationCount::class,
        StatisticsStart::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class PushDatabase : RoomDatabase() {
    internal abstract fun data(): PushDao

    val storage: PushStorage by lazy { PushStorage(this) }

    companion object {
        private val addRuleScope = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE journal ADD COLUMN ruleScope TEXT")
            }
        }

        private val addJournalSettings = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS journal_settings (singleton INTEGER NOT NULL PRIMARY KEY, capacity INTEGER NOT NULL)",
                )
            }
        }

        internal val addGlobalRetention = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Recreate this small table to keep compatibility with SQLite on API 34.
                db.execSQL("CREATE TABLE sources_new (packageName TEXT NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL, consentRevision INTEGER NOT NULL)")
                db.execSQL("INSERT INTO sources_new SELECT packageName, enabled, consentRevision FROM sources")
                db.execSQL("DROP TABLE sources")
                db.execSQL("ALTER TABLE sources_new RENAME TO sources")
                db.execSQL("ALTER TABLE journal_settings ADD COLUMN retainContent INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE journal_settings ADD COLUMN retentionRevision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE journal ADD COLUMN archivedText TEXT")
                db.execSQL("CREATE TABLE journal_media (journalId INTEGER NOT NULL PRIMARY KEY, image BLOB NOT NULL, FOREIGN KEY(journalId) REFERENCES journal(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }

        internal val addHistoryAndStatistics = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rule_set ADD COLUMN activeRevisionId TEXT")
                db.execSQL("CREATE TABLE rule_history (revisionId TEXT NOT NULL, createdAtMillis INTEGER, reason TEXT NOT NULL, count INTEGER NOT NULL, rulesJson TEXT NOT NULL, sequence INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX index_rule_history_revisionId ON rule_history (revisionId)")
                db.execSQL("CREATE TABLE rule_revision_floor (ruleId TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL)")
                db.execSQL("INSERT INTO rule_revision_floor SELECT ruleId, MAX(ruleRevision) FROM journal WHERE ruleId IS NOT NULL AND ruleRevision IS NOT NULL GROUP BY ruleId")
                db.execSQL("CREATE TABLE notification_counts (lifecycleId TEXT NOT NULL PRIMARY KEY, receivedAtMillis INTEGER NOT NULL, dismissalRequested INTEGER NOT NULL, hidden INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX index_notification_counts_receivedAtMillis ON notification_counts (receivedAtMillis)")
                db.execSQL("CREATE TABLE statistics_start (startedAtMillis INTEGER NOT NULL, singleton INTEGER NOT NULL PRIMARY KEY)")
                db.execSQL("INSERT INTO statistics_start VALUES (?, 0)", arrayOf(System.currentTimeMillis()))
                val legacy = db.query("SELECT rulesJson, previousRulesJson FROM rule_set WHERE singleton = 0").use {
                    if (it.moveToFirst()) it.getString(0) to it.getString(1) else null
                }
                if (legacy != null) {
                    legacy.second?.let { insertSnapshot(db, it, "BASELINE") }
                    val current = insertSnapshot(db, legacy.first, "BASELINE")
                    db.execSQL("UPDATE rule_set SET activeRevisionId = ? WHERE singleton = 0", arrayOf(current))
                }
            }

            private fun insertSnapshot(db: SupportSQLiteDatabase, rulesJson: String, reason: String): String {
                val id = UUID.randomUUID().toString()
                val rules = RuleJson.decode(rulesJson)
                db.execSQL(
                    "INSERT INTO rule_history(revisionId,reason,count,rulesJson) VALUES(?,?,?,?)",
                    arrayOf<Any>(id, reason, rules.size, RuleJson.encode(rules)),
                )
                for (rule in rules) {
                    db.execSQL(
                        "INSERT OR IGNORE INTO rule_revision_floor(ruleId, revision) VALUES(?, ?)",
                        arrayOf<Any>(rule.id, rule.revision),
                    )
                    db.execSQL(
                        "UPDATE rule_revision_floor SET revision = MAX(revision, ?) WHERE ruleId = ?",
                        arrayOf<Any>(rule.revision, rule.id),
                    )
                }
                return id
            }
        }

        private val addJournalState = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE journal ADD COLUMN changedRevision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX index_journal_changedRevision ON journal (changedRevision)")
                db.execSQL("CREATE TABLE journal_state (actualCount INTEGER NOT NULL, revision INTEGER NOT NULL, resetRevision INTEGER NOT NULL, singleton INTEGER NOT NULL PRIMARY KEY)")
                db.execSQL("INSERT INTO journal_state SELECT COUNT(*), 0, 0, 0 FROM journal")
            }
        }

        @Volatile
        private var instance: PushDatabase? = null

        fun open(context: Context): PushDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PushDatabase::class.java,
                "pushmenot.db",
            ).addMigrations(addRuleScope, addJournalSettings, addGlobalRetention, addHistoryAndStatistics, addJournalState).build().also { instance = it }
        }
    }
}
