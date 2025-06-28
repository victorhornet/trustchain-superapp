package nl.tudelft.trustchain.eurotoken.ui.transfer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch
import nl.tudelft.trustchain.common.util.QRCodeUtils
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.FragmentRequestMoneyBinding
import nl.tudelft.trustchain.eurotoken.ui.EurotokenBaseFragment
import nl.tudelft.trustchain.eurotoken.common.Channel
import androidx.navigation.fragment.navArgs
import nl.tudelft.trustchain.eurotoken.nfc.EuroTokenHCEService
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository
import nl.tudelft.trustchain.common.contacts.ContactStore
import androidx.core.content.ContextCompat

class RequestMoneyFragment : EurotokenBaseFragment(R.layout.fragment_request_money) {
    private var _binding: FragmentRequestMoneyBinding? = null
    private val binding get() = _binding!!

    private val navArgs: RequestMoneyFragmentArgs by navArgs()
    private val qrCodeUtils by lazy { QRCodeUtils(requireContext()) }

    // false-> qr -> only displays qr
    // true -> nfc --> changes ui for nfc
    //
    private var isTerminalMode = false

    // listens for hce transaction updates/broadcasts
    // filters broadcasts sent by localbroadcastmanger
    // channel/action
    private val transactionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                EuroTokenHCEService.ACTION_TRANSACTION_COMPLETED -> {
                    val amount = intent.getLongExtra(EuroTokenHCEService.EXTRA_AMOUNT, 0)
                    val senderName = intent.getStringExtra(EuroTokenHCEService.EXTRA_SENDER_NAME) ?: "Unknown"
                    val senderKey = intent.getStringExtra(EuroTokenHCEService.EXTRA_SENDER_KEY) ?: ""

                    onTransactionComplete(amount, senderName, senderKey)
                }
                EuroTokenHCEService.ACTION_TRANSACTION_FAILED -> {
                    val error = intent.getStringExtra(EuroTokenHCEService.EXTRA_ERROR_MESSAGE) ?: "Unknown error"
                    onTransactionFailed(error)
                }
                EuroTokenHCEService.ACTION_CUSTOMER_CONNECTED -> {
                    updateStatus("Processing payment...")
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRequestMoneyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val transactionArgs = navArgs.transactionArgs
        if (transactionArgs == null) {
            Toast.makeText(requireContext(), "Error: Request details missing.", Toast.LENGTH_LONG).show()
            findNavController().popBackStack()
            return
        }

        when (transactionArgs.channel) {
            Channel.QR -> setupQRMode(transactionArgs)
            Channel.NFC -> setupNfcTerminalMode(transactionArgs)
            else -> {
                Toast.makeText(requireContext(), "Invalid channel", Toast.LENGTH_LONG).show()
                findNavController().popBackStack()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isTerminalMode) {
            // here it filters for  transactionreceiver
            // just look for hce string
            val filter = IntentFilter().apply {
                addAction(EuroTokenHCEService.ACTION_TRANSACTION_COMPLETED)
                addAction(EuroTokenHCEService.ACTION_TRANSACTION_FAILED)
                addAction(EuroTokenHCEService.ACTION_CUSTOMER_CONNECTED)
            }
            LocalBroadcastManager.getInstance(requireContext())
                .registerReceiver(transactionReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        if (isTerminalMode) {
            LocalBroadcastManager.getInstance(requireContext())
                .unregisterReceiver(transactionReceiver)
        }
    }

    private fun setupNfcTerminalMode(transactionArgs: nl.tudelft.trustchain.eurotoken.common.TransactionArgs) {
        isTerminalMode = true

        binding.qrModeLayout.visibility = View.GONE
        binding.nfcTerminalModeLayout.visibility = View.VISIBLE

        binding.txtAmountNfc.text = TransactionRepository.prettyAmount(transactionArgs.amount)

        binding.txtAmountNfc.textSize = 72f

        binding.txtStatusNfc.text = "Waiting for sender to connect..."

        // ripplllleeee
        binding.ivNfcWaitingAnimation.visibility = View.VISIBLE
        startRippleAnimation()

        binding.btnContinue.text = "Cancel"
        binding.btnContinue.setOnClickListener {
            requireContext().stopService(Intent(requireContext(), EuroTokenHCEService::class.java))
            findNavController().popBackStack()
        }

        startHceTerminalService(transactionArgs.amount)
    }

    private fun startHceTerminalService(amount: Long) {
        val myPublicKey = getTrustChainCommunity().myPeer.publicKey.keyToBin().toHex()
        val myName = ContactStore.getInstance(requireContext())
            .getContactFromPublicKey(getTrustChainCommunity().myPeer.publicKey)?.name ?: "Recipient"

        // lets start hce service with intent data (not static!)
        // was initially static
        val serviceIntent = Intent(requireContext(), EuroTokenHCEService::class.java).apply {
            action = EuroTokenHCEService.ACTION_START_TERMINAL_MODE
            putExtra(EuroTokenHCEService.EXTRA_AMOUNT, amount)
            putExtra(EuroTokenHCEService.EXTRA_PUBLIC_KEY, myPublicKey)
            putExtra(EuroTokenHCEService.EXTRA_NAME, myName)
        }

        requireContext().startService(serviceIntent)
        Log.d(TAG, "Started HCE Service for amount: ${TransactionRepository.prettyAmount(amount)}")
    }

    private fun updateStatus(status: String) {
        binding.txtStatusNfc.text = when (status) {
            "Processing payment..." -> "⚡ Processing payment..."
            "Payment complete" -> "✅ Payment received!"
            else -> status
        }
    }

    private fun onTransactionComplete(amount: Long, senderName: String, senderKey: String) {
        binding.ivNfcWaitingAnimation.clearAnimation()
        binding.ivNfcWaitingAnimation.visibility = View.GONE

        binding.txtStatusNfc.text = "🎉 Payment Received!"
        binding.txtStatusNfc.setTextColor(ContextCompat.getColor(requireContext(), R.color.green))

        // we update button to go back
        binding.btnContinue.text = "Done"
        binding.btnContinue.setOnClickListener {
            findNavController().popBackStack()
        }

        Log.d(TAG, "NFC Terminal mode: Transaction completed successfully") //
    }

    private fun onTransactionFailed(error: String) {
        binding.ivNfcWaitingAnimation.clearAnimation()
        binding.ivNfcWaitingAnimation.visibility = View.GONE

        binding.txtStatusNfc.text = "Transaction Failed"
        binding.txtStatusNfc.setTextColor(ContextCompat.getColor(requireContext(), R.color.red))
        binding.txtStatusNfc.append("\n$error")

        // lets reset it
        lifecycleScope.launch {
            kotlinx.coroutines.delay(3000)
            if (isResumed) {
                setupNfcTerminalMode(navArgs.transactionArgs)
            }
        }
    }

    // makes it less static
    // ripple animation adds some flair
    private fun startRippleAnimation() {
        lifecycleScope.launch {
            while (isTerminalMode && _binding != null && binding.ivNfcWaitingAnimation.visibility == View.VISIBLE) {
                binding.ivNfcWaitingAnimation.animate()
                    .scaleX(1.5f).scaleY(1.5f)
                    .alpha(0f)
                    .setDuration(1500)
                    // .setDuration(1700)
                    .withEndAction {
                        _binding?.let { safeBinding ->
                            if (isTerminalMode) {
                                safeBinding.ivNfcWaitingAnimation.scaleX = 1f
                                safeBinding.ivNfcWaitingAnimation.scaleY = 1f
                                // safeBinding.ivNfcWaitingAnimation.scaleY = 2f
                                safeBinding.ivNfcWaitingAnimation.alpha = 1f
                            }
                        }
                    }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    // potentially add vibration/sound, not necessary for now

    private fun setupQRMode(transactionArgs: nl.tudelft.trustchain.eurotoken.common.TransactionArgs) {
        binding.qrModeLayout.visibility = View.VISIBLE
        binding.nfcTerminalModeLayout.visibility = View.GONE

        val qrBitmap = qrCodeUtils.createQR(transactionArgs.qrData ?: "")
        binding.qr?.setImageBitmap(qrBitmap)
        binding.txtRequest?.text = TransactionRepository.prettyAmount(transactionArgs.amount)

        binding.btnContinueQr?.setOnClickListener {
            findNavController().popBackStack()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (isTerminalMode) {
            requireContext().stopService(Intent(requireContext(), EuroTokenHCEService::class.java))
            Log.d(TAG, "Receiver mode destroyed, HCE service stopped")
        }
        _binding = null
    }

    companion object {
        const val TAG = "RequestMoneyFragment"
    }
}
