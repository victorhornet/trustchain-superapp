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
import nl.tudelft.trustchain.eurotoken.EuroTokenMainActivity
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
import nl.tudelft.trustchain.eurotoken.benchmarks.UsageLogger
import nl.tudelft.trustchain.eurotoken.common.Channel
import org.json.JSONException
import nl.tudelft.trustchain.eurotoken.community.EuroTokenCommunity
import nl.tudelft.trustchain.eurotoken.common.ConnectionData
import nl.tudelft.trustchain.eurotoken.nfc.EuroTokenHCEService
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import nl.tudelft.trustchain.eurotoken.ui.transfer.RequestMoneyFragment.Companion.TAG

class SendMoneyFragment : EurotokenBaseFragment(R.layout.fragment_send_money) {
    private var addContact = false

//    private lateinit var qrCodeUtils: QRCodeUtils
    private val qrCodeUtils by lazy { QRCodeUtils(requireContext()) }

    private val navArgs: SendMoneyFragmentArgs by navArgs()
    private lateinit var currentTransactionArgs: TransactionArgs
    private val binding by viewBinding(FragmentSendMoneyBinding::bind)

    private lateinit var nfcReaderLauncher: ActivityResultLauncher<Intent>
    // private var transactionToConfirm: TransactionArgs? = null
    // private lateinit var publicKey: String
    // private var transactionAmount: Long = 0L
    // private lateinit var recipientKey: PublicKey
    // check if correct TODO

    private val ownPublicKey by lazy {
        defaultCryptoProvider.keyFromPublicBin(
            transactionRepository
                .trustChainCommunity
                .myPeer
                .publicKey
                .keyToBin()
                .toHex()
                .hexToBytes()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val originalTransactionArgs = navArgs.transactionArgs
        // val originalTransactionArgs = navArgs.transactionArgs
        // how is nfcreaderactivity's result handled??->

        nfcReaderLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
                ->
                Log.d(
                    TAG,
                    "NFC Reader Activity finished with result code: ${result.resultCode}"
                )
                UsageLogger.logTransactionCheckpointStart("Send Money")
                if (result.resultCode == Activity.RESULT_OK) {
                    // TODO: temporary solution for benchmarks because transactions break,
                    //  move this to appropriate place (finalize transaction).
                    // UsageLogger.logTransactionCheckpointEnd("Send Money")

                    // UsageLogger.logTransactionDone()
                    val receivedData =
                        result.data?.getStringExtra(
                            "nl.tudelft.trustchain.eurotoken.NFC_DATA"
                        )
                            ?: return@registerForActivityResult



                    try {
                        val connData = ConnectionData(receivedData)
                        val receivedPublicKeyHex = connData.publicKey
//                        var recipientKey = null
                        var recipientKey: PublicKey? = null
                        recipientKey = receivedPublicKeyHex?.hexToBytes()?.let { defaultCryptoProvider.keyFromPublicBin(it) } //

                        if (recipientKey != null) {
                            Log.d(TAG, "Recipient key after defaultCryptoProvider.keyFromPublicBin:")
                            Log.d(TAG, "  Type: ${recipientKey.javaClass.simpleName}")
                            Log.d(TAG, "  .keyToBin().toHex(): ${recipientKey.keyToBin().toHex()}")
                            Log.d(TAG, "  .toString(): ${recipientKey.toString()}")
                        } else {
                            Log.e(TAG, "Failed to parse recipient public key (recipientKey is null).")
                            return@registerForActivityResult
                        }
                         currentTransactionArgs = currentTransactionArgs.copy(
                             publicKey = connData.publicKey, // check hex-> error
                             name = connData.name,
                             amount = connData.amount, // now use amount from nfc
                             channel = Channel.NFC
                         )

                        updateUIWithRecipientData()

                        // val bundle = Bundle().apply {
                        //     putString("amount", connData.amount.toString())
                        //     putString("name", connData.name)
                        //     putString("pubkey", connData.publicKey)
                        // }
                        // findNavController().navigate(R.id.action_sendMoneyFragment_to_nfcResultFragment, bundle)
                    } catch (e: JSONException) {
                        Log.e(TAG, "Error parsing JSON from NFC: $receivedData", e)
                        Toast.makeText(requireContext(), "NFC Error: Invalid data format received.", Toast.LENGTH_LONG).show()
                        // binding.btnSend.text = "Retry NFC Read"
                        // Toast.makeText(requireContext(), "Scan failed (invalid QR)", Toast.LENGTH_LONG).show()
                        binding.btnSend.apply {
                            text = "Retry NFC Read"
                            setOnClickListener {
                                Log.d(TAG, "Retrying NFC read...")
                                // UsageLogger.logTransactionStart(jsonData)
                                val intent = Intent(requireContext(), NfcReaderActivity::class.java)
                                nfcReaderLauncher.launch(intent)
                            }
                        }
                    }
                } else {
                    val nfcErrorStr = result.data?.getStringExtra("nl.tudelft.trustchain.eurotoken.NFC_ERROR")
                    val errorType = try {
                        nfcErrorStr?.let { NfcError.valueOf(it) } ?: NfcError.UNKNOWN_ERROR
                    } catch (e: IllegalArgumentException) {
                        NfcError.UNKNOWN_ERROR
                    }

                    Log.w(TAG, "NFC Failed or Cancelled: $errorType")
                    val errorMsg = getString(R.string.nfc_confirmation_failed, errorType.name)
                    Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_LONG).show()

                    binding.btnSend.apply {
                        text = "Retry NFC Read"
                        setOnClickListener {
                            Log.d(TAG, "Retrying NFC read...")
                            val jsonData = JSONObject().apply {
                                put("amount", currentTransactionArgs.amount)
                                put("public_key", ownPublicKey.toString())
                                put("name", ContactStore.getInstance(requireContext()).getContactFromPublicKey(ownPublicKey)?.name ?: "")
                                put("type", "transfer")
                            }.toString()
                            UsageLogger.logTransactionStart(jsonData)
                            val intent = Intent(requireContext(), NfcReaderActivity::class.java)
                            nfcReaderLauncher.launch(intent)
                        }
                    }
                }
            }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentSendMoneyBinding.bind(view)

