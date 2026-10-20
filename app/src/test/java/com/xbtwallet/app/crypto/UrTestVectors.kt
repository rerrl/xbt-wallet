package com.xbtwallet.app.crypto

/**
 * BC-UR `crypto-account` fixtures, taken from other people's implementations.
 *
 * These live in their own file because two suites need them: the decoder's
 * ([UrAccountImportTest]) and the screen state machine's
 * ([com.xbtwallet.app.ui.signers.AddSigningDeviceScreenStateTest]). Both are
 * testing *our* reading of a format someone else produced, so the fixtures are
 * quoted verbatim rather than round-tripped through our own encoder — otherwise
 * an encoder and a decoder that agree with each other would look like a spec.
 *
 * **Device origin, so nobody has to guess what these are:**
 *
 *  - [bcrStatic] — the published **BCR-2020-015** test vector (BlockchainCommons
 *    Research; authors Craig Raw — Sparrow's author — and Wolf McNally). A static
 *    single-frame `crypto-account` for account #0 of the BIP-39 seed
 *    `shield group erode awake lock sausage cash glare wave crew flame glove`,
 *    carrying one output descriptor per script type: `pkh`, `sh(wpkh)`, `wpkh`,
 *    two multisig cosigners and `tr`.
 *  - [bcrAnimated] — the same payload as an **animated** export. The frames were
 *    produced from [bcrStatic]'s CBOR with hummingbird's own `UREncoder` at 100
 *    bytes per part, i.e. by the same BC-UR fountain encoding a signer performs
 *    (SeedSigner's encoder is a port of the same reference implementation). The
 *    decoded content is therefore independently known to be the BCR payload.
 *  - [seedSigner] — **SeedSigner's** own published frames, asserted in its test
 *    suite (`tests/test_encodepsbtqr.py::test_ur_xpub_qr`): a mainnet multisig
 *    export at `m/48h/1h/0h/2h` from the seed `obscure bone gas open exotic abuse
 *    virus bunker shuffle nasty ship dash`, four parts of four. A real device's
 *    real output — and a `wsh(cosigner…)` account, so there is no native segwit
 *    descriptor in it to import.
 */
object UrTestVectors {

    /** The BCR-2020-015 static `crypto-account` vector, verbatim. */
    const val bcrStatic =
        "ur:crypto-account/oeadcyemrewytyaolttaadeetaadmutaaddloxaxhdclaxwmfmdeiamecsdsemgtvsjzcncygrkowtrontzschgezokstswkkscfmklrtauteyaahdcxiehfonurdppfyntapejpproypegrdawkgmaewejlsfdtsrfybdehcaflmtrlbdhpamtaaddyoeadlncsdwykaeykaeykaocyemrewytyaycynlytsnyltaadeetaadmhtaadmwtaaddloxaxhdclaostvelfemdyynwydwyaievosrgmambklovabdgypdglldvespsthysadamhpmjeinaahdcxntdllnaaeykoytdacygegwhgjsiyonpywmcmrpwphsvodsrerozsbyaxluzcoxdpamtaaddyoeadlncsehykaeykaeykaocyemrewytyaycypdbskeuytaadeetaadmwtaaddloxaxhdclaxzcfxeegdrpmogrgwkbzctlttweadkiengrwlhtprremouoluutqdpfbncedkynfhaahdcxjpwevdeogthttkmeswzcolcpsaahcfnshkhtehytclmnteatmoteadtlwynnftloamtaaddyoeadlncsghykaeykaeykaocyemrewytyaycybthlvytstaadeetaadmhtaadnytaaddloxaxhdclaxhhsnhdrpftdwuocntilydibehnecmovdfekpjkclcslasbhkpawsaddmcmmnahnyaahdcxlotedtndfymyltclhlmtpfsadscnhtztaolbnnkistaedegwfmmedreetnwmcycnamtaaddyoeadlfcsdpykaocyemrewytyaycyemrewytytaadeetaadmhtaadmetaadnytaaddloxaxhdclaxdwkswmztpytnswtsecnblfbayajkdldeclqzzolrsnhljedsgminetytbnahatbyaahdcxkkguwsvyimjkvwteytwztyswvendtpmncpasfrrylprnhtkblndrgrmkoyjtbkrpamtaaddyoeadlocsdyykaeykaeykadykaocyemrewytyaycyhkrpnddrtaadeetaadmetaadnytaaddloxaxhdclaohnhffmvsbndslrfgclpfjejyatbdpebacnzokotofxntaoemvskpaowmryfnotfgaahdcxdlnbvecentssfsssgylnhkrstoytecrdlyadrekirfaybglahltalsrfcaeerobwamtaaddyoeadlocsdyykaeykaeykaoykaocyemrewytyaycyhkrpnddrtaadeetaadnltaaddloxaxhdclaorkrhkeytwsoykorletwstbwycagtbsotmeptjkesgwrfcmveskvdmngujzttgtdpaahdcxgrfgmuvyylmwcxjtttechplslgoegagaptdniatidmhdmebdwfryfsnsdkcplyvaamtaaddyoeadlncshfykaeykaeykaocyemrewytyaycytostatbngmdavolk"

