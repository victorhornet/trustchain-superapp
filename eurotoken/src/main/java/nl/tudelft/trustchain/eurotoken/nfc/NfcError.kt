package nl.tudelft.trustchain.eurotoken.nfc

enum class NfcError {
    NFC_UNSUPPORTED,
    NFC_DISABLED,
    TAG_LOST,
    IO_ERROR,
    AID_SELECT_FAILED,
    READ_FAILED,
    HCE_DATA_NOT_READY,
    UNKNOWN_ERROR,
    INSUFFICIENT_BALANCE,
    PROPOSAL_REJECTED,
    AGREEMENT_FAILED,
    OTHER
}
