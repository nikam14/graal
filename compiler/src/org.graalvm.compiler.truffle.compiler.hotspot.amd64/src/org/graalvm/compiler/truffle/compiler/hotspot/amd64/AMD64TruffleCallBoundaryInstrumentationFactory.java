/*
 * Copyright (c) 2013, 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.truffle.compiler.hotspot.amd64;

import static jdk.vm.ci.hotspot.HotSpotCallingConventionType.JavaCall;
import static org.graalvm.compiler.hotspot.meta.HotSpotHostForeignCallsProvider.Z_FIELD_BARRIER;

import org.graalvm.compiler.asm.Label;
import org.graalvm.compiler.asm.amd64.AMD64Address;
import org.graalvm.compiler.asm.amd64.AMD64Assembler.ConditionFlag;
import org.graalvm.compiler.asm.amd64.AMD64MacroAssembler;
import org.graalvm.compiler.core.common.CompressEncoding;
import org.graalvm.compiler.core.common.spi.ForeignCallLinkage;
import org.graalvm.compiler.hotspot.GraalHotSpotVMConfig;
import org.graalvm.compiler.hotspot.HotSpotGraalRuntime;
import org.graalvm.compiler.hotspot.amd64.AMD64HotSpotBackend;
import org.graalvm.compiler.hotspot.amd64.AMD64HotSpotZBarrierSetLIRGenerator;
import org.graalvm.compiler.hotspot.meta.HotSpotRegistersProvider;
import org.graalvm.compiler.lir.amd64.AMD64FrameMap;
import org.graalvm.compiler.lir.amd64.AMD64Move;
import org.graalvm.compiler.lir.asm.CompilationResultBuilder;
import org.graalvm.compiler.lir.asm.EntryPointDecorator;
import org.graalvm.compiler.serviceprovider.ServiceProvider;
import org.graalvm.compiler.truffle.compiler.hotspot.TruffleCallBoundaryInstrumentationFactory;
import org.graalvm.compiler.truffle.compiler.hotspot.TruffleEntryPointDecorator;

import jdk.vm.ci.amd64.AMD64;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;

@ServiceProvider(TruffleCallBoundaryInstrumentationFactory.class)
public class AMD64TruffleCallBoundaryInstrumentationFactory extends TruffleCallBoundaryInstrumentationFactory {

    @Override
    public EntryPointDecorator create(MetaAccessProvider metaAccess, GraalHotSpotVMConfig config, HotSpotRegistersProvider registers) {
        return new TruffleEntryPointDecorator(metaAccess, config, registers) {
            @Override
            public void emitEntryPoint(CompilationResultBuilder crb) {
                AMD64MacroAssembler masm = (AMD64MacroAssembler) crb.asm;
                Register thisRegister = crb.codeCache.getRegisterConfig().getCallingConventionRegisters(JavaCall, JavaKind.Object).get(0);
                Register spillRegister = AMD64.r10;
                Label doProlog = new Label();
                int pos = masm.position();

                AMD64Address address = new AMD64Address(thisRegister, installedCodeOffset);
                if (config.useCompressedOops) {
                    // First instruction must be at least 5 bytes long to be safe for
                    // patching
                    masm.movl(spillRegister, address, true);
                    assert masm.position() - pos >= AMD64HotSpotBackend.PATCHED_VERIFIED_ENTRY_POINT_INSTRUCTION_SIZE;
                    CompressEncoding encoding = config.getOopEncoding();
                    Register heapBaseRegister = AMD64Move.UncompressPointerOp.hasBase(encoding) ? registers.getHeapBaseRegister() : Register.None;
                    AMD64Move.UncompressPointerOp.emitUncompressCode(masm, spillRegister, encoding.getShift(), heapBaseRegister, true);
                } else {
                    // First instruction must be at least 5 bytes long to be safe for
                    // patching
                    masm.movq(spillRegister, address, true);
                    assert masm.position() - pos >= AMD64HotSpotBackend.PATCHED_VERIFIED_ENTRY_POINT_INSTRUCTION_SIZE;
                    if (config.gc == HotSpotGraalRuntime.HotSpotGC.Z) {
                        ForeignCallLinkage callTarget = crb.providers.getForeignCalls().lookupForeignCall(Z_FIELD_BARRIER);
                        AMD64FrameMap frameMap = (AMD64FrameMap) crb.frameMap;
                        AMD64HotSpotZBarrierSetLIRGenerator.emitBarrier(crb, masm, null, spillRegister, config, callTarget, address, null, frameMap);
                    }
                }
                masm.movq(spillRegister, new AMD64Address(spillRegister, entryPointOffset));
                masm.testqAndJcc(spillRegister, spillRegister, ConditionFlag.Equal, doProlog, true);
                masm.jmp(spillRegister);
                masm.bind(doProlog);
            }
        };
    }

    @Override
    public String getArchitecture() {
        return "AMD64";
    }
}
