ME2 local video gallery (drop-in)

Convention
----------
Bundled (APK):  assets/videos/{mood}/*.mp4|webm
Runtime (device): <app filesDir>/gallery/{mood}/*.mp4|webm

Moods (folder names)
--------------------
loop_neutral   — idle rotation (no spoken voice; ambient/onomatopoeia OK)
presentacion   — welcome only (spoken voice allowed here)
calida
alegre
atenta
aliviada
agradecida

Priority when resolving a mood
------------------------------
1) filesDir/gallery/{mood}/
2) assets/videos/{mood}/
3) res/raw demo clips (bundled fallbacks already in the APK)

How to add videos later
-----------------------
A) Bundle in the next APK: copy files into the matching mood folder under assets/videos/, rebuild.
B) Drop-in without rebuild: push/copy into the app's filesDir/gallery/{mood}/ (adb, future gallery UI, or sync).

ClipCatalog / GalleryRepository list these automatically; MainActivity avatar playback uses the same API.
Gallery UI can be added later without rewriting ExoPlayer wiring.

Audio product rule
------------------
Voice only on welcome/presentacion. Elsewhere: ambient / onomatopoeia.
App must keep working offline with local fallbacks; only the LLM needs network.
