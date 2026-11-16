package com.wusan.location.tmap

import android.content.Context
import android.util.AttributeSet
import com.tencent.tencentmap.mapsdk.maps.MapView
import com.tencent.tencentmap.mapsdk.maps.TencentMapOptions

/** Supply the decrypted key before MapView creates its map engine. */
class SecureTencentMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MapView(context, attrs, defStyleAttr, TencentMapOptions().setMapKey(TencentMapKey.value))
