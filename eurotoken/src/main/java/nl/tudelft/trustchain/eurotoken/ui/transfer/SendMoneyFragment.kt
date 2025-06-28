package nl.tudelft.trustchain.eurotoken.ui.transfer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.findNavController
import nl.tudelft.ipv8.keyvault.PublicKey
import nl.tudelft.ipv8.keyvault.defaultCryptoProvider
import nl.tudelft.ipv8.util.hexToBytes
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.contacts.ContactStore
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository
import nl.tudelft.trustchain.common.util.viewBinding
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.common.TransactionArgs
import nl.tudelft.trustchain.eurotoken.databinding.FragmentSendMoneyBinding
import nl.tudelft.trustchain.eurotoken.nfc.NfcError
import nl.tudelft.trustchain.eurotoken.ui.EurotokenBaseFragment
import nl.tudelft.trustchain.eurotoken.ui.NfcReaderActivity
import nl.tudelft.trustchain.common.util.QRCodeUtils
import androidx.navigation.fragment.navArgs
// import androidx.compose.runtime.snapshots.current
import nl.tudelft.ipv8.Peer
import nl.tudelft.trustchain.common.eurotoken.benchmarks.UsageLogger
import nl.tudelft.trustchain.eurotoken.common.Channel
import org.json.JSONException
import nl.tudelft.trustchain.eurotoken.community.EuroTokenCommunity
import nl.tudelft.trustchain.eurotoken.common.ConnectionData
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.tudelft.trustchain.common.eurotoken.EurotokenPreferences

class SendMoneyFragment : EurotokenBaseFragment(R.layout.fragment_send_money) {
    private val binding by viewBinding(FragmentSendMoneyBinding::bind)
    private val navArgs: SendMoneyFragmentArgs by navArgs()
    private lateinit var currentTransactionArgs: TransactionArgs
    private val qrCodeUtils by lazy { QRCodeUtils(requireContext()) }

    private lateinit var nfcReaderLauncher: ActivityResultLauncher<Intent>
    private var isInPaymentMode = false

    private var addContact = false
    private val transactionMutex = Mutex()

