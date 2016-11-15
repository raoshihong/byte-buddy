package net.bytebuddy.asm;

import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.method.ParameterDescription;
import net.bytebuddy.description.method.ParameterList;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.description.type.TypeList;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.scaffold.FieldLocator;
import net.bytebuddy.dynamic.scaffold.InstrumentedType;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.SuperMethodCall;
import net.bytebuddy.implementation.bytecode.*;
import net.bytebuddy.implementation.bytecode.constant.*;
import net.bytebuddy.implementation.bytecode.member.FieldAccess;
import net.bytebuddy.implementation.bytecode.member.MethodVariableAccess;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.CompoundList;
import net.bytebuddy.utility.JavaType;
import net.bytebuddy.utility.visitor.ExceptionTableSensitiveMethodVisitor;
import net.bytebuddy.utility.visitor.LineNumberPrependingMethodVisitor;
import net.bytebuddy.utility.visitor.StackAwareMethodVisitor;
import org.objectweb.asm.*;

import java.io.IOException;
import java.io.Serializable;
import java.lang.annotation.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

import static net.bytebuddy.matcher.ElementMatchers.named;

/**
 * <p>
 * Advice wrappers copy the code of blueprint methods to be executed before and/or after a matched method. To achieve this, a {@code static}
 * method of a class is annotated by {@link OnMethodEnter} and/or {@link OnMethodExit} and provided to an instance of this class.
 * </p>
 * <p>
 * A method that is annotated with {@link OnMethodEnter} can annotate its parameters with {@link Argument} where field access to this parameter
 * is substituted with access to the specified argument of the instrumented method. Alternatively, a parameter can be annotated by {@link This}
 * where the {@code this} reference of the instrumented method is read when the parameter is accessed. This mechanism can also be used to assign a
 * new value to the {@code this} reference of an instrumented method. If no annotation is used on a parameter, it is assigned the {@code n}-th
 * parameter of the instrumented method for the {@code n}-th parameter of the advice method. All parameters must declare the exact same type as
 * the parameters of the instrumented type or the method's declaring type for the {@link This} reference respectively if they are not marked as
 * <i>read-only</i>. In the latter case, it suffices that a parameter type is a super type of the corresponding type of the instrumented method.
 * </p>
 * <p>
 * A method that is annotated with {@link OnMethodExit} can equally annotate its parameters with {@link Argument} and {@link This}. Additionally,
 * it can annotate a parameter with {@link Return} to receive the original method's return value. By reassigning the return value, it is possible
 * to replace the returned value. If an instrumented method does not return a value, this annotation must not be used. If a method returns
 * exceptionally, the parameter is set to its default value, i.e. to {@code 0} for primitive types and to {@code null} for reference types. The
 * parameter's type must be equal to the instrumented method's return type if it is not set to <i>read-only</i> where it suffices to declare the
 * parameter type to be of any super type to the instrumented method's return type. An exception can be read by annotating a parameter of type
 * {@link Throwable} annotated with {@link Thrown} which is assigned the thrown {@link Throwable} or {@code null} if a method returns normally.
 * Doing so, it is possible to exchange a thrown exception with any checked or unchecked exception.Finally, if a method annotated with
 * {@link OnMethodEnter} exists and this method returns a value, this value can be accessed by a parameter annotated with {@link Enter}.
 * This parameter must declare the same type as type being returned by the method annotated with {@link OnMethodEnter}. If the parameter is marked
 * to be <i>read-only</i>, it suffices that the annotated parameter is of a super type of the return type of the method annotated by
 * {@link OnMethodEnter}. If no such method exists or this method returns {@code void}, no such parameter must be declared. Any return value
 * of a method that is annotated by {@link OnMethodExit} is discarded.
 * </p>
 * <p>
 * If any advice method throws an exception, the method is terminated prematurely. If the method annotated by {@link OnMethodEnter} throws an exception,
 * the method annotated by {@link OnMethodExit} method is not invoked. If the instrumented method throws an exception, the method that is annotated by
 * {@link OnMethodExit} is only invoked if the {@link OnMethodExit#onThrowable()} property is set to {@code true} what is the default. If this property
 * is set to {@code false}, the {@link Thrown} annotation must not be used on any parameter.
 * </p>
 * <p>
 * Byte Buddy does not assert the visibility of any types that are referenced within an inlined advice method. It is the responsibility of
 * the user of this class to assure that all types referenced within the advice methods are visible to the instrumented class. Failing to
 * do so results in a {@link IllegalAccessError} at the instrumented class's runtime.
 * </p>
 * <p>
 * Advice can be used either as a {@link AsmVisitorWrapper} where any declared methods of the currently instrumented type are enhanced without
 * replacing an existing implementation. Alternatively, advice can function as an {@link Implementation} where, by default, the original super
 * or default method of the instrumented method is invoked. If this is not possible or undesired, the delegate implementation can be changed
 * by specifying a wrapped implementation explicitly by {@link Advice#wrap(Implementation)}.
 * </p>
 * <p>
 * When using an advice class as a visitor wrapper, native or abstract methods which are silently skipped when advice matches such a method.
 * </p>
 * <p>
 * <b>Important</b>: Since Java 6, class files contain <i>stack map frames</i> embedded into a method's byte code. When advice methods are compiled
 * with a class file version less then Java 6 but are used for a class file that was compiled to Java 6 or newer, these stack map frames must be
 * computed by ASM by using the {@link ClassWriter#COMPUTE_FRAMES} option. If the advice methods do not contain any branching instructions, this is
 * not required. No action is required if the advice methods are at least compiled with Java 6 but are used on classes older than Java 6. This
 * limitation only applies to advice methods that are inlined. Also, it is the responsibility of this class's user to assure that the advice method
 * does not contain byte code constructs that are not supported by the class containing the instrumented method. In particular, pre Java-5
 * try-finally blocks cannot be inlined into classes with newer byte code levels as the <i>jsr</i> instruction was deprecated. Also, classes prior
 * to Java 7 do not support the <i>invokedynamic</i> command which must not be contained by an advice method if the instrumented method targets an
 * older class file format version.
 * </p>
 * <p>
 * <b>Note</b>: For the purpose of inlining, Java 5 and Java 6 byte code can be seen as the best candidate for advice methods. These versions do
 * no longer allow subroutines, neither do they already allow invokedynamic instructions or method handles. This way, Java 5 and Java 6 byte
 * code is compatible to both older and newer versions. One exception for backwards-incompatible byte code is the possibility to load type references
 * from the constant pool onto the operand stack. These instructions can however easily be transformerd for classes compiled to Java 4 and older
 * by registering a {@link TypeConstantAdjustment} <b>before</b> the advice visitor.
 * </p>
 * <p>
 * <b>Note</b>: It is not possible to trigger break points in inlined advice methods as the debugging information of the inlined advice is not
 * preserved. It is not possible in Java to reference more than one source file per class what makes translating such debugging information
 * impossible. It is however possible to set break points in advice methods when invoking the original advice target. This allows debugging
 * of advice code within unit tests that invoke the advice method without instrumentation. As a conequence of not transferring debugging information,
 * the names of the parameters of an advice method do not matter when inlining, neither does any meta information on the advice method's body
 * such as annotations or parameter modifiers.
 * </p>
 * <p>
 * <b>Note</b>: The behavior of this component is undefined if it is supplied with invalid byte code what might result in runtime exceptions.
 * </p>
 *
 * @see DynamicValue
 * @see OnMethodEnter
 * @see OnMethodExit
 */
public class Advice implements AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper, Implementation {

    /**
     * Indicates that no class reader is available to an adice method.
     */
    private static final ClassReader UNDEFINED = null;

    /**
     * A reference to the {@link OnMethodEnter#inline()} method.
     */
    private static final MethodDescription.InDefinedShape INLINE_ENTER;

    /**
     * A reference to the {@link OnMethodEnter#suppress()} method.
     */
    private static final MethodDescription.InDefinedShape SUPPRESS_ENTER;

    /**
     * A reference to the {@link OnMethodEnter#prependLineNumber()} method.
     */
    private static final MethodDescription.InDefinedShape PREPEND_LINE_NUMBER;

    /**
     * A reference to the {@link OnMethodEnter#skipOn()} method.
     */
    private static final MethodDescription.InDefinedShape SKIP_ON;

    /**
     * A reference to the {@link OnMethodExit#inline()} method.
     */
    private static final MethodDescription.InDefinedShape INLINE_EXIT;

    /**
     * A reference to the {@link OnMethodExit#suppress()} method.
     */
    private static final MethodDescription.InDefinedShape SUPPRESS_EXIT;

    /**
     * A reference to the {@link OnMethodExit#onThrowable()} method.
     */
    private static final MethodDescription.InDefinedShape ON_THROWABLE;

    /*
     * Extracts the annotation values for the enter and exit advice annotations.
     */
    static {
        MethodList<MethodDescription.InDefinedShape> enter = new TypeDescription.ForLoadedType(OnMethodEnter.class).getDeclaredMethods();
        INLINE_ENTER = enter.filter(named("inline")).getOnly();
        SUPPRESS_ENTER = enter.filter(named("suppress")).getOnly();
        SKIP_ON = enter.filter(named("skipOn")).getOnly();
        PREPEND_LINE_NUMBER = enter.filter(named("prependLineNumber")).getOnly();
        MethodList<MethodDescription.InDefinedShape> exit = new TypeDescription.ForLoadedType(OnMethodExit.class).getDeclaredMethods();
        INLINE_EXIT = exit.filter(named("inline")).getOnly();
        SUPPRESS_EXIT = exit.filter(named("suppress")).getOnly();
        ON_THROWABLE = exit.filter(named("onThrowable")).getOnly();
    }

    /**
     * The dispatcher for instrumenting the instrumented method upon entering.
     */
    private final Dispatcher.Resolved.ForMethodEnter methodEnter;

    /**
     * The dispatcher for instrumenting the instrumented method upon exiting.
     */
    private final Dispatcher.Resolved.ForMethodExit methodExit;

    /**
     * The delegate implementation to apply if this advice is used as an instrumentation.
     */
    private final Implementation delegate;

    /**
     * Creates a new advice.
     *
     * @param methodEnter The dispatcher for instrumenting the instrumented method upon entering.
     * @param methodExit  The dispatcher for instrumenting the instrumented method upon exiting.
     */
    protected Advice(Dispatcher.Resolved.ForMethodEnter methodEnter, Dispatcher.Resolved.ForMethodExit methodExit) {
        this(methodEnter, methodExit, SuperMethodCall.INSTANCE);
    }

    /**
     * Creates a new advice.
     *
     * @param methodEnter The dispatcher for instrumenting the instrumented method upon entering.
     * @param methodExit  The dispatcher for instrumenting the instrumented method upon exiting.
     * @param delegate    The delegate implementation to apply if this advice is used as an instrumentation.
     */
    private Advice(Dispatcher.Resolved.ForMethodEnter methodEnter, Dispatcher.Resolved.ForMethodExit methodExit, Implementation delegate) {
        this.methodEnter = methodEnter;
        this.methodExit = methodExit;
        this.delegate = delegate;
    }

    /**
     * Implements advice where every matched method is advised by the given type's advisory methods. The advices binary representation is
     * accessed by querying the class loader of the supplied class for a class file.
     *
     * @param advice The type declaring the advice.
     * @return A method visitor wrapper representing the supplied advice.
     */
    public static Advice to(Class<?> advice) {
        return to(advice, ClassFileLocator.ForClassLoader.of(advice.getClassLoader()));
    }

    /**
     * Implements advice where every matched method is advised by the given type's advisory methods.
     *
     * @param advice           The type declaring the advice.
     * @param classFileLocator The class file locator for locating the advisory class's class file.
     * @return A method visitor wrapper representing the supplied advice.
     */
    public static Advice to(Class<?> advice, ClassFileLocator classFileLocator) {
        return to(new TypeDescription.ForLoadedType(advice), classFileLocator);
    }

    /**
     * Implements advice where every matched method is advised by the given type's advisory methods. Using this method, a non-operational
     * class file locator is specified for the advice target. This implies that only advice targets with the <i>inline</i> target set
     * to {@code false} are resolvable by the returned instance.
     *
     * @param advice The type declaring the advice.
     * @return A method visitor wrapper representing the supplied advice.
     */
    public static Advice to(TypeDescription advice) {
        return to(advice, ClassFileLocator.NoOp.INSTANCE);
    }

    /**
     * Implements advice where every matched method is advised by the given type's advisory methods.
     *
     * @param advice           A description of the type declaring the advice.
     * @param classFileLocator The class file locator for locating the advisory class's class file.
     * @return A method visitor wrapper representing the supplied advice.
     */
    public static Advice to(TypeDescription advice, ClassFileLocator classFileLocator) {
        return to(advice, classFileLocator, Collections.<Dispatcher.OffsetMapping.Factory>emptyList());
    }

    /**
     * Creates a new advice.
     *
     * @param advice           A description of the type declaring the advice.
     * @param classFileLocator The class file locator for locating the advisory class's class file.
     * @param userFactories    A list of custom factories for user generated offset mappings.
     * @return A method visitor wrapper representing the supplied advice.
     */
    protected static Advice to(TypeDescription advice, ClassFileLocator classFileLocator, List<? extends Dispatcher.OffsetMapping.Factory> userFactories) {
        Dispatcher.Unresolved methodEnter = Dispatcher.Inactive.INSTANCE, methodExit = Dispatcher.Inactive.INSTANCE;
        for (MethodDescription.InDefinedShape methodDescription : advice.getDeclaredMethods()) {
            methodEnter = locate(OnMethodEnter.class, INLINE_ENTER, methodEnter, methodDescription);
            methodExit = locate(OnMethodExit.class, INLINE_EXIT, methodExit, methodDescription);
        }
        if (!methodEnter.isAlive() && !methodExit.isAlive()) {
            throw new IllegalArgumentException("No advice defined by " + advice);
        }
        try {
            ClassReader classReader = methodEnter.isBinary() || methodExit.isBinary()
                    ? new ClassReader(classFileLocator.locate(advice.getName()).resolve())
                    : UNDEFINED;
            Dispatcher.Resolved.ForMethodEnter resolved = methodEnter.asMethodEnter(userFactories, classReader);
            return new Advice(resolved, methodExit.asMethodExitTo(userFactories, classReader, resolved));
        } catch (IOException exception) {
            throw new IllegalStateException("Error reading class file of " + advice, exception);
        }
    }

    /**
     * Implements advice where every matched method is advised by the given type's advisory methods. The advices binary representation is
     * accessed by querying the class loader of the supplied class for a class file.
     *
     * @param enterAdvice The type declaring the enter advice.
     * @param exitAdvice  The type declaring the exit advice.
     * @return A method visitor wrapper representing the supplied advice.
     */
    public static Advice to(Class<?> enterAdvice, Class<?> exitAdvice) {
        ClassLoader enterLoader = enterAdvice.getClassLoader(), exitLoader = exitAdvice.getClassLoader();
        return to(enterAdvice, exitAdvice, enterLoader == exitLoader
                ? ClassFileLocator.ForClassLoader.of(enterLoader)
                : new ClassFileLocator.Compound(ClassFileLocator.ForClassLoader.of(enterLoader), ClassFileLocator.ForClassLoader.of(exitLoader)));
    }

    /**
     * Implements advice where every matched method is advised by the given type's advisory methods.
     *
     * @param enterAdvice      The type declaring the enter advice.
     * @param exitAdvice       The type declaring the exit advice.
     * @param classFileLocator The class file locator for locating the advisory class's class file.
     * @return A method visitor wrapper representing the supplied advice.
     */
    public static Advice to(Class<?> enterAdvice, Class<?> exitAdvice, ClassFileLocator classFileLocator) {
        return to(new TypeDescription.ForLoadedType(enterAdvice), new TypeDescription.ForLoadedType(exitAdvice), classFileLocator);
    }

    /**
     * Implements advice where every matched method is advised by the given type's advisory methods. Using this method, a non-operational
     * class file locator is specified for the advice target. This implies that only advice targets with the <i>inline</i> target set
     * to {@code false} are resolvable by the returned instance.
     *
     * @param enterAdvice The type declaring the enter advice.
     * @param exitAdvice  The type declaring the exit advice.
     * @return A method visitor wrapper representing the supplied advice.
     */
    public static Advice to(TypeDescription enterAdvice, TypeDescription exitAdvice) {
        return to(enterAdvice, exitAdvice, ClassFileLocator.NoOp.INSTANCE);
    }

    /**
     * Implements advice where every matched method is advised by the given type's advisory methods.
     *
     * @param enterAdvice      The type declaring the enter advice.
     * @param exitAdvice       The type declaring the exit advice.
     * @param classFileLocator The class file locator for locating the advisory class's class file.
     * @return A method visitor wrapper representing the supplied advice.
     */
    public static Advice to(TypeDescription enterAdvice, TypeDescription exitAdvice, ClassFileLocator classFileLocator) {
        return to(enterAdvice, exitAdvice, classFileLocator, Collections.<Dispatcher.OffsetMapping.Factory>emptyList());
    }

    /**
     * Creates a new advice.
     *
     * @param enterAdvice      The type declaring the enter advice.
     * @param exitAdvice       The type declaring the exit advice.
     * @param classFileLocator The class file locator for locating the advisory class's class file.
     * @param userFactories    A list of custom factories for user generated offset mappings.
     * @return A method visitor wrapper representing the supplied advice.
     */
    protected static Advice to(TypeDescription enterAdvice,
                               TypeDescription exitAdvice,
                               ClassFileLocator classFileLocator,
                               List<? extends Dispatcher.OffsetMapping.Factory> userFactories) {
        Dispatcher.Unresolved methodEnter = Dispatcher.Inactive.INSTANCE, methodExit = Dispatcher.Inactive.INSTANCE;
        for (MethodDescription.InDefinedShape methodDescription : enterAdvice.getDeclaredMethods()) {
            methodEnter = locate(OnMethodEnter.class, INLINE_ENTER, methodEnter, methodDescription);
        }
        if (!methodEnter.isAlive()) {
            throw new IllegalArgumentException("No enter advice defined by " + enterAdvice);
        }
        for (MethodDescription.InDefinedShape methodDescription : exitAdvice.getDeclaredMethods()) {
            methodExit = locate(OnMethodExit.class, INLINE_EXIT, methodExit, methodDescription);
        }
        if (!methodExit.isAlive()) {
            throw new IllegalArgumentException("No enter advice defined by " + exitAdvice);
        }
        try {
            Dispatcher.Resolved.ForMethodEnter resolved = methodEnter.asMethodEnter(userFactories, methodEnter.isBinary()
                    ? new ClassReader(classFileLocator.locate(enterAdvice.getName()).resolve())
                    : UNDEFINED);
            return new Advice(resolved, methodExit.asMethodExitTo(userFactories, methodExit.isBinary()
                    ? new ClassReader(classFileLocator.locate(exitAdvice.getName()).resolve())
                    : UNDEFINED, resolved));
        } catch (IOException exception) {
            throw new IllegalStateException("Error reading class file of " + enterAdvice + " or " + exitAdvice, exception);
        }
    }

    /**
     * Locates a dispatcher for the method if available.
     *
     * @param type              The annotation type that indicates a given form of advice that is currently resolved.
     * @param property          An annotation property that indicates if the advice method should be inlined.
     * @param dispatcher        Any previous dispatcher that was discovered or {@code null} if no such dispatcher was yet found.
     * @param methodDescription The method description that is considered as an advice method.
     * @return A resolved dispatcher or {@code null} if no dispatcher was resolved.
     */
    private static Dispatcher.Unresolved locate(Class<? extends Annotation> type,
                                                MethodDescription.InDefinedShape property,
                                                Dispatcher.Unresolved dispatcher,
                                                MethodDescription.InDefinedShape methodDescription) {
        AnnotationDescription annotation = methodDescription.getDeclaredAnnotations().ofType(type);
        if (annotation == null) {
            return dispatcher;
        } else if (dispatcher.isAlive()) {
            throw new IllegalStateException("Duplicate advice for " + dispatcher + " and " + methodDescription);
        } else if (!methodDescription.isStatic()) {
            throw new IllegalStateException("Advice for " + methodDescription + " is not static");
        } else {
            return annotation.getValue(property).resolve(Boolean.class)
                    ? new Dispatcher.Inlining(methodDescription)
                    : new Dispatcher.Delegating(methodDescription);
        }
    }

    /**
     * Allows for the configuration of custom annotations that are then bound to a dynamically computed, constant value.
     *
     * @return A builder for an {@link Advice} instrumentation with custom values.
     * @see DynamicValue
     */
    public static WithCustomMapping withCustomMapping() {
        return new WithCustomMapping();
    }

    /**
     * Returns an ASM visitor wrapper that matches the given matcher and applies this advice to the matched methods.
     *
     * @param matcher The matcher identifying methods to apply the advice to.
     * @return A suitable ASM visitor wrapper with the <i>compute frames</i> option enabled.
     */
    public AsmVisitorWrapper.ForDeclaredMethods on(ElementMatcher<? super MethodDescription.InDefinedShape> matcher) {
        return new AsmVisitorWrapper.ForDeclaredMethods().method(matcher, this);
    }

    @Override
    public MethodVisitor wrap(TypeDescription instrumentedType,
                              MethodDescription.InDefinedShape instrumentedMethod,
                              MethodVisitor methodVisitor,
                              Implementation.Context implementationContext,
                              TypePool typePool,
                              int writerFlags,
                              int readerFlags) {
        return instrumentedMethod.isAbstract() || instrumentedMethod.isNative()
                ? methodVisitor
                : doWrap(instrumentedType, instrumentedMethod, methodVisitor, implementationContext, writerFlags, readerFlags);
    }

    /**
     * Wraps the method visitor to implement this advice.
     *
     * @param instrumentedType   The instrumented type.
     * @param instrumentedMethod The instrumented method.
     * @param methodVisitor      The method visitor to write to.
     * @param writerFlags        The ASM writer flags to use.
     * @param readerFlags        The ASM reader flags to use.
     * @return A method visitor that applies this advice.
     */
    protected MethodVisitor doWrap(TypeDescription instrumentedType,
                                   MethodDescription instrumentedMethod,
                                   MethodVisitor methodVisitor,
                                   Implementation.Context implementationContext,
                                   int writerFlags,
                                   int readerFlags) {
        methodVisitor = methodEnter.isPrependLineNumber()
                ? new LineNumberPrependingMethodVisitor(methodVisitor)
                : methodVisitor;
        if (!methodExit.isAlive()) {
            return new AdviceVisitor.WithoutExitAdvice(methodVisitor,
                    implementationContext,
                    instrumentedType,
                    instrumentedMethod,
                    methodEnter,
                    writerFlags,
                    readerFlags);
        } else if (methodExit.getTriggeringThrowable().represents(NoExceptionHandler.class)) {
            return new AdviceVisitor.WithExitAdvice.WithoutExceptionHandling(methodVisitor,
                    implementationContext,
                    instrumentedType,
                    instrumentedMethod,
                    methodEnter,
                    methodExit,
                    writerFlags,
                    readerFlags);
        } else if (instrumentedMethod.isConstructor()) {
            throw new IllegalStateException("Cannot catch exception during constructor call for " + instrumentedMethod);
        } else {
            return new AdviceVisitor.WithExitAdvice.WithExceptionHandling(methodVisitor,
                    implementationContext,
                    instrumentedType,
                    instrumentedMethod,
                    methodEnter,
                    methodExit,
                    writerFlags,
                    readerFlags,
                    methodExit.getTriggeringThrowable());
        }
    }

    @Override
    public InstrumentedType prepare(InstrumentedType instrumentedType) {
        return delegate.prepare(instrumentedType);
    }

    @Override
    public ByteCodeAppender appender(Target implementationTarget) {
        return new Appender(this, implementationTarget, delegate.appender(implementationTarget));
    }

    /**
     * Wraps the supplied implementation to have this advice applied around it.
     *
     * @param implementation The implementation to wrap.
     * @return An implementation that applies the supplied implementation and wraps it with this advice.
     */
    public Implementation wrap(Implementation implementation) {
        return new Advice(methodEnter, methodExit, implementation);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (other == null || getClass() != other.getClass()) return false;
        Advice advice = (Advice) other;
        return methodEnter.equals(advice.methodEnter)
                && methodExit.equals(advice.methodExit)
                && delegate.equals(advice.delegate);
    }

    @Override
    public int hashCode() {
        int result = methodEnter.hashCode();
        result = 31 * result + methodExit.hashCode();
        result = 31 * result + delegate.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "Advice{" +
                "methodEnter=" + methodEnter +
                ", methodExit=" + methodExit +
                ", delegate=" + delegate +
                '}';
    }

    /**
     * A handler for computing the instrumented method's size.
     */
    protected interface MethodSizeHandler {

        /**
         * Indicates that a size is not computed but handled directly by ASM.
         */
        int UNDEFINED_SIZE = Short.MAX_VALUE;

        /**
         * Requires a minimum length of the local variable array.
         *
         * @param localVariableLength The minimal required length of the local variable array.
         */
        void requireLocalVariableLength(int localVariableLength);

        /**
         * A method size handler for the instrumented method.
         */
        interface ForInstrumentedMethod extends MethodSizeHandler {

            /**
             * Binds a method size handler for the entry advice.
             *
             * @param adviceMethod The method representing the entry advice.
             * @return A method size handler for the entry advice.
             */
            ForAdvice bindEntry(MethodDescription.InDefinedShape adviceMethod);

            /**
             * Binds the method size handler for the exit advice.
             *
             * @param adviceMethod  The method representing the exit advice.
             * @param skipThrowable {@code true} if the exit advice is not invoked on an exception.
             * @return A method size handler for the exit advice.
             */
            ForAdvice bindExit(MethodDescription.InDefinedShape adviceMethod, boolean skipThrowable);

            /**
             * Computes a compound stack size for the advice and the translated instrumented method.
             *
             * @param stackSize The required stack size of the instrumented method before translation.
             * @return The stack size required by the instrumented method and its advice methods.
             */
            int compoundStackSize(int stackSize);

            /**
             * Computes a compound local variable array length for the advice and the translated instrumented method.
             *
             * @param localVariableLength The required local variable array length of the instrumented method before translation.
             * @return The local variable length required by the instrumented method and its advice methods.
             */
            int compoundLocalVariableLength(int localVariableLength);
        }

        /**
         * A method size handler for an advice method.
         */
        interface ForAdvice extends MethodSizeHandler {

            /**
             * Records a minimum stack size required by the represented advice method.
             *
             * @param stackSize The minimum size required by the represented advice method.
             */
            void requireStackSize(int stackSize);

            /**
             * Records the maximum values for stack size and local variable array which are required by the advice method
             * for its individual execution without translation.
             *
             * @param stackSize           The minimum required stack size.
             * @param localVariableLength The minimum required length of the local variable array.
             */
            void recordMaxima(int stackSize, int localVariableLength);

            /**
             * Records a minimum padding additionally to the computed stack size that is required for implementing this advice method.
             *
             * @param padding The minimum required padding.
             */
            void recordPadding(int padding);
        }

        /**
         * A non-operational method size handler.
         */
        enum NoOp implements ForInstrumentedMethod, ForAdvice {

            /**
             * The singleton instance.
             */
            INSTANCE;

            @Override
            public ForAdvice bindEntry(MethodDescription.InDefinedShape adviceMethod) {
                return this;
            }

            @Override
            public ForAdvice bindExit(MethodDescription.InDefinedShape adviceMethod, boolean skipThrowable) {
                return this;
            }

            @Override
            public int compoundStackSize(int stackSize) {
                return UNDEFINED_SIZE;
            }

            @Override
            public int compoundLocalVariableLength(int localVariableLength) {
                return UNDEFINED_SIZE;
            }

            @Override
            public void requireLocalVariableLength(int localVariableLength) {
                /* do nothing */
            }

            @Override
            public void requireStackSize(int stackSize) {
                /* do nothing */
            }

            @Override
            public void recordMaxima(int stackSize, int localVariableLength) {
                /* do nothing */
            }

            @Override
            public void recordPadding(int padding) {
                /* do nothing */
            }

            @Override
            public String toString() {
                return "Advice.MethodSizeHandler.NoOp." + name();
            }
        }

        /**
         * A default implementation for a method size handler.
         */
        class Default implements MethodSizeHandler.ForInstrumentedMethod {

            /**
             * The instrumented method.
             */
            private final MethodDescription instrumentedMethod;

            /**
             * The list of types that the instrumented method requires in addition to the method parameters.
             */
            private final TypeList requiredTypes;

            /**
             * A list of types that are yielded by the instrumented method and available to the exit advice.
             */
            private final TypeList yieldedTypes;

            /**
             * The maximum stack size required by a visited advice method.
             */
            private int stackSize;

            /**
             * The maximum length of the local variable array required by a visited advice method.
             */
            private int localVariableLength;

            /**
             * Creates a new default meta data handler that recomputes the space requirements of an instrumented method.
             *
             * @param instrumentedMethod The instrumented method.
             * @param requiredTypes      The types this meta data handler expects to be available additionally to the instrumented method's parameters.
             * @param yieldedTypes       The types that are expected to be added after the instrumented method returns.
             */
            protected Default(MethodDescription instrumentedMethod, TypeList requiredTypes, TypeList yieldedTypes) {
                this.instrumentedMethod = instrumentedMethod;
                this.requiredTypes = requiredTypes;
                this.yieldedTypes = yieldedTypes;
            }

            /**
             * Creates a method size handler applicable for the given instrumented method.
             *
             * @param instrumentedMethod The instrumented method.
             * @param requiredTypes      The list of types that the instrumented method requires in addition to the method parameters.
             * @param yieldedTypes       A list of types that are yielded by the instrumented method and available to the exit advice.
             * @param writerFlags        The flags supplied to the ASM class writer.
             * @return An appropriate method size handler.
             */
            protected static MethodSizeHandler.ForInstrumentedMethod of(MethodDescription instrumentedMethod,
                                                                        List<? extends TypeDescription> requiredTypes,
                                                                        List<? extends TypeDescription> yieldedTypes,
                                                                        int writerFlags) {
                return (writerFlags & (ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES)) != 0
                        ? NoOp.INSTANCE
                        : new Default(instrumentedMethod, new TypeList.Explicit(requiredTypes), new TypeList.Explicit(yieldedTypes));
            }

            @Override
            public MethodSizeHandler.ForAdvice bindEntry(MethodDescription.InDefinedShape adviceMethod) {
                stackSize = Math.max(stackSize, adviceMethod.getReturnType().getStackSize().getSize());
                return new ForAdvice(adviceMethod, new TypeList.Empty(), new TypeList.Explicit(requiredTypes));
            }

            @Override
            public MethodSizeHandler.ForAdvice bindExit(MethodDescription.InDefinedShape adviceMethod, boolean skipThrowable) {
                stackSize = Math.max(stackSize, adviceMethod.getReturnType().getStackSize().maximum(skipThrowable
                        ? StackSize.ZERO
                        : StackSize.SINGLE).getSize());
                return new ForAdvice(adviceMethod, new TypeList.Explicit(CompoundList.of(requiredTypes, yieldedTypes)), new TypeList.Empty());
            }

            @Override
            public int compoundStackSize(int stackSize) {
                return Math.max(this.stackSize, stackSize);
            }

            @Override
            public int compoundLocalVariableLength(int localVariableLength) {
                return Math.max(this.localVariableLength, localVariableLength
                        + requiredTypes.getStackSize()
                        + yieldedTypes.getStackSize());
            }

            @Override
            public void requireLocalVariableLength(int localVariableLength) {
                this.localVariableLength = Math.max(this.localVariableLength, localVariableLength);
            }

            @Override
            public String toString() {
                return "Advice.MethodSizeHandler.Default{" +
                        "instrumentedMethod=" + instrumentedMethod +
                        ", requiredTypes=" + requiredTypes +
                        ", yieldedTypes=" + yieldedTypes +
                        ", stackSize=" + stackSize +
                        ", localVariableLength=" + localVariableLength +
                        '}';
            }

            /**
             * A method size handler for an advice method.
             */
            protected class ForAdvice implements MethodSizeHandler.ForAdvice {

                /**
                 * The advice method.
                 */
                private final MethodDescription.InDefinedShape adviceMethod;

                /**
                 * A list of types required by this advice method.
                 */
                private final TypeList requiredTypes;

                /**
                 * A list of types yielded by this advice method.
                 */
                private final TypeList yieldedTypes;

                /**
                 * The padding that this advice method requires additionally to its computed size.
                 */
                private int padding;

                /**
                 * Creates a new method size handler for an advice method.
                 *
                 * @param adviceMethod  The advice method.
                 * @param requiredTypes A list of types required by this advice method.
                 * @param yieldedTypes  A list of types yielded by this advice method.
                 */
                protected ForAdvice(MethodDescription.InDefinedShape adviceMethod, TypeList requiredTypes, TypeList yieldedTypes) {
                    this.adviceMethod = adviceMethod;
                    this.requiredTypes = requiredTypes;
                    this.yieldedTypes = yieldedTypes;
                    stackSize = Math.max(stackSize, adviceMethod.getReturnType().getStackSize().getSize());
                }

                @Override
                public void requireLocalVariableLength(int localVariableLength) {
                    Default.this.requireLocalVariableLength(localVariableLength);
                }

                @Override
                public void requireStackSize(int stackSize) {
                    Default.this.stackSize = Math.max(Default.this.stackSize, stackSize);
                }

                @Override
                public void recordMaxima(int stackSize, int localVariableLength) {
                    Default.this.stackSize = Math.max(Default.this.stackSize, stackSize) + padding;
                    Default.this.localVariableLength = Math.max(Default.this.localVariableLength, localVariableLength
                            - adviceMethod.getStackSize()
                            + instrumentedMethod.getStackSize()
                            + requiredTypes.getStackSize()
                            + yieldedTypes.getStackSize());
                }

                @Override
                public void recordPadding(int padding) {
                    this.padding = Math.max(this.padding, padding);
                }

                @Override
                public String toString() {
                    return "Advice.MethodSizeHandler.Default.ForAdvice{" +
                            "adviceMethod=" + adviceMethod +
                            ", requiredTypes=" + requiredTypes +
                            ", yieldedTypes=" + yieldedTypes +
                            ", padding=" + padding +
                            '}';
                }
            }
        }
    }

    /**
     * A handler for computing and translating stack map frames.
     */
    protected interface StackMapFrameHandler {

        /**
         * Translates a frame.
         *
         * @param methodVisitor       The method visitor to write the frame to.
         * @param frameType           The frame's type.
         * @param localVariableLength The local variable length.
         * @param localVariable       An array containing the types of the current local variables.
         * @param stackSize           The size of the operand stack.
         * @param stack               An array containing the types of the current operand stack.
         */
        void translateFrame(MethodVisitor methodVisitor, int frameType, int localVariableLength, Object[] localVariable, int stackSize, Object[] stack);

        /**
         * Injects a frame indicating the beginning of a return value handler for the currently handled method.
         *
         * @param methodVisitor The method visitor onto which to apply the stack map frame.
         */
        void injectReturnFrame(MethodVisitor methodVisitor);

        /**
         * Injects a frame indicating the beginning of an exception handler for the currently handled method.
         *
         * @param methodVisitor The method visitor onto which to apply the stack map frame.
         */
        void injectExceptionFrame(MethodVisitor methodVisitor);

        /**
         * Injects a frame indicating the completion of the currently handled method, i.e. all yielded types were added.
         *
         * @param methodVisitor The method visitor onto which to apply the stack map frame.
         * @param secondary     {@code true} if another completion frame for this method was written previously.
         */
        void injectCompletionFrame(MethodVisitor methodVisitor, boolean secondary);

        /**
         * A stack map frame handler for an instrumented method.
         */
        interface ForInstrumentedMethod extends StackMapFrameHandler {

            /**
             * Binds this meta data handler for the entry advice.
             *
             * @param adviceMethod The entry advice method.
             * @return An appropriate meta data handler for the enter method.
             */
            ForAdvice bindEntry(MethodDescription.InDefinedShape adviceMethod);

            /**
             * Binds this meta data handler for the exit advice.
             *
             * @param adviceMethod The exit advice method.
             * @return An appropriate meta data handler for the enter method.
             */
            ForAdvice bindExit(MethodDescription.InDefinedShape adviceMethod);

            /**
             * Returns a hint to supply to a {@link ClassReader} when parsing an advice method.
             *
             * @return The reader hint to supply to an ASM class reader.
             */
            int getReaderHint();
        }

        /**
         * A stack map frame handler for an advice method.
         */
        interface ForAdvice extends StackMapFrameHandler {
            /* marker interface */
        }

        /**
         * A non-operational stack map frame handler.
         */
        enum NoOp implements ForInstrumentedMethod, ForAdvice {

            /**
             * The singleton instance.
             */
            INSTANCE;

            @Override
            public StackMapFrameHandler.ForAdvice bindEntry(MethodDescription.InDefinedShape adviceMethod) {
                return this;
            }

            @Override
            public StackMapFrameHandler.ForAdvice bindExit(MethodDescription.InDefinedShape adviceMethod) {
                return this;
            }

