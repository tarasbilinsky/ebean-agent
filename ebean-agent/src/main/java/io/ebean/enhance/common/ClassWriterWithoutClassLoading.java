package io.ebean.enhance.common;

import io.ebean.enhance.asm.ClassReader;
import io.ebean.enhance.asm.ClassVisitor;
import io.ebean.enhance.asm.ClassWriter;
import io.ebean.enhance.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;

import java.util.*;

import static io.ebean.enhance.Transformer.EBEAN_ASM_VERSION;

/**
 * ClassWriter without class loading. Fixes problems on dynamic enhancement mentioned here:
 * <a href="https://github.com/ebean-orm/ebean-agent/issues/59">https://github.com/ebean-orm/ebean-agent/issues/59</a>
 *
 * Idea taken from here:
 *
 * <a href="https://github.com/zygote1984/AspectualAdapters/blob/master/ALIA4J-NOIRIn-all/src/org/alia4j/noirin/transform/ClassWriterWithoutClassLoading.java">https://github.com/zygote1984/AspectualAdapters/blob/master/ALIA4J-NOIRIn-all/src/org/alia4j/noirin/transform/ClassWriterWithoutClassLoading.java</a>
 *
 * @author praml
 */
public class ClassWriterWithoutClassLoading extends ClassWriter {

  private final Map<String, Set<String>> type2instanceOfs = new HashMap<>();
  private final Map<String, String> type2superclass = new HashMap<>();
  private final Map<String, Boolean> type2isInterface = new HashMap<>();
  private final ClassLoader classLoader;
  private final List<CommonSuperUnresolved> unresolved = new ArrayList<>();

  public ClassWriterWithoutClassLoading(ClassReader classReader, int flags, ClassLoader classLoader) {
    super(classReader, flags);
    this.classLoader = classLoader;
  }

  public ClassWriterWithoutClassLoading(int flags, ClassLoader classLoader) {
    super(flags);
    this.classLoader = classLoader;
  }

  public List<CommonSuperUnresolved> getUnresolved() {
    return unresolved;
  }


  protected String getCommonSuperClass(String type1, String type2)
    {
        try {
            // First put all super classes of type1, including type1 (starting with type2 is equivalent)
            Set<String> superTypes1 = new HashSet<String>();
            String s = type1;
            superTypes1.add(s);
            while (!"java/lang/Object".equals(s)) {
                s = getSuperType(s);
                superTypes1.add(s);
            }
            // Then check type2 and each of it's super classes in sequence if it is in the set
            // First match is the common superclass.
            s = type2;
            while (true) {
                if (superTypes1.contains(s)) return s;
                s = getSuperType(s);
            }
        } catch (Exception e) {
            throw new RuntimeException(e.toString());
        }
    }

    private String getSuperType(String type) throws ClassNotFoundException
    {
      play.classloading.ApplicationClasses.ApplicationClass ac = play.Play.classes.getApplicationClass(type.replace('/', '.'));
        try {
            return ac != null ? new ClassReader(ac.enhancedByteCode).getSuperName() : new ClassReader(type).getSuperName();
        } catch (IOException e) {
            throw new ClassNotFoundException(type);
        }
    }


  private Set<String> getInstanceOfs(String type) {
    if (!type2instanceOfs.containsKey(type)) {
      initializeTypeHierarchyFor(type);
    }
    return type2instanceOfs.get(type);
  }

  /**
  * Here we read the class at bytecode-level.
  */
  private void initializeTypeHierarchyFor(final String internalTypeName) {
    if (classLoader == null) {
      // Bug in Zulu JDK for jdk classes (which we should skip anyway)
      throw new IllegalStateException("ClassLoader is null?");
    }
    try (InputStream classBytes = classLoader.getResourceAsStream(internalTypeName + ".class")){
      ClassReader classReader = new ClassReader(classBytes);
      classReader.accept(new ClassVisitor(EBEAN_ASM_VERSION) {

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
          super.visit(version, access, name, signature, superName, interfaces);
          type2superclass.put(internalTypeName, superName);
          type2isInterface.put(internalTypeName, (access & Opcodes.ACC_INTERFACE) > 0);

          Set<String> instanceOfs = new HashSet<>();
          instanceOfs.add(internalTypeName); // we are instance of ourself
          if (superName != null) {
            instanceOfs.add(superName);
            instanceOfs.addAll(getInstanceOfs(superName));
          }
          for (String superInterface : interfaces) {
            instanceOfs.add(superInterface);
            instanceOfs.addAll(getInstanceOfs(superInterface));
          }
          type2instanceOfs.put(internalTypeName, instanceOfs);
        }
      }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    } catch (IOException e) {
      throw new IllegalArgumentException(e);
    }
  }

}