    /** The same payload as an animated export: eight fountain-coded frames. */
    val bcrAnimated = listOf(
        "ur:crypto-account/1-8/lpadaycfaxahcygmdavolkhdhsoeadcyemrewytyaolttaadeetaadmutaaddloxaxhdclaxwmfmdeiamecsdsemgtvsjzcncygrkowtrontzschgezokstswkkscfmklrtauteyaahdcxiehfonurdppfyntapejpproypegrdawkgmaewejlsfdtsrfybdehcaflmtrlbdhpamtaaddyoeadlnvscwjtly",
        "ur:crypto-account/2-8/lpaoaycfaxahcygmdavolkhdhscsdwykaeykaeykaocyemrewytyaycynlytsnyltaadeetaadmhtaadmwtaaddloxaxhdclaostvelfemdyynwydwyaievosrgmambklovabdgypdglldvespsthysadamhpmjeinaahdcxntdllnaaeykoytdacygegwhgjsiyonpywmcmrpwphsvodsrerozsmohlylby",
        "ur:crypto-account/3-8/lpaxaycfaxahcygmdavolkhdhsbyaxluzcoxdpamtaaddyoeadlncsehykaeykaeykaocyemrewytyaycypdbskeuytaadeetaadmwtaaddloxaxhdclaxzcfxeegdrpmogrgwkbzctlttweadkiengrwlhtprremouoluutqdpfbncedkynfhaahdcxjpwevdeogthttkmeswzcolcpsaahcfnsnbtnckpe",
        "ur:crypto-account/4-8/lpaaaycfaxahcygmdavolkhdhshkhtehytclmnteatmoteadtlwynnftloamtaaddyoeadlncsghykaeykaeykaocyemrewytyaycybthlvytstaadeetaadmhtaadnytaaddloxaxhdclaxhhsnhdrpftdwuocntilydibehnecmovdfekpjkclcslasbhkpawsaddmcmmnahnyaahdcxlotedtcmkehdyk",
        "ur:crypto-account/5-8/lpahaycfaxahcygmdavolkhdhsndfymyltclhlmtpfsadscnhtztaolbnnkistaedegwfmmedreetnwmcycnamtaaddyoeadlfcsdpykaocyemrewytyaycyemrewytytaadeetaadmhtaadmetaadnytaaddloxaxhdclaxdwkswmztpytnswtsecnblfbayajkdldeclqzzolrsnhljedsgminflnttbbw",
        "ur:crypto-account/6-8/lpamaycfaxahcygmdavolkhdhsetytbnahatbyaahdcxkkguwsvyimjkvwteytwztyswvendtpmncpasfrrylprnhtkblndrgrmkoyjtbkrpamtaaddyoeadlocsdyykaeykaeykadykaocyemrewytyaycyhkrpnddrtaadeetaadmetaadnytaaddloxaxhdclaohnhffmvsbndslrfgclpfjeehioreby",
        "ur:crypto-account/7-8/lpataycfaxahcygmdavolkhdhsjyatbdpebacnzokotofxntaoemvskpaowmryfnotfgaahdcxdlnbvecentssfsssgylnhkrstoytecrdlyadrekirfaybglahltalsrfcaeerobwamtaaddyoeadlocsdyykaeykaeykaoykaocyemrewytyaycyhkrpnddrtaadeetaadnltaaddloxaxhdcldlsoclox",
        "ur:crypto-account/8-8/lpayaycfaxahcygmdavolkhdhsaorkrhkeytwsoykorletwstbwycagtbsotmeptjkesgwrfcmveskvdmngujzttgtdpaahdcxgrfgmuvyylmwcxjtttechplslgoegagaptdniatidmhdmebdwfryfsnsdkcplyvaamtaaddyoeadlncshfykaeykaeykaocyemrewytyaycytostatbnaeaeaedksbdedk",
    )

    /** SeedSigner's published frames: a mainnet multisig account, four parts of four. */
    val seedSigner = listOf(
        "UR:CRYPTO-ACCOUNT/1-4/LPADAACSKPCYMOMNLGRYHDCKOEADCYSSMECPONAOLYTAADMETAADDLOXAXHDCLAOKSRLNLKPUEGYATHPMNSNIYMUECBY",
        "UR:CRYPTO-ACCOUNT/2-4/LPAOAACSKPCYMOMNLGRYHDCKKKGHZMLUZORPVDGUOTECSTTKTOLPCWPTNTLKZTTIZTBEAAHDCXVDTPMYRSTDMOPSCXFZ",
        "UR:CRYPTO-ACCOUNT/3-4/LPAXAACSKPCYMOMNLGRYHDCKSPZSBZSPGERLGDATUYNLPYBTGYIYYKBTWTAOSWKSVTSGCHBYDKYAVDAMTAADMONDGDFD",
        "UR:CRYPTO-ACCOUNT/4-4/LPAAAACSKPCYMOMNLGRYHDCKDYOTADLOCSDYYKADYKAEYKAOYKAOCYSSMECPONAXAAAYCYIOREKKJKAEAEAEWZWDMYON",
    )

    /**
     * The native segwit account inside [bcrStatic], re-encoded as a zpub.
     *
     * zpub because a `wpkh` account *is* BIP-84, and this app's account model is
     * native segwit.
     */
    const val bcrZpub =
        "zpub6qQzfPRBjbGVHz7SiPbrSnt77iRsrFRXMdWng2jWSZUbCiWTsYGLoBQ4sFRnUxDF1N3KXdV2RETtyNnhQqtJvJVSZVHEXeupW5gighTMoZg"

    /**
     * The `xpub` BCR-2020-015 publishes for that same descriptor:
     * `wpkh([37b5eed4/84'/0'/0']xpub6BkU…CQ23)`.
     *
     * Held separately from [bcrZpub] so a test can prove the *key material* is
     * right, not merely that our two encodings agree with each other.
     */
    const val bcrSpecXpub =
        "xpub6BkU445MSEBXbPjD3g2c2ch6mn8yy1SXXQUM7EwjgYiq6Wt1NDwDZ45npqWcV8uQC5oi2gHuVukoCoZZyT4HKq8EpotPMqGqxdZRuapCQ23"
}