            @Override
            public int getReaderHint() {
                return ClassReader.SKIP_FRAMES;
            }

            @Override
            public void translateFrame(MethodVisitor methodVisitor,
                                       int frameType,
                                       int localVariableLength,
                                       Object[] localVariable,
                                       int stackSize,
                                       Object[] stack) {
                /* do nothing */
            }


            @Override
            public void injectReturnFrame(MethodVisitor methodVisitor) {
                /* do nothing */
            }

            @Override
            public void injectExceptionFrame(MethodVisitor methodVisitor) {
                /* do nothing */
            }

            @Override
            public void injectCompletionFrame(MethodVisitor methodVisitor, boolean secondary) {
                /* do nothing */
            }

            @Override
            public String toString() {
                return "Advice.StackMapFrameHandler.NoOp." + name();
            }
        }

        /**
         * A default implementation of a stack map frame handler for an instrumented method.
         */
        class Default implements ForInstrumentedMethod {

            /**
             * An empty array indicating an empty frame.
             */
            private static final Object[] EMPTY = new Object[0];

            /**
             * The instrumented type.
             */
            private final TypeDescription instrumentedType;

            /**
             * The instrumented method.
             */
            protected final MethodDescription instrumentedMethod;

            /**
             * A list of intermediate types to be considered as part of the instrumented method's steady signature.
             */
            protected final TypeList requiredTypes;

            /**
             * The types that are expected to be added after the instrumented method returns.
             */
            protected final TypeList yieldedTypes;

            /**
             * {@code true} if the meta data handler is expected to expand its frames.
             */
            private final boolean expandFrames;

            /**
             * The current frame's size divergence from the original local variable array.
             */
            private int currentFrameDivergence;

            /**
             * Creates a new default meta data handler.
             *
             * @param instrumentedType   The instrumented type.
             * @param instrumentedMethod The instrumented method.
             * @param requiredTypes      A list of intermediate types to be considered as part of the instrumented method's steady signature.
             * @param yieldedTypes       The types that are expected to be added after the instrumented method returns.
             * @param expandFrames       {@code true} if the meta data handler is expected to expand its frames.
             */
            protected Default(TypeDescription instrumentedType,
                              MethodDescription instrumentedMethod,
                              TypeList requiredTypes,
                              TypeList yieldedTypes,
                              boolean expandFrames) {
                this.instrumentedType = instrumentedType;
                this.instrumentedMethod = instrumentedMethod;
                this.requiredTypes = requiredTypes;
                this.yieldedTypes = yieldedTypes;
                this.expandFrames = expandFrames;
            }

            /**
             * Creates an appropriate stack map frame handler for an instrumented method.
             *
             * @param instrumentedType   The instrumented type.
             * @param instrumentedMethod The instrumented method.
             * @param requiredTypes      A list of intermediate types to be considered as part of the instrumented method's steady signature.
             * @param yieldedTypes       The types that are expected to be added after the instrumented method returns.
             * @param classFileVersion   The instrumented type's class file version.
             * @param writerFlags        The flags supplied to the ASM writier.
             * @param readerFlags        The reader flags supplied to the ASM reader.
             * @return An approrpiate stack map frame handler for an instrumented method.
             */
            protected static ForInstrumentedMethod of(TypeDescription instrumentedType,
                                                      MethodDescription instrumentedMethod,
                                                      List<? extends TypeDescription> requiredTypes,
                                                      List<? extends TypeDescription> yieldedTypes,
                                                      ClassFileVersion classFileVersion,
                                                      int writerFlags,
                                                      int readerFlags) {
                return (writerFlags & ClassWriter.COMPUTE_FRAMES) != 0 || classFileVersion.isLessThan(ClassFileVersion.JAVA_V6)
                        ? NoOp.INSTANCE
                        : new Default(instrumentedType, instrumentedMethod, new TypeList.Explicit(requiredTypes), new TypeList.Explicit(yieldedTypes), (readerFlags & ClassReader.EXPAND_FRAMES) != 0);
            }

            /**
             * Translates a type into a representation of its form inside a stack map frame.
             *
             * @param typeDescription The type to translate.
             * @return A stack entry representation of the supplied type.
             */
            protected static Object toFrame(TypeDescription typeDescription) {
                if (typeDescription.represents(boolean.class)
                        || typeDescription.represents(byte.class)
                        || typeDescription.represents(short.class)
                        || typeDescription.represents(char.class)
                        || typeDescription.represents(int.class)) {
                    return Opcodes.INTEGER;
                } else if (typeDescription.represents(long.class)) {
                    return Opcodes.LONG;
                } else if (typeDescription.represents(float.class)) {
                    return Opcodes.FLOAT;
                } else if (typeDescription.represents(double.class)) {
                    return Opcodes.DOUBLE;
                } else {
                    return typeDescription.getInternalName();
                }
            }

            @Override
            public StackMapFrameHandler.ForAdvice bindEntry(MethodDescription.InDefinedShape adviceMethod) {
                return new ForAdvice(adviceMethod, new TypeList.Empty(), requiredTypes, TranslationMode.ENTRY);
            }

            @Override
            public StackMapFrameHandler.ForAdvice bindExit(MethodDescription.InDefinedShape adviceMethod) {
                return new ForAdvice(adviceMethod, new TypeList.Explicit(CompoundList.of(requiredTypes, yieldedTypes)), new TypeList.Empty(), TranslationMode.EXIT);
            }

            @Override
            public int getReaderHint() {
                return expandFrames
                        ? ClassReader.EXPAND_FRAMES
                        : AsmVisitorWrapper.NO_FLAGS;
            }

            @Override
            public void translateFrame(MethodVisitor methodVisitor,
                                       int type,
                                       int localVariableLength,
                                       Object[] localVariable,
                                       int stackSize,
                                       Object[] stack) {
                translateFrame(methodVisitor,
                        TranslationMode.COPY,
                        instrumentedMethod,
                        requiredTypes,
                        type,
                        localVariableLength,
                        localVariable,
                        stackSize,
                        stack);
            }

            /**
             * Translates a frame.
             *
             * @param methodVisitor       The method visitor to write the frame to.
             * @param translationMode     The translation mode to apply.
             * @param methodDescription   The method description for which the frame is written.
             * @param additionalTypes     The additional types to consider part of the instrumented method's parameters.
             * @param frameType           The frame's type.
             * @param localVariableLength The local variable length.
             * @param localVariable       An array containing the types of the current local variables.
             * @param stackSize           The size of the operand stack.
             * @param stack               An array containing the types of the current operand stack.
             */
            protected void translateFrame(MethodVisitor methodVisitor,
                                          TranslationMode translationMode,
                                          MethodDescription methodDescription,
                                          TypeList additionalTypes,
                                          int frameType,
                                          int localVariableLength,
                                          Object[] localVariable,
                                          int stackSize,
                                          Object[] stack) {
                switch (frameType) {
                    case Opcodes.F_SAME:
                    case Opcodes.F_SAME1:
                        break;
                    case Opcodes.F_APPEND:
                        currentFrameDivergence += localVariableLength;
                        break;
                    case Opcodes.F_CHOP:
                        currentFrameDivergence -= localVariableLength;
                        break;
                    case Opcodes.F_FULL:
                    case Opcodes.F_NEW:
                        Object[] translated = new Object[localVariableLength
                                - methodDescription.getParameters().size()
                                - (methodDescription.isStatic() ? 0 : 1)
                                + instrumentedMethod.getParameters().size()
                                + (instrumentedMethod.isStatic() ? 0 : 1)
                                + additionalTypes.size()];
                        int index = translationMode.copy(instrumentedType, instrumentedMethod, methodDescription, localVariable, translated);
                        for (TypeDescription typeDescription : additionalTypes) {
                            translated[index++] = toFrame(typeDescription);
                        }
                        System.arraycopy(localVariable,
                                methodDescription.getParameters().size() + (methodDescription.isStatic() ? 0 : 1),
                                translated,
                                index,
                                translated.length - index);
                        localVariableLength = translated.length;
                        localVariable = translated;
                        currentFrameDivergence = translated.length - index;
                        break;
                    default:
                        throw new IllegalArgumentException("Unexpected frame type: " + frameType);
                }
                methodVisitor.visitFrame(frameType, localVariableLength, localVariable, stackSize, stack);
            }

            @Override
            public void injectReturnFrame(MethodVisitor methodVisitor) {
                if (!expandFrames && currentFrameDivergence == 0 && !instrumentedMethod.isConstructor()) {
                    if (instrumentedMethod.getReturnType().represents(void.class)) {
                        methodVisitor.visitFrame(Opcodes.F_SAME, 0, EMPTY, 0, EMPTY);
                    } else {
                        methodVisitor.visitFrame(Opcodes.F_SAME1, 0, EMPTY, 1, new Object[]{toFrame(instrumentedMethod.getReturnType().asErasure())});
                    }
                } else {
                    injectFullFrame(methodVisitor, requiredTypes, instrumentedMethod.getReturnType().represents(void.class)
                            ? Collections.<TypeDescription>emptyList()
                            : Collections.singletonList(instrumentedMethod.getReturnType().asErasure()));
                }
            }

            @Override
            public void injectExceptionFrame(MethodVisitor methodVisitor) {
                if (!expandFrames && currentFrameDivergence == 0) {
                    methodVisitor.visitFrame(Opcodes.F_SAME1, 0, EMPTY, 1, new Object[]{Type.getInternalName(Throwable.class)});
                } else {
                    injectFullFrame(methodVisitor, requiredTypes, Collections.singletonList(TypeDescription.THROWABLE));
                }
            }

            @Override
            public void injectCompletionFrame(MethodVisitor methodVisitor, boolean secondary) {
                if (!expandFrames && currentFrameDivergence == 0 && (secondary || !instrumentedMethod.isConstructor())) {
                    if (secondary) {
                        methodVisitor.visitFrame(Opcodes.F_SAME, 0, EMPTY, 0, EMPTY);
                    } else {
                        Object[] local = new Object[yieldedTypes.size()];
                        int index = 0;
                        for (TypeDescription typeDescription : yieldedTypes) {
                            local[index++] = toFrame(typeDescription);
                        }
                        methodVisitor.visitFrame(Opcodes.F_APPEND, local.length, local, 0, EMPTY);
                    }
                } else {
                    injectFullFrame(methodVisitor, CompoundList.of(requiredTypes, yieldedTypes), Collections.<TypeDescription>emptyList());
                }
            }

            /**
             * Injects a full stack map frame.
             *
             * @param methodVisitor The method visitor onto which to write the stack map frame.
             * @param typesInArray  The types that were added to the local variable array additionally to the values of the instrumented method.
             * @param typesOnStack  The types currently on the operand stack.
             */
            protected void injectFullFrame(MethodVisitor methodVisitor,
                                           List<? extends TypeDescription> typesInArray,
                                           List<? extends TypeDescription> typesOnStack) {
                Object[] localVariable = new Object[instrumentedMethod.getParameters().size()
                        + (instrumentedMethod.isStatic() ? 0 : 1)
                        + typesInArray.size()];
                int index = 0;
                if (!instrumentedMethod.isStatic()) {
                    localVariable[index++] = toFrame(instrumentedType);
                }
                for (TypeDescription typeDescription : instrumentedMethod.getParameters().asTypeList().asErasures()) {
                    localVariable[index++] = toFrame(typeDescription);
                }
                for (TypeDescription typeDescription : typesInArray) {
                    localVariable[index++] = toFrame(typeDescription);
                }
                index = 0;
                Object[] stackType = new Object[typesOnStack.size()];
                for (TypeDescription typeDescription : typesOnStack) {
                    stackType[index++] = toFrame(typeDescription);
                }
                methodVisitor.visitFrame(expandFrames ? Opcodes.F_NEW : Opcodes.F_FULL, localVariable.length, localVariable, stackType.length, stackType);
                currentFrameDivergence = 0;
            }

            @Override
            public String toString() {
                return "Advice.StackMapFrameHandler.Default{" +
                        "instrumentedType=" + instrumentedType +
                        ", instrumentedMethod=" + instrumentedMethod +
                        ", requiredTypes=" + requiredTypes +
                        ", yieldedTypes=" + yieldedTypes +
                        ", expandFrames=" + expandFrames +
                        ", currentFrameDivergence=" + currentFrameDivergence +
                        '}';
            }

            /**
             * A translation mode that determines how the fixed frames of the instrumented method are written.
             */
            protected enum TranslationMode {

                /**
                 * A translation mode that simply copies the original frames which are available when translating frames of the instrumented method.
                 */
                COPY {
                    @Override
                    protected int copy(TypeDescription instrumentedType,
                                       MethodDescription instrumentedMethod,
                                       MethodDescription methodDescription,
                                       Object[] localVariable,
                                       Object[] translated) {
                        int length = instrumentedMethod.getParameters().size() + (instrumentedMethod.isStatic() ? 0 : 1);
                        System.arraycopy(localVariable, 0, translated, 0, length);
                        return length;
                    }
                },

                /**
                 * A translation mode for the entry advice that considers that the {@code this} reference might not be initialized for a constructor.
                 */
                ENTRY {
                    @Override
                    protected int copy(TypeDescription instrumentedType,
                                       MethodDescription instrumentedMethod,
                                       MethodDescription methodDescription,
                                       Object[] localVariable,
                                       Object[] translated) {
                        int index = 0;
                        if (!instrumentedMethod.isStatic()) {
                            translated[index++] = instrumentedMethod.isConstructor()
                                    ? Opcodes.UNINITIALIZED_THIS
                                    : toFrame(instrumentedType);
                        }
                        for (TypeDescription typeDescription : instrumentedMethod.getParameters().asTypeList().asErasures()) {
                            translated[index++] = toFrame(typeDescription);
                        }
                        return index;

                    }
                },

                /**
                 * A translation mode for an exit advice where the {@code this} reference is always initialized.
                 */
                EXIT {
                    @Override
                    protected int copy(TypeDescription instrumentedType,
                                       MethodDescription instrumentedMethod,
                                       MethodDescription methodDescription,
                                       Object[] localVariable,
                                       Object[] translated) {
                        int index = 0;
                        if (!instrumentedMethod.isStatic()) {
                            translated[index++] = toFrame(instrumentedType);
                        }
                        for (TypeDescription typeDescription : instrumentedMethod.getParameters().asTypeList().asErasures()) {
                            translated[index++] = toFrame(typeDescription);
                        }
                        return index;
                    }
                };

                /**
                 * Copies the fixed parameters of the instrumented method onto the operand stack.
                 *
                 * @param instrumentedType   The instrumented type.
                 * @param instrumentedMethod The instrumented method.
                 * @param methodDescription  The method for which a frame is created.
                 * @param localVariable      The original local variable array.
                 * @param translated         The array containing the translated frames.
                 * @return The amount of frames added to the translated frame array.
                 */
                protected abstract int copy(TypeDescription instrumentedType,
                                            MethodDescription instrumentedMethod,
                                            MethodDescription methodDescription,
                                            Object[] localVariable,
                                            Object[] translated);

                @Override
                public String toString() {
                    return "Advice.StackMapFrameHandler.Default.TranslationMode." + name();
                }
            }

            /**
             * A stack map frame handler for an advice method.
             */
            protected class ForAdvice implements StackMapFrameHandler.ForAdvice {

                /**
                 * The method description for which frames are translated.
                 */
                protected final MethodDescription.InDefinedShape adviceMethod;

                /**
                 * A list of intermediate types to be considered as part of the instrumented method's steady signature.
                 */
                protected final TypeList requiredTypes;

                /**
                 * The types that this method yields as a result.
                 */
                private final TypeList yieldedTypes;

                /**
                 * The translation mode to apply for this advice method. Should be either {@link TranslationMode#ENTRY} or {@link TranslationMode#EXIT}.
                 */
                protected final TranslationMode translationMode;

                /**
                 * Creates a new meta data handler for an advice method.
                 *
                 * @param adviceMethod    The method description for which frames are translated.
                 * @param requiredTypes   A list of expected types to be considered as part of the instrumented method's steady signature.
                 * @param yieldedTypes    The types that this method yields as a result.
                 * @param translationMode The translation mode to apply for this advice method. Should be
                 *                        either {@link TranslationMode#ENTRY} or {@link TranslationMode#EXIT}.
                 */
                protected ForAdvice(MethodDescription.InDefinedShape adviceMethod,
                                    TypeList requiredTypes,
                                    TypeList yieldedTypes,
                                    TranslationMode translationMode) {
                    this.adviceMethod = adviceMethod;
                    this.requiredTypes = requiredTypes;
                    this.yieldedTypes = yieldedTypes;
                    this.translationMode = translationMode;
                }

                @Override
                public void translateFrame(MethodVisitor methodVisitor,
                                           int type,
                                           int localVariableLength,
                                           Object[] localVariable,
                                           int stackSize,
                                           Object[] stack) {
                    Default.this.translateFrame(methodVisitor,
                            translationMode,
                            adviceMethod,
                            requiredTypes,
                            type,
                            localVariableLength,
                            localVariable,
                            stackSize,
                            stack);
                }

                @Override
                public void injectReturnFrame(MethodVisitor methodVisitor) {
                    if (!expandFrames && currentFrameDivergence == 0) {
                        if (yieldedTypes.isEmpty() || adviceMethod.getReturnType().represents(void.class)) {
                            methodVisitor.visitFrame(Opcodes.F_SAME, 0, EMPTY, 0, EMPTY);
                        } else {
                            methodVisitor.visitFrame(Opcodes.F_SAME1, 0, EMPTY, 1, new Object[]{toFrame(adviceMethod.getReturnType().asErasure())});
                        }
                    } else {
                        injectFullFrame(methodVisitor, requiredTypes, yieldedTypes.isEmpty() || adviceMethod.getReturnType().represents(void.class)
                                ? Collections.<TypeDescription>emptyList()
                                : Collections.singletonList(adviceMethod.getReturnType().asErasure()));
                    }
                }

                @Override
                public void injectExceptionFrame(MethodVisitor methodVisitor) {
                    if (!expandFrames && currentFrameDivergence == 0) {
                        methodVisitor.visitFrame(Opcodes.F_SAME1, 0, EMPTY, 1, new Object[]{Type.getInternalName(Throwable.class)});
                    } else {
                        injectFullFrame(methodVisitor, requiredTypes, Collections.singletonList(TypeDescription.THROWABLE));
                    }
                }

                @Override
                public void injectCompletionFrame(MethodVisitor methodVisitor, boolean secondary) {
                    if ((!expandFrames && currentFrameDivergence == 0 && yieldedTypes.size() < 4)) {
                        if (secondary || yieldedTypes.isEmpty()) {
                            methodVisitor.visitFrame(Opcodes.F_SAME, 0, EMPTY, 0, EMPTY);
                        } else {
                            Object[] local = new Object[yieldedTypes.size()];
                            int index = 0;
                            for (TypeDescription typeDescription : yieldedTypes) {
                                local[index++] = toFrame(typeDescription);
                            }
                            methodVisitor.visitFrame(Opcodes.F_APPEND, local.length, local, 0, EMPTY);
                        }
                    } else {
                        injectFullFrame(methodVisitor, CompoundList.of(requiredTypes, yieldedTypes), Collections.<TypeDescription>emptyList());
                    }
                }

                @Override
                public String toString() {
                    return "Advice.StackMapFrameHandler.Default.ForAdvice{" +
                            "adviceMethod=" + adviceMethod +
                            ", requiredTypes=" + requiredTypes +
                            ", yieldedTypes=" + yieldedTypes +
                            ", translationMode=" + translationMode +
                            '}';
                }
            }
        }
    }

    /**
     * A dispatcher for implementing advice.
     */
    protected interface Dispatcher {

        /**
         * Indicates that a method does not represent advice and does not need to be visited.
         */
        MethodVisitor IGNORE_METHOD = null;

        /**
         * Expresses that an annotation should not be visited.
         */
        AnnotationVisitor IGNORE_ANNOTATION = null;

        /**
         * Returns {@code true} if this dispatcher is alive.
         *
         * @return {@code true} if this dispatcher is alive.
         */
        boolean isAlive();

        /**
         * A dispatcher that is not yet resolved.
         */
        interface Unresolved extends Dispatcher {

            /**
             * Indicates that this dispatcher requires access to the class file declaring the advice method.
             *
             * @return {@code true} if this dispatcher requires access to the advice method's class file.
             */
            boolean isBinary();

            /**
             * Resolves this dispatcher as a dispatcher for entering a method.
             *
             * @param userFactories A list of custom factories for binding parameters of an advice method.
             * @param classReader   A class reader to query for a class file which might be {@code null} if this dispatcher is not binary.
             * @return This dispatcher as a dispatcher for entering a method.
             */
            Resolved.ForMethodEnter asMethodEnter(List<? extends OffsetMapping.Factory> userFactories,
                                                  ClassReader classReader);

            /**
             * Resolves this dispatcher as a dispatcher for exiting a method.
             *
             * @param userFactories A list of custom factories for binding parameters of an advice method.
             * @param classReader   A class reader to query for a class file which might be {@code null} if this dispatcher is not binary.
             * @param dispatcher    The dispatcher for entering a method.
             * @return This dispatcher as a dispatcher for exiting a method.
             */
            Resolved.ForMethodExit asMethodExitTo(List<? extends OffsetMapping.Factory> userFactories,
                                                  ClassReader classReader,
                                                  Resolved.ForMethodEnter dispatcher);
        }

        /**
         * Represents an offset mapping for an advice method to an alternative offset.
         */
        interface OffsetMapping {

            /**
             * Resolves an offset mapping to a given target offset.
             *
             * @param instrumentedType   The instrumented type.
             * @param instrumentedMethod The instrumented method for which the mapping is to be resolved.
             * @param context            The context in which the offset mapping is applied.
             * @return A suitable target mapping.
             */
            Target resolve(TypeDescription instrumentedType, MethodDescription instrumentedMethod, Context context);

            /**
             * A context for applying an {@link OffsetMapping}.
             */
            interface Context {

                /**
                 * Returns {@code true} if the advice is applied on a fully initialized instance, i.e. describes if the {@code this}
                 * instance is available or still uninitialized during calling the advice.
                 *
                 * @return {@code true} if the advice is applied onto a fully initialized method.
                 */
                boolean isInitialized();

                /**
                 * Returns the padding before writing additional values that this context applies.
                 *
                 * @return The required padding for this context.
                 */
                int getPadding();

                /**
                 * A context for an offset mapping describing a method entry.
                 */
                enum ForMethodEntry implements Context {

                    /**
                     * Describes a context for a method entry that is not a constructor.
                     */
                    INITIALIZED(true),

                    /**
                     * Describes a context for a method entry that is a constructor.
                     */
                    NON_INITIALIZED(false);

                    /**
                     * Resolves an appropriate method entry context for the supplied instrumented method.
                     *
                     * @param instrumentedMethod The instrumented method.
                     * @return An appropriate context.
                     */
                    protected static Context of(MethodDescription instrumentedMethod) {
                        return instrumentedMethod.isConstructor()
                                ? NON_INITIALIZED
                                : INITIALIZED;
                    }

                    /**
                     * {@code true} if the method is no constructor, i.e. is invoked for an initialized instance upon entry.
                     */
                    private final boolean initialized;

                    /**
                     * Creates a new context for a method entry.
                     *
                     * @param initialized {@code true} if the method is no constructor, i.e. is invoked for an initialized instance upon entry.
                     */
                    ForMethodEntry(boolean initialized) {
                        this.initialized = initialized;
                    }

                    @Override
                    public boolean isInitialized() {
                        return initialized;
                    }

                    @Override
                    public int getPadding() {
                        return StackSize.ZERO.getSize();
                    }

                    @Override
                    public String toString() {
                        return "Advice.Dispatcher.OffsetMapping.Context.ForMethodEntry." + name();
                    }
                }

                /**
                 * A context for an offset mapping describing a method exit.
                 */
                enum ForMethodExit implements Context {

                    /**
                     * A method exit with a zero sized padding.
                     */
                    ZERO(StackSize.ZERO),

                    /**
                     * A method exit with a single slot padding.
                     */
                    SINGLE(StackSize.SINGLE),

                    /**
                     * A method exit with a double slot padding.
                     */
                    DOUBLE(StackSize.DOUBLE);

                    /**
                     * The padding implied by this method exit.
                     */
                    private final StackSize stackSize;

                    /**
                     * Creates a new context for a method exit.
                     *
                     * @param stackSize The padding implied by this method exit.
                     */
                    ForMethodExit(StackSize stackSize) {
                        this.stackSize = stackSize;
                    }

                    /**
                     * Resolves an appropriate method exit context for the supplied entry method type.
                     *
                     * @param typeDescription The type that is returned by the enter method.
                     * @return An appropriate context for the supplied entry method type.
                     */
                    protected static Context of(TypeDescription typeDescription) {
                        switch (typeDescription.getStackSize()) {
                            case ZERO:
                                return ZERO;
                            case SINGLE:
                                return SINGLE;
                            case DOUBLE:
                                return DOUBLE;
                            default:
                                throw new IllegalStateException("Unknown stack size: " + typeDescription);
                        }
                    }

                    @Override
                    public boolean isInitialized() {
                        return true;
                    }

                    @Override
                    public int getPadding() {
                        return stackSize.getSize();
                    }

                    @Override
                    public String toString() {
                        return "Advice.Dispatcher.OffsetMapping.Context.ForMethodExit." + name();
                    }
                }
            }

            /**
             * A target offset of an offset mapping.
             */
            interface Target {

                StackManipulation resolveRead();

                StackManipulation resolveWrite();

                StackManipulation resolveIncrement(int value);

                enum ForThis implements Target {

                    READ_ONLY {
                        @Override
                        public StackManipulation resolveWrite() {
                            throw new IllegalStateException("Cannot write to read-only this reference");
                        }
                    },

                    READ_WRITE {
                        @Override
                        public StackManipulation resolveWrite() {
                            return MethodVariableAccess.REFERENCE.storeAt(0);
                        }
                    };

                    @Override
                    public StackManipulation resolveRead() {
                        return MethodVariableAccess.REFERENCE.loadFrom(0);
                    }

                    @Override
                    public StackManipulation resolveIncrement(int value) {
                        throw new IllegalStateException("Cannot increment this variable");
                    }
                }

                abstract class ForDefaultValue implements Target {

                    protected final TypeDefinition typeDefinition;

                    protected ForDefaultValue(TypeDefinition typeDefinition) {
                        this.typeDefinition = typeDefinition;
                    }

                    @Override
                    public StackManipulation resolveRead() {
                        return DefaultValue.of(typeDefinition);
                    }

                    protected static class ReadOnly extends ForDefaultValue {

                        protected ReadOnly(TypeDefinition typeDefinition) {
                            super(typeDefinition);
                        }

                        @Override
                        public StackManipulation resolveWrite() {
                            throw new IllegalStateException();
                        }

                        @Override
                        public StackManipulation resolveIncrement(int value) {
                            throw new IllegalStateException();
                        }
                    }

                    protected static class ReadWrite extends ForDefaultValue {

                        protected ReadWrite(TypeDefinition typeDefinition) {
                            super(typeDefinition);
                        }

                        @Override
                        public StackManipulation resolveWrite() {
                            return Removal.pop(typeDefinition);
                        }

                        @Override
                        public StackManipulation resolveIncrement(int value) {
                            return StackManipulation.Trivial.INSTANCE;
                        }
                    }
                }

                abstract class ForVariable implements Target {

                    protected final TypeDefinition typeDefinition;

                    protected final int offset;

                    protected ForVariable(TypeDefinition typeDefinition, int offset) {
                        this.typeDefinition = typeDefinition;
                        this.offset = offset;
                    }

                    @Override
                    public StackManipulation resolveRead() {
                        return MethodVariableAccess.of(typeDefinition).loadFrom(offset);
                    }

                    protected static class ReadOnly extends ForVariable {

                        protected ReadOnly(TypeDefinition typeDefinition, int offset) {
                            super(typeDefinition, offset);
                        }

                        protected static Target of(ParameterDescription parameterDescription) {
                            return new ReadOnly(parameterDescription.getType(), parameterDescription.getOffset());
                        }

                        @Override
                        public StackManipulation resolveWrite() {
                            throw new IllegalStateException("Cannot write to read-only parameter " + typeDefinition + " at " + offset);
                        }

                        @Override
                        public StackManipulation resolveIncrement(int value) {
                            throw new IllegalStateException("Cannot write to read-only variable " + typeDefinition + " at " + offset);
                        }
                    }

                    protected static class ReadWrite extends ForVariable {

                        protected ReadWrite(TypeDefinition typeDefinition, int offet) {
                            super(typeDefinition, offet);
                        }

                        protected static Target of(ParameterDescription parameterDescription) {
                            return new ReadWrite(parameterDescription.getType(), parameterDescription.getOffset());
                        }

                        @Override
                        public StackManipulation resolveWrite() {
                            return MethodVariableAccess.of(typeDefinition).storeAt(offset);
                        }

                        @Override
                        public StackManipulation resolveIncrement(int value) {
                            return MethodVariableAccess.of(typeDefinition).increment(offset, value);
                        }
                    }
                }

                abstract class ForField implements Target {

                    protected final FieldDescription fieldDescription;

                    protected ForField(FieldDescription fieldDescription) {
                        this.fieldDescription = fieldDescription;
                    }

                    @Override
                    public StackManipulation resolveRead() {
                        return new StackManipulation.Compound(fieldDescription.isStatic()
                                ? StackManipulation.Trivial.INSTANCE
                                : MethodVariableAccess.REFERENCE.loadFrom(0), FieldAccess.forField(fieldDescription).getter());
                    }

                    static class ReadOnly extends ForField {

                        public ReadOnly(FieldDescription fieldDescription) {
                            super(fieldDescription);
                        }

                        @Override
                        public StackManipulation resolveWrite() {
                            return null;
                        }

                        @Override
                        public StackManipulation resolveIncrement(int value) {
                            return null;
                        }
                    }

                    static class ReadWrite extends ForField {

                        protected ReadWrite(FieldDescription fieldDescription) {
                            super(fieldDescription);
                        }

                        @Override
                        public StackManipulation resolveWrite() {
                            StackManipulation preparation;
                            if (fieldDescription.isStatic()) {
                                preparation = StackManipulation.Trivial.INSTANCE;
                            } else {
                                preparation = new StackManipulation.Compound(
                                        MethodVariableAccess.REFERENCE.loadFrom(0),
                                        Duplication.SINGLE.flipOver(fieldDescription.getType())
                                );
                            }
                            return new StackManipulation.Compound(preparation, FieldAccess.forField(fieldDescription).putter());
                        }

                        @Override
                        public StackManipulation resolveIncrement(int value) {
                            return new StackManipulation.Compound(
                                    resolveRead(),
                                    IntegerConstant.forValue(value),
                                    Addition.INTEGER,
                                    resolveWrite()
                            );
                        }
                    }
                }

                class ForStackManipulation implements Target {

                    private final StackManipulation stackManipulation;

                    protected ForStackManipulation(StackManipulation stackManipulation) {
                        this.stackManipulation = stackManipulation;
                    }

                    protected static Target of(MethodDescription.InDefinedShape methodDescription) {
                        return new ForStackManipulation(MethodConstant.forMethod(methodDescription));
                    }

                    protected static Target of(TypeDescription typeDescription) {
                        return new ForStackManipulation(ClassConstant.of(typeDescription));
                    }

                    protected static Target of(String value) {
                        return new ForStackManipulation(new TextConstant(value));
                    }

                    protected static Target of(Object value) {
                        if (value instanceof Boolean) {
                            return new ForStackManipulation(IntegerConstant.forValue((Boolean) value));
                        } else if (value instanceof Byte) {
                            return new ForStackManipulation(IntegerConstant.forValue((Byte) value));
                        } else if (value instanceof Short) {
                            return new ForStackManipulation(IntegerConstant.forValue((Short) value));
                        } else if (value instanceof Character) {
                            return new ForStackManipulation(IntegerConstant.forValue((Character) value));
                        } else if (value instanceof Integer) {
                            return new ForStackManipulation(IntegerConstant.forValue((Integer) value));
                        } else if (value instanceof Long) {
                            return new ForStackManipulation(LongConstant.forValue((Long) value));
                        } else if (value instanceof Float) {
                            return new ForStackManipulation(FloatConstant.forValue((Float) value));
                        } else if (value instanceof Double) {
                            return new ForStackManipulation(DoubleConstant.forValue((Double) value));
                        } else if (value instanceof String) {
                            return new ForStackManipulation(new TextConstant((String) value));
                        } else {
                            throw new IllegalArgumentException();
                        }
                    }

                    @Override
                    public StackManipulation resolveRead() {
                        return stackManipulation;
                    }

                    @Override
                    public StackManipulation resolveWrite() {
                        throw new IllegalStateException("Cannot write to constant value: " + stackManipulation);
                    }

                    @Override
                    public StackManipulation resolveIncrement(int value) {
                        throw new IllegalStateException("Cannot write to constant value: " + stackManipulation);
                    }
                }
            }

            /**
             * Represents a factory for creating a {@link OffsetMapping} for a given parameter.
             */
            interface Factory {

                /**
                 * Indicates that an offset mapping is undefined.
                 */
                OffsetMapping UNDEFINED = null;

                /**
                 * Creates a new offset mapping for the supplied parameter if possible.
                 *
                 * @param parameterDescription The parameter description for which to resolve an offset mapping.
                 * @return A resolved offset mapping or {@code null} if no mapping can be resolved for this parameter.
                 */
                OffsetMapping make(ParameterDescription.InDefinedShape parameterDescription);
            }

            /**
             * An offset mapping for a given parameter of the instrumented method.
             */
            class ForParameter implements OffsetMapping {

                /**
                 * The index of the parameter.
                 */
                private final int index;

                /**
                 * Determines if the parameter is to be treated as read-only.
                 */
                private final boolean readOnly;

                /**
                 * The type expected by the advice method.
                 */
                private final TypeDescription targetType;

                /**
                 * Creates a new offset mapping for a parameter.
                 *
                 * @param argument   The annotation for which the mapping is to be created.
                 * @param targetType Determines if the parameter is to be treated as read-only.
                 */
                protected ForParameter(Argument argument, TypeDescription targetType) {
                    this(argument.value(), argument.readOnly(), targetType);
                }

                /**
                 * Creates a new offset mapping for a parameter of the instrumented method.
                 *
                 * @param index      The index of the parameter.
                 * @param readOnly   Determines if the parameter is to be treated as read-only.
                 * @param targetType The type expected by the advice method.
                 */
                protected ForParameter(int index, boolean readOnly, TypeDescription targetType) {
                    this.index = index;
                    this.readOnly = readOnly;
                    this.targetType = targetType;
                }

                @Override
                public Target resolve(TypeDescription instrumentedType, MethodDescription instrumentedMethod, Context context) {
                    ParameterList<?> parameters = instrumentedMethod.getParameters();
                    if (parameters.size() <= index) {
                        throw new IllegalStateException(instrumentedMethod + " does not define an index " + index);
                    } else if (!readOnly && !parameters.get(index).getType().asErasure().equals(targetType)) {
                        throw new IllegalStateException("read-only " + targetType + " is not equal to type of " + parameters.get(index));
                    } else if (readOnly && !parameters.get(index).getType().asErasure().isAssignableTo(targetType)) {
                        throw new IllegalStateException(targetType + " is not assignable to " + parameters.get(index));
                    }
                    return readOnly
                            ? Target.ForVariable.ReadOnly.of(parameters.get(index))
                            : Target.ForVariable.ReadWrite.of(parameters.get(index));
                }

                @Override
                public boolean equals(Object other) {
                    if (this == other) return true;
                    if (other == null || getClass() != other.getClass()) return false;
                    ForParameter that = (ForParameter) other;
                    return index == that.index
                            && readOnly == that.readOnly
                            && targetType.equals(that.targetType);
                }

                @Override
                public int hashCode() {
                    int result = index;
                    result = 31 * result + (readOnly ? 1 : 0);
                    result = 31 * result + targetType.hashCode();
                    return result;
                }

                @Override
                public String toString() {
                    return "Advice.Dispatcher.OffsetMapping.ForParameter{" +
                            "index=" + index +
                            ", readOnly=" + readOnly +
                            ", targetType=" + targetType +
                            '}';
                }

                /**
                 * A factory for creating a {@link ForParameter} offset mapping.
                 */
                protected enum Factory implements OffsetMapping.Factory {

                    /**
                     * A factory that does not allow writing to the mapped parameter.
                     */
                    READ_ONLY(true),

                    /**
                     * A factory that allows writing to the mapped parameter.
                     */
                    READ_WRITE(false);

                    /**
                     * {@code true} if the parameter is read-only.
                     */
                    private final boolean readOnly;

                    /**
                     * Creates a new factory.
                     *
                     * @param readOnly {@code true} if the parameter is read-only.
                     */
                    Factory(boolean readOnly) {
                        this.readOnly = readOnly;
                    }

