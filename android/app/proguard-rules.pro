# R8 shrinking is enabled for release (isMinifyEnabled/isShrinkResources = true).
#
# AppCompat/Material resolve the theme's `viewInflaterClass` attribute by
# STRING NAME via Class.forName(). R8 full mode (AGP default) renames these
# classes, so the lookup throws ClassNotFoundException at runtime and the
# delegate silently falls back to a plain inflater — Material widget
# auto-inflation stops happening and M3 styling degrades. This does not fail
# the build; it was caught only by installing the release APK and reading
# logcat. Keep both names and their no-arg constructors.
-keep class androidx.appcompat.app.AppCompatViewInflater { <init>(); }
-keep class com.google.android.material.theme.MaterialComponentsViewInflater { <init>(); }

# Beyond the rules above, no further keeps are needed:
# - Firestore: FocusPolicyClient.kt reads/writes documents via manual
#   DocumentSnapshot.get("field")/hashMap access, not .toObject(<DataClass>).
#   There are no Firestore POJOs relying on reflection, so no field-name
#   keep rules are required. If a future change introduces .toObject(),
#   add -keep rules for that data class then.
# - androidx.credentials / credentials-play-services-auth / googleid: these
#   AARs ship their own consumer-rules.pro, applied automatically by AGP.
#   Verified by launching the release APK on-device and confirming no
#   ClassNotFound/NoSuchMethod entries for these packages in logcat — a
#   successful build alone does NOT prove this, as the inflater bug above
#   demonstrates.
# - org.json:json is testImplementation only; not present in the release
#   classpath, so no rule needed.