    private val ownPublicKey by lazy {
        transactionRepository.trustChainCommunity.myPeer.publicKey
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentTransactionArgs = navArgs.transactionArgs

        nfcReaderLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            handleNfcResult(result)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (currentTransactionArgs.channel == Channel.NFC) {
            setupNfcPaymentMode()
        } else {
            setupQrMode()
        }

        binding.btnCancel.setOnClickListener {
            findNavController().popBackStack()
        }
    }

//     override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
//         super.onViewCreated(view, savedInstanceState)
//         val binding = FragmentSendMoneyBinding.bind(view)

//         val transactionArgs = navArgs.transactionArgs
//         currentTransactionArgs = transactionArgs

//         if (transactionArgs == null) {
//             Toast.makeText(requireContext(), "Error: Transaction details missing for send.", Toast.LENGTH_LONG).show()
//             findNavController().popBackStack()
//             return
//         }

//         binding.btnSend.visibility = View.GONE
//         binding.txtAmount.text = "Waiting for recipient..."

//         // val publicKeyArg = transactionArgs.publicKey
//         val amount = transactionArgs.amount
//         var publicKeyArg = transactionArgs.publicKey
//         var name = transactionArgs.name
//         val channel = transactionArgs.channel

//         var recipientKey: PublicKey? = publicKeyArg?.hexToBytes()?.let { defaultCryptoProvider.keyFromPublicBin(it) }
//         val contact = recipientKey?.let { ContactStore.getInstance(view.context).getContactFromPublicKey(it) }
//         binding.txtContactName.text = contact?.name ?: (name ?: "Unknown Recipient")

//         binding.newContactName.visibility = View.GONE

//         if (!name.isNullOrEmpty()) {
//             binding.newContactName.setText(name, android.widget.TextView.BufferType.EDITABLE)
//         }

//         if (contact == null) {
//             binding.addContactSwitch.toggle()
//             addContact = true
//             binding.newContactName.visibility = View.VISIBLE
//             binding.newContactName.setText(name, android.widget.TextView.BufferType.EDITABLE)
//         } else {
//             binding.addContactSwitch.visibility = View.GONE
//             binding.newContactName.visibility = View.GONE
//         }

//         binding.addContactSwitch.setOnClickListener {
//             addContact = !addContact
//             if (addContact) {
//                 binding.newContactName.visibility = View.VISIBLE
//             } else {
//                 binding.newContactName.visibility = View.GONE
//             }
//         }

//         val pref =
//             requireContext()
//                 .getSharedPreferences(
//                     EurotokenPreferences
//                         .EUROTOKEN_SHARED_PREF_NAME,
//                     Context.MODE_PRIVATE
//                 )
//         val demoModeEnabled =
//             pref.getBoolean(EurotokenPreferences.DEMO_MODE_ENABLED, false)

//         if (demoModeEnabled) {
//             binding.txtBalance.text =
//                 TransactionRepository.prettyAmount(transactionRepository.getMyBalance())
//         } else {
//             binding.txtBalance.text =
//                 TransactionRepository.prettyAmount(transactionRepository.getMyVerifiedBalance())
//         }
//         binding.txtOwnPublicKey.text = ownPublicKey.toString()
//         binding.txtAmount.text = TransactionRepository.prettyAmount(amount)
//         binding.txtContactPublicKey.text = publicKeyArg ?: ""

//         updateTrustScoreDisplay(publicKeyArg)

// //            val channel = currentTransactionArgs.channel
//         val pubKey = currentTransactionArgs.publicKey
//         Log.d(TAG, "Channel = $channel, publicKey = $publicKeyArg")

//         when (channel) {
//             Channel.QR -> {
//                 Log.d(TAG, "Entering QR branch")
//                 binding.btnSend.visibility = View.VISIBLE
//                 // binding.btnSend.visibility = View.GONE
//                 // no recipient ?-> show a scan button
//                 binding.btnSend.apply {
//                     visibility = View.VISIBLE
//                     text = if (pubKey.isNullOrEmpty()) {
//                         "Scan Recipient QR"
//                     } else {
//                         "Confirm Send (QR)"
//                     }
//                     setOnClickListener {
//                         if (pubKey.isNullOrEmpty()) {
//                             qrCodeUtils.startQRScanner(this@SendMoneyFragment)
//                         } else {
//                             finalizeTransaction(currentTransactionArgs)
//                         }
//                     }
//                 }
//             }
//             Channel.NFC -> {
//                 Log.d(TAG, "Entering NFC branch")

//                 val jsonData = JSONObject().apply {
//                     put("amount", currentTransactionArgs.amount)
//                     put("public_key", ownPublicKey.keyToBin().toHex())
//                     put("name", ContactStore.getInstance(requireContext()).getContactFromPublicKey(ownPublicKey)?.name ?: "")
//                     put("type", "nfc_send_initiated")
//                 }.toString()

//                 UsageLogger.logTransactionStart(jsonData)
// //
// //                val payloadBytes = jsonData.toByteArray(Charsets.UTF_8)
// //                val payloadLen = payloadBytes.size
// //
// //                // 4byts for length
// //                val headerByes = ByteBuffer.allocate(4).putInt(payloadLen).array()
// //                val hcePayload = headerByes + payloadBytes
// //                EuroTokenHCEService.setPayload(hcePayload)
// //                Log.d(TAG, "⟶ HCE payload set for sending: $jsonData, size: ${hcePayload.size}")

//                 binding.txtContactName.text = "Ready for NFC Scan"
//                 binding.txtContactPublicKey.text = "Tap phone to get recipient details"
//                 binding.txtAmount.text = "Amount: ${TransactionRepository.prettyAmount(currentTransactionArgs.amount)}"
//                 binding.trustScoreWarning.visibility = View.GONE

//                 binding.btnSend.apply {
//                     text = "Start NFC Read"
//                     visibility = View.VISIBLE
//                     setOnClickListener {
//                         Log.d(TAG, "NFC Button clicked. Launching NfcReaderActivity…")
//                         val intent = Intent(requireContext(), NfcReaderActivity::class.java)
//                         nfcReaderLauncher.launch(intent)
//                     }
//                 }
//             }
//             else -> {
//                 Log.w(TAG, "Unknown channel: $channel")
//             }
//         }
//     }

