# R8 shrinking is enabled for release (isMinifyEnabled/isShrinkResources = true).
#
# No custom keep rules are needed at this time:
# - Firestore: FocusPolicyClient.kt reads/writes documents via manual
#   DocumentSnapshot.get("field")/hashMap access, not .toObject(<DataClass>).
#   There are no Firestore POJOs relying on reflection, so no field-name
#   keep rules are required. If a future change introduces .toObject(),
#   add -keep rules for that data class then.
# - androidx.credentials / credentials-play-services-auth / googleid: these
#   AARs ship their own consumer-rules.pro, applied automatically by AGP.
#   assembleRelease succeeded with R8 enabled and no extra rules, confirming
#   nothing here needs hand-written keeps.
# - org.json:json is testImplementation only; not present in the release
#   classpath, so no rule needed.
