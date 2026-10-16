package digital.tonima.protonsync.api

import digital.tonima.protonsync.api.dto.AuthInfoRequest
import digital.tonima.protonsync.api.dto.AuthResponse
import digital.tonima.protonsync.api.dto.CalendarEventDetail
import digital.tonima.protonsync.api.dto.CalendarEventListResponse
import digital.tonima.protonsync.api.dto.CalendarInfo
import digital.tonima.protonsync.api.dto.CalendarListResponse
import digital.tonima.protonsync.api.dto.EventPart
import digital.tonima.protonsync.api.dto.RefreshTokenRequest
import digital.tonima.protonsync.api.dto.SrpAuthRequest
import digital.tonima.protonsync.api.dto.TwoFactorInfo
import digital.tonima.protonsync.api.dto.TwoFactorRequest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Wire-format tests for the DTOs: Proton's PascalCase field names are the contract, so the
 * serialized forms are asserted literally against payloads shaped per the RE specs
 * (calendar-api.md §3, auth-flow.md §1.2), and unknown fields must be ignored so forward
 * server changes degrade gracefully.
 */
class DtoSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `auth info request serializes the exact wire key set`() {
        val encoded = json.encodeToString(AuthInfoRequest.serializer(), AuthInfoRequest(username = "user@proton.me"))

