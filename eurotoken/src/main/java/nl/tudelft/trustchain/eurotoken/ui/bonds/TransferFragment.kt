package nl.tudelft.trustchain.eurotoken.ui.bonds

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.tudelft.ipv8.util.hexToBytes
import nl.tudelft.trustchain.common.util.viewBinding
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.FragmentTransferBinding
import nl.tudelft.trustchain.eurotoken.ui.EurotokenBaseFragment

// New TransferFragment.kt
class TransferFragment : EurotokenBaseFragment(R.layout.fragment_transfer) {
    private val binding by viewBinding(FragmentTransferBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnSend.setOnClickListener {
            val amount = binding.etAmount.text.toString().toDoubleOrNull() ?: 0.0
            val amountCents = (amount * 100).toLong()
            val receiverHex = binding.etReceiver.text.toString().trim()

            if (receiverHex.isEmpty()) {
                Toast.makeText(requireContext(), "Enter receiver address", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val receiverKey = receiverHex.hexToBytes()
                    val block = community.sendTransferProposalWithRiskCheck(receiverKey, amountCents)

                    withContext(Dispatchers.Main) {
                        if (block != null) {
                            Toast.makeText(requireContext(), "Transfer successful!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(requireContext(), "Transfer rejected: Risk too high", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Invalid address format", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
