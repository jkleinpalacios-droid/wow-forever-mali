package app.gamenative.utils

/**
 * SHA-256 pins for the runtime components this app downloads (they hold native code that runs inside the app).
 *
 * Keys are the file names passed to [Net.fetchFileWithFallback]. The values below were taken from the copies that
 * the build we tested had already downloaded, so they are a trust-on-first-use baseline, not a signature from the
 * component's author: they stop a file from silently changing afterwards (a compromised host, a swapped file), they
 * do not prove the original was clean. Add or update a pin only after checking the new file.
 */
object PinnedHashes {
    /** When true, a download with no pin is refused. Set to false only to experiment with other components. */
    const val REQUIRE_PIN = true

    private val sha256 = mapOf(
        "imagefs_bionic.txz" to "368db62bfc58b72c97e5169bda9aa64d4246f07964c447e27c79a065e7e9c48b",
        "container_files/extras.tzst" to "d47d2c0e12f4f59beb3e4a80ed9e7373a15fc7cc796d406fd6f18d0f58249184",
        "container_files/container_pattern_common_20260821.tzst" to
            "c62311ac7a10a149f33cd1b2fcdf9f79c8a6b35d8b46066b33108c003b7e85c6",
        "graphics_driver/extra_libs.tzst" to "5e42e95bc79f098f22822cdee25a21099e585536db01927216ea30a6eb7ca27f",
        "graphics_driver/wrapper-gamenative-20260724.tzst" to
            "1a546621cf285b50a6de47aeb0ada4a298d41ac24fc5ca2cdc46673859056d37",
    )

    fun forFile(fileName: String): String? = sha256[fileName]
}
