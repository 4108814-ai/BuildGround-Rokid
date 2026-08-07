package com.anezium.rokidbus.phone

import java.net.URL
import org.json.JSONArray

internal enum class NexusReleaseArtifact(val assetPrefix: String) {
    PHONE("nexus-phone"),
    GLASSES("nexus-glasses"),
}

internal data class NexusReleaseAsset(
    val version: NexusSemVersion,
    val apkUrl: String,
    val sha256: String?,
    val notes: String? = null,
    val publishedAt: String? = null,
)

internal object NexusReleaseAssetResolver {
    @Throws(NexusUpdateParseException::class)
    fun parseLatest(body: String, artifact: NexusReleaseArtifact): NexusReleaseAsset? {
        val releases = try {
            JSONArray(body)
        } catch (failure: Exception) {
            throw NexusUpdateParseException("GitHub releases body is not a JSON array", failure)
        }
        val candidates = buildList {
            for (index in 0 until releases.length()) {
                val release = releases.optJSONObject(index) ?: continue
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
                val version = NexusSemVersion.fromAppTag(release.optString("tag_name")) ?: continue
                val expectedAssetName = "${artifact.assetPrefix}-$version.apk"
                val assets = release.optJSONArray("assets") ?: continue
                for (assetIndex in 0 until assets.length()) {
                    val asset = assets.optJSONObject(assetIndex) ?: continue
                    if (asset.optString("name") != expectedAssetName) continue
                    val apkUrl = asset.optString("browser_download_url")
                        .takeIf(::isHttpsUrl) ?: continue
                    add(
                        NexusReleaseAsset(
                            version = version,
                            apkUrl = apkUrl,
                            sha256 = asset.optString("digest")
                                .takeIf { it.matches(Regex("^sha256:[0-9a-fA-F]{64}$")) }
                                ?.substringAfter("sha256:")
                                ?.lowercase(),
                            notes = release.optString("body").takeIf(String::isNotBlank),
                            publishedAt = release.optString("published_at").takeIf(String::isNotBlank),
                        ),
                    )
                    break
                }
            }
        }
        return candidates.maxByOrNull(NexusReleaseAsset::version)
    }

    /** Every published app release with notes, newest first. Plugin and SDK tags never match. */
    @Throws(NexusUpdateParseException::class)
    fun parseAppHistory(body: String): List<NexusAppReleaseNote> {
        val releases = try {
            JSONArray(body)
        } catch (failure: Exception) {
            throw NexusUpdateParseException("GitHub releases body is not a JSON array", failure)
        }
        return buildList {
            for (index in 0 until releases.length()) {
                val release = releases.optJSONObject(index) ?: continue
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
                val version = NexusSemVersion.fromAppTag(release.optString("tag_name")) ?: continue
                val notes = release.optString("body").takeIf(String::isNotBlank) ?: continue
                add(
                    NexusAppReleaseNote(
                        version = version,
                        publishedAt = release.optString("published_at").takeIf(String::isNotBlank),
                        notes = notes,
                    ),
                )
            }
        }.sortedByDescending(NexusAppReleaseNote::version)
    }

    private fun isHttpsUrl(value: String): Boolean =
        runCatching { URL(value).protocol == "https" }.getOrDefault(false)
}
