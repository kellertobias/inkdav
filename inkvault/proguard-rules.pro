# Markwon detects this optional codec reflectively. InkVault does not enable GIF playback.
-dontwarn pl.droidsonroids.gif.**

# jump3r's Android encoder does not use the desktop Java Sound input API.
-dontwarn javax.sound.sampled.AudioFormat
-dontwarn javax.sound.sampled.AudioFormat$Encoding

# Optional adapters referenced by BOOX's fastjson and SLF4J dependencies are not used by InkVault.
-dontwarn org.joda.convert.FromString
-dontwarn org.joda.convert.ToString
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.slf4j.impl.StaticMDCBinder
-dontwarn org.slf4j.impl.StaticMarkerBinder
