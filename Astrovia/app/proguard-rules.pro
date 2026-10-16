# Keep model classes used with networking/parsing
-keep class com.kroryan.astrovia.model.** { *; }

# Play requires at least 25% of the DEX code to be optimised, shrunk and obfuscated (it is ~97% now).
# Flatten packages and let R8 widen access so it can inline and merge more.
-repackageclasses ''
-allowaccessmodification
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
