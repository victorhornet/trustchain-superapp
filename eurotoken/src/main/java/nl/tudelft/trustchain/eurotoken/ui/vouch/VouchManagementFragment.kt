package nl.tudelft.trustchain.eurotoken.ui.vouch

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.mattskala.itemadapter.ItemAdapter
import kotlinx.coroutines.launch
import nl.tudelft.trustchain.common.util.viewBinding
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.FragmentVouchManagementBinding
import nl.tudelft.trustchain.eurotoken.ui.EurotokenBaseFragment
import androidx.navigation.fragment.findNavController
import nl.tudelft.ipv8.util.toHex
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class VouchManagementFragment : EurotokenBaseFragment(R.layout.fragment_vouch_management) {
    private val binding by viewBinding(FragmentVouchManagementBinding::bind)

    private val viewModel: VouchManagementViewModel by viewModels {
        VouchManagementViewModelFactory(vouchStore, transactionRepository)
    }
    private val adapter = ItemAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize vouch table (already done in base class, but ensure it's called)
        vouchStore.createVouchTable()

        adapter.registerRenderer(
            VouchUserItemRenderer { trustScore ->
                findNavController().navigate(
                    R.id.action_vouchManagementFragment_to_vouchDetailFragment,
                    bundleOf("pubKey" to trustScore.pubKey.toHex())
                )
            }
        )
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)
        binding.vouchRecyclerView.adapter = adapter
        binding.vouchRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.vouchRecyclerView.addItemDecoration(
            DividerItemDecoration(context, LinearLayout.VERTICAL)
        )

        var selectedVouchUntil: Long? = null
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        binding.btnPickVouchUntil.setOnClickListener {
            val now = Calendar.getInstance()
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

        lifecycleScope.launch {
            val trustScores = trustStore.getAllScores()
            viewModel.vouches.observe(viewLifecycleOwner) { vouches ->
                val items =
                    trustScores.map {
                        VouchUserItem(it, vouches[it.pubKey.toHex()])
                    }
                adapter.updateItems(items)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh vouches when fragment resumes
        viewModel.refreshVouches()
    }
}
