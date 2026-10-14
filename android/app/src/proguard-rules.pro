
# The data layer is small and is read and written by name (JSON field names, enum values); keep it intact
# rather than trust shrinking with no device here to catch a mistake.
-keep class com.jashith.stride.core.** { *; }
-keepclassmembers class com.jashith.stride.core.** { *; }
