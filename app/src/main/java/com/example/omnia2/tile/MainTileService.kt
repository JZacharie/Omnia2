package com.example.omnia2.tile

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders.Resources
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Typography.TYPOGRAPHY_BODY1
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.ButtonDefaults
import androidx.wear.tiles.RequestBuilders.ResourcesRequest
import androidx.wear.tiles.tooling.preview.Preview
import androidx.wear.tiles.tooling.preview.TilePreviewData
import androidx.wear.tooling.preview.devices.WearDevices
import com.example.omnia2.R
import com.example.omnia2.presentation.MainActivity
import com.google.common.util.concurrent.ListenableFuture

private const val RESOURCES_VERSION = "0"

class MainTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        Futures.immediateFuture(tile(requestParams, this))

    override fun onTileResourcesRequest(requestParams: ResourcesRequest): ListenableFuture<Resources> =
        Futures.immediateFuture(resources(requestParams))
}

private fun resources(requestParams: ResourcesRequest): Resources {
    return Resources.Builder()
        .setVersion(RESOURCES_VERSION)
        .build()
}

private fun tile(
    requestParams: RequestBuilders.TileRequest,
    context: Context,
): TileBuilders.Tile {
    val layout = PrimaryLayout.Builder(requestParams.deviceConfiguration)
        .setContent(
            Text.Builder(context, context.getString(R.string.app_name))
                .setTypography(TYPOGRAPHY_BODY1)
                .build()
        )
        .setPrimaryChipContent(
            Button.Builder(context, ModifiersBuilders.Clickable.Builder()
                .setId("launch_app")
                .setOnClick(
                    ActionBuilders.LaunchAction.Builder()
                        .setAndroidActivity(
                            ActionBuilders.AndroidActivity.Builder()
                                .setClassName(MainActivity::class.java.name)
                                .setPackageName(context.packageName)
                                .build()
                        )
                        .build()
                )
                .build())
                .setTextContent(context.getString(R.string.record_audio))
                .build()
        )
        .build()

    return TileBuilders.Tile.Builder()
        .setResourcesVersion(RESOURCES_VERSION)
        .setTileTimeline(
            TimelineBuilders.Timeline.fromLayoutElement(layout)
        )
        .build()
}

@Preview(device = WearDevices.SMALL_ROUND)
@Preview(device = WearDevices.LARGE_ROUND)
fun tilePreview(context: Context) = TilePreviewData(::resources) {
    tile(it, context)
}
