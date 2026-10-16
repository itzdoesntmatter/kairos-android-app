package digital.tonima.protonsync.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models for the calendar family (calendar-api.md §2/§3). All field names and semantics
 * are documented per-field with their spec section; unknown JSON keys are ignored by the
 * shared Json configuration so forward server changes degrade gracefully, while fields the
 * engine depends on are required (non-null, no default) so API drift fails loudly instead of
 * silently syncing garbage (architecture §8).
 */

/** GET /calendar/v1 — the account's calendar list. */
@Serializable
data class CalendarListResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("Calendars") val calendars: List<CalendarInfo> = emptyList(),
)

@Serializable
data class CalendarInfo(
    @SerialName("ID") val id: String = "",
    @SerialName("Name") val name: String = "",
    @SerialName("Description") val description: String? = null,
    /** RGB colour as a hex string like `#rrggbb`. */
    @SerialName("Color") val color: String? = null,
    @SerialName("Display") val display: Int = 0,
    /** 0 = personal/owned; other = subscribed/shared (calendar-api.md §3). */
    @SerialName("Type") val type: Int = 0,
    @SerialName("Flags") val flags: Int = 0,
    @SerialName("Members") val members: List<CalendarMember> = emptyList(),
)

@Serializable
data class CalendarMember(
    @SerialName("ID") val id: String = "",
    @SerialName("CalendarID") val calendarId: String = "",
    @SerialName("AddressID") val addressId: String = "",
    @SerialName("Email") val email: String = "",
    /** Bit 16 = editable (calendar-api.md §3). */
    @SerialName("Permissions") val permissions: Int = 0,
    @SerialName("Display") val display: Int = 0,
    @SerialName("Color") val color: String? = null,
) {
    companion object {
        const val PERMISSION_EDITABLE = 16
    }
}

/** GET /calendar/v1/{id}/keys — the calendar's private key(s). */
@Serializable
data class CalendarKeyResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("Keys") val keys: List<CalendarKey> = emptyList(),
)

@Serializable
data class CalendarKey(
    @SerialName("ID") val id: String = "",
    @SerialName("CalendarID") val calendarId: String = "",
    /** Armored PGP private key (calendar-api.md §3). */
    @SerialName("PrivateKey") val privateKey: String = "",
    @SerialName("PassphraseID") val passphraseId: String = "",
    /** Bits 1+2 together = primary key (calendar-api.md §3). */
    @SerialName("Flags") val flags: Int = 0,
) {
    val isPrimary: Boolean get() = flags and 1 != 0 && flags and 2 != 0
}

/** GET /calendar/v1/{id}/passphrase — member passphrases encrypted to the user key. */
@Serializable
data class CalendarPassphraseResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("Passphrases") val passphrases: List<CalendarPassphrase> = emptyList(),
    @SerialName("Passphrase") val passphrase: CalendarPassphrase? = null,
)

@Serializable
data class CalendarPassphrase(
    @SerialName("ID") val id: String = "",
    @SerialName("Flags") val flags: Int = 0,
    @SerialName("MemberPassphrases") val memberPassphrases: List<MemberPassphrase> = emptyList(),
)

@Serializable
data class MemberPassphrase(
    @SerialName("MemberID") val memberId: String = "",
    /** Armored, encrypted to the user key (calendar-api.md §3). */
    @SerialName("Passphrase") val passphrase: String = "",
    @SerialName("Signature") val signature: String? = null,
)

/** GET /calendar/v1/{id}/settings. */
@Serializable
data class CalendarSettingsResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("CalendarSettings") val settings: CalendarSettingsData? = null,
)

@Serializable
data class CalendarSettingsData(
    @SerialName("DefaultEventDuration") val defaultEventDuration: Int? = null,
    @SerialName("DefaultFullDayNotifications")
    val defaultFullDayNotifications: List<EventNotification> = emptyList(),
    @SerialName("DefaultPartDayNotifications")
    val defaultPartDayNotifications: List<EventNotification> = emptyList(),
)

@Serializable
data class EventNotification(
    @SerialName("Type") val type: Int = 0,
    /** ISO-8601 duration (calendar-api.md §3). */
    @SerialName("Trigger") val trigger: String? = null,
)

/** GET /calendar/v1/{id}/events — one page of a time window. */
@Serializable
data class CalendarEventListResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("Events") val events: List<CalendarEventDetail> = emptyList(),
)

