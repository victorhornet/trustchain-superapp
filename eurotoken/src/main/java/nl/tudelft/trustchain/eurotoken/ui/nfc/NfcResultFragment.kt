package nl.tudelft.trustchain.eurotoken.ui.nfc

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.findNavController
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.FragmentNfcResultBinding
import nl.tudelft.trustchain.eurotoken.ui.EurotokenBaseFragment
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository

class NfcResultFragment : EurotokenBaseFragment(R.layout.fragment_nfc_result) {
    private var _binding: FragmentNfcResultBinding? = null
    private val binding get() = _binding!!

    // Read the passed args
    private val amountStr by lazy { requireArguments().getString("amount") ?: "0" }
    private val nameStr   by lazy { requireArguments().getString("name")   ?: "recipient" }
    private val pubKeyHex by lazy { requireArguments().getString("pubkey") ?: "" }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentNfcResultBinding.bind(view)

    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
