package nl.tudelft.trustchain.eurotoken.ui.vouches

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.mattskala.itemadapter.ItemAdapter
import kotlinx.coroutines.delay
import nl.tudelft.trustchain.common.util.viewBinding
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.FragmentVouchListBinding
import nl.tudelft.trustchain.eurotoken.db.VouchStore
import nl.tudelft.trustchain.eurotoken.ui.EurotokenBaseFragment

/**
 * A fragment for displaying a list of vouches (either own or received).
 */
class VouchListFragment : EurotokenBaseFragment(R.layout.fragment_vouch_list) {
    private val binding by viewBinding(FragmentVouchListBinding::bind)
    private val adapter = ItemAdapter()

    private val vouchStore by lazy {
        VouchStore.getInstance(requireContext())
    }

    private var isReceived: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        isReceived = arguments?.getBoolean(ARG_IS_RECEIVED, false) ?: false
        adapter.registerRenderer(VouchItemRenderer())
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        binding.vouchesRecyclerView.adapter = adapter
        binding.vouchesRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.vouchesRecyclerView.addItemDecoration(
            DividerItemDecoration(context, LinearLayout.VERTICAL)
        )

        refreshVouches()
    }

    override fun onResume() {
        super.onResume()
        refreshVouches()
    }

    fun refreshVouches() {
        lifecycleScope.launchWhenResumed {
            val vouches =
                if (isReceived) {
                    vouchStore.getReceivedActiveVouches()
                } else {
                    vouchStore.getOwnActiveVouches()
                }

            val items = vouches.map { vouch ->
                val trustScore = trustStore.getScore(vouch.vouchedForPubKey)
                VouchItem(vouch, trustScore?.toInt())
            }
            adapter.updateItems(items)
            adapter.notifyDataSetChanged()

            delay(100L)
        }
    }

    companion object {
        private const val ARG_IS_RECEIVED = "is_received"

        fun newInstance(isReceived: Boolean): VouchListFragment {
            val fragment = VouchListFragment()
            val args = Bundle()
            args.putBoolean(ARG_IS_RECEIVED, isReceived)
            fragment.arguments = args
            return fragment
        }
    }
}