                    @Override
                    public OffsetMapping make(ParameterDescription.InDefinedShape parameterDescription) {
                        AnnotationDescription.Loadable<Argument> annotation = parameterDescription.getDeclaredAnnotations().ofType(Argument.class);
                        if (annotation == null) {
                            return UNDEFINED;
                        } else if (readOnly && !annotation.loadSilent().readOnly()) {
                            throw new IllegalStateException("Cannot define writable field access for " + parameterDescription);
                        } else {
                            return new ForParameter(annotation.loadSilent(), parameterDescription.getType().asErasure());
                        }
                    }

                    @Override
                    public String toString() {
                        return "Advice.Dispatcher.OffsetMapping.ForParameter.Factory." + name();
                    }
                }
            }

            /**
             * An offset mapping that provides access to the {@code this} reference of the instrumented method.
             */
            class ForThisReference implements OffsetMapping {

                /**
                 * Determines if the parameter is to be treated as read-only.
                 */
                private final boolean readOnly;

                /**
                 * {@code true} if the parameter should be bound to {@code null} if the instrumented method is static.
                 */
                private final boolean optional;

                /**
                 * The type that the advice method expects for the {@code this} reference.
                 */
                private final TypeDescription targetType;

                /**
                 * Creates a new offset mapping for a {@code this} reference.
                 *
                 * @param readOnly   Determines if the parameter is to be treated as read-only.
                 * @param optional   {@code true} if the parameter should be bound to {@code null} if the instrumented method is static.
                 * @param targetType The type that the advice method expects for the {@code this} reference.
                 */
                protected ForThisReference(boolean readOnly, boolean optional, TypeDescription targetType) {
                    this.readOnly = readOnly;
                    this.optional = optional;
                    this.targetType = targetType;
                }

                @Override
                public Target resolve(TypeDescription instrumentedType, MethodDescription instrumentedMethod, Context context) {
                    if (!readOnly && !instrumentedType.equals(targetType)) {
                        throw new IllegalStateException("Declaring type of " + instrumentedMethod + " is not equal to read-only " + targetType);
                    } else if (readOnly && !instrumentedType.isAssignableTo(targetType)) {
                        throw new IllegalStateException("Declaring type of " + instrumentedMethod + " is not assignable to " + targetType);
                    } else if (instrumentedMethod.isStatic() && optional) {
                        return readOnly
                                ? new Target.ForDefaultValue.ReadOnly(instrumentedType.getDeclaringType())
                                : new Target.ForDefaultValue.ReadWrite(instrumentedType.getDeclaringType());
                    } else if (instrumentedMethod.isStatic() && !optional) {
                        throw new IllegalStateException("Cannot map this reference for static method " + instrumentedMethod);
                    } else if (!context.isInitialized()) {
                        throw new IllegalStateException("Cannot access this reference before calling constructor: " + instrumentedMethod);
                    }
                    return readOnly
                            ? Target.ForThis.READ_ONLY
                            : Target.ForThis.READ_WRITE;
                }

                @Override
                public boolean equals(Object other) {
                    if (this == other) return true;
                    if (other == null || getClass() != other.getClass()) return false;
                    ForThisReference that = (ForThisReference) other;
                    return readOnly == that.readOnly
                            && optional == that.optional
                            && targetType.equals(that.targetType);
                }

                @Override
                public int hashCode() {
                    int result = (readOnly ? 1 : 0);
                    result = 31 * result + (readOnly ? 1 : 0);
                    result = 31 * result + targetType.hashCode();
                    return result;
                }

                @Override
                public String toString() {
                    return "Advice.Dispatcher.OffsetMapping.ForThisReference{" +
                            "readOnly=" + readOnly +
                            ", optional=" + optional +
                            ", targetType=" + targetType +
                            '}';
                }

                /**
                 * A factory for creating a {@link ForThisReference} offset mapping.
                 */
                protected enum Factory implements OffsetMapping.Factory {

                    /**
                     * A factory that does not allow writing to the mapped parameter.
                     */
                    READ_ONLY(true),

                    /**
                     * A factory that allows writing to the mapped parameter.
                     */
                    READ_WRITE(false);

                    /**
                     * {@code true} if the parameter is read-only.
                     */
                    private final boolean readOnly;

                    /**
                     * Creates a new factory.
                     *
                     * @param readOnly {@code true} if the parameter is read-only.
                     */
                    Factory(boolean readOnly) {
                        this.readOnly = readOnly;
                    }

                    @Override
                    public OffsetMapping make(ParameterDescription.InDefinedShape parameterDescription) {
                        AnnotationDescription.Loadable<This> annotation = parameterDescription.getDeclaredAnnotations().ofType(This.class);
                        if (annotation == null) {
                            return UNDEFINED;
                        } else if (readOnly && !annotation.loadSilent().readOnly()) {
                            throw new IllegalStateException("Cannot write to this reference for " + parameterDescription + " in read-only context");
                        } else {
                            return new ForThisReference(annotation.loadSilent().readOnly(),
                                    annotation.loadSilent().optional(),
                                    parameterDescription.getType().asErasure());
                        }
                    }

                    @Override
                    public String toString() {
                        return "Advice.Dispatcher.OffsetMapping.ForThisReference.Factory." + name();
                    }
                }
            }

            /**
             * Maps the declaring type of the instrumented method.
             */
            enum ForInstrumentedType implements OffsetMapping {

                /**
                 * The singleton instance.
                 */
                INSTANCE;

                @Override
                public Target resolve(TypeDescription instrumentedType, MethodDescription instrumentedMethod, Context context) {
                    return Target.ForStackManipulation.of(instrumentedType);
                }

                @Override
                public String toString() {
                    return "Advice.Dispatcher.OffsetMapping.ForInstrumentedType." + name();
                }
            }

            /**
             * Maps a constant representing the instrumented method.
             */
            enum ForInstrumentedMethod implements OffsetMapping {

                /**
                 * A constant that must be a {@link Method} instance.
                 */
                METHOD {
                    @Override
                    protected boolean isRepresentable(MethodDescription instrumentedMethod) {
                        return instrumentedMethod.isMethod();
                    }
                },

                /**
                 * A constant that must be a {@link Constructor} instance.
                 */
                CONSTRUCTOR {
                    @Override
                    protected boolean isRepresentable(MethodDescription instrumentedMethod) {
                        return instrumentedMethod.isConstructor();
                    }
                },

                /**
                 * A constant that must be a {@code java.lang.reflect.Executable} instance.
                 */
                EXECUTABLE {
                    @Override
                    protected boolean isRepresentable(MethodDescription instrumentedMethod) {
                        return false;
                    }
                };

                @Override
                public Target resolve(TypeDescription instrumentedType, MethodDescription instrumentedMethod, Context context) {
                    if (!isRepresentable(instrumentedMethod)) {
                        throw new IllegalStateException("Cannot represent " + instrumentedMethod + " as given method constant");
                    }
                    return Target.ForStackManipulation.of(instrumentedMethod.asDefined());
                }

                /**
                 * Checks if the supplied method is representable for the assigned offset mapping.
                 *
                 * @param instrumentedMethod The instrumented method to represent.
                 * @return {@code true} if this method is representable.
                 */
                protected abstract boolean isRepresentable(MethodDescription instrumentedMethod);

                @Override
                public String toString() {
                    return "Advice.Dispatcher.OffsetMapping.ForInstrumentedMethod." + name();
                }
            }

            /**
             * An offset mapping for a field.
             */
            abstract class ForField implements OffsetMapping {

                /**
                 * The {@link FieldValue#value()} method.
                 */
                private static final MethodDescription.InDefinedShape VALUE;

                /**
                 * The {@link FieldValue#declaringType()}} method.
                 */
                private static final MethodDescription.InDefinedShape DECLARING_TYPE;

                /**
                 * The {@link FieldValue#readOnly()}} method.
                 */
                private static final MethodDescription.InDefinedShape READ_ONLY;

                static {
                    MethodList<MethodDescription.InDefinedShape> methods = new TypeDescription.ForLoadedType(FieldValue.class).getDeclaredMethods();
                    VALUE = methods.filter(named("value")).getOnly();
                    DECLARING_TYPE = methods.filter(named("declaringType")).getOnly();
                    READ_ONLY = methods.filter(named("readOnly")).getOnly();
                }

                /**
                 * The name of the field.
                 */
                protected final String name;

                /**
                 * The expected type that the field can be assigned to.
                 */
                protected final TypeDescription targetType;

                /**
                 * {@code true} if this mapping is read-only.
                 */
                protected final boolean readOnly;

                /**
                 * Creates an offset mapping for a field.
                 *
                 * @param name       The name of the field.
                 * @param targetType The expected type that the field can be assigned to.
                 * @param readOnly   {@code true} if this mapping is read-only.
                 */
                protected ForField(String name, TypeDescription targetType, boolean readOnly) {
                    this.name = name;
                    this.targetType = targetType;
                    this.readOnly = readOnly;
                }

                @Override
                public Target resolve(TypeDescription instrumentedType, MethodDescription instrumentedMethod, Context context) {
                    FieldLocator.Resolution resolution = fieldLocator(instrumentedType).locate(name);
                    if (!resolution.isResolved()) {
                        throw new IllegalStateException("Cannot locate field named " + name + " for " + instrumentedMethod);
                    } else if (readOnly && !resolution.getField().asDefined().getType().asErasure().isAssignableTo(targetType)) {
                        throw new IllegalStateException("Cannot assign type of read-only field " + resolution.getField() + " to " + targetType);
                    } else if (!readOnly && !resolution.getField().asDefined().getType().asErasure().equals(targetType)) {
                        throw new IllegalStateException("Type of field " + resolution.getField() + " is not equal to " + targetType);
                    } else if (!resolution.getField().isStatic() && instrumentedMethod.isStatic()) {
                        throw new IllegalStateException("Cannot read non-static field " + resolution.getField() + " from static method " + instrumentedMethod);
                    } else if (!context.isInitialized() && !resolution.getField().isStatic()) {
                        throw new IllegalStateException("Cannot access non-static field before calling constructor: " + instrumentedMethod);
                    }
                    return readOnly
                            ? new Target.ForField.ReadOnly(resolution.getField().asDefined())
                            : new Target.ForField.ReadWrite(resolution.getField().asDefined());
                }

                @Override
                public boolean equals(Object other) {
                    if (this == other) return true;
                    if (other == null || getClass() != other.getClass()) return false;
                    ForField forField = (ForField) other;
                    return name.equals(forField.name) && targetType.equals(forField.targetType) && readOnly == forField.readOnly;
                }

                @Override
                public int hashCode() {
                    int result = name.hashCode();
                    result = 31 * result + targetType.hashCode();
                    result = 31 * result + (readOnly ? 1 : 0);
                    return result;
                }

                /**
                 * Returns a field locator for this instance.
                 *
                 * @param instrumentedType The instrumented type.
                 * @return An appropriate field locator.
                 */
                protected abstract FieldLocator fieldLocator(TypeDescription instrumentedType);

                /**
                 * An offset mapping for a field with an implicit declaring type.
                 */
                protected static class WithImplicitType extends ForField {

                    /**
                     * Creates an offset mapping for a field with an implicit declaring type.
                     *
                     * @param name       The name of the field.
                     * @param targetType The expected type that the field can be assigned to.
                     * @param readOnly   {@code true} if the field is read-only.
                     */
                    protected WithImplicitType(String name, TypeDescription targetType, boolean readOnly) {
                        super(name, targetType, readOnly);
                    }

                    @Override
                    protected FieldLocator fieldLocator(TypeDescription instrumentedType) {
                        return new FieldLocator.ForClassHierarchy(instrumentedType);
                    }

                    @Override
                    public String toString() {
                        return "Advice.Dispatcher.OffsetMapping.ForField.WithImplicitType{" +
                                "name=" + name +
                                ", targetType=" + targetType +
                                '}';
                    }
                }

                /**
                 * An offset mapping for a field with an explicit declaring type.
                 */
                protected static class WithExplicitType extends ForField {

                    /**
                     * The type declaring the field.
                     */
                    private final TypeDescription explicitType;

                    /**
                     * Creates an offset mapping for a field with an explicit declaring type.
                     *
                     * @param name        The name of the field.
                     * @param targetType  The expected type that the field can be assigned to.
                     * @param locatedType The type declaring the field.
                     * @param readOnly    {@code true} if the field is read-only.
                     */
                    protected WithExplicitType(String name, TypeDescription targetType, TypeDescription locatedType, boolean readOnly) {
                        super(name, targetType, readOnly);
                        this.explicitType = locatedType;
                    }

                    @Override
                    protected FieldLocator fieldLocator(TypeDescription instrumentedType) {
                        if (!instrumentedType.isAssignableTo(explicitType)) {
                            throw new IllegalStateException(explicitType + " is no super type of " + instrumentedType);
                        }
                        return new FieldLocator.ForExactType(explicitType);
                    }

                    @Override
                    public boolean equals(Object other) {
                        if (this == other) return true;
                        if (other == null || getClass() != other.getClass()) return false;
                        if (!super.equals(other)) return false;
                        WithExplicitType that = (WithExplicitType) other;
                        return explicitType.equals(that.explicitType);
                    }

                    @Override
                    public int hashCode() {
                        int result = super.hashCode();
                        result = 31 * result + explicitType.hashCode();
                        return result;
                    }

                    @Override
                    public String toString() {
                        return "Advice.Dispatcher.OffsetMapping.ForField.WithExplicitType{" +
                                "name=" + name +
                                ", targetType=" + targetType +
                                ", explicitType=" + explicitType +
                                '}';
                    }
                }

                /**
                 * A factory for a {@link ForField} offset mapping.
                 */
                protected enum Factory implements OffsetMapping.Factory {

                    /**
                     * A factory that does not allow writing to the mapped parameter.
                     */
                    READ_ONLY(true),

                    /**
                     * A factory that allows writing to the mapped parameter.
                     */
                    READ_WRITE(false);

                    /**
                     * {@code true} if the parameter is read-only.
                     */
                    private final boolean readOnly;

                    /**
                     * Creates a new factory.
                     *
                     * @param readOnly {@code true} if the parameter is read-only.
                     */
                    Factory(boolean readOnly) {
                        this.readOnly = readOnly;
                    }

                    @Override
                    public OffsetMapping make(ParameterDescription.InDefinedShape parameterDescription) {
                        AnnotationDescription annotation = parameterDescription.getDeclaredAnnotations().ofType(FieldValue.class);
                        if (annotation == null) {
                            return UNDEFINED;
                        } else if (readOnly && !annotation.getValue(ForField.READ_ONLY).resolve(Boolean.class)) {
                            throw new IllegalStateException("Cannot write to field for " + parameterDescription + " in read-only context");
                        } else {
                            TypeDescription declaringType = annotation.getValue(DECLARING_TYPE).resolve(TypeDescription.class);
                            String name = annotation.getValue(VALUE).resolve(String.class);
                            TypeDescription targetType = parameterDescription.getType().asErasure();
                            return declaringType.represents(void.class)
                                    ? new WithImplicitType(name, targetType, annotation.getValue(ForField.READ_ONLY).resolve(Boolean.class))
                                    : new WithExplicitType(name, targetType, declaringType, annotation.getValue(ForField.READ_ONLY).resolve(Boolean.class));
                        }
                    }

                    @Override
                    public String toString() {
                        return "Advice.Dispatcher.OffsetMapping.ForField.Factory." + name();
                    }
                }
            }

            /**
             * An offset mapping for the {@link Advice.Origin} annotation.
             */
            class ForOrigin implements OffsetMapping {

                /**
                 * The delimiter character.
                 */
                private static final char DELIMITER = '#';

                /**
                 * The escape character.
                 */
                private static final char ESCAPE = '\\';

                /**
                 * The renderers to apply.
                 */
                private final List<Renderer> renderers;

                /**
                 * Creates a new offset mapping for an origin value.
                 *
                 * @param renderers The renderers to apply.
                 */
                protected ForOrigin(List<Renderer> renderers) {
                    this.renderers = renderers;
                }

                /**
                 * Parses a pattern of an origin annotation.
                 *
                 * @param pattern The supplied pattern.
                 * @return An appropriate offset mapping.
                 */
                protected static OffsetMapping parse(String pattern) {
                    if (pattern.equals(Origin.DEFAULT)) {
                        return new ForOrigin(Collections.<Renderer>singletonList(Renderer.ForStringRepresentation.INSTANCE));
                    } else {
                        List<Renderer> renderers = new ArrayList<Renderer>(pattern.length());
                        int from = 0;
                        for (int to = pattern.indexOf(DELIMITER); to != -1; to = pattern.indexOf(DELIMITER, from)) {
                            if (to != 0 && pattern.charAt(to - 1) == ESCAPE && (to == 1 || pattern.charAt(to - 2) != ESCAPE)) {
                                renderers.add(new Renderer.ForConstantValue(pattern.substring(from, Math.max(0, to - 1)) + DELIMITER));
                                from = to + 1;
                                continue;
                            } else if (pattern.length() == to + 1) {
                                throw new IllegalStateException("Missing sort descriptor for " + pattern + " at index " + to);
                            }
                            renderers.add(new Renderer.ForConstantValue(pattern.substring(from, to).replace("" + ESCAPE + ESCAPE, "" + ESCAPE)));
                            switch (pattern.charAt(to + 1)) {
                                case Renderer.ForMethodName.SYMBOL:
                                    renderers.add(Renderer.ForMethodName.INSTANCE);
                                    break;
                                case Renderer.ForTypeName.SYMBOL:
                                    renderers.add(Renderer.ForTypeName.INSTANCE);
                                    break;
                                case Renderer.ForDescriptor.SYMBOL:
                                    renderers.add(Renderer.ForDescriptor.INSTANCE);
                                    break;
                                case Renderer.ForReturnTypeName.SYMBOL:
                                    renderers.add(Renderer.ForReturnTypeName.INSTANCE);
                                    break;
                                case Renderer.ForJavaSignature.SYMBOL:
                                    renderers.add(Renderer.ForJavaSignature.INSTANCE);
                                    break;
                                default:
                                    throw new IllegalStateException("Illegal sort descriptor " + pattern.charAt(to + 1) + " for " + pattern);
                            }
                            from = to + 2;
                        }
                        renderers.add(new Renderer.ForConstantValue(pattern.substring(from)));
                        return new ForOrigin(renderers);
                    }
                }

                @Override
                public Target resolve(TypeDescription instrumentedType, MethodDescription instrumentedMethod, Context context) {
                    StringBuilder stringBuilder = new StringBuilder();
                    for (Renderer renderer : renderers) {
                        stringBuilder.append(renderer.apply(instrumentedType, instrumentedMethod));
                    }
                    return Target.ForStackManipulation.of(stringBuilder.toString());
                }

                @Override
                public boolean equals(Object other) {
                    if (this == other) return true;
                    if (other == null || getClass() != other.getClass()) return false;
                    ForOrigin forOrigin = (ForOrigin) other;
                    return renderers.equals(forOrigin.renderers);
                }

                @Override
                public int hashCode() {
                    return renderers.hashCode();
                }

                @Override
                public String toString() {
                    return "Advice.Dispatcher.OffsetMapping.ForOrigin{" +
                            "renderers=" + renderers +
                            '}';
                }

                /**
                 * A renderer for an origin pattern element.
                 */
                protected interface Renderer {

                    /**
                     * Returns a string representation for this renderer.
                     *
                     * @param instrumentedType   The instrumented type.
                     * @param instrumentedMethod The instrumented method.
                     * @return The string representation.
                     */
                    String apply(TypeDescription instrumentedType, MethodDescription instrumentedMethod);

                    /**
                     * A renderer for a method's internal name.
                     */
                    enum ForMethodName implements Renderer {

                        /**
                         * The singleton instance.
                         */
                        INSTANCE;

                        /**
                         * The method name symbol.
                         */
                        public static final char SYMBOL = 'm';

                        @Override
                        public String apply(TypeDescription instrumentedType, MethodDescription instrumentedMethod) {
                            return instrumentedMethod.getInternalName();
                        }

                        @Override
                        public String toString() {
                            return "Advice.Dispatcher.OffsetMapping.ForOrigin.Renderer.ForMethodName." + name();
                        }
                    }

                    /**
                     * A renderer for a method declaring type's binary name.
                     */
                    enum ForTypeName implements Renderer {

                        /**
                         * The singleton instance.
                         */
                        INSTANCE;

                        /**
                         * The type name symbol.
                         */
                        public static final char SYMBOL = 't';

                        @Override
                        public String apply(TypeDescription instrumentedType, MethodDescription instrumentedMethod) {
                            return instrumentedType.getName();
                        }

                        @Override
                        public String toString() {
                            return "Advice.Dispatcher.OffsetMapping.ForOrigin.Renderer.ForTypeName." + name();
                        }
                    }

                    /**
                     * A renderer for a method descriptor.
                     */
                    enum ForDescriptor implements Renderer {

                        /**
                         * The singleton instance.
                         */
                        INSTANCE;

                        /**
                         * The descriptor symbol.
                         */
                        public static final char SYMBOL = 'd';

                        @Override
                        public String apply(TypeDescription instrumentedType, MethodDescription instrumentedMethod) {
                            return instrumentedMethod.getDescriptor();
                        }

                        @Override
                        public String toString() {
                            return "Advice.Dispatcher.OffsetMapping.ForOrigin.Renderer.ForDescriptor." + name();
                        }
                    }

                    /**
                     * A renderer for a method's Java signature in binary form.
                     */
                    enum ForJavaSignature implements Renderer {

                        /**
                         * The singleton instance.
                         */
                        INSTANCE;

                        /**
                         * The signature symbol.
                         */
                        public static final char SYMBOL = 's';

                        @Override
                        public String apply(TypeDescription instrumentedType, MethodDescription instrumentedMethod) {
                            StringBuilder stringBuilder = new StringBuilder("(");
                            boolean comma = false;
                            for (TypeDescription typeDescription : instrumentedMethod.getParameters().asTypeList().asErasures()) {
                                if (comma) {
                                    stringBuilder.append(',');
                                } else {
                                    comma = true;
                                }
                                stringBuilder.append(typeDescription.getName());
                            }
                            return stringBuilder.append(')').toString();
                        }

                        @Override
                        public String toString() {
                            return "Advice.Dispatcher.OffsetMapping.ForOrigin.Renderer.ForJavaSignature." + name();
                        }
                    }

                    /**
                     * A renderer for a method's return type in binary form.
                     */
                    enum ForReturnTypeName implements Renderer {

                        /**
                         * The singleton instance.
                         */
                        INSTANCE;

                        /**
                         * The return type symbol.
                         */
                        public static final char SYMBOL = 'r';

                        @Override
                        public String apply(TypeDescription instrumentedType, MethodDescription instrumentedMethod) {
                            return instrumentedMethod.getReturnType().asErasure().getName();
                        }

                        @Override
                        public String toString() {
                            return "Advice.Dispatcher.OffsetMapping.ForOrigin.Renderer.ForReturnTypeName." + name();
                        }
                    }

                    /**
                     * A renderer for a method's {@link Object#toString()} representation.
                     */
                    enum ForStringRepresentation implements Renderer {

                        /**
                         * The singleton instance.
                         */
                        INSTANCE;

                        @Override
                        public String apply(TypeDescription instrumentedType, MethodDescription instrumentedMethod) {
                            return instrumentedMethod.toString();
                        }

                        @Override
                        public String toString() {
                            return "Advice.Dispatcher.OffsetMapping.ForOrigin.Renderer.ForStringRepresentation." + name();
                        }
                    }

                    /**
                     * A renderer for a constant value.
                     */
                    class ForConstantValue implements Renderer {

                        /**
                         * The constant value.
                         */
                        private final String value;

                        /**
                         * Creates a new renderer for a constant value.
                         *
                         * @param value The constant value.
                         */
                        protected ForConstantValue(String value) {
                            this.value = value;
                        }

                        @Override
                        public String apply(TypeDescription instrumentedType, MethodDescription instrumentedMethod) {
                            return value;
                        }

                        @Override
                        public boolean equals(Object other) {
                            if (this == other) return true;
                            if (other == null || getClass() != other.getClass()) return false;
                            ForConstantValue that = (ForConstantValue) other;
                            return value.equals(that.value);
                        }

                        @Override
                        public int hashCode() {
                            return value.hashCode();
                        }

                        @Override
                        public String toString() {
                            return "Advice.Dispatcher.OffsetMapping.ForOrigin.Renderer.ForConstantValue{" +
                                    "value='" + value + '\'' +
                                    '}';
                        }
                    }
                }

                /**
                 * A factory for a method origin.
                 */
                protected enum Factory implements OffsetMapping.Factory {

                    /**
                     * The singleton instance.
                     */
                    INSTANCE;

                    @Override
                    public OffsetMapping make(ParameterDescription.InDefinedShape parameterDescription) {
                        AnnotationDescription.Loadable<Origin> origin = parameterDescription.getDeclaredAnnotations().ofType(Origin.class);
                        if (origin == null) {
                            return UNDEFINED;
                        } else if (parameterDescription.getType().asErasure().represents(Class.class)) {
                            return OffsetMapping.ForInstrumentedType.INSTANCE;
                        } else if (parameterDescription.getType().asErasure().represents(Method.class)) {
                            return OffsetMapping.ForInstrumentedMethod.METHOD;
                        } else if (parameterDescription.getType().asErasure().represents(Constructor.class)) {
                            return OffsetMapping.ForInstrumentedMethod.CONSTRUCTOR;
                        } else if (JavaType.EXECUTABLE.getTypeStub().equals(parameterDescription.getType().asErasure())) {
                            return OffsetMapping.ForInstrumentedMethod.EXECUTABLE;
                        } else if (parameterDescription.getType().asErasure().isAssignableFrom(String.class)) {
                            return ForOrigin.parse(origin.loadSilent().value());
                        } else {
                            throw new IllegalStateException("Non-supported type " + parameterDescription.getType() + " for @Origin annotation");
                        }
                    }

                    @Override
                    public String toString() {
                        return "Advice.Dispatcher.OffsetMapping.ForOrigin.Factory." + name();
                    }
                }
            }

            /**
             * An offset mapping for a parameter where assignments are fully ignored and that always return the parameter type's default value.
             */
            class ForUnusedValue implements OffsetMapping {

                private final TypeDefinition typeDefinition;

                ForUnusedValue(TypeDefinition typeDefinition) {
                    this.typeDefinition = typeDefinition;
                }

                @Override
                public Target resolve(TypeDescription instrumentedType, MethodDescription instrumentedMethod, Context context) {
                    return new Target.ForDefaultValue.ReadWrite(typeDefinition);
                }

                enum Factory implements OffsetMapping.Factory {

                    INSTANCE;

                    @Override
                    public OffsetMapping make(ParameterDescription.InDefinedShape parameterDescription) {
                        return parameterDescription.getDeclaredAnnotations().isAnnotationPresent(Unused.class)
                                ? new ForUnusedValue(parameterDescription.getType())
                                : UNDEFINED;
                    }
                }
            }

            /**
             * An offset mapping for a parameter where assignments are fully ignored and that is assigned a boxed version of the instrumented
             * method's return valueor {@code null} if the return type is not primitive or {@code void}.
             */
            enum ForStubValue implements OffsetMapping, Factory {

                /**
                 * The singleton instance.
                 */
                INSTANCE;

                @Override
                public Target resolve(TypeDescription instrumentedType, MethodDescription instrumentedMethod, Context context) {
                    return instrumentedMethod.getReturnType().isPrimitive() && !instrumentedMethod.getReturnType().represents(void.class)
                            ? Target.ForBoxedDefaultValue.of(instrumentedMethod.getReturnType())
                            : Target.ForNullConstant.READ_WRITE;
                }

                @Override
                public OffsetMapping make(ParameterDescription.InDefinedShape parameterDescription) {
                    if (!parameterDescription.getDeclaredAnnotations().isAnnotationPresent(StubValue.class)) {
                        return UNDEFINED;
                    } else if (!parameterDescription.getType().represents(Object.class)) {
                        throw new IllegalStateException("Cannot use StubValue on non-Object parameter type " + parameterDescription);
                    } else {
                        return this;
                    }
                }

                @Override
                public String toString() {
                    return "Advice.Dispatcher.OffsetMapping.ForStubValue." + name();
                }
            }

            /**
             * An offset mapping that provides access to the value that is returned by the enter advice.
             */
            class ForEnterValue implements OffsetMapping {

                private final TypeDescription typeDescription;

                protected ForEnterValue(TypeDescription typeDescription) {
                    this.typeDescription = typeDescription;
                }

                @Override
                public Target resolve(TypeDescription instrumentedType, MethodDescription instrumentedMethod, Context context) {
                    return new Target.ForVariable.ReadOnly(typeDescription, instrumentedMethod.getStackSize());
                }

                /**
                 * A factory for creating a {@link ForEnterValue} offset mapping.
                 */
                protected static class Factory implements OffsetMapping.Factory {

                    /**
                     * The supplied type of the enter method.
                     */
                    private final TypeDescription enterType;

                    /**
                     * Indicates that the mapped parameter is read-only.
                     */
                    private final boolean readOnly;

                    /**
                     * Creates a new factory for creating a {@link ForEnterValue} offset mapping.
                     *
                     * @param enterType The supplied type of the enter method.
                     * @param readOnly  Indicates that the mapped parameter is read-only.
                     */
                    protected Factory(TypeDescription enterType, boolean readOnly) {
                        this.enterType = enterType;
                        this.readOnly = readOnly;
                    }

                    @Override
                    public OffsetMapping make(ParameterDescription.InDefinedShape parameterDescription) {
                        AnnotationDescription.Loadable<Enter> annotation = parameterDescription.getDeclaredAnnotations().ofType(Enter.class);
                        if (annotation != null) {
                            boolean readOnly = annotation.loadSilent().readOnly();
                            if (!readOnly && !enterType.equals(parameterDescription.getType().asErasure())) {
                                throw new IllegalStateException("read-only type of " + parameterDescription + " does not equal " + enterType);
                            } else if (readOnly && !enterType.isAssignableTo(parameterDescription.getType().asErasure())) {
                                throw new IllegalStateException("Cannot assign the type of " + parameterDescription + " to supplied type " + enterType);
                            } else if (this.readOnly && !readOnly) {
                                throw new IllegalStateException("Cannot write to enter value field for " + parameterDescription + " in read only context");
                            }
                            return new ForEnterValue(enterType); // TODO: Merge factory with target
                        } else {
                            return UNDEFINED;
                        }
                    }

                    @Override
                    public boolean equals(Object other) {
                        if (this == other) return true;
                        if (other == null || getClass() != other.getClass()) return false;
                        Factory factory = (Factory) other;
                        return readOnly == factory.readOnly && enterType.equals(factory.enterType);
                    }

                    @Override
                    public int hashCode() {
                        int result = enterType.hashCode();
                        result = 31 * result + (readOnly ? 1 : 0);
                        return result;
                    }

                    @Override
                    public String toString() {
                        return "Advice.Dispatcher.OffsetMapping.ForEnterValue.Factory{" +
                                "enterType=" + enterType +
                                "m readOnly=" + readOnly +
                                '}';
                    }
                }
            }

            /**
             * An offset mapping that provides access to the value that is returned by the instrumented method.
             */
            class ForReturnValue implements OffsetMapping {

                /**
                 * Determines if the parameter is to be treated as read-only.
                 */
                private final boolean readOnly;

                /**
                 * The type that the advice method expects for the {@code this} reference.
                 */
                private final TypeDescription targetType;

                /**
                 * Creates an offset mapping for accessing the return type of the instrumented method.
                 *
                 * @param readOnly   Determines if the parameter is to be treated as read-only.
                 * @param targetType The expected target type of the return type.
                 */
                protected ForReturnValue(boolean readOnly, TypeDescription targetType) {
                    this.readOnly = readOnly;
                    this.targetType = targetType;
                }

                @Override
                public Target resolve(TypeDescription instrumentedType, MethodDescription instrumentedMethod, Context context) {
                    if (!readOnly && !instrumentedMethod.getReturnType().asErasure().equals(targetType)) {
                        throw new IllegalStateException("Non read-only return type of " + instrumentedMethod + " is not equal to " + targetType);
                    } else if (readOnly && !instrumentedMethod.getReturnType().asErasure().isAssignableTo(targetType)) {
                        throw new IllegalStateException("Cannot assign return type of " + instrumentedMethod + " to " + targetType);
                    }
                    return readOnly
                            ? new Target.ForVariable.ReadOnly(instrumentedMethod.getReturnType(), instrumentedMethod.getStackSize() + context.getPadding())
                            : new Target.ForVariable.ReadWrite(instrumentedMethod.getReturnType(), instrumentedMethod.getStackSize() + context.getPadding());
                }

                @Override
                public boolean equals(Object other) {
                    if (this == other) return true;
                    if (other == null || getClass() != other.getClass()) return false;
                    ForReturnValue that = (ForReturnValue) other;
                    return readOnly == that.readOnly && targetType.equals(that.targetType);
                }

                @Override
                public int hashCode() {
                    return (readOnly ? 1 : 0) + 31 * targetType.hashCode();
                }

                @Override
                public String toString() {
                    return "Advice.Dispatcher.OffsetMapping.ForReturnValue{" +
                            "readOnly=" + readOnly +
                            ", targetType=" + targetType +
                            '}';
                }

                /**
                 * A factory for creating a {@link ForReturnValue} offset mapping.
                 */
                protected enum Factory implements OffsetMapping.Factory {

                    /**
                     * A factory that does not allow writing to the mapped parameter.
                     */
                    READ_ONLY(true),

                    /**
                     * A factory that allows writing to the mapped parameter.
                     */
                    READ_WRITE(false);

                    /**
                     * {@code true} if the parameter is read-only.
                     */
                    private final boolean readOnly;

                    /**
                     * Creates a new factory.
                     *
                     * @param readOnly {@code true} if the parameter is read-only.
                     */
                    Factory(boolean readOnly) {
                        this.readOnly = readOnly;
                    }

                    @Override
                    public OffsetMapping make(ParameterDescription.InDefinedShape parameterDescription) {
                        AnnotationDescription.Loadable<Return> annotation = parameterDescription.getDeclaredAnnotations().ofType(Return.class);
                        if (annotation == null) {
                            return UNDEFINED;
                        } else if (readOnly && !annotation.loadSilent().readOnly()) {
                            throw new IllegalStateException("Cannot write return value for " + parameterDescription + " in read-only context");
                        } else {
                            return new ForReturnValue(annotation.loadSilent().readOnly(), parameterDescription.getType().asErasure());
                        }
                    }

                    @Override
                    public String toString() {
                        return "Advice.Dispatcher.OffsetMapping.ForReturnValue.Factory." + name();
                    }
                }
            }

            /**
             * An offset mapping for the method's (boxed) return value.
             */
            enum ForBoxedReturnValue implements OffsetMapping {

                /**
                 * Indicates that it is only legal to read the boxed return value.
                 */
                READ_ONLY(true),

                /**
                 * A mapping that also allows writing to the boxed return value via a type casting and potential unboxing.
                 */
                READ_WRITE(false);

                /**
                 * {@code true} if the factory is read-only.
                 */
                private final boolean readOnly;

                /**
                 * Creates a new offset mapping.
                 *
                 * @param readOnly {@code true} if the mapping is read-only.
                 */
                ForBoxedReturnValue(boolean readOnly) {
                    this.readOnly = readOnly;
                }

                @Override
                public Target resolve(TypeDescription instrumentedType, MethodDescription instrumentedMethod, Context context) {
                    if (instrumentedMethod.getReturnType().represents(void.class)) {
                        return readOnly
                                ? Target.ForNullConstant.READ_ONLY
                                : Target.ForNullConstant.READ_WRITE;
                    } else if (instrumentedMethod.getReturnType().isPrimitive()) {
                        return readOnly
                                ? Target.ForBoxedArgument.ReadOnly.of(instrumentedMethod.getStackSize() + context.getPadding(), instrumentedMethod.getReturnType())
                                : Target.ForBoxedArgument.ReadWrite.of(instrumentedMethod.getStackSize() + context.getPadding(), instrumentedMethod.getReturnType());
                    } else {
                        return readOnly
                                ? new Target.ForParameter.ReadOnly(instrumentedMethod.getStackSize() + context.getPadding())
                                : new Target.ForParameter.ReadWrite(instrumentedMethod.getStackSize() + context.getPadding()).casted(instrumentedMethod.getReturnType().asErasure());
                    }
                }

                @Override
                public String toString() {
                    return "Advice.Dispatcher.OffsetMapping.ForBoxedReturnValue." + name();
                }

                /**
                 * A factory for an offset mapping the method's (boxed) return value.
                 */
                protected enum Factory implements OffsetMapping.Factory {

                    /**
                     * Indicates that it is only legal to read the boxed return value.
                     */
                    READ_ONLY(true),

                    /**
                     * A factory that also allows writing to the boxed return value via a type casting and potential unboxing.
                     */
                    READ_WRITE(false);

                    /**
                     * {@code true} if the factory is read-only.
                     */
                    private final boolean readOnly;

                    /**
                     * Creates a new factory.
                     *
                     * @param readOnly {@code true} if the factory is read-only.
                     */
                    Factory(boolean readOnly) {
                        this.readOnly = readOnly;
                    }

