package nl.tudelft.trustchain.eurotoken.ui.bonds
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.util.viewBinding
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.FragmentLoanPayoutBinding
import nl.tudelft.trustchain.eurotoken.ui.EurotokenBaseFragment

class LoanPayoutFragment : EurotokenBaseFragment(R.layout.fragment_loan_payout) {
    private val binding by viewBinding(FragmentLoanPayoutBinding::bind)
    private val myPublicKey: ByteArray
        get() = community.myPeer.publicKey.keyToBin()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnCalculateRisk.setOnClickListener {
            calculateRisk()
        }

        binding.btnRequestPayout.setOnClickListener {
            requestPayout()
        }
    }

    private fun calculateRisk() {
        val amount = binding.etAmount.text.toString().toDoubleOrNull() ?: 0.0
        val amountCents = (amount * 100).toLong()

        val coverage = community.getTotalVouchCoverage(myPublicKey)
        val coverageEuros = coverage / 100.0

        binding.tvCoverage.text = "Vouch Coverage: €${"%.2f".format(coverageEuros)}"
        binding.tvRiskResults.text = "" // Clear previous results

        val guarantors = community.getVouchStore().getGuarantorsForUser(myPublicKey, community.getTrustStore())
        var allRiskAccepted = true

        guarantors.forEach { guarantor ->
            val risk = community.estimateRisk(amountCents, guarantor.publicKey)

            if (risk < 0.5) {
                allRiskAccepted = false
                binding.tvRiskResults.append(
                    "⚠️ ${guarantor.publicKey.toHex().take(8)}: ${(risk*100).toInt()}%\n"
                )
            } else {
                binding.tvRiskResults.append(
                    "✓ ${guarantor.publicKey.toHex().take(8)}: ${(risk*100).toInt()}%\n"
                )
            }
        }

        binding.btnRequestPayout.isEnabled = allRiskAccepted && coverage >= amountCents
    }

    private fun requestPayout() {
        val amount = binding.etAmount.text.toString().toDoubleOrNull() ?: 0.0
        val amountCents = (amount * 100).toLong()

        lifecycleScope.launch(Dispatchers.IO) {
            community.processLoanPayout(myPublicKey, amountCents)

            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), "Payout processed!", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
