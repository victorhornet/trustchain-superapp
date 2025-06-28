package nl.tudelft.trustchain.eurotoken.ui.transfer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.contacts.ContactStore
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository
import nl.tudelft.trustchain.common.util.QRCodeUtils
import nl.tudelft.trustchain.common.util.viewBinding
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.FragmentTransferEuroBinding
import nl.tudelft.trustchain.eurotoken.ui.EurotokenBaseFragment
import org.json.JSONException
import org.json.JSONObject
import nl.tudelft.trustchain.eurotoken.common.Mode
import androidx.core.os.bundleOf
import nl.tudelft.trustchain.eurotoken.common.TransactionArgs
import nl.tudelft.trustchain.eurotoken.common.Channel
import nl.tudelft.trustchain.common.eurotoken.EurotokenPreferences

class TransferFragment : EurotokenBaseFragment(R.layout.fragment_transfer_euro) {
    private val binding by viewBinding(FragmentTransferEuroBinding::bind)
    private val qrCodeUtils by lazy { QRCodeUtils(requireContext()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launchWhenResumed {
            while (isActive) {
                updateBalanceDisplay()
                delay(1000L)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupNameField()
        setupPublicKeyDisplay()
        updateBalanceDisplay()
        setupPaymentButtons()
    }

    // refactord so its more understandeable
    // bottomsheet
    // defualt is qr--> bottomsheet overrides
    // amount will be set via dialogfrag also bottomsheet overrides
    private fun setupPaymentButtons() {
        binding.btnSend.setOnClickListener {
            val transactionArgs = TransactionArgs(
                mode = Mode.SEND,
                channel = Channel.QR,
                amount = 0L,
                extraPayloadBytes = getExtraPayloadBytes()
            )

            // this is exactly important
            val transportSheet = TransportChoiceSheet.newInstance(transactionArgs)
            transportSheet.show(childFragmentManager, "TransportChoiceSend")
        }

        binding.btnReceive.setOnClickListener {
            val transactionArgs = TransactionArgs(
                mode = Mode.RECEIVE,
                channel = Channel.QR,
                amount = 0L,
                extraPayloadBytes = getExtraPayloadBytes()
            )

            val transportSheet = TransportChoiceSheet.newInstance(transactionArgs)
            transportSheet.show(childFragmentManager, "TransportChoiceReceive")
        }
    }

    private fun setupPublicKeyDisplay() {
        val ownKey = transactionRepository.trustChainCommunity.myPeer.publicKey
        binding.txtOwnPublicKey.text = ownKey.keyToHash().toHex()

        // copy button added, easy to copy the public key for ui
        binding.btnCopyPublicKey.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("EuroToken ID", ownKey.keyToHash().toHex())
            clipboard.setPrimaryClip(clip)

            Toast.makeText(requireContext(), "ID copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateBalanceDisplay() {
        val ownKey = transactionRepository.trustChainCommunity.myPeer.publicKey
        val ownContact = ContactStore.getInstance(requireContext()).getContactFromPublicKey(ownKey)

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

        if (ownContact?.name != null) {
            binding.missingNameCard.visibility = View.GONE
            binding.txtOwnName.text = "Your balance (${ownContact.name})"
        } else {
            binding.missingNameCard.visibility = View.VISIBLE
            binding.txtOwnName.text = "Your balance"
        }
    }

    private fun setupNameField() {
        val ownKey = transactionRepository.trustChainCommunity.myPeer.publicKey
        val ownContact = ContactStore.getInstance(requireContext()).getContactFromPublicKey(ownKey)

        if (ownContact?.name != null) {
            binding.missingNameCard.visibility = View.GONE
        }

        fun addName() {
            val newName = binding.edtMissingName.text.toString().trim()
            if (newName.isNotEmpty()) {
                ContactStore.getInstance(requireContext()).addContact(ownKey, newName)
                binding.missingNameCard.visibility = View.GONE
                updateBalanceDisplay()
                requireContext().hideKeyboard(binding.root)

                Toast.makeText(requireContext(), "Welcome, $newName! 🎉", Toast.LENGTH_SHORT).show()
            } else {
                // alternative was lottie but still had something similar in old code
                // adds flair :)
                binding.edtMissingName.animate()
                    .translationX(-10f).setDuration(50)
                    .withEndAction {
                        binding.edtMissingName.animate()
                            .translationX(10f).setDuration(50)
                            .withEndAction {
                                binding.edtMissingName.animate()
                                    .translationX(0f).setDuration(50)
                            }
                    }
                Toast.makeText(requireContext(), "Please enter your name", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnAdd.setOnClickListener { addName() }
        binding.edtMissingName.onSubmit { addName() }
    }

    private fun getExtraPayloadBytes(): Int {
        val text = binding.edtExtraPayloadBytes.text.toString()
        return if (text.isNotEmpty()) {
            try {
                text.toInt().coerceAtLeast(0)
            } catch (e: NumberFormatException) {
                0
            }
        } else {
            0
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        qrCodeUtils.parseActivityResult(requestCode, resultCode, data)?.let {
            try {
                val connectionData = ConnectionData(it)

                if (connectionData.type == "transfer" || connectionData.type == "request") {
                    val transactionArgs = TransactionArgs(
                        mode = Mode.SEND,
                        channel = Channel.QR,
                        amount = connectionData.amount,
                        publicKey = connectionData.publicKey,
                        name = connectionData.name,
                        qrData = null
                    )

                    findNavController().navigate(
                        R.id.action_transferFragment_to_sendMoneyFragment,
                        bundleOf("transaction_args" to transactionArgs)
                    )
                } else {
                    Toast.makeText(requireContext(), "Invalid QR", Toast.LENGTH_LONG).show()
                }
            } catch (e: JSONException) {
                Toast.makeText(requireContext(), "Scan failed, try again", Toast.LENGTH_LONG).show()
            }
        } ?: Toast.makeText(requireContext(), "Scan failed", Toast.LENGTH_LONG).show()
    }

    companion object {
        fun EditText.onSubmit(func: () -> Unit) {
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    func()
                }
                true
            }
        }

        class ConnectionData(json: String) : JSONObject(json) {
            var publicKey = this.optString("public_key")
            var amount = this.optLong("amount", -1L)
            var name = this.optString("name")
            var type = this.optString("type")
        }

        fun getAmount(amount: String): Long {
            val regex = """\D""".toRegex()
            if (amount.isEmpty()) {
                return 0L
            }
            return regex.replace(amount, "").toLong()
        }

        fun Context.hideKeyboard(view: View) {
            val inputMethodManager =
                getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
        }

        fun EditText.decimalLimiter(string: String): String {
            var amount = getAmount(string)
            if (amount == 0L) {
                return ""
            }
            return (amount / 100).toString() + "." + (amount % 100).toString().padStart(2, '0')
        }

        fun EditText.addDecimalLimiter() {
            this.addTextChangedListener(
                object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {
                        val str = this@addDecimalLimiter.text!!.toString()
                        if (str.isEmpty()) return
                        val str2 = decimalLimiter(str)

                        if (str2 != str) {
                            this@addDecimalLimiter.setText(str2)
                            val pos = this@addDecimalLimiter.text!!.length
                            this@addDecimalLimiter.setSelection(pos)
                        }
                    }

                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int
                    ) {}
                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int
                    ) {}
                }
            )
        }
    }
}
