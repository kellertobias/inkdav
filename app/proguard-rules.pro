-keepattributes Signature,*Annotation*
-keep class de.tobisk.inkdav.data.** { *; }

# Optional JVM service/annotation types referenced by ical4j and ThreeTen Extra are not
# loaded on Android; their Android-compatible code paths use java.time directly.
-dontwarn java.time.zone.ZoneRulesProvider
-dontwarn org.joda.convert.ToString
