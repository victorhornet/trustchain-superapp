package nl.tudelft.trustchain.eurotoken.ui.vouches

import com.mattskala.itemadapter.Item
import nl.tudelft.trustchain.eurotoken.entity.Vouch

/**
 * [VouchItem] used by the [VouchesFragment] to render the [Vouch] items as a list.
 */
class VouchItem(
    val vouch: Vouch,
    val trustScore: Int? = null
) : Item() {
    override fun areItemsTheSame(other: Item): Boolean {
        return other is VouchItem && vouch == other.vouch
    }
}