    private fun setupNfcPaymentMode() {
        // lets hide qr
        binding.contactLayout.visibility = View.GONE
        binding.addContactSwitch.visibility = View.GONE
        binding.newContactName.visibility = View.GONE
        binding.trustScoreWarning.visibility = View.GONE
        binding.txtTitle.visibility = View.GONE
        binding.txtTo.visibility = View.GONE
        binding.txtAmount.visibility = View.GONE

        binding.nfcPaymentLayout.visibility = View.VISIBLE
        binding.nfcPaymentStatus.text = "Connect your device to a peer device"
        binding.nfcPaymentIcon.visibility = View.VISIBLE

        updateBalanceDisplay()

        binding.btnSend.apply {
            text = "Ready to Send"
            setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.primary))
            isEnabled = true
            setOnClickListener {
                startNfcPayment()
            }
        }
    }

    private fun startNfcPayment() {
        isInPaymentMode = true

        binding.btnSend.apply {
            text = "Hold Near Peer Device..."
            isEnabled = false
            setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.metallic_gold))
        }

        binding.nfcPaymentStatus.text = "Hold device near recipient's device"

        // this honestly looks cool
        // makes it less static
        binding.nfcPaymentIcon.animate()
            .scaleX(1.2f)
            .scaleY(1.2f)
            .setDuration(500)
            .withEndAction {
                binding.nfcPaymentIcon.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(500)
                    .start()
            }

        val intent = Intent(requireContext(), NfcReaderActivity::class.java).apply {
            putExtra("PAYMENT_MODE", true)
            putExtra("SENDER_PUBLIC_KEY", ownPublicKey.keyToBin().toHex())
        }

        UsageLogger.logTransactionStart("nfc_payment")

        nfcReaderLauncher.launch(intent)
    }

    private fun handleNfcResult(result: androidx.activity.result.ActivityResult) {
        Log.d(TAG, "NFC Reader Activity finished with result code: ${result.resultCode}")

        if (result.resultCode == Activity.RESULT_OK) {
            UsageLogger.logTransactionDone()

            // briefly shown
            binding.nfcPaymentStatus.text = "Payment Complete!"
            binding.nfcPaymentStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.green))
            binding.nfcPaymentIcon.setImageResource(R.drawable.ic_check_circle)
            binding.nfcPaymentIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.green)

            // now in here...
            // since we have success lets notify the user
            lifecycleScope.launch {
                delay(1500)
                Toast.makeText(
                    requireContext(),
                    "Payment completed successfully!",
                    Toast.LENGTH_SHORT
                ).show()

                if (findNavController().currentDestination?.id == R.id.sendMoneyFragment) {
                    findNavController().navigate(R.id.action_sendMoneyFragment_to_transactionsFragment)
                }
            }
        } else {
            val nfcErrorStr = result.data?.getStringExtra("nl.tudelft.trustchain.eurotoken.NFC_ERROR")
            val errorType = try {
                nfcErrorStr?.let { NfcError.valueOf(it) } ?: NfcError.UNKNOWN_ERROR
            } catch (e: IllegalArgumentException) {
                NfcError.UNKNOWN_ERROR
            }

            Log.w(TAG, "NFC Payment failed: $errorType")

            // added for debugging
            // gave too many errors
            resetPaymentMode()

            val errorMsg = when (errorType) {
                NfcError.TAG_LOST -> "Connection lost. Please try again."
                NfcError.INSUFFICIENT_BALANCE -> "Insufficient balance for this payment."
                NfcError.PROPOSAL_REJECTED -> "Payment was rejected by the terminal."
                else -> "Payment failed. Please try again."
            }

            Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_LONG).show()
        }
    }

    private fun resetPaymentMode() {
        binding.btnSend.apply {
            text = "Ready to Send"
            isEnabled = true
            setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.primary))
        }

        binding.nfcPaymentStatus.text = "Connect your device to a peer device"
        binding.nfcPaymentStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
        binding.nfcPaymentIcon.setImageResource(R.drawable.ic_nfc)
        binding.nfcPaymentIcon.imageTintList = null
    }

    // override fun onResume() {
    //     super.onResume()
    //     if (currentTransactionArgs.channel == Channel.NFC && !currentTransactionArgs.publicKey.isNullOrEmpty()) {
    //         updateUIWithRecipientData()
    //     }
    // }

    // private fun updateUIWithRecipientData() {
    //     binding.txtAmount.text = TransactionRepository.prettyAmount(currentTransactionArgs.amount)
    //     binding.txtContactName.text = currentTransactionArgs.name ?: "Unknown Recipient"
    //     binding.txtContactPublicKey.text = currentTransactionArgs.publicKey ?: ""

    //     updateTrustScoreDisplay(currentTransactionArgs.publicKey)

    //     binding.btnSend.apply {
    //         text = "Confirm Payment"
    //         visibility = View.VISIBLE
    //         setOnClickListener {
    //             finalizeTransaction(currentTransactionArgs)
    //         }
    //     }
    // }

    // private fun updateTrustScoreDisplay(publicKeyHex: String?) {
    //     val trustScore = publicKeyHex
    //         ?.hexToBytes()
    //         ?.let { trustStore.getScore(it) }
    //     logger.info { "Trustscore: $trustScore" }

    //     if (trustScore != null) {
    //         if (trustScore >= TRUSTSCORE_AVERAGE_BOUNDARY) {
    //             binding.trustScoreWarning.text =
    //                 getString(R.string.send_money_trustscore_warning_high, trustScore)
    //             binding.trustScoreWarning.setBackgroundColor(
    //                 ContextCompat.getColor(requireContext(), R.color.android_green)
    //             )
    //         } else if (trustScore > TRUSTSCORE_LOW_BOUNDARY) {
    //             binding.trustScoreWarning.text =
    //                 getString(R.string.send_money_trustscore_warning_average, trustScore)
    //             binding.trustScoreWarning.setBackgroundColor(
    //                 ContextCompat.getColor(requireContext(), R.color.metallic_gold)
    //             )
    //         } else {
    //             binding.trustScoreWarning.text =
    //                 getString(R.string.send_money_trustscore_warning_low, trustScore)
    //             binding.trustScoreWarning.setBackgroundColor(
    //                 ContextCompat.getColor(requireContext(), R.color.red)
    //             )
    //         }
    //         binding.trustScoreWarning.visibility = View.VISIBLE
    //     } else {
    //         binding.trustScoreWarning.text =
    //             getString(R.string.send_money_trustscore_warning_no_score)
    //         binding.trustScoreWarning.setBackgroundColor(
    //             ContextCompat.getColor(requireContext(), R.color.metallic_gold)
    //         )
    //         binding.trustScoreWarning.visibility = View.VISIBLE
    //     }
    // }

    private fun setupQrMode() {
        // so if qr->hide NFC
        binding.nfcPaymentLayout.visibility = View.GONE

        binding.contactLayout.visibility = View.VISIBLE
        binding.txtTitle.visibility = View.VISIBLE
        binding.txtTo.visibility = View.VISIBLE
        binding.txtAmount.visibility = View.VISIBLE
        binding.buttonBar.visibility = View.VISIBLE

        updateBalanceDisplay()

        val pubKey = currentTransactionArgs.publicKey
        if (pubKey.isNullOrEmpty()) {
            // first scan
            binding.txtContactName.text = "Unknown Recipient"
            binding.txtContactPublicKey.text = "Scan QR code to get recipient details"
            binding.txtAmount.text = "€0.00"
            binding.trustScoreWarning.visibility = View.GONE
            binding.addContactSwitch.visibility = View.GONE
            binding.newContactName.visibility = View.GONE

            binding.btnSend.text = "Scan Recipient QR"
            binding.btnSend.setOnClickListener {
                UsageLogger.logTransactionStart("qr_payment")
                qrCodeUtils.startQRScanner(this)
            }
        } else {
            // now we know it so update
            updateUiForQr(currentTransactionArgs)
        }
    }

    private fun updateUiForQr(args: TransactionArgs) {
        val recipientKey: PublicKey? = args.publicKey?.hexToBytes()?.let {
            defaultCryptoProvider.keyFromPublicBin(it)
        }
        val contact = recipientKey?.let {
            ContactStore.getInstance(requireContext()).getContactFromPublicKey(it)
        }

        binding.txtContactName.text = contact?.name ?: (args.name ?: "Unknown Recipient")
        binding.txtAmount.text = TransactionRepository.prettyAmount(args.amount)
        binding.txtContactPublicKey.text = args.publicKey ?: ""

        // wasnt correctly integrated first...
        // check
        updateTrustScoreDisplay(args.publicKey)

        if (contact == null && !args.name.isNullOrEmpty()) {
            binding.addContactSwitch.visibility = View.VISIBLE
            binding.addContactSwitch.isChecked = true
            addContact = true
            binding.newContactName.visibility = View.VISIBLE
            binding.newContactName.setText(args.name)
        } else {
            binding.addContactSwitch.visibility = View.GONE
            binding.newContactName.visibility = View.GONE
            addContact = false
        }

        binding.addContactSwitch.setOnCheckedChangeListener { _, isChecked ->
            addContact = isChecked
            binding.newContactName.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.btnSend.text = "Confirm Send"
        binding.btnSend.setOnClickListener {
            finalizeTransaction(args)
        }
    }

    @Deprecated("Using onActivityResult for QR scan as qrCodeUtils might require it")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            qrCodeUtils.parseActivityResult(requestCode, resultCode, data)
                ?.let { rawQr ->
                    onQrScanned(rawQr)
                }
        }
    }

    private fun onQrScanned(qrContent: String) {
        try {
            val cd = ConnectionData(qrContent)

            if (cd.type != "request" && cd.type != "transfer_request") {
                Toast.makeText(requireContext(), "Invalid QR code type for payment.", Toast.LENGTH_LONG).show()
                return
            }

            currentTransactionArgs = currentTransactionArgs.copy(
                publicKey = cd.publicKey,
                name = cd.name,
                amount = cd.amount
            )
            updateUiForQr(currentTransactionArgs)
        } catch (e: JSONException) {
            Toast.makeText(requireContext(), "Scan failed (invalid QR)", Toast.LENGTH_LONG).show()
        }
    }

    // for qr
    private fun finalizeTransaction(args: TransactionArgs) {
        val amount = args.amount
        val recipientKeyBytes = args.publicKey?.hexToBytes()
        val recipientKey: PublicKey? = recipientKeyBytes?.let {
            defaultCryptoProvider.keyFromPublicBin(it)
        }

        val newName = binding.newContactName.text.toString()
        if (addContact && recipientKey != null && newName.isNotEmpty()) {
            ContactStore.getInstance(requireContext()).addContact(recipientKey, newName)
        }

        if (recipientKey != null) {
            lifecycleScope.launch {
                transactionMutex.withLock {
                    val success = transactionRepository.sendTransferProposal(
                        recipientKey.keyToBin(),
                        amount
                    )
                    if (!success) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                requireContext(),
                                "Insufficient balance or send error",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        return@withLock
                    }

                    UsageLogger.logTransactionDone()

                    // if online, lets send some trust adresses :)
                    try {
                        val peer = Peer(recipientKey)
                        Log.d(TAG, "Waiting for peer: ${peer.mid}")
                        val peerFound = waitForPeer(peer, 5000L)
                        Log.d(TAG, "waitForPeer result: $peerFound")
                        if (peerFound) {
                            Log.d(TAG, "Peer discovered. Sending trust addresses.")
                            getEuroTokenCommunity().sendAddressesOfLastTransactions(peer)
                            Log.d(TAG, "Finished sending trust addresses.")
                        } else {
                            Log.w(TAG, "Could not find peer within timeout. Trust info will not be sent.")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send trust addresses", e)
                    }

                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            requireContext(),
                            "Payment sent successfully!",
                            Toast.LENGTH_SHORT
                        ).show()
                        findNavController().navigate(R.id.action_sendMoneyFragment_to_transactionsFragment)
                    }
                }
            }
        } else {
            Toast.makeText(requireContext(), "Recipient public key is missing.", Toast.LENGTH_LONG).show()
        }
    }
    private fun getErrorMessage(error: NfcError): String {
        return when (error) {
            NfcError.NFC_UNSUPPORTED -> "NFC is not supported on this device"
            NfcError.NFC_DISABLED -> "Please enable NFC in settings"
            NfcError.TAG_LOST -> "Connection lost. Please try again"
            NfcError.AID_SELECT_FAILED -> "Incompatible peer device"
            NfcError.READ_FAILED -> "Failed to read payment information"
            NfcError.INSUFFICIENT_BALANCE -> "Insufficient balance"
            NfcError.PROPOSAL_REJECTED -> "Payment was rejected"
            NfcError.AGREEMENT_FAILED -> "Failed to confirm payment"
            NfcError.IO_ERROR -> "Communication error. Please try again"
            NfcError.UNKNOWN_ERROR -> "An unknown error occurred"
            NfcError.HCE_DATA_NOT_READY -> "The peer device is not ready."
            NfcError.OTHER -> "An unexpected error occurred."
        }
    }

    // hopefully resolves potential race condition
    private suspend fun waitForPeer(peerToFind: Peer, timeoutMillis: Long): Boolean {
        val community = getEuroTokenCommunity()
        return withTimeoutOrNull(timeoutMillis) {
            var peerFound = false
            while (!peerFound) {
                peerFound = community.getPeers().any {
                    it.key.keyToBin().contentEquals(peerToFind.key.keyToBin())
                }
                if (!peerFound) {
                    delay(300)
                }
            }
            peerFound
        } ?: false
    }

    private fun updateBalanceDisplay() {
        val pref = requireContext().getSharedPreferences(
            EurotokenPreferences.EUROTOKEN_SHARED_PREF_NAME,
            Context.MODE_PRIVATE
        )
        val demoModeEnabled = pref.getBoolean(
            EurotokenPreferences.DEMO_MODE_ENABLED,
            false
        )
        val balance = if (demoModeEnabled) {
            transactionRepository.getMyBalance()
        } else {
            transactionRepository.getMyVerifiedBalance()
        }
        binding.txtBalance.text = TransactionRepository.prettyAmount(balance)
        binding.txtOwnPublicKey.text = ownPublicKey.keyToHash().toHex()
    }

    private fun updateTrustScoreDisplay(publicKeyHex: String?) {
        val trustScore = publicKeyHex?.hexToBytes()?.let { trustStore.getScore(it) }
        logger.info { "Trustscore: $trustScore" }

        if (trustScore != null) {
            val (messageId, colorId) = when {
                trustScore >= TRUSTSCORE_AVERAGE_BOUNDARY -> {
                    R.string.send_money_trustscore_warning_high to R.color.green
                }
                trustScore > TRUSTSCORE_LOW_BOUNDARY -> {
                    R.string.send_money_trustscore_warning_average to R.color.metallic_gold
                }
                else -> {
                    R.string.send_money_trustscore_warning_low to R.color.red
                }
            }
            binding.trustScoreWarning.text = getString(messageId, trustScore)
            binding.trustScoreWarning.setBackgroundColor(
                ContextCompat.getColor(requireContext(), colorId)
            )
            binding.trustScoreWarning.visibility = View.VISIBLE
        } else {
            binding.trustScoreWarning.text = getString(R.string.send_money_trustscore_warning_no_score)
            binding.trustScoreWarning.setBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.metallic_gold)
            )
            binding.trustScoreWarning.visibility = View.VISIBLE
        }
    }

    private fun getEuroTokenCommunity(): EuroTokenCommunity {
        return getIpv8().getOverlay<EuroTokenCommunity>()
            ?: throw java.lang.IllegalStateException("EuroTokenCommunity is not configured")
    }

    companion object {
        private val TAG = SendMoneyFragment::class.java.simpleName

        // const val ARG_AMOUNT = "amount"
        // const val ARG_PUBLIC_KEY = "pubkey"
        // const val ARG_NAME = "name"
        const val TRUSTSCORE_AVERAGE_BOUNDARY = 70
        const val TRUSTSCORE_LOW_BOUNDARY = 30
    }
}

