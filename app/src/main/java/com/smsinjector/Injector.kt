package com.smsinjector

import android.util.Base64
import com.topjohnwu.superuser.Shell

object Injector {

    private val MMS_DB   = "/data/data/com.android.providers.telephony/databases/mmssms.db"
    private val BUGLE_DB = "/data/data/com.google.android.apps.messaging/databases/bugle_db"

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

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun su(cmd: String): Shell.Result =
        Shell.cmd("export PATH=/system/bin:/system/xbin:/sbin:\$PATH; $cmd").exec()

    private fun sqlExec(db: String, sql: String): Shell.Result {
        val b64 = Base64.encodeToString(sql.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val cmd = "echo $b64 | base64 -d > /data/local/tmp/.si.sql " +
                  "&& $sq3 $db < /data/local/tmp/.si.sql; " +
                  "rc=\$?; rm -f /data/local/tmp/.si.sql; exit \$rc"
        return su(cmd)
    }

    private fun sqlRows(db: String, query: String): List<List<String>> {
        val b64 = Base64.encodeToString("$query;".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val cmd = "echo $b64 | base64 -d > /data/local/tmp/.sr.sql " +
                  "&& $sq3 $db < /data/local/tmp/.sr.sql; " +
                  "rc=\$?; rm -f /data/local/tmp/.sr.sql; exit \$rc"
        val result = su(cmd)
        if (result.code != 0) return emptyList()
        return result.out
            .filter { it.isNotBlank() }
            .map { it.split("|") }
    }

    private fun escape(s: String) = s.replace("'", "''")

    // ── Public startup ────────────────────────────────────────────────────────

    private var sq3: String = ""
    private var smsPkg: String = ""
    private var isGoogleMessages: Boolean = false

    data class StartupInfo(
        val sq3: String,
        val pkg: String,
        val isGoogle: Boolean,
        val error: String? = null
    )

    fun detectTools(): StartupInfo {
        // Find sqlite3
        val foundSq3 = SQLITE3_PATHS.firstOrNull { path ->
            su("test -x $path").code == 0
        } ?: return StartupInfo("", "", false, "sqlite3 not found on device")

        // Find SMS app
        val foundPkg = SMS_PACKAGES.firstOrNull { pkg ->
            su("test -d /data/data/$pkg").code == 0
        } ?: return StartupInfo(foundSq3, "", false, "No SMS app found")

        sq3 = foundSq3
        smsPkg = foundPkg
        isGoogleMessages = foundPkg == "com.google.android.apps.messaging"
        return StartupInfo(foundSq3, foundPkg, isGoogleMessages)
    }

    // ── Thread list ───────────────────────────────────────────────────────────

    data class SmsThread(
        val id: Int,
        val address: String,
        val snippet: String,
        val date: String
    ) {
        override fun toString(): String {
            val snip = if (snippet.length > 50) snippet.take(50) + "…" else snippet
            return "$address — $snip ($date)"
        }
    }

    fun loadThreads(): List<SmsThread> {
        val rows = sqlRows(MMS_DB,
            "SELECT t._id, t.date, t.snippet, a.address " +
            "FROM threads t LEFT JOIN canonical_addresses a ON t.recipient_ids=a._id " +
            "ORDER BY t.date DESC LIMIT 200")
        return rows.mapNotNull { r ->
            if (r.size < 4) return@mapNotNull null
            val tsMs = r[1].trim().toLongOrNull() ?: return@mapNotNull null
            val date = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
                .format(java.util.Date(tsMs))
            SmsThread(
                id      = r[0].trim().toIntOrNull() ?: return@mapNotNull null,
                address = r[3].trim().ifEmpty { "Unknown" },
                snippet = r[2].trim(),
                date    = date
            )
        }
    }

    // ── Core injection ────────────────────────────────────────────────────────

    data class InjectResult(val ok: Boolean, val message: String)

    fun getOrCreateThreadId(sender: String): Int? {
        val result = su("content query --uri 'content://mms-sms/threadID?recipient=$sender'")
        val out = result.out.joinToString("\n")
        if ("_id=" in out) {
            out.replace(",", " ").split("\\s+".toRegex()).forEach { part ->
                if (part.startsWith("_id=")) {
                    return part.removePrefix("_id=").trim().toIntOrNull()
                }
            }
        }
        return null
    }

    fun getThreadSender(threadId: Int): String {
        val rows = sqlRows(MMS_DB,
            "SELECT a.address FROM threads t " +
            "LEFT JOIN canonical_addresses a ON t.recipient_ids=a._id " +
            "WHERE t._id=$threadId LIMIT 1")
        return rows.firstOrNull()?.firstOrNull()?.trim() ?: "Unknown"
    }

    fun inject(sender: String, body: String, tsMs: Long, threadId: Int): InjectResult {
        val errors = mutableListOf<String>()
        val bodySq  = escape(body)
        val senderSq = escape(sender)
        val snipSq  = escape(body.take(100))

        // 1. Insert into mmssms.db
        val mmsSql = """
            DROP TRIGGER IF EXISTS sms_words_update;
            INSERT INTO sms (address, body, date, date_sent, type, read, seen, status, thread_id)
              VALUES ('$senderSq', '$bodySq', $tsMs, $tsMs, 1, 0, 0, -1, $threadId);
            $FTS_TRIGGER
            UPDATE threads SET date=$tsMs, snippet='$snipSq' WHERE _id=$threadId;
        """.trimIndent()

        val mmsResult = sqlExec(MMS_DB, mmsSql)
        if (mmsResult.code != 0) errors += "mmssms: ${mmsResult.out.joinToString()}"

        // Get the new sms _id
        val smsIdRows = sqlRows(MMS_DB,
            "SELECT _id FROM sms WHERE thread_id=$threadId ORDER BY _id DESC LIMIT 1")
        val smsId = smsIdRows.firstOrNull()?.firstOrNull()?.trim()

        // 2. Insert into bugle_db (Google Messages only)
        if (isGoogleMessages && smsId != null) {
            val convRows = sqlRows(BUGLE_DB,
                "SELECT _id, current_self_id FROM conversations WHERE sms_thread_id=$threadId LIMIT 1")
            val convId = convRows.firstOrNull()?.getOrNull(0)?.trim()
            val selfId = convRows.firstOrNull()?.getOrNull(1)?.trim() ?: "4"

            if (convId == null) {
                errors += "bugle: no conversation for this thread — open it in Messages once, then retry"
            } else {
                // Ensure participant exists
                val bodySqB = escape(body)
                val senderSqB = escape(sender)
                val snipSqB = escape(body.take(80))

                val partRows = sqlRows(BUGLE_DB,
                    "SELECT _id FROM participants WHERE normalized_destination='$senderSqB' LIMIT 1")
                val pid = if (partRows.isNotEmpty()) {
                    partRows.first().first().trim()
                } else {
                    sqlExec(BUGLE_DB,
                        "INSERT INTO participants (normalized_destination, full_name) " +
                        "VALUES ('$senderSqB', '$senderSqB');")
                    sqlRows(BUGLE_DB,
                        "SELECT _id FROM participants WHERE normalized_destination='$senderSqB' LIMIT 1")
                        .firstOrNull()?.firstOrNull()?.trim()
                }

                if (pid == null) {
                    errors += "bugle: could not create participant"
                } else {
                    val bugleSql = """
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
                          VALUES (last_insert_rowid(), $convId, 'text/plain', '$bodySqB', $tsMs);
                        UPDATE conversations
                          SET latest_message_id=(SELECT _id FROM messages WHERE sms_message_uri='content://sms/$smsId'),
                              sort_timestamp=$tsMs,
                              snippet_text='$snipSqB'
                          WHERE _id=$convId;
                    """.trimIndent()

                    val bugleResult = sqlExec(BUGLE_DB, bugleSql)
                    if (bugleResult.code != 0) errors += "bugle: ${bugleResult.out.joinToString()}"
                }
            }
        }

        // 3. Restart SMS app
        su("am force-stop $smsPkg")
        Thread.sleep(1000)
        su("monkey -p $smsPkg -c android.intent.category.LAUNCHER 1")

        return if (errors.isEmpty()) {
            val when_ = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(tsMs))
            InjectResult(true, "Done.\nFrom: $sender\nThread: $threadId\nTime: $when_\nMessages restarted.")
        } else {
            InjectResult(false, "Errors:\n• " + errors.joinToString("\n• "))
        }
    }
}
