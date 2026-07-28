package com.anezium.rokidbus.phone

import android.content.Context
import com.anezium.rokidbus.shared.GlyphContract

class PluginGlyphReader(
    private val context: Context,
    private val logger: (String) -> Unit,
) {
    fun read(principal: PhonePluginPrincipal): List<GlyphContract.CustomGlyph> {
        val resourceId = principal.descriptor.glyphsResId ?: return emptyList()
        val entries = try {
            context.createPackageContext(principal.packageName, 0)
                .resources
                .getStringArray(resourceId)
                .toList()
        } catch (failure: Throwable) {
            logger(
                "plugin glyphs unavailable id=${principal.descriptor.id} " +
                    "error=${failure.javaClass.simpleName}: ${failure.message}",
            )
            return emptyList()
        }

        return when (val result = GlyphContract.parse(entries)) {
            is GlyphContract.ParseResult.Valid -> result.glyphs
            is GlyphContract.ParseResult.Invalid -> {
                logger("plugin glyphs invalid id=${principal.descriptor.id} reason=${result.reason}")
                emptyList()
            }
        }
    }
}
