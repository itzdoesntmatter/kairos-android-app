package digital.tonima.protonsync.api

import digital.tonima.protonsync.api.dto.AddressesResponse
import digital.tonima.protonsync.api.dto.AuthInfoResponse
import digital.tonima.protonsync.api.dto.AuthResponse
import digital.tonima.protonsync.api.dto.CalendarEventDetailResponse
import digital.tonima.protonsync.api.dto.CalendarEventIdsResponse
import digital.tonima.protonsync.api.dto.CalendarEventListResponse
import digital.tonima.protonsync.api.dto.CalendarKey
import digital.tonima.protonsync.api.dto.CalendarKeyResponse
import digital.tonima.protonsync.api.dto.CalendarListResponse
import digital.tonima.protonsync.api.dto.CalendarMember
import digital.tonima.protonsync.api.dto.CalendarPassphraseResponse
import digital.tonima.protonsync.api.dto.CalendarSettingsResponse
import digital.tonima.protonsync.api.dto.HumanVerificationDetails
import digital.tonima.protonsync.api.dto.KeySaltsResponse
import digital.tonima.protonsync.api.dto.RefreshTokenResponse
import digital.tonima.protonsync.api.dto.TwoFactorInfo
import digital.tonima.protonsync.api.dto.UserResponse
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gap-fill for [DtoSerializationTest] (specs/test-plan.md API-01/API-02/API-03): one payload
 * example per model of calendar-api.md §3, decoded AND re-encoded (round-trip) so an asymmetrical
 * `@SerialName` — the failure mode that silently drops a field — fails the build. Server-added
 * keys must stay tolerable everywhere (API-03), and times must stay Unix seconds in this layer
 * (API-02, the engine mapper owns the ×1000).
 *
 * Payload shapes are written from calendar-api.md §3 field lists; no bytes are lifted from the
 * decompiled application (cleanroom, ANALYSIS-NOTES.md).
 */
class DtoGapTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Decodes, re-encodes and re-decodes; the two decoded values must be identical. */
    private fun <T> roundTrip(serializer: KSerializer<T>, payload: String): T {
        val first = json.decodeFromString(serializer, payload)
        val second = json.decodeFromString(serializer, json.encodeToString(serializer, first))
        assertEquals(first, second)
        return first
    }

    @Test
    fun `calendar list example round-trips with members and a null colour`() {
        val payload =
            """
            {
              "Code": 1000,
              "Calendars": [
                {"ID": "cal-1", "Name": "Personal", "Description": "Personal calendar",
                 "Color": "#aabbcc", "Display": 1, "Type": 0, "Flags": 2,
                 "Members": [
                   {"ID": "m1", "CalendarID": "cal-1", "AddressID": "a1", "Email": "user@proton.me",
                    "Permissions": 16, "Display": 1, "Color": "#ffffff"}]},
                {"ID": "cal-2", "Name": "Shared", "Color": null, "Display": 0, "Type": 1, "Flags": 0,
                 "Members": [], "ServerAddedField": true}
              ]
            }
            """.trimIndent()

        val parsed = roundTrip(CalendarListResponse.serializer(), payload)

        assertEquals(1000, parsed.code)
        val personal = parsed.calendars[0]
        assertEquals("Personal", personal.name)
        assertEquals("Personal calendar", personal.description)
        assertEquals("#aabbcc", personal.color)
        assertEquals(0, personal.type)
        assertEquals(CalendarMember.PERMISSION_EDITABLE, personal.members.single().permissions)
        val shared = parsed.calendars[1]
        assertNull(shared.color)
        assertEquals(1, shared.type)
        assertTrue(shared.members.isEmpty())
    }

    @Test
    fun `calendar key example round-trips and keeps the primary flag rule`() {
        val payload =
            """
            {
              "Code": 1000,
              "Keys": [
                {"ID": "key-1", "CalendarID": "cal-1",
                 "PrivateKey": "-----BEGIN PGP PRIVATE KEY BLOCK-----\nabc\n-----END PGP PRIVATE KEY BLOCK-----",
                 "PassphraseID": "pp-1", "Flags": 3},
                {"ID": "key-2", "CalendarID": "cal-1", "PrivateKey": "k2", "PassphraseID": "pp-1", "Flags": 1}
              ]
            }
            """.trimIndent()

        val parsed = roundTrip(CalendarKeyResponse.serializer(), payload)

        assertTrue(parsed.keys[0].isPrimary)
        assertTrue(!parsed.keys[1].isPrimary)
        assertTrue(parsed.keys[0].privateKey.startsWith("-----BEGIN PGP PRIVATE KEY BLOCK-----"))
        assertEquals("pp-1", parsed.keys[0].passphraseId)
    }

    @Test
    fun `calendar passphrase example carries both the list and the singular form`() {
        // f.java consumes both shapes (calendar-api.md §3) — a payload may deliver either.
        val payload =
            """
            {
              "Code": 1000,
              "Passphrases": [
                {"ID": "pp-1", "Flags": 1,
                 "MemberPassphrases": [
                   {"MemberID": "m1", "Passphrase": "-----BEGIN PGP MESSAGE-----", "Signature": "sig-1"},
                   {"MemberID": "m2", "Passphrase": "blob", "Signature": null}]}
              ],
              "Passphrase": {"ID": "pp-2", "Flags": 0, "MemberPassphrases": []}
            }
            """.trimIndent()

        val parsed = roundTrip(CalendarPassphraseResponse.serializer(), payload)

        assertEquals(1, parsed.passphrases.size)
        assertEquals(2, parsed.passphrases.single().memberPassphrases.size)
        assertNull(parsed.passphrases.single().memberPassphrases[1].signature)
        assertEquals("pp-2", parsed.passphrase!!.id)
    }

    @Test
    fun `calendar settings example round-trips the default notification triggers`() {
        val payload =
            """
            {
              "Code": 1000,
              "CalendarSettings": {
                "DefaultEventDuration": 30,
                "DefaultFullDayNotifications": [{"Type": 1, "Trigger": "-PT1D"}],
                "DefaultPartDayNotifications": [{"Type": 1, "Trigger": "-PT15M"}, {"Type": 0, "Trigger": null}]
              }
            }
            """.trimIndent()

        val parsed = roundTrip(CalendarSettingsResponse.serializer(), payload)

        val settings = parsed.settings!!
        assertEquals(30, settings.defaultEventDuration)
        assertEquals("-PT1D", settings.defaultFullDayNotifications.single().trigger)
        assertEquals("-PT15M", settings.defaultPartDayNotifications[0].trigger)
        assertNull(settings.defaultPartDayNotifications[1].trigger)
    }

    @Test
    fun `a settings response without the settings block decodes to null`() {
        val parsed = json.decodeFromString(CalendarSettingsResponse.serializer(), """{"Code": 1000}""")

        assertNull(parsed.settings)
    }

    @Test
    fun `event detail example round-trips all documented fields and stays in seconds`() {
        val event = fullEventJson()
        val parsed = roundTrip(CalendarEventListResponse.serializer(), """{"Code": 1000, "Events": [$event]}""")

        val detail = parsed.events.single()
        assertEquals("evt-1", detail.id)
        assertEquals("cal-1", detail.calendarId)
        assertEquals("ckp-b64", detail.calendarKeyPacket)
        assertEquals(1_700_000_000L, detail.createTime)
        assertEquals(1_700_000_100L, detail.lastEditTime)
        // API-02: Unix SECONDS survive the DTO layer untouched; ×1000 is the engine mapper's job.
        assertEquals(1_700_000_200L, detail.startTime)
        assertEquals(1_700_003_800L, detail.endTime)
        assertEquals(0, detail.fullDay)
        assertEquals("uid-1@proton.me", detail.uid)
        assertEquals(1, detail.isOrganizer)
        assertEquals(0, detail.isProtonProtonInvite)
        assertEquals("akp-b64", detail.addressKeyPacket)
        assertEquals("a1", detail.addressId)
        assertEquals("skp-b64", detail.sharedKeyPacket)
        assertEquals(2, detail.sharedEvents.single().type)
        assertEquals("m1", detail.sharedEvents.single().memberId)
        assertEquals("user@proton.me", detail.sharedEvents.single().author)
        assertEquals(1, detail.calendarEvents.single().type)
        assertTrue(detail.personalEvents.isEmpty())
        assertTrue(detail.attendeesEvents.isEmpty())
        assertEquals("guest@example.com", detail.attendees.single().token)
        assertEquals(3, detail.attendees.single().status)
        assertEquals(1, detail.attendees.single().comment!!.type)
        assertEquals(1, detail.attendeesInfo!!.moreAttendees)
        assertEquals("#112233", detail.color)
        assertEquals("-PT10M", detail.notifications.single().trigger)
        assertEquals("shared-1", detail.sharedEventId)
    }

    @Test
    fun `effective attendees prefer the attendees info block and fall back to the legacy list`() {
        val withInfo =
            json.decodeFromString(CalendarEventListResponse.serializer(), """{"Events": [${fullEventJson()}]}""")
                .events.single()
        assertEquals("att-2", withInfo.effectiveAttendees.single().id)

        val legacyPayload = """{"Events": [${fullEventJson(withAttendeesInfo = false)}]}"""
        val legacy =
            json.decodeFromString(CalendarEventListResponse.serializer(), legacyPayload).events.single()
        assertEquals("att-1", legacy.effectiveAttendees.single().id)
    }

    @Test
    fun `the single-event response round-trips the same detail shape`() {
        val parsed = roundTrip(
            CalendarEventDetailResponse.serializer(),
            """{"Code": 1000, "Event": ${fullEventJson()}}""",
        )

        assertEquals("evt-1", parsed.event!!.id)

        val empty = json.decodeFromString(CalendarEventDetailResponse.serializer(), """{"Code": 1000}""")
        assertNull(empty.event)
    }

    @Test
    fun `event ids example round-trips and tolerates extra keys`() {
        // The list field name is RE VERIFY-NEEDED (calendar-api.md §7.6): the consumer reads a
        // generic map, we pinned `IDs`. A live capture in W3 confirms or corrects it; until then
        // the DTO must at least tolerate whatever else the page carries.
        val parsed = roundTrip(
            CalendarEventIdsResponse.serializer(),
            """{"Code": 1000, "IDs": ["evt-1", "evt-2"], "HasMore": true, "Total": 2}""",
        )

        assertEquals(listOf("evt-1", "evt-2"), parsed.ids)
    }

    @Test
    fun `key unlock payload examples round-trip - users, addresses and key salts`() {
        val users =
            roundTrip(
                UserResponse.serializer(),
                """
                {"Code": 1000, "User": {"ID": "u1", "Name": "Display", "Keys": [
                  {"ID": "uk1", "PrivateKey": "-----BEGIN PGP PRIVATE KEY BLOCK-----", "Active": 1, "Primary": 1},
                  {"ID": "uk2", "PrivateKey": "old", "Active": 0, "Primary": 0}]}}
                """.trimIndent(),
            )
        assertEquals("u1", users.user!!.id)
        assertEquals(1, users.user!!.keys[0].active)
        assertEquals(1, users.user!!.keys[0].primary)

        val addresses =
            roundTrip(
                AddressesResponse.serializer(),
                """
                {"Code": 1000, "Addresses": [{"ID": "a1", "Email": "user@proton.me", "Keys": [
                  {"ID": "ak1", "PrivateKey": "pk1", "Token": "token-blob", "Signature": "sig", "Active": 1, "Primary": 1},
                  {"ID": "ak2", "PrivateKey": "pk2", "Active": 1, "Primary": 0}]}]}
                """.trimIndent(),
            )
        assertEquals("user@proton.me", addresses.addresses.single().email)
        assertEquals("token-blob", addresses.addresses.single().keys[0].token)
        assertNull(addresses.addresses.single().keys[1].token)

        val salts =
            roundTrip(
                KeySaltsResponse.serializer(),
                """{"Code": 1000, "KeySalts": [{"ID": "uk1", "KeySalt": "salt-1"}, {"ID": "uk2", "KeySalt": null}]}""",
            )
        assertEquals("salt-1", salts.keySalts[0].keySalt)
        assertNull(salts.keySalts[1].keySalt)
    }

    @Test
    fun `auth info and refresh response examples round-trip`() {
        val info =
            roundTrip(
                AuthInfoResponse.serializer(),
                """
                {"Code": 1000, "Modulus": "-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA256\n\nAAAA\n-----BEGIN PGP SIGNATURE-----",
                 "Salt": "yKlc5/CvObfoiw==", "ServerEphemeral": "B-B64", "SRPSession": "sess-1", "Version": 4}
                """.trimIndent(),
            )
        assertEquals(4, info.version)
        assertEquals("sess-1", info.srpSession)
        assertEquals("B-B64", info.serverEphemeral)

        val refresh =
            roundTrip(
                RefreshTokenResponse.serializer(),
                """{"Code": 1000, "AccessToken": "a", "RefreshToken": "r", "TokenType": "Bearer",
                    "Uid": "uid-1", "Scope": "full calendar"}""",
            )
        assertEquals("uid-1", refresh.uid)
        assertEquals("full calendar", refresh.scope)
    }

    @Test
    fun `the two factor challenge example round-trips the fido2 registered keys`() {
        val payload =
            """
            {"Enabled": 2, "TOTP": 0, "FIDO2": {"RegisteredKeys": [
              {"AttestationFormat": "packed", "CredentialID": "cred-1", "Name": "Security key"}]}}
            """.trimIndent()

        val parsed = roundTrip(TwoFactorInfo.serializer(), payload)

        assertTrue(parsed.fido2Only)
        assertTrue(!parsed.totpEnabled)
        val key = parsed.fido2!!.registeredKeys.single()
        assertEquals("packed", key.attestationFormat)
        assertEquals("cred-1", key.credentialId)
        assertEquals("Security key", key.name)
    }

    @Test
    fun `a human verification details block decodes from a 9001 body`() {
        val body =
            """
            {"Code": 9001, "Details": {"HumanVerificationToken": "hv-token", "WebUrl": "https://verify.proton.me/x",
             "HumanVerificationMethods": ["captcha", "sms"]}}
            """.trimIndent()
        val detailsJson = Json.parseToJsonElement(body).jsonObject.getValue("Details")
        val decoded = json.decodeFromJsonElement(HumanVerificationDetails.serializer(), detailsJson)
        val details = roundTrip(
            HumanVerificationDetails.serializer(),
            json.encodeToString(HumanVerificationDetails.serializer(), decoded),
        )

        assertEquals("hv-token", details.token)
        assertEquals("https://verify.proton.me/x", details.webUrl)
        assertEquals(listOf("captcha", "sms"), details.methods)

        // The documented ["captcha"] default belongs to the login flow (auth-flow.md §2.7); the DTO
        // reports an absent list as empty, so the flow must apply the default.
        val empty = json.decodeFromString(HumanVerificationDetails.serializer(), "{}")
        assertTrue(empty.methods.isEmpty())
        assertNull(empty.token)
    }

    @Test
    fun `a 9001 body decoded as AuthResponse loses the details block - FINDING F-6`() {
        // FINDING F-6 (LOD-12): HumanVerificationDetails is declared but unreachable through the
        // landed client surface — ProtonApiClient.authenticate returns the typed AuthResponse,
        // which has no `Details` field, and unknown keys are ignored. The HV flow
        // (auth-flow.md §2.7, security-audit §2.5.22) therefore has no typed path to the
        // verification token or WebUrl; a caller would have to parse HttpException's error body by
        // hand. Owner: code-agent (LOD-12 F-6).
        val body =
            """{"Code": 9001, "Details": {"HumanVerificationToken": "hv-token", "WebUrl": "https://verify.proton.me/x"}}"""

        val parsed = json.decodeFromString(AuthResponse.serializer(), body)

        assertEquals(9001, parsed.code)
        assertFalse(json.encodeToString(AuthResponse.serializer(), parsed).contains("Details"))
    }

    /** One event payload exercising every field of calendar-api.md §3's 23-field model. */
    private fun fullEventJson(withAttendeesInfo: Boolean = true): String {
        val attendeesInfo =
            if (withAttendeesInfo) {
                """"AttendeesInfo": {"Attendees": [{"ID": "att-2", "Token": "other@example.com", "Status": 1,
                    "Permissions": 0, "Comment": null}], "MoreAttendees": 1},"""
            } else {
                ""
            }
        return """
        {
          "ID": "evt-1", "CalendarID": "cal-1", "CalendarKeyPacket": "ckp-b64", "CreateTime": 1700000000,
          "LastEditTime": 1700000100, "StartTime": 1700000200, "EndTime": 1700003800, "FullDay": 0,
          "UID": "uid-1@proton.me", "IsOrganizer": 1, "IsProtonProtonInvite": 0,
          "AddressKeyPacket": "akp-b64", "AddressID": "a1", "SharedKeyPacket": "skp-b64",
          "SharedEvents": [{"Type": 2, "Data": "BEGIN:VCALENDAR", "Signature": "sig", "MemberID": "m1",
                            "Author": "user@proton.me"}],
          "CalendarEvents": [{"Type": 1, "Data": "BEGIN:VCALENDAR", "Signature": null, "MemberID": null,
                              "Author": null}],
          "PersonalEvents": [], "AttendeesEvents": [],
          "Attendees": [{"ID": "att-1", "Token": "guest@example.com", "Status": 3, "Permissions": 4,
                         "Comment": {"Message": "hi", "Type": 1}}],
          $attendeesInfo
          "Color": "#112233", "Notifications": [{"Type": 1, "Trigger": "-PT10M"}], "SharedEventID": "shared-1",
          "ServerAddedField": "ignored"
        }
        """.trimIndent()
    }
}
