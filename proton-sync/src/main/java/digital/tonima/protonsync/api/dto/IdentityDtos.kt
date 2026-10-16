package digital.tonima.protonsync.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Identity/key-unlock wire models, consumed by the post-login key unlock sequence
 * (auth-flow.md §2.6): GET /users → GET /core/v4/keys/salts → GET /addresses.
 */

@Serializable
data class UserResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("User") val user: ProtonUser? = null,
)

@Serializable
data class ProtonUser(
    @SerialName("ID") val id: String = "",
    @SerialName("Name") val name: String? = null,
    @SerialName("Keys") val keys: List<UserKey> = emptyList(),
)

/**
 * A user (account-level) private key. [active] == 1 marks the keys the account actually uses;
 * the unlock order prefers the primary active key (auth-flow.md §2.6).
 */
@Serializable
data class UserKey(
    @SerialName("ID") val id: String = "",
    @SerialName("PrivateKey") val privateKey: String = "",
    @SerialName("Active") val active: Int = 0,
    @SerialName("Primary") val primary: Int = 0,
)

/** GET /core/v4/keys/salts — per-key salts, matched by key ID (auth-flow.md §2.6). */
@Serializable
data class KeySaltsResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("KeySalts") val keySalts: List<KeySaltInfo> = emptyList(),
)

@Serializable
data class KeySaltInfo(
    @SerialName("ID") val id: String = "",
    @SerialName("KeySalt") val keySalt: String? = null,
)

@Serializable
data class AddressesResponse(
    @SerialName("Code") val code: Int = 0,
    @SerialName("Addresses") val addresses: List<AddressInfo> = emptyList(),
)

@Serializable
data class AddressInfo(
    @SerialName("ID") val id: String = "",
    @SerialName("Email") val email: String = "",
    @SerialName("Keys") val keys: List<AddressKeyInfo> = emptyList(),
)

/**
 * Address keys are user-key shaped with one addition: a [token] whose decrypted value is the
 * key passphrase, replacing the password-derived keyPassword (auth-flow.md §2.6 step 6).
 */
@Serializable
data class AddressKeyInfo(
    @SerialName("ID") val id: String = "",
    @SerialName("PrivateKey") val privateKey: String = "",
    @SerialName("Token") val token: String? = null,
    @SerialName("Signature") val signature: String? = null,
    @SerialName("Active") val active: Int = 0,
    @SerialName("Primary") val primary: Int = 0,
)
