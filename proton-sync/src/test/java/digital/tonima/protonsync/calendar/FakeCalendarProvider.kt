package digital.tonima.protonsync.calendar

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.BaseColumns
import android.provider.CalendarContract

/**
 * In-memory stand-in for CalendarProvider, registered against [CalendarContract.AUTHORITY]
 * through ShadowContentResolver so [NativeCalendarWriter] is exercised against a real
 * ContentResolver with real applyBatch/back-reference semantics.
 *
 * Deliberately minimal: implements exactly the URI shapes and the selection grammar the
 * writer uses, and mirrors the real provider where it matters for the sync invariants:
 * - applyBatch is NOT overridden: the base ContentProvider implementation resolves
 *   withValueBackReference placeholders and dispatches into insert/update/delete here;
 * - inserts do not cascade: deleting an Events row does not touch Instances/Attendees, so
 *   tests prove the writer's explicit multi-table deletes (invariant I3);
 * - an event insert materialises an Instances row (like the real provider), which the writer
 *   must also clean up;
 * - [rejectUid2445Column] emulates OEM providers that predate the API-35 UID_2445 column by
 *   throwing on queries that project it.
 *
 * Selection grammar (superset of the wave-1 fake): equality conjunctions, parenthesised
 * OR/AND groups, and `IN (...)` lists — the writer's event sweep, exception-row reconcile
 * and default-reminder queries.
 */
class FakeCalendarProvider : ContentProvider() {
    /** When true, any query whose projection contains UID_2445 throws IllegalArgumentException. */
    var rejectUid2445Column: Boolean = false

    /** Every URI handed to a mutating call — used to assert the I1 sync-adapter parameters. */
    val writtenUris = mutableListOf<Uri>()

    var insertCalls: Int = 0
        private set
    var updateCalls: Int = 0
        private set
    var deleteCalls: Int = 0
        private set

    private data class Table(
        var nextId: Long = 1L,
        val rows: LinkedHashMap<Long, MutableMap<String, Any?>> = LinkedHashMap(),
    ) {
        fun insert(
            baseUri: Uri,
            values: ContentValues,
        ): Uri {
            val id = nextId++
            val row = mutableMapOf<String, Any?>(BaseColumns._ID to id)
            values.valueSet().forEach { (key, value) -> row[key] = value }
            rows[id] = row
            return ContentUris.withAppendedId(baseUri, id)
        }
    }

