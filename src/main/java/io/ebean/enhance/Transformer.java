package io.ebean.enhance;

import io.ebean.enhance.asm.ClassReader;
import io.ebean.enhance.asm.ClassWriter;
import io.ebean.enhance.common.AlreadyEnhancedException;
import io.ebean.enhance.common.ClassBytesReader;
import io.ebean.enhance.common.CommonSuperUnresolved;
import io.ebean.enhance.common.DetectEnhancement;
import io.ebean.enhance.common.EnhanceContext;
import io.ebean.enhance.common.NoEnhancementRequiredException;
import io.ebean.enhance.common.TransformRequest;
import io.ebean.enhance.common.UrlPathHelper;
import io.ebean.enhance.entity.ClassAdapterEntity;
import io.ebean.enhance.entity.ClassPathClassBytesReader;
import io.ebean.enhance.entity.MessageOutput;
import io.ebean.enhance.querybean.TypeQueryClassAdapter;
import io.ebean.enhance.transactional.ClassAdapterTransactional;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A Class file Transformer that enhances entity beans.
 * <p>
 * This is used as both a javaagent or via an ANT task (or other off line
 * approach).
 * </p>
 */
public class Transformer implements ClassFileTransformer {

  public static void premain(String agentArgs, Instrumentation inst) {

    Transformer transformer = new Transformer(null, agentArgs);
    inst.addTransformer(transformer);
  }

  public static void agentmain(String agentArgs, Instrumentation inst) throws Exception {
    premain(agentArgs, inst);
  }

  private final EnhanceContext enhanceContext;

  private final List<CommonSuperUnresolved> unresolved = new ArrayList<>();

  private boolean keepUnresolved;

  public Transformer(ClassLoader classLoader, String agentArgs) {
    if (classLoader == null) {
      classLoader = getClass().getClassLoader();
    }
    ClassBytesReader reader = new ClassPathClassBytesReader(null);
    this.enhanceContext = new EnhanceContext(reader, classLoader, agentArgs, null);
  }

  /**
   * Create a transformer for entity bean enhancement and transactional method enhancement.
   *
   * @param bytesReader reads resources from class path for related inheritance and interfaces
   * @param agentArgs command line arguments for debug level etc
   * @param packages limit enhancement to specified packages
   */
  public Transformer(ClassBytesReader bytesReader, ClassLoader classLoader, String agentArgs, Set<String> packages) {
    this.enhanceContext = new EnhanceContext(bytesReader, classLoader, agentArgs, packages);
  }

  /**
   * Set this to keep and report unresolved explicitly.
   */
  public void setKeepUnresolved() {
    this.keepUnresolved = true;
  }

  /**
   * Change the logout to something other than system out.
   */
  public void setLogout(MessageOutput logout) {
    this.enhanceContext.setLogout(logout);
  }

  public void log(int level, String msg) {
    log(level, null, msg);
  }

  private void log(int level, String className, String msg) {
    enhanceContext.log(level, className, msg);
  }

  public int getLogLevel() {
    return enhanceContext.getLogLevel();
  }

  public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {

    try {
      // ignore JDK and JDBC classes etc
      if (enhanceContext.isIgnoreClass(className)) {
        log(9, className, "ignore class");
        return null;
      }

      DetectEnhancement detect = detect(loader, classfileBuffer);

      TransformRequest request = new TransformRequest(classfileBuffer);

      if (detect.isEntity()) {
        if (detect.isEnhancedEntity()) {
          detect.log(3, "already enhanced entity");
        } else {
          entityEnhancement(loader, request);
          if (request.isEnhancedEntity()) {
            // we don't need perform subsequent transactional
            // or query bean enhancement so return early
            return request.getBytes();
          }
        }
      }

      if (detect.isTransactional()) {
        if (detect.isEnhancedTransactional()) {
          detect.log(3, "already enhanced transactional");
        } else {
          transactionalEnhancement(loader, request);
        }
      }

      enhanceQueryBean(loader, request);

      if (request.isEnhanced()) {
        return request.getBytes();
      }

      log(9, className, "no enhancement on class");
      return null;

    } catch (NoEnhancementRequiredException e) {
      // the class is an interface
      log(8, className, "No Enhancement required " + e.getMessage());
      return null;

    } catch (Exception e) {
      enhanceContext.log(e);
      return null;
    } finally {
      logUnresolvedCommonSuper(className);
    }
  }