                    @Override
                    public OffsetMapping make(ParameterDescription.InDefinedShape parameterDescription) {
                        AnnotationDescription.Loadable<BoxedReturn> annotation = parameterDescription.getDeclaredAnnotations().ofType(BoxedReturn.class);
                        if (annotation == null) {
                            return UNDEFINED;
                        } else if (readOnly && !annotation.loadSilent().readOnly()) {
                            throw new IllegalStateException("Cannot write return value from a non-writable context for " + parameterDescription);
                        } else if (parameterDescription.getType().represents(Object.class)) {
                            return annotation.loadSilent().readOnly()
                                    ? ForBoxedReturnValue.READ_ONLY
                                    : ForBoxedReturnValue.READ_WRITE;
                        } else {
                            throw new IllegalStateException("Can only assign a boxed return value to an Object type for " + parameterDescription);
                        }
                    }

                    @Override
                    public String toString() {
                        return "Advice.Dispatcher.OffsetMapping.ForBoxedReturnValue.Factory." + name();
                    }
                }
            }

            /**
             * An offset mapping for an array containing the (boxed) method arguments.
             */
            enum ForBoxedArguments implements OffsetMapping {

                /**
                 * Does not allow writing to the annotated parameter.
                 */
                READ_ONLY(true),

                /**
                 * Does allow writing to the annotated parameter.
                 */
                READ_WRITE(false);

                /**
                 * {@code true} if the annotated parameter is read-only.
                 */
                private final boolean readOnly;

                /**
                 * Creates a new offset mapping.
                 *
                 * @param readOnly {@code true} if the annotated parameter is read-only.
                 */
                ForBoxedArguments(boolean readOnly) {
                    this.readOnly = readOnly;
                }

                @Override
                public Target resolve(TypeDescription instrumentedType, MethodDescription instrumentedMethod, Context context) {
                    return readOnly
                            ? new Target.ForBoxedArguments.ReadOnly(instrumentedMethod.getParameters())
                            : new Target.ForBoxedArguments.ReadWrite(instrumentedMethod.getParameters());
                }

                @Override
                public String toString() {
                    return "Advice.Dispatcher.OffsetMapping.ForBoxedArguments." + name();
                }

                /**
                 * A factory for an offset mapping for boxed method arguments.
                 */
                protected enum Factory implements OffsetMapping.Factory {

                    /**
                     * Does not allow writing to the annotated parameter.
                     */
                    READ_ONLY(true),

                    /**
                     * Does allow writing to the annotated parameter.
                     */
                    READ_WRITE(false);

                    /**
                     * {@code true} if the annotated parameter is read-only.
                     */
                    private final boolean readOnly;

                    /**
                     * Creates a new factory.
                     *
                     * @param readOnly {@code true} if the annotated parameter is read-only.
                     */
                    Factory(boolean readOnly) {
                        this.readOnly = readOnly;
                    }

                    @Override
                    public OffsetMapping make(ParameterDescription.InDefinedShape parameterDescription) {
                        AnnotationDescription.Loadable<BoxedArguments> annotation = parameterDescription.getDeclaredAnnotations().ofType(BoxedArguments.class);
                        if (annotation == null) {
                            return UNDEFINED;
                        } else if (readOnly && !annotation.loadSilent().readOnly()) {
                            throw new IllegalStateException("Cannot write return value from a non-writable context for " + parameterDescription);
                        } else if (parameterDescription.getType().represents(Object[].class)) {
                            return annotation.loadSilent().readOnly()
                                    ? ForBoxedArguments.READ_ONLY
                                    : ForBoxedArguments.READ_WRITE;
                        } else {
                            throw new IllegalStateException("Can only assign a boxed return value to an Object[] type for " + parameterDescription);
                        }
                    }

                    @Override
                    public String toString() {
                        return "Advice.Dispatcher.OffsetMapping.ForBoxedArguments.Factory." + name();
                    }
                }
            }

            /**
             * An offset mapping for accessing a {@link Throwable} of the instrumented method.
             */
            class ForThrowable implements OffsetMapping {

                /**
                 * The type of parameter that is being accessed.
                 */
                private final TypeDescription targetType;

                /**
                 * The type of the {@link Throwable} being catched if thrown from the instrumented method.
                 */
                private final TypeDescription triggeringThrowable;

                /**
                 * {@code true} if the parameter is read-only.
                 */
                private final boolean readOnly;

                /**
                 * Creates a new offset mapping for access of the exception that is thrown by the instrumented method..
                 *
                 * @param targetType          The type of parameter that is being accessed.
                 * @param triggeringThrowable The type of the {@link Throwable} being catched if thrown from the instrumented method.
                 * @param readOnly            {@code true} if the parameter is read-only.
                 */
                protected ForThrowable(TypeDescription targetType, TypeDescription triggeringThrowable, boolean readOnly) {
                    this.targetType = targetType;
                    this.triggeringThrowable = triggeringThrowable;
                    this.readOnly = readOnly;
                }

                @Override
                public Target resolve(TypeDescription instrumentedType, MethodDescription instrumentedMethod, Context context) {
                    int offset = instrumentedMethod.getStackSize() + context.getPadding() + instrumentedMethod.getReturnType().getStackSize().getSize();
                    return readOnly
                            ? new Target.ForVariable.ReadOnly(TypeDescription.THROWABLE, offset)
                            : new Target.ForVariable.ReadWrite(TypeDescription.THROWABLE, offset);
                }

                @Override
                public boolean equals(Object other) {
                    if (this == other) return true;
                    if (other == null || getClass() != other.getClass()) return false;
                    ForThrowable forThrowable = (ForThrowable) other;
                    return readOnly == forThrowable.readOnly
                            && targetType.equals(forThrowable.targetType)
                            && triggeringThrowable.equals(forThrowable.triggeringThrowable);
                }

                @Override
                public int hashCode() {
                    int result = triggeringThrowable.hashCode();
                    result = 31 * result + targetType.hashCode();
                    result = 31 * result + (readOnly ? 1 : 0);
                    return result;
                }

                @Override
                public String toString() {
                    return "Advice.Dispatcher.OffsetMapping.ForThrowable{" +
                            "targetType=" + targetType +
                            ", triggeringThrowable=" + triggeringThrowable +
                            ", readOnly=" + readOnly +
                            '}';
                }

                /**
                 * A factory for accessing an exception that was thrown by the instrumented method.
                 */
                protected static class Factory implements OffsetMapping.Factory {

                    /**
                     * The type of the {@link Throwable} being catched if thrown from the instrumented method.
                     */
                    private final TypeDescription triggeringThrowable;

                    /**
                     * {@code true} if the parameter is read-only.
                     */
                    private final boolean readOnly;

                    /**
                     * Creates a new factory for access of the exception that is thrown by the instrumented method..
                     *
                     * @param triggeringThrowable The type of the {@link Throwable} being catched if thrown from the instrumented method.
                     * @param readOnly            {@code true} if the parameter is read-only.
                     */
                    protected Factory(TypeDescription triggeringThrowable, boolean readOnly) {
                        this.triggeringThrowable = triggeringThrowable;
                        this.readOnly = readOnly;
                    }

                    /**
                     * Resolves an appropriate offset mapping factory for the {@link Thrown} parameter annotation.
                     *
                     * @param adviceMethod The exit advice method, annotated with {@link OnMethodExit}.
                     * @param readOnly     {@code true} if the parameter is read-only.
                     * @return An appropriate offset mapping factory.
                     */
                    @SuppressWarnings("unchecked") // In absence of @SafeVarargs for Java 6
                    protected static OffsetMapping.Factory of(MethodDescription.InDefinedShape adviceMethod, boolean readOnly) {
                        TypeDescription triggeringThrowable = adviceMethod.getDeclaredAnnotations()
                                .ofType(OnMethodExit.class)
                                .getValue(ON_THROWABLE).resolve(TypeDescription.class);
                        return triggeringThrowable.represents(NoExceptionHandler.class)
                                ? new OffsetMapping.Illegal(Thrown.class)
                                : new Factory(triggeringThrowable, readOnly);
                    }

                    @Override
                    public OffsetMapping make(ParameterDescription.InDefinedShape parameterDescription) {
                        AnnotationDescription.Loadable<Thrown> annotation = parameterDescription.getDeclaredAnnotations().ofType(Thrown.class);
                        if (annotation == null) {
                            return UNDEFINED;
                        } else if (!parameterDescription.getType().represents(Throwable.class)) {
                            throw new IllegalStateException("Parameter must be a throwable type for " + parameterDescription);
                        } else if (readOnly && !annotation.loadSilent().readOnly()) {
                            throw new IllegalStateException("Cannot write exception value for " + parameterDescription + " in read-only context");
                        } else {
                            return new ForThrowable(parameterDescription.getType().asErasure(), triggeringThrowable, annotation.loadSilent().readOnly());
                        }
                    }

                    @Override
                    public boolean equals(Object other) {
                        if (this == other) return true;
                        if (other == null || getClass() != other.getClass()) return false;
                        Factory factory = (Factory) other;
                        return readOnly == factory.readOnly && triggeringThrowable.equals(factory.triggeringThrowable);
                    }

                    @Override
                    public int hashCode() {
                        int result = triggeringThrowable.hashCode();
                        result = 31 * result + (readOnly ? 1 : 0);
                        return result;
                    }

                    @Override
                    public String toString() {
                        return "Advice.Dispatcher.OffsetMapping.ForThrowable.Factory{" +
                                "triggeringThrowable=" + triggeringThrowable +
                                ", readOnly=" + readOnly +
                                '}';
                    }
                }
            }

            /**
             * Represents an offset mapping for a user-defined value.
             *
             * @param <T> The mapped annotation type.
             */
            class ForUserValue<T extends Annotation> implements OffsetMapping {

                /**
                 * The target parameter that is bound.
                 */
                private final ParameterDescription.InDefinedShape target;

                /**
                 * The annotation value that triggered the binding.
                 */
                private final AnnotationDescription.Loadable<T> annotation;

                /**
                 * The dynamic value that is bound.
                 */
                private final DynamicValue<T> dynamicValue;

                /**
                 * Creates a new offset mapping for a user-defined value.
                 *
                 * @param target       The target parameter that is bound.
                 * @param annotation   The annotation value that triggered the binding.
                 * @param dynamicValue The dynamic value that is bound.
                 */
                protected ForUserValue(ParameterDescription.InDefinedShape target,
                                       AnnotationDescription.Loadable<T> annotation,
                                       DynamicValue<T> dynamicValue) {
                    this.target = target;
                    this.annotation = annotation;
                    this.dynamicValue = dynamicValue;
                }

                @Override
                public Target resolve(TypeDescription instrumentedType, MethodDescription instrumentedMethod, Context context) {
                    Object value = dynamicValue.resolve(instrumentedType, instrumentedMethod, target, annotation, context.isInitialized());
                    if (value == null) {
                        if (target.getType().isPrimitive()) {
                            throw new IllegalStateException("Cannot map null to primitive type of " + target);
                        }
                        return Target.ForNullConstant.READ_ONLY;
                    } else if ((value instanceof String)) {
                        if (!target.getType().asErasure().isAssignableFrom(String.class)) {
                            throw new IllegalStateException("Cannot assign " + value + " to " + target);
                        }
                        return new Target.ForConstantPoolValue(value);
                    } else if (value instanceof Boolean
                            || value instanceof Byte
                            || value instanceof Short
                            || value instanceof Character
                            || value instanceof Integer
                            || value instanceof Long
                            || value instanceof Float
                            || value instanceof Double) {
                        if (target.getType().isPrimitive() && target.getType().asErasure().asBoxed().isInstance(value)) {
                            return new Target.ForConstantPoolValue(value);
                        } else if (target.getType().asErasure().isInstance(value)) {
                            return Target.ForConstantPoolValue.WithBoxing.of(value);
                        } else {
                            throw new IllegalStateException("Cannot assign " + value + " to " + target);
                        }
                    } else if (value instanceof Class) {
                        if (!target.getType().asErasure().isAssignableFrom(Class.class)) {
                            throw new IllegalStateException("Cannot assign " + value + " to " + target);
                        }
                        return new Target.ForConstantPoolValue(Type.getType((Class<?>) value));
                    } else if (value instanceof TypeDescription) {
                        if (!target.getType().asErasure().isAssignableFrom(Class.class)) {
                            throw new IllegalStateException("Cannot assign " + value + " to " + target);
                        }
                        return new Target.ForConstantPoolValue(Type.getType(((TypeDescription) value).getDescriptor()));
                    } else if (value instanceof FieldDescription) {
                        FieldDescription.InDefinedShape fieldDescription = ((FieldDescription) value).asDefined();
                        if (!fieldDescription.isStatic() && !instrumentedType.isAssignableTo(fieldDescription.getDeclaringType())) {
                            throw new IllegalStateException("Cannot access " + fieldDescription + " from " + instrumentedType);
                        } else if (!fieldDescription.isVisibleTo(instrumentedType)) {
                            throw new IllegalStateException(fieldDescription + " is not visible from " + instrumentedType);
                        } else if (fieldDescription.getType().asErasure().isAssignableTo(target.getType().asErasure())) {
                            return new Target.ForField.ReadOnly(fieldDescription);
                        } else if (fieldDescription.getType().asErasure().asBoxed().isAssignableTo(target.getType().asErasure())) {
                            return new Target.ForField.ReadBoxed(fieldDescription);
                        } else {
                            throw new IllegalStateException("Cannot assign " + fieldDescription + " to " + target);
                        }
                    } else if (value instanceof ParameterDescription) {
                        ParameterDescription parameterDescription = (ParameterDescription) value;
                        if (!instrumentedMethod.equals(parameterDescription.getDeclaringMethod())) {
                            throw new IllegalStateException("Cannot access " + parameterDescription + " from " + instrumentedMethod);
                        } else if (parameterDescription.getType().asErasure().isAssignableTo(target.getType().asErasure())) {
                            return new Target.ForParameter.ReadOnly(parameterDescription.getOffset());
                        } else if (parameterDescription.getType().asErasure().asBoxed().isAssignableTo(target.getType().asErasure())) {
                            return new Target.ForBoxedArgument.ReadOnly(parameterDescription.getOffset(), Target.PrimitiveDispatcher.of(parameterDescription.getType()));
                        } else {
                            throw new IllegalStateException("Cannot assign " + parameterDescription + " to " + target);
                        }
                    } else if (value instanceof Serializable) {
                        if (!target.getType().asErasure().isInstance(value)) {
                            throw new IllegalStateException("Cannot assign " + value + " to " + target);
                        }
                        return Target.ForSerializedObject.of(target.getType().asErasure(), (Serializable) value);
                    } else {
                        throw new IllegalStateException("Cannot map " + value + " as constant value of " + target.getType());
                    }
                }

                @Override
                public boolean equals(Object other) {
                    if (this == other) return true;
                    if (other == null || getClass() != other.getClass()) return false;
                    ForUserValue that = (ForUserValue) other;
                    return target.equals(that.target)
                            && annotation.equals(that.annotation)
                            && dynamicValue.equals(that.dynamicValue);
                }

                @Override
                public int hashCode() {
                    int result = target.hashCode();
                    result = 31 * result + annotation.hashCode();
                    result = 31 * result + dynamicValue.hashCode();
                    return result;
                }

                @Override
                public String toString() {
                    return "Advice.Dispatcher.OffsetMapping.ForUserValue{" +
                            "target=" + target +
                            ", annotation=" + annotation +
                            ", dynamicValue=" + dynamicValue +
                            '}';
                }

                /**
                 * A factory for mapping a user-defined dynamic value.
                 *
                 * @param <S> The mapped annotation type.
                 */
                protected static class Factory<S extends Annotation> implements OffsetMapping.Factory {

                    /**
                     * The mapped annotation type.
                     */
                    private final Class<S> type;

                    /**
                     * The dynamic value instance used for resolving a binding.
                     */
                    private final DynamicValue<S> dynamicValue;

                    /**
                     * Creates a new factory for a user-defined dynamic value.
                     *
                     * @param type         The mapped annotation type.
                     * @param dynamicValue The dynamic value instance used for resolving a binding.
                     */
                    protected Factory(Class<S> type, DynamicValue<S> dynamicValue) {
                        this.type = type;
                        this.dynamicValue = dynamicValue;
                    }

                    /**
                     * Creates a new factory for mapping a user value.
                     *
                     * @param type         The mapped annotation type.
                     * @param dynamicValue The dynamic value instance used for resolving a binding.
                     * @return An appropriate factory for such a offset mapping.
                     */
                    @SuppressWarnings("unchecked")
                    protected static OffsetMapping.Factory of(Class<? extends Annotation> type, DynamicValue<?> dynamicValue) {
                        return new Factory(type, dynamicValue);
                    }

                    @Override
                    public OffsetMapping make(ParameterDescription.InDefinedShape parameterDescription) {
                        AnnotationDescription.Loadable<S> annotation = parameterDescription.getDeclaredAnnotations().ofType(type);
                        return annotation == null
                                ? UNDEFINED
                                : new ForUserValue<S>(parameterDescription, annotation, dynamicValue);
                    }

                    @Override
                    public boolean equals(Object other) {
                        if (this == other) return true;
                        if (other == null || getClass() != other.getClass()) return false;
                        Factory factory = (Factory) other;
                        return type.equals(factory.type) && dynamicValue.equals(factory.dynamicValue);
                    }

                    @Override
                    public int hashCode() {
                        int result = type.hashCode();
                        result = 31 * result + dynamicValue.hashCode();
                        return result;
                    }

                    @Override
                    public String toString() {
                        return "Advice.Dispatcher.OffsetMapping.ForUserValue.Factory{" +
                                "type=" + type +
                                ", dynamicValue=" + dynamicValue +
                                '}';
                    }
                }
            }

            /**
             * Represents a factory that throws an exception for a given set of illegal parameter annotations.
             */
            class Illegal implements Factory {

                /**
                 * The set of illegal annotations.
                 */
                private final List<? extends Class<? extends Annotation>> annotations;

                /**
                 * Creates a new factory for restricting the use of illegal annotation types.
                 *
                 * @param annotation The set of illegal annotations.
                 */
                @SuppressWarnings("unchecked") // In absence of @SafeVarargs for Java 6
                protected Illegal(Class<? extends Annotation>... annotation) {
                    this(Arrays.asList(annotation));
                }

                /**
                 * Creates a new factory for restricting the use of illegal annotation types.
                 *
                 * @param annotations The set of illegal annotations.
                 */
                protected Illegal(List<? extends Class<? extends Annotation>> annotations) {
                    this.annotations = annotations;
                }

                @Override
                public OffsetMapping make(ParameterDescription.InDefinedShape parameterDescription) {
                    for (Class<? extends Annotation> annotation : annotations) {
                        if (parameterDescription.getDeclaredAnnotations().isAnnotationPresent(annotation)) {
                            throw new IllegalStateException("Illegal annotation " + annotation + " for " + parameterDescription);
                        }
                    }
                    return UNDEFINED;
                }

                @Override
                public boolean equals(Object other) {
                    if (this == other) return true;
                    if (other == null || getClass() != other.getClass()) return false;
                    Illegal illegal = (Illegal) other;
                    return annotations.equals(illegal.annotations);
                }

                @Override
                public int hashCode() {
                    return annotations.hashCode();
                }

                @Override
                public String toString() {
                    return "Advice.Dispatcher.OffsetMapping.Illegal{" +
                            "annotations=" + annotations +
                            '}';
                }
            }
        }

        /**
         * A suppression handler for optionally suppressing exceptions.
         */
        interface SuppressionHandler {

            /**
             * Binds the suppression handler for instrumenting a specific method.
             *
             * @return A bound version of the suppression handler.
             */
            Bound bind();

            /**
             * A producer for a default return value if this is applicable.
             */
            interface ReturnValueProducer {

                /**
                 * Instructs this return value producer to assure the production of a default value for the return type of the currently handled method.
                 *
                 * @param methodVisitor The method visitor to write the default value to.
                 */
                void onDefaultValue(MethodVisitor methodVisitor);
            }

            /**
             * A bound version of a suppression handler that must not be reused.
             */
            interface Bound {

                /**
                 * Invoked to prepare the suppression handler, i.e. to write an exception handler entry if appropriate.
                 *
                 * @param methodVisitor The method visitor to apply the preparation to.
                 */
                void onPrepare(MethodVisitor methodVisitor);

                /**
                 * Invoked at the start of a method.
                 *
                 * @param methodVisitor     The method visitor of the instrumented method.
                 * @param methodSizeHandler A handler for computing the method size requirements.
                 */
                void onStart(MethodVisitor methodVisitor, MethodSizeHandler.ForAdvice methodSizeHandler);

                /**
                 * Invoked at the end of a method.
                 *
                 * @param methodVisitor        The method visitor of the instrumented method.
                 * @param stackMapFrameHandler A handler for translating and injecting stack map frames.
                 * @param returnValueProducer  A producer for defining a default return value of the advised method.
                 */
                void onEnd(MethodVisitor methodVisitor, StackMapFrameHandler.ForAdvice stackMapFrameHandler, ReturnValueProducer returnValueProducer);

                /**
                 * Invoked at the end of a method. Additionally indicates that the handler block should be surrounding by a skipping instruction. This method
                 * is always followed by a stack map frame (if it is required for the class level and class writer setting).
                 *
                 * @param methodVisitor        The method visitor of the instrumented method.
                 * @param stackMapFrameHandler A handler for translating and injecting stack map frames.
                 * @param returnValueProducer  A producer for defining a default return value of the advised method.
                 */
                void onEndSkipped(MethodVisitor methodVisitor, StackMapFrameHandler.ForAdvice stackMapFrameHandler, ReturnValueProducer returnValueProducer);
            }

            /**
             * A non-operational suppression handler that does not suppress any method.
             */
            enum NoOp implements SuppressionHandler, Bound {

                /**
                 * The singleton instance.
                 */
                INSTANCE;

                @Override
                public Bound bind() {
                    return this;
                }

                @Override
                public void onPrepare(MethodVisitor methodVisitor) {
                    /* do nothing */
                }

                @Override
                public void onStart(MethodVisitor methodVisitor, MethodSizeHandler.ForAdvice methodSizeHandler) {
                    /* do nothing */
                }

                @Override
                public void onEnd(MethodVisitor methodVisitor, StackMapFrameHandler.ForAdvice stackMapFrameHandler, ReturnValueProducer returnValueProducer) {
                    /* do nothing */
                }

                @Override
                public void onEndSkipped(MethodVisitor methodVisitor, StackMapFrameHandler.ForAdvice stackMapFrameHandler, ReturnValueProducer returnValueProducer) {
                    /* do nothing */
                }

                @Override
                public String toString() {
                    return "Advice.Dispatcher.SuppressionHandler.NoOp." + name();
                }
            }

            /**
             * A suppression handler that suppresses a given throwable type.
             */
            class Suppressing implements SuppressionHandler {

                /**
                 * The suppressed throwable type.
                 */
                private final TypeDescription suppressedType;

                /**
                 * Creates a new suppressing suppression handler.
                 *
                 * @param suppressedType The suppressed throwable type.
                 */
                protected Suppressing(TypeDescription suppressedType) {
                    this.suppressedType = suppressedType;
                }

                /**
                 * Resolves an appropriate suppression handler.
                 *
                 * @param suppressedType The suppressed type or {@link NoExceptionHandler} if no type should be suppressed.
                 * @return An appropriate suppression handler.
                 */
                protected static SuppressionHandler of(TypeDescription suppressedType) {
                    return suppressedType.represents(NoExceptionHandler.class)
                            ? NoOp.INSTANCE
                            : new Suppressing(suppressedType);
                }

                @Override
                public SuppressionHandler.Bound bind() {
                    return new Bound(suppressedType);
                }

                @Override
                public boolean equals(Object other) {
                    if (this == other) return true;
                    if (other == null || getClass() != other.getClass()) return false;
                    Suppressing that = (Suppressing) other;
                    return suppressedType.equals(that.suppressedType);
                }

                @Override
                public int hashCode() {
                    return suppressedType.hashCode();
                }

                @Override
                public String toString() {
                    return "Advice.Dispatcher.SuppressionHandler.Suppressing{" +
                            "suppressedType=" + suppressedType +
                            '}';
                }

                /**
                 * An active, bound suppression handler.
                 */
                protected static class Bound implements SuppressionHandler.Bound {

                    /**
                     * The suppressed throwable type.
                     */
                    private final TypeDescription suppressedType;

                    /**
                     * A label indicating the start of the method.
                     */
                    private final Label startOfMethod;

                    /**
                     * A label indicating the end of the method.
                     */
                    private final Label endOfMethod;

                    /**
                     * Creates a new active, bound suppression handler.
                     *
                     * @param suppressedType The suppressed throwable type.
                     */
                    protected Bound(TypeDescription suppressedType) {
                        this.suppressedType = suppressedType;
                        startOfMethod = new Label();
                        endOfMethod = new Label();
                    }

                    @Override
                    public void onPrepare(MethodVisitor methodVisitor) {
                        methodVisitor.visitTryCatchBlock(startOfMethod, endOfMethod, endOfMethod, suppressedType.getInternalName());
                    }

                    @Override
                    public void onStart(MethodVisitor methodVisitor, MethodSizeHandler.ForAdvice methodSizeHandler) {
                        methodVisitor.visitLabel(startOfMethod);
                        methodSizeHandler.requireStackSize(StackSize.SINGLE.getSize());
                    }

                    @Override
                    public void onEnd(MethodVisitor methodVisitor, StackMapFrameHandler.ForAdvice stackMapFrameHandler, ReturnValueProducer returnValueProducer) {
                        methodVisitor.visitLabel(endOfMethod);
                        stackMapFrameHandler.injectExceptionFrame(methodVisitor);
                        methodVisitor.visitInsn(Opcodes.POP);
                        returnValueProducer.onDefaultValue(methodVisitor);
                    }

                    @Override
                    public void onEndSkipped(MethodVisitor methodVisitor, StackMapFrameHandler.ForAdvice stackMapFrameHandler, ReturnValueProducer returnValueProducer) {
                        Label endOfHandler = new Label();
                        methodVisitor.visitJumpInsn(Opcodes.GOTO, endOfHandler);
                        onEnd(methodVisitor, stackMapFrameHandler, returnValueProducer);
                        methodVisitor.visitLabel(endOfHandler);
                    }

                    @Override
                    public String toString() {
                        return "Advice.Dispatcher.SuppressionHandler.Suppressing.Bound{" +
                                "suppressedType=" + suppressedType +
                                ", startOfMethod=" + startOfMethod +
                                ", endOfMethod=" + endOfMethod +
                                '}';
                    }
                }
            }
        }

        /**
         * Represents a resolved dispatcher.
         */
        interface Resolved extends Dispatcher {

            /**
             * Binds this dispatcher for resolution to a specific method.
             *
             * @param instrumentedType     The instrumented type.
             * @param instrumentedMethod   The instrumented method.
             * @param methodVisitor        The method visitor for writing the instrumented method.
             * @param methodSizeHandler    A handler for computing the method size requirements.
             * @param stackMapFrameHandler A handler for translating and injecting stack map frames.
             * @return A dispatcher that is bound to the instrumented method.
             */
            Bound bind(TypeDescription instrumentedType,
                       MethodDescription instrumentedMethod,
                       MethodVisitor methodVisitor,
                       Implementation.Context implementationContext,
                       MethodSizeHandler.ForInstrumentedMethod methodSizeHandler,
                       StackMapFrameHandler.ForInstrumentedMethod stackMapFrameHandler);

            /**
             * Represents a resolved dispatcher for entering a method.
             */
            interface ForMethodEnter extends Resolved {

                /**
                 * Returns the type that this dispatcher supplies as a result of its advice or a description of {@code void} if
                 * no type is supplied as a result of the enter advice.
                 *
                 * @return The type that this dispatcher supplies as a result of its advice or a description of {@code void}.
                 */
                TypeDescription getEnterType();

                /**
                 * Returns {@code true} if the first discovered line number information should be prepended to the advice code.
                 *
                 * @return {@code true} if the first discovered line number information should be prepended to the advice code.
                 */
                boolean isPrependLineNumber();

                @Override
                Bound.ForMethodEnter bind(TypeDescription instrumentedType,
                                          MethodDescription instrumentedMethod,
                                          MethodVisitor methodVisitor,
                                          Implementation.Context implementationContext,
                                          MethodSizeHandler.ForInstrumentedMethod methodSizeHandler,
                                          StackMapFrameHandler.ForInstrumentedMethod stackMapFrameHandler);

                /**
                 * A skip dispatcher is responsible for skipping the instrumented method depending on the
                 * return value of the enter advice method.
                 */
                interface SkipDispatcher {

                    /**
                     * Applies this skip dispatcher.
                     *
                     * @param methodVisitor        The method visitor to write to.
                     * @param methodSizeHandler    The method size handler of the advice method to use.
                     * @param stackMapFrameHandler The stack map frame handler of the advice method to use.
                     * @param instrumentedMethod   The instrumented method.
                     * @param skipHandler          The skip handler to use.
                     */
                    void apply(MethodVisitor methodVisitor,
                               MethodSizeHandler.ForAdvice methodSizeHandler,
                               StackMapFrameHandler.ForAdvice stackMapFrameHandler,
                               MethodDescription instrumentedMethod,
                               Bound.SkipHandler skipHandler);

                    /**
                     * A disabled skip dispatcher where the instrumented method is always executed.
                     */
                    enum Disabled implements SkipDispatcher {

                        /**
                         * The singleton instance.
                         */
                        INSTANCE;

                        @Override
                        public void apply(MethodVisitor methodVisitor,
                                          MethodSizeHandler.ForAdvice methodSizeHandler,
                                          StackMapFrameHandler.ForAdvice stackMapFrameHandler,
                                          MethodDescription instrumentedMethod,
                                          Bound.SkipHandler skipHandler) {
                            /* do nothing */
                        }

                        @Override
                        public String toString() {
                            return "Advice.Dispatcher.Resolved.ForMethodEnter.SkipDispatcher.Disabled." + name();
                        }
                    }

                    /**
                     * A skip dispatcher where the instrumented method is skipped for any default value of the advice method's return type.
                     * If the return type is {@code boolean}, the relationship is inversed, where the instrumented is skipped for a {@code true}
                     * return value.
                     */
                    enum ForValue implements SkipDispatcher {

                        /**
                         * A skip dispatcher for a {@code boolean}, {@code  byte}, {@code short}, {@code char} or {@code int} value.
                         */
                        FOR_INTEGER(Opcodes.ILOAD, Opcodes.IFNE, Opcodes.IFEQ) {
                            @Override
                            protected void convertValue(MethodVisitor methodVisitor, MethodSizeHandler.ForAdvice methodSizeHandler) {
                                /* do nothing */
                            }
                        },

                        /**
                         * A skip dispatcher for a {@code long} value.
                         */
                        FOR_LONG(Opcodes.LLOAD, Opcodes.IFNE, Opcodes.IFEQ) {
                            @Override
                            protected void convertValue(MethodVisitor methodVisitor, MethodSizeHandler.ForAdvice methodSizeHandler) {
                                methodVisitor.visitInsn(Opcodes.L2I);
                            }
                        },

                        /**
                         * A skip dispatcher for a {@code float} value.
                         */
                        FOR_FLOAT(Opcodes.FLOAD, Opcodes.IFNE, Opcodes.IFEQ) {
                            @Override
                            protected void convertValue(MethodVisitor methodVisitor, MethodSizeHandler.ForAdvice methodSizeHandler) {
                                methodVisitor.visitInsn(Opcodes.FCONST_0);
                                methodVisitor.visitInsn(Opcodes.FCMPL);
                                methodSizeHandler.requireStackSize(2);
                            }
                        },

                        /**
                         * A skip dispatcher for a {@code double} value.
                         */
                        FOR_DOUBLE(Opcodes.DLOAD, Opcodes.IFNE, Opcodes.IFEQ) {
                            @Override
                            protected void convertValue(MethodVisitor methodVisitor, MethodSizeHandler.ForAdvice methodSizeHandler) {
                                methodVisitor.visitInsn(Opcodes.DCONST_0);
                                methodVisitor.visitInsn(Opcodes.DCMPL);
                                methodSizeHandler.requireStackSize(4);
                            }
                        },

                        /**
                         * A skip dispatcher for a reference value.
                         */
                        FOR_REFERENCE(Opcodes.ALOAD, Opcodes.IFNONNULL, Opcodes.IFNULL) {
                            @Override
                            protected void convertValue(MethodVisitor methodVisitor, MethodSizeHandler.ForAdvice methodSizeHandler) {
                                /* do nothing */
                            }
                        };

                        /**
                         * The load opcode for this skip dispatcher.
                         */
                        private final int load;

                        /**
                         * The jump instruction that triggers skipping upon observing a value's default value.
                         */
                        private final int defaultJump;

                        /**
                         * The jump instruction that triggers skipping upon observing a value's non-default value.
                         */
                        private final int nonDefaultJump;

                        /**
                         * Creates a new skip dispatcher.
                         *
                         * @param load           The load opcode for this skip dispatcher.
                         * @param defaultJump    The jump instruction that triggers skipping upon observing a value's default value.
                         * @param nonDefaultJump The jump instruction that triggers skipping upon observing a value's non-default value.
                         */
                        ForValue(int load, int defaultJump, int nonDefaultJump) {
                            this.load = load;
                            this.defaultJump = defaultJump;
                            this.nonDefaultJump = nonDefaultJump;
                        }

                        /**
                         * Creates an appropriate skip dispatcher.
                         *
                         * @param typeDefinition The type for which to skip a value.
                         * @param inverted       {@code true} if the skip condition should be inverted to trigger upon non-default values.
                         * @return An appropriate skip dispatcher.
                         */
                        protected static SkipDispatcher of(TypeDefinition typeDefinition, boolean inverted) {
                            ForValue skipDispatcher;
                            if (typeDefinition.represents(long.class)) {
                                skipDispatcher = FOR_LONG;
                            } else if (typeDefinition.represents(float.class)) {
                                skipDispatcher = FOR_FLOAT;
                            } else if (typeDefinition.represents(double.class)) {
                                skipDispatcher = FOR_DOUBLE;
                            } else if (typeDefinition.represents(void.class)) {
                                throw new IllegalStateException("Cannot skip on default value for void return type");
                            } else if (typeDefinition.isPrimitive()) { // anyOf(byte, short, char, int)
                                skipDispatcher = FOR_INTEGER;
                            } else {
                                skipDispatcher = FOR_REFERENCE;
                            }
                            return inverted
                                    ? skipDispatcher.inverted()
                                    : skipDispatcher;
                        }

                        @Override
                        public void apply(MethodVisitor methodVisitor,
                                          MethodSizeHandler.ForAdvice methodSizeHandler,
                                          StackMapFrameHandler.ForAdvice stackMapFrameHandler,
                                          MethodDescription instrumentedMethod,
                                          Bound.SkipHandler skipHandler) {
                            doApply(methodVisitor, methodSizeHandler, stackMapFrameHandler, instrumentedMethod, skipHandler, false);
                        }

                        /**
                         * Applies this skip dispatcher.
                         *
                         * @param methodVisitor        The method visitor to write to.
                         * @param methodSizeHandler    The method size handler of the advice method to use.
                         * @param stackMapFrameHandler The stack map frame handler of the advice method to use.
                         * @param instrumentedMethod   The instrumented method.
                         * @param skipHandler          The skip handler to use.
                         * @param inverted             {@code true} if the skip condition should be inverted.
                         */
                        protected void doApply(MethodVisitor methodVisitor,
                                               MethodSizeHandler.ForAdvice methodSizeHandler,
                                               StackMapFrameHandler.ForAdvice stackMapFrameHandler,
                                               MethodDescription instrumentedMethod,
                                               Bound.SkipHandler skipHandler,
                                               boolean inverted) {
                            methodVisitor.visitVarInsn(load, instrumentedMethod.getStackSize());
                            convertValue(methodVisitor, methodSizeHandler);
                            Label noSkip = new Label();
                            methodVisitor.visitJumpInsn(inverted
                                    ? nonDefaultJump
                                    : defaultJump, noSkip);
                            skipHandler.apply(methodVisitor);
                            methodVisitor.visitLabel(noSkip);
                            stackMapFrameHandler.injectCompletionFrame(methodVisitor, true);
                        }

                        /**
                         * Converts the return value to an {@code int} value.
                         *
                         * @param methodVisitor     The method visitor to use.
                         * @param methodSizeHandler The method size handler of the advice method to use.
                         */
                        protected abstract void convertValue(MethodVisitor methodVisitor, MethodSizeHandler.ForAdvice methodSizeHandler);

                        /**
                         * Returns an inverted version of this skip dispatcher.
                         *
                         * @return An inverted version of this skip dispatcher.
                         */
                        private SkipDispatcher inverted() {
                            return new Inverted();
                        }

                        @Override
                        public String toString() {
                            return "Advice.Dispatcher.Resolved.ForMethodEnter.SkipDispatcher.ForValue." + name();
                        }

