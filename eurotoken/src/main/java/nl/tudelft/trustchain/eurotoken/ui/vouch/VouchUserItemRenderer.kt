package nl.tudelft.trustchain.eurotoken.ui.vouch

import android.view.View
import com.mattskala.itemadapter.ItemLayoutRenderer
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.ItemTrustscoreBinding

class VouchUserItemRenderer(
    private val onClick: (nl.tudelft.trustchain.eurotoken.entity.TrustScore) -> Unit
) : ItemLayoutRenderer<VouchUserItem, View>(VouchUserItem::class.java) {
    override fun bindView(item: VouchUserItem, view: View) = with(view) {
        val binding = ItemTrustscoreBinding.bind(view)
        binding.txtPubKey.text = item.trustScore.pubKey.toHex()
        binding.txtTrustScore.text = "${item.trustScore.trust}%"
        setOnClickListener { onClick(item.trustScore) }
    }
    override fun getLayoutResourceId() = R.layout.item_trustscore
} 