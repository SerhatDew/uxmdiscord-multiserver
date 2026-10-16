package com.uxplima.discordsync.multiserver.agent;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public final class LicenseBypassAgent {
    private LicenseBypassAgent() {}

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[uxmDiscordSync-HookAgent] Activating runtime hook agent...");
        try {
            inst.addTransformer(new ClassFileTransformer() {
                @Override
                public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                        ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                    if ("com/uxplima/discordsync/UXMDiscordSync".equals(className)) {
                        try {
                            System.out.println("[uxmDiscordSync-HookAgent] Bytecode rewriting UXMDiscordSync.initializeLicense() -> return true");
                            ClassReader reader = new ClassReader(classfileBuffer);
                            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                            ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                                @Override
                                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                                    if ("initializeLicense".equals(name) && "()Z".equals(descriptor)) {
                                        return new MethodVisitor(Opcodes.ASM9, mv) {
                                            @Override
                                            public void visitCode() {
                                                super.visitCode();
                                                super.visitInsn(Opcodes.ICONST_1);
                                                super.visitInsn(Opcodes.IRETURN);
                                            }
                                        };
                                    }
                                    return mv;
                                }
                            };
                            reader.accept(visitor, 0);
                            return writer.toByteArray();
                        } catch (Throwable t) {
                            System.err.println("[uxmDiscordSync-HookAgent] Error transforming UXMDiscordSync: " + t.getMessage());
                            t.printStackTrace();
                        }
                    }

                    if ("com/uxplima/discordsync/database/impl/MySQLDatabase".equals(className)) {
                        try {
                            System.out.println("[uxmDiscordSync-HookAgent] Bytecode patching MySQLDatabase characterEncoding utf8mb4 -> UTF-8");
                            ClassReader reader = new ClassReader(classfileBuffer);
                            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                            ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                                @Override
                                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                                    if ("connect".equals(name)) {
                                        return new MethodVisitor(Opcodes.ASM9, mv) {
                                            @Override
                                            public void visitLdcInsn(Object value) {
                                                if ("utf8mb4".equals(value)) {
                                                    super.visitLdcInsn("UTF-8");
                                                } else {
                                                    super.visitLdcInsn(value);
                                                }
                                            }
                                        };
                                    }
                                    return mv;
                                }
                            };
                            reader.accept(visitor, 0);
                            return writer.toByteArray();
                        } catch (Throwable t) {
                            System.err.println("[uxmDiscordSync-HookAgent] Error patching MySQLDatabase: " + t.getMessage());
                            t.printStackTrace();
                        }
                    }

                    return null;
                }
            });
            System.out.println("[uxmDiscordSync-HookAgent] Hook agent successfully installed.");
        } catch (Throwable t) {
            System.err.println("[uxmDiscordSync-HookAgent] Failed to register transformer: " + t.getMessage());
            t.printStackTrace();
        }
    }
}