                        /**
                         * An inverted version of a value-based skipped dispatcher that triggers upon observing a non-default value.
                         */
                        protected class Inverted implements SkipDispatcher {

                            @Override
                            public void apply(MethodVisitor methodVisitor,
                                              MethodSizeHandler.ForAdvice methodSizeHandler,
                                              StackMapFrameHandler.ForAdvice stackMapFrameHandler,
                                              MethodDescription instrumentedMethod,
                                              Bound.SkipHandler skipHandler) {
                                doApply(methodVisitor, methodSizeHandler, stackMapFrameHandler, instrumentedMethod, skipHandler, true);
                            }

                            /**
                             * Returns the outer instance.
                             *
                             * @return The outer instance.
                             */
                            private SkipDispatcher getOuter() {
                                return ForValue.this;
                            }

                            @Override
                            public int hashCode() {
                                return ForValue.this.hashCode();
                            }

                            @Override
                            public boolean equals(Object other) {
                                if (other == this) {
                                    return true;
                                }
                                if (!(other instanceof Inverted)) {
                                    return false;
                                }
                                Inverted inverted = (Inverted) other;
                                return inverted.getOuter().equals(ForValue.this);
                            }

                            @Override
                            public String toString() {
                                return "Advice.Dispatcher.Resolved.ForMethodEnter.SkipDispatcher.ForValue.Inverted{" +
                                        "outer=" + ForValue.this +
                                        "}";
                            }
                        }
                    }

                    /**
                     * A skip dispatcher that skips a value if it is of a given instance.
                     */
                    class ForType implements SkipDispatcher {

                        /**
                         * The type for which to skip instances.
                         */
                        private final TypeDescription typeDescription;

                        /**
                         * Creates a new skip dispatcher for a given type.
                         *
                         * @param typeDescription The type for which to skip instances.
                         */
                        protected ForType(TypeDescription typeDescription) {
                            this.typeDescription = typeDescription;
                        }

                        /**
                         * Creates a skip dispatcher for an advice method.
                         *
                         * @param adviceMethod The advice method for which to resolve a skip dispatcher.
                         * @return An appropriate skip dispatcher.
                         */
                        public static SkipDispatcher of(MethodDescription adviceMethod) {
                            return of(adviceMethod.getDeclaredAnnotations()
                                    .ofType(OnMethodEnter.class)
                                    .getValue(SKIP_ON)
                                    .resolve(TypeDescription.class), adviceMethod);
                        }

                        /**
                         * Creates a skip dispatcher for a given annotation type and advice method.
                         *
                         * @param typeDescription The type that was specified as an annotation value.
                         * @param adviceMethod    The advice method.
                         * @return An appropriate skip dispatcher.
                         */
                        protected static SkipDispatcher of(TypeDescription typeDescription, MethodDescription adviceMethod) {
                            if (typeDescription.represents(void.class)) {
                                return Disabled.INSTANCE;
                            } else if (typeDescription.represents(OnDefaultValue.class)) {
                                return ForValue.of(adviceMethod.getReturnType(), false);
                            } else if (typeDescription.represents(OnNonDefaultValue.class)) {
                                return ForValue.of(adviceMethod.getReturnType(), true);
                            } else if (typeDescription.isPrimitive() || adviceMethod.getReturnType().isPrimitive()) {
                                throw new IllegalStateException("Cannot skip method by instance type for primitive return value on " + adviceMethod);
                            } else {
                                return new ForType(typeDescription);
                            }
                        }

                        @Override
                        public void apply(MethodVisitor methodVisitor,
                                          MethodSizeHandler.ForAdvice methodSizeHandler,
                                          StackMapFrameHandler.ForAdvice stackMapFrameHandler,
                                          MethodDescription instrumentedMethod,
                                          Bound.SkipHandler skipHandler) {
                            methodVisitor.visitVarInsn(Opcodes.ALOAD, instrumentedMethod.getStackSize());
                            methodVisitor.visitTypeInsn(Opcodes.INSTANCEOF, typeDescription.getInternalName());
                            Label noSkip = new Label();
                            methodVisitor.visitJumpInsn(Opcodes.IFEQ, noSkip);
                            skipHandler.apply(methodVisitor);
                            methodVisitor.visitLabel(noSkip);
                            stackMapFrameHandler.injectCompletionFrame(methodVisitor, true);
                        }

                        @Override
                        public boolean equals(Object other) {
                            if (this == other) return true;
                            if (other == null || getClass() != other.getClass()) return false;
                            ForType forType = (ForType) other;
                            return typeDescription.equals(forType.typeDescription);
                        }

                        @Override
                        public int hashCode() {
                            return typeDescription.hashCode();
                        }

                        @Override
                        public String toString() {
                            return "Advice.Dispatcher.Resolved.ForMethodEnter.SkipDispatcher.ForType{" +
                                    "typeDescription=" + typeDescription +
                                    '}';
                        }
                    }
                }
            }

            /**
             * Represents a resolved dispatcher for exiting a method.
             */
            interface ForMethodExit extends Resolved {

                /**
                 * Returns the type of throwable for which this exit advice is supposed to be invoked.
                 *
                 * @return The {@link Throwable} type for which to invoke this exit advice or a description of {@link NoExceptionHandler}
                 * if this exit advice does not expect to be invoked upon any throwable.
                 */
                TypeDescription getTriggeringThrowable();

                @Override
                Bound.ForMethodExit bind(TypeDescription instrumentedType,
                                         MethodDescription instrumentedMethod,
                                         MethodVisitor methodVisitor,
                                         Implementation.Context implementationContext,
                                         MethodSizeHandler.ForInstrumentedMethod methodSizeHandler,
                                         StackMapFrameHandler.ForInstrumentedMethod stackMapFrameHandler);
            }
        }

        /**
         * A bound resolution of an advice method.
         */
        interface Bound {

            /**
             * Prepares the advice method's exception handlers.
             */
            void prepare();

            /**
             * A skip handler is responsible for writing code that skips the invocation of the original code
             * within the instrumented method.
             */
            interface SkipHandler {

                /**
                 * Applies this skip handler.
                 *
                 * @param methodVisitor The method visitor to write the code to.
                 */
                void apply(MethodVisitor methodVisitor);
            }

            /**
             * A bound dispatcher for a method enter.
             */
            interface ForMethodEnter extends Bound {

                /**
                 * Applies this dispatcher.
                 *
                 * @param skipHandler The skip handler to use.
                 */
                void apply(SkipHandler skipHandler);
            }

            /**
             * A bound dispatcher for a method exit.
             */
            interface ForMethodExit extends Bound {

                /**
                 * Applies this dispatcher.
                 */
                void apply();
            }
        }

        /**
         * An implementation for inactive devise that does not write any byte code.
         */
        enum Inactive implements Dispatcher.Unresolved, Resolved.ForMethodEnter, Resolved.ForMethodExit, Bound.ForMethodEnter, Bound.ForMethodExit {

            /**
             * The singleton instance.
             */
            INSTANCE;

            @Override
            public boolean isAlive() {
                return false;
            }

            @Override
            public boolean isBinary() {
                return false;
            }

            @Override
            public TypeDescription getTriggeringThrowable() {
                return NoExceptionHandler.DESCRIPTION;
            }

            @Override
            public TypeDescription getEnterType() {
                return TypeDescription.VOID;
            }

            @Override
            public boolean isPrependLineNumber() {
                return false;
            }

            @Override
            public Resolved.ForMethodEnter asMethodEnter(List<? extends OffsetMapping.Factory> userFactories,
                                                         ClassReader classReader) {
                return this;
            }

            @Override
            public Resolved.ForMethodExit asMethodExitTo(List<? extends OffsetMapping.Factory> userFactories,
                                                         ClassReader classReader,
                                                         Resolved.ForMethodEnter dispatcher) {
                return this;
            }

            @Override
            public void prepare() {
                /* do nothing */
            }

            @Override
            public void apply() {
                /* do nothing */
            }

            @Override
            public void apply(SkipHandler skipHandler) {
                /* do nothing */
            }

            @Override
            public Inactive bind(TypeDescription instrumentedType,
                                 MethodDescription instrumentedMethod,
                                 MethodVisitor methodVisitor,
                                 Implementation.Context implementationContext,
                                 MethodSizeHandler.ForInstrumentedMethod methodSizeHandler,
                                 StackMapFrameHandler.ForInstrumentedMethod stackMapFrameHandler) {
                return this;
            }

            @Override
            public String toString() {
                return "Advice.Dispatcher.Inactive." + name();
            }
        }

        /**
         * A dispatcher for an advice method that is being inlined into the instrumented method.
         */
        class Inlining implements Unresolved {

            /**
             * The advice method.
             */
            protected final MethodDescription.InDefinedShape adviceMethod;

            /**
             * Creates a dispatcher for inlined advice method.
             *
             * @param adviceMethod The advice method.
             */
            protected Inlining(MethodDescription.InDefinedShape adviceMethod) {
                this.adviceMethod = adviceMethod;
            }

            @Override
            public boolean isAlive() {
                return true;
            }

            @Override
            public boolean isBinary() {
                return true;
            }

            @Override
            public Dispatcher.Resolved.ForMethodEnter asMethodEnter(List<? extends OffsetMapping.Factory> userFactories,
                                                                    ClassReader classReader) {
                return new Resolved.ForMethodEnter(adviceMethod, userFactories, classReader);
            }

            @Override
            public Dispatcher.Resolved.ForMethodExit asMethodExitTo(List<? extends OffsetMapping.Factory> userFactories,
                                                                    ClassReader classReader,
                                                                    Dispatcher.Resolved.ForMethodEnter dispatcher) {
                return Resolved.ForMethodExit.of(adviceMethod, userFactories, classReader, dispatcher.getEnterType());
            }

            @Override
            public boolean equals(Object other) {
                return this == other || !(other == null || getClass() != other.getClass()) && adviceMethod.equals(((Inlining) other).adviceMethod);
            }

            @Override
            public int hashCode() {
                return adviceMethod.hashCode();
            }

            @Override
            public String toString() {
                return "Advice.Dispatcher.Inlining{" +
                        "adviceMethod=" + adviceMethod +
                        '}';
            }

            /**
             * A resolved version of a dispatcher.
             */
            protected abstract static class Resolved implements Dispatcher.Resolved {

                /**
                 * Indicates a read-only mapping for an offset.
                 */
                private static final boolean READ_ONLY = true;

                /**
                 * The represented advice method.
                 */
                protected final MethodDescription.InDefinedShape adviceMethod;

                /**
                 * A class reader to query for the class file of the advice method.
                 */
                protected final ClassReader classReader;

                /**
                 * An unresolved mapping of offsets of the advice method based on the annotations discovered on each method parameter.
                 */
                protected final Map<Integer, OffsetMapping> offsetMappings;

                /**
                 * The suppression handler to use.
                 */
                protected final SuppressionHandler suppressionHandler;

                /**
                 * Creates a new resolved version of a dispatcher.
                 *
                 * @param adviceMethod  The represented advice method.
                 * @param factories     A list of factories to resolve for the parameters of the advice method.
                 * @param classReader   A class reader to query for the class file of the advice method.
                 * @param throwableType The type to handle by a suppression handler or {@link NoExceptionHandler} to not handle any exceptions.
                 */
                protected Resolved(MethodDescription.InDefinedShape adviceMethod,
                                   List<OffsetMapping.Factory> factories,
                                   ClassReader classReader,
                                   TypeDescription throwableType) {
                    this.adviceMethod = adviceMethod;
                    offsetMappings = new HashMap<Integer, OffsetMapping>();
                    for (ParameterDescription.InDefinedShape parameterDescription : adviceMethod.getParameters()) {
                        OffsetMapping offsetMapping = OffsetMapping.Factory.UNDEFINED;
                        for (OffsetMapping.Factory factory : factories) {
                            OffsetMapping possible = factory.make(parameterDescription);
                            if (possible != null) {
                                if (offsetMapping == null) {
                                    offsetMapping = possible;
                                } else {
                                    throw new IllegalStateException(parameterDescription + " is bound to both " + possible + " and " + offsetMapping);
                                }
                            }
                        }
                        offsetMappings.put(parameterDescription.getOffset(), offsetMapping == null
                                ? new OffsetMapping.ForParameter(parameterDescription.getIndex(), READ_ONLY, parameterDescription.getType().asErasure())
                                : offsetMapping);
                    }
                    this.classReader = classReader;
                    suppressionHandler = SuppressionHandler.Suppressing.of(throwableType);
                }

                @Override
                public boolean isAlive() {
                    return true;
                }

                /**
                 * Applies a resolution for a given instrumented method.
                 *
                 * @param methodVisitor        A method visitor for writing byte code to the instrumented method.
                 * @param methodSizeHandler    A handler for computing the method size requirements.
                 * @param stackMapFrameHandler A handler for translating and injecting stack map frames.
                 * @param instrumentedType     A description of the instrumented type.
                 * @param instrumentedMethod   A description of the instrumented method.
                 * @param suppressionHandler   The bound suppression handler that is used for suppressing exceptions of this advice method.
                 * @return A method visitor for visiting the advice method's byte code.
                 */
                protected abstract MethodVisitor apply(MethodVisitor methodVisitor,
                                                       Implementation.Context implementationContext,
                                                       MethodSizeHandler.ForInstrumentedMethod methodSizeHandler,
                                                       StackMapFrameHandler.ForInstrumentedMethod stackMapFrameHandler,
                                                       TypeDescription instrumentedType,
                                                       MethodDescription instrumentedMethod,
                                                       SuppressionHandler.Bound suppressionHandler);

                @Override
                public boolean equals(Object other) {
                    if (this == other) return true;
                    if (other == null || getClass() != other.getClass()) return false;
                    Inlining.Resolved resolved = (Inlining.Resolved) other;
                    return adviceMethod.equals(resolved.adviceMethod) && offsetMappings.equals(resolved.offsetMappings);
                }

                @Override
                public int hashCode() {
                    int result = adviceMethod.hashCode();
                    result = 31 * result + offsetMappings.hashCode();
                    return result;
                }

                /**
                 * A bound advice method that copies the code by first extracting the exception table and later appending the
                 * code of the method without copying any meta data.
                 */
                protected abstract class AdviceMethodInliner extends ClassVisitor implements Bound {

                    /**
                     * A description of the instrumented type.
                     */
                    protected final TypeDescription instrumentedType;

                    /**
                     * The instrumented method.
                     */
                    protected final MethodDescription instrumentedMethod;

                    /**
                     * The method visitor for writing the instrumented method.
                     */
                    protected final MethodVisitor methodVisitor;

                    protected final Implementation.Context implementationContext;

                    /**
                     * A handler for computing the method size requirements.
                     */
                    protected final MethodSizeHandler.ForInstrumentedMethod methodSizeHandler;

                    /**
                     * A handler for translating and injecting stack map frames.
                     */
                    protected final StackMapFrameHandler.ForInstrumentedMethod stackMapFrameHandler;

                    /**
                     * A bound suppression handler that is used for suppressing exceptions of this advice method.
                     */
                    protected final SuppressionHandler.Bound suppressionHandler;

                    /**
                     * A class reader for parsing the class file containing the represented advice method.
                     */
                    protected final ClassReader classReader;

                    /**
                     * The labels that were found during parsing the method's exception handler in the order of their discovery.
                     */
                    protected List<Label> labels;

                    /**
                     * Creates a new advice method inliner.
                     *
                     * @param instrumentedType     A description of the instrumented type.
                     * @param instrumentedMethod   The instrumented method.
                     * @param methodVisitor        The method visitor for writing the instrumented method.
                     * @param methodSizeHandler    A handler for computing the method size requirements.
                     * @param stackMapFrameHandler A handler for translating and injecting stack map frames.
                     * @param suppressionHandler   A bound suppression handler that is used for suppressing exceptions of this advice method.
                     * @param classReader          A class reader for parsing the class file containing the represented advice method.
                     */
                    protected AdviceMethodInliner(TypeDescription instrumentedType,
                                                  MethodDescription instrumentedMethod,
                                                  MethodVisitor methodVisitor,
                                                  Implementation.Context implementationContext,
                                                  MethodSizeHandler.ForInstrumentedMethod methodSizeHandler,
                                                  StackMapFrameHandler.ForInstrumentedMethod stackMapFrameHandler,
                                                  SuppressionHandler.Bound suppressionHandler,
                                                  ClassReader classReader) {
                        super(Opcodes.ASM5);
                        this.instrumentedType = instrumentedType;
                        this.instrumentedMethod = instrumentedMethod;
                        this.methodVisitor = methodVisitor;
                        this.implementationContext = implementationContext;
                        this.methodSizeHandler = methodSizeHandler;
                        this.stackMapFrameHandler = stackMapFrameHandler;
                        this.suppressionHandler = suppressionHandler;
                        this.classReader = classReader;
                        labels = new ArrayList<Label>();
                    }

                    @Override
                    public void prepare() {
                        classReader.accept(new ExceptionTableExtractor(), ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
                        suppressionHandler.onPrepare(methodVisitor);
                    }

                    /**
                     * Inlines the advice method.
                     */
                    protected void doApply() {
                        classReader.accept(this, ClassReader.SKIP_DEBUG | stackMapFrameHandler.getReaderHint());
                    }

                    @Override
                    public MethodVisitor visitMethod(int modifiers, String internalName, String descriptor, String signature, String[] exception) {
                        return adviceMethod.getInternalName().equals(internalName) && adviceMethod.getDescriptor().equals(descriptor)
                                ? new ExceptionTableSubstitutor(Inlining.Resolved.this.apply(methodVisitor, implementationContext, methodSizeHandler, stackMapFrameHandler, instrumentedType, instrumentedMethod, suppressionHandler))
                                : IGNORE_METHOD;
                    }

                    /**
                     * A class visitor that extracts the exception tables of the advice method.
                     */
                    protected class ExceptionTableExtractor extends ClassVisitor {

                        /**
                         * Creates a new exception table extractor.
                         */
                        protected ExceptionTableExtractor() {
                            super(Opcodes.ASM5);
                        }

                        @Override
                        public MethodVisitor visitMethod(int modifiers, String internalName, String descriptor, String signature, String[] exception) {
                            return adviceMethod.getInternalName().equals(internalName) && adviceMethod.getDescriptor().equals(descriptor)
                                    ? new ExceptionTableCollector(methodVisitor)
                                    : IGNORE_METHOD;
                        }

                        @Override
                        public String toString() {
                            return "Advice.Dispatcher.Inlining.Resolved.AdviceMethodInliner.ExceptionTableExtractor{" +
                                    "methodVisitor=" + methodVisitor +
                                    '}';
                        }
                    }

                    /**
                     * A visitor that only writes try-catch-finally blocks to the supplied method visitor. All labels of these tables are collected
                     * for substitution when revisiting the reminder of the method.
                     */
                    protected class ExceptionTableCollector extends MethodVisitor {

                        /**
                         * The method visitor for which the try-catch-finally blocks should be written.
                         */
                        private final MethodVisitor methodVisitor;

                        /**
                         * Creates a new exception table collector.
                         *
                         * @param methodVisitor The method visitor for which the try-catch-finally blocks should be written.
                         */
                        protected ExceptionTableCollector(MethodVisitor methodVisitor) {
                            super(Opcodes.ASM5);
                            this.methodVisitor = methodVisitor;
                        }

                        @Override
                        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                            methodVisitor.visitTryCatchBlock(start, end, handler, type);
                            labels.addAll(Arrays.asList(start, end, handler));
                        }

                        @Override
                        public AnnotationVisitor visitTryCatchAnnotation(int typeReference, TypePath typePath, String descriptor, boolean visible) {
                            return methodVisitor.visitTryCatchAnnotation(typeReference, typePath, descriptor, visible);
                        }

                        @Override
                        public String toString() {
                            return "Advice.Dispatcher.Inlining.Resolved.AdviceMethodInliner.ExceptionTableCollector{" +
                                    "methodVisitor=" + methodVisitor +
                                    '}';
                        }
                    }

                    /**
                     * A label substitutor allows to visit an advice method a second time after the exception handlers were already written.
                     * Doing so, this visitor substitutes all labels that were already created during the first visit to keep the mapping
                     * consistent. It is not required to resolve labels for non-code instructions as meta information is not propagated to
                     * the target method visitor for advice code.
                     */
                    protected class ExceptionTableSubstitutor extends MethodVisitor {

                        /**
                         * A map containing resolved substitutions.
                         */
                        private final Map<Label, Label> substitutions;

                        /**
                         * The current index of the visited labels that are used for try-catch-finally blocks.
                         */
                        private int index;

                        /**
                         * Creates a label substitor.
                         *
                         * @param methodVisitor The method visitor for which to substitute labels.
                         */
                        protected ExceptionTableSubstitutor(MethodVisitor methodVisitor) {
                            super(Opcodes.ASM5, methodVisitor);
                            substitutions = new IdentityHashMap<Label, Label>();
                        }

                        @Override
                        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                            substitutions.put(start, labels.get(index++));
                            substitutions.put(end, labels.get(index++));
                            Label actualHandler = labels.get(index++);
                            substitutions.put(handler, actualHandler);
                            ((CodeTranslationVisitor) mv).propagateHandler(actualHandler);
                        }

                        @Override
                        public AnnotationVisitor visitTryCatchAnnotation(int typeReference, TypePath typePath, String descriptor, boolean visible) {
                            return IGNORE_ANNOTATION;
                        }

                        @Override
                        public void visitLabel(Label label) {
                            super.visitLabel(resolve(label));
                        }

                        @Override
                        public void visitJumpInsn(int opcode, Label label) {
                            super.visitJumpInsn(opcode, resolve(label));
                        }

                        @Override
                        public void visitTableSwitchInsn(int minimum, int maximum, Label defaultOption, Label... label) {
                            super.visitTableSwitchInsn(minimum, maximum, defaultOption, resolve(label));
                        }

                        @Override
                        public void visitLookupSwitchInsn(Label defaultOption, int[] keys, Label[] label) {
                            super.visitLookupSwitchInsn(resolve(defaultOption), keys, resolve(label));
                        }

                        /**
                         * Resolves an array of labels.
                         *
                         * @param label The labels to resolved.
                         * @return An array containing the resolved arrays.
                         */
                        private Label[] resolve(Label[] label) {
                            Label[] resolved = new Label[label.length];
                            int index = 0;
                            for (Label aLabel : label) {
                                resolved[index++] = resolve(aLabel);
                            }
                            return resolved;
                        }

                        /**
                         * Resolves a single label if mapped or returns the original label.
                         *
                         * @param label The label to resolve.
                         * @return The resolved label.
                         */
                        private Label resolve(Label label) {
                            Label substitution = substitutions.get(label);
                            return substitution == null
                                    ? label
                                    : substitution;
                        }

                        @Override
                        public String toString() {
                            return "Advice.Dispatcher.Inlining.Resolved.AdviceMethodInliner.ExceptionTableSubstitutor{" +
                                    "methodVisitor=" + methodVisitor +
                                    ", substitutions=" + substitutions +
                                    ", index=" + index +
                                    '}';
                        }
                    }
                }

                /**
                 * A resolved dispatcher for implementing method enter advice.
                 */
                protected static class ForMethodEnter extends Inlining.Resolved implements Dispatcher.Resolved.ForMethodEnter {

                    /**
                     * The skip dispatcher to use.
                     */
                    private final SkipDispatcher skipDispatcher;

                    /**
                     * {@code true} if the first discovered line number information should be prepended to the advice code.
                     */
                    private final boolean prependLineNumber;

                    /**
                     * Creates a new resolved dispatcher for implementing method enter advice.
                     *
                     * @param adviceMethod  The represented advice method.
                     * @param userFactories A list of user-defined factories for offset mappings.
                     * @param classReader   A class reader to query for the class file of the advice method.
                     */
                    @SuppressWarnings("unchecked") // In absence of @SafeVarargs for Java 6
                    protected ForMethodEnter(MethodDescription.InDefinedShape adviceMethod,
                                             List<? extends OffsetMapping.Factory> userFactories,
                                             ClassReader classReader) {
                        super(adviceMethod,
                                CompoundList.of(Arrays.asList(OffsetMapping.ForParameter.Factory.READ_WRITE,
                                        OffsetMapping.ForBoxedArguments.Factory.READ_WRITE,
                                        OffsetMapping.ForThisReference.Factory.READ_WRITE,
                                        OffsetMapping.ForField.Factory.READ_WRITE,
                                        OffsetMapping.ForOrigin.Factory.INSTANCE,
                                        OffsetMapping.ForUnusedValue.Factory.INSTANCE,
                                        OffsetMapping.ForStubValue.INSTANCE,
                                        new OffsetMapping.Illegal(Thrown.class, Enter.class, Return.class, BoxedReturn.class)), userFactories),
                                classReader,
                                adviceMethod.getDeclaredAnnotations().ofType(OnMethodEnter.class).getValue(SUPPRESS_ENTER).resolve(TypeDescription.class));
                        skipDispatcher = SkipDispatcher.ForType.of(adviceMethod);
                        prependLineNumber = adviceMethod.getDeclaredAnnotations().ofType(OnMethodEnter.class).getValue(PREPEND_LINE_NUMBER).resolve(Boolean.class);
                    }

                    @Override
                    public Bound.ForMethodEnter bind(TypeDescription instrumentedType,
                                                     MethodDescription instrumentedMethod,
                                                     MethodVisitor methodVisitor,
                                                     Implementation.Context implementationContext,
                                                     MethodSizeHandler.ForInstrumentedMethod methodSizeHandler,
                                                     StackMapFrameHandler.ForInstrumentedMethod stackMapFrameHandler) {
                        return new AdviceMethodInliner(instrumentedType,
                                instrumentedMethod,
                                methodVisitor,
                                implementationContext,
                                methodSizeHandler,
                                stackMapFrameHandler,
                                suppressionHandler.bind(),
                                classReader,
                                skipDispatcher);
                    }

                    @Override
                    public TypeDescription getEnterType() {
                        return adviceMethod.getReturnType().asErasure();
                    }

                    @Override
                    public boolean isPrependLineNumber() {
                        return prependLineNumber;
                    }

                    @Override
                    protected MethodVisitor apply(MethodVisitor methodVisitor,
                                                  Implementation.Context implementationContext,
                                                  MethodSizeHandler.ForInstrumentedMethod methodSizeHandler,
                                                  StackMapFrameHandler.ForInstrumentedMethod stackMapFrameHandler,
                                                  TypeDescription instrumentedType,
                                                  MethodDescription instrumentedMethod,
                                                  SuppressionHandler.Bound suppressionHandler) {
                        Map<Integer, OffsetMapping.Target> offsetMappings = new HashMap<Integer, OffsetMapping.Target>();
                        for (Map.Entry<Integer, OffsetMapping> entry : this.offsetMappings.entrySet()) {
                            offsetMappings.put(entry.getKey(), entry.getValue().resolve(instrumentedType,
                                    instrumentedMethod,
                                    OffsetMapping.Context.ForMethodEntry.of(instrumentedMethod)));
                        }
                        return new CodeTranslationVisitor.ForMethodEnter(methodVisitor,
                                implementationContext,
                                methodSizeHandler.bindEntry(adviceMethod),
                                stackMapFrameHandler.bindEntry(adviceMethod),
                                instrumentedMethod,
                                adviceMethod,
                                offsetMappings,
                                suppressionHandler);
                    }

                    @Override
                    public boolean equals(Object other) {
                        if (this == other) return true;
                        if (other == null || getClass() != other.getClass()) return false;
                        if (!super.equals(other)) return false;
                        Inlining.Resolved.ForMethodEnter that = (Inlining.Resolved.ForMethodEnter) other;
                        return skipDispatcher == that.skipDispatcher && prependLineNumber == that.prependLineNumber;
                    }

                    @Override
                    public int hashCode() {
                        int result = super.hashCode();
                        result = 31 * result + skipDispatcher.hashCode();
                        result = 31 * result + (prependLineNumber ? 1 : 0);
                        return result;
                    }

                    @Override
                    public String toString() {
                        return "Advice.Dispatcher.Inlining.Resolved.ForMethodEnter{" +
                                "adviceMethod=" + adviceMethod +
                                ", offsetMappings=" + offsetMappings +
                                ", skipDispatcher=" + skipDispatcher +
                                ", prependLineNumber=" + prependLineNumber +
                                '}';
                    }

                    /**
                     * An advice method inliner for a method enter.
                     */
                    protected class AdviceMethodInliner extends Inlining.Resolved.AdviceMethodInliner implements Bound.ForMethodEnter {

                        /**
                         * The skip dispatcher to use.
                         */
                        private final SkipDispatcher skipDispatcher;

                        /**
                         * Creates a new advice method inliner for a method enter.
                         *
                         * @param instrumentedType     A description of the instrumented type.
                         * @param instrumentedMethod   A description of the instrumented method.
                         * @param methodVisitor        The method visitor for writing the instrumented method.
                         * @param methodSizeHandler    A handler for computing the method size requirements.
                         * @param stackMapFrameHandler A handler for translating and injecting stack map frames.
                         * @param suppressionHandler   A bound suppression handler that is used for suppressing exceptions of this advice method.
                         * @param classReader          A class reader for parsing the class file containing the represented advice method.
                         * @param skipDispatcher       The skip dispatcher to use.
                         */
                        public AdviceMethodInliner(TypeDescription instrumentedType,
                                                   MethodDescription instrumentedMethod,
                                                   MethodVisitor methodVisitor,
                                                   Implementation.Context implementationContext,
                                                   MethodSizeHandler.ForInstrumentedMethod methodSizeHandler,
                                                   StackMapFrameHandler.ForInstrumentedMethod stackMapFrameHandler,
                                                   SuppressionHandler.Bound suppressionHandler,
                                                   ClassReader classReader,
                                                   SkipDispatcher skipDispatcher) {
                            super(instrumentedType,
                                    instrumentedMethod,
                                    methodVisitor,
                                    implementationContext,
                                    methodSizeHandler,
                                    stackMapFrameHandler,
                                    suppressionHandler,
                                    classReader);
                            this.skipDispatcher = skipDispatcher;
                        }

                        @Override
                        public void apply(SkipHandler skipHandler) {
                            doApply();
                            skipDispatcher.apply(methodVisitor,
                                    methodSizeHandler.bindEntry(adviceMethod),
                                    stackMapFrameHandler.bindEntry(adviceMethod),
                                    instrumentedMethod, skipHandler);
                        }

                        @Override
                        public String toString() {
                            return "Advice.Dispatcher.Inlining.Resolved.ForMethodEnter.AdviceMethodInliner{" +
                                    "instrumentedType=" + instrumentedType +
                                    ", instrumentedMethod=" + instrumentedMethod +
                                    ", methodVisitor=" + methodVisitor +
                                    ", methodSizeHandler=" + methodSizeHandler +
                                    ", stackMapFrameHandler=" + stackMapFrameHandler +
                                    ", suppressionHandler=" + suppressionHandler +
                                    ", classReader=" + classReader +
                                    ", labels=" + labels +
                                    ", skipDispatcher=" + skipDispatcher +
                                    '}';
                        }
                    }
                }

                /**
                 * A resolved dispatcher for implementing method exit advice.
                 */
                protected abstract static class ForMethodExit extends Inlining.Resolved implements Dispatcher.Resolved.ForMethodExit {

                    /**
                     * The additional stack size to consider when accessing the local variable array.
                     */
                    private final TypeDescription enterType;

                    /**
                     * Creates a new resolved dispatcher for implementing method exit advice.
                     *
                     * @param adviceMethod  The represented advice method.
                     * @param userFactories A list of user-defined factories for offset mappings.
                     * @param classReader   The class reader for parsing the advice method's class file.
                     * @param enterType     The type of the value supplied by the enter advice method or
                     *                      a description of {@code void} if no such value exists.
                     */
                    protected ForMethodExit(MethodDescription.InDefinedShape adviceMethod,
                                            List<? extends OffsetMapping.Factory> userFactories,
                                            ClassReader classReader,
                                            TypeDescription enterType) {
                        super(adviceMethod,
                                CompoundList.of(Arrays.asList(
                                        OffsetMapping.ForParameter.Factory.READ_WRITE,
                                        OffsetMapping.ForBoxedArguments.Factory.READ_WRITE,
                                        OffsetMapping.ForThisReference.Factory.READ_WRITE,
                                        OffsetMapping.ForField.Factory.READ_WRITE,
                                        OffsetMapping.ForOrigin.Factory.INSTANCE,
                                        OffsetMapping.ForUnusedValue.Factory.INSTANCE,
                                        OffsetMapping.ForStubValue.INSTANCE,
                                        new OffsetMapping.ForEnterValue.Factory(enterType, false),
                                        OffsetMapping.ForReturnValue.Factory.READ_WRITE,
                                        OffsetMapping.ForBoxedReturnValue.Factory.READ_WRITE,
                                        OffsetMapping.ForThrowable.Factory.of(adviceMethod, false)
                                ), userFactories),
                                classReader,
                                adviceMethod.getDeclaredAnnotations().ofType(OnMethodExit.class).getValue(SUPPRESS_EXIT).resolve(TypeDescription.class));
                        this.enterType = enterType;
                    }

                    /**
                     * Resolves exit advice that handles exceptions depending on the specification of the exit advice.
                     *
                     * @param adviceMethod  The advice method.
                     * @param userFactories A list of user-defined factories for offset mappings.
                     * @param classReader   The class reader for parsing the advice method's class file.
                     * @param enterType     The type of the value supplied by the enter advice method or
                     *                      a description of {@code void} if no such value exists.
                     * @return An appropriate exit handler.
                     */
                    protected static Resolved.ForMethodExit of(MethodDescription.InDefinedShape adviceMethod,
                                                               List<? extends OffsetMapping.Factory> userFactories,
                                                               ClassReader classReader,
                                                               TypeDescription enterType) {
                        TypeDescription triggeringThrowable = adviceMethod.getDeclaredAnnotations()
                                .ofType(OnMethodExit.class)
                                .getValue(ON_THROWABLE).resolve(TypeDescription.class);
                        return triggeringThrowable.represents(NoExceptionHandler.class)
                                ? new WithoutExceptionHandler(adviceMethod, userFactories, classReader, enterType)
                                : new WithExceptionHandler(adviceMethod, userFactories, classReader, enterType, triggeringThrowable);
                    }

                    @Override
                    protected MethodVisitor apply(MethodVisitor methodVisitor,
                                                  Implementation.Context implementationContext,
                                                  MethodSizeHandler.ForInstrumentedMethod methodSizeHandler,
                                                  StackMapFrameHandler.ForInstrumentedMethod stackMapFrameHandler,
                                                  TypeDescription instrumentedType,
                                                  MethodDescription instrumentedMethod,
                                                  SuppressionHandler.Bound suppressionHandler) {
                        Map<Integer, OffsetMapping.Target> offsetMappings = new HashMap<Integer, OffsetMapping.Target>();
                        for (Map.Entry<Integer, OffsetMapping> entry : this.offsetMappings.entrySet()) {
                            offsetMappings.put(entry.getKey(), entry.getValue().resolve(instrumentedType,
                                    instrumentedMethod,
                                    OffsetMapping.Context.ForMethodExit.of(enterType)));
                        }
                        return new CodeTranslationVisitor.ForMethodExit(methodVisitor,
                                implementationContext,
                                methodSizeHandler.bindExit(adviceMethod, getTriggeringThrowable().represents(NoExceptionHandler.class)),
                                stackMapFrameHandler.bindExit(adviceMethod),
                                instrumentedMethod,
                                adviceMethod,
                                offsetMappings,
                                suppressionHandler,
                                enterType.getStackSize().getSize() + getPadding().getSize());
                    }


                    @Override
                    public Bound.ForMethodExit bind(TypeDescription instrumentedType,
                                                    MethodDescription instrumentedMethod,
                                                    MethodVisitor methodVisitor,
                                                    Implementation.Context implementationContext,
                                                    MethodSizeHandler.ForInstrumentedMethod methodSizeHandler,
                                                    StackMapFrameHandler.ForInstrumentedMethod stackMapFrameHandler) {
                        return new AdviceMethodInliner(instrumentedType,
                                instrumentedMethod,
                                methodVisitor,
                                implementationContext,
                                methodSizeHandler,
                                stackMapFrameHandler,
                                suppressionHandler.bind(),
                                classReader);
                    }

                    /**
                     * Returns the additional padding this exit advice implies.
                     *
                     * @return The additional padding this exit advice implies.
                     */
                    protected abstract StackSize getPadding();

                    @Override
                    public boolean equals(Object other) {
                        return this == other || !(other == null || getClass() != other.getClass())
                                && super.equals(other)
                                && enterType == ((Inlining.Resolved.ForMethodExit) other).enterType;
                    }

                    @Override
                    public int hashCode() {
                        int result = super.hashCode();
                        result = 31 * result + enterType.hashCode();
                        return result;
                    }

                    /**
                     * An advice method inliner for a method exit.
                     */
                    protected class AdviceMethodInliner extends Inlining.Resolved.AdviceMethodInliner implements Bound.ForMethodExit {

