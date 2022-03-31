package io.ebean.enhance.entity;

import io.ebean.enhance.asm.Label;
import io.ebean.enhance.asm.MethodVisitor;
import io.ebean.enhance.asm.Opcodes;
import io.ebean.enhance.common.ClassMeta;

import java.util.ArrayList;
import java.util.List;

import static io.ebean.enhance.common.EnhanceConstants.INIT;
import static io.ebean.enhance.common.EnhanceConstants.NOARG_VOID;

/**
 * This is a class that 'defers' bytecode instructions in the default constructor initialisation
 * such that code that initialises persistent many properties (Lists, Sets and Maps) is removed.
 * <p>
 * The purpose is to consume unwanted initialisation of Lists, Sets and Maps for OneToMany
 * and ManyToMany properties.
 * </p>
 * <pre>
 *
 *  mv.visitVarInsn(ALOAD, 0);
 *  mv.visitInsn(ICONST_0);         // (A) Optionally generated by Kotlin
 *  mv.visitVarInsn(ISTORE, 1);     // (A)
 *  mv.visitTypeInsn(NEW, "java/util/ArrayList");
 *  mv.visitInsn(DUP);
 *  mv.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
 *  mv.visitTypeInsn(CHECKCAST, "java/util/List"); // (B) Optionally generated by Kotlin
 *  Label label = new Label();      // (B)
 *  mv.visitLabel(label);           // (B)
 *  mv.visitLineNumber(__, label4); // (B)
 *  mv.visitFieldInsn(PUTFIELD, "test/model/WithInitialisedCollections", "contacts", "Ljava/util/List;");
 *
 * </pre>
 */
final class ConstructorDeferredCode implements Opcodes {

  enum State {
    UNSET,
    ALOAD,
    KT_ICONST,       // optional kotlin state
    NEW_COLLECTION,
    DUP,
    INVOKE_SPECIAL,
    KT_CHECKCAST,   // optional kotlin state
    KT_LABEL        // optional kotlin state
  }

  private static final ALoad ALOAD_INSTRUCTION = new ALoad();
  private static final Dup DUP_INSTRUCTION = new Dup();
  private static final Iconst0 ICONST0_INSTRUCTION = new Iconst0();

  private final ClassMeta meta;
  private final MethodVisitor mv;
  private final List<DeferredCode> codes = new ArrayList<>();
  private State state = State.UNSET;

  ConstructorDeferredCode(ClassMeta meta, MethodVisitor mv) {
    this.meta = meta;
    this.mv = mv;
  }

  /**
   * Return true if this is an ALOAD 0 which we defer.
   */
  boolean deferVisitVarInsn(int opcode, int var) {
    if (state == State.KT_ICONST && opcode == ISTORE) {
      codes.add(new Istore(var));
      state = State.ALOAD;
      return true;
    }
    flush();
    if (opcode == ALOAD && var == 0) {
      codes.add(ALOAD_INSTRUCTION);
      state = State.ALOAD;
      return true;
    }
    return false;
  }

  /**
   * Return true if we defer this based on it being a NEW or CHECKCAST on persistent many
   * and was proceeded by a deferred ALOAD (for NEW) or Collection init (for CHECKCAST).
   */
  boolean deferVisitTypeInsn(int opcode, String type) {
    if (opcode == NEW && isCollection(type) && stateAload()) {
      codes.add(new NewCollection(type));
      state = State.NEW_COLLECTION;
      return true;
    }
    if (opcode == CHECKCAST && stateInvokeSpecial()) {
      codes.add(new CheckCastCollection(type));
      state = State.KT_CHECKCAST;
      return true;
    }
    flush();
    return false;
  }

  /**
   * Return true if we defer this based on it being a DUP and was proceeded
   * by a deferred ALOAD and NEW.
   */
  boolean deferVisitInsn(int opcode) {
    if (opcode == ICONST_0 && stateAload()) {
      codes.add(ICONST0_INSTRUCTION);
      state = State.KT_ICONST;
      return true;
    }
    if (opcode == DUP && stateNewCollection()) {
      codes.add(DUP_INSTRUCTION);
      state = State.DUP;
      return true;
    }
    flush();
    return false;
  }

