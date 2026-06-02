# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the SDK tools.

-keepattributes Signature
-keepattributes *Annotation*

# Keep accessibility service
-keep class com.bebig.magnify.service.MagnifyAccessibilityService { *; }

# Keep PreferenceHelper (used via reflection in some cases)
-keep class com.bebig.magnify.util.PreferenceHelper { *; }
