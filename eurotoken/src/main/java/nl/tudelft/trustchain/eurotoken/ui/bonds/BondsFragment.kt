package nl.tudelft.trustchain.eurotoken.ui.bonds

import nl.tudelft.trustchain.eurotoken.ui.EurotokenBaseFragment
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.mattskala.itemadapter.ItemAdapter
import com.mattskala.itemadapter.ItemLayoutRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.util.viewBinding
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.FragmentBondsBinding
import nl.tudelft.trustchain.eurotoken.databinding.ItemBondBinding
import nl.tudelft.trustchain.eurotoken.entity.BondStatus
// If you don't have BondItem, add this:
import com.mattskala.itemadapter.Item
import nl.tudelft.trustchain.eurotoken.entity.Bond

data class BondItem(val bond: Bond) : Item()

class BondsFragment : EurotokenBaseFragment(R.layout.fragment_bonds) {
    private val binding by viewBinding(FragmentBondsBinding::bind)
    private val adapter = ItemAdapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter.registerRenderer(BondItemRenderer())
        binding.bondsRecyclerView.adapter = adapter
        binding.bondsRecyclerView.layoutManager = LinearLayoutManager(context)

        loadBonds()
    }

    private fun loadBonds() {
        lifecycleScope.launch(Dispatchers.IO) {
            val bonds = community.getActiveBonds()

            withContext(Dispatchers.Main) {
                adapter.updateItems(bonds.map { BondItem(it) })
            }
        }
    }

    inner class BondItemRenderer : ItemLayoutRenderer<BondItem, View>(BondItem::class.java) {
        override fun bindView(item: BondItem, view: View) {
            val binding = ItemBondBinding.bind(view)
            val bond = item.bond

            binding.txtBondId.text = bond.id.take(8)
            binding.txtAmount.text = "€${"%.2f".format(bond.amount)}"
            binding.txtCounterparty.text = bond.publicKeyReceiver.toHex().take(8)

            when (bond.status) {
                BondStatus.ACTIVE -> binding.btnClaim.visibility = View.VISIBLE
                else -> binding.btnClaim.visibility = View.GONE
            }

            binding.btnClaim.setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    val claimed = community.claimBond(bond.id)

                    withContext(Dispatchers.Main) {
                        if (claimed) {
                            Toast.makeText(context, "Bond claimed!", Toast.LENGTH_SHORT).show()
                            loadBonds()
                        }
                    }
                }
            }
        }

        override fun getLayoutResourceId(): Int = R.layout.item_bond
    }
}


