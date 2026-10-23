# kotlinx.serialization: keep generated serializers for the shared protocol classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class io.github.tendo505.tandem.core.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.tendo505.tandem.core.**$$serializer { *; }

# Retrofit: keep service interface methods and generic signatures.
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep interface io.github.tendo505.tandem.app.sync.TandemService { *; }
