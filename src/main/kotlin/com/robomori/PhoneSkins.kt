package com.robomori

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File
import kotlin.math.abs

/**
 * A phone's real frame from the Android SDK's `skins` folder, in portrait: [image] is [width] by [height] pixels and
 * the screen sits at [screenX], [screenY], [screenWidth] by [screenHeight] pixels inside it.
 */
data class PhoneSkin(
    val image: File, val width: Int, val height: Int,
    val screenX: Int, val screenY: Int, val screenWidth: Int, val screenHeight: Int,
) {
    /** Whether this skin's screen has the shape of a [width] by [height] portrait screen, so the picture fits it. */
    fun fits(width: Int, height: Int): Boolean =
        abs(screenWidth.toFloat() / screenHeight - width.toFloat() / height) < .02f
}

/** The skin folder a model such as `Pixel_2` or `Pixel 3a XL` has in the SDK's `skins` folder, such as `pixel_3a_xl`. */
internal fun skinName(model: String): String = model.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

/** The real frame for [model] in [sdk]'s skins, or null when the SDK has none for it. */
fun phoneSkin(sdk: File, model: String): PhoneSkin? {
    val directory = File(sdk, "skins/${skinName(model)}")
    val layout = File(directory, "layout").takeIf(File::isFile)?.readText() ?: return null
    return parsePhoneSkin(layout)?.let { it.copy(image = File(directory, it.image.name)) }?.takeIf { it.image.isFile }
}

/** Reads the portrait frame and screen position from an emulator skin's `layout` file. */
internal fun parsePhoneSkin(layout: String): PhoneSkin? = runCatching {
    val root = parseSkinLayout(layout)
    val parts = root.section("parts")
    val display = parts.section("device").section("display")
    val background = parts.section("portrait").section("background").value("image")
    val portrait = root.section("layouts").section("portrait")
    val device = portrait.sections().first { it.value("name") == "device" }
    PhoneSkin(
        image = File(background), width = portrait.value("width").toInt(), height = portrait.value("height").toInt(),
        screenX = device.value("x").toInt(), screenY = device.value("y").toInt(),
        screenWidth = display.value("width").toInt(), screenHeight = display.value("height").toInt(),
    )
}.getOrNull()

/** A section of a skin layout: `key value` lines and nested `name { … }` sections. */
private class SkinSection(val values: Map<String, String>, val children: List<Pair<String, SkinSection>>) {
    fun section(name: String) = children.first { it.first == name }.second
    fun sections() = children.map { it.second }
    fun value(name: String) = values.getValue(name)
}

private fun parseSkinLayout(text: String): SkinSection {
    val tokens = Regex("""[{}]|[^\s{}]+""").findAll(text).map { it.value }.toList()
    var index = 0
    fun parse(): SkinSection {
        val values = mutableMapOf<String, String>()
        val children = mutableListOf<Pair<String, SkinSection>>()
        while (index < tokens.size && tokens[index] != "}") {
            val key = tokens[index++]
            if (tokens.getOrNull(index) == "{") {
                index++
                children += key to parse()
                index++ // closing brace
            } else values[key] = tokens.getOrElse(index++) { "" }
        }
        return SkinSection(values, children)
    }
    return parse()
}

/** A [skin] with its frame image decoded, ready to draw. */
class PhoneSkinArt(val skin: PhoneSkin, val image: ImageBitmap)

/** Loads the real frame for [model], decoding its image; null when there is none. Reads files, so call it off the UI thread. */
fun loadPhoneSkinArt(sdk: File, model: String): PhoneSkinArt? = phoneSkin(sdk, model)?.let { skin ->
    runCatching { PhoneSkinArt(skin, org.jetbrains.skia.Image.makeFromEncoded(skin.image.readBytes()).toComposeImageBitmap()) }.getOrNull()
}
