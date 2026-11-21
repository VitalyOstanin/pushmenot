package io.github.vitalyostanin.pushmenot.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
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
    version = 7,
    exportSchema = true,
)
abstract class PushDatabase : RoomDatabase() {
    internal abstract fun data(): PushDao

    val storage: PushStorage by lazy { PushStorage(this) }

    companion object {
        private val addRuleScope = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE journal ADD COLUMN ruleScope TEXT")
            }
        }

        private val addJournalSettings = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS journal_settings (singleton INTEGER NOT NULL PRIMARY KEY, capacity INTEGER NOT NULL)",
                )
            }
        }

        internal val addGlobalRetention = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("CREATE TABLE sources_new (packageName TEXT NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL, consentRevision INTEGER NOT NULL)")
                connection.execSQL("INSERT INTO sources_new SELECT packageName, enabled, consentRevision FROM sources")
                connection.execSQL("DROP TABLE sources")
                connection.execSQL("ALTER TABLE sources_new RENAME TO sources")
                connection.execSQL("ALTER TABLE journal_settings ADD COLUMN retainContent INTEGER NOT NULL DEFAULT 1")
                connection.execSQL("ALTER TABLE journal_settings ADD COLUMN retentionRevision INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE journal ADD COLUMN archivedText TEXT")
                connection.execSQL("CREATE TABLE journal_media (journalId INTEGER NOT NULL PRIMARY KEY, image BLOB NOT NULL, FOREIGN KEY(journalId) REFERENCES journal(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }

        internal val addHistoryAndStatistics = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE rule_set ADD COLUMN activeRevisionId TEXT")
                connection.execSQL("CREATE TABLE rule_history (revisionId TEXT NOT NULL, createdAtMillis INTEGER, reason TEXT NOT NULL, count INTEGER NOT NULL, rulesJson TEXT NOT NULL, sequence INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
                connection.execSQL("CREATE UNIQUE INDEX index_rule_history_revisionId ON rule_history (revisionId)")
                connection.execSQL("CREATE TABLE rule_revision_floor (ruleId TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL)")
                connection.execSQL("INSERT INTO rule_revision_floor SELECT ruleId, MAX(ruleRevision) FROM journal WHERE ruleId IS NOT NULL AND ruleRevision IS NOT NULL GROUP BY ruleId")
                connection.execSQL("CREATE TABLE notification_counts (lifecycleId TEXT NOT NULL PRIMARY KEY, receivedAtMillis INTEGER NOT NULL, dismissalRequested INTEGER NOT NULL, hidden INTEGER NOT NULL)")
                connection.execSQL("CREATE INDEX index_notification_counts_receivedAtMillis ON notification_counts (receivedAtMillis)")
                connection.execSQL("CREATE TABLE statistics_start (startedAtMillis INTEGER NOT NULL, singleton INTEGER NOT NULL PRIMARY KEY)")
                connection.prepare("INSERT INTO statistics_start VALUES (?, 0)").use {
                    it.bindLong(1, System.currentTimeMillis())
                    it.step()
                }
                val legacy = connection.prepare("SELECT rulesJson, previousRulesJson FROM rule_set WHERE singleton = 0").use {
                    if (it.step()) {
                        it.getText(0) to if (it.isNull(1)) null else it.getText(1)
                    } else {
                        null
                    }
                }
                if (legacy != null) {
                    legacy.second?.let { insertSnapshot(connection, it, "BASELINE") }
                    val current = insertSnapshot(connection, legacy.first, "BASELINE")
                    connection.prepare("UPDATE rule_set SET activeRevisionId = ? WHERE singleton = 0").use {
                        it.bindText(1, current)
                        it.step()
                    }
                }
            }

            private fun insertSnapshot(connection: SQLiteConnection, rulesJson: String, reason: String): String {
                val id = UUID.randomUUID().toString()
                val rules = RuleJson.decode(rulesJson)
                connection.prepare("INSERT INTO rule_history(revisionId,reason,count,rulesJson) VALUES(?,?,?,?)").use {
                    it.bindText(1, id)
                    it.bindText(2, reason)
                    it.bindLong(3, rules.size.toLong())
                    it.bindText(4, RuleJson.encode(rules))
                    it.step()
                }
                connection.prepare(
                    "INSERT INTO rule_revision_floor(ruleId, revision) VALUES(?, ?) " +
                        "ON CONFLICT(ruleId) DO UPDATE SET revision = MAX(revision, excluded.revision)",
                ).use { statement ->
                    for (rule in rules) {
                        statement.bindText(1, rule.id)
                        statement.bindLong(2, rule.revision.toLong())
                        statement.step()
                        statement.reset()
                    }
                }
                return id
            }
        }

        private val addJournalState = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE journal ADD COLUMN changedRevision INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("CREATE INDEX index_journal_changedRevision ON journal (changedRevision)")
                connection.execSQL("CREATE TABLE journal_state (actualCount INTEGER NOT NULL, revision INTEGER NOT NULL, resetRevision INTEGER NOT NULL, singleton INTEGER NOT NULL PRIMARY KEY)")
                connection.execSQL("INSERT INTO journal_state SELECT COUNT(*), 0, 0, 0 FROM journal")
            }
        }

        private val addChannelName = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE journal ADD COLUMN channelName TEXT")
            }
        }

        @Volatile
        private var instance: PushDatabase? = null

        fun open(context: Context): PushDatabase = instance ?: synchronized(this) {
            instance ?: builder(context, "pushmenot.db").build().also { instance = it }
        }

        internal fun builder(context: Context, name: String): Builder<PushDatabase> =
            Room.databaseBuilder(context.applicationContext, PushDatabase::class.java, name)
                .setDriver(BundledSQLiteDriver())
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(
                    addRuleScope, addJournalSettings, addGlobalRetention, addHistoryAndStatistics, addJournalState,
                    addChannelName,
                )
    }
}

internal suspend fun <T> PushDatabase.writeTransaction(block: suspend () -> T): T =
    useWriterConnection { writer -> writer.immediateTransaction { block() } }
