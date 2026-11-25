package com.robomori

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class PhoneSkinsTest {
    @TempDir lateinit var sdk: File

    // The portrait parts of the Android SDK's pixel_2 skin layout.
    private val pixel2 = """
        parts {
          device { display { width 1080 height 1920 x 0 y 0 } }
          portrait { background { image port_back.webp } onion { image port_fore.webp } }
          landscape { background { image land_back.webp } }
        }
        layouts {
          portrait { width 1370 height 2534 event EV_SW:0:1
            part1 { name portrait x 0 y 0 }
            part2 { name device x 140 y 280 }
          }
          landscape { width 2596 height 1258 part1 { name landscape x 0 y 0 } part2 { name device x 338 y 68 rotation 3 } }
        }
    """.trimIndent()

    @Test fun `a model finds its skin folder`() {
        assertEquals("pixel_2", skinName("Pixel_2"))
        assertEquals("pixel_3a_xl", skinName("Pixel 3a XL"))
    }

    @Test fun `the portrait frame and screen position are read from the layout`() {
        val skin = assertNotNull(parsePhoneSkin(pixel2))
        assertEquals(PhoneSkin(File("port_back.webp"), 1370, 2534, 140, 280, 1080, 1920), skin)
        assertTrue(skin.fits(1080, 1920))
        assertFalse(skin.fits(1080, 2400))
    }

    @Test fun `a phone without a skin in the SDK has no frame`() {
        assertNull(phoneSkin(sdk, "Pixel_2"))
        File(sdk, "skins/pixel_2").apply { mkdirs(); resolve("layout").writeText(pixel2); resolve("port_back.webp").writeText("") }
        assertEquals(File(sdk, "skins/pixel_2/port_back.webp"), phoneSkin(sdk, "Pixel_2")?.image)
        assertNull(parsePhoneSkin("not a layout"))
    }
}
