package nl.tudelft.trustchain.eurotoken.ui.vouches

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * ViewPager adapter for vouch tabs (own vouches and received vouches).
 */
class VouchPagerAdapter(
    fragmentManager: FragmentManager,
    lifecycle: Lifecycle
) : FragmentStateAdapter(fragmentManager, lifecycle) {

    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> VouchListFragment.newInstance(isReceived = false) // Own vouches
            1 -> VouchListFragment.newInstance(isReceived = true)  // Received vouches
            else -> throw IllegalArgumentException("Invalid position: $position")
        }
    }
} 