// val trustScore = trustStore.getScore(publicKey.toByteArray())
// logger.info { "Trustscore: $trustScore" }

// if (trustScore != null) {
//     if (trustScore >= TRUSTSCORE_AVERAGE_BOUNDARY) {
//         binding.trustScoreWarning.text =
//             getString(R.string.send_money_trustscore_warning_high, trustScore)
//         binding.trustScoreWarning.setBackgroundColor(
//             ContextCompat.getColor(
//                 requireContext(),
//                 R.color.android_green
//             )
//         )
//     } else if (trustScore > TRUSTSCORE_LOW_BOUNDARY) {
//         binding.trustScoreWarning.text =
//             getString(R.string.send_money_trustscore_warning_average, trustScore)
//         binding.trustScoreWarning.setBackgroundColor(
//             ContextCompat.getColor(
//                 requireContext(),
//                 R.color.metallic_gold
//             )
//         )
//     } else {
//         binding.trustScoreWarning.text =
//             getString(R.string.send_money_trustscore_warning_low, trustScore)
//         binding.trustScoreWarning.setBackgroundColor(
//             ContextCompat.getColor(
//                 requireContext(),
//                 R.color.red
//             )
//         )
//     }
// } else {
//     binding.trustScoreWarning.text =
//         getString(R.string.send_money_trustscore_warning_no_score)
//     binding.trustScoreWarning.setBackgroundColor(
//         ContextCompat.getColor(
//             requireContext(),
//             R.color.metallic_gold
//         )
//     )
//     binding.trustScoreWarning.visibility = View.VISIBLE
// }
