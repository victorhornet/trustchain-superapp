package nl.tudelft.trustchain.eurotoken.ui.bonds

import nl.tudelft.trustchain.eurotoken.ui.EurotokenBaseFragment
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
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
    private var showAsLender = true // Default to showing bonds where user is lender

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup tabs
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("As Lender"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("As Receiver"))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showAsLender = tab.position == 0
                loadBonds()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        adapter.registerRenderer(BondItemRenderer())
        binding.bondsRecyclerView.adapter = adapter
        binding.bondsRecyclerView.layoutManager = LinearLayoutManager(context)

        loadBonds()
    }

    private fun loadBonds() {
        lifecycleScope.launch(Dispatchers.IO) {
            val bonds = if (showAsLender) {
                community.getBondsByLender()
            } else {
                community.getBondsByReceiver()
            }

            withContext(Dispatchers.Main) {
                adapter.updateItems(bonds.map { BondItem(it) })
            }
        }
    }

    inner class BondItemRenderer : ItemLayoutRenderer<BondItem, View>(BondItem::class.java) {
        override fun bindView(item: BondItem, view: View) {
            val binding = ItemBondBinding.bind(view)
            val bond = item.bond
            val myKey = community.myPeer.publicKey.keyToBin()

            binding.txtBondId.text = "Bond: ${bond.id.take(8)}"
            binding.txtAmount.text = "€${"%.2f".format(bond.amount)}"
            binding.txtCounterparty.text = if (bond.publicKeyLender.contentEquals(myKey)) {
                "To: ${bond.publicKeyReceiver.toHex().take(8)}"
            } else {
                "From: ${bond.publicKeyLender.toHex().take(8)}"
            }

            // Status-specific UI
            when (bond.status) {
                BondStatus.ACTIVE -> {
                    binding.btnAction1.visibility = View.VISIBLE
                    binding.btnAction1.text = if (bond.publicKeyLender.contentEquals(myKey)) "Forfeit" else "Claim"
                    binding.btnAction2.visibility = if (bond.publicKeyLender.contentEquals(myKey)) View.VISIBLE else View.GONE
                    binding.btnAction2.text = "Enforce"
                }
                BondStatus.FORFEITED -> binding.txtStatus.text = "Forfeited"
                BondStatus.CLAIMED -> binding.txtStatus.text = "Claimed"
                BondStatus.EXPIRED -> binding.txtStatus.text = "Expired"
                else -> binding.txtStatus.text = "Released"
            }

            binding.btnAction1.setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    if (bond.publicKeyLender.contentEquals(myKey)) {
                        community.forfeitBond(bond.id)
                    } else {
                        community.claimBond(bond.id)
                    }
                    withContext(Dispatchers.Main) { loadBonds() }
                }
            }

            binding.btnAction2.setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    community.enforceBond(bond.id, bond.publicKeyLender)
                    withContext(Dispatchers.Main) { loadBonds() }
                }
            }
        }

        override fun getLayoutResourceId(): Int = R.layout.item_bond
    }
}

