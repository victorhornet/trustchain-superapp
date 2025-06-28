package nl.tudelft.trustchain.eurotoken.ui.bonds

import android.view.View
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding

class ViewBindingExtensions {
    inline fun <reified T : ViewBinding> Fragment.viewBinding(
        crossinline binder: (View) -> T,
        crossinline viewProvider: (Fragment) -> View = Fragment::requireView
    ): Lazy<T> = lazy {
        binder(viewProvider(this))
    }
}