/** GET /calendar/v1/{id}/events/{eventId}. */
@Serializable
data class CalendarEventDetailResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("Event") val event: CalendarEventDetail? = null,
)

/**
 * The full event payload (calendar-api.md §3). Times are Unix SECONDS on the wire — the
 * engine multiplies by 1000 when mapping to the native provider (calendar-api.md legend).
 */
@Serializable
data class CalendarEventDetail(
    @SerialName("ID") val id: String = "",
    @SerialName("CalendarID") val calendarId: String = "",
    /** base64 PKESK encrypted to the calendar key (crypto-envelope.md §3). */
    @SerialName("CalendarKeyPacket") val calendarKeyPacket: String? = null,
    @SerialName("CreateTime") val createTime: Long? = null,
    /** Seconds; the engine's refresh rule compares stored last_modified against this. */
    @SerialName("LastEditTime") val lastEditTime: Long? = null,
    @SerialName("StartTime") val startTime: Long? = null,
    @SerialName("EndTime") val endTime: Long? = null,
    @SerialName("FullDay") val fullDay: Int = 0,
    @SerialName("UID") val uid: String? = null,
    @SerialName("IsOrganizer") val isOrganizer: Int = 0,
    @SerialName("IsProtonProtonInvite") val isProtonProtonInvite: Int? = null,
    /** base64 PKESK encrypted to the address key (crypto-envelope.md §3). */
    @SerialName("AddressKeyPacket") val addressKeyPacket: String? = null,
    @SerialName("AddressID") val addressId: String? = null,
    @SerialName("SharedKeyPacket") val sharedKeyPacket: String? = null,
    @SerialName("SharedEvents") val sharedEvents: List<EventPart> = emptyList(),
    @SerialName("CalendarEvents") val calendarEvents: List<EventPart> = emptyList(),
    @SerialName("PersonalEvents") val personalEvents: List<EventPart> = emptyList(),
    @SerialName("AttendeesEvents") val attendeesEvents: List<EventPart> = emptyList(),
    @SerialName("Attendees") val attendees: List<EventAttendee> = emptyList(),
    @SerialName("AttendeesInfo") val attendeesInfo: AttendeesInfo? = null,
    @SerialName("Color") val color: String? = null,
    @SerialName("Notifications") val notifications: List<EventNotification> = emptyList(),
    @SerialName("SharedEventID") val sharedEventId: String? = null,
) {
    /** AttendeesInfo.Attendees when present, else the legacy Attendees list (calendar-api.md §3). */
    val effectiveAttendees: List<EventAttendee>
        get() = attendeesInfo?.attendees ?: attendees
}

/**
 * One encrypted/plaintext event part. Type semantics per crypto-envelope.md §3:
 * 0 plaintext; 1 plaintext if `BEGIN:`-prefixed else binary; 2 plaintext iCal (main content);
 * 3 binary-decrypt then verify detached signature over the decrypted text.
 */
@Serializable
data class EventPart(
    @SerialName("Type") val type: Int = 0,
    @SerialName("Data") val data: String? = null,
    @SerialName("Signature") val signature: String? = null,
    @SerialName("MemberID") val memberId: String? = null,
    @SerialName("Author") val author: String? = null,
)

@Serializable
data class EventAttendee(
    @SerialName("ID") val id: String = "",
    @SerialName("Token") val token: String? = null,
    /** Local status int; mapped to iCal values by the engine (calendar-api.md §3.6). */
    @SerialName("Status") val status: Int = 0,
    @SerialName("Permissions") val permissions: Int = 0,
    @SerialName("Comment") val comment: AttendeeComment? = null,
)

@Serializable
data class AttendeeComment(
    @SerialName("Message") val message: String? = null,
    @SerialName("Type") val type: Int = 0,
)

@Serializable
data class AttendeesInfo(
    @SerialName("Attendees") val attendees: List<EventAttendee>? = null,
    @SerialName("MoreAttendees") val moreAttendees: Int = 0,
)

/**
 * GET /calendar/v1/{id}/events/ids — the all-time deletion authority. The exact list field
 * name is RE VERIFY-NEEDED (calendar-api.md §7.6, consumed as a generic Map by the reference);
 * `IDs` is the documented expectation and is re-checked against a live capture in W3 before
 * the deletion pass ships.
 */
@Serializable
data class CalendarEventIdsResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("IDs") val ids: List<String> = emptyList(),
)
