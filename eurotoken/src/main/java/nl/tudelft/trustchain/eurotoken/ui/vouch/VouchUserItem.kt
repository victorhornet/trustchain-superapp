package nl.tudelft.trustchain.eurotoken.ui.vouch

import com.mattskala.itemadapter.Item
import nl.tudelft.trustchain.eurotoken.entity.TrustScore
import nl.tudelft.trustchain.eurotoken.entity.VouchEntry

class VouchUserItem(val trustScore: TrustScore, val vouchEntry: VouchEntry?) : Item()
