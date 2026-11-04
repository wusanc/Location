package com.wusan.location.ui.features

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.wusan.location.R
import com.wusan.location.databinding.FragmentFeaturesBinding
import com.wusan.location.databinding.ItemFeatureBinding

/**
 * 底部导航的「功能」页：把原先侧边栏里的几个功能收进这里的入口列表。
 */
class FeaturesFragment : Fragment() {

    private var _binding: FragmentFeaturesBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFeaturesBinding.inflate(inflater, container, false)

        bindCard(
            binding.cardMap,
            R.drawable.baseline_edit_location_alt_24,
            R.string.feature_map_title,
            R.string.feature_map_desc,
            R.id.nav_home
        )
        bindCard(
            binding.cardMock,
            R.drawable.baseline_label_important_24,
            R.string.feature_mock_title,
            R.string.feature_mock_desc,
            R.id.nav_mock
        )
        bindCard(
            binding.cardGnss,
            R.drawable.baseline_satellite_alt_24,
            R.string.feature_gnss_title,
            R.string.feature_gnss_desc,
            R.id.nav_gnss_mock
        )
        bindCard(
            binding.cardRoute,
            R.drawable.baseline_route_24,
            R.string.feature_route_title,
            R.string.feature_route_desc,
            R.id.nav_route_gallery
        )

        return binding.root
    }

    private fun bindCard(
        card: ItemFeatureBinding,
        @DrawableRes icon: Int,
        @StringRes title: Int,
        @StringRes subtitle: Int,
        destinationId: Int
    ) {
        card.featureIcon.setImageResource(icon)
        card.featureTitle.setText(title)
        card.featureSubtitle.setText(subtitle)
        card.root.setOnClickListener { navigateTo(destinationId) }
    }

    /** 防止连点导致重复导航。 */
    private fun navigateTo(destinationId: Int) {
        val navController = findNavController()
        if (navController.currentDestination?.id == R.id.nav_features) {
            navController.navigate(destinationId)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}