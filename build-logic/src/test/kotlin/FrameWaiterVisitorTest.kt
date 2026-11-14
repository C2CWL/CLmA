import com.clma.app.diagnostics.FrameWaiterProbe
import org.junit.Assert.*
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.coroutines.Continuation

class FrameWaiterVisitorTest {
    private fun fixture(name: String = FrameWaiterVisitor.CLOCK, methodDescriptor: String = FrameWaiterVisitor.WAIT, throwing: Boolean = false): ByteArray {
        val w = ClassWriter(ClassWriter.COMPUTE_MAXS)
        w.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode(); visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN); visitMaxs(0, 0); visitEnd()
        }
        w.visitMethod(Opcodes.ACC_PUBLIC, "withFrameNanos", methodDescriptor, null, null).apply {
            visitCode(); visitVarInsn(Opcodes.ALOAD, 1)
            if (throwing) { visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Throwable"); visitInsn(Opcodes.ATHROW) }
            else visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0); visitEnd()
        }
        w.visitEnd(); return w.toByteArray()
    }
    private fun transform(bytes: ByteArray): ByteArray {
        val w = ClassWriter(ClassWriter.COMPUTE_MAXS)
        ClassReader(bytes).accept(FrameWaiterVisitor(w), 0)
        return w.toByteArray()
    }
    private fun load(bytes: ByteArray): Class<*> = object : ClassLoader(javaClass.classLoader) {
        fun define(): Class<*> = defineClass(null, bytes, 0, bytes.size)
    }.define()
    @Test fun `actual descriptor gets one entry hook and original return value`() {
        FrameWaiterProbe.hits = 0
        val type = load(transform(fixture()))
        val callback: (Long) -> Any = { it }
        val value = type.getMethod("withFrameNanos", Function1::class.java, Continuation::class.java)
            .invoke(type.getConstructor().newInstance(), callback, null)
        assertSame(callback, value)
        assertSame(callback, FrameWaiterProbe.callback)
        assertEquals(1, FrameWaiterProbe.hits)
    }
    private class CallbackFailure : RuntimeException(), (Long) -> Any {
        override fun invoke(value: Long): Any = value
    }
    @Test fun `original exception identity is preserved`() {
        val type = load(transform(fixture(throwing = true)))
        val expected = CallbackFailure()
        try {
            type.getMethod("withFrameNanos", Function1::class.java, Continuation::class.java)
                .invoke(type.getConstructor().newInstance(), expected, null)
            fail("should throw")
        } catch (e: java.lang.reflect.InvocationTargetException) { assertSame(expected, e.cause) }
    }
    @Test fun `ABI mismatch fails instead of silently reporting zero`() {
        assertThrows(IllegalStateException::class.java) { transform(fixture(methodDescriptor = "(Ljava/lang/Object;)Ljava/lang/Object;")) }
    }
    @Test fun `direct registration call preserves operands in loaded bytecode`() {
        val owner = "com/clma/app/ui/ProbeFixture"
        val w = ClassWriter(ClassWriter.COMPUTE_MAXS)
        w.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null)
        w.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "register", "(Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", null, null).apply {
            visitCode(); visitVarInsn(Opcodes.ALOAD, 0); visitVarInsn(Opcodes.ALOAD, 1)
            visitMethodInsn(Opcodes.INVOKESTATIC, "androidx/compose/runtime/MonotonicFrameClockKt", "withFrameNanos", FrameWaiterVisitor.WAIT, false)
            visitInsn(Opcodes.ARETURN); visitMaxs(0, 0); visitEnd()
        }
        w.visitEnd()
        var siteHooks = 0
        ClassReader(transform(w.toByteArray())).accept(object : org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(a: Int, n: String, d: String, s: String?, e: Array<out String>?): org.objectweb.asm.MethodVisitor = object : org.objectweb.asm.MethodVisitor(Opcodes.ASM9) {
                override fun visitMethodInsn(op: Int, o: String, name: String, desc: String, iface: Boolean) {
                    if (o == FrameWaiterVisitor.HOOK && name == "onRequestSite") siteHooks++
                }
            }
        }, 0)
        assertEquals(1, siteHooks)
        FrameWaiterProbe.siteHits = 0
        val type = load(transform(w.toByteArray()))
        val callback: (Long) -> Any = { it }
        val result = type.getMethod("register", Function1::class.java, Continuation::class.java)
            .invoke(null, callback, null)
        assertSame(callback, result)
        assertSame(callback, FrameWaiterProbe.callback)
        assertEquals(1, FrameWaiterProbe.siteHits)
    }
}
