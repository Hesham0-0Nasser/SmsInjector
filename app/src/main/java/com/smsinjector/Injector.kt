package com.smsinjector

import android.util.Base64
import com.topjohnwu.superuser.Shell

object Injector {

    private const val MMS_DB   = "/data/data/com.android.providers.telephony/databases/mmssms.db"
    private const val BUGLE_DB = "/data/data/com.google.android.apps.messaging/databases/bugle_db"

    private val SQLITE3_PATHS = listOf(
        "/data/adb/modules/PixelXpert/sqlite3",
        "/system/bin/sqlite3",
        "/system/xbin/sqlite3",
        "/data/local/tmp/sqlite3"
    )
    private val SMS_PACKAGES = listOf(
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.mms"
    )
    private val FTS_TRIGGER = """
        CREATE TRIGGER sms_words_update AFTER UPDATE ON sms
        BEGIN UPDATE words SET index_text = NEW.body
        WHERE (source_id=NEW._id AND table_to_use=1); END;
    """.trimIndent()

    var sq3: String = ""; private set
    var smsPkg: String = ""; private set
    var isGoogleMessages: Boolean = false; private set

    // ── Root shell helpers ────────────────────────────────────────────────────

    // Shell.su() uses libsu's root shell — properly triggers Magisk auth dialog.
    private fun su(cmd: String): Pair<Int, String> {
        val result = Shell.su(
            "export PATH=/system/bin:/system/xbin:/sbin:\$PATH; $cmd"
        ).exec()
        return Pair(result.code, result.out.joinToString("\n"))
    }

