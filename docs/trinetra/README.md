# Trinetra integration notes

The VX repository is now the Android Studio implementation target for Trinetra. The existing ARCore/OpenGL pipeline is preserved and the adaptive Hindi-first safety layer is integrated into the existing package `com.example.vx`.

The current code deliberately treats missing depth or weak tracking as caution rather than a clear path. The object-search and Storyteller model assets remain subject to model license review and real-device benchmarking before they should be bundled.