                        /**
                         * Creates a new advice method inliner for a method exit.
                         *
                         * @param instrumentedType     A description of the instrumented type.
                         * @param instrumentedMethod   A description of the instrumented method.
                         * @param methodVisitor        The method visitor for writing the instrumented method.
                         * @param methodSizeHandler    A handler for computing the method size requirements.
                         * @param stackMapFrameHandler A handler for translating and injecting stack map frames.
                         * @param suppressionHandler   A bound suppression handler that is used for suppressing exceptions of this advice method.
                         * @param classReader          A class reader for parsing the class file containing the represented advice method.
                         */
                        public AdviceMethodInliner(TypeDescription instrumentedType,
                                                   MethodDescription instrumentedMethod,
                                                   MethodVisitor methodVisitor,
                                                   Implementation.Context implementationContext,
                                                   MethodSizeHandler.ForInstrumentedMethod methodSizeHandler,
                                                   StackMapFrameHandler.ForInstrumentedMethod stackMapFrameHandler,
                                                   SuppressionHandler.Bound suppressionHandler,
                                                   ClassReader classReader) {
                            super(instrumentedType,
                                    instrumentedMethod,
                                    methodVisitor,
                                    implementationContext,
                                    methodSizeHandler,
                                    stackMapFrameHandler,
                                    suppressionHandler,
                                    classReader);
                        }

                        @Override
                        public void apply() {
                            doApply();
                        }

                        @Override
                        public String toString() {
                            return "Advice.Dispatcher.Inlining.Resolved.ForMethodExit.AdviceMethodInliner{" +
                                    "instrumentedType=" + instrumentedType +
                                    ", instrumentedMethod=" + instrumentedMethod +
                                    ", methodVisitor=" + methodVisitor +
                                    ", methodSizeHandler=" + methodSizeHandler +
                                    ", stackMapFrameHandler=" + stackMapFrameHandler +
                                    ", suppressionHandler=" + suppressionHandler +
                                    ", classReader=" + classReader +
                                    ", labels=" + labels +
                                    '}';
                        }
                    }

                    /**
                     * Implementation of exit advice that handles exceptions.
                     */
                    protected static class WithExceptionHandler extends Inlining.Resolved.ForMethodExit {

                        /**
                         * The type of the handled throwable type for which this advice is invoked.
                         */
                        private final TypeDescription triggeringThrowable;

                        /**
                         * Creates a new resolved dispatcher for implementing method exit advice that handles exceptions.
                         *
                         * @param adviceMethod        The represented advice method.
                         * @param userFactories       A list of user-defined factories for offset mappings.
                         * @param classReader         The class reader for parsing the advice method's class file.
                         * @param enterType           The type of the value supplied by the enter advice method or
                         *                            a description of {@code void} if no such value exists.
                         * @param triggeringThrowable The type of the handled throwable type for which this advice is invoked.
                         */
                        protected WithExceptionHandler(MethodDescription.InDefinedShape adviceMethod,
                                                       List<? extends OffsetMapping.Factory> userFactories,
                                                       ClassReader classReader,
                                                       TypeDescription enterType,
                                                       TypeDescription triggeringThrowable) {
                            super(adviceMethod, userFactories, classReader, enterType);
                            this.triggeringThrowable = triggeringThrowable;
                        }

                        @Override
                        protected StackSize getPadding() {
                            return triggeringThrowable.getStackSize();
                        }

                        @Override
                        public TypeDescription getTriggeringThrowable() {
                            return triggeringThrowable;
                        }

                        @Override
                        public String toString() {
                            return "Advice.Dispatcher.Inlining.Resolved.ForMethodExit.WithExceptionHandler{" +
                                    "adviceMethod=" + adviceMethod +
                                    ", offsetMappings=" + offsetMappings +
                                    ", triggeringThrowable=" + triggeringThrowable +
                                    '}';
                        }
                    }

                    /**
                     * Implementation of exit advice that ignores exceptions.
                     */
                    protected static class WithoutExceptionHandler extends Inlining.Resolved.ForMethodExit {

                        /**
                         * Creates a new resolved dispatcher for implementing method exit advice that does not handle exceptions.
                         *
                         * @param adviceMethod  The represented advice method.
                         * @param userFactories A list of user-defined factories for offset mappings.
                         * @param classReader   A class reader to query for the class file of the advice method.
                         * @param enterType     The type of the value supplied by the enter advice method or
                         *                      a description of {@code void} if no such value exists.
                         */
                        protected WithoutExceptionHandler(MethodDescription.InDefinedShape adviceMethod,
                                                          List<? extends OffsetMapping.Factory> userFactories,
                                                          ClassReader classReader,
                                                          TypeDescription enterType) {
                            super(adviceMethod, userFactories, classReader, enterType);
                        }

                        @Override
                        protected StackSize getPadding() {
                            return StackSize.ZERO;
                        }

                        @Override
                        public TypeDescription getTriggeringThrowable() {
                            return NoExceptionHandler.DESCRIPTION;
                        }

                        @Override
                        public String toString() {
                            return "Advice.Dispatcher.Inlining.Resolved.ForMethodExit.WithoutExceptionHandler{" +
                                    "adviceMethod=" + adviceMethod +
                                    ", offsetMappings=" + offsetMappings +
                                    '}';
                        }
                    }
                }
            }

            /**
             * A visitor for translating an advice method's byte code for inlining into the instrumented method.
             */
            protected abstract static class CodeTranslationVisitor extends MethodVisitor implements SuppressionHandler.ReturnValueProducer {

                /**
                 * The original method visitor to which all instructions are eventually written to.
                 */
                protected final MethodVisitor methodVisitor;

                protected final Implementation.Context implementationContext;

                /**
                 * A handler for computing the method size requirements.
                 */
                protected final MethodSizeHandler.ForAdvice methodSizeHandler;

                /**
                 * A handler for translating and injecting stack map frames.
                 */
                protected final StackMapFrameHandler.ForAdvice stackMapFrameHandler;

                /**
                 * The instrumented method.
                 */
                protected final MethodDescription instrumentedMethod;

                /**
                 * The advice method.
                 */
                protected final MethodDescription.InDefinedShape adviceMethod;

                /**
                 * A mapping of offsets to resolved target offsets in the instrumented method.
                 */
                private final Map<Integer, Resolved.OffsetMapping.Target> offsetMappings;

                /**
                 * A handler for optionally suppressing exceptions.
                 */
                private final SuppressionHandler.Bound suppressionHandler;

                /**
                 * A label indicating the end of the advice byte code.
                 */
                protected final Label endOfMethod;

                /**
                 * Creates a new code translation visitor.
                 *
                 * @param methodVisitor         A method visitor for writing the instrumented method's byte code.
                 * @param implementationContext
                 * @param methodSizeHandler     A handler for computing the method size requirements.
                 * @param stackMapFrameHandler  A handler for translating and injecting stack map frames.
                 * @param instrumentedMethod    The instrumented method.
                 * @param adviceMethod          The advice method.
                 * @param offsetMappings        A mapping of offsets to resolved target offsets in the instrumented method.
                 * @param suppressionHandler    The suppression handler to use.
                 */
                protected CodeTranslationVisitor(MethodVisitor methodVisitor,
                                                 Context implementationContext,
                                                 MethodSizeHandler.ForAdvice methodSizeHandler,
                                                 StackMapFrameHandler.ForAdvice stackMapFrameHandler,
                                                 MethodDescription instrumentedMethod,
                                                 MethodDescription.InDefinedShape adviceMethod,
                                                 Map<Integer, OffsetMapping.Target> offsetMappings,
                                                 SuppressionHandler.Bound suppressionHandler) {
                    super(Opcodes.ASM5, new StackAwareMethodVisitor(methodVisitor, instrumentedMethod));
                    this.methodVisitor = methodVisitor;
                    this.implementationContext = implementationContext;
                    this.methodSizeHandler = methodSizeHandler;
                    this.stackMapFrameHandler = stackMapFrameHandler;
                    this.instrumentedMethod = instrumentedMethod;
                    this.adviceMethod = adviceMethod;
                    this.offsetMappings = offsetMappings;
                    this.suppressionHandler = suppressionHandler;
                    endOfMethod = new Label();
                }

                /**
                 * Propagates a label for an exception handler that is typically suppressed by the overlaying
                 * {@link Resolved.AdviceMethodInliner.ExceptionTableSubstitutor}.
                 *
                 * @param label The label to register as a target for an exception handler.
                 */
                protected void propagateHandler(Label label) {
                    ((StackAwareMethodVisitor) mv).register(label, Collections.singletonList(StackSize.SINGLE));
                }

                @Override
                public void visitParameter(String name, int modifiers) {
                    /* do nothing */
                }

                @Override
                public AnnotationVisitor visitAnnotationDefault() {
                    return IGNORE_ANNOTATION;
                }

                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    return IGNORE_ANNOTATION;
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(int typeReference, TypePath typePath, String descriptor, boolean visible) {
                    return IGNORE_ANNOTATION;
                }

                @Override
                public AnnotationVisitor visitParameterAnnotation(int index, String descriptor, boolean visible) {
                    return IGNORE_ANNOTATION;
                }

                @Override
                public void visitAttribute(Attribute attribute) {
                    /* do nothing */
                }

                @Override
                public void visitCode() {
                    suppressionHandler.onStart(methodVisitor, methodSizeHandler);
                }

                @Override
                public void visitFrame(int frameType, int localVariableLength, Object[] localVariable, int stackSize, Object[] stack) {
                    stackMapFrameHandler.translateFrame(methodVisitor, frameType, localVariableLength, localVariable, stackSize, stack);
                }

                @Override
                public void visitEnd() {
                    suppressionHandler.onEnd(methodVisitor, stackMapFrameHandler, this);
                    methodVisitor.visitLabel(endOfMethod);
                    onMethodReturn();
                    stackMapFrameHandler.injectCompletionFrame(methodVisitor, false);
                }

                @Override
                public void visitMaxs(int stackSize, int localVariableLength) {
                    methodSizeHandler.recordMaxima(stackSize, localVariableLength);
                }

                @Override
                public void visitVarInsn(int opcode, int offset) {
                    Resolved.OffsetMapping.Target target = offsetMappings.get(offset);
                    if (target != null) {
                        StackManipulation stackManipulation;
                        StackSize expectedGrowth;
                        switch (opcode) {
                            case Opcodes.ILOAD:
                            case Opcodes.FLOAD:
                            case Opcodes.ALOAD:
                                stackManipulation = target.resolveRead();
                                expectedGrowth = StackSize.SINGLE;
                                break;
                            case Opcodes.DLOAD:
                            case Opcodes.LLOAD:
                                stackManipulation = target.resolveRead();
                                expectedGrowth = StackSize.DOUBLE;
                                break;
                            case Opcodes.ISTORE:
                            case Opcodes.FSTORE:
                            case Opcodes.ASTORE:
                            case Opcodes.LSTORE:
                            case Opcodes.DSTORE:
                                stackManipulation = target.resolveWrite();
                                expectedGrowth = StackSize.ZERO;
                                break;
                            default:
                                throw new IllegalStateException("Unexpected opcode: " + opcode);
                        }
                        methodSizeHandler.recordPadding(stackManipulation.apply(mv, null).getMaximalSize() - expectedGrowth.getSize());
                    } else {
                        mv.visitVarInsn(opcode, adjust(offset + instrumentedMethod.getStackSize() - adviceMethod.getStackSize()));
                    }
                }

                @Override
                public void visitIincInsn(int offset, int value) {
                    Resolved.OffsetMapping.Target target = offsetMappings.get(offset);
                    if (target != null) {
                        methodSizeHandler.recordPadding(target.resolveIncrement(value).apply(mv, null).getMaximalSize());
                    } else {
                        mv.visitIincInsn(adjust(offset + instrumentedMethod.getStackSize() - adviceMethod.getStackSize()), value);
                    }
                }

                /**
                 * Adjusts the offset of a variable instruction within the advice method such that no arguments to
                 * the instrumented method are overridden.
                 *
                 * @param offset The original offset.
                 * @return The adjusted offset.
                 */
                protected abstract int adjust(int offset);

                @Override
                public abstract void visitInsn(int opcode);

                /**
                 * Invoked after returning from the advice method.
                 */
                protected abstract void onMethodReturn();

                /**
                 * A code translation visitor that retains the return value of the represented advice method.
                 */
                protected static class ForMethodEnter extends CodeTranslationVisitor {

                    /**
                     * {@code true} if the method can return non-exceptionally.
                     */
                    private boolean doesReturn;

                    /**
                     * Creates a code translation visitor for translating exit advice.
                     *
                     * @param methodVisitor        A method visitor for writing the instrumented method's byte code.
                     * @param methodSizeHandler    A handler for computing the method size requirements.
                     * @param stackMapFrameHandler A handler for translating and injecting stack map frames.
                     * @param instrumentedMethod   The instrumented method.
                     * @param adviceMethod         The advice method.
                     * @param offsetMappings       A mapping of offsets to resolved target offsets in the instrumented method.
                     * @param suppressionHandler   The suppression handler to use.
                     */
                    protected ForMethodEnter(MethodVisitor methodVisitor,
                                             Context implementationContext,
                                             MethodSizeHandler.ForAdvice methodSizeHandler,
                                             StackMapFrameHandler.ForAdvice stackMapFrameHandler,
                                             MethodDescription instrumentedMethod,
                                             MethodDescription.InDefinedShape adviceMethod,
                                             Map<Integer, Resolved.OffsetMapping.Target> offsetMappings,
                                             SuppressionHandler.Bound suppressionHandler) {
                        super(methodVisitor,
                                implementationContext,
                                methodSizeHandler,
                                stackMapFrameHandler,
                                instrumentedMethod,
                                adviceMethod,
                                offsetMappings,
                                suppressionHandler);
                        doesReturn = false;
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        switch (opcode) {
                            case Opcodes.RETURN:
                                ((StackAwareMethodVisitor) mv).drainStack();
                                break;
                            case Opcodes.IRETURN:
                                methodSizeHandler.requireLocalVariableLength(((StackAwareMethodVisitor) mv).drainStack(Opcodes.ISTORE, Opcodes.ILOAD, StackSize.SINGLE));
                                break;
                            case Opcodes.ARETURN:
                                methodSizeHandler.requireLocalVariableLength(((StackAwareMethodVisitor) mv).drainStack(Opcodes.ASTORE, Opcodes.ALOAD, StackSize.SINGLE));
                                break;
                            case Opcodes.FRETURN:
                                methodSizeHandler.requireLocalVariableLength(((StackAwareMethodVisitor) mv).drainStack(Opcodes.FSTORE, Opcodes.FLOAD, StackSize.SINGLE));
                                break;
                            case Opcodes.LRETURN:
                                methodSizeHandler.requireLocalVariableLength(((StackAwareMethodVisitor) mv).drainStack(Opcodes.LSTORE, Opcodes.LLOAD, StackSize.DOUBLE));
                                break;
                            case Opcodes.DRETURN:
                                methodSizeHandler.requireLocalVariableLength(((StackAwareMethodVisitor) mv).drainStack(Opcodes.DSTORE, Opcodes.DLOAD, StackSize.DOUBLE));
                                break;
                            default:
                                mv.visitInsn(opcode);
                                return;
                        }
                        mv.visitJumpInsn(Opcodes.GOTO, endOfMethod);
                        doesReturn = true;
                    }

                    @Override
                    protected int adjust(int offset) {
                        return offset;
                    }

                    @Override
                    public void onDefaultValue(MethodVisitor methodVisitor) {
                        if (adviceMethod.getReturnType().represents(boolean.class)
                                || adviceMethod.getReturnType().represents(byte.class)
                                || adviceMethod.getReturnType().represents(short.class)
                                || adviceMethod.getReturnType().represents(char.class)
                                || adviceMethod.getReturnType().represents(int.class)) {
                            methodVisitor.visitInsn(Opcodes.ICONST_0);
                        } else if (adviceMethod.getReturnType().represents(long.class)) {
                            methodVisitor.visitInsn(Opcodes.LCONST_0);
                        } else if (adviceMethod.getReturnType().represents(float.class)) {
                            methodVisitor.visitInsn(Opcodes.FCONST_0);
                        } else if (adviceMethod.getReturnType().represents(double.class)) {
                            methodVisitor.visitInsn(Opcodes.DCONST_0);
                        } else if (!adviceMethod.getReturnType().represents(void.class)) {
                            methodVisitor.visitInsn(Opcodes.ACONST_NULL);
                        }
                        doesReturn = true;
                    }

                    @Override
                    protected void onMethodReturn() {
                        Type returnType = Type.getType(adviceMethod.getReturnType().asErasure().getDescriptor());
                        if (doesReturn && !returnType.equals(Type.VOID_TYPE)) {
                            stackMapFrameHandler.injectReturnFrame(methodVisitor);
                            methodVisitor.visitVarInsn(returnType.getOpcode(Opcodes.ISTORE), instrumentedMethod.getStackSize());
                        }
                    }

                    @Override
                    public String toString() {
                        return "Advice.Dispatcher.Inlining.CodeTranslationVisitor.ForMethodEnter{" +
                                "instrumentedMethod=" + instrumentedMethod +
                                ", adviceMethod=" + adviceMethod +
                                ", doesReturn=" + doesReturn +
                                '}';
                    }
                }

                /**
                 * A code translation visitor that discards the return value of the represented advice method.
                 */
                protected static class ForMethodExit extends CodeTranslationVisitor {

                    /**
                     * The padding after the instrumented method's arguments in the local variable array.
                     */
                    private final int padding;

                    /**
                     * Creates a code translation visitor for translating exit advice.
                     *
                     * @param methodVisitor        A method visitor for writing the instrumented method's byte code.
                     * @param methodSizeHandler    A handler for computing the method size requirements.
                     * @param stackMapFrameHandler A handler for translating and injecting stack map frames.
                     * @param instrumentedMethod   The instrumented method.
                     * @param adviceMethod         The advice method.
                     * @param offsetMappings       A mapping of offsets to resolved target offsets in the instrumented method.
                     * @param suppressionHandler   The suppression handler to use.
                     * @param padding              The padding after the instrumented method's arguments in the local variable array.
                     */
                    protected ForMethodExit(MethodVisitor methodVisitor,
                                            Implementation.Context implementationContext,
                                            MethodSizeHandler.ForAdvice methodSizeHandler,
                                            StackMapFrameHandler.ForAdvice stackMapFrameHandler,
                                            MethodDescription instrumentedMethod,
                                            MethodDescription.InDefinedShape adviceMethod,
                                            Map<Integer, Resolved.OffsetMapping.Target> offsetMappings,
                                            SuppressionHandler.Bound suppressionHandler,
                                            int padding) {
                        super(methodVisitor,
                                implementationContext,
                                methodSizeHandler,
                                stackMapFrameHandler,
                                instrumentedMethod,
                                adviceMethod,
                                offsetMappings,
                                suppressionHandler);
                        this.padding = padding;
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        switch (opcode) {
                            case Opcodes.RETURN:
                                break;
                            case Opcodes.IRETURN:
                            case Opcodes.ARETURN:
                            case Opcodes.FRETURN:
                                mv.visitInsn(Opcodes.POP);
                                break;
                            case Opcodes.LRETURN:
                            case Opcodes.DRETURN:
                                mv.visitInsn(Opcodes.POP2);
                                break;
                            default:
                                mv.visitInsn(opcode);
                                return;
                        }
                        ((StackAwareMethodVisitor) mv).drainStack();
                        mv.visitJumpInsn(Opcodes.GOTO, endOfMethod);
                    }

                    @Override
                    protected int adjust(int offset) {
                        return instrumentedMethod.getReturnType().getStackSize().getSize() + padding + offset;
                    }

                    @Override
                    public void onDefaultValue(MethodVisitor methodVisitor) {
                        /* do nothing */
                    }

                    @Override
                    protected void onMethodReturn() {
                        /* do nothing */
                    }

                    @Override
                    public String toString() {
                        return "Advice.Dispatcher.Inlining.CodeTranslationVisitor.ForMethodExit{" +
                                "instrumentedMethod=" + instrumentedMethod +
                                ", adviceMethod=" + adviceMethod +
                                ", padding=" + padding +
                                '}';
                    }
                }
            }
        }

        /**
         * A dispatcher for an advice method that is being invoked from the instrumented method.
         */
        class Delegating implements Unresolved {

            /**
             * The advice method.
             */
            protected final MethodDescription.InDefinedShape adviceMethod;

            /**
             * Creates a new delegating advice dispatcher.
             *
             * @param adviceMethod The advice method.
             */
            protected Delegating(MethodDescription.InDefinedShape adviceMethod) {
                this.adviceMethod = adviceMethod;
            }

            @Override
            public boolean isAlive() {
                return true;
            }

            @Override
            public boolean isBinary() {
                return false;
            }

            @Override
            public Dispatcher.Resolved.ForMethodEnter asMethodEnter(List<? extends OffsetMapping.Factory> userFactories,
                                                                    ClassReader classReader) {
                return new Resolved.ForMethodEnter(adviceMethod, userFactories);
            }

            @Override
            public Dispatcher.Resolved.ForMethodExit asMethodExitTo(List<? extends OffsetMapping.Factory> userFactories,
                                                                    ClassReader classReader,
                                                                    Dispatcher.Resolved.ForMethodEnter dispatcher) {
                return Resolved.ForMethodExit.of(adviceMethod, userFactories, dispatcher.getEnterType());
            }

            @Override
            public boolean equals(Object other) {
                return this == other || !(other == null || getClass() != other.getClass()) && adviceMethod.equals(((Delegating) other).adviceMethod);
            }

            @Override
            public int hashCode() {
                return adviceMethod.hashCode();
            }

            @Override
            public String toString() {
                return "Advice.Dispatcher.Delegating{" +
                        "adviceMethod=" + adviceMethod +
                        '}';
            }

            /**
             * A resolved version of a dispatcher.
             *
             * @param <T> The type of advice dispatcher that is bound.
             */
            protected abstract static class Resolved<T extends Bound> implements Dispatcher.Resolved {

                /**
                 * Indicates a read-only mapping for an offset.
                 */
                private static final boolean READ_ONLY = true;

                /**
                 * The represented advice method.
                 */
                protected final MethodDescription.InDefinedShape adviceMethod;

                /**
                 * An unresolved mapping of offsets of the advice method based on the annotations discovered on each method parameter.
                 */
                protected final List<OffsetMapping> offsetMappings;

                /**
                 * The suppression handler to use.
                 */
                protected final SuppressionHandler suppressionHandler;

                /**
                 * Creates a new resolved version of a dispatcher.
                 *
                 * @param adviceMethod  The represented advice method.
                 * @param factories     A list of factories to resolve for the parameters of the advice method.
                 * @param throwableType The type to handle by a suppression handler or {@link NoExceptionHandler} to not handle any exceptions.
                 */
                protected Resolved(MethodDescription.InDefinedShape adviceMethod, List<OffsetMapping.Factory> factories, TypeDescription throwableType) {
                    this.adviceMethod = adviceMethod;
                    offsetMappings = new ArrayList<OffsetMapping>(adviceMethod.getParameters().size());
                    for (ParameterDescription.InDefinedShape parameterDescription : adviceMethod.getParameters()) {
                        OffsetMapping offsetMapping = OffsetMapping.Factory.UNDEFINED;
                        for (OffsetMapping.Factory factory : factories) {
                            OffsetMapping possible = factory.make(parameterDescription);
                            if (possible != null) {
                                if (offsetMapping == null) {
                                    offsetMapping = possible;
                                } else {
                                    throw new IllegalStateException(parameterDescription + " is bound to both " + possible + " and " + offsetMapping);
                                }
                            }
                        }
                        offsetMappings.add(offsetMapping == null
                                ? new OffsetMapping.ForParameter(parameterDescription.getIndex(), READ_ONLY, parameterDescription.getType().asErasure())
                                : offsetMapping);
                    }
                    suppressionHandler = SuppressionHandler.Suppressing.of(throwableType);
                }

                @Override
                public boolean isAlive() {
                    return true;
                }

                @Override
                public T bind(TypeDescription instrumentedType,
                              MethodDescription instrumentedMethod,
                              MethodVisitor methodVisitor,
                              Implementation.Context implementationContext,
                              MethodSizeHandler.ForInstrumentedMethod methodSizeHandler,
                              StackMapFrameHandler.ForInstrumentedMethod stackMapFrameHandler) {
                    if (!adviceMethod.isVisibleTo(instrumentedType)) {
                        throw new IllegalStateException(adviceMethod + " is not visible to " + instrumentedMethod.getDeclaringType());
                    }
                    return resolve(instrumentedType, instrumentedMethod, methodVisitor, implementationContext, methodSizeHandler, stackMapFrameHandler);
                }

                /**
                 * Binds this dispatcher for resolution to a specific method.
                 *
                 * @param instrumentedType     A description of the instrumented type.
                 * @param instrumentedMethod   The instrumented method that is being bound.
                 * @param methodVisitor        The method visitor for writing to the instrumented method.
                 * @param methodSizeHandler    A handler for computing the method size requirements.
                 * @param stackMapFrameHandler A handler for translating and injecting stack map frames.
                 * @return An appropriate bound advice dispatcher.
                 */
                protected abstract T resolve(TypeDescription instrumentedType,
                                             MethodDescription instrumentedMethod,
                                             MethodVisitor methodVisitor,
                                             Implementation.Context implementationContext,
                                             MethodSizeHandler.ForInstrumentedMethod methodSizeHandler,
                                             StackMapFrameHandler.ForInstrumentedMethod stackMapFrameHandler);

                @Override
                public boolean equals(Object other) {
                    if (this == other) return true;
                    if (other == null || getClass() != other.getClass()) return false;
                    Delegating.Resolved resolved = (Delegating.Resolved) other;
                    return adviceMethod.equals(resolved.adviceMethod) && offsetMappings.equals(resolved.offsetMappings);
                }

                @Override
                public int hashCode() {
                    int result = adviceMethod.hashCode();
                    result = 31 * result + offsetMappings.hashCode();
                    return result;
                }

                /**
                 * A bound advice method that copies the code by first extracting the exception table and later appending the
                 * code of the method without copying any meta data.
                 */
                protected abstract static class AdviceMethodWriter implements Bound, SuppressionHandler.ReturnValueProducer {

                    /**
                     * Indicates an empty local variable array which is not required for calling a method.
                     */
                    private static final int EMPTY = 0;

                    /**
                     * The advice method.
                     */
                    protected final MethodDescription.InDefinedShape adviceMethod;

                    /**
                     * The instrumented method.
                     */
                    protected final MethodDescription instrumentedMethod;

                    /**
                     * The offset mappings available to this advice.
                     */
                    private final List<OffsetMapping.Target> offsetMappings;

                    /**
                     * The method visitor for writing the instrumented method.
                     */
                    protected final MethodVisitor methodVisitor;

                    /**
                     * A handler for computing the method size requirements.
                     */
                    protected final MethodSizeHandler.ForAdvice methodSizeHandler;

                    /**
                     * A handler for translating and injecting stack map frmes.
                     */
                    protected final StackMapFrameHandler.ForAdvice stackMapFrameHandler;

                    /**
                     * A bound suppression handler that is used for suppressing exceptions of this advice method.
                     */
                    private final SuppressionHandler.Bound suppressionHandler;

                    /**
                     * Creates a new advice method writer.
                     *
                     * @param adviceMethod         The advice method.
                     * @param instrumentedMethod   The instrumented method.
                     * @param offsetMappings       The offset mappings available to this advice.
                     * @param methodVisitor        The method visitor for writing the instrumented method.
                     * @param methodSizeHandler    A handler for computing the method size requirements.
                     * @param stackMapFrameHandler A handler for translating and injecting stack map frames.
                     * @param suppressionHandler   A bound suppression handler that is used for suppressing exceptions of this advice method.
                     */
                    protected AdviceMethodWriter(MethodDescription.InDefinedShape adviceMethod,
                                                 MethodDescription instrumentedMethod,
                                                 List<OffsetMapping.Target> offsetMappings,
                                                 MethodVisitor methodVisitor,
                                                 MethodSizeHandler.ForAdvice methodSizeHandler,
                                                 StackMapFrameHandler.ForAdvice stackMapFrameHandler,
                                                 SuppressionHandler.Bound suppressionHandler) {
                        this.adviceMethod = adviceMethod;
                        this.instrumentedMethod = instrumentedMethod;
                        this.offsetMappings = offsetMappings;
                        this.methodVisitor = methodVisitor;
                        this.methodSizeHandler = methodSizeHandler;
                        this.stackMapFrameHandler = stackMapFrameHandler;
                        this.suppressionHandler = suppressionHandler;
                    }

                    @Override
                    public void prepare() {
                        suppressionHandler.onPrepare(methodVisitor);
                    }

                    /**
                     * Writes the advice method invocation.
                     */
                    protected void doApply() {
                        suppressionHandler.onStart(methodVisitor, methodSizeHandler);
                        int index = 0, currentStackSize = 0, maximumStackSize = 0;
                        for (OffsetMapping.Target offsetMapping : offsetMappings) {
                            Type type = Type.getType(adviceMethod.getParameters().get(index++).getType().asErasure().getDescriptor());
                            currentStackSize += type.getSize();
                            maximumStackSize = Math.max(maximumStackSize, currentStackSize + offsetMapping.resolveRead().apply(methodVisitor, null).getMaximalSize());
                        }
                        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC,
                                adviceMethod.getDeclaringType().getInternalName(),
                                adviceMethod.getInternalName(),
                                adviceMethod.getDescriptor(),
                                false);
                        onMethodReturn();
                        suppressionHandler.onEndSkipped(methodVisitor, stackMapFrameHandler, this);
                        stackMapFrameHandler.injectCompletionFrame(methodVisitor, false);
                        methodSizeHandler.recordMaxima(Math.max(maximumStackSize, adviceMethod.getReturnType().getStackSize().getSize()), EMPTY);
                    }

                    /**
                     * Invoked directly after the advice method was called.
                     */
                    protected abstract void onMethodReturn();

                    @Override
                    public String toString() {
                        return "Advice.Dispatcher.Delegating.Resolved.AdviceMethodWriter{" +
                                "instrumentedMethod=" + instrumentedMethod +
                                ", methodVisitor=" + methodVisitor +
                                ", methodSizeHandler=" + methodSizeHandler +
                                ", stackMapFrameHandler=" + stackMapFrameHandler +
                                ", suppressionHandler=" + suppressionHandler +
                                '}';
                    }

                    /**
                     * An advice method writer for a method entry.
                     */
                    protected static class ForMethodEnter extends AdviceMethodWriter implements Bound.ForMethodEnter {

                        /**
                         * The skip dispatcher to use.
                         */
                        private final Resolved.ForMethodEnter.SkipDispatcher skipDispatcher;

                        /**
                         * Creates a new advice method writer.
                         *
                         * @param adviceMethod         The advice method.
                         * @param instrumentedMethod   The instrumented method.
                         * @param offsetMappings       The offset mappings available to this advice.
                         * @param methodVisitor        The method visitor for writing the instrumented method.
                         * @param methodSizeHandler    A handler for computing the method size requirements.
                         * @param stackMapFrameHandler A handler for translating and injecting stack map frames.
                         * @param suppressionHandler   A bound suppression handler that is used for suppressing exceptions of this advice method.
                         * @param skipDispatcher       The skip dispatcher to use.
                         */
                        protected ForMethodEnter(MethodDescription.InDefinedShape adviceMethod,
                                                 MethodDescription instrumentedMethod,
                                                 List<OffsetMapping.Target> offsetMappings,
                                                 MethodVisitor methodVisitor,
                                                 MethodSizeHandler.ForAdvice methodSizeHandler,
                                                 StackMapFrameHandler.ForAdvice stackMapFrameHandler,
                                                 SuppressionHandler.Bound suppressionHandler,
                                                 Resolved.ForMethodEnter.SkipDispatcher skipDispatcher) {
                            super(adviceMethod, instrumentedMethod, offsetMappings, methodVisitor, methodSizeHandler, stackMapFrameHandler, suppressionHandler);
                            this.skipDispatcher = skipDispatcher;
                        }

                        @Override
                        protected void onMethodReturn() {
                            if (adviceMethod.getReturnType().represents(boolean.class)
                                    || adviceMethod.getReturnType().represents(byte.class)
                                    || adviceMethod.getReturnType().represents(short.class)
                                    || adviceMethod.getReturnType().represents(char.class)
                                    || adviceMethod.getReturnType().represents(int.class)) {
                                methodVisitor.visitVarInsn(Opcodes.ISTORE, instrumentedMethod.getStackSize());
                            } else if (adviceMethod.getReturnType().represents(long.class)) {
                                methodVisitor.visitVarInsn(Opcodes.LSTORE, instrumentedMethod.getStackSize());
                            } else if (adviceMethod.getReturnType().represents(float.class)) {
                                methodVisitor.visitVarInsn(Opcodes.FSTORE, instrumentedMethod.getStackSize());
                            } else if (adviceMethod.getReturnType().represents(double.class)) {
                                methodVisitor.visitVarInsn(Opcodes.DSTORE, instrumentedMethod.getStackSize());
                            } else if (!adviceMethod.getReturnType().represents(void.class)) {
                                methodVisitor.visitVarInsn(Opcodes.ASTORE, instrumentedMethod.getStackSize());
                            }
                        }

                        @Override
                        public void apply(SkipHandler skipHandler) {
                            doApply();
                            skipDispatcher.apply(methodVisitor, methodSizeHandler, stackMapFrameHandler, instrumentedMethod, skipHandler);
                        }

                        @Override
                        public void onDefaultValue(MethodVisitor methodVisitor) {
                            if (adviceMethod.getReturnType().represents(boolean.class)
                                    || adviceMethod.getReturnType().represents(byte.class)
                                    || adviceMethod.getReturnType().represents(short.class)
                                    || adviceMethod.getReturnType().represents(char.class)
                                    || adviceMethod.getReturnType().represents(int.class)) {
                                methodVisitor.visitInsn(Opcodes.ICONST_0);
                                methodVisitor.visitVarInsn(Opcodes.ISTORE, instrumentedMethod.getStackSize());
                            } else if (adviceMethod.getReturnType().represents(long.class)) {
                                methodVisitor.visitInsn(Opcodes.LCONST_0);
                                methodVisitor.visitVarInsn(Opcodes.LSTORE, instrumentedMethod.getStackSize());
                            } else if (adviceMethod.getReturnType().represents(float.class)) {
                                methodVisitor.visitInsn(Opcodes.FCONST_0);
                                methodVisitor.visitVarInsn(Opcodes.FSTORE, instrumentedMethod.getStackSize());
                            } else if (adviceMethod.getReturnType().represents(double.class)) {
                                methodVisitor.visitInsn(Opcodes.DCONST_0);
                                methodVisitor.visitVarInsn(Opcodes.DSTORE, instrumentedMethod.getStackSize());
                            } else if (!adviceMethod.getReturnType().represents(void.class)) {
                                methodVisitor.visitInsn(Opcodes.ACONST_NULL);
                                methodVisitor.visitVarInsn(Opcodes.ASTORE, instrumentedMethod.getStackSize());
                            }
                        }

                        @Override
                        public String toString() {
                            return "Advice.Dispatcher.Delegating.Resolved.AdviceMethodWriter.ForMethodEnter{" +
                                    "instrumentedMethod=" + instrumentedMethod +
                                    ", adviceMethod=" + adviceMethod +
                                    "}";
                        }
                    }

                    /**
                     * An advice method writer for a method exit.
                     */
                    protected static class ForMethodExit extends AdviceMethodWriter implements Bound.ForMethodExit {

                        /**
                         * Creates a new advice method writer.
                         *
                         * @param adviceMethod         The advice method.
                         * @param instrumentedMethod   The instrumented method.
                         * @param offsetMappings       The offset mappings available to this advice.
                         * @param methodVisitor        The method visitor for writing the instrumented method.
                         * @param methodSizeHandler    A handler for computing the method size requirements.
                         * @param stackMapFrameHandler A handler for translating and injecting stack map frames.
                         * @param suppressionHandler   A bound suppression handler that is used for suppressing exceptions of this advice method.
                         */
                        protected ForMethodExit(MethodDescription.InDefinedShape adviceMethod,
                                                MethodDescription instrumentedMethod,
                                                List<OffsetMapping.Target> offsetMappings,
                                                MethodVisitor methodVisitor,
                                                MethodSizeHandler.ForAdvice methodSizeHandler,
                                                StackMapFrameHandler.ForAdvice stackMapFrameHandler,
                                                SuppressionHandler.Bound suppressionHandler) {
                            super(adviceMethod, instrumentedMethod, offsetMappings, methodVisitor, methodSizeHandler, stackMapFrameHandler, suppressionHandler);
                        }

                        @Override
                        public void apply() {
                            doApply();
                        }

                        @Override
                        protected void onMethodReturn() {
                            switch (adviceMethod.getReturnType().getStackSize()) {
                                case ZERO:
                                    return;
                                case SINGLE:
                                    methodVisitor.visitInsn(Opcodes.POP);
                                    return;
                                case DOUBLE:
                                    methodVisitor.visitInsn(Opcodes.POP2);
                                    return;
                                default:
                                    throw new IllegalStateException("Unexpected size: " + adviceMethod.getReturnType().getStackSize());
                            }
                        }

