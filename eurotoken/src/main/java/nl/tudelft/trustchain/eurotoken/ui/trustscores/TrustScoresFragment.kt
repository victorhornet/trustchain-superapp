package nl.tudelft.trustchain.eurotoken.ui.trustscores

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.mattskala.itemadapter.ItemAdapter
import nl.tudelft.trustchain.common.util.viewBinding
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.ui.EurotokenBaseFragment
import nl.tudelft.trustchain.eurotoken.databinding.FragmentTrustScoresBinding
import nl.tudelft.trustchain.eurotoken.entity.TrustScore

/**
 * A simple [Fragment] subclass.
 * Use the [TrustScoresFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class TrustScoresFragment : EurotokenBaseFragment(R.layout.fragment_trust_scores) {
    private val binding by viewBinding(FragmentTrustScoresBinding::bind)

    private val adapter = ItemAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter.registerRenderer(TrustScoreItemRenderer())
    }

    override fun onResume() {
        super.onResume()
        
        lifecycleScope.launchWhenResumed {
            val items =
                trustStore.getAllScores()
                    .map { trustScore: TrustScore -> TrustScoreItem(trustScore) }
            adapter.updateItems(items)
            adapter.notifyDataSetChanged()
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        binding.trustScoresRecyclerView.adapter = adapter
        binding.trustScoresRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.trustScoresRecyclerView.addItemDecoration(
            DividerItemDecoration(
                context,
                LinearLayout.VERTICAL
            )
        )
    }
}