        val transactionArgs = navArgs.transactionArgs
        currentTransactionArgs = transactionArgs

        if (transactionArgs == null) {
            Toast.makeText(requireContext(), "Error: Transaction details missing for send.", Toast.LENGTH_LONG).show()
            findNavController().popBackStack()
            return
        }

        binding.btnSend.visibility = View.GONE
        binding.txtAmount.text = "Waiting for recipient..."

        // val publicKeyArg = transactionArgs.publicKey
        val amount = transactionArgs.amount
        var publicKeyArg = transactionArgs.publicKey
        var name = transactionArgs.name
        val channel = transactionArgs.channel

        var recipientKey: PublicKey? = publicKeyArg?.hexToBytes()?.let { defaultCryptoProvider.keyFromPublicBin(it) }
        val contact = recipientKey?.let { ContactStore.getInstance(view.context).getContactFromPublicKey(it) }
        binding.txtContactName.text = contact?.name ?: (name ?: "Unknown Recipient")

        binding.newContactName.visibility = View.GONE

        if (!name.isNullOrEmpty()) {
            binding.newContactName.setText(name, android.widget.TextView.BufferType.EDITABLE)
        }

        if (contact == null) {
            binding.addContactSwitch.toggle()
            addContact = true
            binding.newContactName.visibility = View.VISIBLE
            binding.newContactName.setText(name, android.widget.TextView.BufferType.EDITABLE)
        } else {
            binding.addContactSwitch.visibility = View.GONE
            binding.newContactName.visibility = View.GONE
        }

        binding.addContactSwitch.setOnClickListener {
            addContact = !addContact
            if (addContact) {
                binding.newContactName.visibility = View.VISIBLE
            } else {
                binding.newContactName.visibility = View.GONE
            }
        }