                        @Override
                        public void onDefaultValue(MethodVisitor methodVisitor) {
                            /* do nothing */
                        }

                        @Override
                        public String toString() {
                            return "Advice.Dispatcher.Delegating.Resolved.AdviceMethodWriter.ForMethodExit{" +
                                    "instrumentedMethod=" + instrumentedMethod +
                                    ", adviceMethod=" + adviceMethod +
                                    "}";
                        }
                    }
                }

                /**
                 * A resolved dispatcher for implementing method enter advice.
                 */
                protected static class ForMethodEnter extends Delegating.Resolved<Bound.ForMethodEnter> implements Dispatcher.Resolved.ForMethodEnter {

                    /**
                     * The skip dispatcher to use.
                     */
                    private final SkipDispatcher skipDispatcher;

                    /**
                     * {@code true} if the first discovered line number information should be prepended to the advice code.
                     */
                    private final boolean prependLineNumber;

                    /**
                     * Creates a new resolved dispatcher for implementing method enter advice.
                     *
                     * @param adviceMethod  The represented advice method.
                     * @param userFactories A list of user-defined factories for offset mappings.
                     */
                    @SuppressWarnings("unchecked") // In absence of @SafeVarargs for Java 6
                    protected ForMethodEnter(MethodDescription.InDefinedShape adviceMethod, List<? extends OffsetMapping.Factory> userFactories) {
                        super(adviceMethod,
                                CompoundList.of(Arrays.asList(OffsetMapping.ForParameter.Factory.READ_ONLY,
                                        OffsetMapping.ForBoxedArguments.Factory.READ_ONLY,
                                        OffsetMapping.ForThisReference.Factory.READ_ONLY,
                                        OffsetMapping.ForField.Factory.READ_ONLY,
                                        OffsetMapping.ForOrigin.Factory.INSTANCE,
                                        OffsetMapping.ForUnusedValue.Factory.INSTANCE,
                                        OffsetMapping.ForStubValue.INSTANCE,
                                        new OffsetMapping.Illegal(Thrown.class, Enter.class, Return.class, BoxedReturn.class)), userFactories),
                                adviceMethod.getDeclaredAnnotations().ofType(OnMethodEnter.class).getValue(SUPPRESS_ENTER).resolve(TypeDescription.class));
                        skipDispatcher = SkipDispatcher.ForType.of(adviceMethod);
                        prependLineNumber = adviceMethod.getDeclaredAnnotations().ofType(OnMethodEnter.class).getValue(PREPEND_LINE_NUMBER).resolve(Boolean.class);
                    }

                    @Override
                    public TypeDescription getEnterType() {
                        return adviceMethod.getReturnType().asErasure();
                    }

                    @Override
                    public boolean isPrependLineNumber() {
                        return prependLineNumber;
                    }

                    @Override
                    protected Bound.ForMethodEnter resolve(TypeDescription instrumentedType,
                                                           MethodDescription instrumentedMethod,
                                                           MethodVisitor methodVisitor,
                                                           Implementation.Context implementationContext,
                                                           MethodSizeHandler.ForInstrumentedMethod methodSizeHandler,
                                                           StackMapFrameHandler.ForInstrumentedMethod stackMapFrameHandler) {
                        List<OffsetMapping.Target> offsetMappings = new ArrayList<OffsetMapping.Target>(this.offsetMappings.size());
                        for (OffsetMapping offsetMapping : this.offsetMappings) {
                            offsetMappings.add(offsetMapping.resolve(instrumentedType,
                                    instrumentedMethod,
                                    OffsetMapping.Context.ForMethodEntry.of(instrumentedMethod)));
                        }
                        return new AdviceMethodWriter.ForMethodEnter(adviceMethod,
                                instrumentedMethod,
                                offsetMappings,
                                methodVisitor,
                                methodSizeHandler.bindEntry(adviceMethod),
                                stackMapFrameHandler.bindEntry(adviceMethod),
                                suppressionHandler.bind(),
                                skipDispatcher);
                    }

                    @Override
                    public boolean equals(Object other) {
                        if (this == other) return true;
                        if (other == null || getClass() != other.getClass()) return false;
                        if (!super.equals(other)) return false;
                        Delegating.Resolved.ForMethodEnter that = (Delegating.Resolved.ForMethodEnter) other;
                        return skipDispatcher == that.skipDispatcher && prependLineNumber == that.prependLineNumber;
                    }

                    @Override
                    public int hashCode() {
                        int result = super.hashCode();
                        result = 31 * result + skipDispatcher.hashCode();
                        result = 31 * result + (prependLineNumber ? 1 : 0);
                        return result;
                    }

                    @Override
                    public String toString() {
                        return "Advice.Dispatcher.Delegating.Resolved.ForMethodEnter{" +
                                "adviceMethod=" + adviceMethod +
                                ", offsetMappings=" + offsetMappings +
                                ", skipDispatcher=" + skipDispatcher +
                                ", prependLineNumber=" + prependLineNumber +
                                '}';
                    }
                }

                /**
                 * A resolved dispatcher for implementing method exit advice.
                 */
                protected abstract static class ForMethodExit extends Delegating.Resolved<Bound.ForMethodExit> implements Dispatcher.Resolved.ForMethodExit {

                    /**
                     * The additional stack size to consider when accessing the local variable array.
                     */
                    private final TypeDescription enterType;

                    /**
                     * Creates a new resolved dispatcher for implementing method exit advice.
                     *
                     * @param adviceMethod  The represented advice method.
                     * @param userFactories A list of user-defined factories for offset mappings.
                     * @param enterType     The type of the value supplied by the enter advice method or
                     *                      a description of {@code void} if no such value exists.
                     */
                    protected ForMethodExit(MethodDescription.InDefinedShape adviceMethod,
                                            List<? extends OffsetMapping.Factory> userFactories,
                                            TypeDescription enterType) {
                        super(adviceMethod,
                                CompoundList.of(Arrays.asList(
                                        OffsetMapping.ForParameter.Factory.READ_ONLY,
                                        OffsetMapping.ForBoxedArguments.Factory.READ_ONLY,
                                        OffsetMapping.ForThisReference.Factory.READ_ONLY,
                                        OffsetMapping.ForField.Factory.READ_ONLY,
                                        OffsetMapping.ForOrigin.Factory.INSTANCE,
                                        OffsetMapping.ForUnusedValue.Factory.INSTANCE,
                                        OffsetMapping.ForStubValue.INSTANCE,
                                        new OffsetMapping.ForEnterValue.Factory(enterType, true),
                                        OffsetMapping.ForReturnValue.Factory.READ_ONLY,
                                        OffsetMapping.ForBoxedReturnValue.Factory.READ_ONLY,
                                        OffsetMapping.ForThrowable.Factory.of(adviceMethod, true)
                                ), userFactories),
                                adviceMethod.getDeclaredAnnotations().ofType(OnMethodExit.class).getValue(SUPPRESS_EXIT).resolve(TypeDescription.class));
                        this.enterType = enterType;
                    }

                    /**
                     * Resolves exit advice that handles exceptions depending on the specification of the exit advice.
                     *
                     * @param adviceMethod  The advice method.
                     * @param userFactories A list of user-defined factories for offset mappings.
                     * @param enterType     The type of the value supplied by the enter advice method or
                     *                      a description of {@code void} if no such value exists.
                     * @return An appropriate exit handler.
                     */
                    protected static Resolved.ForMethodExit of(MethodDescription.InDefinedShape adviceMethod,
                                                               List<? extends OffsetMapping.Factory> userFactories,
                                                               TypeDescription enterType) {
                        TypeDescription triggeringThrowable = adviceMethod.getDeclaredAnnotations()
                                .ofType(OnMethodExit.class)
                                .getValue(ON_THROWABLE).resolve(TypeDescription.class);
                        return triggeringThrowable.represents(NoExceptionHandler.class)
                                ? new WithoutExceptionHandler(adviceMethod, userFactories, enterType)
                                : new WithExceptionHandler(adviceMethod, userFactories, enterType, triggeringThrowable);
                    }

                    @Override
                    protected Bound.ForMethodExit resolve(TypeDescription instrumentedType,
                                                          MethodDescription instrumentedMethod,
                                                          MethodVisitor methodVisitor,
                                                          Implementation.Context implementationContext,
                                                          MethodSizeHandler.ForInstrumentedMethod methodSizeHandler,
                                                          StackMapFrameHandler.ForInstrumentedMethod stackMapFrameHandler) {
                        List<OffsetMapping.Target> offsetMappings = new ArrayList<OffsetMapping.Target>(this.offsetMappings.size());
                        for (OffsetMapping offsetMapping : this.offsetMappings) {
                            offsetMappings.add(offsetMapping.resolve(instrumentedType,
                                    instrumentedMethod,
                                    OffsetMapping.Context.ForMethodExit.of(enterType)));
                        }
                        return new AdviceMethodWriter.ForMethodExit(adviceMethod,
                                instrumentedMethod,
                                offsetMappings,
                                methodVisitor,
                                methodSizeHandler.bindExit(adviceMethod, getTriggeringThrowable().represents(NoExceptionHandler.class)),
                                stackMapFrameHandler.bindExit(adviceMethod),
                                suppressionHandler.bind());
                    }

                    @Override
                    public boolean equals(Object other) {
                        return this == other || !(other == null || getClass() != other.getClass())
                                && super.equals(other)
                                && enterType == ((Delegating.Resolved.ForMethodExit) other).enterType;
                    }

                    @Override
                    public int hashCode() {
                        int result = super.hashCode();
                        result = 31 * result + enterType.hashCode();
                        return result;
                    }

                    /**
                     * Implementation of exit advice that handles exceptions.
                     */
                    protected static class WithExceptionHandler extends Delegating.Resolved.ForMethodExit {

                        /**
                         * The type of the handled throwable type for which this advice is invoked.
                         */
                        private final TypeDescription triggeringThrowable;

                        /**
                         * Creates a new resolved dispatcher for implementing method exit advice that handles exceptions.
                         *
                         * @param adviceMethod        The represented advice method.
                         * @param userFactories       A list of user-defined factories for offset mappings.
                         * @param enterType           The type of the value supplied by the enter advice method or
                         *                            a description of {@code void} if no such value exists.
                         * @param triggeringThrowable The type of the handled throwable type for which this advice is invoked.
                         */
                        protected WithExceptionHandler(MethodDescription.InDefinedShape adviceMethod,
                                                       List<? extends OffsetMapping.Factory> userFactories,
                                                       TypeDescription enterType,
                                                       TypeDescription triggeringThrowable) {
                            super(adviceMethod, userFactories, enterType);
                            this.triggeringThrowable = triggeringThrowable;
                        }

                        @Override
                        public TypeDescription getTriggeringThrowable() {
                            return triggeringThrowable;
                        }

                        @Override
                        public String toString() {
                            return "Advice.Dispatcher.Delegating.Resolved.ForMethodExit.WithExceptionHandler{" +
                                    "adviceMethod=" + adviceMethod +
                                    ", offsetMappings=" + offsetMappings +
                                    ", triggeringThrowable=" + triggeringThrowable +
                                    '}';
                        }
                    }

                    /**
                     * Implementation of exit advice that ignores exceptions.
                     */
                    protected static class WithoutExceptionHandler extends Delegating.Resolved.ForMethodExit {

                        /**
                         * Creates a new resolved dispatcher for implementing method exit advice that does not handle exceptions.
                         *
                         * @param adviceMethod  The represented advice method.
                         * @param userFactories A list of user-defined factories for offset mappings.
                         * @param enterType     The type of the value supplied by the enter advice method or
                         *                      a description of {@code void} if no such value exists.
                         */
                        protected WithoutExceptionHandler(MethodDescription.InDefinedShape adviceMethod,
                                                          List<? extends OffsetMapping.Factory> userFactories,
                                                          TypeDescription enterType) {
                            super(adviceMethod, userFactories, enterType);
                        }

                        @Override
                        public TypeDescription getTriggeringThrowable() {
                            return NoExceptionHandler.DESCRIPTION;
                        }