    private val calendars = Table()
    private val events = Table()
    private val instances = Table()
    private val attendees = Table()
    private val reminders = Table()

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = "vnd.android.cursor.dir/event"

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val (table, idConstraint, extraWhere) = tableFor(uri)
        val columns =
            projection?.toList() ?: table.rows.values.flatMap { it.keys }.distinct()
        if (rejectUid2445Column && columns.any { it == CalendarContract.Events.UID_2445 }) {
            throw IllegalArgumentException(
                "column '${CalendarContract.Events.UID_2445}' not supported by this provider",
            )
        }
        val cursor = MatrixCursor(columns.toTypedArray())
        for ((id, row) in table.rows) {
            if (idConstraint != null && id != idConstraint) continue
            if (!matchesExtra(extraWhere, row)) continue
            if (!matches(selection, selectionArgs, row)) continue
            cursor.addRow(columns.map { row[it] }.toTypedArray())
        }
        return cursor
    }

    /** The "attendees"/"reminders" path segments (see NativeCalendarWriter rationale). */
    private val attendeesPath = "attendees"
    private val remindersPath = "reminders"

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? {
        requireNotNull(values) { "insert values must not be null" }
        writtenUris += uri
        insertCalls++
        val segments = uri.pathSegments
        return when {
            segments.firstOrNull() == "calendars" -> calendars.insert(CalendarContract.Calendars.CONTENT_URI, values)

            segments.firstOrNull() == "events" &&
                (
                    segments.getOrNull(1) == attendeesPath ||
                        segments.getOrNull(2) == attendeesPath
                ) ->
                attendees.insert(CalendarContract.Attendees.CONTENT_URI, values)

            segments.firstOrNull() == "events" &&
                (
                    segments.getOrNull(1) == remindersPath ||
                        segments.getOrNull(2) == remindersPath
                ) ->
                reminders.insert(CalendarContract.Reminders.CONTENT_URI, values)

            segments.firstOrNull() == "events" -> {
                val eventUri = events.insert(CalendarContract.Events.CONTENT_URI, values)
                // The real provider materialises an Instances row per event.
                instances.insert(
                    CalendarContract.Instances.CONTENT_URI,
                    ContentValues().apply {
                        put(CalendarContract.Instances.EVENT_ID, ContentUris.parseId(eventUri))
                    },
                )
                eventUri
            }

            else -> throw IllegalArgumentException("Unsupported insert URI: $uri")
        }
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int {
        writtenUris += uri
        updateCalls++
        val (table, idConstraint, extraWhere) = tableFor(uri)
        var count = 0
        for ((id, row) in table.rows) {
            if (idConstraint != null && id != idConstraint) continue
            if (!matchesExtra(extraWhere, row)) continue
            if (!matches(selection, selectionArgs, row)) continue
            requireNotNull(values).valueSet().forEach { (key, value) -> row[key] = value }
            count++
        }
        return count
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int {
        writtenUris += uri
        deleteCalls++
        val (table, idConstraint, extraWhere) = tableFor(uri)
        val doomed =
            table.rows.filterKeys { id ->
                (idConstraint == null || id == idConstraint) &&
                    matchesExtra(extraWhere, table.rows[id]!!) &&
                    matches(selection, selectionArgs, table.rows[id]!!)
            }.keys
        doomed.forEach { table.rows.remove(it) }
        return doomed.size
    }

    private fun tableFor(uri: Uri): Triple<Table, Long?, String?> {
        val segments = uri.pathSegments
        return when {
            segments.firstOrNull() == "calendars" -> Triple(calendars, segments.getOrNull(1)?.toLongOrNull(), null)

            segments.firstOrNull() == "instances" -> Triple(instances, segments.getOrNull(1)?.toLongOrNull(), null)

            segments.firstOrNull() == "attendees" -> Triple(attendees, segments.getOrNull(1)?.toLongOrNull(), null)

            segments.firstOrNull() == "reminders" -> Triple(reminders, segments.getOrNull(1)?.toLongOrNull(), null)

            segments.firstOrNull() == "events" &&
                segments.getOrNull(2) == attendeesPath ->
                Triple(attendees, null, "event_id=${segments[1]}")

            segments.firstOrNull() == "events" &&
                segments.getOrNull(2) == remindersPath ->
                Triple(reminders, null, "event_id=${segments[1]}")

            segments.firstOrNull() == "events" -> Triple(events, segments.getOrNull(1)?.toLongOrNull(), null)

            else -> throw IllegalArgumentException("Unsupported URI: $uri")
        }
    }

    /**
     * The selection grammar the writer emits: equality conjunctions, parenthesised OR/AND
     * groups (`(a=? AND (b=? OR c=?))`) and `IN (...)` lists. A tiny recursive evaluator —
     * deliberately not a full SQL engine.
     */
    private fun matches(
        selection: String?,
        selectionArgs: Array<String>?,
        row: MutableMap<String, Any?>,
    ): Boolean {
        if (selection.isNullOrBlank()) return true
        val args = selectionArgs?.toList() ?: emptyList()
        var argIndex = 0

        fun findTopLevel(expr: String, operator: String): List<Int> {
            val result = mutableListOf<Int>()
            var depth = 0
            var i = 0
            while (i <= expr.length - operator.length) {
                val c = expr[i]
                if (c == '(') depth++
                if (c == ')') depth--
                if (depth == 0 && expr.startsWith(operator, i)) {
                    result += i
                    i += operator.length
                } else {
                    i++
                }
            }
            return result
        }

        fun eval(expr: String): Boolean {
            // Strip wrapping parens FIRST: the OR/AND scan below only splits at top level,
            // so a fully wrapped group like "(a=? AND (b=? OR c=?))" would otherwise fall
            // through to the leaf matchers unhandled.
            var current = expr.trim()
            while (current.startsWith("(") && current.endsWith(")")) {
                current = current.removePrefix("(").removeSuffix(")").trim()
            }
            for (orIndex in findTopLevel(current, " OR ")) {
                return eval(current.substring(0, orIndex)) || eval(current.substring(orIndex + 4))
            }
            for (andIndex in findTopLevel(current, " AND ")) {
                return eval(current.substring(0, andIndex)) && eval(current.substring(andIndex + 5))
            }
            val equality = Regex("^(\\w+)\\s*=\\s*\\?$").find(current)
            if (equality != null) {
                val column = equality.groupValues[1]
                val arg = args.getOrNull(argIndex++)
                    ?: throw IllegalArgumentException("Missing selection arg for '$selection'")
                return row[column]?.toString() == arg
            }
            val inList = Regex("^(\\w+)\\s+IN\\s+\\((.*)\\)$").find(current)
            if (inList != null) {
                val column = inList.groupValues[1]
                val values = inList.groupValues[2].split(",").map { it.trim() }
                val argsSlice = args.subList(argIndex, argIndex + values.size)
                argIndex += values.size
                return row[column]?.toString() in argsSlice
            }
            throw IllegalArgumentException("Unsupported selection: $selection")
        }

        return eval(selection)
    }

    private fun matchesExtra(
        extra: String?,
        row: MutableMap<String, Any?>,
    ): Boolean {
        if (extra == null) return true
        val match =
            Regex("^event_id=(\\d+)$").find(extra)
                ?: throw IllegalArgumentException("Unsupported URI constraint: $extra")
        return row[CalendarContract.Attendees.EVENT_ID]?.toString() == match.groupValues[1]
    }
}
