package com.jakewharton.overloadreturn.compiler

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.lang.classfile.Annotation
import java.lang.classfile.AnnotationValue
import java.lang.classfile.ClassElement
import java.lang.classfile.ClassModel
import java.lang.classfile.MethodElement
import java.lang.classfile.MethodModel
import java.lang.classfile.attribute.ExceptionsAttribute
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute
import java.lang.classfile.attribute.SignatureAttribute
import java.lang.constant.ClassDesc
import java.util.function.Consumer

internal class ParsingClassVisitor(
  private val overloads: MutableList<ReturnOverload>,
  private val debug: Boolean = false
) : ClassVisitor(Opcodes.ASM7) {

  private lateinit var owner: String

  override fun visit(version: Int, access: Int, name: String, signature: String?,
      superName: String?, interfaces: Array<out String>?) {
    if (debug) {
      println("OWNER: $name")
    }
    owner = name
  }

  override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?,
      exceptions: Array<out String>?): MethodVisitor {
    if (debug) {
      println("visitMethod[access: $access, name: $name, descriptor: $descriptor, signature: $signature, exceptions: ${exceptions?.contentToString()}]")
    }
    return ParsingMethodVisitor(owner, access, name, descriptor, signature, exceptions, overloads,
        debug)
  }
}

internal class ParsingMethodVisitor(
    private val owner: String,
    private val access: Int,
    private val name: String,
    private val descriptor: String,
    private val signature: String?,
    private val exceptions: Array<out String>?,
    private val targets: MutableList<ReturnOverload>,
    private val debug: Boolean = false
) : MethodVisitor(Opcodes.ASM7) {
  override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
    if (debug) {
      println("visitAnnotation[descriptor: $descriptor, visible: $visible]")
    }
    if (descriptor == overloadReturnDescriptor) {
      return ParsingAnnotationVisitor(owner, access, name, this.descriptor, signature, exceptions,
          targets, debug)
    }
    return null
  }
}

internal class ParsingAnnotationVisitor(
    private val owner: String,
    private val access: Int,
    private val name: String,
    private val descriptor: String,
    private val signature: String?,
    private val exceptions: Array<out String>?,
    private val targets: MutableList<ReturnOverload>,
    private val debug: Boolean = false
) : AnnotationVisitor(Opcodes.ASM7) {
  override fun visit(name: String?, value: Any) {
    require(value is Type)
    if (debug) {
      println("XXX $name $value ${value::class.java}")
    }
    targets.add(ReturnOverload(owner, access, this.name, descriptor, signature, exceptions?.toList() ?: emptyList(), value.descriptor))
  }

  override fun visitArray(name: String): AnnotationVisitor {
    require(name == "value")
    return this
  }
}

internal class ClassElementVisitor(
  private val overloads: MutableList<ReturnOverload>,
  private val classModel: ClassModel,
  private val debug: Boolean = false
) : Consumer<ClassElement> {
  override fun accept(classElement: ClassElement) {
    if (debug) {
      println("visitClassElement[classElement: $classElement]")
    }
    val methodModelVisitor = MethodModelVisitor(overloads, classModel, debug)
    if (classElement is MethodModel) {
      methodModelVisitor.accept(classElement)
    }
  }
}

internal class MethodModelVisitor(
  private val overloads: MutableList<ReturnOverload>,
  private val classModel: ClassModel,
  private val debug: Boolean = false
) : Consumer<MethodModel> {
  override fun accept(methodModel: MethodModel) {
    val exceptions = methodModel.filterIsInstance<ExceptionsAttribute>().singleOrNull()?.exceptions()?.map { it.asInternalName() } ?: emptyList()
    val signature = methodModel.filterIsInstance<SignatureAttribute>().singleOrNull()?.signature()?.stringValue()
    val visitor = MethodElementVisitor(overloads, classModel, exceptions, signature, methodModel, debug)

    methodModel.forEach(visitor)
  }
}

internal val classDesc = ClassDesc.of("com.jakewharton.overloadreturn", "OverloadReturn")

internal class MethodElementVisitor(
  private val overloads: MutableList<ReturnOverload>,
  private val classModel: ClassModel,
  private val exceptions: List<String>,
  private val signature: String?,
  private val methodModel: MethodModel,
  private val debug: Boolean = false
) : Consumer<MethodElement> {
  override fun accept(methodElement: MethodElement) {
    if (debug) {
      println("visitMethodElement[methodElement: $methodElement]")
    }
    if (methodElement !is RuntimeInvisibleAnnotationsAttribute) {
      return
    }
    val visitor = AnnotationVisitor(overloads, classModel, exceptions, signature, methodModel, debug)
    methodElement.annotations().forEach(visitor)
  }
}

internal class AnnotationVisitor(
  private val overloads: MutableList<ReturnOverload>,
  private val classModel: ClassModel,
  private val exceptions: List<String>,
  private val signature: String?,
  private val methodModel: MethodModel,
  private val debug: Boolean = false
) : Consumer<Annotation> {
  override fun accept(annotation: Annotation) {
    if (debug) {
      println("visitAnnotation[annotation: $annotation]")
    }
    if (annotation.classSymbol() != classDesc) {
      return
    }
    val annotationValue = annotation.elements().single { it.name().equalsString("value") }
    val value = annotationValue.value()
    if (value !is AnnotationValue.OfArray) {
      return
    }
    val classes = value.values().filterIsInstance<AnnotationValue.OfClass>()
    classes.forEach { overloadClass ->
      val returnOverload = overloadClass.className().stringValue()

      overloads.add(
        ReturnOverload(
          owner = classModel.thisClass().name().stringValue(),
          access = methodModel.flags().flagsMask(),
          name = methodModel.methodName().stringValue(),
          descriptor = methodModel.methodType().stringValue(),
          signature = signature,
          exceptions = exceptions,
          returnOverload = returnOverload
        )
      )
    }
  }
}
