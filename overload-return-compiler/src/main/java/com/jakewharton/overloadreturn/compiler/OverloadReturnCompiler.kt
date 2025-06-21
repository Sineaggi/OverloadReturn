package com.jakewharton.overloadreturn.compiler

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.ACC_BRIDGE
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Opcodes.ACC_SYNTHETIC
import org.objectweb.asm.Opcodes.ALOAD
import org.objectweb.asm.Opcodes.DLOAD
import org.objectweb.asm.Opcodes.INVOKESTATIC
import org.objectweb.asm.Opcodes.INVOKEVIRTUAL
import org.objectweb.asm.Opcodes.LLOAD
import org.objectweb.asm.Opcodes.POP
import org.objectweb.asm.Type
import org.objectweb.asm.Type.VOID_TYPE
import java.io.IOException
import java.lang.classfile.ClassBuilder
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassModel
import java.lang.classfile.ClassTransform
import java.lang.classfile.CodeBuilder
import java.lang.classfile.MethodBuilder
import java.lang.classfile.MethodElement
import java.lang.classfile.Opcode
import java.lang.classfile.TypeKind
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute
import java.lang.constant.ClassDesc
import java.lang.constant.MethodTypeDesc
import java.lang.reflect.AccessFlag
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitResult.CONTINUE
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

class OverloadReturnCompiler @JvmOverloads constructor(
  val debug: Boolean = false
) {
  fun parse(bytes: ByteArray) : ClassInfo {
    val asm = parseAsm(bytes)
    val classFileApi = parseClassFileApi(bytes)

    if (classFileApi.overloads != asm.overloads) {
      throw IllegalStateException("ASM and ClassFile API parsing results differ: ${asm.overloads} != ${classFileApi.overloads}")
    }

    return classFileApi
  }

  internal fun parseAsm(bytes: ByteArray) : ClassInfo {
    val overloads = mutableListOf<ReturnOverload>()
    ClassReader(bytes).accept(ParsingClassVisitor(overloads, debug), 0)

    return ClassInfo(bytes, overloads)
  }

  internal fun parseClassFileApi(bytes: ByteArray): ClassInfo {
    val overloads = mutableListOf<ReturnOverload>()
    val cf: ClassFile = ClassFile.of()
    val classModel: ClassModel = cf.parse(bytes)
    classModel.forEach(ClassElementVisitor(overloads, classModel, debug))

    return ClassInfo(bytes, overloads)
  }

  @Throws(IOException::class)
  fun processDirectory(inputRoot: Path, outputRoot: Path) {
    Files.walkFileTree(inputRoot, object : SimpleFileVisitor<Path>() {
      override fun visitFile(inputPath: Path, attrs: BasicFileAttributes): FileVisitResult {
        val relativePath = inputRoot.relativize(inputPath)
        val outputPath = outputRoot.resolve(relativePath)
        outputPath.parent.createDirectories()

        if (inputPath.toString().endsWith(".class")) {
          val inputBytes = inputPath.readBytes()
          val outputBytes = parse(inputBytes).toBytes()
          outputPath.writeBytes(outputBytes)
        } else {
          inputPath.copyTo(outputPath)
        }
        return CONTINUE
      }
    })
  }
}

@Suppress("ArrayInDataClass")
data class ClassInfo(
  val originalBytes: ByteArray,
  val overloads: List<ReturnOverload>
) {
  fun toBytes(): ByteArray {
    if (overloads.isEmpty()) {
      return originalBytes
    }

    return toBytesClassFileApi()
  }

  internal fun toBytesAsm(): ByteArray {
    val writer = ClassWriter(null, 0)
    val filteringVisitor = FilteringClassVisitor(writer)
    ClassReader(originalBytes).accept(filteringVisitor, 0)

    overloads.forEach { target ->
      val argumentTypes = Type.getArgumentTypes(target.descriptor)
      val returnType = Type.getType(target.returnOverload)
      val newDescriptor = Type.getMethodDescriptor(returnType, *argumentTypes)

      writer.visitMethod(target.access.withFlags(ACC_BRIDGE, ACC_SYNTHETIC), target.name,
          newDescriptor, target.signature, target.exceptions.takeIf { it.isNotEmpty() }?.toTypedArray()).apply {
        visitCode()

        var localIndex = 0

        if (ACC_STATIC isNotFlagIn target.access) {
          visitVarInsn(ALOAD, localIndex++)
        }
        for (argumentType in argumentTypes) {
          val instruction = argumentType.toVarInstruction()
          visitVarInsn(instruction, localIndex)

          localIndex += when (instruction) {
            DLOAD, LLOAD -> 2
            else -> 1
          }
        }

        val invoke = if (ACC_STATIC isFlagIn target.access) INVOKESTATIC else INVOKEVIRTUAL
        visitMethodInsn(invoke, target.owner, target.name, target.descriptor, false)

        if (returnType == VOID_TYPE) {
          visitInsn(POP)
        }
        visitInsn(returnType.toReturnInstruction())

        // Since void is never the return type of the annotated target method, we always need at
        // least a stack size of 1.
        val stackSize = maxOf(1, localIndex)
        visitMaxs(stackSize, localIndex)

        visitEnd()
      }
    }

    return writer.toByteArray()
  }

  internal fun toBytesClassFileApi(): ByteArray {
    val cf = ClassFile.of()
    val classModel = cf.parse(originalBytes)

    val annotationRemover = ClassTransform.transformingMethods { methodBuilder: MethodBuilder, methodElement: MethodElement ->
      if (methodElement is RuntimeInvisibleAnnotationsAttribute) {
        val annotations = methodElement.annotations().filter { !it.classSymbol().equals(classDesc) }
        methodBuilder.accept(RuntimeInvisibleAnnotationsAttribute.of(annotations))
      } else {
        methodBuilder.accept(methodElement)
      }
    }

    val overloadAdder = ClassTransform.endHandler { builder: ClassBuilder ->
      overloads.forEach { target ->
        val descriptor = MethodTypeDesc.ofDescriptor(target.descriptor)
        val argumentTypes = descriptor.parameterList()
        val returnType = TypeKind.from(ClassDesc.ofDescriptor(target.returnOverload))

        builder.withMethodBody(
          target.name,
          MethodTypeDesc.of(ClassDesc.ofDescriptor(target.returnOverload), argumentTypes),
          target.access.withFlags(AccessFlag.BRIDGE.mask(), AccessFlag.SYNTHETIC.mask()),
        ) { builder: CodeBuilder ->
          var localIndex = 0

          if (AccessFlag.STATIC.mask() isNotFlagIn target.access) {
            builder.aload(localIndex++)
          }
          for (argumentType in descriptor.parameterList()) {
            val instruction = TypeKind.from(argumentType)
            builder.loadLocal(instruction, localIndex)

            localIndex += when (instruction) {
              TypeKind.DOUBLE, TypeKind.LONG -> 2
              else -> 1
            }
          }

          val invoke =
            if (AccessFlag.STATIC.mask() isFlagIn target.access) Opcode.INVOKESTATIC else Opcode.INVOKEVIRTUAL

          builder.invoke(
              invoke,
              ClassDesc.ofInternalName(target.owner),
              target.name,
              descriptor,
              false
            )

          if (returnType == TypeKind.VOID) {
            builder.pop()
          }
          builder.return_(returnType)
        }
      }
    }

    return cf.transformClass(classModel, annotationRemover.andThen(overloadAdder) )
  }
}

data class ReturnOverload(
  val owner: String,
  val access: Int,
  val name: String,
  val descriptor: String,
  val signature: String?,
  val exceptions: List<String>,
  val returnOverload: String
)
