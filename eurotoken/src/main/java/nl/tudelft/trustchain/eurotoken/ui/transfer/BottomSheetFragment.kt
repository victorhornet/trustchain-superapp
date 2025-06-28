package nl.tudelft.trustchain.eurotoken.ui.transfer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.FragmentTransportChoiceBinding
import nl.tudelft.trustchain.eurotoken.common.Mode
import nl.tudelft.trustchain.eurotoken.common.Channel
import nl.tudelft.trustchain.eurotoken.common.TransactionArgs
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.text.TextWatcher
import android.text.Editable
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import nl.tudelft.ipv8.attestation.trustchain.TrustChainCommunity
import org.json.JSONObject
import nl.tudelft.trustchain.common.contacts.ContactStore
import nl.tudelft.ipv8.android.IPv8Android
import nl.tudelft.ipv8.util.toHex

// now bottomsheet/transport choice
class TransportChoiceSheet : BottomSheetDialogFragment() {
    private var _binding: FragmentTransportChoiceBinding? = null
    private val binding get() = _binding!!

    private lateinit var originalTransactionArgs: TransactionArgs

    companion object {
        const val ARG_TRANSACTION_ARGS_RECEIVED = "transaction_args"
        fun newInstance(args: TransactionArgs) = TransportChoiceSheet().apply {
            arguments = bundleOf(ARG_TRANSACTION_ARGS_RECEIVED to args)
        }
    }
    private fun getTrustChainCommunity(): TrustChainCommunity {
        return IPv8Android.getInstance().getOverlay()
            ?: throw IllegalStateException("TrustChainCommunity is not configured")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // still getparceable.. since version incorrect
        originalTransactionArgs = requireArguments()
            .getParcelable(ARG_TRANSACTION_ARGS_RECEIVED)
            ?: throw IllegalStateException("TransactionArgs missing")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
        FragmentTransportChoiceBinding.inflate(inflater, container, false)
            .also { _binding = it }
            .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        when (originalTransactionArgs.mode) {
            Mode.SEND -> {
                binding.txtSheetTitle.text = getString(R.string.title_send_options)
                binding.btnReceiveQr.visibility = View.GONE
                binding.btnReceiveNfc.visibility = View.GONE
                binding.btnSendQr.visibility = View.VISIBLE
                binding.btnSendNfc.visibility = View.VISIBLE
            }
            Mode.RECEIVE -> {
                binding.txtSheetTitle.text = getString(R.string.title_receive_options)
                binding.btnSendQr.visibility = View.GONE
                binding.btnSendNfc.visibility = View.GONE
                binding.btnReceiveQr.visibility = View.VISIBLE
                binding.btnReceiveNfc.visibility = View.VISIBLE
            }
        }

        binding.btnSendQr.setOnClickListener { navigateWithSelectedChannel(Channel.QR) }
        binding.btnSendNfc.setOnClickListener { navigateWithSelectedChannel(Channel.NFC) }
        binding.btnReceiveQr.setOnClickListener { navigateWithSelectedChannel(Channel.QR) }
        binding.btnReceiveNfc.setOnClickListener { navigateWithSelectedChannel(Channel.NFC) }
    }
    private fun navigateWithSelectedChannel(selectedChannel: Channel) {
        if (originalTransactionArgs.mode == Mode.RECEIVE) {
            showAmountInputDialog { amount ->
                val argsForNextFragment = if (selectedChannel == Channel.QR) {
                    // qr so we need to prepare mroe data
                    val myPublicKey = getTrustChainCommunity().myPeer.publicKey.keyToHash().toHex()
                    val myName = ContactStore.getInstance(requireContext())
                        .getContactFromPublicKey(getTrustChainCommunity().myPeer.publicKey)?.name ?: ""

                    val qrJsonData = JSONObject().apply {
                        put("type", "request")
                        put("amount", amount)
                        put("public_key", myPublicKey)
                        put("name", myName)
                    }.toString()

                    originalTransactionArgs.copy(
                        channel = selectedChannel,
                        amount = amount,
                        publicKey = myPublicKey,
                        name = myName,
                        qrData = qrJsonData
                    )
                } else {
                    // nfc so we dont need to prepare more data
                    originalTransactionArgs.copy(
                        channel = selectedChannel,
                        amount = amount,
                        publicKey = getTrustChainCommunity().myPeer.publicKey.keyToHash().toHex(),
                        name = ContactStore.getInstance(requireContext())
                            .getContactFromPublicKey(getTrustChainCommunity().myPeer.publicKey)?.name ?: ""
                    )
                }
                navigateToFragment(argsForNextFragment)
            }
            return
        }

        val argsForNextFragment = originalTransactionArgs.copy(channel = selectedChannel)
        navigateToFragment(argsForNextFragment)
    }

    private fun navigateToFragment(args: TransactionArgs) {
        val destinationId = when (args.mode) {
            Mode.SEND -> R.id.sendMoneyFragment
            Mode.RECEIVE -> R.id.requestMoneyFragment
        }

        val navigationArgs = bundleOf("transaction_args" to args)

        findNavController().navigate(destinationId, navigationArgs)
        dismiss()
    }

    // for fragment with input
    private fun showAmountInputDialog(onAmountEntered: (Long) -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_amount_input, null)

        val editText = dialogView.findViewById<EditText>(R.id.edtDialogAmount)

        editText.addDecimalLimiter()

        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Enter Amount to Receive")
            .setView(dialogView)
            .setPositiveButton("Continue") { _, _ ->
                val amountText = editText.text.toString()
                val amount = getAmount(amountText)

                if (amount > 0) {
                    onAmountEntered(amount)
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Please enter a valid amount",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
        editText.requestFocus()

        // bit more user friendly and less buggy
        // initially had keyboard not showing up
        editText.postDelayed({
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }, 100)
    }

    fun getAmount(amount: String): Long {
        val regex = """[^\d]""".toRegex()
        if (amount.isEmpty()) {
            return 0L
        }
        return regex.replace(amount, "").toLong()
    }

    fun EditText.decimalLimiter(string: String): String {
        var amount = getAmount(string)
        if (amount == 0L) {
            return "0,00"
        }

        return (amount / 100).toString() + "," + (amount % 100).toString().padStart(2, '0')
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
                ) {
                }

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
