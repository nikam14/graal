/*
 * Copyright (c) 2021, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.methodhandles;

import org.graalvm.compiler.debug.GraalError;
import com.oracle.svm.core.annotate.AlwaysInline;
// Checkstyle: stop
import sun.invoke.util.Wrapper;
// Checkstyle: resume

public class MethodHandleUtils {
    @AlwaysInline("constant fold as much as possible in signature polymorphic wrappers")
    public static long longUnbox(Object retVal, Target_java_lang_invoke_MethodHandle methodHandle) {
        return longUnbox(retVal, methodHandle.type.returnType());
    }

    @AlwaysInline("constant fold as much as possible in signature polymorphic wrappers")
    public static long longUnbox(Object retVal, Target_java_lang_invoke_MemberName memberName) {
        return longUnbox(retVal, memberName.getMethodType().returnType());
    }

    @AlwaysInline("constant fold as much as possible in signature polymorphic wrappers")
    private static long longUnbox(Object retVal, Class<?> returnType) {
        switch (Wrapper.forPrimitiveType(returnType)) {
            case BOOLEAN:
                return (boolean) retVal ? 1L : 0L;
            case BYTE:
                return (byte) retVal;
            case SHORT:
                return (short) retVal;
            case CHAR:
                return (char) retVal;
            case INT:
                return (int) retVal;
            case LONG:
                return (long) retVal;
            default:
                throw new GraalError("Unexpected type for unbox function: " + returnType);
        }
    }

    @AlwaysInline("constant fold as much as possible in signature polymorphic wrappers")
    public static int intUnbox(Object retVal, Target_java_lang_invoke_MethodHandle methodHandle) {
        return intUnbox(retVal, methodHandle.type.returnType());
    }

    @AlwaysInline("constant fold as much as possible in signature polymorphic wrappers")
    public static int intUnbox(Object retVal, Target_java_lang_invoke_MemberName memberName) {
        return intUnbox(retVal, memberName.getMethodType().returnType());
    }

    @AlwaysInline("constant fold as much as possible in signature polymorphic wrappers")
    public static int intUnbox(Object retVal, Class<?> returnType) {
        switch (Wrapper.forPrimitiveType(returnType)) {
            case BOOLEAN:
                return (boolean) retVal ? 1 : 0;
            case BYTE:
                return (byte) retVal;
            case SHORT:
                return (short) retVal;
            case CHAR:
                return (char) retVal;
            case INT:
                return (int) retVal;
            default:
                throw new GraalError("Unexpected type for unbox function: " + returnType);
        }
    }

    @AlwaysInline("constant fold as much as possible in signature polymorphic wrappers")
    public static short shortUnbox(Object retVal, Target_java_lang_invoke_MethodHandle methodHandle) {
        return shortUnbox(retVal, methodHandle.type.returnType());
    }

    @AlwaysInline("constant fold as much as possible in signature polymorphic wrappers")
    public static short shortUnbox(Object retVal, Target_java_lang_invoke_MemberName memberName) {
        return shortUnbox(retVal, memberName.getMethodType().returnType());
    }

    @AlwaysInline("constant fold as much as possible in signature polymorphic wrappers")
    public static short shortUnbox(Object retVal, Class<?> returnType) {
        switch (Wrapper.forPrimitiveType(returnType)) {
            case BOOLEAN:
                return (boolean) retVal ? (short) 1 : (short) 0;
            case BYTE:
                return (byte) retVal;
            case SHORT:
                return (short) retVal;
            default:
                throw new GraalError("Unexpected type for unbox function: " + returnType);
        }
    }
}
