/**
 * ﻿Copyright 2015-2017 Valery Silaev (http://vsilaev.com)
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:

 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.

 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.

 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.tascalate.async.spi;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.tascalate.async.api.Scheduler;
import net.tascalate.async.api.SchedulerProvider;
import net.tascalate.async.util.Cache;

public class SchedulerProviderLookup {
    
    abstract public static class Accessor {
        abstract protected Object doRead(Object target) throws ReflectiveOperationException;
        abstract protected boolean isVisibleTo(Class<?> subClass);
        
        final protected boolean isVisibleTo(Class<?> subClass, Member member) {
            Class<?> declaringClass = member.getDeclaringClass();
            if (!declaringClass.isAssignableFrom(subClass)) {
                return false;
            }
            int modifiers = member.getModifiers();
            if (0 != (modifiers & (Modifier.PUBLIC | Modifier.PROTECTED))) {
                return true;
            } else {
                if (0 != (modifiers & Modifier.PRIVATE)) {
                    return subClass == declaringClass;
                } else {
                    return subClass.getPackage().equals(declaringClass.getPackage());
                }
            }
            
        }
        
        final public Scheduler read(Object target) {
            try {
                return (Scheduler)doRead(target);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        }
    }
    
    static final class ReadClassField extends Accessor {
        final private Field field;
        
        ReadClassField(Field field) {
            this.field = field;
        }
        
        @Override
        protected final boolean isVisibleTo(Class<?> subClass) {
            return isVisibleTo(subClass, field);
        }
        
        @Override
        protected final Object doRead(Object target) throws ReflectiveOperationException {
            return field.get(null);
        }
        
        @Override
        public String toString() {
            return "FIELD: {" + field.toString() + "}";
        }
    }
    
    static final class ReadInstanceField extends Accessor {
        final private Field field;
        
        ReadInstanceField(Field field) {
            this.field = field;
        }
        
        @Override
        protected final boolean isVisibleTo(Class<?> subClass) {
            return isVisibleTo(subClass, field);
        }
        
        @Override
        protected final Object doRead(Object target) throws ReflectiveOperationException {
            return field.get(target);
        }
        
        @Override
        public String toString() {
            return "FIELD: {" + field.toString() + "}";
        }
    }
    
    static final class InvokeClassGetter extends Accessor {
        final private Method method;
        
        InvokeClassGetter(Method method) {
            this.method = method;
        }
        
        @Override
        protected final boolean isVisibleTo(Class<?> subClass) {
            return isVisibleTo(subClass, method);
        }
        
        @Override
        protected final Object doRead(Object target) throws ReflectiveOperationException {
            return method.invoke(null);
        }
        
        @Override
        public String toString() {
            return "METHOD: {" + method.toString() + "}";
        }
    }
    
    static final class InvokeInstanceGetter extends Accessor {
        final private Method method;
        
        InvokeInstanceGetter(Method method) {
            this.method = method;
        }
        
        @Override
        protected final boolean isVisibleTo(Class<?> subClass) {
            return isVisibleTo(subClass, method);
        }
        
        @Override
        protected final Object doRead(Object target) throws ReflectiveOperationException {
            return method.invoke(target);
        }
        
        @Override
        public String toString() {
            return "METHOD: {" + method.toString() + "}";
        }        
    }
    
    static final Accessor NO_ACCESSOR = new Accessor() {
        protected Object doRead(Object target) { 
            return null; 
        } 
        protected boolean isVisibleTo(Class<?> subClass) {
            return false;
        }
    };
    
    private final Cache<Class<?>, Accessor> accessorsCache = new Cache<>();
    
    private final boolean inspectSuperclasses;
    private final boolean inspectInterfaces;
    private final boolean checkVisibility;
    private final boolean superClassPriority;
    
    public SchedulerProviderLookup(boolean inspectSuperclasses, boolean inspectInterfaces, boolean checkVisibility, boolean superClassPriority) {
        this.inspectSuperclasses = inspectSuperclasses;
        this.inspectInterfaces   = inspectInterfaces;
        this.checkVisibility     = checkVisibility;   
        this.superClassPriority  = superClassPriority;
    }
    
    public Accessor getAccessor(Class<?> targetClass) {
        return getAccessor(targetClass, new HashSet<>());
    }
    
    protected Accessor getAccessor(Class<?> targetClass, Set<Class<?>> visitedInterfaces) {
        Accessor result = accessorsCache.get(
            targetClass,
            c -> {
                Accessor a = findAccessor(c, visitedInterfaces);
                return a != null ? a : NO_ACCESSOR;
            }
        );
        return result == NO_ACCESSOR ? null : result;
    }
    
    protected Accessor findAccessor(Class<?> targetClass, Set<Class<?>> visitedInterfaces) {
        Accessor accessor = findDeclaredAccessor(targetClass);
        if (null != accessor) {
            return accessor;
        }

        if (inspectSuperclasses) {
            Class<?> superClass = targetClass.getSuperclass();
            if (null != superClass && Object.class != superClass) {
                accessor = getAccessor(superClass, visitedInterfaces);
                if (null != accessor && checkVisibility && !accessor.isVisibleTo(targetClass)) {
                    accessor = null;
                }
            }
        }

        List<Accessor> superAccessors = new ArrayList<>();
        if (accessor != null) {
            if (superClassPriority) {
                return accessor;
            } else {
                superAccessors.add(accessor);
            }
        } 
        
        if (inspectInterfaces) {
            Stream<Accessor> accessorsByInterfaces = Stream.of(targetClass.getInterfaces())
                .filter(i -> !visitedInterfaces.contains(i))
                .peek(i -> visitedInterfaces.add(i))
                .map(i -> getAccessor(i, visitedInterfaces))
                .filter(Objects::nonNull);
            
            if (checkVisibility) {
                accessorsByInterfaces = accessorsByInterfaces.filter(a -> a.isVisibleTo(targetClass));
            }
            
            List<Accessor> accessors = accessorsByInterfaces
                //.limit(2)
                .collect(Collectors.toList());
            superAccessors.addAll(accessors);
        }
        
        
        switch (superAccessors.size()) {
            case 0: return null;
            case 1: return superAccessors.get(0);
            default:
                throw new IllegalStateException(
                    "Ambiguity: class " + targetClass.getName() + " has more than one " +
                    SchedulerProvider.class.getSimpleName() + " defined by inherited accessors:\n" +
                    superAccessors.toString()
                );                    
        }
    }
    
    Accessor findDeclaredAccessor(Class<?> targetClass) {
        List<Field> ownProviderFields = Stream.of(targetClass.getDeclaredFields())
            .filter(SchedulerProviderLookup::isSchedulerSubtype)
            .filter(SchedulerProviderLookup::isAnnotatedAsProvider)
            //.limit(2)
            .collect(Collectors.toList());
        
        if (ownProviderFields.size() > 1) {
            throw new IllegalStateException(
                "Ambiguity: class " + targetClass.getName() + " declares more than one " +
                SchedulerProvider.class.getSimpleName() + " fields, " +
                "at least the following: " + ownProviderFields
            );
        }
        
        List<Method> ownProviderMethods = Stream.of(targetClass.getDeclaredMethods())
            .filter(
                 predicate(SchedulerProviderLookup::hasNoParameters)
                 .and(SchedulerProviderLookup::isNotSynthetic)            
                 .and(SchedulerProviderLookup::isSchedulerSubtype)
                 .and(SchedulerProviderLookup::isAnnotatedAsProvider)
             )
            //.limit(2)
            .collect(Collectors.toList());
        
        int fSize = ownProviderFields.size();
        int mSize = ownProviderMethods.size(); 
        if (fSize + mSize > 1) {
            throw new IllegalStateException(
                "Ambiguity: class " + targetClass.getName() + " declares more than one " +
                SchedulerProvider.class.getSimpleName() + " getters and/or fields, " +
                "at least the following causes the problem:\n " + 
                "-- fields: "+ ownProviderFields + "\n" +
                "-- getters: " + ownProviderMethods
            );
        } else if (fSize == 1) {
            Field field = ensureAccessible(ownProviderFields.get(0));
            return isStatic(field) ? new ReadClassField(field) : new ReadInstanceField(field);
        } else if (mSize == 1) {
            Method method = ensureAccessible(ownProviderMethods.get(0));
            return isStatic(method) ? new InvokeClassGetter(method) : new InvokeInstanceGetter(method);
        } else {
            return null;
        }
        
    }
    
    
    static boolean isNotSynthetic(Method method) {
        return !(method.isBridge() || method.isSynthetic()); 
    }
    
    static boolean isSchedulerSubtype(Method method) {
        return Scheduler.class.isAssignableFrom( method.getReturnType() ); 
    }
    
    static boolean hasNoParameters(Method method) {
        return method.getParameterCount() == 0; 
    }
    
    static boolean isSchedulerSubtype(Field field) {
        return Scheduler.class.isAssignableFrom( field.getType() ); 
    }
    
    static boolean isAnnotatedAsProvider(AnnotatedElement member) {
        return member.getAnnotation(SchedulerProvider.class) != null; 
    }
    
    private static <M extends Member> boolean isStatic(M target) {
        return (target.getModifiers() & Modifier.STATIC) != 0;
    }
    
    private static <M extends Member> M ensureAccessible(M target) {
        if ((target.getModifiers() & Modifier.PUBLIC) == 0 || (target.getDeclaringClass().getModifiers() & Modifier.PUBLIC) == 0) {
            AccessibleObject ao = (AccessibleObject)target;
            ao.setAccessible(true);
        }
        return target;
    }
    
    private static <T> Predicate<T> predicate(Predicate<T> closure) {
        return closure;
    }

}