        val pref =
            requireContext()
                .getSharedPreferences(
                    EuroTokenMainActivity.EurotokenPreferences
                        .EUROTOKEN_SHARED_PREF_NAME,
                    Context.MODE_PRIVATE
                )
        val demoModeEnabled =
            pref.getBoolean(EuroTokenMainActivity.EurotokenPreferences.DEMO_MODE_ENABLED, false)

        if (demoModeEnabled) {
            binding.txtBalance.text =
                TransactionRepository.prettyAmount(transactionRepository.getMyBalance())
        } else {
            binding.txtBalance.text =
                TransactionRepository.prettyAmount(transactionRepository.getMyVerifiedBalance())
        }
        binding.txtOwnPublicKey.text = ownPublicKey.toString()
        binding.txtAmount.text = TransactionRepository.prettyAmount(amount)
        binding.txtContactPublicKey.text = publicKeyArg ?: ""

        updateTrustScoreDisplay(publicKeyArg)

//            val channel = currentTransactionArgs.channel
        val pubKey = currentTransactionArgs.publicKey
        Log.d(TAG, "Channel = $channel, publicKey = $publicKeyArg")

        when (channel) {
            Channel.QR -> {
                Log.d(TAG, "Entering QR branch")
                binding.btnSend.visibility = View.VISIBLE
                // binding.btnSend.visibility = View.GONE
                // no recipient ?-> show a scan button
                binding.btnSend.apply {
                    visibility = View.VISIBLE
                    text = if (pubKey.isNullOrEmpty()) {
                        "Scan Recipient QR"
                    } else {
                        "Confirm Send (QR)"
                    }
                    setOnClickListener {
                        if (pubKey.isNullOrEmpty()) {
                            qrCodeUtils.startQRScanner(this@SendMoneyFragment)
                        } else {
                            finalizeTransaction(currentTransactionArgs)
                        }
                    }
                }
            }
            Channel.NFC -> {
                Log.d(TAG, "Entering NFC branch")


                val jsonData = JSONObject().apply {
                    put("amount", currentTransactionArgs.amount)
                    put("public_key", ownPublicKey.toString())
                    put("name", ContactStore.getInstance(requireContext()).getContactFromPublicKey(ownPublicKey)?.name ?: "")
                    put("type", "transfer")
                }.toString()

                val payloadBytes = jsonData.toByteArray(Charsets.UTF_8)
                val payloadLen = payloadBytes.size

                // 4byts for length
                val headerByes = ByteBuffer.allocate(4).putInt(payloadLen).array()
                val hcePayload = headerByes + payloadBytes
                EuroTokenHCEService.setPayload(hcePayload)
                Log.d(TAG, "⟶ HCE payload set for sending: $jsonData, size: ${hcePayload.size}")

                binding.txtContactName.text = "Ready for NFC Scan"
                binding.txtContactPublicKey.text = "Tap phone to get recipient details"
                binding.txtAmount.text = "Amount: ${TransactionRepository.prettyAmount(currentTransactionArgs.amount)}"
                binding.trustScoreWarning.visibility = View.GONE

                binding.btnSend.apply {
                    text = "Start NFC Read"
                    visibility = View.VISIBLE
                    setOnClickListener {
                        Log.d(TAG, "NFC Button clicked. Launching NfcReaderActivity…")
                        UsageLogger.logTransactionStart(jsonData)
                        val intent = Intent(requireContext(), NfcReaderActivity::class.java)
                        nfcReaderLauncher.launch(intent)
                    }
                }
            }
            else -> {
                Log.w(TAG, "Unknown channel: $channel")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (currentTransactionArgs.channel == Channel.NFC && !currentTransactionArgs.publicKey.isNullOrEmpty()) {
            updateUIWithRecipientData()
        }
    }

    private fun updateUIWithRecipientData() {
        binding.txtAmount.text = TransactionRepository.prettyAmount(currentTransactionArgs.amount)
        binding.txtContactName.text = currentTransactionArgs.name ?: "Unknown Recipient"
        binding.txtContactPublicKey.text = currentTransactionArgs.publicKey ?: ""

        updateTrustScoreDisplay(currentTransactionArgs.publicKey)

        binding.btnSend.apply {
            text = "Confirm Payment"
            visibility = View.VISIBLE
            setOnClickListener {
                finalizeTransaction(currentTransactionArgs)
            }
        }
    }

    private fun updateTrustScoreDisplay(publicKeyHex: String?) {
        val trustScore = publicKeyHex
            ?.hexToBytes()
            ?.let { trustStore.getScore(it) }
        logger.info { "Trustscore: $trustScore" }

        if (trustScore != null) {
            if (trustScore >= TRUSTSCORE_AVERAGE_BOUNDARY) {
                binding.trustScoreWarning.text =
                    getString(R.string.send_money_trustscore_warning_high, trustScore)
                binding.trustScoreWarning.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.android_green)
                )
            } else if (trustScore > TRUSTSCORE_LOW_BOUNDARY) {
                binding.trustScoreWarning.text =
                    getString(R.string.send_money_trustscore_warning_average, trustScore)
                binding.trustScoreWarning.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.metallic_gold)
                )
            } else {
                binding.trustScoreWarning.text =
                    getString(R.string.send_money_trustscore_warning_low, trustScore)
                binding.trustScoreWarning.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.red)
                )
            }
            binding.trustScoreWarning.visibility = View.VISIBLE
        } else {
            binding.trustScoreWarning.text =
                getString(R.string.send_money_trustscore_warning_no_score)
            binding.trustScoreWarning.setBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.metallic_gold)
            )
            binding.trustScoreWarning.visibility = View.VISIBLE
        }
    }

    // QR code still used old way
    @Deprecated("Using onActivityResult for QR scan…")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        qrCodeUtils.parseActivityResult(requestCode, resultCode, data)
            ?.let { rawQr ->
                onQrScanned(rawQr)
            }
    }


    private fun onQrScanned(qrContent: String) {
        try {
            val cd = ConnectionData(qrContent)
            val updatedArgs = currentTransactionArgs.copy(
                publicKey = cd.publicKey,
                name = cd.name,
                amount = cd.amount
            )
            binding.txtAmount.text = TransactionRepository.prettyAmount(currentTransactionArgs.amount)
            binding.txtContactName.text = currentTransactionArgs.name
            binding.txtContactPublicKey.text = currentTransactionArgs.publicKey

            updateTrustScoreDisplay(currentTransactionArgs.publicKey)

            binding.btnSend.text = "Confirm Send (QR)"
            binding.btnSend.isEnabled = true
            binding.btnSend.visibility = View.VISIBLE
            binding.btnSend.setOnClickListener {
                finalizeTransaction(currentTransactionArgs)
            }
        } catch (e: JSONException) {
            Toast.makeText(
                requireContext(),
                "Scan failed (invalid QR)",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun finalizeTransaction(args: TransactionArgs) {
        val amount = args.amount
        val publicKey = args.publicKey
        val recipientKeyBytes = publicKey?.hexToBytes()
        val recipientKey: PublicKey? = recipientKeyBytes?.let {
            defaultCryptoProvider.keyFromPublicBin(
                it
            )
        }

        val newName = binding.newContactName.text.toString()
        if (addContact && recipientKey != null && newName.isNotEmpty()) {
            ContactStore.getInstance(requireContext()).addContact(recipientKey, newName)
        }

        if (recipientKey != null) {
            Log.d(TAG, "Successfully created PublicKey object. Type: ${recipientKey.javaClass.simpleName}")
            Log.d(TAG, "Passing recipient key (binary hex) to repository: ${recipientKey.keyToBin().toHex()}")

            val success = transactionRepository.sendTransferProposal(recipientKey.keyToBin(), amount)
            if (!success) {
                Toast.makeText(
                    requireContext(),
                    "Insufficient balance",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            Log.d(TAG, "sendTransferProposal returned true.")

            UsageLogger.logTransactionCheckpointEnd("Send Money")
            UsageLogger.logTransactionDone()
            lifecycleScope.launch {
                try {
//                val peer = findPeer(recipientKey.keyToBin().toHex())
//                if (peer != null) {
                    Log.d(TAG, "Coroutine to send trust addresses: STARTED.")
                    val peer = Peer(recipientKey)
                    val peerFound = waitForPeer(peer, 5000L)
                    //racecondition
                    if (peerFound) {
                        Log.d(TAG, "Peer discovered. Sending trust addresses.")
                        getEuroTokenCommunity().sendAddressesOfLastTransactions(peer)
                        Log.d(TAG, "Finished sending trust addresses.")
                    } else {
                        Log.w(TAG, "Could not find peer within the timeout. Trust information will not be sent.")
                        Toast.makeText(requireContext(), "Could not sync trust info with peer.", Toast.LENGTH_SHORT).show()
                    }
                    launch(Dispatchers.Main) {
                        // nfc? send address for trust upd
                        Log.d(TAG, "Coroutine to send trust addresses: Navigating away.")
                        if (args.channel == Channel.NFC) {
                            // val nfcDataString = "Transaction successful: Sent ${
                            //     TransactionRepository.prettyAmount(amount)
                            // } to ${binding.txtContactName.text}."
                            // val bundle = Bundle().apply { putString("nfcData", nfcDataString) }
                            // findNavController().navigate(
                            //     R.id.action_sendMoneyFragment_to_nfcResultFragment,
                            //     bundle
                            // )
                            Toast.makeText(
                                requireContext(),
                                "Payment sent successfully to ${binding.txtContactName.text}!",
                                Toast.LENGTH_SHORT
                            ).show()

                            // Navigate to transactions for both NFC and QR
                            findNavController().navigate(R.id.action_sendMoneyFragment_to_transactionsFragment)
                        }
                        // QR
                        else {
                            findNavController().navigate(R.id.action_sendMoneyFragment_to_transactionsFragment)
                        }
                    }
                }
                catch (e:Exception){
                    Log.e(TAG, ">>>> FAILED to send trust addresses. See exception below. <<<<", e) // need to find why trustdb doesnt see anything
                }
            }
        } else {
            Toast.makeText(requireContext(), "Recipient public key is missing to send.", Toast.LENGTH_LONG).show()
        }
    }

    private fun findPeer(pubKeyHex: String): Peer? {
        val itr = transactionRepository.trustChainCommunity.getPeers().listIterator()
        while (itr.hasNext()) {
            val cur: Peer = itr.next()
            if (cur.key.keyToBin().toHex() == pubKeyHex) {
                return cur
            }
        }
        return null
    }

    private fun getEuroTokenCommunity(): EuroTokenCommunity {
        return getIpv8().getOverlay<EuroTokenCommunity>()
            ?: throw java.lang.IllegalStateException("EuroTokenCommunity is not configured")
    }

    //hopefully resolves potential race condition
    private suspend fun waitForPeer(peerToFind: Peer, timeoutMillis: Long): Boolean {
        val community = getEuroTokenCommunity()
        return withTimeoutOrNull(timeoutMillis) {
            var peerFound = false
            while (!peerFound) {
                peerFound = community.getPeers().any { it.key.keyToBin().contentEquals(peerToFind.key.keyToBin()) }
                if (!peerFound) {
                    delay(300)
                }
            }
            peerFound
        } ?: false
    }

    companion object {
        private val TAG = SendMoneyFragment::class.java.simpleName

        const val ARG_AMOUNT = "amount"
        const val ARG_PUBLIC_KEY = "pubkey"
        const val ARG_NAME = "name"
        const val TRUSTSCORE_AVERAGE_BOUNDARY = 70
        const val TRUSTSCORE_LOW_BOUNDARY = 30
    }
}