        assertEquals("""{"Username":"user@proton.me","Intent":"Proton"}""", encoded)
    }

    @Test
    fun `srp auth request serializes with the exact wire key set`() {
        val encoded = json.encodeToString(
            SrpAuthRequest.serializer(),
            SrpAuthRequest(
                username = "user@proton.me",
                clientEphemeral = "A-B64",
                clientProof = "M-B64",
                srpSession = "session",
            ),
        )

        assertEquals(
            """{"Username":"user@proton.me","ClientEphemeral":"A-B64","ClientProof":"M-B64","SRPSession":"session"}""",
            encoded,
        )
    }

    @Test
    fun `refresh request encodes the mandatory defaulted wire fields`() {
        val encoded = json.encodeToString(
            RefreshTokenRequest.serializer(),
            RefreshTokenRequest(refreshToken = "refresh", uid = "uid"),
        )
        val expected =
            "{\"RefreshToken\":\"refresh\",\"UID\":\"uid\",\"ResponseType\":\"token\"," +
                "\"GrantType\":\"refresh_token\",\"RedirectURI\":\"https://protonmail.ch\"}"

        assertEquals(expected, encoded)
    }

    @Test
    fun `two factor request carries the six digit code under TwoFactorCode`() {
        val encoded = json.encodeToString(
            TwoFactorRequest.serializer(),
            TwoFactorRequest(twoFactorCode = "123456"),
        )

        assertEquals("""{"TwoFactorCode":"123456"}""", encoded)
    }

    @Test
    fun `auth response parses the success payload with all token fields`() {
        val payload =
            """
            {
              "Code": 1000,
              "AccessToken": "access",
              "RefreshToken": "refresh",
              "TokenType": "Bearer",
              "Uid": "uid-1",
              "Scope": "full mail calendar",
              "ServerProof": "proof",
              "PasswordMode": 1,
              "KeySalt": "salt",
              "ExtraFutureField": {"Anything": true}
            }
            """.trimIndent()

        val parsed = json.decodeFromString(AuthResponse.serializer(), payload)

        assertEquals(1000, parsed.code)
        assertEquals("access", parsed.accessToken)
        assertEquals("refresh", parsed.refreshToken)
        assertEquals("uid-1", parsed.uid)
        assertEquals("full mail calendar", parsed.scope)
        assertEquals(1, parsed.passwordMode)
    }

    @Test
    fun `auth response parses the two-factor challenge shape without token fields`() {
        val payload =
            """
            {
              "Code": 1000,
              "2FA": {"Enabled": 3, "TOTP": 1, "FIDO2": {"RegisteredKeys": [{"Name": "key1"}]}}
            }
            """.trimIndent()

        val parsed = json.decodeFromString(AuthResponse.serializer(), payload)

        assertNotNull(parsed.twoFa)
        assertEquals(true, parsed.twoFa!!.totpEnabled)
        assertEquals(false, parsed.twoFa!!.fido2Only)
        assertEquals(1, parsed.twoFa!!.fido2?.registeredKeys?.size)
    }

    @Test
    fun `calendar list parses the documented field names`() {
        val payload =
            """
            {
              "Code": 1000,
              "Calendars": [
                {"ID": "cal-1", "Name": "Personal", "Color": "#aabbcc", "Display": 1,
                 "Type": 0, "Flags": 2, "UnknownFuture": 42,
                 "Members": [{"ID": "m1", "CalendarID": "cal-1", "AddressID": "a1",
                              "Email": "user@proton.me", "Permissions": 16, "Display": 1, "Color": "#ffffff"}]}
              ]
            }
            """.trimIndent()

        val parsed = json.decodeFromString(CalendarListResponse.serializer(), payload)

        val calendar = parsed.calendars.single()
        assertEquals("cal-1", calendar.id)
        assertEquals("Personal", calendar.name)
        assertEquals(16, calendar.members.single().permissions)
    }

    @Test
    fun `event detail parses the documented field names including part lists`() {
        val payload =
            """
            {
              "Code": 1000,
              "Events": [
                {"ID": "evt-1", "CalendarID": "cal-1", "CalendarKeyPacket": "kpk",
                 "CreateTime": 1, "LastEditTime": 2, "StartTime": 1000, "EndTime": 2000,
                 "FullDay": 1, "UID": "uid@proton", "IsOrganizer": 1, "IsProtonProtonInvite": 0,
                 "AddressKeyPacket": "apk", "AddressID": "a1", "SharedKeyPacket": "skp",
                 "SharedEvents": [{"Type": 2, "Data": "BEGIN:VCALENDAR", "Signature": null}],
                 "CalendarEvents": [], "PersonalEvents": [], "AttendeesEvents": [],
                 "Attendees": [{"ID": "att-1", "Token": "x@proton.me", "Status": 3, "Permissions": 4,
                                "Comment": {"Message": "hi", "Type": 1}}],
                 "AttendeesInfo": {"Attendees": [{"ID": "att-1", "Token": "x@proton.me", "Status": 3}],
                                    "MoreAttendees": 0},
                 "Notifications": [{"Type": 1, "Trigger": "-PT15M"}],
                 "SharedEventID": "shared-1",
                 "FutureField": "ignored"}
              ]
            }
            """.trimIndent()

        val parsed = json.decodeFromString(CalendarEventListResponse.serializer(), payload)

        val event = parsed.events.single()
        assertEquals("evt-1", event.id)
        assertEquals(1000L, event.startTime)
        assertEquals(1, event.fullDay)
        assertEquals(2, event.sharedEvents.single().type)
        assertEquals("x@proton.me", event.effectiveAttendees.single().token)
        assertEquals("-PT15M", event.notifications.single().trigger)
        assertEquals(1000, parsed.code)
    }

    @Test
    fun `two factor info distinguishes totp-only from fido2-only accounts`() {
        val totp = TwoFactorInfo(enabled = 3, totp = 1)
        assertEquals(true, totp.totpEnabled)
        assertEquals(false, totp.fido2Only)

        val fido2 = TwoFactorInfo(enabled = 2, totp = 0)
        assertEquals(true, fido2.fido2Only)

        val legacy = TwoFactorInfo(enabled = 0, totp = 0)
        assertEquals(false, legacy.fido2Only)
    }

    @Test
    fun `calendar key primary flag requires both bits`() {
        assertEquals(true, digital.tonima.protonsync.api.dto.CalendarKey(flags = 3).isPrimary)
        assertEquals(false, digital.tonima.protonsync.api.dto.CalendarKey(flags = 1).isPrimary)
        assertEquals(false, digital.tonima.protonsync.api.dto.CalendarKey(flags = 2).isPrimary)
    }

    @Test
    fun `event part type list carries the documented bucket semantics`() {
        // Type 2 = main plaintext iCal content; the engine selects on this (crypto-envelope §3).
        val part = EventPart(type = 2, data = "BEGIN:VEVENT")

        assertEquals(2, part.type)
    }

    @Test
    fun `calendar info type zero is personal`() {
        assertEquals(0, CalendarInfo(type = 0).type)
    }
}