    private fun sqlExec(db: String, sql: String): Pair<Int, String> {
        val b64 = Base64.encodeToString(sql.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return su(
            "echo $b64 | base64 -d > /data/local/tmp/.si.sql" +
            " && $sq3 $db < /data/local/tmp/.si.sql;" +
            " rc=\$?; rm -f /data/local/tmp/.si.sql; exit \$rc"
        )
    }

    private fun sqlRows(db: String, query: String): List<List<String>> {
        val b64 = Base64.encodeToString("$query;".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val (code, out) = su(
            "echo $b64 | base64 -d > /data/local/tmp/.sr.sql" +
            " && $sq3 $db < /data/local/tmp/.sr.sql;" +
            " rc=\$?; rm -f /data/local/tmp/.sr.sql; exit \$rc"
        )
        if (code != 0 || out.isBlank()) return emptyList()
        return out.lines().filter { it.isNotBlank() }.map { it.split("|") }
    }

    private fun esc(s: String) = s.replace("'", "''")

    // ── Startup ───────────────────────────────────────────────────────────────

    fun checkRoot(): Boolean = Shell.rootAccess()

    data class StartupInfo(val sq3: String, val pkg: String, val isGoogle: Boolean, val error: String? = null)

    fun detectTools(): StartupInfo {
        val foundSq3 = SQLITE3_PATHS.firstOrNull { path ->
            su("test -x $path").first == 0
        } ?: return StartupInfo("", "", false,
            "sqlite3 not found.\nInstall PixelXpert Magisk module or put sqlite3 at /data/local/tmp/sqlite3")

        val foundPkg = SMS_PACKAGES.firstOrNull { pkg ->
            su("test -d /data/data/$pkg").first == 0
        } ?: return StartupInfo(foundSq3, "", false, "No SMS app found")

        sq3 = foundSq3
        smsPkg = foundPkg
        isGoogleMessages = foundPkg == "com.google.android.apps.messaging"
        return StartupInfo(foundSq3, foundPkg, isGoogleMessages)
    }

    // ── Thread list ───────────────────────────────────────────────────────────

    data class SmsThread(val id: Int, val address: String, val snippet: String, val date: String) {
        override fun toString(): String {
            val snip = snippet.take(50).let { if (snippet.length > 50) "$it…" else it }
            return "$address  —  $snip  ($date)"
        }
    }

    fun loadThreads(): List<SmsThread> {
        return sqlRows(MMS_DB,
            "SELECT t._id, t.date, t.snippet, a.address " +
            "FROM threads t LEFT JOIN canonical_addresses a ON t.recipient_ids=a._id " +
            "ORDER BY t.date DESC LIMIT 200"
        ).mapNotNull { r ->
            if (r.size < 4) return@mapNotNull null
            val tsMs = r[1].trim().toLongOrNull() ?: return@mapNotNull null
            SmsThread(
                id      = r[0].trim().toIntOrNull() ?: return@mapNotNull null,
                address = r[3].trim().ifEmpty { "Unknown" },
                snippet = r[2].trim(),
                date    = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
                              .format(java.util.Date(tsMs))
            )
        }
    }

    // ── Injection ─────────────────────────────────────────────────────────────

    data class InjectResult(val ok: Boolean, val message: String)

    fun getOrCreateThreadId(sender: String): Int? {
        val (_, out) = su("content query --uri 'content://mms-sms/threadID?recipient=$sender'")
        if ("_id=" in out) {
            out.replace(",", " ").split("\\s+".toRegex()).forEach { part ->
                if (part.startsWith("_id=")) return part.removePrefix("_id=").trim().toIntOrNull()
            }
        }
        return null
    }

    fun getThreadSender(threadId: Int): String {
        return sqlRows(MMS_DB,
            "SELECT a.address FROM threads t " +
            "LEFT JOIN canonical_addresses a ON t.recipient_ids=a._id " +
            "WHERE t._id=$threadId LIMIT 1"
        ).firstOrNull()?.firstOrNull()?.trim() ?: "Unknown"
    }

    fun inject(sender: String, body: String, tsMs: Long, threadId: Int): InjectResult {
        val errors = mutableListOf<String>()

        // 1. mmssms.db
        val (mmsCode, mmsOut) = sqlExec(MMS_DB, """
            DROP TRIGGER IF EXISTS sms_words_update;
            INSERT INTO sms (address, body, date, date_sent, type, read, seen, status, thread_id)
              VALUES ('${esc(sender)}', '${esc(body)}', $tsMs, $tsMs, 1, 0, 0, -1, $threadId);
            $FTS_TRIGGER
            UPDATE threads SET date=$tsMs, snippet='${esc(body.take(100))}' WHERE _id=$threadId;
        """.trimIndent())
        if (mmsCode != 0) errors += "mmssms: $mmsOut"

        val smsId = sqlRows(MMS_DB,
            "SELECT _id FROM sms WHERE thread_id=$threadId ORDER BY _id DESC LIMIT 1"
        ).firstOrNull()?.firstOrNull()?.trim()

        // 2. bugle_db (Google Messages only)
        if (isGoogleMessages && smsId != null) {
            val convRows = sqlRows(BUGLE_DB,
                "SELECT _id, current_self_id FROM conversations WHERE sms_thread_id=$threadId LIMIT 1")
            val convId = convRows.firstOrNull()?.getOrNull(0)?.trim()
            val selfId = convRows.firstOrNull()?.getOrNull(1)?.trim() ?: "4"

            if (convId == null) {
                errors += "bugle: no conversation — open this contact in Messages first, then retry"
            } else {
                val partRows = sqlRows(BUGLE_DB,
                    "SELECT _id FROM participants WHERE normalized_destination='${esc(sender)}' LIMIT 1")
                val pid = if (partRows.isNotEmpty()) {
                    partRows.first().first().trim()
                } else {
                    sqlExec(BUGLE_DB,
                        "INSERT INTO participants (normalized_destination, full_name) " +
                        "VALUES ('${esc(sender)}', '${esc(sender)}');")
                    sqlRows(BUGLE_DB,
                        "SELECT _id FROM participants WHERE normalized_destination='${esc(sender)}' LIMIT 1"
                    ).firstOrNull()?.firstOrNull()?.trim()
                }

                if (pid == null) {
                    errors += "bugle: could not create participant"
                } else {
                    val (bCode, bOut) = sqlExec(BUGLE_DB, """
                        INSERT INTO messages (
                          conversation_id, sender_id,
                          sent_timestamp, queue_insert_timestamp, received_timestamp,
                          message_protocol, message_status, message_report_status,
                          seen, read, sms_message_uri, sms_priority, self_id
                        ) VALUES (
                          $convId, $pid, $tsMs, $tsMs, $tsMs,
                          0, 100, 0, 0, 0, 'content://sms/$smsId', 129, $selfId
                        );
                        INSERT INTO parts (message_id, conversation_id, content_type, text, timestamp)
                          VALUES (last_insert_rowid(), $convId, 'text/plain', '${esc(body)}', $tsMs);
                        UPDATE conversations
                          SET latest_message_id=(SELECT _id FROM messages WHERE sms_message_uri='content://sms/$smsId'),
                              sort_timestamp=$tsMs,
                              snippet_text='${esc(body.take(80))}'
                          WHERE _id=$convId;
                    """.trimIndent())
                    if (bCode != 0) errors += "bugle: $bOut"
                }
            }
        }

        // 3. Restart SMS app
        su("am force-stop $smsPkg")
        Thread.sleep(1000)
        su("monkey -p $smsPkg -c android.intent.category.LAUNCHER 1")

        return if (errors.isEmpty()) {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            InjectResult(true, "Done.\nFrom: $sender\nThread: $threadId\nTime: ${fmt.format(java.util.Date(tsMs))}")
        } else {
            InjectResult(false, "Errors:\n• " + errors.joinToString("\n• "))
        }
    }
}
