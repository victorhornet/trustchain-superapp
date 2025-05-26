package nl.tudelft.trustchain.eurotoken.ui.vouch

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import nl.tudelft.trustchain.common.util.viewBinding
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.FragmentVouchDetailBinding
import nl.tudelft.trustchain.eurotoken.ui.EurotokenBaseFragment
import androidx.navigation.fragment.findNavController
import nl.tudelft.ipv8.util.toHex
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class VouchDetailFragment : EurotokenBaseFragment(R.layout.fragment_vouch_detail) {
    private val binding by viewBinding(FragmentVouchDetailBinding::bind)
    
    private val viewModel: VouchManagementViewModel by activityViewModels { 
        VouchManagementViewModelFactory(vouchStore, transactionRepository) 
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val pubKeyHex = requireArguments().getString("pubKey")!!
        val trustScores = trustStore.getAllScores()
        val trustScore = trustScores.find { it.pubKey.toHex() == pubKeyHex } ?: return
        binding.txtPubKey.text = pubKeyHex
        binding.txtTrustScore.text = "Trust Score: ${trustScore.trust}%"
        binding.txtRiskFactor.text = "Risk: (coming soon)"
        val vouchEntry = viewModel.getVouch(trustScore.pubKey)
        binding.editVouchAmount.setText(vouchEntry?.vouchAmount?.toString() ?: "")

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        var selectedVouchUntil: Long? = vouchEntry?.vouchUntil
        if (selectedVouchUntil != null) {
            binding.txtVouchUntil.text = "Vouch until: " + dateFormat.format(selectedVouchUntil)
        }

        binding.btnPickVouchUntil.setOnClickListener {
            val now = Calendar.getInstance()
            if (selectedVouchUntil != null) now.timeInMillis = selectedVouchUntil as Long
            DatePickerDialog(requireContext(), { _, year, month, dayOfMonth ->
                val pickedDate = Calendar.getInstance()
                pickedDate.set(year, month, dayOfMonth)
                TimePickerDialog(requireContext(), { _, hour, minute ->
                    pickedDate.set(Calendar.HOUR_OF_DAY, hour)
                    pickedDate.set(Calendar.MINUTE, minute)
                    pickedDate.set(Calendar.SECOND, 0)
                    pickedDate.set(Calendar.MILLISECOND, 0)
                    selectedVouchUntil = pickedDate.timeInMillis
                    binding.txtVouchUntil.text = "Vouch until: " + dateFormat.format(pickedDate.time)
                }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
            }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
        }

        binding.btnUpdate.setOnClickListener {
            val input = binding.editVouchAmount.text.toString()
            val amount = input.toDoubleOrNull()
            val balance = transactionRepository.getMyBalance() // balance in cents
            // Only let user vouch for an amount that is less than or equal to their balance
            if (amount == null || amount < 0 || !Regex("""^\d+(\.\d{1,2})?$""").matches(input)) {
                Toast.makeText(requireContext(), "Invalid amount", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if ((amount * 100).toLong() > balance) {
                Toast.makeText(requireContext(), "Amount exceeds your current balance", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.setVouch(trustScore.pubKey, amount, selectedVouchUntil)
            Toast.makeText(requireContext(), "Vouch updated and saved", Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
        }
    }
}
