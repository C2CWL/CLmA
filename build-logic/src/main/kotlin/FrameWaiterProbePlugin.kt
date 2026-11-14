import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/** Diagnostic branch only. Transforms library bytecode, not a wrapper around AndroidUiFrameClock. */
class FrameWaiterProbePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.withPlugin("com.android.application") {
            target.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
                .onVariants { variant ->
                    if (variant.buildType == "clma") {
                        variant.instrumentation.transformClassesWith(
                            FrameWaiterVisitorFactory::class.java, InstrumentationScope.ALL,
                        ) {}
                        variant.instrumentation.setAsmFramesComputationMode(
                            FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS,
                        )
                    }
                }
        }
    }
}

abstract class FrameWaiterVisitorFactory : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun isInstrumentable(classData: ClassData): Boolean =
        FrameWaiterVisitor.accepts(classData.className.replace('.', '/'))

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        FrameWaiterVisitor(nextClassVisitor)
}

/** Only inserts no-throw static observations. Original callback/continuation operands are untouched. */
class FrameWaiterVisitor(next: ClassVisitor) : ClassVisitor(Opcodes.ASM9, next) {
    companion object {
        const val CLOCK = "androidx/compose/runtime/BroadcastFrameClock"
        const val HOOK = "com/clma/app/diagnostics/FrameWaiterProbe"
        const val WAIT = "(Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"
        fun accepts(name: String): Boolean = name == CLOCK ||
            name.startsWith("androidx/compose/animation/core/") ||
            name.startsWith("dev/chrisbanes/haze/HazeSourceNode") ||
            name.startsWith("com/clma/app/ui/")
        fun category(name: String): String = when {
            "InfiniteTransition" in name -> "infinite_transition"
            "SuspendAnimation" in name -> "animation_frame"
            "InfiniteAnimationPolicy" in name -> "infinite_policy"
            "MeshGradientBackground" in name -> "gradient_clock"
            "HazeSourceNode" in name -> "haze_pre_draw_site"
            "DrawerDiagnostics" in name -> "drawer_probe"
            "ChatPage" in name -> "chat_page"
            else -> "app_frame_site"
        }
    }
    private var owner = ""
    private var matchedEntries = 0
    override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
        owner = name
        super.visit(version, access, name, signature, superName, interfaces)
    }
    override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
        val downstream = super.visitMethod(access, name, descriptor, signature, exceptions)
        val entry = owner == CLOCK && name == "withFrameNanos"
        if (entry) {
            check(descriptor == WAIT && access and Opcodes.ACC_STATIC == 0) { "Unsupported BroadcastFrameClock ABI: $descriptor" }
            matchedEntries++
        }
        return object : MethodVisitor(Opcodes.ASM9, downstream) {
            private var sourceLine = -1
            override fun visitLineNumber(line: Int, start: Label) {
                sourceLine = line
                super.visitLineNumber(line, start)
            }
            override fun visitCode() {
                super.visitCode()
                if (entry) {
                    super.visitVarInsn(Opcodes.ALOAD, 1)
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "onAwait", "(Ljava/lang/Object;)V", false)
                }
            }
            override fun visitMethodInsn(opcode: Int, callOwner: String, callName: String, desc: String, isInterface: Boolean) {
                // The final two category-1 operands are (Function1, Continuation), both preserved.
                // Do not tag generic InfiniteAnimationPolicy adapters: preserve the original producer tag.
                val site = owner != CLOCK && "InfiniteAnimationPolicy" !in owner && desc == WAIT &&
                    ((callName == "withFrameNanos" && callOwner in setOf(
                        "androidx/compose/runtime/MonotonicFrameClock", "androidx/compose/runtime/MonotonicFrameClockKt",
                    )) || (callName == "withInfiniteAnimationFrameNanos" &&
                        callOwner == "androidx/compose/animation/core/InfiniteAnimationPolicyKt"))
                if (site) {
                    super.visitInsn(Opcodes.DUP2)
                    super.visitInsn(Opcodes.POP)
                    super.visitLdcInsn("framewaiter-origin:v1:${owner.replace('/', '.')}#$name:$sourceLine")
                    super.visitLdcInsn(category(owner))
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "onRequestSite", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V", false)
                }
                super.visitMethodInsn(opcode, callOwner, callName, desc, isInterface)
            }
        }
    }
    override fun visitEnd() {
        if (owner == CLOCK) check(matchedEntries == 1) { "Expected one BroadcastFrameClock.withFrameNanos entry, got $matchedEntries" }
        super.visitEnd()
    }
}
