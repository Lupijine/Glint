# youtubedl-android 0.17.3 and Jackson 2.11.1 ship without consumer keep rules.
# Jackson infers unannotated JSON properties from names and uses generic collection signatures.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep class com.yausername.youtubedl_android.mapper.** { *; }

# Preserve this older Jackson runtime, including its reflective constructors and helpers.
# The app and Compose UI remain eligible for R8 optimization.
-keep class com.fasterxml.jackson.** { *; }

# Optional desktop JavaBeans support is loaded reflectively with a guarded fallback.
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient
# DOM serialization is unused: the downloader only reads JSON into its mapper models.
-dontwarn org.w3c.dom.bootstrap.DOMImplementationRegistry

# The downloader unpacks Python and ffmpeg with commons-compress, which creates zip extra-field
# classes reflectively (Class.newInstance) in a static initializer. Without their constructors the
# first launch fails with ExceptionInInitializerError. The library's own rules keep both packages,
# but it does not ship them as consumer rules.
-keep class com.yausername.** { *; }
-keep class org.apache.commons.compress.archivers.zip.** { *; }

# Keep real class and method names, with line numbers, so crash reports can be read directly.
# Code is still shrunk and optimized; only renaming is skipped.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
