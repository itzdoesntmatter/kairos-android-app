package digital.tonima.protonsync.auth

import org.bouncycastle.bcpg.ArmoredInputStream
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider
import java.io.ByteArrayInputStream
import java.security.Security

/**
 * Verifies the clearsigned SRP modulus Proton delivers in `auth/v4/info` and extracts its
 * base64 body (auth-flow.md §2.2).
 *
 * Proton rotates the modulus PER REQUEST (verified live 2026-10-16: four consecutive
 * `auth/v4/info` calls returned four distinct clearsigned moduli), so byte-pinning a single
 * value can never work — every request would fail a pin check. The correct trust anchor is
 * what the clearsign envelope exists for: the signature is verified against Proton's
 * dedicated modulus key (`proton@srp.modulus`, key ID 0x350585C4E9518F26), embedded here
 * byte-identical to ProtonMail/go-srp's published `modulusPubkey` constant. This is
 * strictly stronger than the reference app, which never verified the signature at all
 * (security-audit CR-2), and it keeps working across modulus rotation.
 *
 * After verification the modulus gets the same structural group checks Proton's own client
 * performs (go-srp `checkParams`): 2048-bit, odd, 3 mod 8 — a substituted-but-signed
 * degenerate group fails here even before any SRP math.
 */
internal object ModulusVerifier {

    /**
     * Proton's SRP modulus verification key, armored — byte-identical to the
     * `modulusPubkey` constant in ProtonMail/go-srp (`srp.go`). Key ID 0x350585C4E9518F26,
     * user `proton@srp.modulus`, EdDSA.
     */
    const val MODULUS_VERIFICATION_KEY =
        "-----BEGIN PGP PUBLIC KEY BLOCK-----\n\n" +
            "xjMEXAHLgxYJKwYBBAHaRw8BAQdAFurWXXwjTemqjD7CXjXVyKf0of7n9Ctm\n" +
            "L8v9enkzggHNEnByb3RvbkBzcnAubW9kdWx1c8J3BBAWCgApBQJcAcuDBgsJ\n" +
            "BwgDAgkQNQWFxOlRjyYEFQgKAgMWAgECGQECGwMCHgEAAPGRAP9sauJsW12U\n" +
            "MnTQUZpsbJb53d0Wv55mZIIiJL2XulpWPQD/V6NglBd96lZKBmInSXX/kXat\n" +
            "Sv+y0io+LR8i2+jV+AbOOARcAcuDEgorBgEEAZdVAQUBAQdAeJHUz1c9+KfE\n" +
            "kSIgcBRE3WuXC4oj5a2/U3oASExGDW4DAQgHwmEEGBYIABMFAlwBy4MJEDUF\n" +
            "hcTpUY8mAhsMAAD/XQD8DxNI6E78meodQI+wLsrKLeHn32iLvUqJbVDhfWSU\n" +
            "WO4BAMcm1u02t4VKw++ttECPt+HUgPUq5pqQWe5Q2cW4TMsE\n" +
            "=Y4Mw\n" +
            "-----END PGP PUBLIC KEY BLOCK-----"

    /** The expected key ID of [MODULUS_VERIFICATION_KEY] — asserted at first use. */
    private const val EXPECTED_KEY_ID: Long = 0x350585C4E9518F26L

    private const val GROUP_BYTES = 256

    private const val BEGIN_TEXT = "-----BEGIN PGP SIGNED MESSAGE-----"
    private const val BEGIN_SIG = "-----BEGIN PGP SIGNATURE-----"
    private const val END_SIG = "-----END PGP SIGNATURE-----"

    private val verificationKey: PGPPublicKey by lazy {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        val rings =
            PGPPublicKeyRingCollection(
                ArmoredInputStream(ByteArrayInputStream(MODULUS_VERIFICATION_KEY.toByteArray(Charsets.UTF_8))),
                JcaKeyFingerprintCalculator(),
            )
        rings.keyRings.asSequence()
            .flatMap { it.publicKeys.asSequence() }
            .firstOrNull { it.keyID == EXPECTED_KEY_ID }
            ?: throw SrpException("embedded SRP modulus verification key is missing or has the wrong key ID")
    }

    /** Extracts, verifies and structurally validates the modulus body. */
    fun parseAndVerify(raw: String): ByteArray {
        val textStart = raw.indexOf(BEGIN_TEXT)
        val sigStart = raw.indexOf(BEGIN_SIG)
        val sigEnd = raw.indexOf(END_SIG)
        if (textStart < 0 || sigStart < 0 || sigEnd < 0 || sigStart <= textStart) {
            throw SrpException("modulus is not a clearsigned message")
        }
        if (raw.substring(sigEnd + END_SIG.length).trim().isNotEmpty()) {
            throw SrpException("data found after the signed modulus message")
        }

        val textSection = raw.substring(textStart + BEGIN_TEXT.length, sigStart)
        val signatureArmor = raw.substring(sigStart, sigEnd + END_SIG.length)

        // RFC 4880 §7 canonical form, matching go clearsign.Decode exactly: the "Hash:"
        // header line (and the blank separator) are MIME-style headers, NOT part of the
        // signed text. Signed text = the remaining lines, per-line trailing whitespace
        // stripped, dash-escaping removed, CRLF BETWEEN lines only — the final CRLF is not
        // included in the hash.
        val textLines =
            textSection
                .trimEnd('\n', '\r')
                .split("\r\n", "\n")
                .dropWhile { it.isBlank() || it.startsWith("Hash:") }
                .map { line ->
                    var processed = line.trimEnd(' ', '\t')
                    if (processed.startsWith("- ")) processed = processed.substring(2)
                    processed
                }
        if (textLines.isEmpty()) {
            throw SrpException("signed modulus carries no body")
        }
        val canonicalText = textLines.joinToString("\r\n").toByteArray(Charsets.UTF_8)

        val signature =
            JcaPGPObjectFactory(
                ArmoredInputStream(ByteArrayInputStream(signatureArmor.toByteArray(Charsets.UTF_8))),
            ).let { factory ->
                val obj = factory.nextObject()
                if (obj !is PGPSignatureList) {
                    throw SrpException("signed modulus carries no signature list")
                }
                obj.get(0)
            }
        if (signature.keyID != EXPECTED_KEY_ID) {
            throw SrpException("signed modulus is signed by an unexpected key")
        }

        signature.init(JcaPGPContentVerifierBuilderProvider(), verificationKey)
        signature.update(canonicalText)
        if (!signature.verify()) {
            throw SrpException("signed modulus signature verification failed")
        }

        val body =
            canonicalText
                .toString(Charsets.UTF_8)
                .lineSequence()
                .firstOrNull { it.isNotBlank() && !it.startsWith("Hash:") }
                ?.trim()
                ?: throw SrpException("signed modulus carries no body")

        val modulus = SrpPrimitives.decodeBase64(body)
        if (modulus.size != GROUP_BYTES) {
            throw SrpException("modulus must be $GROUP_BYTES bytes, got ${modulus.size}")
        }
        val n = SrpPrimitives.fromLittleEndian(modulus)
        if (n.bitLength() != 2048 || !n.testBit(0) || n.mod(java.math.BigInteger.valueOf(8)).toInt() != 3) {
            throw SrpException("modulus fails the structural group checks (2048-bit, odd, 3 mod 8)")
        }
        return modulus
    }
}
