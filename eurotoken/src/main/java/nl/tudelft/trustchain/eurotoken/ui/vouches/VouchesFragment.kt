package nl.tudelft.trustchain.eurotoken.ui.vouches

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.delay
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.util.viewBinding
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.DialogCreateVouchBinding
import nl.tudelft.trustchain.eurotoken.databinding.FragmentVouchesBinding
import nl.tudelft.trustchain.eurotoken.db.VouchStore
import nl.tudelft.trustchain.eurotoken.entity.Vouch
import nl.tudelft.trustchain.eurotoken.ui.EurotokenBaseFragment
import java.text.SimpleDateFormat
import java.util.*

/**
 * A [Fragment] for managing vouches - commitments to pay certain amounts for other users.
 */
class VouchesFragment : EurotokenBaseFragment(R.layout.fragment_vouches) {
    private val binding by viewBinding(FragmentVouchesBinding::bind)

    private val vouchStore by lazy {
        VouchStore.getInstance(requireContext())
    }

    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    private lateinit var pagerAdapter: VouchPagerAdapter

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        setupTabs()

        binding.fabAddVouch.setOnClickListener {
            showCreateVouchDialog()
        }

        refreshTotalVouched()
    }

    override fun onResume() {
        super.onResume()
        refreshTotalVouched()
        refreshTabContents()
    }

    private fun setupTabs() {
        pagerAdapter = VouchPagerAdapter(childFragmentManager, lifecycle)
        binding.viewPager.adapter = pagerAdapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text =
                when (position) {
                    0 -> "My Vouches"
                    1 -> "Received Vouches"
                    else -> "Tab $position"
                }
        }.attach()
    }

    private fun refreshTotalVouched() {
        lifecycleScope.launchWhenResumed {
            // Update total vouched amount (only own vouches, not received)
            val totalAmount = vouchStore.getTotalOwnVouchedAmount()
            val totalInEuros = totalAmount / 100.0
            binding.txtTotalVouched.text = String.format("Total Vouched: €%.2f", totalInEuros)

            delay(100L)
        }
    }

    private fun refreshTabContents() {
        // Refresh both tabs by getting current fragments and calling their refresh methods
        val currentFragment = childFragmentManager.findFragmentByTag("f${binding.viewPager.currentItem}")
        if (currentFragment is VouchListFragment) {
            currentFragment.refreshVouches()
        }

        // Also refresh the other tab if it exists
        val otherIndex = if (binding.viewPager.currentItem == 0) 1 else 0
        val otherFragment = childFragmentManager.findFragmentByTag("f$otherIndex")
        if (otherFragment is VouchListFragment) {
            otherFragment.refreshVouches()
        }
    }

    /**
     * Validates if the user has sufficient balance to create a vouch for the given amount.
     * @param amountInCents The amount to vouch for in cents
     * @return true if sufficient balance is available, false otherwise
     */
    private fun validateVouchBalance(amountInCents: Long): Boolean {
        val currentBalance = transactionRepository.getMyBalance()

        // Handle negative balance case
        if (currentBalance < 0) {
            Toast.makeText(
                requireContext(),
                "Cannot create vouch: Your account balance is negative",
                Toast.LENGTH_LONG
            ).show()
            return false
        }

        // Get total amount already vouched for (only own vouches, not received ones)
        val totalOwnVouchedAmount = vouchStore.getTotalOwnVouchedAmount()
        val availableBalance = currentBalance - totalOwnVouchedAmount

        // Check if enough balance is available
        if (amountInCents > availableBalance) {
            val balanceInEuros = currentBalance / 100.0
            val vouchedInEuros = totalOwnVouchedAmount / 100.0
            val availableInEuros = availableBalance / 100.0
            val requestedInEuros = amountInCents / 100.0

            Toast.makeText(
                requireContext(),
                "Insufficient available balance.\nTotal: €%.2f | Already vouched: €%.2f | Available: €%.2f | Requested: €%.2f".format(
                    balanceInEuros,
                    vouchedInEuros,
                    availableInEuros,
                    requestedInEuros
                ),
                Toast.LENGTH_LONG
            ).show()
            return false
        }

        return true
    }

    private fun showCreateVouchDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_create_vouch, null)
        val dialogBinding = DialogCreateVouchBinding.bind(dialogView)

        // Get trust scores for the spinner
        val trustScores = trustStore.getAllScores()
        if (trustScores.isEmpty()) {
            Toast.makeText(requireContext(), "No trusted users found. Complete some transactions first.", Toast.LENGTH_LONG).show()
            return
        }

        // Create adapter for user selection
        val userDisplayNames =
            trustScores.map { trustScore ->
                val pubKeyHex = trustScore.pubKey.toHex()
                "${pubKeyHex.take(16)}... (Trust: ${trustScore.trust}%)"
            }
        val userAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, userDisplayNames)
        userAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        dialogBinding.spinnerUsers.adapter = userAdapter

        // Display current balance information
        val currentBalance = transactionRepository.getMyBalance()
        val totalVouchedAmount = vouchStore.getTotalOwnVouchedAmount()
        val availableBalance = currentBalance - totalVouchedAmount
        val balanceInfoText =
            "Balance: €%.2f | Vouched: €%.2f | Available: €%.2f".format(
                currentBalance / 100.0,
                totalVouchedAmount / 100.0,
                availableBalance / 100.0
            )
        dialogBinding.txtBalanceInfo.text = balanceInfoText

        var selectedDate: Date? = null

        // Date picker for expiry date
        dialogBinding.btnSelectDate.setOnClickListener {
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.MONTH, 1) // Default to 1 month from now

            val datePickerDialog =
                DatePickerDialog(
                    requireContext(),
                    { _, year, month, dayOfMonth ->
                        calendar.set(year, month, dayOfMonth)
                        selectedDate = calendar.time
                        dialogBinding.btnSelectDate.text = dateFormat.format(selectedDate!!)
                    },
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH),
                    calendar.get(Calendar.DAY_OF_MONTH)
                )

            // Set minimum date to tomorrow
            val tomorrow = Calendar.getInstance()
            tomorrow.add(Calendar.DAY_OF_MONTH, 1)
            datePickerDialog.datePicker.minDate = tomorrow.timeInMillis

            datePickerDialog.show()
        }

        val dialog =
            AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .create()

        dialogBinding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnCreate.setOnClickListener {
            val selectedUserIndex = dialogBinding.spinnerUsers.selectedItemPosition
            val amountText = dialogBinding.etAmount.text.toString()
            val description = dialogBinding.etDescription.text.toString().trim()

            when {
                selectedUserIndex < 0 || selectedUserIndex >= trustScores.size -> {
                    Toast.makeText(requireContext(), "Please select a user", Toast.LENGTH_SHORT).show()
                }
                amountText.isBlank() -> {
                    Toast.makeText(requireContext(), "Please enter an amount", Toast.LENGTH_SHORT).show()
                }
                selectedDate == null -> {
                    Toast.makeText(requireContext(), "Please select an expiry date", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    try {
                        val amount = amountText.toDouble()
                        if (amount <= 0) {
                            Toast.makeText(requireContext(), "Amount must be greater than 0", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }

                        val selectedTrustScore = trustScores[selectedUserIndex]
                        val amountInCents = (amount * 100).toLong()

                        if (!validateVouchBalance(amountInCents)) {
                            return@setOnClickListener
                        }

                        val vouch =
                            Vouch(
                                vouchedForPubKey = selectedTrustScore.pubKey,
                                amount = amountInCents,
                                expiryDate = selectedDate!!,
                                createdDate = Date(),
                                description = description,
                                isActive = true,
                                isReceived = false,
                                senderPubKey = null
                            )
                        vouchStore.addVouch(vouch)

                        Toast.makeText(requireContext(), "Vouch created successfully!", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        refreshTotalVouched()
                        refreshTabContents()
                    } catch (e: NumberFormatException) {
                        Toast.makeText(requireContext(), "Invalid amount entered", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        dialog.show()
    }
}