                        @Override
                        public String toString() {
                            return "Advice.Dispatcher.Delegating.Resolved.ForMethodExit.WithoutExceptionHandler{" +
                                    "adviceMethod=" + adviceMethod +
                                    ", offsetMappings=" + offsetMappings +
                                    '}';
                        }
                    }
                }
            }
        }
    }

    /**
     * A method visitor that weaves the advice methods' byte codes.
     */
    protected abstract static class AdviceVisitor extends ExceptionTableSensitiveMethodVisitor implements Dispatcher.Bound.SkipHandler {

        /**
         * Indicates a zero offset.
         */
        private static final int NO_OFFSET = 0;

        /**
         * The actual method visitor that is underlying this method visitor to which all instructions are written.
         */
        protected final MethodVisitor methodVisitor;

        /**
         * A description of the instrumented method.
         */
        protected final MethodDescription instrumentedMethod;

        /**
         * The required padding before using local variables after the instrumented method's arguments.
         */
        private final int padding;

        /**
         * The dispatcher to be used for method entry.
         */
        private final Dispatcher.Bound.ForMethodEnter methodEnter;

        /**
         * The dispatcher to be used for method exit.
         */
        protected final Dispatcher.Bound.ForMethodExit methodExit;

        /**
         * A handler for computing the method size requirements.
         */
        protected final MethodSizeHandler.ForInstrumentedMethod methodSizeHandler;

        /**
         * A handler for translating and injecting stack map frames.
         */
        protected final StackMapFrameHandler.ForInstrumentedMethod stackMapFrameHandler;

        /**
         * Creates a new advice visitor.
         *
         * @param methodVisitor      The actual method visitor that is underlying this method visitor to which all instructions are written.
         * @param delegate           A delegate to which all instructions of the original method are written to. Must delegate to {@code methodVisitor}.
         * @param instrumentedType   A description of the instrumented type.
         * @param instrumentedMethod The instrumented method.
         * @param methodEnter        The method enter advice.
         * @param methodExit         The method exit advice.
         * @param yieldedTypes       The types that are expected to be added after the instrumented method returns.
         * @param writerFlags        The ASM writer flags that were set.
         * @param readerFlags        The ASM reader flags that were set.
         */
        protected AdviceVisitor(MethodVisitor methodVisitor,
                                MethodVisitor delegate,
                                Implementation.Context implementationContext,
                                TypeDescription instrumentedType,
                                MethodDescription instrumentedMethod,
                                Dispatcher.Resolved.ForMethodEnter methodEnter,
                                Dispatcher.Resolved.ForMethodExit methodExit,
                                List<? extends TypeDescription> yieldedTypes,
                                int writerFlags,
                                int readerFlags) {
            super(Opcodes.ASM5, delegate);
            this.methodVisitor = methodVisitor;
            this.instrumentedMethod = instrumentedMethod;
            padding = methodEnter.getEnterType().getStackSize().getSize();
            List<TypeDescription> requiredTypes = methodEnter.getEnterType().represents(void.class)
                    ? Collections.<TypeDescription>emptyList()
                    : Collections.singletonList(methodEnter.getEnterType());
            methodSizeHandler = MethodSizeHandler.Default.of(instrumentedMethod, requiredTypes, yieldedTypes, writerFlags);
            stackMapFrameHandler = StackMapFrameHandler.Default.of(instrumentedType,
                    instrumentedMethod,
                    requiredTypes,
                    yieldedTypes,
                    implementationContext.getClassFileVersion(),
                    writerFlags,
                    readerFlags);
            this.methodEnter = methodEnter.bind(instrumentedType, instrumentedMethod, methodVisitor, implementationContext, methodSizeHandler, stackMapFrameHandler);
            this.methodExit = methodExit.bind(instrumentedType, instrumentedMethod, methodVisitor, implementationContext, methodSizeHandler, stackMapFrameHandler);
        }

        @Override
        protected void onAfterExceptionTable() {
            methodEnter.prepare();
            onUserPrepare();
            methodExit.prepare();
            methodEnter.apply(this);
            onUserStart();
        }

        /**
         * Invoked when the user method's exception handler (if any) is supposed to be prepared.
         */
        protected abstract void onUserPrepare();

        /**
         * Writes the advice for entering the instrumented method.
         */
        protected abstract void onUserStart();

        @Override
        protected void onVisitVarInsn(int opcode, int offset) {
            mv.visitVarInsn(opcode, resolve(offset));
        }

        @Override
        protected void onVisitIincInsn(int offset, int increment) {
            mv.visitIincInsn(resolve(offset), increment);
        }

        /**
         * Access the first variable after the instrumented variables and return type are stored.
         *
         * @param opcode The opcode for accessing the variable.
         */
        protected void variable(int opcode) {
            variable(opcode, NO_OFFSET);
        }

        /**
         * Access the first variable after the instrumented variables and return type are stored.
         *
         * @param opcode The opcode for accessing the variable.
         * @param offset The additional offset of the variable.
         */
        protected void variable(int opcode, int offset) {
            methodVisitor.visitVarInsn(opcode, instrumentedMethod.getStackSize() + padding + offset);
        }

        @Override
        public void visitFrame(int frameType, int localVariableLength, Object[] localVariable, int stackSize, Object[] stack) {
            stackMapFrameHandler.translateFrame(methodVisitor, frameType, localVariableLength, localVariable, stackSize, stack);
        }

        @Override
        public void visitMaxs(int stackSize, int localVariableLength) {
            onUserEnd();
            methodVisitor.visitMaxs(methodSizeHandler.compoundStackSize(stackSize), methodSizeHandler.compoundLocalVariableLength(localVariableLength));
        }

        @Override
        public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
            mv.visitLocalVariable(name, descriptor, signature, start, end, resolve(index));
        }

        @Override
        public AnnotationVisitor visitLocalVariableAnnotation(int typeReference,
                                                              TypePath typePath,
                                                              Label[] start,
                                                              Label[] end,
                                                              int[] index,
                                                              String descriptor,
                                                              boolean visible) {
            return mv.visitLocalVariableAnnotation(typeReference, typePath, start, end, resolve(index), descriptor, visible);
        }

        /**
         * Resolves the index of a local variable in the context of the instrumentation.
         *
         * @param index The indices to adjust.
         * @return An array with adjusted indices.
         */
        private int[] resolve(int[] index) {
            int[] resolved = new int[index.length];
            for (int anIndex = 0; anIndex < index.length; anIndex++) {
                resolved[anIndex] = resolve(index[anIndex]);
            }
            return resolved;
        }

        /**
         * Resolves the index of a local variable in the context of the instrumentation.
         *
         * @param index The index to adjust.
         * @return The adjusted index.
         */
        private int resolve(int index) {
            return index < instrumentedMethod.getStackSize()
                    ? index
                    : padding + index;
        }

        /**
         * Writes the advice for completing the instrumented method.
         */
        protected abstract void onUserEnd();

        /**
         * An advice visitor that does not apply exit advice.
         */
        protected static class WithoutExitAdvice extends AdviceVisitor {

            /**
             * Creates an advice visitor that does not apply exit advice.
             *
             * @param methodVisitor      The method visitor for the instrumented method.
             * @param instrumentedType   A description of the instrumented type.
             * @param instrumentedMethod A description of the instrumented method.
             * @param methodEnter        The dispatcher to be used for method entry.
             * @param writerFlags        The ASM writer flags that were set.
             * @param readerFlags        The ASM reader flags that were set.
             */
            protected WithoutExitAdvice(MethodVisitor methodVisitor,
                                        Implementation.Context implementationContext,
                                        TypeDescription instrumentedType,
                                        MethodDescription instrumentedMethod,
                                        Dispatcher.Resolved.ForMethodEnter methodEnter,
                                        int writerFlags,
                                        int readerFlags) {
                super(methodVisitor,
                        methodVisitor,
                        implementationContext,
                        instrumentedType,
                        instrumentedMethod,
                        methodEnter,
                        Dispatcher.Inactive.INSTANCE,
                        Collections.<TypeDescription>emptyList(),
                        writerFlags,
                        readerFlags);
            }

            @Override
            protected void onUserPrepare() {
                /* do nothing */
            }

            @Override
            protected void onUserStart() {
                /* do nothing */
            }

            @Override
            protected void onUserEnd() {
                /* do nothing */
            }

            @Override
            public void apply(MethodVisitor methodVisitor) {
                if (instrumentedMethod.getReturnType().represents(boolean.class)
                        || instrumentedMethod.getReturnType().represents(byte.class)
                        || instrumentedMethod.getReturnType().represents(short.class)
                        || instrumentedMethod.getReturnType().represents(char.class)
                        || instrumentedMethod.getReturnType().represents(int.class)) {
                    methodVisitor.visitInsn(Opcodes.ICONST_0);
                    methodVisitor.visitInsn(Opcodes.IRETURN);
                } else if (instrumentedMethod.getReturnType().represents(long.class)) {
                    methodVisitor.visitInsn(Opcodes.LCONST_0);
                    methodVisitor.visitInsn(Opcodes.LRETURN);
                } else if (instrumentedMethod.getReturnType().represents(float.class)) {
                    methodVisitor.visitInsn(Opcodes.FCONST_0);
                    methodVisitor.visitInsn(Opcodes.FRETURN);
                } else if (instrumentedMethod.getReturnType().represents(double.class)) {
                    methodVisitor.visitInsn(Opcodes.DCONST_0);
                    methodVisitor.visitInsn(Opcodes.DRETURN);
                } else if (instrumentedMethod.getReturnType().represents(void.class)) {
                    methodVisitor.visitInsn(Opcodes.RETURN);
                } else {
                    methodVisitor.visitInsn(Opcodes.ACONST_NULL);
                    methodVisitor.visitInsn(Opcodes.ARETURN);
                }
            }

            @Override
            public String toString() {
                return "Advice.AdviceVisitor.WithoutExitAdvice{" +
                        ", instrumentedMethod=" + instrumentedMethod +
                        "}";
            }
        }

        /**
         * An advice visitor that applies exit advice.
         */
        protected abstract static class WithExitAdvice extends AdviceVisitor {

            /**
             * Indicates the handler for the value returned by the advice method.
             */
            protected final Label returnHandler;

            /**
             * {@code true} if the advice method ever returns non-exceptionally.
             */
            protected boolean doesReturn;

            /**
             * Creates an advice visitor that applies exit advice.
             *
             * @param methodVisitor      The method visitor for the instrumented method.
             * @param instrumentedType   A description of the instrumented type.
             * @param instrumentedMethod A description of the instrumented method.
             * @param methodEnter        The dispatcher to be used for method entry.
             * @param methodExit         The dispatcher to be used for method exit.
             * @param yieldedTypes       The types that are expected to be added after the instrumented method returns.
             * @param writerFlags        The ASM writer flags that were set.
             * @param readerFlags        The ASM reader flags that were set.
             */
            protected WithExitAdvice(MethodVisitor methodVisitor,
                                     Implementation.Context implementationContext,
                                     TypeDescription instrumentedType,
                                     MethodDescription instrumentedMethod,
                                     Dispatcher.Resolved.ForMethodEnter methodEnter,
                                     Dispatcher.Resolved.ForMethodExit methodExit,
                                     List<? extends TypeDescription> yieldedTypes,
                                     int writerFlags,
                                     int readerFlags) {
                super(methodVisitor,
                        new StackAwareMethodVisitor(methodVisitor, instrumentedMethod),
                        implementationContext,
                        instrumentedType,
                        instrumentedMethod,
                        methodEnter,
                        methodExit,
                        yieldedTypes,
                        writerFlags, readerFlags);
                returnHandler = new Label();
                doesReturn = false;
            }

            @Override
            public void apply(MethodVisitor methodVisitor) {
                if (instrumentedMethod.getReturnType().represents(boolean.class)
                        || instrumentedMethod.getReturnType().represents(byte.class)
                        || instrumentedMethod.getReturnType().represents(short.class)
                        || instrumentedMethod.getReturnType().represents(char.class)
                        || instrumentedMethod.getReturnType().represents(int.class)) {
                    methodVisitor.visitInsn(Opcodes.ICONST_0);
                } else if (instrumentedMethod.getReturnType().represents(long.class)) {
                    methodVisitor.visitInsn(Opcodes.LCONST_0);
                } else if (instrumentedMethod.getReturnType().represents(float.class)) {
                    methodVisitor.visitInsn(Opcodes.FCONST_0);
                } else if (instrumentedMethod.getReturnType().represents(double.class)) {
                    methodVisitor.visitInsn(Opcodes.DCONST_0);
                } else if (!instrumentedMethod.getReturnType().represents(void.class)) {
                    methodVisitor.visitInsn(Opcodes.ACONST_NULL);
                }
                methodVisitor.visitJumpInsn(Opcodes.GOTO, returnHandler);
                doesReturn = true;
            }

            @Override
            protected void onVisitInsn(int opcode) {
                switch (opcode) {
                    case Opcodes.RETURN:
                        ((StackAwareMethodVisitor) mv).drainStack();
                        break;
                    case Opcodes.IRETURN:
                        methodSizeHandler.requireLocalVariableLength(((StackAwareMethodVisitor) mv).drainStack(Opcodes.ISTORE, Opcodes.ILOAD, StackSize.SINGLE));
                        break;
                    case Opcodes.FRETURN:
                        methodSizeHandler.requireLocalVariableLength(((StackAwareMethodVisitor) mv).drainStack(Opcodes.FSTORE, Opcodes.FLOAD, StackSize.SINGLE));
                        break;
                    case Opcodes.DRETURN:
                        methodSizeHandler.requireLocalVariableLength(((StackAwareMethodVisitor) mv).drainStack(Opcodes.DSTORE, Opcodes.DLOAD, StackSize.DOUBLE));
                        break;
                    case Opcodes.LRETURN:
                        methodSizeHandler.requireLocalVariableLength((((StackAwareMethodVisitor) mv).drainStack(Opcodes.LSTORE, Opcodes.LLOAD, StackSize.DOUBLE)));
                        break;
                    case Opcodes.ARETURN:
                        methodSizeHandler.requireLocalVariableLength((((StackAwareMethodVisitor) mv).drainStack(Opcodes.ASTORE, Opcodes.ALOAD, StackSize.SINGLE)));
                        break;
                    default:
                        mv.visitInsn(opcode);
                        return;
                }
                mv.visitJumpInsn(Opcodes.GOTO, returnHandler);
                doesReturn = true;
            }

            @Override
            protected void onUserEnd() {
                Type returnType = Type.getType(instrumentedMethod.getReturnType().asErasure().getDescriptor());
                methodVisitor.visitLabel(returnHandler);
                if (doesReturn) {
                    stackMapFrameHandler.injectReturnFrame(methodVisitor);
                    if (!returnType.equals(Type.VOID_TYPE)) {
                        variable(returnType.getOpcode(Opcodes.ISTORE));
                    }
                }
                onUserReturn();
                methodExit.apply();
                onExitAdviceReturn();
                if (returnType.equals(Type.VOID_TYPE)) {
                    methodVisitor.visitInsn(Opcodes.RETURN);
                } else {
                    variable(returnType.getOpcode(Opcodes.ILOAD));
                    methodVisitor.visitInsn(returnType.getOpcode(Opcodes.IRETURN));
                }
            }

            /**
             * Invoked after the user method has returned.
             */
            protected abstract void onUserReturn();

            /**
             * Invoked after the exit advice method has returned.
             */
            protected abstract void onExitAdviceReturn();

            /**
             * An advice visitor that does not capture exceptions.
             */
            protected static class WithoutExceptionHandling extends WithExitAdvice {

                /**
                 * Creates a new advice visitor that does not capture exceptions.
                 *
                 * @param methodVisitor      The method visitor for the instrumented method.
                 * @param instrumentedType   A description of the instrumented type.
                 * @param instrumentedMethod A description of the instrumented method.
                 * @param methodEnter        The dispatcher to be used for method entry.
                 * @param methodExit         The dispatcher to be used for method exit.
                 * @param writerFlags        The ASM writer flags that were set.
                 * @param readerFlags        The ASM reader flags that were set.
                 */
                protected WithoutExceptionHandling(MethodVisitor methodVisitor,
                                                   Implementation.Context implementationContext,
                                                   TypeDescription instrumentedType,
                                                   MethodDescription instrumentedMethod,
                                                   Dispatcher.Resolved.ForMethodEnter methodEnter,
                                                   Dispatcher.Resolved.ForMethodExit methodExit,
                                                   int writerFlags,
                                                   int readerFlags) {
                    super(methodVisitor,
                            implementationContext,
                            instrumentedType,
                            instrumentedMethod,
                            methodEnter,
                            methodExit,
                            instrumentedMethod.getReturnType().represents(void.class)
                                    ? Collections.<TypeDescription>emptyList()
                                    : Collections.singletonList(instrumentedMethod.getReturnType().asErasure()),
                            writerFlags,
                            readerFlags);
                }

                @Override
                protected void onUserPrepare() {
                    /* empty */
                }

                @Override
                protected void onUserStart() {
                    /* empty */
                }

                @Override
                protected void onUserReturn() {
                    if (!doesReturn || !instrumentedMethod.getReturnType().represents(void.class)) {
                        stackMapFrameHandler.injectCompletionFrame(methodVisitor, false);
                    }
                }

                @Override
                protected void onExitAdviceReturn() {
                    /* empty */
                }

                @Override
                public String toString() {
                    return "Advice.AdviceVisitor.WithExitAdvice.WithoutExceptionHandling{" +
                            "instrumentedMethod=" + instrumentedMethod +
                            ", doesReturn=" + doesReturn +
                            "}";
                }
            }

            /**
             * An advice visitor that captures exceptions by weaving try-catch blocks around user code.
             */
            protected static class WithExceptionHandling extends WithExitAdvice {

                /**
                 * The type of the handled throwable type for which this advice is invoked.
                 */
                private final TypeDescription triggeringThrowable;

                /**
                 * Indicates the start of the user method.
                 */
                private final Label userStart;

                /**
                 * Indicates the exception handler.
                 */
                private final Label exceptionHandler;

                /**
                 * Creates a new advice visitor that captures exception by weaving try-catch blocks around user code.
                 *
                 * @param methodVisitor       The method visitor for the instrumented method.
                 * @param instrumentedType    A description of the instrumented type.
                 * @param instrumentedMethod  A description of the instrumented method.
                 * @param methodEnter         The dispatcher to be used for method entry.
                 * @param methodExit          The dispatcher to be used for method exit.
                 * @param writerFlags         The ASM writer flags that were set.
                 * @param readerFlags         The ASM reader flags that were set.
                 * @param triggeringThrowable The type of the handled throwable type for which this advice is invoked.
                 */
                protected WithExceptionHandling(MethodVisitor methodVisitor,
                                                Implementation.Context implementationContext,
                                                TypeDescription instrumentedType,
                                                MethodDescription instrumentedMethod,
                                                Dispatcher.Resolved.ForMethodEnter methodEnter,
                                                Dispatcher.Resolved.ForMethodExit methodExit,
                                                int writerFlags,
                                                int readerFlags,
                                                TypeDescription triggeringThrowable) {
                    super(methodVisitor,
                            implementationContext,
                            instrumentedType,
                            instrumentedMethod,
                            methodEnter,
                            methodExit,
                            instrumentedMethod.getReturnType().represents(void.class)
                                    ? Collections.singletonList(TypeDescription.THROWABLE)
                                    : Arrays.asList(instrumentedMethod.getReturnType().asErasure(), TypeDescription.THROWABLE),
                            writerFlags,
                            readerFlags);
                    this.triggeringThrowable = triggeringThrowable;
                    userStart = new Label();
                    exceptionHandler = new Label();
                }

                @Override
                protected void onUserPrepare() {
                    methodVisitor.visitTryCatchBlock(userStart, returnHandler, exceptionHandler, triggeringThrowable.getInternalName());
                }

                @Override
                protected void onUserStart() {
                    methodVisitor.visitLabel(userStart);
                }

                @Override
                protected void onUserReturn() {
                    Label endOfHandler = new Label();
                    if (doesReturn) {
                        methodVisitor.visitInsn(Opcodes.ACONST_NULL);
                        variable(Opcodes.ASTORE, instrumentedMethod.getReturnType().getStackSize().getSize());
                        methodVisitor.visitJumpInsn(Opcodes.GOTO, endOfHandler);
                    }
                    methodVisitor.visitLabel(exceptionHandler);
                    stackMapFrameHandler.injectExceptionFrame(methodVisitor);
                    variable(Opcodes.ASTORE, instrumentedMethod.getReturnType().getStackSize().getSize());
                    storeDefaultReturn();
                    if (doesReturn) {
                        methodVisitor.visitLabel(endOfHandler);
                    }
                    stackMapFrameHandler.injectCompletionFrame(methodVisitor, false);
                }

                @Override
                protected void onExitAdviceReturn() {
                    variable(Opcodes.ALOAD, instrumentedMethod.getReturnType().getStackSize().getSize());
                    Label endOfHandler = new Label();
                    methodVisitor.visitJumpInsn(Opcodes.IFNULL, endOfHandler);
                    variable(Opcodes.ALOAD, instrumentedMethod.getReturnType().getStackSize().getSize());
                    methodVisitor.visitInsn(Opcodes.ATHROW);
                    methodVisitor.visitLabel(endOfHandler);
                    stackMapFrameHandler.injectCompletionFrame(methodVisitor, true);
                }

                /**
                 * Stores a default return value in the designated slot of the local variable array.
                 */
                private void storeDefaultReturn() {
                    if (instrumentedMethod.getReturnType().represents(boolean.class)
                            || instrumentedMethod.getReturnType().represents(byte.class)
                            || instrumentedMethod.getReturnType().represents(short.class)
                            || instrumentedMethod.getReturnType().represents(char.class)
                            || instrumentedMethod.getReturnType().represents(int.class)) {
                        methodVisitor.visitInsn(Opcodes.ICONST_0);
                        variable(Opcodes.ISTORE);
                    } else if (instrumentedMethod.getReturnType().represents(long.class)) {
                        methodVisitor.visitInsn(Opcodes.LCONST_0);
                        variable(Opcodes.LSTORE);
                    } else if (instrumentedMethod.getReturnType().represents(float.class)) {
                        methodVisitor.visitInsn(Opcodes.FCONST_0);
                        variable(Opcodes.FSTORE);
                    } else if (instrumentedMethod.getReturnType().represents(double.class)) {
                        methodVisitor.visitInsn(Opcodes.DCONST_0);
                        variable(Opcodes.DSTORE);
                    } else if (!instrumentedMethod.getReturnType().represents(void.class)) {
                        methodVisitor.visitInsn(Opcodes.ACONST_NULL);
                        variable(Opcodes.ASTORE);
                    }
                }

                @Override
                public String toString() {
                    return "Advice.AdviceVisitor.WithExitAdvice.WithExceptionHandling{" +
                            "instrumentedMethod=" + instrumentedMethod +
                            ", triggeringThrowable=" + triggeringThrowable +
                            ", doesReturn=" + doesReturn +
                            "}";
                }
            }
        }
    }

    /**
     * A byte code appender for implementing {@link Advice}.
     */
    protected static class Appender implements ByteCodeAppender {

        /**
         * The advice to implement.
         */
        private final Advice advice;

        /**
         * The current implementation target.
         */
        private final Implementation.Target implementationTarget;

        /**
         * The delegate byte code appender.
         */
        private final ByteCodeAppender delegate;

        /**
         * Creates a new appender for an advice component.
         *
         * @param advice               The advice to implement.
         * @param implementationTarget The current implementation target.
         * @param delegate             The delegate byte code appender.
         */
        protected Appender(Advice advice, Target implementationTarget, ByteCodeAppender delegate) {
            this.advice = advice;
            this.implementationTarget = implementationTarget;
            this.delegate = delegate;
        }

        @Override
        public Size apply(MethodVisitor methodVisitor, Context implementationContext, MethodDescription instrumentedMethod) {
            EmulatingMethodVisitor emulatingMethodVisitor = new EmulatingMethodVisitor(methodVisitor, delegate);
            methodVisitor = advice.doWrap(implementationTarget.getInstrumentedType(),
                    instrumentedMethod,
                    emulatingMethodVisitor,
                    implementationContext,
                    AsmVisitorWrapper.NO_FLAGS,
                    AsmVisitorWrapper.NO_FLAGS);
            return emulatingMethodVisitor.resolve(methodVisitor, implementationContext, instrumentedMethod);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (other == null || getClass() != other.getClass()) return false;
            Appender appender = (Appender) other;
            return advice.equals(appender.advice)
                    && delegate.equals(appender.delegate)
                    && implementationTarget.equals(appender.implementationTarget);
        }

        @Override
        public int hashCode() {
            int result = advice.hashCode();
            result = 31 * result + implementationTarget.hashCode();
            result = 31 * result + delegate.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return "Advice.Appender{" +
                    "advice=" + advice +
                    ", implementationTarget=" + implementationTarget +
                    ", delegate=" + delegate +
                    '}';
        }

        /**
         * A method visitor that allows for the emulation of a full method visitor invocation circle without delegating initial
         * and ending visitations to the underlying visitor.
         */
        protected static class EmulatingMethodVisitor extends MethodVisitor {

            /**
             * The delegate byte code appender.
             */
            private final ByteCodeAppender delegate;

            /**
             * The currently recorded minimal required stack size.
             */
            private int stackSize;

            /**
             * The currently recorded minimal required local variable array length.
             */
            private int localVariableLength;

            /**
             * Creates a new emulating method visitor.
             *
             * @param methodVisitor The underlying method visitor.
             * @param delegate      The delegate byte code appender.
             */
            protected EmulatingMethodVisitor(MethodVisitor methodVisitor, ByteCodeAppender delegate) {
                super(Opcodes.ASM5, methodVisitor);
                this.delegate = delegate;
            }

            /**
             * Resolves this this advice emulating method visitor for its delegate.
             *
             * @param methodVisitor         The method visitor to apply.
             * @param implementationContext The implementation context to apply.
             * @param instrumentedMethod    The instrumented method.
             * @return The resulting size of the implemented method.
             */
            protected ByteCodeAppender.Size resolve(MethodVisitor methodVisitor,
                                                    Implementation.Context implementationContext,
                                                    MethodDescription instrumentedMethod) {
                methodVisitor.visitCode();
                Size size = delegate.apply(methodVisitor, implementationContext, instrumentedMethod);
                methodVisitor.visitMaxs(size.getOperandStackSize(), size.getLocalVariableSize());
                methodVisitor.visitEnd();
                return new ByteCodeAppender.Size(stackSize, localVariableLength);
            }

            @Override
            public void visitCode() {
                /* do nothing */
            }

            @Override
            public void visitMaxs(int stackSize, int localVariableLength) {
                this.stackSize = stackSize;
                this.localVariableLength = localVariableLength;
            }

            @Override
            public void visitEnd() {
                /* do nothing */
            }

            @Override
            public String toString() {
                return "Advice.Appender.EmulatingMethodVisitor{" +
                        "delegate=" + delegate +
                        ", stackSize=" + stackSize +
                        ", localVariableLength=" + localVariableLength +
                        '}';
            }
        }
    }

    /**
     * <p>
     * Indicates that this method should be inlined before the matched method is invoked. Any class must declare
     * at most one method with this annotation. The annotated method must be static. When instrumenting constructors,
     * the {@code this} values can only be accessed for writing fields but not for reading fields or invoking methods.
     * </p>
     * <p>
     * The annotated method can return a value that is made accessible to another method annotated by {@link OnMethodExit}.
     * </p>
     *
     * @see Advice
     * @see Argument
     * @see This
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(ElementType.METHOD)
    public @interface OnMethodEnter {

        /**
         * Determines if the annotated method should be inlined into the instrumented method or invoked from it. When a method
         * is inlined, its byte code is copied into the body of the target method. this makes it is possible to execute code
         * with the visibility privileges of the instrumented method while loosing the privileges of the declared method methods.
         * When a method is not inlined, it is invoked similarly to a common Java method call. Note that it is not possible to
         * set breakpoints within a method when it is inlined as no debugging information is copied from the advice method into
         * the instrumented method.
         *
         * @return {@code true} if the annotated method should be inlined into the instrumented method.
         */
        boolean inline() default true;

        /**
         * Indicates that this advice should suppress any {@link Throwable} type being thrown during the advice's execution.
         *
         * @return The type of {@link Throwable} to suppress.
         */
        Class<? extends Throwable> suppress() default NoExceptionHandler.class;

        /**
         * When specifying a non-primitive type, this method's return value that is subject to an {@code instanceof} check where
         * the instrumented method is only executed, if the returned instance is {@code not} an instance of the specified class.
         * Alternatively, it is possible to specify either {@link OnDefaultValue} or {@link OnNonDefaultValue} where the instrumented
         * method is only executed if the advice method returns a default or non-default value of the advice method's return type.
         * It is illegal to specify a primitive type as an argument whereas setting the value to {@code void} indicates that the
         * instrumented method should never be skipped.
         *
         * @return A value defining what return values of the advice method indicate that the instrumented method
         * should be skipped or {@code void} if the instrumented method should never be skipped.
         */
        Class<?> skipOn() default void.class;

        /**
         * If set to {@code true}, the instrumented method's line number information is adjusted such that stack traces generated within
         * this advice method appear as if they were generated within the first line of the instrumented method. If set to {@code false},
         * no line number information is made available for such stack traces.
         *
         * @return {@code true} if this advice code should appear as if it was added within the first line of the instrumented method.
         */
        boolean prependLineNumber() default true;
    }

    /**
     * <p>
     * Indicates that this method should be executed before exiting the instrumented method. Any class must declare
     * at most one method with this annotation. The annotated method must be static.
     * </p>
     * <p>
     * By default, the annotated method is not invoked if the instrumented method terminates exceptionally. This behavior
     * can be changed by setting the {@link OnMethodExit#onThrowable()} property to an exception type for which this advice
     * method should be invoked. By setting the value to {@link Throwable}, the advice method is always invoked.
     * </p>
     *
     * @see Advice
     * @see Argument
     * @see This
     * @see Enter
     * @see Return
     * @see Thrown
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(ElementType.METHOD)
    public @interface OnMethodExit {

        /**
         * Determines if the annotated method should be inlined into the instrumented method or invoked from it. When a method
         * is inlined, its byte code is copied into the body of the target method. this makes it is possible to execute code
         * with the visibility privileges of the instrumented method while loosing the privileges of the declared method methods.
         * When a method is not inlined, it is invoked similarly to a common Java method call. Note that it is not possible to
         * set breakpoints within a method when it is inlined as no debugging information is copied from the advice method into
         * the instrumented method.
         *
         * @return {@code true} if the annotated method should be inlined into the instrumented method.
         */
        boolean inline() default true;

        /**
         * Indicates that this advice should suppress any {@link Throwable} type being thrown during the advice's execution.
         *
         * @return The type of {@link Throwable} to suppress.
         */
        Class<? extends Throwable> suppress() default NoExceptionHandler.class;

        /**
         * Indicates a {@link Throwable} super type for which this exit advice is invoked if it was thrown from the instrumented method.
         * If an exception is thrown, it is available via the {@link Thrown} parameter annotation. If a method returns exceptionally,
         * any parameter annotated with {@link Return} is assigned the parameter type's default value.
         *
         * @return The type of {@link Throwable} for which this exit advice handler is invoked.
         */
        Class<? extends Throwable> onThrowable() default NoExceptionHandler.class;
    }

    /**
     * Indicates that the annotated parameter should be mapped to the parameter with index {@link Argument#value()} of
     * the instrumented method.
     *
     * @see Advice
     * @see OnMethodEnter
     * @see OnMethodExit
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(ElementType.PARAMETER)
    public @interface Argument {

        /**
         * Returns the index of the mapped parameter.
         *
         * @return The index of the mapped parameter.
         */
        int value();

        /**
         * Indicates if it is possible to write to this parameter. If this property is set to {@code false}, the annotated
         * type must be equal to the parameter of the instrumented method. If this property is set to {@code true}, the
         * annotated parameter can be any super type of the instrumented methods parameter.
         *
         * @return {@code true} if this parameter is read-only.
         */
        boolean readOnly() default true;
    }

    /**
     * <p>
     * Indicates that the annotated parameter should be mapped to the {@code this} reference of the instrumented method.
     * </p>
     * <p>
     * <b>Important</b>: Parameters with this option must not be used when from a constructor in combination with
     * {@link OnMethodEnter} where the {@code this} reference is not available.
     * </p>
     *
     * @see Advice
     * @see OnMethodEnter
     * @see OnMethodExit
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(ElementType.PARAMETER)
    public @interface This {

        /**
         * Indicates if it is possible to write to this parameter. If this property is set to {@code false}, the annotated
         * type must be equal to the type declaring the instrumented method. If this property is set to {@code true}, the
         * annotated parameter can be any super type of the instrumented method's declaring type.
         *
         * @return {@code true} if this parameter is read-only.
         */
        boolean readOnly() default true;

        /**
         * Determines if the parameter should be assigned {@code null} if the instrumented method is static.
         *
         * @return {@code true} if the value assignment is optional.
         */
        boolean optional() default false;
    }

    /**
     * <p>
     * Indicates that the annotated parameter should be mapped to a field in the scope of the instrumented method.
     * </p>
     * <p>
     * <b>Important</b>: Parameters with this option must not be used when from a constructor in combination with
     * {@link OnMethodEnter} and a non-static field where the {@code this} reference is not available.
     * </p>
     * <p>
     * <b>Note</b>: As the mapping is virtual, Byte Buddy might be required to reserve more space on the operand stack than the
     * optimal value when accessing this parameter. This does not normally matter as the additional space requirement is minimal.
     * However, if the runtime performance of class creation is secondary, one can require ASM to recompute the optimal frames by
     * setting {@link ClassWriter#COMPUTE_MAXS}. This is however only relevant when writing to a non-static field.
     * </p>
     *
     * @see Advice
     * @see OnMethodEnter
     * @see OnMethodExit
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(ElementType.PARAMETER)
    public @interface FieldValue {

        /**
         * Returns the name of the field.
         *
         * @return The name of the field.
         */
        String value();

        /**
         * Returns the type that declares the field that should be mapped to the annotated parameter. If this property
         * is set to {@code void}, the field is looked up implicitly within the instrumented class's class hierarchy.
         *
         * @return The type that declares the field or {@code void} if this type should be determined implicitly.
         */
        Class<?> declaringType() default void.class;

        /**
         * Indicates if it is possible to write to this parameter. If this property is set to {@code false}, the annotated
         * type must be equal to the mapped field type. If this property is set to {@code true}, the  annotated parameter
         * can be any super type of the field type.
         *
         * @return {@code true} if this parameter is read-only.
         */
        boolean readOnly() default true;
    }

    /**
     * <p>
     * Indicates that the annotated parameter should be mapped to a string representation of the instrumented method,
     * a constant representing the {@link Class} declaring the adviced method or a {@link Method}, {@link Constructor}
     * or {@code java.lang.reflect.Executable} representing this method.
     * </p>
     * <p>
     * <b>Note</b>: A constant representing a {@link Method} or {@link Constructor} is not cached but is recreated for
     * every read.
     * </p>
     *
     * @see Advice
     * @see OnMethodEnter
     * @see OnMethodExit
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(ElementType.PARAMETER)
    public @interface Origin {

        /**
         * Indicates that the origin string should be indicated by the {@link Object#toString()} representation of the instrumented method.
         */
        String DEFAULT = "";

        /**
         * Returns the pattern the annotated parameter should be assigned. By default, the {@link Origin#toString()} representation
         * of the method is assigned. Alternatively, a pattern can be assigned where:
         * <ul>
         * <li>{@code #t} inserts the method's declaring type.</li>
         * <li>{@code #m} inserts the name of the method ({@code <init>} for constructors and {@code <clinit>} for static initializers).</li>
         * <li>{@code #d} for the method's descriptor.</li>
         * <li>{@code #s} for the method's signature.</li>
         * <li>{@code #r} for the method's return type.</li>
         * </ul>
         * Any other {@code #} character must be escaped by {@code \} which can be escaped by itself. This property is ignored if the annotated
         * parameter is of type {@link Class}.
         *
         * @return The pattern the annotated parameter should be assigned.
         */
        String value() default DEFAULT;
    }

    /**
     * Indicates that the annotated parameter should always return a default value (i.e. {@code 0} for numeric values, {@code false}
     * for {@code boolean} types and {@code null} for reference types).
     *
     * @see Advice
     * @see OnMethodEnter
     * @see OnMethodExit
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(ElementType.PARAMETER)
    public @interface Unused {
        /* empty */
    }

    /**
     * Indicates that the annotated parameter should always return a default a boxed version of the instrumented methods return value
     * (i.e. {@code 0} for numeric values, {@code false} for {@code boolean} types and {@code null} for reference types). The annotated
     * parameter must be of type {@link Object}.
     *
     * @see Advice
     * @see OnMethodEnter
     * @see OnMethodExit
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(ElementType.PARAMETER)
    public @interface StubValue {
        /* empty */
    }

    /**
     * Indicates that the annotated parameter should be mapped to the value that is returned by the advice method that is annotated
     * by {@link OnMethodEnter}.
     *
     * @see Advice
     * @see OnMethodExit
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(ElementType.PARAMETER)
    public @interface Enter {

        /**
         * Indicates if it is possible to write to this parameter. If this property is set to {@code false}, the annotated
         * type must be equal to the parameter of the instrumented method. If this property is set to {@code true}, the
         * annotated parameter can be any super type of the instrumented methods parameter.
         *
         * @return {@code true} if this parameter is read-only.
         */
        boolean readOnly() default true;
    }

    /**
     * Indicates that the annotated parameter should be mapped to the return value of the instrumented method. If the instrumented
     * method terminates exceptionally, the type's default value is assigned to the parameter, i.e. {@code 0} for numeric types
     * and {@code null} for reference types.
     *
     * @see Advice
     * @see OnMethodExit
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(ElementType.PARAMETER)
    public @interface Return {

        /**
         * Indicates if it is possible to write to this parameter. If this property is set to {@code false}, the annotated
         * type must be equal to the parameter of the instrumented method. If this property is set to {@code true}, the
         * annotated parameter can be any super type of the instrumented methods parameter.
         *
         * @return {@code true} if this parameter is read-only.
         */
        boolean readOnly() default true;
    }

    /**
     * <p>
     * Indicates that the annotated parameter should be mapped to a the return value where primitive types are boxed. For this
     * to be possible, the annotated parameter must be of type {@link Object}.
     * </p>
     * <p>
     * Note that accessing this parameter is merely virtual. A new array is created on every access. As a result, changes to the
     * array have no effect other than for the local copy and when accessing the array twice, the equality relation does not hold.
     * For example, for {@code @Advice.BoxedReturn Object foo}, the relation {@code foo == foo} does not necessarily hold for primitive
     * types. For avoiding additional allocations, the array needs to be stored in a separate local variable. The variable itself is
     * always read only.
     * </p>
     * <p>
     * <b>Note</b>: As the mapping is virtual, Byte Buddy might be required to reserve more space on the operand stack than the
     * optimal value when accessing this parameter. This does not normally matter as the additional space requirement is minimal.
     * However, if the runtime performance of class creation is secondary, one can require ASM to recompute the optimal frames by
     * setting {@link ClassWriter#COMPUTE_MAXS}.
     * </p>
     *
     * @see Advice
     * @see OnMethodEnter
     * @see OnMethodExit
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(ElementType.PARAMETER)
    public @interface BoxedReturn {

        /**
         * Determines if it should be possible to write to the boxed return value. When writing to the return value, the assigned type
         * is casted to the instrumented method's return type. If the method's return type is primitive, the value is unboxed from the
         * primitive return type's wrapper type after casting to the latter type. If the method is {@code void}, the assigned value is
         * simply dropped.
         *
         * @return {@code true} if it should be possible to write to the annotated parameter.
         */
        boolean readOnly() default true;
    }

    /**
     * <p>
     * Indicates that the annotated parameter should be mapped to an array containing a (boxed) version of all arguments of the
     * method being instrumented. It is required that the annotated parameter is an array of type {@link Object}.
     * </p>
     * <p>
     * Note that accessing this parameter is merely virtual. A new array is created on every access. As a result, changes to the
     * array have no effect other than for the local copy and when accessing the array twice, the equality relation does not hold.
     * For example, for {@code @Advice.BoxedArguments Object[] foo}, the relation {@code foo == foo} does not hold. For avoiding
     * new allocations, the array needs to be stored in a separate local variable. The variable itself is always read only.
     * </p>
     * <p>
     * <b>Note</b>: As the mapping is virtual, Byte Buddy might be required to reserve more space on the operand stack than the
     * optimal value when accessing this parameter. This does not normally matter as the additional space requirement is minimal.
     * However, if the runtime performance of class creation is secondary, one can require ASM to recompute the optimal frames by
     * setting {@link ClassWriter#COMPUTE_MAXS}.
     * </p>
     *
     * @see Advice
     * @see OnMethodEnter
     * @see OnMethodExit
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(ElementType.PARAMETER)
    public @interface BoxedArguments {

        /**
         * <p>
         * Determines if it should be possible to assign an array with boxed argument values of all parameters which replace the original
         * parameters. If the assigned array has less arguments than the current methods has parameters, an {@link ArrayIndexOutOfBoundsException}
         * is thrown. It it contains incompatible types, a {@link ClassCastException} is thrown.
         * </p>
         * <p>
         * <b>Important</b>: It is not possible to assign elements of the annotated array. Instead, an array needs to be assigned to
         * the annotated parameter. This does not necessarily imply allocation overhead as local allocations are typically removed
         * by the JVM's optimizer. Writing directly to the annotated parameter has no effect.
         * </p>
         *
         * @return {@code true} if it should be possible to assign an array of replacement arguments to the annotated parameter.
         */
        boolean readOnly() default true;
    }

    /**
     * Indicates that the annotated parameter should be mapped to the return value of the instrumented method. For this to be valid,
     * the parameter must be of type {@link Throwable}. If the instrumented method terminates regularly, {@code null} is assigned to
     * the annotated parameter.
     *
     * @see Advice
     * @see OnMethodExit
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(ElementType.PARAMETER)
    public @interface Thrown {

        /**
         * <p>
         * Indicates if it is possible to write to this parameter. If this property is set to {@code false}, it is illegal to
         * write to the annotated parameter. If this property is set to {@code true}, the annotated parameter can either be set
         * to {@code null} to suppress an exception that was thrown by the adviced method or it can be set to any other exception
         * that will be thrown after the advice method returned.
         * </p>
         * <p>
         * If an exception is suppressed, the default value for the return type is returned from the method, i.e. {@code 0} for any
         * numeric type and {@code null} for a reference type. The default value can be replaced via the {@link Return} annotation.
         * </p>
         *
         * @return {@code true} if this parameter is read-only.
         */
        boolean readOnly() default true;
    }

    /**
     * <p>
     * A dynamic value allows to bind parameters of an {@link Advice} method to a custom, constant value.
     * </p>
     * <p>The mapped value must be a constant value that can be embedded into a Java class file. This holds for all primitive types,
     * instances of {@link String} and for {@link Class} instances as well as their unloaded {@link TypeDescription} representations.
     * </p>
     *
     * @param <T> The type of the annotation this dynamic value requires to provide a mapping.
     * @see WithCustomMapping
     */
    public interface DynamicValue<T extends Annotation> {

        /**
         * <p>
         * Resolves a constant value that is mapped to a parameter that is annotated with a custom bound annotation:
         * </p>
         * <ul>
         * <li>A primitive wrapper value allow binding of the primitive which is optionally boxed but never unboxed.</li>
         * <li>A {@link Class} or {@link TypeDescription} indicate the binding of a type constant.</li>
         * <li>A {@link FieldDescription} indicates binding the field value. The field must be visible and be declared by a super type or
         * be static. A field value is optionally boxed but never unboxed,</li>
         * <li>A {@link ParameterDescription} indicates binding the assigned argument value. The parameter must be declared by the instrumented method.
         * The parameter value is optionally boxed but never unboxed,</li>
         * <li>A {@link Serializable} value is serialized and stored Base64 encoded in the constant pool.</li>
         * </ul>
         *
         * @param instrumentedType   The instrumented type.
         * @param instrumentedMethod The instrumented method onto which this advice is applied.
         * @param target             The target parameter that is bound.
         * @param annotation         The annotation that triggered this binding.
         * @param initialized        {@code true} if the method is initialized when the value is bound, i.e. that the value is not
         *                           supplied to a constructor before the super constructor was invoked.
         * @return A constant value, a serializable value or a field or parameter description to bind to the supplied parameter or
         * {@code null} to assign this value.
         */
        Object resolve(TypeDescription instrumentedType,
                       MethodDescription instrumentedMethod,
                       ParameterDescription.InDefinedShape target,
                       AnnotationDescription.Loadable<T> annotation,
                       boolean initialized);

        /**
         * <p>
         * A {@link DynamicValue} implementation that always binds a fixed value.
         * </p>
         * <p>
         * The mapped value must be a constant value that can be embedded into a Java class file. This holds for all primitive types,
         * instances of {@link String} and for {@link Class} instances as well as their unloaded {@link TypeDescription} representations.
         * </p>
         */
        class ForFixedValue implements DynamicValue<Annotation> {

            /**
             * The fixed value to bind to the corresponding annotation.
             */
            private final Object value;

            /**
             * Creates a dynamic value for a fixed value.
             *
             * @param value The fixed value to bind to the corresponding annotation.
             */
            public ForFixedValue(Object value) {
                this.value = value;
            }

            @Override
            public Object resolve(TypeDescription instrumentedType,
                                  MethodDescription instrumentedMethod,
                                  ParameterDescription.InDefinedShape target,
                                  AnnotationDescription.Loadable<Annotation> annotation,
                                  boolean initialized) {
                return value;
            }

            @Override
            public boolean equals(Object other) {
                if (this == other) return true;
                if (other == null || getClass() != other.getClass()) return false;
                ForFixedValue that = (ForFixedValue) other;
                return value != null ? value.equals(that.value) : that.value == null;
            }

            @Override
            public int hashCode() {
                return value != null ? value.hashCode() : 0;
            }

            @Override
            public String toString() {
                return "Advice.DynamicValue.ForFixedValue{" +
                        "value=" + value +
                        '}';
            }
        }

        /**
         * A dynamic value for an annotation type's property.
         *
         * @param <T> The type of the annotation for which a property is bound.
         */
        class ForAnnotationProperty<T extends Annotation> implements DynamicValue<T> {

            /**
             * The annotation property.
             */
            private final MethodDescription.InDefinedShape annotationProperty;

            /**
             * Creates a new dynamic value for an annotation property.
             *
             * @param annotationProperty The annotation property.
             */
            protected ForAnnotationProperty(MethodDescription.InDefinedShape annotationProperty) {
                this.annotationProperty = annotationProperty;
            }

            /**
             * Locates the annotation property of the given name or throws an exception if no such property exists.
             *
             * @param type     The annotation type being bound.
             * @param property The name of the annotation property.
             * @param <T>      The type of the annotation.
             * @return A dynamic value for the located property type.
             */
            protected static <T extends Annotation> DynamicValue<T> of(Class<? extends T> type, String property) {
                try {
                    return new ForAnnotationProperty<T>(new MethodDescription.ForLoadedMethod(type.getDeclaredMethod(property)));
                } catch (NoSuchMethodException exception) {
                    throw new IllegalArgumentException("Property '" + property + "' does not exist for " + type, exception);
                }
            }

            @Override
            public Object resolve(TypeDescription instrumentedType,
                                  MethodDescription instrumentedMethod,
                                  ParameterDescription.InDefinedShape target,
                                  AnnotationDescription.Loadable<T> annotation,
                                  boolean initialized) {
                return annotation.getValue(annotationProperty).resolve();
            }

            @Override
            public boolean equals(Object other) {
                if (this == other) return true;
                if (other == null || getClass() != other.getClass()) return false;
                ForAnnotationProperty<?> that = (ForAnnotationProperty<?>) other;
                return annotationProperty.equals(that.annotationProperty);
            }

            @Override
            public int hashCode() {
                return annotationProperty.hashCode();
            }

            @Override
            public String toString() {
                return "Advice.DynamicValue.ForAnnotationProperty{" +
                        "annotationProperty=" + annotationProperty +
                        '}';
            }
        }
    }

    /**
     * A builder step for creating an {@link Advice} that uses custom mappings of annotations to constant pool values.
     */
    public static class WithCustomMapping {

        /**
         * A map containing dynamically computed constant pool values that are mapped by their triggering annotation type.
         */
        private final Map<Class<? extends Annotation>, DynamicValue<?>> dynamicValues;

        /**
         * Creates a new custom mapping builder step without including any custom mappings.
         */
        protected WithCustomMapping() {
            this(Collections.<Class<? extends Annotation>, DynamicValue<?>>emptyMap());
        }

        /**
         * Creates a new custom mapping builder step with the given custom mappings.
         *
         * @param dynamicValues A map containing dynamically computed constant pool values that are mapped by their triggering annotation type.
         */
        protected WithCustomMapping(Map<Class<? extends Annotation>, DynamicValue<?>> dynamicValues) {
            this.dynamicValues = dynamicValues;
        }

        /**
         * Binds the supplied annotation to a type constant of the supplied value.
         *
         * @param type            The type of the annotation being bound.
         * @param typeDescription The type reference to bind to this annotation.
         * @param <T>             The annotation type.
         * @return A new builder for an advice that considers the supplied annotation type during binding.
         * @see DynamicValue.ForFixedValue
         */
        public <T extends Annotation> WithCustomMapping bind(Class<? extends T> type, TypeDescription typeDescription) {
            return bind(type, new DynamicValue.ForFixedValue(typeDescription));
        }

        /**
         * Binds the supplied annotation to the value of the supplied field. The field must be visible by the
         * instrumented type and must be declared by a super type of the instrumented field.
         *
         * @param type  The type of the annotation being bound.
         * @param field The field to bind to this annotation.
         * @param <T>   The annotation type.
         * @return A new builder for an advice that considers the supplied annotation type during binding.
         * @see DynamicValue.ForFixedValue
         */
        public <T extends Annotation> WithCustomMapping bind(Class<? extends T> type, Field field) {
            return bind(type, new FieldDescription.ForLoadedField(field));
        }

        /**
         * Binds the supplied annotation to the value of the supplied field. The field must be visible by the
         * instrumented type and must be declared by a super type of the instrumented field.
         *
         * @param type             The type of the annotation being bound.
         * @param fieldDescription The field to bind to this annotation.
         * @param <T>              The annotation type.
         * @return A new builder for an advice that considers the supplied annotation type during binding.
         * @see DynamicValue.ForFixedValue
         */
        public <T extends Annotation> WithCustomMapping bind(Class<? extends T> type, FieldDescription fieldDescription) {
            return bind(type, new DynamicValue.ForFixedValue(fieldDescription));
        }

        /**
         * Binds the supplied annotation to the supplied parameter's argument.
         *
         * @param type   The type of the annotation being bound.
         * @param method The method that defines the parameter.
         * @param index  The index of the parameter.
         * @param <T>    The annotation type.
         * @return A new builder for an advice that considers the supplied annotation type during binding.
         * @see DynamicValue.ForFixedValue
         */
        public <T extends Annotation> WithCustomMapping bind(Class<? extends T> type, Method method, int index) {
            if (index < 0) {
                throw new IllegalArgumentException("A parameter cannot be negative: " + index);
            } else if (method.getParameterTypes().length <= index) {
                throw new IllegalArgumentException(method + " does not declare a parameter with index " + index);
            }
            return bind(type, new MethodDescription.ForLoadedMethod(method).getParameters().get(index));
        }

        /**
         * Binds the supplied annotation to the supplied parameter's argument.
         *
         * @param type        The type of the annotation being bound.
         * @param constructor The constructor that defines the parameter.
         * @param index       The index of the parameter.
         * @param <T>         The annotation type.
         * @return A new builder for an advice that considers the supplied annotation type during binding.
         * @see DynamicValue.ForFixedValue
         */
        public <T extends Annotation> WithCustomMapping bind(Class<? extends T> type, Constructor<?> constructor, int index) {
            if (index < 0) {
                throw new IllegalArgumentException("A parameter cannot be negative: " + index);
            } else if (constructor.getParameterTypes().length <= index) {
                throw new IllegalArgumentException(constructor + " does not declare a parameter with index " + index);
            }
            return bind(type, new MethodDescription.ForLoadedConstructor(constructor).getParameters().get(index));
        }

        /**
         * Binds the supplied annotation to the supplied parameter's argument.
         *
         * @param type                 The type of the annotation being bound.
         * @param parameterDescription The parameter for which to bind an argument.
         * @param <T>                  The annotation type.
         * @return A new builder for an advice that considers the supplied annotation type during binding.
         * @see DynamicValue.ForFixedValue
         */
        public <T extends Annotation> WithCustomMapping bind(Class<? extends T> type, ParameterDescription parameterDescription) {
            return bind(type, new DynamicValue.ForFixedValue(parameterDescription));
        }

        /**
         * Binds the supplied annotation to the supplied fixed value.
         *
         * @param type  The type of the annotation being bound.
         * @param value The value to bind to this annotation.
         * @param <T>   The annotation type.
         * @return A new builder for an advice that considers the supplied annotation type during binding.
         * @see DynamicValue.ForFixedValue
         */
        public <T extends Annotation> WithCustomMapping bind(Class<? extends T> type, Serializable value) {
            return bind(type, new DynamicValue.ForFixedValue(value));
        }

        /**
         * Binds the supplied annotation to the annotation's property of the specified name.
         *
         * @param type     The type of the annotation being bound.
         * @param property The name of the annotation property to be bound.
         * @param <T>      The annotation type.
         * @return A new builder for an advice that considers the supplied annotation during binding.
         */
        public <T extends Annotation> WithCustomMapping bindProperty(Class<? extends T> type, String property) {
            return bind(type, DynamicValue.ForAnnotationProperty.<T>of(type, property));
        }

        /**
         * Binds an annotation type to dynamically computed value. Whenever the {@link Advice} component discovers the given annotation on
         * a parameter of an advice method, the dynamic value is asked to provide a value that is then assigned to the parameter in question.
         *
         * @param type         The annotation type that triggers the mapping.
         * @param dynamicValue The dynamic value that is computed for binding the parameter to a value.
         * @param <T>          The annotation type.
         * @return A new builder for an advice that considers the supplied annotation type during binding.
         */
        public <T extends Annotation> WithCustomMapping bind(Class<? extends T> type, DynamicValue<T> dynamicValue) {
            Map<Class<? extends Annotation>, DynamicValue<?>> dynamicValues = new HashMap<Class<? extends Annotation>, Advice.DynamicValue<?>>(this.dynamicValues);
            if (!type.isAnnotation()) {
                throw new IllegalArgumentException("Not an annotation type: " + type);
            } else if (dynamicValues.put(type, dynamicValue) != null) {
                throw new IllegalArgumentException("Annotation type already mapped: " + type);
            }
            return new WithCustomMapping(dynamicValues);
        }

        /**
         * Implements advice where every matched method is advised by the given type's advisory methods. The advices binary representation is
         * accessed by querying the class loader of the supplied class for a class file.
         *
         * @param advice The type declaring the advice.
         * @return A method visitor wrapper representing the supplied advice.
         */
        public Advice to(Class<?> advice) {
            return to(advice, ClassFileLocator.ForClassLoader.of(advice.getClassLoader()));
        }

        /**
         * Implements advice where every matched method is advised by the given type's advisory methods.
         *
         * @param advice           The type declaring the advice.
         * @param classFileLocator The class file locator for locating the advisory class's class file.
         * @return A method visitor wrapper representing the supplied advice.
         */
        public Advice to(Class<?> advice, ClassFileLocator classFileLocator) {
            return to(new TypeDescription.ForLoadedType(advice), classFileLocator);
        }

        /**
         * Implements advice where every matched method is advised by the given type's advisory methods.
         *
         * @param advice           A description of the type declaring the advice.
         * @param classFileLocator The class file locator for locating the advisory class's class file.
         * @return A method visitor wrapper representing the supplied advice.
         */
        public Advice to(TypeDescription advice, ClassFileLocator classFileLocator) {
            List<Dispatcher.OffsetMapping.Factory> userFactories = new ArrayList<Dispatcher.OffsetMapping.Factory>(dynamicValues.size());
            for (Map.Entry<Class<? extends Annotation>, DynamicValue<?>> entry : dynamicValues.entrySet()) {
                userFactories.add(Dispatcher.OffsetMapping.ForUserValue.Factory.of(entry.getKey(), entry.getValue()));
            }
            return Advice.to(advice, classFileLocator, userFactories);
        }

        /**
         * Implements advice where every matched method is advised by the given type's advisory methods. The advices binary representation is
         * accessed by querying the class loader of the supplied class for a class file.
         *
         * @param enterAdvice The type declaring the enter advice.
         * @param exitAdvice  The type declaring the exit advice.
         * @return A method visitor wrapper representing the supplied advice.
         */
        public Advice to(Class<?> enterAdvice, Class<?> exitAdvice) {
            ClassLoader enterLoader = enterAdvice.getClassLoader(), exitLoader = exitAdvice.getClassLoader();
            return to(enterAdvice, exitAdvice, enterLoader == exitLoader
                    ? ClassFileLocator.ForClassLoader.of(enterLoader)
                    : new ClassFileLocator.Compound(ClassFileLocator.ForClassLoader.of(enterLoader), ClassFileLocator.ForClassLoader.of(exitLoader)));
        }

        /**
         * Implements advice where every matched method is advised by the given type's advisory methods.
         *
         * @param enterAdvice      The type declaring the enter advice.
         * @param exitAdvice       The type declaring the exit advice.
         * @param classFileLocator The class file locator for locating the advisory class's class file.
         * @return A method visitor wrapper representing the supplied advice.
         */
        public Advice to(Class<?> enterAdvice, Class<?> exitAdvice, ClassFileLocator classFileLocator) {
            return to(new TypeDescription.ForLoadedType(enterAdvice), new TypeDescription.ForLoadedType(exitAdvice), classFileLocator);
        }

        /**
         * Implements advice where every matched method is advised by the given type's advisory methods. Using this method, a non-operational
         * class file locator is specified for the advice target. This implies that only advice targets with the <i>inline</i> target set
         * to {@code false} are resolvable by the returned instance.
         *
         * @param enterAdvice The type declaring the enter advice.
         * @param exitAdvice  The type declaring the exit advice.
         * @return A method visitor wrapper representing the supplied advice.
         */
        public Advice to(TypeDescription enterAdvice, TypeDescription exitAdvice) {
            return to(enterAdvice, exitAdvice, ClassFileLocator.NoOp.INSTANCE);
        }

        /**
         * Implements advice where every matched method is advised by the given type's advisory methods.
         *
         * @param enterAdvice      The type declaring the enter advice.
         * @param exitAdvice       The type declaring the exit advice.
         * @param classFileLocator The class file locator for locating the advisory class's class file.
         * @return A method visitor wrapper representing the supplied advice.
         */
        public Advice to(TypeDescription enterAdvice, TypeDescription exitAdvice, ClassFileLocator classFileLocator) {
            List<Dispatcher.OffsetMapping.Factory> userFactories = new ArrayList<Dispatcher.OffsetMapping.Factory>(dynamicValues.size());
            for (Map.Entry<Class<? extends Annotation>, DynamicValue<?>> entry : dynamicValues.entrySet()) {
                userFactories.add(Dispatcher.OffsetMapping.ForUserValue.Factory.of(entry.getKey(), entry.getValue()));
            }
            return Advice.to(enterAdvice, exitAdvice, classFileLocator, userFactories);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (other == null || getClass() != other.getClass()) return false;
            WithCustomMapping that = (WithCustomMapping) other;
            return dynamicValues.equals(that.dynamicValues);
        }

        @Override
        public int hashCode() {
            return dynamicValues.hashCode();
        }

        @Override
        public String toString() {
            return "Advice.WithCustomMapping{" +
                    "dynamicValues=" + dynamicValues +
                    '}';
        }
    }

    /**
     * A marker class that indicates that an advice method does not suppress any {@link Throwable}.
     */
    private static class NoExceptionHandler extends Throwable {

        /**
         * A description of the {@link NoExceptionHandler} type.
         */
        private static final TypeDescription DESCRIPTION = new TypeDescription.ForLoadedType(NoExceptionHandler.class);

        /**
         * A private constructor as this class is not supposed to be invoked.
         */
        private NoExceptionHandler() {
            throw new UnsupportedOperationException("This marker class is not supposed to be instantiated");
        }
    }

    /**
     * A marker type to be used as an argument for {@link OnMethodEnter#skipOn()}. If this value is set, the instrumented method
     * is not invoked if the annotated advice method <b>returns a default value</b>. A default value is {@code false} for a
     * {@code boolean} type, {@code 0} for a {@code byte}, {@code short}, {@code char}, {@code int}, {@code long}, {@code float}
     * or {@code double} type and {@code null} for a reference type. It is illegal to use this value if the advice method
     * returns {@code void}.
     */
    public static final class OnDefaultValue {

        /**
         * A private constructor as this class is not supposed to be invoked.
         */
        private OnDefaultValue() {
            throw new UnsupportedOperationException("This marker class is not supposed to be instantiated");
        }
    }

    /**
     * A marker type to be used as an argument for {@link OnMethodEnter#skipOn()}. If this value is set, the instrumented method
     * is not invoked if the annotated advice method <b>returns a non-default value</b>. A default value is {@code false} for a
     * {@code boolean} type, {@code 0} for a {@code byte}, {@code short}, {@code char}, {@code int}, {@code long}, {@code float}
     * or {@code double} type and {@code null} for a reference type. It is illegal to use this value if the advice method
     * returns {@code void}.
     */
    public static final class OnNonDefaultValue {

        /**
         * A private constructor as this class is not supposed to be invoked.
         */
        private OnNonDefaultValue() {
            throw new UnsupportedOperationException("This marker class is not supposed to be instantiated");
        }
    }
}