  /**
   * Log and common superclass classpath issues that defaulted to Object.
   */
  private void logUnresolvedCommonSuper(String className) {
    if (!keepUnresolved && !unresolved.isEmpty()) {
      for (CommonSuperUnresolved commonUnresolved : unresolved) {
        log(0, className, commonUnresolved.getMessage());
      }
      unresolved.clear();
    }
  }

  /**
   * Return the list of unresolved common superclass issues. This should be cleared
   * after each use and can only be used with {@link #setKeepUnresolved()}.
   */
  public List<CommonSuperUnresolved> getUnresolved() {
    return unresolved;
  }

  /**
   * Perform entity bean enhancement.
   */
  private void entityEnhancement(ClassLoader loader, TransformRequest request) {

    ClassReader cr = new ClassReader(request.getBytes());
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS, loader);
    ClassAdapterEntity ca = new ClassAdapterEntity(cw, loader, enhanceContext);
    try {

      cr.accept(ca, 0);

      if (ca.isLog(1)) {
        ca.logEnhanced();
      }

      request.enhancedEntity(cw.toByteArray());

    } catch (AlreadyEnhancedException e) {
      if (ca.isLog(1)) {
        ca.log("already enhanced entity");
      }
      request.enhancedEntity(null);

    } catch (NoEnhancementRequiredException e) {
      if (ca.isLog(2)) {
        ca.log("skipping... no enhancement required");
      }
    } finally {
      unresolved.addAll(cw.getUnresolved());
    }
  }

  /**
   * Perform transactional enhancement.
   */
  private void transactionalEnhancement(ClassLoader loader, TransformRequest request) {

    ClassReader cr = new ClassReader(request.getBytes());
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES, loader);
    ClassAdapterTransactional ca = new ClassAdapterTransactional(cw, loader, enhanceContext);

    try {
      cr.accept(ca, 0);

      if (ca.isLog(1)) {
        ca.log("enhanced transactional");
      }

      request.enhancedTransactional(cw.toByteArray());

    } catch (AlreadyEnhancedException e) {
      if (ca.isLog(1)) {
        ca.log("already enhanced");
      }

    } catch (NoEnhancementRequiredException e) {
      if (ca.isLog(0)) {
        ca.log("skipping... no enhancement required");
      }
    } finally {
      unresolved.addAll(cw.getUnresolved());
    }
  }


  /**
   * Perform enhancement.
   */
  private void enhanceQueryBean(ClassLoader loader, TransformRequest request) {

    ClassReader cr = new ClassReader(request.getBytes());
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES, loader);
    TypeQueryClassAdapter ca = new TypeQueryClassAdapter(cw, enhanceContext);

    try {

      cr.accept(ca, 0);
      if (ca.isLog(9)) {
        ca.log("... completed");
      }
      request.enhancedQueryBean(cw.toByteArray());

    } catch (AlreadyEnhancedException e) {
      if (ca.isLog(1)) {
        ca.log("already enhanced");
      }

    } catch (NoEnhancementRequiredException e) {
      if (ca.isLog(9)) {
        ca.log("... skipping, no enhancement required");
      }
    } finally {
      unresolved.addAll(cw.getUnresolved());
    }
  }

  /**
   * Helper method to split semi-colon separated class paths into a URL array.
   */
  public static URL[] parseClassPaths(String extraClassPath) {

    if (extraClassPath == null) {
      return new URL[0];
    }

    return UrlPathHelper.convertToUrl(extraClassPath.split(";"));
  }

  /**
   * Read the bytes quickly trying to detect if it needs entity or transactional
   * enhancement.
   */
  private DetectEnhancement detect(ClassLoader classLoader, byte[] classfileBuffer) {

    DetectEnhancement detect = new DetectEnhancement(classLoader, enhanceContext);

    ClassReader cr = new ClassReader(classfileBuffer);
    cr.accept(detect, ClassReader.SKIP_CODE + ClassReader.SKIP_DEBUG + ClassReader.SKIP_FRAMES);
    return detect;
  }
}
