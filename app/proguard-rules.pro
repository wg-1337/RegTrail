# P0 keeps minification off. These rules are the minimum that will be needed
# once the LSPosed module part lands in P1 (see docs/PLAN.md 3.4).
#
# -adaptresourcefilecontents META-INF/xposed/java_init.list   # rewrite the entry-class list after R8 renames it
# -keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule { public <init>(); }

-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
