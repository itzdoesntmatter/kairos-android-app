package digital.tonima.protonsync.auth

/**
 * Test fixtures for the SRP suite.
 *
 * [TEST_MODULUS_BODY] is the go-srp published TEST modulus (srp_test.go `testModulus`) — the
 * expected A/M vector outputs in SrpClientTest/SrpClientGapTest were computed against it, so
 * the math assertions must keep using it. It is NOT Proton's production group; production
 * moduli are per-request and signature-verified (see ModulusVerifier).
 *
 * [capturedClearsignedModulus] is a real, complete `Modulus` field captured from a live
 * `POST auth/v4/info` response (2026-10-16) — clearsigned message + signature. It is the
 * fixture for tests that exercise ModulusVerifier.parseAndVerify with a genuine server payload.
 */
internal object TestFixtures {

    const val TEST_MODULUS_BODY =
        "W2z5HBi8RvsfYzZTS7qBaUxxPhsfHJFZpu3Kd6s1JafNrCCH9rfvPLrfuqocxWPgWDH2R8neK7PkNvjxto9" +
            "TStuY5z7jAzWRvFWN9cQhAKkdWgy0JY6ywVn22+HFpF4cYesHrqFIKUPDMSSIlWjBVmEJZ/MusD44ZT29xcPrOqeZvwt" +
            "CffKtGAIjLYPZIEbZKnDM1Dm3q2K/xS5h+xdhjnndhsrkwm9U9oyA2wxzSXFL+pdfj2fOdRwuR5nW0J2NFrq3kJjkRmpO" +
            "/Genq1UW+TEknIWAb6VzJJJA244K/H8cnSx2+nSNZO3bbo6Ys228ruV9A8m6DhxmS+bihN3ttQ=="

    fun capturedClearsignedModulus(): String =
        checkNotNull(
            TestFixtures::class.java.classLoader
                ?.getResourceAsStream("modulus-captured-20261016.txt"),
        ) {
            "missing test resource modulus-captured-20261016.txt"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    /**
     * The go-srp published test vector's clearsigned modulus: [TEST_MODULUS_BODY] wrapped in
     * the signature block Proton published with the vector (srp_test.go). The signature is
     * over the canonical clearsigned text of the TEST modulus and verifies against
     * [ModulusVerifier.MODULUS_VERIFICATION_KEY] — it is the fixture the math-vector tests
     * feed through the real verification path.
     */
    fun testClearsignedModulus(): String =
        "-----BEGIN PGP SIGNED MESSAGE-----\n" +
            "Hash: SHA256\n" +
            "\n" +
            "$TEST_MODULUS_BODY\n" +
            "-----BEGIN PGP SIGNATURE-----\n" +
            "Version: ProtonMail\n" +
            "Comment: https://protonmail.com\n" +
            "\n" +
            "wl4EARYIABAFAlwB1j0JEDUFhcTpUY8mAAD8CgEAnsFnF4cF0uSHKkXa1GIa\n" +
            "GO86yMV4zDZEZcDSJo0fgr8A/AlupGN9EdHlsrZLmTA1vhIx+rOgxdEff28N\n" +
            "kvNM7qIK\n" +
            "=q6vu\n" +
            "-----END PGP SIGNATURE-----"
}
