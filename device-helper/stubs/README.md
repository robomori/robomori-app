Compile-only stand-ins for the Android classes the device helper calls. The helper runs against the device's own
framework, so these only need matching signatures; they're never packaged.

The unit tests compile the helper with these too, so `Rect` works like Android's: the screen logic they test measures
with it.