  /**
   * Return true if we defer this based on it being an init of a collection
   * and was proceeded by a deferred DUP.
   */
  boolean deferVisitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
    if (opcode == INVOKESPECIAL && stateDup() && isCollectionInit(owner, name, desc)) {
      codes.add(new CollectionInit(opcode, owner, name, desc, itf));
      state = State.INVOKE_SPECIAL;
      return true;
    }
    flush();
    return false;
  }

  /**
   * Return true if this is an init of a ArrayList, HashSet, LinkedHashSet.
   */
  private boolean isCollectionInit(String owner, String name, String desc) {
    return name.equals(INIT) && desc.equals(NOARG_VOID) && isCollection(owner);
  }

  /**
   * Return true if we have consumed all the deferred code that initialises a persistent collection.
   */
  boolean consumeVisitFieldInsn(int opcode, String name) {
    if (opcode == PUTFIELD && stateConsumeDeferred() && meta.isConsumeInitMany(name)) {
      if (meta.isLog(3)) {
        meta.log("... consumed init of many: " + name);
      }
      state = State.UNSET;
      codes.clear();
      return true;
    }
    flush();
    return false;
  }

  boolean consumeVisitLabel(Label label) {
    if (state == State.KT_CHECKCAST) {
      codes.add(new DeferredLabel(label));
      state = State.KT_LABEL;
      return true;
    }
    return false;
  }

  public boolean consumeVisitLineNumber(int line, Label start) {
    if (state == State.KT_LABEL) {
      codes.add(new DeferredLineNumber(line, start));
      state = State.INVOKE_SPECIAL;
      return true;
    }
    return false;
  }

  /**
   * Flush all deferred instructions.
   */
  void flush() {
    state = State.UNSET;
    if (!codes.isEmpty()) {
      for (DeferredCode code : codes) {
        if (meta.isLog(4)) {
          meta.log("... flush deferred: " + code);
        }
        code.write(mv);
      }
      codes.clear();
    }
  }

  private boolean stateAload() {
    return state == State.ALOAD;
  }

  private boolean stateNewCollection() {
    return state == State.NEW_COLLECTION;
  }

  private boolean stateDup() {
    return state == State.DUP;
  }

  private boolean stateInvokeSpecial() {
    return state == State.INVOKE_SPECIAL;
  }

  private boolean stateConsumeDeferred() {
    return state == State.INVOKE_SPECIAL || state == State.KT_CHECKCAST;
  }

  /**
   * Return true if this is a collection type used to initialise persistent collections.
   */
  private boolean isCollection(String type) {
    return ("java/util/ArrayList".equals(type)
      || "java/util/LinkedHashSet".equals(type)
      || "java/util/HashSet".equals(type));
  }

  private static class ALoad implements DeferredCode {
    @Override
    public void write(MethodVisitor mv) {
      mv.visitVarInsn(ALOAD, 0);
    }
  }

  private static class DeferredLabel implements DeferredCode {
    private final Label label;
    DeferredLabel(Label label) {
      this.label = label;
    }

    @Override
    public void write(MethodVisitor mv) {
      mv.visitLabel(label);
    }
  }

  private static class DeferredLineNumber implements DeferredCode {
    private final int line;
    private final Label label;
    DeferredLineNumber(int line, Label label) {
      this.line = line;
      this.label = label;
    }

    @Override
    public void write(MethodVisitor mv) {
      mv.visitLineNumber(line, label);
    }
  }

  private static class Iconst0 implements DeferredCode {
    @Override
    public void write(MethodVisitor mv) {
      mv.visitInsn(ICONST_0);
    }
  }

  private static class Istore implements DeferredCode {
    private final int value;
    Istore(int value) {
      this.value = value;
    }

    @Override
    public void write(MethodVisitor mv) {
      mv.visitVarInsn(ISTORE, value);
    }
  }

  /**
   * DUP
   */
  private static class Dup implements DeferredCode {
    @Override
    public void write(MethodVisitor mv) {
      mv.visitInsn(DUP);
    }
  }

  /**
   * Typically NEW java/util/ArrayList
   */
  private static class NewCollection implements DeferredCode {
    final String type;
    NewCollection(String type) {
      this.type = type;
    }

    @Override
    public void write(MethodVisitor mv) {
      mv.visitTypeInsn(NEW, type);
    }
  }

  /**
   * Typically CHECKCAST java/util/List
   */
  private static class CheckCastCollection implements DeferredCode {
    final String type;
    CheckCastCollection(String type) {
      this.type = type;
    }

    @Override
    public void write(MethodVisitor mv) {
      mv.visitTypeInsn(CHECKCAST, type);
    }
  }

  /**
   * Typically INVOKESPECIAL java/util/ArrayList.<init> ()V
   */
  private static class CollectionInit implements DeferredCode {

    final int opcode;
    final String owner;
    final String name;
    final String desc;
    final boolean itf;

    CollectionInit(int opcode, String owner, String name, String desc, boolean itf) {
      this.opcode = opcode;
      this.owner = owner;
      this.name = name;
      this.desc = desc;
      this.itf = itf;
    }

    @Override
    public void write(MethodVisitor mv) {
      mv.visitMethodInsn(opcode, owner, name, desc, itf);
    }
  }
}