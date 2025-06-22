package nl.tudelft.trustchain.eurotoken.ui.vouches

import android.graphics.Color
import android.view.View
import com.mattskala.itemadapter.ItemLayoutRenderer
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.ItemVouchBinding
import java.text.SimpleDateFormat
import java.util.*

/**
 * [VouchItemRenderer] used by the [VouchesFragment] to render the [VouchItem] items as a list.
 */
class VouchItemRenderer :
    ItemLayoutRenderer<VouchItem, View>(
        VouchItem::class.java
    ) {
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

    override fun bindView(
        item: VouchItem,
        view: View
    ) = with(view) {
        val binding = ItemVouchBinding.bind(view)
        val vouch = item.vouch

        // Set vouched-for public key (truncated)
        val pubKeyHex = vouch.vouchedForPubKey.toHex()
        binding.txtVouchedForPubKey.text = pubKeyHex.take(32) + "..."

        // Set amount in euros
        val amountInEuros = vouch.amount / 100.0
        binding.txtAmount.text = String.format("€%.2f", amountInEuros)

        // Set expiry date
        binding.txtExpiryDate.text = "Expires: ${dateFormat.format(vouch.expiryDate)}"

        // Set status
        val currentTime = System.currentTimeMillis()
        val isExpired = vouch.expiryDate.time < currentTime

        when {
            !vouch.isActive -> {
                binding.txtStatus.text = "INACTIVE"
                binding.txtStatus.setBackgroundColor(Color.GRAY)
            }
            isExpired -> {
                binding.txtStatus.text = "EXPIRED"
                binding.txtStatus.setBackgroundColor(Color.RED)
            }
            else -> {
                binding.txtStatus.text = "ACTIVE"
                binding.txtStatus.setBackgroundColor(Color.GREEN)
            }
        }

        // Set description (show only if not empty)
        if (vouch.description.isNotEmpty()) {
            binding.txtDescription.text = vouch.description
            binding.txtDescription.visibility = View.VISIBLE
        } else {
            binding.txtDescription.visibility = View.GONE
        }

        // For received vouches, show additional info about the sender
        if (vouch.isReceived && vouch.senderPubKey != null) {
            val senderHex = vouch.senderPubKey.toHex()
            binding.txtVouchedForLabel.text = "Vouched for (from ${senderHex.take(16)}...):"
        } else {
            binding.txtVouchedForLabel.text = "Vouched for:"
        }
    }

    override fun getLayoutResourceId(): Int = R.layout.item_vouch
}
